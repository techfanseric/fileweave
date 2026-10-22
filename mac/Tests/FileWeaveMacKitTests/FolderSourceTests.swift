import XCTest
@testable import FileWeaveMacKit

/// 文件夹共享协议（Mac 侧客户端）的解析回归：JSON、TXT、Range。
///
/// 协议两端（Android / bridge 侧）在并行开发，字段形态只有这里钉住，
/// 对不上才能当场红，而不是连上真机才发现。
final class FolderSourceTests: XCTestCase {

    // MARK: /v1/tree JSON

    private let treeJSON = """
    {
      "ok": true,
      "items": [
        {"id": "doc1", "path": "CallRecord/通话-14743662996-202610121120.m4a",
         "size": 9437184, "mtime": 1760212800000},
        {"id": "doc2", "path": "Sounds/微信-Yan-202610110803.m4a",
         "size": 1048576, "mtime": 1760126400000,
         "trashPath": ".Trash/1791591461589/Sounds/微信-Yan-202610110803.m4a",
         "movedAt": 1791591461589},
        {"id": "doc3", "path": "Sounds/未知批次.m4a",
         "size": 8, "mtime": 1760126400000,
         "trashPath": ".Trash/not-a-stamp/Sounds/未知批次.m4a",
         "movedAt": 0},
        {"id": "", "path": "", "size": 1, "mtime": 1760212800000}
      ]
    }
    """

    func testTreeDecodesLiveAndTrash() throws {
        let snap = try FileWeaveFolderSource.snapshot(fromTree: Data(treeJSON.utf8))
        XCTAssertEqual(snap.live.count, 1)
        XCTAssertEqual(snap.trash.count, 2)

        let live = try XCTUnwrap(snap.live.first)
        XCTAssertEqual(live.remotePath, "CallRecord/通话-14743662996-202610121120.m4a")
        XCTAssertEqual(live.remoteId, "doc1")
        XCTAssertEqual(live.kind, .call)
        XCTAssertEqual(live.subject, "14743662996")
        // 契约：mtime 是毫秒
        XCTAssertEqual(live.modifiedAt.timeIntervalSince1970, 1_760_212_800, accuracy: 0.001)

        let trash = try XCTUnwrap(snap.trash.first { $0.remoteId == "doc2" })
        XCTAssertTrue(trash.isTrashed)
        XCTAssertNotNil(trash.trashPath)
        let movedAt = try XCTUnwrap(trash.movedAt)
        XCTAssertEqual(movedAt.timeIntervalSince1970, 1_791_591_461.589, accuracy: 0.001)
    }

    /// 契约：movedAt 为 0（批次名读不出时间戳）按「不知道」处理，绝不能变成 1970-01-01
    func testTreeTreatsZeroMovedAtAsUnknown() throws {
        let snap = try FileWeaveFolderSource.snapshot(fromTree: Data(treeJSON.utf8))
        let unknown = try XCTUnwrap(snap.trash.first { $0.remoteId == "doc3" })
        XCTAssertNil(unknown.movedAt)
    }

    /// 空 path 的条目必须整条丢弃，不能造出一个 id 为空的录音占位
    func testTreeSkipsEmptyPaths() throws {
        let snap = try FileWeaveFolderSource.snapshot(fromTree: Data(treeJSON.utf8))
        XCTAssertEqual(snap.live.count + snap.trash.count, 3)
    }

    func testTreeRejectsOkFalse() throws {
        let bad = #"{"ok": false, "items": []}"#
        XCTAssertThrowsError(try FileWeaveFolderSource.snapshot(fromTree: Data(bad.utf8)))
    }

    func testTreeToleratesMissingOptionalFields() throws {
        let minimal = """
        {"ok": true, "items": [{"id": "a", "path": "Sounds/x.m4a", "size": 5, "mtime": 1000}]}
        """
        let snap = try FileWeaveFolderSource.snapshot(fromTree: Data(minimal.utf8))
        let rec = try XCTUnwrap(snap.live.first)
        XCTAssertNil(rec.trashPath)
        XCTAssertNil(rec.movedAt)
    }

    // MARK: Range 响应

    func testParseContentRange() throws {
        let r = try XCTUnwrap(FileWeaveFolderSource.parseContentRange("bytes 0-99/1000"))
        XCTAssertEqual(r.start, 0)
        XCTAssertEqual(r.end, 99)
        XCTAssertEqual(r.total, 1000)

        let r2 = try XCTUnwrap(FileWeaveFolderSource.parseContentRange("bytes 512-1023/2048"))
        XCTAssertEqual(r2.start, 512)
        XCTAssertEqual(r2.total, 2048)
    }

    func testParseContentRangeRejectsGarbage() {
        XCTAssertNil(FileWeaveFolderSource.parseContentRange("0-99/1000"))
        XCTAssertNil(FileWeaveFolderSource.parseContentRange("bytes 100-0/1000"))
        XCTAssertNil(FileWeaveFolderSource.parseContentRange("bytes 0-1000/1000"))
        XCTAssertNil(FileWeaveFolderSource.parseContentRange("bytes */1000"))
        XCTAssertNil(FileWeaveFolderSource.parseContentRange(""))
    }

    // MARK: Bonjour TXT

    func testParseTXTReadsVAndFp() throws {
        let txt = NetService.data(fromTXTRecord: ["v": Data("1".utf8),
                                                  "fp": Data("ABCDEF0123".utf8)])
        let dict = FolderDiscovery.parseTXT(txt)
        XCTAssertEqual(dict["v"], "1")
        XCTAssertEqual(dict["fp"], "ABCDEF0123")
    }

    // MARK: paired-devices.json

    func testPairedDevicesParse() {
        let json = """
        {
          "8f2a": {"name": "Honor Magic7", "token": "t-1234567890abcdefghij",
                   "folderFp": "AABBCCDD"},
          "91bc": {"name": "OnePlus 5", "token": "t-0987654321abcdefghij"}
        }
        """
        let records = PairedDeviceStore.parse(Data(json.utf8))
        XCTAssertEqual(records.count, 2)

        let honor = records.first { $0.name == "Honor Magic7" }
        XCTAssertEqual(honor?.id, "8f2a")
        // fp 统一小写，和证书摘要的计算结果直接可比
        XCTAssertEqual(honor?.folderFp, "aabbccdd")

        let oneplus = records.first { $0.name == "OnePlus 5" }
        XCTAssertNil(oneplus?.folderFp, "没开过文件夹共享的设备没有 fp")
    }

    func testPairedDevicesParseSkipsMalformedEntries() {
        let json = """
        {
          "good": {"name": "A", "token": "t-ok"},
          "no-name": {"token": "t-x"},
          "junk": "not-a-dict"
        }
        """
        let records = PairedDeviceStore.parse(Data(json.utf8))
        XCTAssertEqual(records.count, 1)
        XCTAssertEqual(records.first?.id, "good")
    }

    func testPairedDevicesParseRejectsNonObject() {
        XCTAssertTrue(PairedDeviceStore.parse(Data("[1,2,3]".utf8)).isEmpty)
        XCTAssertTrue(PairedDeviceStore.parse(Data("garbage".utf8)).isEmpty)
    }

    // MARK: 发现匹配（不碰网络）

    /// fp 对不上的广播绝不发起连接 —— 广播谁都能发，
    /// 配对记录才是用户亲手批准过的信任。
    func testDiscoverPairedIgnoresUnmatchedFp() {
        let dir = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("fw-test-paired-\(UUID().uuidString)", isDirectory: true)
        let file = dir.appendingPathComponent("paired-devices.json")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let json = """
        {"8f2a": {"name": "Honor", "token": "t-1234567890abcdefghij",
                  "folderFp": "aabbccdd"}}
        """
        try? Data(json.utf8).write(to: file)
        let store = PairedDeviceStore(url: file)

        // 广播里的 fp 和配对记录不一致：这里绝不能去连 127.0.0.1
        let strangers: (TimeInterval) -> [FolderDiscovery.Candidate] = { _ in
            [FolderDiscovery.Candidate(name: "stranger", host: "127.0.0.1",
                                       port: 1, fp: "00000000")]
        }
        XCTAssertNil(FileWeaveFolderSource.discoverPaired(store: store, browse: strangers))

        // 没有任何配对记录带 folderFp 时，广播再多也一律不连。
        // （另建一个文件给第二个 store：PairedDeviceStore 按文件属性缓存，
        //  同一路径快速改写两次可能因时间戳粒度撞上缓存。）
        let fileNoFp = dir.appendingPathComponent("paired-devices-nofp.json")
        let jsonNoFp = """
        {"91bc": {"name": "OnePlus", "token": "t-0987654321abcdefghij"}}
        """
        try? Data(jsonNoFp.utf8).write(to: fileNoFp)
        let storeNoFp = PairedDeviceStore(url: fileNoFp)
        let any: (TimeInterval) -> [FolderDiscovery.Candidate] = { _ in
            [FolderDiscovery.Candidate(name: "x", host: "127.0.0.1",
                                       port: 1, fp: "aabbccdd")]
        }
        XCTAssertNil(FileWeaveFolderSource.discoverPaired(store: storeNoFp, browse: any))

        try? FileManager.default.removeItem(at: dir)
    }
}
