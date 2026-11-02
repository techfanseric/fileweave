import XCTest
import AVFoundation
import AppKit
import PDFKit
@testable import FileWeaveMacKit

final class FilePreviewTests: XCTestCase {
    func testPhonePreviewFamiliesAndPropertyFallback() {
        for ext in ["png", "jpg", "heic", "gif"] { XCTAssertEqual(kind("a.\(ext)"), .image) }
        for ext in ["mp4", "mov", "m4a", "wav", "mp3"] { XCTAssertEqual(kind("a.\(ext)"), .media) }
        for ext in ["txt", "md", "xml", "log", "html", "htm"] { XCTAssertEqual(kind("a.\(ext)"), .text) }
        XCTAssertEqual(kind("a.PDF"), .pdf)
        XCTAssertEqual(kind("a.zip"), .properties)
        XCTAssertEqual(FilePreviewKind.resolve(PhoneFile(name: "Folder.pdf", directory: true, size: 0)), .properties)
    }
    private func kind(_ name: String) -> FilePreviewKind { .resolve(PhoneFile(name: name, directory: false, size: 100)) }

    func testPrivateCacheCleanupAndCrashResidueDoNotDeleteOriginals() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let slot = FilePreviewStore(root: root)
        let url = try slot.destination(for: "notes.txt")
        try Data("preview".utf8).write(to: url)
        let original = root.appendingPathComponent("original.txt")
        try Data("original".utf8).write(to: original)
        XCTAssertThrowsError(try slot.destination(for: "../outside"))
        XCTAssertThrowsError(try slot.destination(for: ".."))
        slot.clear()
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: original.path))
        let abandoned = root.appendingPathComponent("abandoned")
        try FileManager.default.createDirectory(at: abandoned, withIntermediateDirectories: true)
        try FileManager.default.setAttributes([.modificationDate: Date(timeIntervalSince1970: 1)], ofItemAtPath: abandoned.path)
        FilePreviewStore.sweep(root: root, before: Date().addingTimeInterval(-3600))
        XCTAssertFalse(FileManager.default.fileExists(atPath: abandoned.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: original.path))
    }

    @MainActor
    func testCanceledLateDownloadCannotReplaceSelectionAndRemovesItsFile() async throws {
        var destinations: [URL] = []
        let source = FileWeaveFolderSource(host: "127.0.0.1", port: 1, fp: "unused", token: "unused", deviceName: "Test")
        let model = FilePreviewModel { _, path, _, destination, _ in
            destinations.append(destination)
            if path == "old.txt" {
                // Simulate a transport returning after cancellation.
                await withCheckedContinuation { continuation in
                    DispatchQueue.global().asyncAfter(deadline: .now() + 0.25) { continuation.resume() }
                }
            }
            try Data(path.utf8).write(to: destination)
        }
        let old = Task { await model.load(file: PhoneFile(name: "old.txt", directory: false, size: 7), path: "old.txt", source: source, selected: false) }
        try await Task.sleep(nanoseconds: 210_000_000)
        await model.load(file: PhoneFile(name: "new.txt", directory: false, size: 7), path: "new.txt", source: source, selected: true)
        await old.value
        XCTAssertEqual(model.text, "new.txt")
        XCTAssertEqual(destinations.count, 2)
        XCTAssertFalse(FileManager.default.fileExists(atPath: destinations[0].path))
        model.clear()
        XCTAssertNil(model.text)
        XCTAssertTrue(destinations.allSatisfy { !FileManager.default.fileExists(atPath: $0.path) })
    }

    @MainActor
    func testOversizedAndUnknownFilesNeverStartDownload() async {
        var downloads = 0
        let model = FilePreviewModel { _, _, _, _, _ in downloads += 1 }
        let source = FileWeaveFolderSource(host: "127.0.0.1", port: 1, fp: "unused", token: "unused", deviceName: "Test")
        for file in [PhoneFile(name: "huge.mp4", directory: false, size: FilePreviewStore.byteLimit + 1),
                     PhoneFile(name: "unknown.zip", directory: false, size: 100)] {
            await model.load(file: file, path: file.name, source: source, selected: false)
            XCTAssertNil(model.player)
            XCTAssertFalse(model.loading)
        }
        XCTAssertEqual(downloads, 0)
    }

    @MainActor
    func testExpiryClearsPreviewButKeepsLocalOriginal() async throws {
        let original = FileManager.default.temporaryDirectory.appendingPathComponent("\(UUID()).txt")
        defer { try? FileManager.default.removeItem(at: original) }
        try Data("local contents".utf8).write(to: original)
        let model = FilePreviewModel(lifetime: 0.03)
        await model.load(file: PhoneFile(name: "notes.txt", directory: false, size: 14), path: "notes.txt", source: nil, selected: false, localURL: original)
        XCTAssertEqual(model.text, "local contents")
        try await Task.sleep(nanoseconds: 80_000_000)
        XCTAssertNil(model.text)
        XCTAssertNotNil(model.message)
        XCTAssertTrue(FileManager.default.fileExists(atPath: original.path))
    }

    @MainActor
    func testLocalMediaSupportsRandomSeekAndClearInvalidatesPlayer() async throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("\(UUID()).wav")
        defer { try? FileManager.default.removeItem(at: url) }
        // Two seconds of PCM silence, with a real seekable WAV container.
        var wav = Data()
        func ascii(_ string: String) { wav.append(Data(string.utf8)) }
        func u32(_ value: UInt32) { withUnsafeBytes(of: value.littleEndian) { wav.append(contentsOf: $0) } }
        func u16(_ value: UInt16) { withUnsafeBytes(of: value.littleEndian) { wav.append(contentsOf: $0) } }
        ascii("RIFF"); u32(32036); ascii("WAVEfmt "); u32(16); u16(1); u16(1); u32(8000); u32(16000); u16(2); u16(16); ascii("data"); u32(32000)
        wav.append(Data(repeating: 0, count: 32000)); try wav.write(to: url)
        let model = FilePreviewModel()
        await model.load(file: PhoneFile(name: "test.wav", directory: false, size: Int64(wav.count)), path: "test.wav", source: nil, selected: false, localURL: url)
        let player = try XCTUnwrap(model.player)
        let seeked = await player.seek(to: CMTime(seconds: 1, preferredTimescale: 8000))
        XCTAssertTrue(seeked)
        XCTAssertEqual(player.currentTime().seconds, 1, accuracy: 0.02)
        let rewound = await player.seek(to: .zero)
        XCTAssertTrue(rewound)
        XCTAssertEqual(player.currentTime().seconds, 0, accuracy: 0.02)
        model.clear() // same operation used on close, lock, unshare and selection change
        XCTAssertNil(model.player)
        XCTAssertNil(player.currentItem)
        XCTAssertEqual(player.rate, 0)
    }

    @MainActor
    func testImagePDFAndFolderSelectionCanBePreviewedWithoutChangingOriginals() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let rep = try XCTUnwrap(NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: 8, pixelsHigh: 8, bitsPerSample: 8,
                                               samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0))
        let png = root.appendingPathComponent("test.png")
        try XCTUnwrap(rep.representation(using: .png, properties: [:])).write(to: png)
        let document = PDFDocument()
        document.insert(try XCTUnwrap(PDFPage(image: try XCTUnwrap(NSImage(contentsOf: png)))), at: 0)
        let pdf = root.appendingPathComponent("test.pdf")
        XCTAssertTrue(document.write(to: pdf))
        let model = FilePreviewModel()
        for url in [png, pdf] {
            let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
            await model.load(file: PhoneFile(name: url.lastPathComponent, directory: false, size: Int64(size)), path: url.path, source: nil, selected: false, localURL: url)
            if url == png { XCTAssertNotNil(model.image) }
            else { XCTAssertEqual(model.document?.pageCount, 1); XCTAssertNil(model.image) }
        }
        await model.load(file: PhoneFile(name: "Folder", directory: true, size: 0), path: root.path, source: nil, selected: false, localURL: root)
        XCTAssertEqual(model.childCount, 2)
        XCTAssertNil(model.document)
        model.clear()
        XCTAssertTrue(FileManager.default.fileExists(atPath: png.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: pdf.path))
    }

    func testTextBOMAndPartialUTF8Prefix() {
        XCTAssertEqual(FilePreviewModel.decodeText(Data([0xff, 0xfe, 0x41, 0x00])), "A")
        var prefix = Data("文字".utf8); prefix.removeLast()
        XCTAssertEqual(FilePreviewModel.decodeText(prefix, truncated: true), "文")
    }
}
