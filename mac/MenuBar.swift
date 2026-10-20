import AppKit

final class AppDelegate: NSObject, NSApplicationDelegate, NetServiceDelegate {
    private var statusItem: NSStatusItem!
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
    private var statusLine: NSMenuItem?
    private var showingApproval = false
    private let base = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
    private var state: URL { base.appendingPathComponent(".state", isDirectory: true) }

    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.setActivationPolicy(.accessory)
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        statusItem.button?.image = NSImage(systemSymbolName: "arrow.left.arrow.right", accessibilityDescription: "FileWeave")
        statusItem.button?.title = " FW"
        let menu = NSMenu()
        statusLine = NSMenuItem(title: "FileWeave · 正在自动连接手机", action: nil, keyEquivalent: "")
        menu.addItem(statusLine!)
        menu.addItem(NSMenuItem.separator())
        add(menu, "打开共享文件夹（手机可读取）", #selector(openShared))
        add(menu, "打开手机传来的文件", #selector(openInbox))
        menu.addItem(NSMenuItem.separator())
        add(menu, "查看服务日志", #selector(openLog))
        menu.addItem(NSMenuItem.separator())
        add(menu, "退出 FileWeave", #selector(quit))
        statusItem.menu = menu
        startServer()
        let timer = Timer(timeInterval: 1, repeats: true) { [weak self] _ in self?.pollEnrollment() }
        RunLoop.main.add(timer, forMode: .common)
        pairingTimer = timer
    }
    private func add(_ menu: NSMenu, _ title: String, _ action: Selector) {
        let item = NSMenuItem(title: title, action: action, keyEquivalent: "")
        item.target = self
        menu.addItem(item)
    }
    private func startServer() {
        if shuttingDown || process?.isRunning == true || Date() < nextStart { return }
        nextStart = Date().addingTimeInterval(restartDelay)
        restartDelay = min(restartDelay * 2, 30)
        do {
            try FileManager.default.createDirectory(at: state, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
            let executable = ["/opt/homebrew/bin/python3", "/usr/local/bin/python3", "/usr/bin/python3"].first { FileManager.default.isExecutableFile(atPath: $0) }!
            let child = Process()
            child.executableURL = URL(fileURLWithPath: executable)
            child.arguments = [base.appendingPathComponent("bridge.py").path, "--state", state.path]
            let logURL = state.appendingPathComponent("server.log")
            if !FileManager.default.fileExists(atPath: logURL.path) { FileManager.default.createFile(atPath: logURL.path, contents: nil) }
            let log = try FileHandle(forWritingTo: logURL)
            log.seekToEndOfFile()
            child.standardOutput = log
            child.standardError = log
            try child.run()
            process = child
            startedAt = Date()
        } catch {
            statusLine?.title = "FileWeave · 连接恢复中"
            discoveryLog("Service start failed; automatic retry scheduled")
        }
    }
    private func stopServer() { discovery?.stop(); discovery = nil; discoveryIdentity = nil; shownRequests.removeAll(); if process?.isRunning == true { process?.terminate(); process?.waitUntilExit() }; process = nil }
    private func pollEnrollment() {
        guard process?.isRunning == true else {
            discovery?.stop(); discovery = nil; discoveryIdentity = nil
            statusLine?.title = "FileWeave · 连接恢复中"
            startServer()
            return
        }
        if Date().timeIntervalSince(startedAt) >= 30 { restartDelay = 1 }
        if let data = try? Data(contentsOf: state.appendingPathComponent("pairing.json")),
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
              let data = try? Data(contentsOf: state.appendingPathComponent("pairing-requests.json")),
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
        let destination = state.appendingPathComponent("pairing-decisions/\(id).json")
        do {
            let value = try JSONSerialization.data(withJSONObject: ["approved": approved])
            try value.write(to: destination, options: .atomic)
            try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: destination.path)
        } catch { alert("无法保存连接批准", "连接会自动重试，请稍候。") }
    }
    func netServiceDidPublish(_ sender: NetService) {
        guard sender === discovery else { return }
        statusLine?.title = "FileWeave · 自动连接已就绪"
        statusItem.button?.toolTip = "同一局域网内的手机会自动发现并连接。"
        discoveryLog("Bonjour published")
    }
    func netService(_ sender: NetService, didNotPublish errorDict: [String: NSNumber]) {
        guard sender === discovery else { return }
        statusLine?.title = "FileWeave · 等待局域网访问"
        statusItem.button?.toolTip = "请检查系统设置中的局域网访问权限，程序会自动恢复发现。"
        discovery?.stop(); discovery = nil; discoveryIdentity = nil
        nextPublish = Date().addingTimeInterval(30)
        discoveryLog("Bonjour publish failed: \(errorDict)")
    }
    private func discoveryLog(_ message: String) {
        let path = state.appendingPathComponent("discovery.log")
        if !FileManager.default.fileExists(atPath: path.path) {
            FileManager.default.createFile(atPath: path.path, contents: nil, attributes: [.posixPermissions: 0o600])
        }
        if let file = try? FileHandle(forWritingTo: path) {
            file.seekToEndOfFile(); file.write(Data((message + "\n").utf8)); try? file.close()
        }
    }
    @objc func openShared() { openFolder("shared") }
    @objc func openInbox() { openFolder("inbox") }
    private func openFolder(_ name: String) {
        let folder = state.appendingPathComponent(name, isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        NSWorkspace.shared.open(folder)
    }
    @objc func openLog() { NSWorkspace.shared.open(state.appendingPathComponent("server.log")) }
    @objc func quit() { NSApp.terminate(nil) }
    func applicationWillTerminate(_ notification: Notification) { shuttingDown = true; pairingTimer?.invalidate(); stopServer() }
    private func alert(_ title: String, _ text: String) {
        let dialog = NSAlert(); dialog.messageText = title; dialog.informativeText = text; dialog.runModal()
    }
}

let app = NSApplication.shared
let delegate = AppDelegate()
app.delegate = delegate
app.run()
