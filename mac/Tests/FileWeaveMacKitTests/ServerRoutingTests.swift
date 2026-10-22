import XCTest
@testable import FileWeaveMacKit

/// 配对通道的端点路由与重连退避回归测试。
///
/// 旧版（荣耀录音监视器）这里的主题是 adb 双 server 分流；FileWeave 只剩
/// 一条证书固定的 HTTPS 通道，要钉住的变成两件事：
/// 1. 重连退避序列 —— 序列错了要么资源白烧（太密），要么人到场还要干等（太松）；
/// 2. 端点 URL 必须严格落在契约路径上（/v1/status、/v1/tree、/v1/file…），
///    中文文件名等查询参数必须正确转义 —— 契约字段一旦对不上，
///    手机端和 Mac 端任何一边的小改动都会变成运行期才炸的静默失配。
final class ServerRoutingTests: XCTestCase {

    // MARK: 重连退避序列

    /// 刚断线立刻试；之后 5 → 10 → 20 → 40 → 60 秒封顶。
    func testReconnectBackoffEscalatesAndCaps() {
        XCTAssertEqual(AppState.reconnectCooldown(afterConsecutiveFailures: 0), 0)
        XCTAssertEqual(AppState.reconnectCooldown(afterConsecutiveFailures: 1), 5)
        XCTAssertEqual(AppState.reconnectCooldown(afterConsecutiveFailures: 2), 10)
        XCTAssertEqual(AppState.reconnectCooldown(afterConsecutiveFailures: 3), 20)
        XCTAssertEqual(AppState.reconnectCooldown(afterConsecutiveFailures: 4), 40)
        XCTAssertEqual(AppState.reconnectCooldown(afterConsecutiveFailures: 5), 60)
        XCTAssertEqual(AppState.reconnectCooldown(afterConsecutiveFailures: 99), 60)
    }

    // MARK: 契约端点路径

    func testStatusAndTreeEndpoints() throws {
        let status = try XCTUnwrap(
            FileWeaveFolderSource.endpointURL(host: "Phone.local", port: 8790, path: "/v1/status"))
        XCTAssertEqual(status.path, "/v1/status")
        XCTAssertEqual(status.port, 8790)

        let tree = try XCTUnwrap(
            FileWeaveFolderSource.endpointURL(host: "192.168.1.8", port: 8790, path: "/v1/tree"))
        XCTAssertEqual(tree.path, "/v1/tree")
        XCTAssertNil(tree.query)
    }

    /// 下载端点带 path 查询：中文与子目录必须原样转义、解码回来一字不差
    func testFileEndpointEncodesPathQuery() throws {
        let raw = "CallRecord/通话-147 4366 2996-202610121120.m4a"
        let url = try XCTUnwrap(
            FileWeaveFolderSource.endpointURL(host: "Phone.local", port: 8790,
                                              path: "/v1/file", query: ["path": raw]))
        XCTAssertEqual(url.path, "/v1/file")
        let comps = try XCTUnwrap(URLComponents(url: url, resolvingAgainstBaseURL: false))
        let item = try XCTUnwrap(comps.queryItems?.first { $0.name == "path" })
        XCTAssertEqual(item.value, raw)
    }
}
