import SwiftUI
import AppKit
import FileWeaveMacKit

/// 调试入口：把菜单栏那套界面渲染成普通窗口，或用 SNAPSHOT_PATH 直接导出 PNG。
/// 正式应用是 FileWeaveMenuBar；这个只在开发/核对排版时用。
///
/// 这里传给面板的 BridgeProcess **不 start()**：调试窗口不该再拉起一个
/// bridge.py 子进程去和正式实例抢端口 —— 顶部状态段只是渲染出来占位。
@main
struct DebugWindowApp: App {
    @StateObject private var app = AppState()
    @StateObject private var bridge = BridgeProcess()
    @StateObject private var workspace: WorkspaceSession

    init() {
        let session = WorkspaceSession()
        #if DEBUG
        session.setPreviewDestination(ProcessInfo.processInfo.environment["SNAPSHOT_DESTINATION"] ?? ProcessInfo.processInfo.environment["SNAPSHOT_PAGE"] ?? "files")
        #endif
        _workspace = StateObject(wrappedValue: session)
        AppLanguage.configureNativeLanguage()
        if ProcessInfo.processInfo.environment["SNAPSHOT_DARK"] == "1" { NSApplication.shared.appearance = NSAppearance(named: .darkAqua) }
    }

    var body: some Scene {
        Window(L("FileWeave · 调试窗口"), id: "debug") {
            Group {
                if ProcessInfo.processInfo.environment["SNAPSHOT_PAGE"] == "quick" {
                    FileWeavePopoverView(bridge: bridge, workspace: workspace,
                        panelSize: CGSize(width: Double(ProcessInfo.processInfo.environment["SNAPSHOT_WIDTH"] ?? "720") ?? 720,
                                          height: Double(ProcessInfo.processInfo.environment["SNAPSHOT_HEIGHT"] ?? "640") ?? 640), openWindow: {})
                } else {
            FileWeavePanelView(bridge: bridge, workspace: workspace)
                }
            }
                .environmentObject(app)
                .preferredColorScheme(ProcessInfo.processInfo.environment["SNAPSHOT_DARK"] == "1" ? .dark : nil)
                .task { app.isPanelVisible = true; await bringToFrontAndMaybeSnapshot(app) }
        }
        .defaultSize(width: ProcessInfo.processInfo.environment["SNAPSHOT_PAGE"] == "quick" ? Metrics.panelWidth : 1040,
                      height: ProcessInfo.processInfo.environment["SNAPSHOT_PAGE"] == "quick" ? Metrics.panelHeight : 700)
        .windowResizability(.automatic)
    }

    @MainActor
    private func bringToFrontAndMaybeSnapshot(_ app: AppState) async {
        // 调试窗口默认会埋在其它窗口后面，强制置顶并居中，方便截图核对
        try? await Task.sleep(nanoseconds: 1_200_000_000)
        NSApp.activate(ignoringOtherApps: true)
        for w in NSApp.windows where w.isVisible {
            if let width = Double(ProcessInfo.processInfo.environment["SNAPSHOT_WIDTH"] ?? ""),
               let height = Double(ProcessInfo.processInfo.environment["SNAPSHOT_HEIGHT"] ?? "") {
                w.setContentSize(NSSize(width: width, height: height))
            }
            if ProcessInfo.processInfo.environment["SNAPSHOT_DARK"] == "1" { w.appearance = NSAppearance(named: .darkAqua) }
            w.level = .floating
            w.center()
            w.makeKeyAndOrderFront(nil)
        }

        // 截图时预选若干条，用来验证多选操作条（必须在扫描完成之后，且不需要 SNAPSHOT_PATH）
        if let n = Int(ProcessInfo.processInfo.environment["SNAPSHOT_SELECT"] ?? "") {
            for rec in app.recordings.prefix(n) { app.select(rec) }
            try? await Task.sleep(nanoseconds: 800_000_000)
        }

        guard let path = ProcessInfo.processInfo.environment["SNAPSHOT_PATH"],
              !path.isEmpty else { return }

// 等首次扫描与首帧渲染完成
        try? await Task.sleep(nanoseconds: 5_000_000_000)

        // 端到端验证下载链路：把第一条新增真的拉到临时目录（不动 ~/Downloads）
        if ProcessInfo.processInfo.environment["SNAPSHOT_DOWNLOAD"] == "1",
           let first = app.recordings.first(where: { app.isNew($0) }) {
            let dir = URL(fileURLWithPath: NSTemporaryDirectory())
                .appendingPathComponent("fileweave-download-test", isDirectory: true)
            await app.download(recs: [first], into: dir)
            NSLog("DOWNLOAD TEST -> \(dir.path)")
            try? await Task.sleep(nanoseconds: 500_000_000)
        }

        if DebugPreview.mode != nil,
           let row = Int(ProcessInfo.processInfo.environment["SNAPSHOT_COMPARE_ROW"] ?? ""),
           let window = NSApp.windows.first(where: { $0.isVisible && $0.contentView != nil }),
           let content = window.contentView,
           let table = nativeTable(in: content), row >= 0, row < table.numberOfRows {
            NSApp.activate(ignoringOtherApps: true)
            window.makeKeyAndOrderFront(nil)
            try? await Task.sleep(nanoseconds: 250_000_000)
            let previousPointer = CGEvent(source: nil)?.location
            defer {
                if let previousPointer {
                    CGEvent(mouseEventSource: nil, mouseType: .mouseMoved, mouseCursorPosition: previousPointer, mouseButton: .left)?.post(tap: .cghidEventTap)
                }
            }
            let rect = table.rect(ofRow: row)
            let inWindow = table.convert(NSPoint(x: rect.midX, y: rect.midY), to: nil)
            let screen = window.convertPoint(toScreen: inWindow)
            let point = CGPoint(x: screen.x, y: (NSScreen.screens.first?.frame.maxY ?? 0) - screen.y)
            let titlebar = CGPoint(x: window.frame.midX, y: (NSScreen.screens.first?.frame.maxY ?? 0) - window.frame.maxY + 14)
            CGEvent(mouseEventSource: nil, mouseType: .leftMouseDown, mouseCursorPosition: titlebar, mouseButton: .left)?.post(tap: .cghidEventTap)
            CGEvent(mouseEventSource: nil, mouseType: .leftMouseUp, mouseCursorPosition: titlebar, mouseButton: .left)?.post(tap: .cghidEventTap)
            try? await Task.sleep(nanoseconds: 250_000_000)
            CGEvent(mouseEventSource: nil, mouseType: .mouseMoved, mouseCursorPosition: point, mouseButton: .left)?.post(tap: .cghidEventTap)
            try? await Task.sleep(nanoseconds: 500_000_000)
            NSLog("ROW_COMPARE_HOVER row=%d selection=%@ key=%@ active=%@", row, table.selectedRowIndexes.description, window.isKeyWindow.description, NSApp.isActive.description)
            Snapshotter.capture(content.superview ?? content, to: path.replacingOccurrences(of: ".png", with: "-hover.png"))
            CGEvent(mouseEventSource: nil, mouseType: .leftMouseDown, mouseCursorPosition: point, mouseButton: .left)?.post(tap: .cghidEventTap)
            CGEvent(mouseEventSource: nil, mouseType: .leftMouseUp, mouseCursorPosition: point, mouseButton: .left)?.post(tap: .cghidEventTap)
            try? await Task.sleep(nanoseconds: 500_000_000)
            Snapshotter.capture(content.superview ?? content, to: path.replacingOccurrences(of: ".png", with: "-selected.png"))
            NSLog("ROW_COMPARE row=%d nativeRect=%@ selection=%@ key=%@ active=%@", row, NSStringFromRect(rect), table.selectedRowIndexes.description, window.isKeyWindow.description, NSApp.isActive.description)
        }

        if let view = NSApp.windows.first(where: { $0.isVisible && $0.contentView != nil })?.contentView {
            Snapshotter.capture(view.superview ?? view, to: path)
        }
        try? await Task.sleep(nanoseconds: 300_000_000)
        NSApplication.shared.terminate(nil)
    }

    private func nativeTable(in view: NSView) -> NSTableView? {
        if let table = view as? NSTableView { return table }
        for child in view.subviews { if let table = nativeTable(in: child) { return table } }
        return nil
    }
}

@MainActor
enum Snapshotter {
    static func capture(_ view: NSView, to path: String) {
        view.layoutSubtreeIfNeeded()
        guard let rep = view.bitmapImageRepForCachingDisplay(in: view.bounds) else { return }
        view.cacheDisplay(in: view.bounds, to: rep)
        guard let png = rep.representation(using: .png, properties: [:]) else { return }
        try? png.write(to: URL(fileURLWithPath: path))
        NSLog("SNAPSHOT saved: \(path)")
    }
}
