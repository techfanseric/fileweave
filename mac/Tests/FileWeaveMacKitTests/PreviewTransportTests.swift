import XCTest
import CryptoKit
@testable import FileWeaveMacKit

/// Real local TLS responses exercise pinning, authorization, redirects, truncation
/// and the preview size budget without reading any paired device or user files.
final class PreviewTransportTests: XCTestCase {
    func testPinnedPreviewDownloadRejectsInvalidResponsesAndSizeOverflow() async throws {
        let fixture = try TLSPreviewFixture()
        defer { fixture.stop() }
        let source = FileWeaveFolderSource(host: "127.0.0.1", port: fixture.port, fp: fixture.fingerprint, token: "test-only", deviceName: "Fixture")
        let destination = fixture.root.appendingPathComponent("preview.txt")
        try await source.downloadPreview(path: "ok", selected: false, to: destination, limit: 4096)
        XCTAssertEqual(try String(contentsOf: destination, encoding: .utf8), "synthetic preview")
        try FileManager.default.removeItem(at: destination)
        for path in ["large", "redirect", "revoked", "truncated"] {
            do {
                try await source.downloadPreview(path: path, selected: false, to: destination, limit: 4096)
                XCTFail("Unexpected success: \(path)")
            } catch { }
            XCTAssertFalse(FileManager.default.fileExists(atPath: destination.path), path)
        }
        let canceled = Task { try await source.downloadPreview(path: "slow", selected: false, to: destination, limit: 4096) }
        try await Task.sleep(nanoseconds: 100_000_000)
        canceled.cancel()
        do { try await canceled.value; XCTFail("Canceled preview must not complete") }
        catch { XCTAssertTrue(error is CancellationError || (error as NSError).code == NSURLErrorCancelled) }
        XCTAssertFalse(FileManager.default.fileExists(atPath: destination.path))
        let wrongPin = FileWeaveFolderSource(host: "127.0.0.1", port: fixture.port, fp: String(repeating: "0", count: 64), token: "test-only", deviceName: "Fixture")
        do {
            try await wrongPin.downloadPreview(path: "ok", selected: false, to: destination, limit: 4096)
            XCTFail("An unpaired certificate must fail")
        } catch { }
        XCTAssertFalse(FileManager.default.fileExists(atPath: destination.path))
    }
}

final class TLSPreviewFixture {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent("fileweave-tls-\(UUID())")
    let server = Process()
    let port: Int
    let fingerprint: String
    init() throws {
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        let cert = root.appendingPathComponent("cert.pem"), key = root.appendingPathComponent("key.pem"), der = root.appendingPathComponent("cert.der")
        func openssl(_ arguments: [String]) throws {
            let process = Process(); process.executableURL = URL(fileURLWithPath: "/usr/bin/openssl"); process.arguments = arguments
            process.standardOutput = FileHandle.nullDevice; process.standardError = FileHandle.nullDevice
            try process.run(); process.waitUntilExit()
            guard process.terminationStatus == 0 else { throw NSError(domain: "TLSFixture", code: Int(process.terminationStatus)) }
        }
        try openssl(["req", "-x509", "-newkey", "rsa:2048", "-nodes", "-sha256", "-subj", "/CN=localhost", "-days", "1", "-keyout", key.path, "-out", cert.path])
        try openssl(["x509", "-in", cert.path, "-outform", "DER", "-out", der.path])
        fingerprint = SHA256.hash(data: try Data(contentsOf: der)).map { String(format: "%02x", $0) }.joined()
        let script = #"""
import http.server, ssl, sys, urllib.parse, time
class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args): pass
    def do_GET(self):
        if self.headers.get('Authorization') != 'Bearer test-only':
            self.send_error(401); return
        path = urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query).get('path', [''])[0]
        if path == 'redirect':
            self.send_response(302); self.send_header('Location', '/redirect-target'); self.end_headers(); return
        if path == 'revoked':
            self.send_error(403); return
        body = b'x' * 1048576 if path == 'large' else b'synthetic preview'
        self.send_response(200); self.send_header('Content-Type', 'application/octet-stream')
        self.send_header('Content-Length', str(len(body) + (10 if path == 'truncated' else 0))); self.end_headers()
        if path == 'slow': time.sleep(1)
        try: self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError): pass
server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER); context.load_cert_chain(sys.argv[1], sys.argv[2])
server.socket = context.wrap_socket(server.socket, server_side=True)
print(server.server_port, flush=True)
server.serve_forever()
"""#
        let pipe = Pipe()
        server.executableURL = URL(fileURLWithPath: "/usr/bin/env")
        server.arguments = ["python3", "-u", "-c", script, cert.path, key.path]
        server.standardOutput = pipe; server.standardError = FileHandle.nullDevice
        try server.run()
        guard let value = Int(String(decoding: pipe.fileHandleForReading.availableData, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines)) else {
            server.terminate(); throw NSError(domain: "TLSFixtureStart", code: 1)
        }
        port = value
    }
    func stop() {
        if server.isRunning { server.terminate(); server.waitUntilExit() }
        try? FileManager.default.removeItem(at: root)
    }
    deinit { stop() }
}
