import AppKit

/// bridge.py 所在目录与运行期状态目录的统一出口。
///
/// 这套路径刻意**跟着源码走**而不是跟着 .app 走：bridge.py / pairing.py /
/// .state 都留在仓库的 mac/ 目录里（开发者一键运行、日志和配对数据
/// 始终在看得见的地方）。`#filePath` 是编译期常量，指向本文件在源码
/// 树里的绝对位置，往上三级正好是 mac/ —— 无论 .app 被组装到哪里，
/// 运行时都找得到同一份 bridge.py 和 .state。
enum MacPaths {
    /// mac/ 目录（bridge.py、pairing.py、.state 都在这里）
    static let base = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()   // …/mac/Sources/FileWeaveMacKit
        .deletingLastPathComponent()   // …/mac/Sources
        .deletingLastPathComponent()   // …/mac
    /// bridge.py 的运行期状态目录（与 bridge.py 的 --state 一致）
    static let state = base.appendingPathComponent(".state", isDirectory: true)
    /// 已配对设备记录，由 pairing.py 写入（结构见 pairing.py，0600）
    static var pairedDevices: URL { state.appendingPathComponent("paired-devices.json") }
}

/// bridge.py 子进程监管 + Bonjour 发布 + 配对批准弹窗。
///
/// 从原 MenuBar.swift 原样提取：行为一个没动 ——
/// - 子进程死了按 1→2→4…→30 秒退避拉起，稳定跑 30 秒后退避归零；
/// - pairing.json 变化（pin:port 变）时重新发布 `_fileweave._tcp.`，
///   TXT 带 v/pin；发布失败 30 秒后重试；
/// - 每秒轮询 pairing-requests.json，有新请求就弹 NSAlert，
///   决定写进 .state/pairing-decisions/<id>.json；
/// - 状态行（「正在自动连接手机」等）通过 `statusLine` 暴露给 UI。
@MainActor
public final class BridgeProcess: NSObject, ObservableObject, NetServiceDelegate {

    /// 状态行文案。右键菜单和面板顶部共用这一份。
    @Published public private(set) var statusLine = "FileWeave · 正在自动连接手机"
    /// 状态行的悬浮说明（发布成功/失败时各有一句指引）
    @Published public private(set) var statusToolTip: String?

    private var process: Process?
    private var discovery: NetService?
    private var discoveryIdentity: String?
    private var pairingTimer: Timer?
    private var shownRequests = Set<String>()
    private var shuttingDown = false
    private var nextStart = Date.distantPast
    private var restartDelay: TimeInterval = 1
    private var startedAt = Date.distantPast
    private var nextPublish = Date.distantPast
    private var showingApproval = false

    public override init() {
        super.init()
    }

    // MARK: 生命周期

    public func start() {
        startServer()
        let timer = Timer(timeInterval: 1, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.pollEnrollment() }
        }
        RunLoop.main.add(timer, forMode: .common)
        pairingTimer = timer
    }

    /// applicationWillTerminate 时调用：停轮询、收 Bonjour、掐子进程
    public func shutdown() {
        shuttingDown = true
        pairingTimer?.invalidate()
        stopServer()
    }

    // MARK: 子进程监管

    private func startServer() {
        if shuttingDown || process?.isRunning == true || Date() < nextStart { return }
        nextStart = Date().addingTimeInterval(restartDelay)
        restartDelay = min(restartDelay * 2, 30)
        do {
            try FileManager.default.createDirectory(at: MacPaths.state, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
            let executable = ["/opt/homebrew/bin/python3", "/usr/local/bin/python3", "/usr/bin/python3"].first { FileManager.default.isExecutableFile(atPath: $0) }!
            let child = Process()
            child.executableURL = URL(fileURLWithPath: executable)
            child.arguments = [MacPaths.base.appendingPathComponent("bridge.py").path, "--state", MacPaths.state.path]
            let logURL = MacPaths.state.appendingPathComponent("server.log")
            if !FileManager.default.fileExists(atPath: logURL.path) { FileManager.default.createFile(atPath: logURL.path, contents: nil) }
            let log = try FileHandle(forWritingTo: logURL)
            log.seekToEndOfFile()
            child.standardOutput = log
            child.standardError = log
            try child.run()
            process = child
            startedAt = Date()
        } catch {
            statusLine = "FileWeave · 连接恢复中"
            discoveryLog("Service start failed; automatic retry scheduled")
        }
    }

    private func stopServer() {
        discovery?.stop(); discovery = nil; discoveryIdentity = nil; shownRequests.removeAll()
        if process?.isRunning == true { process?.terminate(); process?.waitUntilExit() }
        process = nil
    }

    // MARK: Bonjour 发布 + 配对批准轮询

    private func pollEnrollment() {
        guard process?.isRunning == true else {
            discovery?.stop(); discovery = nil; discoveryIdentity = nil
            statusLine = "FileWeave · 连接恢复中"
            startServer()
            return
        }
        if Date().timeIntervalSince(startedAt) >= 30 { restartDelay = 1 }
        if let data = try? Data(contentsOf: MacPaths.state.appendingPathComponent("pairing.json")),
           let info = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let port = info["port"] as? Int32, let pin = info["pin"] as? String {
            let identity = "\(pin):\(port)"
            if discoveryIdentity != identity && Date() >= nextPublish {
                discovery?.stop()
                let service = NetService(domain: "local.", type: "_fileweave._tcp.",
                                         name: Host.current().localizedName ?? "FileWeave Mac", port: port)
                service.setTXTRecord(NetService.data(fromTXTRecord: ["v": Data("1".utf8), "pin": Data(pin.utf8)]))
                service.delegate = self
                discoveryLog("Starting Bonjour publication")
                service.publish()
                discovery = service; discoveryIdentity = identity
            }
        }
        guard !showingApproval,
              let data = try? Data(contentsOf: MacPaths.state.appendingPathComponent("pairing-requests.json")),
              let requests = try? JSONSerialization.jsonObject(with: data) as? [[String: String]] else { return }
        shownRequests.formIntersection(Set(requests.compactMap { $0["id"] }))
        guard let request = requests.first(where: { !shownRequests.contains($0["id"] ?? "") }),
              let id = request["id"], id.range(of: "^[a-f0-9]{32}$", options: .regularExpression) != nil,
              let code = request["code"], let name = request["name"], let address = request["address"] else { return }
        shownRequests.insert(id)
        let dialog = NSAlert()
        dialog.messageText = "允许 \(name) 与 FileWeave 配对？"
        dialog.informativeText = "手机地址：\(address)\n配对码：\(code)\n\n请核对手机上显示的配对码完全一致，再点击允许。批准后此手机可访问共享文件夹并主动操作剪贴板，之后会自动连接。请求 2 分钟后过期。"
        dialog.addButton(withTitle: "配对码一致，允许")
        dialog.addButton(withTitle: "拒绝")
        NSApp.activate(ignoringOtherApps: true)
        showingApproval = true
        let approved = dialog.runModal() == .alertFirstButtonReturn
        showingApproval = false
        let destination = MacPaths.state.appendingPathComponent("pairing-decisions/\(id).json")
        do {
            let value = try JSONSerialization.data(withJSONObject: ["approved": approved])
            try value.write(to: destination, options: .atomic)
            try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: destination.path)
        } catch { alert("无法保存连接批准", "连接会自动重试，请稍候。") }
    }

    // NetServiceDelegate 的方法在协议里是 nonisolated 的；publish() 是从主线程
    // 起的，回调也就落在主线程，assumeIsolated 进来改状态最省事。
    public nonisolated func netServiceDidPublish(_ sender: NetService) {
        MainActor.assumeIsolated {
            guard sender === self.discovery else { return }
            self.statusLine = "FileWeave · 自动连接已就绪"
            self.statusToolTip = "同一局域网内的手机会自动发现并连接。"
            self.discoveryLog("Bonjour published")
        }
    }

    public nonisolated func netService(_ sender: NetService, didNotPublish errorDict: [String: NSNumber]) {
        MainActor.assumeIsolated {
            guard sender === self.discovery else { return }
            self.statusLine = "FileWeave · 等待局域网访问"
            self.statusToolTip = "请检查系统设置中的局域网访问权限，程序会自动恢复发现。"
            self.discovery?.stop(); self.discovery = nil; self.discoveryIdentity = nil
            self.nextPublish = Date().addingTimeInterval(30)
            self.discoveryLog("Bonjour publish failed: \(errorDict)")
        }
    }

    private func discoveryLog(_ message: String) {
        let path = MacPaths.state.appendingPathComponent("discovery.log")
        if !FileManager.default.fileExists(atPath: path.path) {
            FileManager.default.createFile(atPath: path.path, contents: nil, attributes: [.posixPermissions: 0o600])
        }
        if let file = try? FileHandle(forWritingTo: path) {
            file.seekToEndOfFile(); file.write(Data((message + "\n").utf8)); try? file.close()
        }
    }

    // MARK: 面板/菜单共用入口

    public func openShared() { openFolder("shared") }
    public func openInbox() { openFolder("inbox") }
    private func openFolder(_ name: String) {
        let folder = MacPaths.state.appendingPathComponent(name, isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        NSWorkspace.shared.open(folder)
    }
    public func openLog() { NSWorkspace.shared.open(MacPaths.state.appendingPathComponent("server.log")) }

    private func alert(_ title: String, _ text: String) {
        let dialog = NSAlert(); dialog.messageText = title; dialog.informativeText = text; dialog.runModal()
    }
}
