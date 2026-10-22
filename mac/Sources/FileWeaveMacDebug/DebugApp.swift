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

    var body: some Scene {
        Window("FileWeave · 调试窗口", id: "debug") {
            FileWeavePanelView(bridge: bridge)
                .environmentObject(app)
                .task { await bringToFrontAndMaybeSnapshot(app) }
        }
        .defaultSize(width: 380, height: 620)
    }

    @MainActor
    private func bringToFrontAndMaybeSnapshot(_ app: AppState) async {
        // 调试窗口默认会埋在其它窗口后面，强制置顶并居中，方便截图核对
        try? await Task.sleep(nanoseconds: 1_200_000_000)
        NSApp.activate(ignoringOtherApps: true)
        for w in NSApp.windows where w.isVisible {
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

        Snapshotter.capture(FileWeavePanelView(bridge: bridge).environmentObject(app),
                            size: CGSize(width: 380, height: 620),
                            scale: 2,
                            to: path)
        try? await Task.sleep(nanoseconds: 300_000_000)
        NSApplication.shared.terminate(nil)
    }
}

@MainActor
enum Snapshotter {
    static func capture<V: View>(_ view: V, size: CGSize, scale: CGFloat, to path: String) {
        let renderer = ImageRenderer(
            content: view
                .frame(width: size.width, height: size.height)
                .background(Color(nsColor: .windowBackgroundColor))
        )
        renderer.scale = scale
        guard let nsImage = renderer.nsImage,
              let tiff = nsImage.tiffRepresentation,
              let rep = NSBitmapImageRep(data: tiff),
              let png = rep.representation(using: .png, properties: [:]) else { return }
        try? png.write(to: URL(fileURLWithPath: path))
        NSLog("SNAPSHOT saved: \(path)")
    }
}
