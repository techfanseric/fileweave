import XCTest
import AppKit
import CryptoKit
@testable import FileWeaveMacKit

final class ScreenshotTests: XCTestCase {
    func testScreenshotScopeRejectsOtherPhotosTraversalAndDuplicateNamesRemainDistinct() throws {
        let data = Data(#"{"ok":true,"items":[{"id":"Pictures/Screenshots/same.png","path":"Pictures/Screenshots/same.png","size":4,"mtime":20},{"id":"DCIM/Screenshots/same.png","path":"DCIM/Screenshots/same.png","size":4,"mtime":10}]}"#.utf8)
        let items = try PhoneScreenshot.decodeListing(data)
        XCTAssertEqual(items.count, 2); XCTAssertNotEqual(items[0].id, items[1].id)
        for path in ["Pictures/Camera/a.png", "Pictures/Screenshots/../a.png", "/Pictures/Screenshots/a.png", "Pictures/Screenshots/.private/a.png", "Pictures/ScreenshotsBackup/a.png"] {
            XCTAssertFalse(PhoneScreenshot.isSafe(path, trash: false), path)
        }
        XCTAssertTrue(PhoneScreenshot.isSafe("Pictures/Screenshots/.Trash/123/a.png", trash: true))
    }
    func testFreshPairedAddressFallbackExpiresAndNeverAcceptsUnknownDevice() throws {
        let token = "test-only-token"
        let record = PairedDeviceRecord(id: "id", name: "Phone", token: token, folderFp: "pinned")
        let device = SHA256.hash(data: Data(token.utf8)).map { String(format: "%02x", $0) }.joined()
        let data = try JSONSerialization.data(withJSONObject: [["device": device, "host": "192.168.10.9", "seen": 1000]])
        XCTAssertEqual(FolderDiscovery.recentPeers(data: data, records: [record], now: Date(timeIntervalSince1970: 1010)).count, 1)
        XCTAssertTrue(FolderDiscovery.recentPeers(data: data, records: [], now: Date(timeIntervalSince1970: 1010)).isEmpty)
        XCTAssertTrue(FolderDiscovery.recentPeers(data: data, records: [record], now: Date(timeIntervalSince1970: 1201)).isEmpty)
    }

    @MainActor
    func testCopyUsesOriginalPixelDimensionsAndDoesNotModifySystemClipboard() throws {
        let rep = try XCTUnwrap(NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: 2000, pixelsHigh: 1000, bitsPerSample: 8,
                                               samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0))
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("\(UUID()).png")
        defer { try? FileManager.default.removeItem(at: file) }
        try XCTUnwrap(rep.representation(using: .png, properties: [:])).write(to: file)
        let board = NSPasteboard(name: .init("FileWeave.ScreenshotTest.\(UUID())"))
        defer { board.releaseGlobally() }
        try ScreenshotClipboard.copy(url: file, to: board)
        let copied = try XCTUnwrap(board.data(forType: .png))
        let bitmap = try XCTUnwrap(NSBitmapImageRep(data: copied))
        XCTAssertEqual(bitmap.pixelsWide, 2000); XCTAssertEqual(bitmap.pixelsHigh, 1000)
        XCTAssertNotNil(board.data(forType: .tiff))
    }
    func testRecordingSharingOffIsDifferentFromEmptyRecordingList() throws {
        let result = try FileWeaveFolderSource.snapshot(fromTree: Data(#"{"ok":true,"enabled":false,"items":[]}"#.utf8))
        XCTAssertFalse(result.recordingsEnabled)
        let legacy = try FileWeaveFolderSource.snapshot(fromTree: Data(#"{"ok":true,"items":[]}"#.utf8))
        XCTAssertTrue(legacy.recordingsEnabled)
    }
}
