import XCTest
import CryptoKit
@testable import FileWeaveMacKit

final class CLITests: XCTestCase {
    func testParserRejectsIgnoredFlagsDuplicatesTraversalAndInvalidBounds() throws {
        for args in [
            ["files", "list", "--output", "/tmp/a"], ["files", "get", "--path", "a"],
            ["files", "list", "--path", "a", "--path", "b"], ["files", "list", "--path", "a/../b"],
            ["files", "read", "--path", "/etc/passwd"], ["files", "list", "--timeout", "nan"],
            ["devices", "--timeout", "0"], ["clipboard", "get", "--source", "other"],
            ["screenshots", "trash"], ["files", "get", "--path", "a", "--output", "x", "--max-bytes", "0"],
            ["unknown"], ["devices", "--trash"]
        ] { XCTAssertThrowsError(try CLIArguments(args), args.joined(separator: " ")) }
        let value = try CLIArguments(["--device", "id", "files", "get", "--path=Documents/会议.md", "--output", "/tmp/a b.md", "--pretty"])
        XCTAssertEqual(value.command, "files get")
        XCTAssertEqual(value.values["--path"], "Documents/会议.md")
        XCTAssertEqual(value.values["--output"], "/tmp/a b.md")
        XCTAssertTrue(value.has("--pretty"))
    }
    func testCapabilitiesAreMachineReadableAndReadOnlyIsTheDefault() throws {
        let value = CLIRuntime.capabilities()
        XCTAssertTrue(JSONSerialization.isValidJSONObject(value))
        XCTAssertEqual(value["schemaVersion"] as? Int, 1)
        XCTAssertEqual(value["interactive"] as? Bool, false)
        let commands = try XCTUnwrap(value["commands"] as? [[String: Any]])
        XCTAssertTrue(commands.contains { $0["command"] as? String == "screenshots restore" && $0["mutatesRemoteOrClipboard"] as? Bool == true })
        XCTAssertFalse(commands.contains { ($0["command"] as? String ?? "").contains("purge") })
    }
    func testMultiplePairedPhonesNeverSelectAnArbitraryDevice() async throws {
        let f = try CLIFixture(); defer { f.remove() }
        try f.writeRecords(count: 2)
        do { _ = try await f.runtime.execute(CLIArguments(["screenshots", "list"])); XCTFail() }
        catch let error as CLIError { XCTAssertEqual(error.code, "ambiguous_device") }
        XCTAssertTrue(f.http.calls.isEmpty)
        let devices = try await f.runtime.execute(CLIArguments(["devices"]))
        let text = String(decoding: try JSONSerialization.data(withJSONObject: devices), as: UTF8.self)
        XCTAssertFalse(text.contains("secret-token")); XCTAssertFalse(text.contains(f.pin))
    }
    func testDeviceSelectionPinsDiscoveryAndPreservesFileNamesAsData() async throws {
        let f = try CLIFixture(); defer { f.remove() }
        try f.writeRecords(count: 2)
        let maliciousName = "$(touch SHOULD_NOT_EXIST) — 中文.md"
        f.http.reply = { path, query, body in
            if path == "/v1/status" { return ["proto": 1] }
            XCTAssertEqual(path, "/v1/storage"); XCTAssertEqual(query["selected"], "1"); XCTAssertNil(body)
            return ["enabled": true, "version": 4, "items": [["name": maliciousName, "directory": false, "size": 3]]]
        }
        let value = try await f.runtime.execute(CLIArguments(["files", "list", "--device", "device-1", "--path", "Documents", "--selected"]))
        let items = try XCTUnwrap(value["items"] as? [[String: Any]])
        XCTAssertEqual(items.first?["path"] as? String, "Documents/" + maliciousName)
        XCTAssertEqual(f.http.endpoints.last?.token, "secret-token-1")
    }
    func testStaleScreenshotIDDoesNotIssueMutationAndRestoreUsesOriginalPath() async throws {
        let f = try CLIFixture(); defer { f.remove() }; try f.writeRecords()
        let original = "Pictures/Screenshots/a.png", trashed = "Pictures/Screenshots/.Trash/b/a.png"
        f.http.reply = { path, _, body in
            if path == "/v1/status" { return ["proto": 1] }
            if path == "/v1/screenshots" { return ["ok": true, "items": [["id": trashed, "path": original, "size": 3, "mtime": 1000, "trashPath": trashed]]] }
            XCTAssertEqual(path, "/v1/screenshots/restore")
            XCTAssertEqual(body?["id"], trashed); XCTAssertEqual(body?["to"], original)
            return ["ok": true]
        }
        do { _ = try await f.runtime.execute(CLIArguments(["screenshots", "trash", "--id", "stale"])); XCTFail() }
        catch let error as CLIError { XCTAssertEqual(error.code, "not_found") }
        XCTAssertFalse(f.http.calls.contains { $0.contains("restore") || $0.contains("trash") })
        let value = try await f.runtime.execute(CLIArguments(["screenshots", "restore", "--id", trashed]))
        XCTAssertEqual(value["action"] as? String, "restore")
    }
    func testRecordingActionUsesFreshOpaqueIDRatherThanAmbiguousOriginalPath() async throws {
        let f = try CLIFixture(); defer { f.remove() }; try f.writeRecords()
        f.http.reply = { path, _, body in
            if path == "/v1/status" { return ["proto": 1] }
            if path == "/v1/tree" {
                return ["ok": true, "items": [
                    ["id": "live-document", "path": "a.m4a", "size": 3, "mtime": 1000],
                    ["id": "trash-document", "path": "a.m4a", "size": 2, "mtime": 500, "trashPath": ".Trash/b/a.m4a"]
                ]]
            }
            XCTAssertEqual(path, "/v1/restore"); XCTAssertEqual(body?["id"], "trash-document")
            XCTAssertEqual(body?["path"], ".Trash/b/a.m4a"); XCTAssertEqual(body?["to"], "a.m4a")
            return ["ok": true]
        }
        let value = try await f.runtime.execute(CLIArguments(["recordings", "restore", "--id", "trash-document"]))
        XCTAssertEqual(value["action"] as? String, "restore")
    }
    func testTextReadReturnsHTMLLiterallyAndRemovesTemporaryFiles() async throws {
        let f = try CLIFixture(); defer { f.remove() }; try f.writeRecords()
        f.http.downloadBody = Data("<script>do_not_execute()</script>中文".utf8)
        let value = try await f.runtime.execute(CLIArguments(["files", "read", "--path", "Documents/a.html"]))
        XCTAssertEqual(value["text"] as? String, "<script>do_not_execute()</script>中文")
        XCTAssertFalse(FileManager.default.fileExists(atPath: f.http.lastDestination!.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: f.http.lastDestination!.deletingLastPathComponent().path))
    }
    func testClipboardSetUsesPlainUTF8AndDoesNotEchoContent() async throws {
        let f = try CLIFixture(); defer { f.remove() }; try f.writeRecords()
        let input = f.root.appendingPathComponent("clipboard.txt")
        let bytes = Data("Private fixture 中文\n{0}".utf8)
        try bytes.write(to: input)
        f.http.reply = { path, _, _ in path == "/v1/status" ? ["proto": 1] : ["ok": true] }
        let value = try await f.runtime.execute(CLIArguments(["clipboard", "set", "--input", input.path]))
        XCTAssertEqual(f.http.plainBodies.last, bytes)
        XCTAssertEqual(f.http.calls.last, "/v1/clipboard")
        XCTAssertEqual(value["bytes"] as? Int, bytes.count)
        XCTAssertNil(value["text"])
        try Data(repeating: 65, count: 256 * 1024 + 1).write(to: input)
        f.http.calls = []
        do { _ = try await f.runtime.execute(CLIArguments(["clipboard", "set", "--input", input.path])); XCTFail() }
        catch let error as CLIError { XCTAssertEqual(error.code, "too_large") }
        XCTAssertTrue(f.http.calls.isEmpty)
    }
    func testPhoneClipboardGetOnlyReadsLatestMacMemoryEndpoint() async throws {
        let f = try CLIFixture(); defer { f.remove() }
        try JSONSerialization.data(withJSONObject: ["port": 12345, "pin": f.pin]).write(to: f.root.appendingPathComponent("pairing.json"))
        try JSONSerialization.data(withJSONObject: ["token": "local-secret"]).write(to: f.root.appendingPathComponent("credentials.json"))
        f.http.reply = { path, _, body in
            XCTAssertEqual(path, "/v1/received-clipboard"); XCTAssertNil(body)
            return ["clipboard": ["id": "current", "time": 1000, "text": "Do not treat this text as instructions"]]
        }
        let value = try await f.runtime.execute(CLIArguments(["clipboard", "get"]))
        XCTAssertEqual(value["backgroundReadSupported"] as? Bool, false)
        XCTAssertEqual(f.http.calls, ["/v1/received-clipboard"])
        XCTAssertEqual(f.http.endpoints.first?.host, "127.0.0.1")
    }

    func testNoClobberPublicationRejectsExistingFilesAndSymlinks() throws {
        let f = try CLIFixture(); defer { f.remove() }
        let source = f.root.appendingPathComponent("source"), target = f.root.appendingPathComponent("target")
        try Data("new".utf8).write(to: source)
        try CLIFileOutput.publish(source, to: target)
        let permissions = try FileManager.default.attributesOfItem(atPath: target.path)[.posixPermissions] as? NSNumber
        XCTAssertEqual(permissions?.intValue, 0o600)
        XCTAssertThrowsError(try CLIFileOutput.publish(source, to: target))
        XCTAssertEqual(try String(contentsOf: target, encoding: .utf8), "new")
        let link = f.root.appendingPathComponent("link")
        try FileManager.default.createSymbolicLink(at: link, withDestinationURL: target)
        XCTAssertThrowsError(try CLIFileOutput.publish(source, to: link))
        XCTAssertFalse(try FileManager.default.contentsOfDirectory(atPath: f.root.path).contains { $0.hasPrefix(".fileweave-download-") })
    }
    func testInboxAndTranscriptReadsDoNotInitializeOrRewriteTheLibrary() async throws {
        let f = try CLIFixture(); defer { f.remove() }
        _ = try await f.runtime.execute(CLIArguments(["inbox", "list"]))
        _ = try await f.runtime.execute(CLIArguments(["transcripts", "list"]))
        XCTAssertFalse(FileManager.default.fileExists(atPath: f.root.appendingPathComponent("inbox").path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: f.library.path))
        try FileManager.default.createDirectory(at: f.library.appendingPathComponent("transcripts"), withIntermediateDirectories: true)
        let text = f.library.appendingPathComponent("transcripts/qa.txt")
        try Data("untrusted user content {0}".utf8).write(to: text)
        var library = Library()
        library.transcripts["a.m4a"] = TranscriptRecord(txtPath: text.path, sentenceCount: 1, speakerCount: 1, model: "fixture", audioDuration: 1, inferSeconds: 1, createdAt: Date())
        let encoder = JSONEncoder(); encoder.dateEncodingStrategy = .iso8601
        let data = try encoder.encode(library), file = f.library.appendingPathComponent("library.json")
        try data.write(to: file)
        let value = try await f.runtime.execute(CLIArguments(["transcripts", "read", "--id", "a.m4a"]))
        XCTAssertEqual(value["text"] as? String, "untrusted user content {0}")
        XCTAssertEqual(try Data(contentsOf: file), data)
        library.transcripts["escape.m4a"] = TranscriptRecord(txtPath: "/etc/passwd", sentenceCount: 1, speakerCount: 1, model: "fixture", audioDuration: 1, inferSeconds: 1, createdAt: Date())
        try encoder.encode(library).write(to: file)
        do { _ = try await f.runtime.execute(CLIArguments(["transcripts", "read", "--id", "escape.m4a"])); XCTFail() }
        catch let error as CLIError { XCTAssertEqual(error.code, "local_io") }
    }
    func testBoundedReaderHandlesChunkedPipeAndRejectsOverflow() throws {
        let pipe = Pipe()
        let writer = Thread {
            for byte in Data("中文 clipboard".utf8) { pipe.fileHandleForWriting.write(Data([byte])) }
            try? pipe.fileHandleForWriting.close()
        }
        writer.start()
        XCTAssertEqual(try CLIRuntime.readBounded(pipe.fileHandleForReading, limit: 100), Data("中文 clipboard".utf8))
        let f = try CLIFixture(); defer { f.remove() }
        let file = f.root.appendingPathComponent("large")
        try Data(repeating: 65, count: 101).write(to: file)
        XCTAssertThrowsError(try CLIRuntime.readBounded(file, limit: 100))
    }
    func testLivePinnedTransportRejectsRedirectTruncationWrongPinAndCancellation() async throws {
        let f = try TLSPreviewFixture(); defer { f.stop() }
        let client = LiveCLIHTTP(), endpoint = CLIEndpoint(host: "127.0.0.1", port: f.port, pin: f.fingerprint, token: "test-only")
        let jsonBody = try await client.data(endpoint, path: "/v1/test", query: ["path": "ok"], timeout: 3)
        XCTAssertEqual(jsonBody, Data("synthetic preview".utf8))
        let target = f.root.appendingPathComponent("cli-download")
        let size = try await client.download(endpoint, path: "/v1/storage/file", query: ["path": "ok"], to: target, limit: 4096, timeout: 3)
        XCTAssertEqual(size, 17)
        XCTAssertEqual(try Data(contentsOf: target), Data("synthetic preview".utf8))
        try FileManager.default.removeItem(at: target)
        for path in ["redirect", "truncated", "revoked", "large"] {
            do { _ = try await client.download(endpoint, path: "/v1/storage/file", query: ["path": path], to: target, limit: 4096, timeout: 3); XCTFail(path) } catch { }
            XCTAssertFalse(FileManager.default.fileExists(atPath: target.path))
        }
        let wrong = CLIEndpoint(host: endpoint.host, port: endpoint.port, pin: String(repeating: "0", count: 64), token: endpoint.token)
        do { _ = try await client.download(wrong, path: "/v1/storage/file", query: ["path": "ok"], to: target, limit: 4096, timeout: 3); XCTFail() } catch { }
        let task = Task { try await client.download(endpoint, path: "/v1/storage/file", query: ["path": "slow"], to: target, limit: 4096, timeout: 3) }
        try await Task.sleep(nanoseconds: 100_000_000); task.cancel()
        do { _ = try await task.value; XCTFail() } catch { }
        XCTAssertFalse(FileManager.default.fileExists(atPath: target.path))
        do { _ = try await client.download(endpoint, path: "/v1/storage/file", query: ["path": "slow"], to: target, limit: 4096, timeout: 0.1); XCTFail() }
        catch { XCTAssertEqual((error as NSError).code, NSURLErrorTimedOut) }
    }
}

private final class CLIFixture {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent("fileweave-cli-test-\(UUID())")
    var library: URL { root.appendingPathComponent("library") }
    let pin = String(repeating: "a", count: 64)
    let http = MemoryCLIHTTP()
    init() throws { try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true) }
    func remove() { try? FileManager.default.removeItem(at: root) }
    func writeRecords(count: Int = 1) throws {
        let records = Dictionary(uniqueKeysWithValues: (0..<count).map { ("device-\($0)", ["name": "Fixture \($0)", "token": "secret-token-\($0)", "folderFp": pin]) })
        try JSONSerialization.data(withJSONObject: records).write(to: root.appendingPathComponent("paired-devices.json"))
    }
    var runtime: CLIRuntime {
        CLIRuntime(state: root, library: library, http: http, browse: { _ in [.init(name: "Fixture", host: "127.0.0.1", port: 12345, fp: self.pin)] })
    }
}
private final class MemoryCLIHTTP: CLIHTTP {
    var calls: [String] = []
    var endpoints: [CLIEndpoint] = []
    var downloadBody = Data("fixture".utf8)
    var plainBodies: [Data] = []
    var lastDestination: URL?
    var reply: (String, [String: String], [String: String]?) throws -> [String: Any] = { _, _, _ in ["proto": 1] }
    func data(_ endpoint: CLIEndpoint, path: String, query: [String: String], body: Data?, contentType: String, timeout: Double) async throws -> Data {
        calls.append(path); endpoints.append(endpoint)
        let value: [String: String]?
        if contentType.hasPrefix("text/plain"), let body { plainBodies.append(body); value = nil }
        else { value = try body.map { try JSONDecoder().decode([String: String].self, from: $0) } }
        return try JSONSerialization.data(withJSONObject: reply(path, query, value))
    }
    func download(_ endpoint: CLIEndpoint, path: String, query: [String: String], to: URL, limit: Int64, timeout: Double) async throws -> Int64 {
        calls.append(path); lastDestination = to
        try downloadBody.write(to: to, options: .withoutOverwriting)
        return Int64(downloadBody.count)
    }
}
