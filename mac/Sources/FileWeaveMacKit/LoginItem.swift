import AppKit
import Foundation
import ServiceManagement

/// 「登录时启动」开关。
///
/// 两条实现路径，按可用性自动选：
///
/// 1. `SMAppService.mainApp` —— 系统原生做法，注册后能在
///    「系统设置 → 通用 → 登录项」里看到和关掉。
/// 2. `~/Library/LaunchAgents` 里的 plist —— 兜底。
///    本项目是 SwiftPM 构建 + ad-hoc 签名（`codesign -s -`），
///    `SMAppService` 在这种签名下不保证能注册成功，
///    没有兜底的话开关会静默地「点了没反应」。
///
/// 两条路径的**状态判定**都以真实存在的注册记录为准，
/// 不用本机 UserDefaults 记账 —— 记账会和「系统在登录项里被手动关掉」
/// 这件真实发生的事脱节，界面就会显示一个假的「已开启」。
@MainActor
public final class LoginItem: ObservableObject {

    public enum Backend: String {
        /// 走 ServiceManagement，进系统「登录项」列表
        case service
        /// 走 LaunchAgent plist
        case launchAgent
        /// 当前进程不是真正的 .app bundle（调试版），没法注册
        case unavailable
    }

    /// 现在是否真的会在登录时被拉起
    @Published public private(set) var isEnabled = false

    /// 实际生效的后端，UI 不用关心，但排查时要看它
    @Published public private(set) var backend: Backend = .unavailable

    /// 最近一次失败的原文，出错时才有值
    @Published public private(set) var lastError: String?

    public enum LoginItemError: LocalizedError {
        case serviceUnavailable(String)
        case agentNotLoaded(String)

        public var errorDescription: String? {
            switch self {
            case .serviceUnavailable(let m): return m
            case .agentNotLoaded(let m): return m
            }
        }
    }

    public static let shared = LoginItem()

    /// LaunchAgent 的 label，直接用 bundle id，避免和应用本身混淆。
    /// nonisolated：plist 的落盘路径要在没有主线程保证的地方也能算出来。
    public nonisolated static var label: String {
        Bundle.main.bundleIdentifier ?? "org.fileweave.mac.dev"
    }

    /// 真 app 与调试版的分界：`ServiceManagement` 和登录项都要求一个
    /// 完整的 .app bundle，裸可执行文件（FileWeaveMacDebug）调它会直接抛异常。
    var isRealBundle: Bool {
        Bundle.main.bundleURL.pathExtension == "app"
            && Bundle.main.bundleIdentifier != nil
    }

    /// 能不能提供这个开关。调试版里它恒为 false，UI 就该置灰而不是假装能点。
    public var isAvailable: Bool { isRealBundle }

    // MARK: 读写

    public func refresh() {
        guard isRealBundle else {
            backend = .unavailable
            isEnabled = false
            return
        }
        // ServiceManagement 说开了就算开；它说没开，再看兜底那条是不是**真的装着**。
        // 兜底不能只看 plist 文件在不在 —— 文件写了但 launchctl 没装上，
        // 界面照样显示「已开启」，而下次开机它压根不会来。那种假开启比不开启更坏。
        if serviceStatus() == .enabled {
            backend = .service
            isEnabled = true
        } else if isAgentLoaded() {
            backend = .launchAgent
            isEnabled = true
        } else {
            backend = .service
            isEnabled = false
        }
    }

    /// - Parameter prefer: 指定走哪条实现路径。默认 nil = 先试原生、
    ///   失败再落兜底。显式指定是给「想验另一条路」用的。
    public func setEnabled(_ on: Bool, prefer: Backend? = nil) {
        guard isRealBundle else {
            lastError = "当前不是安装后的 app，无法注册登录项"
            return
        }
        do {
            if on {
                try register(prefer: prefer)
                lastError = nil
            } else {
                try unregister()
                lastError = nil
            }
        } catch {
            // 状态判定必须重新实扫，不能在这里假定成功
            lastError = error.localizedDescription
            refresh()
            return
        }
        refresh()
    }

    public func toggle() { setEnabled(!isEnabled) }

    // MARK: 开启

    private func register(prefer: Backend?) throws {
        if prefer == .launchAgent {
            // 两个方向都要清：只清兜底的话，原生那条还挂着，
            // 界面上看着只有一种注册，开机却会被拉起两次。
            unregisterServiceQuietly()
            removeAgent()
            try installAgent()
            backend = .launchAgent
            isEnabled = true
            return
        }

        // 走原生时同样要把兜底清掉，两套不能并存
        removeAgent()

        let service = SMAppService.mainApp
        if prefer == .service {
            do {
                try service.register()
                noteServiceResult(service)
                return
            } catch {
                throw LoginItemError.serviceUnavailable(
                    "系统登录项注册失败：\(error.localizedDescription)")
            }
        }

        do {
            try service.register()
            noteServiceResult(service)
        } catch {
            // ad-hoc 签名下常见的失败，落兜底而不是把开关变成哑巴
            NSLog("LOGIN ITEM: ServiceManagement 注册失败(%@)，改用 LaunchAgent",
                  String(describing: error))
            let serviceMessage = error.localizedDescription
            do {
                try installAgent()
                backend = .launchAgent
                isEnabled = true
            } catch {
                throw LoginItemError.serviceUnavailable(
                    "系统登录项注册失败（\(serviceMessage)），兜底方式也失败：\(error.localizedDescription)")
            }
        }
    }

    private func noteServiceResult(_ service: SMAppService) {
        backend = .service
        isEnabled = service.status == .enabled
        // 注册成功但等用户点允许，也是一种「没真开起来」
        if service.status == .requiresApproval {
            lastError = "需要到「系统设置 → 通用 → 登录项」里允许它"
        }
    }

    // MARK: 关闭

    private func unregister() throws {
        let firstError = unregisterServiceQuietly()
        // 兜底的记录也一并清掉：用户看到的应该是「彻底关了」，
        // 而不是系统登录项里没了、LaunchAgents 里还留着一条
        removeAgent()
        if let firstError { throw firstError }
    }

    /// 注销原生那条。返回失败原因，没注册过不算错。
    @discardableResult
    private func unregisterServiceQuietly() -> Error? {
        let status = serviceStatus()
        guard status != .notRegistered, status != .notFound else { return nil }
        do { try SMAppService.mainApp.unregister(); return nil }
        catch { return error }
    }

    // MARK: LaunchAgent 兜底

    private func installAgent() throws {
        let plist = LoginAgentPlist.make(
            label: Self.label,
            executable: Bundle.main.executableURL?.path ?? Bundle.main.bundleURL.path
        )
        let url = LoginAgentPlist.url
        do {
            try FileManager.default.createDirectory(
                at: url.deletingLastPathComponent(),
                withIntermediateDirectories: true)
            try plist.write(to: url, options: .atomic)
        } catch {
            throw LoginItemError.agentNotLoaded(
                "写 LaunchAgent 失败：\(error.localizedDescription)")
        }
        // bootstrap 让它这次就装上；已经装过的话 bootout 后重装。
        // 只写文件不加载，开机那一下之外它是不生效的。
        let target = "gui/\(getuid())"
        if !launchctl(["bootstrap", target, url.path]).ok {
            _ = launchctl(["bootout", "\(target)/\(Self.label)"])
            if !launchctl(["bootstrap", target, url.path]).ok {
                let out = launchctl(["print", "\(target)/\(Self.label)"]).out
                throw LoginItemError.agentNotLoaded(
                    "launchctl 装不上 LaunchAgent：\(out.trimmingCharacters(in: .whitespacesAndNewlines))")
            }
        }
        guard isAgentLoaded() else {
            throw LoginItemError.agentNotLoaded("LaunchAgent 已写入但 launchctl 没装上")
        }
    }

    /// 兜底那条是不是真的装着。两个条件都要满足：
    /// 文件在（开机时 launchd 读得到）+ job 已加载（这次登录里就生效）。
    private func isAgentLoaded() -> Bool {
        guard FileManager.default.fileExists(atPath: LoginAgentPlist.url.path) else { return false }
        return launchctl(["print", "gui/\(getuid())/\(Self.label)"]).ok
    }

    private func removeAgent() {
        let url = LoginAgentPlist.url
        guard FileManager.default.fileExists(atPath: url.path) else { return }
        _ = launchctl(["bootout", "gui/\(getuid())/\(Self.label)"])
        try? FileManager.default.removeItem(at: url)
    }

    @discardableResult
    private func launchctl(_ args: [String]) -> (ok: Bool, out: String) {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/bin/launchctl")
        p.arguments = args
        let pipe = Pipe()
        p.standardOutput = pipe
        p.standardError = pipe
        p.standardInput = FileHandle.nullDevice
        do { try p.run() } catch { return (false, error.localizedDescription) }
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        p.waitUntilExit()
        return (p.terminationStatus == 0, String(data: data, encoding: .utf8) ?? "")
    }

    private func serviceStatus() -> SMAppService.Status {
        SMAppService.mainApp.status
    }
}

/// 登录项兜底用的 plist。抽成纯函数是为了能单测 —— 真正注册成功与否
/// 要到开机那一刻才知道，而 plist 写错字段是当场就能验的。
public enum LoginAgentPlist {

    public static var url: URL {
        URL(fileURLWithPath: NSHomeDirectory())
            .appendingPathComponent("Library/LaunchAgents", isDirectory: true)
            .appendingPathComponent("\(LoginItem.label).plist")
    }

    /// launchd 起来时的 PATH 只有系统那几个目录，
    /// Homebrew 的东西全都不在里面。显式写进去，
    /// 免得开机自启的实例和手动双击的实例行为不一样。
    public static let loginPath = "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"

    public static func make(label: String, executable: String) -> Data {
        let dict: [String: Any] = [
            "Label": label,
            "ProgramArguments": [executable],
            "RunAtLoad": true,
            // 菜单栏 App 没有窗口可关，进程自己活着就行，崩了就等下次开机
            "KeepAlive": false,
            // 只在图形登录会话里跑，ssh / 后台会话不该把它拉起来
            "LimitLoadToSessionType": "Aqua",
            "ProcessType": "Interactive",
            "EnvironmentVariables": ["PATH": loginPath]
        ]
        return (try? PropertyListSerialization.data(
            fromPropertyList: dict, format: .xml, options: 0)) ?? Data()
    }
}
