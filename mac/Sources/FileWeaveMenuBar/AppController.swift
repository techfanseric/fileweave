import AppKit
import SwiftUI
import Combine
import FileWeaveMacKit

/// 菜单栏控制器：FileWeave 服务（bridge.py 监管）+ 录音面板的合并宿主。
///
/// 自己用 `NSStatusItem` 而不是 SwiftUI 的 `MenuBarExtra`，
/// 因为只有自己接管点击才能区分：
/// - 左键            → 展开完整面板（NSPopover：FileWeave 状态段 + 录音页）
/// - 右键 / Option+左键 → 展开精简菜单（NSMenu）
@MainActor
final class AppController: NSObject, NSApplicationDelegate, NSPopoverDelegate {

    private let app = AppState()
    private let bridge = BridgeProcess()
    private var statusItem: NSStatusItem!
    private var popover: NSPopover!
    private var compactMenu: NSMenu!
    private var loginMenuItem: NSMenuItem?
    private var cancellables = Set<AnyCancellable>()

    // MARK: 启动

    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.setActivationPolicy(.accessory)

        makeStatusItem()
        makePopover()
        // 必须在建菜单前扫一次：菜单项的勾选状态要按真实注册记录填，
        // 而系统登录项里被手动关掉这种事，本机是不知道的
        LoginItem.shared.refresh()
        rebuildCompactMenu()
        observeApp()
        observeBridge()

        app.start()
        bridge.start()

        // 测试钩子：FILEWEAVE_LOGIN_ITEM=on|off|status|service|agent|unload
        // 菜单栏里的勾选项没法用合成点击驱动，这条路径要能单独验。
        // service / agent 用来逼出指定那条实现：
        // 原生那条平时很难自然失败，不主动跑一次就等于没验过。
        if let want = ProcessInfo.processInfo.environment["FILEWEAVE_LOGIN_ITEM"] {
            Task { @MainActor in
                let li = LoginItem.shared
                switch want {
                case "on": li.setEnabled(true)
                case "off": li.setEnabled(false)
                case "service": li.setEnabled(true, prefer: .service)
                case "agent": li.setEnabled(true, prefer: .launchAgent)
                case "unload": li.setEnabled(false)
                default: li.refresh()
                }
                NSLog("LOGIN ITEM TEST [%@] enabled=%@ backend=%@ error=%@",
                      want, li.isEnabled.description, li.backend.rawValue,
                      li.lastError ?? "<无>")
                exit(0)
            }
        }

        // 自动化截图用：FILEWEAVE_AUTO_OPEN=panel|menu，启动后自动展开对应界面。
        // 平时不设这个变量，行为完全不变。
        switch ProcessInfo.processInfo.environment["FILEWEAVE_AUTO_OPEN"] {
        case "panel":
            DispatchQueue.main.asyncAfter(deadline: .now() + 2) { [weak self] in
                // 后台启动时应用不一定是前台，popover 会弹不出来
                NSApp.activate(ignoringOtherApps: true)
                self?.openPanel()
            }
        case "trashpanel":
            // 自动化截图用：启动后直接停在手机回收站那一屏
            DispatchQueue.main.asyncAfter(deadline: .now() + 2) { [weak self] in
                NSApp.activate(ignoringOtherApps: true)
                self?.app.setTrashView(true)
                self?.openPanel()
            }
        case "menu":
            DispatchQueue.main.asyncAfter(deadline: .now() + 2) { [weak self] in
                NSApp.activate(ignoringOtherApps: true)
                self?.showCompactMenu()
            }
        case let mode where mode == "transcribe" || mode == "read":
            // 自动化测试：对最新一条录音跑完整转录流程
            DispatchQueue.main.asyncAfter(deadline: .now() + 3) { [weak self] in
                guard let self, let rec = self.app.recordings.first else { return }
                // "read" 模式复用上次已生成的转录，只为截图看渲染
                if mode == "read", self.app.transcript(for: rec) != nil {
                    self.app.openTranscript(rec)
                    self.openPanel()
                    return
                }
                Task {
                    await self.app.transcribe(rec)
                    NSLog("E2E transcript -> \(self.app.transcriptFolder.path)")
                    DispatchQueue.main.async { self.openPanel() }
                }
            }
        case "trash":
            // 自动化测试：按 FILEWEAVE_TRASH_MATCH 指定对象名，验证删除与还原闭环
            DispatchQueue.main.asyncAfter(deadline: .now() + 4) { [weak self] in
                guard let self else { return }
                let want = ProcessInfo.processInfo.environment["FILEWEAVE_TRASH_MATCH"] ?? ""
                guard let rec = self.app.recordings.first(where: { $0.subject.contains(want) }) else {
                    NSLog("TRASH TEST: 没找到匹配「%@」的录音", want)
                    return
                }
                NSLog("TRASH TEST: 目标 %@ (%@)", rec.subject, rec.remotePath)
                Task {
                    await self.app.trash(rec, alsoRemoveLocal: false)
                    // 必须重新从回收站列表里取：原来的 rec 是删除前的快照，
                    // trashPath 还是 nil，拿它去还原会被正确地拒绝掉
                    let moved = self.app.trashRecordings.first { $0.id == rec.id }
                    NSLog("TRASH TEST: 删除完成，正常 %d 条 / 回收站 %d 条，回收站里那条的路径=%@",
                          self.app.recordings.count, self.app.trashRecordings.count,
                          moved?.trashPath ?? "<没进回收站>")
                    // 立刻还原，验证回滚路径
                    try? await Task.sleep(nanoseconds: 1_500_000_000)
                    guard let again = self.app.trashRecordings.first(where: { $0.id == rec.id }) else {
                        NSLog("TRASH TEST: 失败，还原前就找不到这条了")
                        return
                    }
                    await self.app.restoreFromTrash(again)
                    NSLog("TRASH TEST: 还原完成，正常 %d 条 / 回收站 %d 条",
                          self.app.recordings.count, self.app.trashRecordings.count)
                }
            }
        case "search":
            // 自动化测试：按 FILEWEAVE_SEARCH 预设搜索词后展开面板
            DispatchQueue.main.asyncAfter(deadline: .now() + 9) { [weak self] in
                guard let self else { return }
                self.app.searchText = ProcessInfo.processInfo.environment["FILEWEAVE_SEARCH"] ?? ""
                NSLog("SEARCH TEST 「%@」→ 命中 %d 条，可搜正文 %d 条",
                      self.app.activeQuery, self.app.filteredCount, self.app.searchableCount)
                self.openPanel()
            }
        default:
            break
        }
    }

    func applicationWillTerminate(_ notification: Notification) {
        bridge.shutdown()
    }

    private func observeApp() {
        // objectWillChange 在属性变更「之前」触发，所以推到下一个 runloop 再刷新
        app.objectWillChange
            .sink { [weak self] _ in
                DispatchQueue.main.async { self?.refreshStatusTitle() }
            }
            .store(in: &cancellables)
    }

    /// bridge 的状态行变了（已就绪 / 恢复中 / 等待局域网访问）：
    /// 右键菜单如果此刻挂着就直接改那一项，状态栏 tooltip 同步换。
    private func observeBridge() {
        bridge.objectWillChange
            .sink { [weak self] _ in
                DispatchQueue.main.async { self?.refreshStatusTitle() }
            }
            .store(in: &cancellables)
    }

    private func refreshStatusTitle() {
        statusItem.button?.toolTip = bridge.statusToolTip
            ?? "FileWeave · 左键打开面板，右键精简菜单"
    }

    // MARK: 状态栏图标

    private func makeStatusItem() {
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)

        guard let button = statusItem.button else { return }
        // 沿用 FileWeave 原来的图标：双向箭头 + " FW"
        button.image = NSImage(systemSymbolName: "arrow.left.arrow.right",
                               accessibilityDescription: "FileWeave")
        button.title = " FW"
        button.target = self
        button.action = #selector(statusItemClicked)
        // 接管左右键，自己决定弹什么
        button.sendAction(on: [.leftMouseUp, .rightMouseUp])
        button.toolTip = "FileWeave · 左键打开面板，右键精简菜单"
    }

    // MARK: 完整面板（左键）

    private func makePopover() {
        popover = NSPopover()
        popover.behavior = .transient
        popover.animates = false
        // transient 模式下点面板外部会自动关闭，必须靠 delegate 才知道要把轮询调回去
        popover.delegate = self
        let host = NSHostingController(
            rootView: FileWeavePanelView(bridge: bridge).environmentObject(app)
        )
        popover.contentViewController = host
    }

    /// 让面板成为 key 窗口，否则里面的菜单要点两次才开：
    /// 窗口不是 key 时，第一次点击被系统拿去「激活窗口」，第二次才轮到菜单。
    ///
    /// 两个手段一起上，缺一不可：
    ///
    /// 1. `becomesKeyOnlyIfNeeded = false` —— 这是 NSPanel 上真正对口的开关。
    ///    NSPopover 自己没这个属性，但它的窗口就是个 NSPanel，显示之后能拿到。
    ///
    /// 2. 补几次 `makeKey()` —— 光设开关不够，因为**刚打开那一刻窗口还没就绪**。
    ///    实测 show() 之后当场调用 makeKey 等于没做（view.window 还是 nil），
    ///    推一个 runloop 勉强够，但「打开后立刻点菜单」这一下还是会闪一下就关。
    ///    连着试几次才稳：0 / 30 / 90 毫秒各来一次。
    private func makePanelReadyForMenus() {
        for delay in [0.0, 0.03, 0.09] {
            DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in
                guard let window = self?.popover.contentViewController?.view.window else { return }
                if let panel = window as? NSPanel {
                    panel.becomesKeyOnlyIfNeeded = false
                }
                window.makeKey()
            }
        }
    }

    private func openPanel() {
        guard let button = statusItem.button else { return }

        // 菜单栏 App 是 LSUIElement（没有 Dock 图标），点状态项**不会**激活它。
        // 于是面板里的菜单第一次点击被系统拿去「激活本 App」，第二次才轮到菜单 ——
        // 表现就是闪一下又关掉。
        //
        // 这比「面板窗口没拿到 key」更靠前：App 压根不是活动应用时，
        // 窗口 makeKey 了也没用，第一次点击就是被系统拿去切换活动 App 的。
        // 打开面板本来就是要跟它交互，先把自己提上来。
        NSApp.activate(ignoringOtherApps: true)

        app.isPanelVisible = true
        app.notifyPanelOpened()
        popover.show(relativeTo: button.bounds, of: button, preferredEdge: .minY)
        makePanelReadyForMenus()

        // 搜索框抢焦点是另一回事，由 MenuView 顶部的焦点占位符管，别和这里混。
        // 这里刻意不做任何焦点补救（makeFirstResponder / initialFirstResponder）。
        // 打开面板不该自动聚焦搜索框，但手段是在 SwiftUI 视图树里放一个焦点占位符，
        // 见 MenuView.listView 顶部 —— 事后抹焦点只能抹掉光标，抹不掉那一下闪烁。
    }

    private func closePanel() {
        app.isPanelVisible = false
        popover.performClose(nil)
    }

    // MARK: 精简菜单（右键 / Option+左键）

    func popoverDidClose(_ notification: Notification) {
        app.isPanelVisible = false
    }

    private func rebuildCompactMenu() {
        let m = NSMenu()
        m.autoenablesItems = false

        // 状态行和右键时看到的服务状态永远是同一份（都来自 BridgeProcess）
        let status = NSMenuItem(title: bridge.statusLine, action: nil, keyEquivalent: "")
        status.isEnabled = false
        m.addItem(status)

        m.addItem(.separator())

        let shared = NSMenuItem(title: "打开共享文件夹（手机可读取）",
                                action: #selector(compactOpenShared),
                                keyEquivalent: "")
        shared.target = self
        m.addItem(shared)

        let inbox = NSMenuItem(title: "打开手机传来的文件",
                               action: #selector(compactOpenInbox),
                               keyEquivalent: "")
        inbox.target = self
        m.addItem(inbox)

        let log = NSMenuItem(title: "查看服务日志",
                             action: #selector(compactOpenLog),
                             keyEquivalent: "")
        log.target = self
        m.addItem(log)

        m.addItem(.separator())

        // 用系统自带的勾选态表达开关，不要做成「登录时启动：开/关」那种文字
        let login = NSMenuItem(title: "登录时启动",
                               action: #selector(compactToggleLoginItem),
                               keyEquivalent: "")
        login.target = self
        login.state = LoginItem.shared.isEnabled ? .on : .off
        loginMenuItem = login
        m.addItem(login)

        m.addItem(.separator())

        let quit = NSMenuItem(title: "退出 FileWeave", action: #selector(compactQuit),
                              keyEquivalent: "q")
        quit.target = self
        m.addItem(quit)

        compactMenu = m
    }

    private func showCompactMenu() {
        rebuildCompactMenu()
        // 临时挂 menu 让系统接管这一次点击，弹完立刻摘掉，
        // 否则后续左键也会走系统菜单而不是我们自己的面板
        statusItem.menu = compactMenu
        statusItem.button?.performClick(nil)
        statusItem.menu = nil
    }

    // MARK: 点击分发

    @objc private func statusItemClicked() {
        guard let event = NSApp.currentEvent else {
            togglePanel()
            return
        }
        let isRightClick = event.type == .rightMouseUp
        let isOption = event.modifierFlags.contains(.option)

        if isRightClick || isOption {
            closePanel()
            showCompactMenu()
        } else {
            togglePanel()
        }
    }

    private func togglePanel() {
        if popover.isShown { closePanel() } else { openPanel() }
    }

    // MARK: 精简菜单动作

    @objc private func compactOpenShared() { bridge.openShared() }
    @objc private func compactOpenInbox() { bridge.openInbox() }
    @objc private func compactOpenLog() { bridge.openLog() }

    @objc private func compactToggleLoginItem() {
        let li = LoginItem.shared
        li.toggle()
        if let err = li.lastError {
            // 注册失败必须当场说出来。静默失败的话，
            // 用户下次开机发现图标没出来，会以为是 app 坏了
            app.notify("设置开机启动失败：\(err)")
        }
        // 不能重建菜单：菜单正挂在屏幕上，重建出来的那份没人看得见。
        // 直接改当前这个 item 的勾选态。
        loginMenuItem?.state = li.isEnabled ? .on : .off
    }

    @objc private func compactQuit() {
        NSApp.terminate(nil)
    }
}
