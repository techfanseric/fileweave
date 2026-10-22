import AppKit

// 纯状态栏应用：没有 Dock 图标、没有窗口，只有菜单栏图标。
// 不使用 SwiftUI 的 App 协议，因为 MenuBarExtra 无法区分左右键。
// 顶层 `let` 是全局强引用，NSApplication.delegate 是 weak，靠它保命。

let application = NSApplication.shared
// 顶层代码本身就跑在主线程，用 assumeIsolated 明确这一点
let controller = MainActor.assumeIsolated { AppController() }

application.delegate = controller
application.setActivationPolicy(.accessory)
application.run()
