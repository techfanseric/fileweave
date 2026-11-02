import Foundation
import Darwin

struct CLIError: Error {
    let code: String
    let message: String
    var exitCode: Int32 = 1
    static func usage(_ option: String) -> Self {
        Self(code: "invalid_arguments", message: L("命令参数无效，请运行 fileweave help：{0}", option), exitCode: 2)
    }
    static var invalidResponse: Self { Self(code: "invalid_response", message: L("设备返回的数据无效或不完整。")) }
    static func io(_ path: String) -> Self { Self(code: "local_io", message: L("无法读写本地文件：{0}", path)) }
    static func exists(_ path: String) -> Self { Self(code: "already_exists", message: L("目标已存在，未覆盖：{0}", path), exitCode: 6) }
}

/// Credentials never participate in output, command arguments, or diagnostics.
struct CLIEndpoint {
    let host: String
    let port: Int
    let pin: String
    let token: String
}
protocol CLIHTTP {
    func data(_ endpoint: CLIEndpoint, path: String, query: [String: String], body: Data?, contentType: String, timeout: Double) async throws -> Data
    func download(_ endpoint: CLIEndpoint, path: String, query: [String: String], to: URL, limit: Int64, timeout: Double) async throws -> Int64
}
final class LiveCLIHTTP: CLIHTTP {
    private func request(_ endpoint: CLIEndpoint, path: String, query: [String: String], body: Data?, contentType: String, timeout: Double) throws -> URLRequest {
        guard let url = FileWeaveFolderSource.endpointURL(host: endpoint.host, port: endpoint.port, path: path, query: query),
              endpoint.pin.range(of: "^[a-fA-F0-9]{64}$", options: .regularExpression) != nil else { throw CLIError.invalidResponse }
        var request = URLRequest(url: url)
        request.timeoutInterval = timeout
        request.setValue("Bearer \(endpoint.token)", forHTTPHeaderField: "Authorization")
        if let body {
            request.httpMethod = "POST"; request.httpBody = body
            request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        }
        return request
    }
    private func configuration(timeout: Double) -> URLSessionConfiguration {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = timeout
        configuration.timeoutIntervalForResource = timeout
        configuration.httpCookieStorage = nil
        configuration.urlCache = nil
        return configuration
    }
    func data(_ endpoint: CLIEndpoint, path: String, query: [String: String] = [:], body: Data? = nil, contentType: String = "application/json", timeout: Double) async throws -> Data {
        let limit: Int64 = 16 * 1024 * 1024
        let delegate = CLIDownloadDelegate(fp: endpoint.pin.lowercased(), limit: limit)
        let session = URLSession(configuration: configuration(timeout: timeout), delegate: delegate, delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        let request = try request(endpoint, path: path, query: query, body: body, contentType: contentType, timeout: timeout)
        // Use the same pinned download delegate as file transfers. Keep JSON on
        // disk until its bounded size is checked, rather than buffering a peer's
        // arbitrary response in memory.
        let temporary: URL
        let response: URLResponse
        do { (temporary, response) = try await session.download(for: request) }
        catch {
            if delegate.exceeded { throw CLIError(code: "too_large", message: L("文件超过读取大小限制。")) }
            throw error
        }
        defer { try? FileManager.default.removeItem(at: temporary) }
        try Self.validate(response)
        let size = (try FileManager.default.attributesOfItem(atPath: temporary.path)[.size] as? NSNumber)?.int64Value ?? -1
        guard size >= 0, size <= limit,
              response.expectedContentLength < 0 || size == response.expectedContentLength else { throw CLIError.invalidResponse }
        try Task.checkCancellation()
        return try Data(contentsOf: temporary)
    }
    func download(_ endpoint: CLIEndpoint, path: String, query: [String: String] = [:], to destination: URL, limit: Int64, timeout: Double) async throws -> Int64 {
        try CLIFileOutput.checkDestination(destination)
        let delegate = CLIDownloadDelegate(fp: endpoint.pin.lowercased(), limit: limit)
        let session = URLSession(configuration: configuration(timeout: timeout), delegate: delegate, delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        let request = try request(endpoint, path: path, query: query, body: nil, contentType: "application/json", timeout: timeout)
        let temporary: URL
        let response: URLResponse
        do { (temporary, response) = try await session.download(for: request) }
        catch {
            if delegate.exceeded { throw CLIError(code: "too_large", message: L("文件超过读取大小限制。")) }
            throw error
        }
        defer { try? FileManager.default.removeItem(at: temporary) }
        try Task.checkCancellation()
        try Self.validate(response)
        guard ["application/octet-stream", "audio/mp4"].contains(response.mimeType ?? "") else { throw CLIError.invalidResponse }
        let size = (try FileManager.default.attributesOfItem(atPath: temporary.path)[.size] as? NSNumber)?.int64Value ?? -1
        guard size >= 0, size <= limit, size == response.expectedContentLength else { throw CLIError.invalidResponse }
        // 206 is deliberately rejected: no Range was requested, so partial data
        // must never be published as a complete file.
        try CLIFileOutput.publish(temporary, to: destination)
        return size
    }
    private static func validate(_ response: URLResponse) throws {
        guard let http = response as? HTTPURLResponse else { throw CLIError.invalidResponse }
        guard http.statusCode == 200 else {
            throw CLIError(code: http.statusCode == 401 || http.statusCode == 403 ? "access_denied" : "http_error",
                           message: L("请求失败，HTTP {0}。", http.statusCode))
        }
    }
}
private final class CLIDownloadDelegate: PinningDelegate, URLSessionDownloadDelegate, @unchecked Sendable {
    let limit: Int64
    private let lock = NSLock()
    private var didExceed = false
    var exceeded: Bool { lock.lock(); defer { lock.unlock() }; return didExceed }
    init(fp: String, limit: Int64) { self.limit = limit; super.init(fp: fp) }
    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64, totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        if totalBytesWritten > limit || totalBytesExpectedToWrite > limit {
            lock.lock(); didExceed = true; lock.unlock()
            downloadTask.cancel()
        }
    }
    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {}
}

enum CLIFileOutput {
    static func checkDestination(_ destination: URL) throws {
        guard destination.isFileURL, !destination.lastPathComponent.isEmpty else { throw CLIError.io(destination.path) }
        var info = stat()
        if lstat(destination.path, &info) == 0 { throw CLIError.exists(destination.path) }
        guard errno == ENOENT else { throw CLIError.io(destination.path) }
        var directory: ObjCBool = false
        guard FileManager.default.fileExists(atPath: destination.deletingLastPathComponent().path, isDirectory: &directory), directory.boolValue else { throw CLIError.io(destination.path) }
    }
    /// Same-directory staging + link(2) gives atomic no-clobber publication even
    /// across volumes, and even if another process creates the target mid-flight.
    static func publish(_ source: URL, to destination: URL) throws {
        try checkDestination(destination)
        let stage = destination.deletingLastPathComponent().appendingPathComponent(".fileweave-download-\(UUID())")
        defer { try? FileManager.default.removeItem(at: stage) }
        do {
            try FileManager.default.copyItem(at: source, to: stage)
            try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: stage.path)
            let handle = try FileHandle(forWritingTo: stage)
            defer { try? handle.close() }
            try handle.synchronize()
            try Task.checkCancellation()
            guard link(stage.path, destination.path) == 0 else {
                if errno == EEXIST { throw CLIError.exists(destination.path) }
                throw CLIError.io(destination.path)
            }
        } catch let error as CLIError { throw error }
        catch is CancellationError { throw CancellationError() }
        catch { throw CLIError.io(destination.path) }
    }
}
