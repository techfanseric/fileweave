import XCTest
@testable import FileWeaveMacKit

/// 「新增」判断（水位线 + 单独已读）与转录重试参数的回归测试。
///
/// 只走不落盘的路径：直接构造 Library、赋值字段、调纯读方法，
/// 绝不碰会写 library.json 的入口 —— 测试不该动真实库文件。
final class NewBadgeAndRetryTests: XCTestCase {

    /// 文件名不带可解析的时间戳，displayTime 就落在 modifiedAt 上，测试可控。
    /// path 用契约里的相对路径形态（相对共享根）。
    private func rec(_ name: String, modifiedAt: Date) -> Recording {
        Recording(remotePath: "CallRecord/\(name)",
                  size: 10, modifiedAt: modifiedAt)!
    }

    // MARK: 新增判断

    func testNoWatermarkMeansNothingNew() {
        let lib = Library()
        let r = rec("a.m4a", modifiedAt: Date(timeIntervalSince1970: 1_100))
        XCTAssertFalse(lib.isNew(r))
        XCTAssertTrue(lib.newlySeen(in: [r]).isEmpty)
    }

    func testOnlyAfterWatermarkIsNew() {
        var lib = Library()
        lib.seenWatermark = Date(timeIntervalSince1970: 1_000)
        let old = rec("old.m4a", modifiedAt: Date(timeIntervalSince1970: 900))
        let new = rec("new.m4a", modifiedAt: Date(timeIntervalSince1970: 1_100))
        XCTAssertFalse(lib.isNew(old))
        XCTAssertTrue(lib.isNew(new))
        XCTAssertEqual(lib.newlySeen(in: [old, new]), [new])
    }

    func testTrashedIsNeverNew() {
        var lib = Library()
        lib.seenWatermark = Date(timeIntervalSince1970: 1_000)
        let t = Recording(remotePath: "CallRecord/t.m4a",
                          size: 10,
                          modifiedAt: Date(timeIntervalSince1970: 1_100),
                          trashPath: ".File_Recycle/1791591461589/CallRecord/t.m4a")!
        XCTAssertFalse(lib.isNew(t))
    }

    /// 转录过的单独去标：只压掉那一条，其他新增照旧 —— 水位线动不得
    func testIndividuallySeenSuppressesOnlyThatPath() {
        var lib = Library()
        lib.seenWatermark = Date(timeIntervalSince1970: 1_000)
        let a = rec("a.m4a", modifiedAt: Date(timeIntervalSince1970: 1_100))
        let b = rec("b.m4a", modifiedAt: Date(timeIntervalSince1970: 1_200))
        lib.individuallySeen = [a.remotePath]
        XCTAssertFalse(lib.isNew(a))
        XCTAssertTrue(lib.isNew(b))
        XCTAssertEqual(lib.newlySeen(in: [a, b]), [b])
    }

    /// 协议里的 path 就是相对路径，relativePath 必须原样使用，
    /// 不能再引入任何「扫描根目录」裁剪
    func testRelativePathIsRemotePathVerbatim() {
        let r = Recording(remotePath: "Sounds/微信-Yan-202610110803.m4a",
                          size: 1, modifiedAt: Date())!
        XCTAssertEqual(r.relativePath, "Sounds/微信-Yan-202610110803.m4a")
    }

    // MARK: 转录重试

    func testRetryDelayEscalatesButStaysBounded() {
        XCTAssertEqual(AppState.retryDelay(afterAttempt: 1), 3)
        XCTAssertEqual(AppState.retryDelay(afterAttempt: 2), 8)
        XCTAssertEqual(AppState.retryDelay(afterAttempt: 5), 15)
        XCTAssertLessThanOrEqual(
            AppState.retryDelay(afterAttempt: 99),
            AppState.retryDelay(afterAttempt: 100))
    }

    func testMissingEnvironmentIsNotRetryable() {
        XCTAssertFalse(AppState.isRetryableTranscriptionError(ASR.ASRError.notInstalled))
        XCTAssertFalse(AppState.isRetryableTranscriptionError(ASR.ASRError.scriptMissing))
    }

    func testTransientFailuresAreRetryable() {
        XCTAssertTrue(AppState.isRetryableTranscriptionError(ASR.ASRError.noOutput))
        XCTAssertTrue(AppState.isRetryableTranscriptionError(
            ASR.ASRError.ffmpegFailed("boom")))
        XCTAssertTrue(AppState.isRetryableTranscriptionError(
            ASR.ASRError.transcribeFailed("命令超时")))
        XCTAssertTrue(AppState.isRetryableTranscriptionError(URLError(.timedOut)))
    }

    /// 首跑 + 2 次重试；要改这个数，先想想底栏要不要变成重试流水账
    func testMaxAttemptsIsThree() {
        XCTAssertEqual(AppState.transcribeMaxAttempts, 3)
    }
}
