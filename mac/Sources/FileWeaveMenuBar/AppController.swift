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
final class AppController: NSObject, NSApplicationDelegate, NSPopoverDelegate, NSWindowDelegate {

    private let app = AppState()
    private let bridge = BridgeProcess()
    private let workspace = WorkspaceSession()
    private var statusItem: NSStatusItem!
    private var popover: NSPopover!
    private var resizePanelContent: ((NSSize) -> Void)?
    private var mainWindow: NSWindow?
    private var languageObserver: NSObjectProtocol?
    private var compactMenu: NSMenu!
    private var clipboardTimer: Timer?
    private var clipboardTask: Task<Void, Never>?
    private var clipboardChange = NSPasteboard.general.changeCount
    private var loginMenuItem: NSMenuItem?
    private var showHiddenMenuItem: NSMenuItem?
    private var cancellables = Set<AnyCancellable>()
    private var displayedStatus: MenuBarIcon.Status?

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

        buildApplicationMenu()
        app.start()
        #if DEBUG
        let isQA = ProcessInfo.processInfo.environment["FILEWEAVE_QA"] == "1"
            || Bundle.main.bundleIdentifier == "org.fileweave.mac.qa"
        workspace.setPreviewDestination(ProcessInfo.processInfo.environment["FILEWEAVE_QA_PAGE"] ?? "files")
        #else
        let isQA = false
        #endif
        if !isQA { bridge.start() }
        #if DEBUG
        if isQA, ProcessInfo.processInfo.environment["FILEWEAVE_QA_FRAME_TRACE"] != nil {
            DispatchQueue.main.asyncAfter(deadline: .now() + 2) { [weak self] in self?.runPanelFrameTrace() }
        }
        if isQA, ProcessInfo.processInfo.environment["FILEWEAVE_QA_SMOKE"] == "1" {
            DispatchQueue.main.asyncAfter(deadline: .now() + 2) { [weak self] in self?.runQASmoke() }
        }
        #endif
        if !isQA { clipboardTimer = Timer.scheduledTimer(withTimeInterval: 0.2, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.forwardClipboardChange() }
        }
        clipboardTimer?.tolerance = 0.05 }

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
                      li.lastError ?? L("<无>"))
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
                    NSLog(L("TRASH TEST: 没找到匹配「%@」的录音"), want)
                    return
                }
                NSLog(L("TRASH TEST: 目标 %@ (%@)"), rec.subject, rec.remotePath)
                Task {
                    await self.app.trash(rec, alsoRemoveLocal: false)
                    // 必须重新从回收站列表里取：原来的 rec 是删除前的快照，
                    // trashPath 还是 nil，拿它去还原会被正确地拒绝掉
                    let moved = self.app.trashRecordings.first { $0.id == rec.id }
                    NSLog(L("TRASH TEST: 删除完成，正常 %d 条 / 回收站 %d 条，回收站里那条的路径=%@"),
                          self.app.recordings.count, self.app.trashRecordings.count,
                          moved?.trashPath ?? L("<没进回收站>"))
                    // 立刻还原，验证回滚路径
                    try? await Task.sleep(nanoseconds: 1_500_000_000)
                    guard let again = self.app.trashRecordings.first(where: { $0.id == rec.id }) else {
                        NSLog(L("TRASH TEST: 失败，还原前就找不到这条了"))
                        return
                    }
                    await self.app.restoreFromTrash(again)
                    NSLog(L("TRASH TEST: 还原完成，正常 %d 条 / 回收站 %d 条"),
                          self.app.recordings.count, self.app.trashRecordings.count)
                }
            }
        case "search":
            // 自动化测试：按 FILEWEAVE_SEARCH 预设搜索词后展开面板
            DispatchQueue.main.asyncAfter(deadline: .now() + 9) { [weak self] in
                guard let self else { return }
                self.app.searchText = ProcessInfo.processInfo.environment["FILEWEAVE_SEARCH"] ?? ""
                NSLog(L("SEARCH TEST 「%@」→ 命中 %d 条，可搜正文 %d 条"),
                      self.app.activeQuery, self.app.filteredCount, self.app.searchableCount)
                self.openPanel()
            }
        default:
            break
        }
    }

    private func forwardClipboardChange() {
        let board = NSPasteboard.general
        guard board.changeCount != clipboardChange else { return }
        clipboardChange = board.changeCount
        let concealed = NSPasteboard.PasteboardType("org.nspasteboard.ConcealedType")
        guard board.availableType(from: [concealed, .fileURL]) == nil,
              let text = board.string(forType: .string), !text.isEmpty else { return }
        clipboardTask?.cancel()
        clipboardTask = Task { await app.sendClipboardToPhone(text) }
    }

    func applicationWillTerminate(_ notification: Notification) {
        clipboardTimer?.invalidate(); clipboardTask?.cancel()
        bridge.shutdown()
    }

    private func observeApp() {
        app.objectWillChange
            .sink { [weak self] _ in
                DispatchQueue.main.async { self?.refreshStatusTitle() }
            }
            .store(in: &cancellables)
        FileVisibilitySettings.shared.$showsHiddenFiles.sink { [weak self] value in
            DispatchQueue.main.async { self?.showHiddenMenuItem?.state = value ? .on : .off }
        }.store(in: &cancellables)
    }

    /// bridge 的状态行变了（已就绪 / 恢复中 / 等待局域网访问）：
    /// 右键菜单如果此刻挂着就直接改那一项，状态栏 tooltip 同步换。
    private func observeBridge() {
        bridge.$highlightID.dropFirst().sink { [weak self] id in
            guard !id.isEmpty else { return }
            DispatchQueue.main.async { self?.openPanel() }
        }.store(in: &cancellables)
        bridge.objectWillChange
            .sink { [weak self] _ in
                DispatchQueue.main.async { self?.refreshStatusTitle() }
            }
            .store(in: &cancellables)
    }

    private var menuBarStatus: MenuBarIcon.Status {
        MenuBarIcon.status(link: app.link, bridge: bridge.availability,
                           awaitingApproval: bridge.showingApproval,
                           hasActiveTask: app.hasActiveTask)
    }

    /// 只在状态类别变化时重绘图标；轮询和进度更新仍刷新悬浮说明。
    private func refreshStatusTitle() {
        guard let button = statusItem.button else { return }
        let status = menuBarStatus
        if displayedStatus != status {
            button.image = MenuBarIcon.image(status: status)
            displayedStatus = status
        }
        var tip = bridge.statusToolTip ?? bridge.statusLine
        if bridge.showingApproval { tip += L("\n请核对配对码并处理确认弹窗") }
        if let task = app.activeTaskTitle { tip += "\n\(task)" }
        if case .ready(let info) = app.link {
            tip += L("\n已连接：{0}（{1}）", info.name, info.detail)
        } else {
            tip += "\n\(app.link.title)"
            if let hint = app.linkHint { tip += "：\(hint)" }
        }
        tip += L("\n左键打开面板，右键精简菜单")
        button.toolTip = tip
    }

    // MARK: 状态栏图标

    private func makeStatusItem() {
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)

        guard let button = statusItem.button else { return }
        button.image = MenuBarIcon.image(status: menuBarStatus)
        displayedStatus = menuBarStatus
        button.title = ""
        button.target = self
        button.action = #selector(statusItemClicked)
        // 接管左右键，自己决定弹什么
        button.sendAction(on: [.leftMouseUp, .rightMouseUp])
        button.toolTip = L("FileWeave · 左键打开面板，右键精简菜单")
    }

    // MARK: 完整面板（左键）

    private func makePopover() {
        popover = NSPopover()
        popover.behavior = .transient
        popover.animates = false
        popover.delegate = self
        let initialSize = boundedPanelSize()
        let appState = app
        let bridgeProcess = bridge
        let sharedWorkspace = workspace
        let openWindow: () -> Void = { [weak self] in self?.openMainWindow() }
        func rootView(size: NSSize) -> some View {
            FileWeavePopoverView(bridge: bridgeProcess, workspace: sharedWorkspace,
                                panelSize: size, openWindow: openWindow).environmentObject(appState)
        }
        let host = NSHostingController(rootView: rootView(size: initialSize))
        // AppKit owns the bounded content size. SwiftUI's ideal List size must not
        // compete with it, and NSPopover alone owns the anchored window position.
        host.sizingOptions = []
        host.safeAreaRegions = []
        host.view.setFrameSize(initialSize)
        resizePanelContent = { [weak host] size in
            host?.rootView = rootView(size: size)
            host?.view.setFrameSize(size)
        }
        popover.contentViewController = host
        popover.contentSize = initialSize
        NotificationCenter.default.publisher(for: NSApplication.didChangeScreenParametersNotification)
            .sink { [weak self] _ in self?.updatePanelSize() }
            .store(in: &cancellables)
    }

    private func boundedPanelSize() -> NSSize {
        let available = statusItem.button?.window?.screen?.visibleFrame
            ?? NSScreen.main?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1000, height: 800)
        return MenuPanelGeometry.contentSize(available: available)
    }

    private func updatePanelSize() {
        let size = boundedPanelSize()
        guard popover.contentSize != size else { return }
        resizePanelContent?(size)
        popover.contentSize = size
    }

    /// Configure focus once the native popover window exists, rather than racing
    /// its opening with timed makeKey/setFrame retries.
    private func preparePanelForInput() {
        guard let popover, popover.isShown, let window = popover.contentViewController?.view.window else { return }
        if let panel = window as? NSPanel { panel.becomesKeyOnlyIfNeeded = false }
        if !window.isKeyWindow { window.makeKey() }
    }

    func popoverDidShow(_ notification: Notification) { preparePanelForInput() }
    func applicationDidBecomeActive(_ notification: Notification) { preparePanelForInput() }

    private func openPanel() {
        workspace.showFiles()
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
        updatePanelSize()
        popover.show(relativeTo: button.bounds, of: button, preferredEdge: .minY)
        if let path = ProcessInfo.processInfo.environment["FILEWEAVE_PANEL_SNAPSHOT"] {
            DispatchQueue.main.asyncAfter(deadline: .now() + 6) { [weak self] in
                guard let self, let hosting = self.popover.contentViewController?.view else { return }
                let view = hosting.window?.contentView?.superview ?? hosting
                guard let bitmap = view.bitmapImageRepForCachingDisplay(in: view.bounds) else { return }
                view.cacheDisplay(in: view.bounds, to: bitmap)
                if let data = bitmap.representation(using: .png, properties: [:]) {
                    try? data.write(to: URL(fileURLWithPath: path))
                }
                NSLog("PANEL_CAPTURE key=%@ appActive=%@ content=%@", (NSApp.keyWindow === hosting.window).description, NSApp.isActive.description, NSStringFromSize(self.popover.contentSize))
                if let anchorWindow = button.window, let window = view.window {
                    let anchor = anchorWindow.convertToScreen(button.convert(button.bounds, to: nil))
                    NSLog("PANEL anchor=%@ screen=%@ frame=%@ transient=%@", NSStringFromRect(anchor), NSStringFromRect(anchorWindow.screen?.visibleFrame ?? .zero), NSStringFromRect(window.frame), (self.popover.behavior == .transient).description)
                }
            }
        }

        // 搜索框抢焦点是另一回事，由 MenuView 顶部的焦点占位符管，别和这里混。
        // 这里刻意不做任何焦点补救（makeFirstResponder / initialFirstResponder）。
        // 打开面板不该自动聚焦搜索框，但手段是在 SwiftUI 视图树里放一个焦点占位符，
        // 见 MenuView.listView 顶部 —— 事后抹焦点只能抹掉光标，抹不掉那一下闪烁。
    }

    private func closePanel() {
        popover.performClose(nil)
        updateWorkspaceVisibility()
    }

    private func updateWorkspaceVisibility() {
        app.isPanelVisible = mainWindow?.isVisible == true || popover.isShown
    }

    // MARK: 精简菜单（右键 / Option+左键）

    func popoverDidClose(_ notification: Notification) {
        app.isPanelVisible = mainWindow?.isVisible == true
    }

    private func rebuildCompactMenu() {
        let m = NSMenu()
        m.autoenablesItems = false

        // 状态行和右键时看到的服务状态永远是同一份（都来自 BridgeProcess）
        let status = NSMenuItem(title: bridge.statusLine, action: nil, keyEquivalent: "")
        status.isEnabled = false
        m.addItem(status)

        // 没连上手机时如实说一声，别让菜单看起来一切正常。
        // 详细原因和指引放 tooltip / 面板提示区，菜单行保持一行放得下。
        if !app.link.isReady {
            let link = NSMenuItem(title: app.link.title, action: nil, keyEquivalent: "")
            link.isEnabled = false
            m.addItem(link)
        }

        m.addItem(.separator())
        let open = NSMenuItem(title: L("打开 FileWeave"), action: #selector(openMainWindow), keyEquivalent: "o")
        open.target = self
        m.addItem(open)

        let shared = NSMenuItem(title: L("打开共享文件夹（手机可读取）"),
                                action: #selector(compactOpenShared),
                                keyEquivalent: "")
        shared.target = self
        m.addItem(shared)

        let inbox = NSMenuItem(title: L("打开手机传来的文件"),
                               action: #selector(compactOpenInbox),
                               keyEquivalent: "")
        inbox.target = self
        m.addItem(inbox)

        let log = NSMenuItem(title: L("查看服务日志"),
                             action: #selector(compactOpenLog),
                             keyEquivalent: "")
        log.target = self
        m.addItem(log)

        m.addItem(.separator())

        // 用系统自带的勾选态表达开关，不要做成「登录时启动：开/关」那种文字
        let login = NSMenuItem(title: L("登录时启动"),
                               action: #selector(compactToggleLoginItem),
                               keyEquivalent: "")
        login.target = self
        login.state = LoginItem.shared.isEnabled ? .on : .off
        loginMenuItem = login
        m.addItem(login)

        m.addItem(.separator())

        let quit = NSMenuItem(title: L("退出 FileWeave"), action: #selector(compactQuit),
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

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { false }
    func windowWillClose(_ notification: Notification) { app.isPanelVisible = popover.isShown }

    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        openMainWindow(); return true
    }

    @objc private func openMainWindow() {
        closePanel()
        if mainWindow == nil {
            let host = NSHostingController(rootView: FileWeavePanelView(bridge: bridge, workspace: workspace).environmentObject(app))
            let window = NSWindow(contentViewController: host)
            window.title = "FileWeave"
            window.delegate = self
            window.styleMask = [.titled, .closable, .miniaturizable, .resizable]
            window.setContentSize(NSSize(width: 1040, height: 700))
            window.minSize = NSSize(width: 800, height: 520)
            window.isReleasedWhenClosed = false
            window.setFrameAutosaveName("FileWeaveWorkspace")
            window.center()
            mainWindow = window
        }
        NSApp.setActivationPolicy(.regular)
        NSApp.activate(ignoringOtherApps: true)
        mainWindow?.makeKeyAndOrderFront(nil)
        app.isPanelVisible = true
    }

    private func buildApplicationMenu() {
        let menu = NSMenu()
        let root = NSMenuItem()
        let appMenu = NSMenu()
        let open = NSMenuItem(title: L("打开 FileWeave"), action: #selector(openMainWindow), keyEquivalent: "o")
        open.target = self; appMenu.addItem(open)
        appMenu.addItem(.separator())
        let quit = NSMenuItem(title: L("退出 FileWeave"), action: #selector(compactQuit), keyEquivalent: "q")
        quit.target = self; appMenu.addItem(quit)
        root.submenu = appMenu; menu.addItem(root)
        let edit = NSMenuItem(title: L("编辑"), action: nil, keyEquivalent: "")
        let editMenu = NSMenu(title: L("编辑"))
        for (title, action, key) in [(L("撤销"), "undo:", "z"), (L("重做"), "redo:", "Z"),
                                     (L("剪切"), "cut:", "x"), (L("复制"), "copy:", "c"),
                                     (L("粘贴"), "paste:", "v")] {
            editMenu.addItem(NSMenuItem(title: title, action: Selector(action), keyEquivalent: key))
        }
        editMenu.addItem(.separator())
        let find = NSMenuItem(title: L("查找"), action: #selector(findInWindow), keyEquivalent: "f")
        find.target = self; editMenu.addItem(find)
        let all = NSMenuItem(title: L("全选"), action: #selector(selectAllInWindow), keyEquivalent: "a")
        all.target = self; editMenu.addItem(all)
        edit.submenu = editMenu; menu.addItem(edit)
        let viewRoot = NSMenuItem(title: L("显示"), action: nil, keyEquivalent: "")
        let viewMenu = NSMenu(title: L("显示"))
        let hidden = NSMenuItem(title: L("显示隐藏文件"), action: #selector(toggleShowHidden(_:)), keyEquivalent: ".")
        hidden.keyEquivalentModifierMask = [.command, .shift]
        hidden.target = self
        hidden.state = FileVisibilitySettings.shared.showsHiddenFiles ? .on : .off
        showHiddenMenuItem = hidden
        viewMenu.addItem(hidden)
        viewMenu.addItem(.separator())
        let refresh = NSMenuItem(title: L("刷新"), action: #selector(refreshWorkspace), keyEquivalent: "r")
        refresh.target = self; viewMenu.addItem(refresh)
        viewRoot.submenu = viewMenu; menu.addItem(viewRoot)
        let windowRoot = NSMenuItem(title: L("窗口"), action: nil, keyEquivalent: "")
        let windows = NSMenu(title: L("窗口"))
        windows.addItem(NSMenuItem(title: L("最小化"), action: #selector(NSWindow.performMiniaturize(_:)), keyEquivalent: "m"))
        windows.addItem(NSMenuItem(title: L("关闭窗口"), action: #selector(NSWindow.performClose(_:)), keyEquivalent: "w"))
        windowRoot.submenu = windows; menu.addItem(windowRoot); NSApp.windowsMenu = windows
        NSApp.mainMenu = menu
        if languageObserver == nil { languageObserver = NotificationCenter.default.addObserver(forName: .fileWeaveLanguageChanged, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.buildApplicationMenu(); self?.rebuildCompactMenu(); self?.refreshStatusTitle() }
        } }
    }

    @objc private func toggleShowHidden(_ sender: NSMenuItem) {
        FileVisibilitySettings.shared.showsHiddenFiles.toggle()
        sender.state = FileVisibilitySettings.shared.showsHiddenFiles ? .on : .off
    }

    @objc private func refreshWorkspace() { workspace.refresh(app: app) }
    @objc private func findInWindow() {
        // Keep work in the surface the user is using, including the left-click panel.
        if !popover.isShown && mainWindow?.isVisible != true { openPanel() }
        NSApp.activate(ignoringOtherApps: true)
        let target = popover.isShown ? popover.contentViewController?.view.window : mainWindow
        target?.makeKeyAndOrderFront(nil)
        DispatchQueue.main.async {
            guard let window = target, let content = window.contentView else { return }
            func focusSearch(in view: NSView) -> Bool {
                if let search = view as? NSSearchField, !search.isHiddenOrHasHiddenAncestor {
                    window.makeFirstResponder(search); search.selectText(nil); return true
                }
                for child in view.subviews where focusSearch(in: child) { return true }
                return false
            }
            _ = focusSearch(in: content)
        }
    }
    @objc private func selectAllInWindow() {
        if let text = NSApp.keyWindow?.firstResponder as? NSTextView { text.selectAll(nil) }
        else { workspace.selectAll(app: app) }
    }

    #if DEBUG
    /// Sample the actual status-item popover throughout three opening cycles.
    /// Synthetic QA skips the bridge and never opens a production workspace.
    private func runPanelFrameTrace() {
        guard let path = ProcessInfo.processInfo.environment["FILEWEAVE_QA_FRAME_TRACE"] else { return }
        Task { @MainActor in
            var samples: [[String: Any]] = []
            for cycle in 0..<3 {
                closePanel()
                try? await Task.sleep(nanoseconds: 150_000_000)
                let started = Date()
                statusItem.button?.performClick(nil)
                for tick in 0...100 {
                    let window = popover.contentViewController?.view.window
                    let frame = window?.frame ?? .zero
                    samples.append([
                        "cycle": cycle, "tick": tick, "elapsed": Date().timeIntervalSince(started),
                        "shown": popover.isShown, "key": window?.isKeyWindow ?? false, "active": NSApp.isActive,
                        "frame": [frame.origin.x, frame.origin.y, frame.width, frame.height],
                        "content": [popover.contentSize.width, popover.contentSize.height]
                    ])
                    try? await Task.sleep(nanoseconds: 10_000_000)
                }
            }
            closePanel()
            let object: [String: Any] = ["samples": samples]
            if let data = try? JSONSerialization.data(withJSONObject: object, options: [.prettyPrinted, .sortedKeys]) {
                try? data.write(to: URL(fileURLWithPath: path))
            }
            NSLog("QA_FRAME_TRACE %@ samples=%d", path, samples.count)
            NSApp.terminate(nil)
        }
    }

    /// Native smoke checks do not use AX snapshots, which can implicitly reopen apps.
    private func runQASmoke() {
        Task { @MainActor in
            // Exercise the actual status control action, rather than calling openPanel.
            mainWindow?.performClose(nil)
            statusItem.button?.performClick(nil)
            try? await Task.sleep(nanoseconds: 350_000_000)
            NSLog("QA_SMOKE leftClick panel=%@ mainVisible=%@", popover.isShown.description, (mainWindow?.isVisible == true).description)
            findInWindow()
            try? await Task.sleep(nanoseconds: 350_000_000)
            let panelWindow = popover.contentViewController?.view.window
            NSLog("QA_SMOKE panelFind key=%@ textEditor=%@", (NSApp.keyWindow === panelWindow).description,
                  (panelWindow?.firstResponder is NSTextView).description)
            panelWindow?.makeFirstResponder(nil)
            selectAllInWindow()
            NSLog("QA_SMOKE panelSelectAll count=%d", workspace.debugSelectedFileCount)
            workspace.setPreviewDestination("recordings")
            try? await Task.sleep(nanoseconds: 100_000_000)
            workspace.setPreviewDestination("files")
            try? await Task.sleep(nanoseconds: 100_000_000)
            NSLog("QA_SMOKE navigationSelection count=%d", workspace.debugSelectedFileCount)
            openMainWindow()
            try? await Task.sleep(nanoseconds: 150_000_000)
            NSLog("QA_SMOKE windowSharesSelection count=%d", workspace.debugSelectedFileCount)
            findInWindow()
            try? await Task.sleep(nanoseconds: 350_000_000)
            NSLog("QA_SMOKE windowFind key=%@ textEditor=%@", (NSApp.keyWindow === mainWindow).description,
                  (mainWindow?.firstResponder is NSTextView).description)
            mainWindow?.setContentSize(NSSize(width: 840, height: 560))
            NSLog("QA_SMOKE resize content=%@", NSStringFromSize(mainWindow?.contentView?.bounds.size ?? .zero))
            statusItem.button?.performClick(nil)
            try? await Task.sleep(nanoseconds: 250_000_000)
            closePanel()
            NSLog("QA_SMOKE closePopover mainVisible=%@ pollingVisible=%@", (mainWindow?.isVisible == true).description, app.isPanelVisible.description)
            mainWindow?.performClose(nil)
            NSLog("QA_SMOKE closeWindow visible=%@ pollingVisible=%@ running=%@", (mainWindow?.isVisible == true).description, app.isPanelVisible.description, NSApp.isRunning.description)
            openMainWindow()
            NSLog("QA_SMOKE reopen visible=%@", (mainWindow?.isVisible == true).description)
            mainWindow?.performClose(nil)
            statusItem.button?.performClick(nil)
            try? await Task.sleep(nanoseconds: 350_000_000)
            NSLog("QA_SMOKE finalPanel shown=%@ key=%@ appActive=%@ size=%@", popover.isShown.description,
                  (NSApp.keyWindow === popover.contentViewController?.view.window).description,
                  NSApp.isActive.description, NSStringFromSize(popover.contentSize))
        }
    }

    #endif

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
            app.notify(L("设置开机启动失败：{0}", err))
        }
        // 不能重建菜单：菜单正挂在屏幕上，重建出来的那份没人看得见。
        // 直接改当前这个 item 的勾选态。
        loginMenuItem?.state = li.isEnabled ? .on : .off
    }

    @objc private func compactQuit() {
        NSApp.terminate(nil)
    }
}
