import AppKit
import CoreImage

final class AppDelegate: NSObject, NSApplicationDelegate {
    private var statusItem: NSStatusItem!
    private var process: Process?
    private var pairingWindow: NSWindow?
    private let base = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
    private var state: URL { base.appendingPathComponent(".state", isDirectory: true) }

    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.setActivationPolicy(.accessory)
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        statusItem.button?.image = NSImage(systemSymbolName: "arrow.left.arrow.right", accessibilityDescription: "FileWeave")
        statusItem.button?.title = " FW"
        let menu = NSMenu()
        menu.addItem(withTitle: "FileWeave · Android ↔ Mac", action: nil, keyEquivalent: "")
        menu.addItem(NSMenuItem.separator())
        add(menu, "配对手机…", #selector(showPairing))
        add(menu, "复制配对信息", #selector(copyPairing))
        add(menu, "打开共享文件夹（手机可读取）", #selector(openShared))
        add(menu, "打开手机传来的文件", #selector(openInbox))
        menu.addItem(NSMenuItem.separator())
        add(menu, "启动服务", #selector(startServer))
        add(menu, "停止服务", #selector(stopServer))
        add(menu, "重置配对", #selector(resetPairing))
        add(menu, "查看服务日志", #selector(openLog))
        menu.addItem(NSMenuItem.separator())
        add(menu, "退出 FileWeave", #selector(quit))
        statusItem.menu = menu
        startServer()
    }
    private func add(_ menu: NSMenu, _ title: String, _ action: Selector) {
        let item = NSMenuItem(title: title, action: action, keyEquivalent: "")
        item.target = self
        menu.addItem(item)
    }
    @objc func startServer() {
        if process?.isRunning == true { return }
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
        } catch { alert("服务启动失败", error.localizedDescription) }
    }
    @objc func stopServer() { if process?.isRunning == true { process?.terminate(); process?.waitUntilExit() }; process = nil }
    private func pairing() -> String? {
        guard process?.isRunning == true,
              let data = try? Data(contentsOf: state.appendingPathComponent("pairing.json")),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        return json["uri"] as? String
    }
    @objc func copyPairing() {
        guard let value = pairing() else { alert("服务尚未就绪", "请稍等后重试，或查看服务日志。"); return }
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(value, forType: .string)
    }
    @objc func showPairing() {
        guard let value = pairing() else { alert("服务尚未就绪", "请稍等后重试，或查看服务日志。"); return }
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 560, height: 660), styleMask: [.titled, .closable], backing: .buffered, defer: false)
        window.title = "FileWeave · 配对手机"
        window.isReleasedWhenClosed = false
        let content = NSView(frame: NSRect(x: 0, y: 0, width: 560, height: 660))
        let title = NSTextField(wrappingLabelWithString: "手机与 Mac 连接同一局域网。用手机系统相机扫描二维码，或复制配对信息到 FileWeave 的「连接 Mac」。")
        title.frame = NSRect(x: 24, y: 570, width: 512, height: 60)
        content.addSubview(title)
        if let filter = CIFilter(name: "CIQRCodeGenerator") {
            filter.setValue(value.data(using: .utf8), forKey: "inputMessage")
            filter.setValue("M", forKey: "inputCorrectionLevel")
            if let image = filter.outputImage {
                let bitmap = NSCIImageRep(ciImage: image.transformed(by: CGAffineTransform(scaleX: 8, y: 8)))
                let qr = NSImage(size: bitmap.size); qr.addRepresentation(bitmap)
                let view = NSImageView(frame: NSRect(x: 70, y: 145, width: 420, height: 420))
                view.image = qr; view.imageScaling = .scaleProportionallyUpOrDown
                content.addSubview(view)
            }
        }
        let hint = NSTextField(wrappingLabelWithString: "配对信息包含连接凭据，请勿发给他人。文件仅共享此程序的共享文件夹；剪贴板只在手机主动点击时读取或写入。")
        hint.frame = NSRect(x: 24, y: 75, width: 512, height: 60); content.addSubview(hint)
        let button = NSButton(title: "复制配对信息", target: self, action: #selector(copyPairing))
        button.frame = NSRect(x: 180, y: 24, width: 200, height: 36); content.addSubview(button)
        window.contentView = content
        window.center(); window.makeKeyAndOrderFront(nil); NSApp.activate(ignoringOtherApps: true)
        pairingWindow = window
    }
    @objc func openShared() { openFolder("shared") }
    @objc func openInbox() { openFolder("inbox") }
    private func openFolder(_ name: String) {
        let folder = state.appendingPathComponent(name, isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        NSWorkspace.shared.open(folder)
    }
    @objc func resetPairing() {
        let dialog = NSAlert(); dialog.messageText = "重置配对？"; dialog.informativeText = "现有手机将失去连接，需要重新扫描或粘贴新的配对信息。"
        dialog.addButton(withTitle: "重置"); dialog.addButton(withTitle: "取消")
        guard dialog.runModal() == .alertFirstButtonReturn else { return }
        stopServer()
        try? FileManager.default.removeItem(at: state.appendingPathComponent("credentials.json"))
        try? FileManager.default.removeItem(at: state.appendingPathComponent("pairing.json"))
        pairingWindow?.close(); startServer()
    }
    @objc func openLog() { NSWorkspace.shared.open(state.appendingPathComponent("server.log")) }
    @objc func quit() { NSApp.terminate(nil) }
    func applicationWillTerminate(_ notification: Notification) { stopServer() }
    private func alert(_ title: String, _ text: String) {
        let dialog = NSAlert(); dialog.messageText = title; dialog.informativeText = text; dialog.runModal()
    }
}

let app = NSApplication.shared
let delegate = AppDelegate()
app.delegate = delegate
app.run()
