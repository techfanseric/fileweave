// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "FileWeaveMac",
    platforms: [.macOS(.v14)],
    targets: [
        // 核心逻辑与界面，菜单栏版和调试版共用
        .target(
            name: "FileWeaveMacKit",
            path: "Sources/FileWeaveMacKit",
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
        // 真正的菜单栏应用：bridge.py 监管 + 文件夹共享 + 录音面板
        .executableTarget(
            name: "FileWeaveMenuBar",
            dependencies: ["FileWeaveMacKit"],
            path: "Sources/FileWeaveMenuBar",
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
        // 调试版：把同一套界面渲染成普通窗口，便于截图核对
        .executableTarget(
            name: "FileWeaveMacDebug",
            dependencies: ["FileWeaveMacKit"],
            path: "Sources/FileWeaveMacDebug",
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
        // 水位线/重试退避/登录项 plist/文件夹共享协议解析的回归测试
        .testTarget(
            name: "FileWeaveMacKitTests",
            dependencies: ["FileWeaveMacKit"],
            path: "Tests/FileWeaveMacKitTests",
            swiftSettings: [.swiftLanguageMode(.v5)]
        )
    ]
)
