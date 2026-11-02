import XCTest
@testable import FileWeaveMacKit

final class WorkspaceActivityTests: XCTestCase {
    @MainActor
    func testSharedFileDownloadIsVisibleAndExplicitlyCancelable() {
        let app = AppState(previewOnly: true)
        var canceled = false
        app.beginFileTransfer(L("正在下载 {0} 项…", 3), cancel: { canceled = true })
        XCTAssertTrue(app.hasActiveTask)
        XCTAssertEqual(app.activeTaskTitle, L("正在下载 {0} 项…", 3))
        // Visibility changes and sidebar navigation do not cancel an explicit task.
        app.isPanelVisible = true
        app.isPanelVisible = false
        XCTAssertFalse(canceled)
        app.cancelFileTransfer()
        XCTAssertTrue(canceled)
        app.finishFileTransfer(L("已取消下载"), failed: false)
        XCTAssertFalse(app.hasActiveTask)
        XCTAssertNil(app.fileTransferTitle)
        XCTAssertEqual(app.infoText, L("已取消下载"))
        XCTAssertNil(app.errorText)
        app.stop()
    }

    @MainActor
    func testFileDownloadFailureRemainsAnErrorAfterActivityEnds() {
        let app = AppState(previewOnly: true)
        app.beginFileTransfer(L("正在下载 {0} 项…", 1), cancel: {})
        app.finishFileTransfer(L("文件传输不完整，请重试"), failed: true)
        XCTAssertFalse(app.hasActiveTask)
        XCTAssertEqual(app.errorText, L("文件传输不完整，请重试"))
        app.stop()
    }

    @MainActor
    func testDelayedRootResponseCannotReplaceANewerDirectoryOrScope() async {
        let session = SharedFilesSession()
        let root = session.beginLoad()
        let slowRoot = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 40_000_000)
            return session.commit(root, items: [PhoneFile(name: "Old root", directory: true, size: 0)], effectivePath: ["Old root"], rootElided: true, enabled: true)
        }
        session.path = ["Documents"]
        let child = session.beginLoad()
        let fastChild = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 2_000_000)
            return session.commit(child, items: [PhoneFile(name: "Current.pdf", directory: false, size: 12)], effectivePath: ["Documents"], rootElided: false, enabled: true)
        }
        let childAccepted = await fastChild.value
        let rootAccepted = await slowRoot.value
        XCTAssertTrue(childAccepted)
        XCTAssertFalse(rootAccepted)
        XCTAssertEqual(session.path, ["Documents"])
        XCTAssertEqual(session.items.map(\.name), ["Current.pdf"])
        let oldScope = session.beginLoad()
        session.selected = true
        XCTAssertFalse(session.commit(oldScope, items: [], effectivePath: [], rootElided: false, enabled: true))
    }

    @MainActor
    func testInboxOverviewReadsLatestWithoutCreatingAnEmptyFolder() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let inbox = InboxSession(root: root)
        XCTAssertNil(inbox.overviewCount)
        inbox.reload()
        XCTAssertTrue(inbox.hasLoaded)
        XCTAssertEqual(inbox.overviewCount, 0)
        XCTAssertTrue(inbox.files.isEmpty)
        XCTAssertFalse(FileManager.default.fileExists(atPath: root.path))
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let old = root.appendingPathComponent("Older.txt")
        let recent = root.appendingPathComponent("会议记录.txt")
        try Data("older".utf8).write(to: old)
        try Data("recent".utf8).write(to: recent)
        try Data().write(to: root.appendingPathComponent(".incoming-hidden"))
        try FileManager.default.setAttributes([.modificationDate: Date(timeIntervalSince1970: 1000)], ofItemAtPath: old.path)
        try FileManager.default.setAttributes([.modificationDate: Date(timeIntervalSince1970: 2000)], ofItemAtPath: recent.path)
        inbox.reload()
        XCTAssertEqual(inbox.files.count, 2)
        XCTAssertEqual(inbox.overviewCount, 2)
        let invalidInbox = InboxSession(root: old)
        invalidInbox.reload()
        XCTAssertNotNil(invalidInbox.loadError)
        XCTAssertNil(invalidInbox.overviewCount)
        XCTAssertEqual(inbox.latestFile?.lastPathComponent, "会议记录.txt")
        XCTAssertNil(inbox.loadError)
    }

    func testEnglishAndLegacyChineseTranscriptMetadataBothParse() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        for (headers, speaker) in [
            ("Duration: 00:03:00\nModel: Paraformer\nSentences: 1\n", "Speaker"),
            ("时长：00:03:00\n模型：Paraformer\n句子：1 句\n", "说话人")
        ] {
            let file = folder.appendingPathComponent("transcript.txt")
            let body = headers + "\n────────────────\n\n[00:00 → 00:12] \(speaker) 1\n原文保持原样 {0}\n"
            try body.write(to: file, atomically: true, encoding: .utf8)
            let doc = try XCTUnwrap(TranscriptDocument.parse(remotePath: "recording.m4a", title: "会议录音", txtURL: file))
            XCTAssertEqual(doc.duration, "00:03:00")
            XCTAssertEqual(doc.model, "Paraformer")
            XCTAssertEqual(doc.segments.first?.speaker, "1")
            XCTAssertEqual(doc.segments.first?.text, "原文保持原样 {0}")
            XCTAssertEqual(doc.title, "会议录音")
        }
    }
}
