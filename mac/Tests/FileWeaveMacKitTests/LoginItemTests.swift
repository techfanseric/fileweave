import XCTest
@testable import FileWeaveMacKit

/// 登录项兜底 plist 的字段级回归。
///
/// 「写 plist 成功」不等于「开机能拉起来」——字段写错（比如少了 RunAtLoad）
/// 要到下次开机才发现，那时用户已经站在一个没图标的登录桌面前了。
/// 这里把关键字段逐个钉住。
final class LoginAgentPlistTests: XCTestCase {

    private func dict(_ data: Data) throws -> [String: Any] {
        let obj = try PropertyListSerialization.propertyList(
            from: data, options: [], format: nil)
        return try XCTUnwrap(obj as? [String: Any])
    }

    func testLabelAndExecutable() throws {
        let exe = "/Applications/FileWeave Mac.app/Contents/MacOS/FileWeaveMac"
        let d = try dict(LoginAgentPlist.make(label: "com.test.app", executable: exe))

        XCTAssertEqual(d["Label"] as? String, "com.test.app")
        XCTAssertEqual(d["ProgramArguments"] as? [String], [exe])
    }

    /// 少了 RunAtLoad，plist 写得再漂亮也不会在登录时启动
    func testRunAtLoadIsOn() throws {
        let d = try dict(LoginAgentPlist.make(label: "a", executable: "/x"))
        XCTAssertEqual(d["RunAtLoad"] as? Bool, true)
    }

    /// KeepAlive 会让进程一挂就被重启，菜单栏 App 不需要
    func testKeepAliveIsOff() throws {
        let d = try dict(LoginAgentPlist.make(label: "a", executable: "/x"))
        XCTAssertEqual(d["KeepAlive"] as? Bool, false)
    }

    /// ssh / 后台会话不该把它拉起来
    func testLimitedToAquaSession() throws {
        let d = try dict(LoginAgentPlist.make(label: "a", executable: "/x"))
        XCTAssertEqual(d["LimitLoadToSessionType"] as? String, "Aqua")
    }

    /// launchd 给的 PATH 里没有 Homebrew，开机自启的实例会找不到 python3 / ffmpeg
    func testLoginPathIncludesHomebrew() throws {
        let d = try dict(LoginAgentPlist.make(label: "a", executable: "/x"))
        let env = try XCTUnwrap(d["EnvironmentVariables"] as? [String: String])
        let path = try XCTUnwrap(env["PATH"])
        XCTAssertTrue(path.contains("/opt/homebrew/bin"), "PATH 缺少 Homebrew：\(path)")
        XCTAssertTrue(path.contains("/usr/bin"))
    }

    func testPathIsUnderUserLaunchAgents() {
        XCTAssertTrue(
            LoginAgentPlist.url.path
                == NSHomeDirectory() + "/Library/LaunchAgents/org.fileweave.mac.dev.plist"
                || LoginAgentPlist.url.path.contains("/Library/LaunchAgents/"),
            "plist 不该写到 LaunchAgents 之外：\(LoginAgentPlist.url.path)")
    }
}
