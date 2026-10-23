import Foundation
import CryptoKit

// MARK: - 数据源契约

/// 一次实扫的结果：在位录音 + 回收站里的录音。
///
/// /v1/tree 一次返回全部，所以不存在「这一轮只扫到一半」的中间态：
/// 扫描失败就整体抛错，调用方沿用上一轮；分开返回只是因为界面要把
/// 在位列表和回收站分开渲染。
public struct SourceSnapshot {
    public var live: [Recording]
    public var trash: [Recording]

    public init(live: [Recording], trash: [Recording]) {
        self.live = live
        self.trash = trash
    }
}

/// 通往手机的通道。上层（列表、回收站、下载、转录）不关心底下走的是
/// 配对发现 + 证书固定的 HTTPS，将来加别的实现时语义必须完全一致。
public protocol RecordingSource {
    /// 菜单栏上显示的名字
    var displayName: String { get }
    /// 走的是哪条通道、连的哪里，用于诊断
    var detail: String { get }
    /// 这条通道背后那台手机的稳定身份 = 配对记录里的设备名。
    ///
    /// 配对是用户亲手批准过的，记录里必有名字，所以不是 Optional。
    var identity: String { get }

    /// 最轻量的连通性探测（GET /v1/status），重连判定用
    func probe() -> Bool
    func scan() throws -> SourceSnapshot
    /// 移入回收站，返回它在回收站里的新路径
    func trash(_ rec: Recording) throws -> String
    /// 从回收站还原回原位
    func restore(_ rec: Recording) throws
    /// 把当前所在位置的文件拉到本机
    func download(_ rec: Recording, to: URL) throws -> URL
}

public enum SourceError: LocalizedError {
    case noSource
    case server(String)

    public var errorDescription: String? {
        switch self {
        case .noSource: return "没有可用的配对设备"
        case .server(let m): return m
        }
    }
}

// MARK: - 已配对设备记录

/// 一条配对记录，来自 bridge 的 `.state/paired-devices.json`。
/// `folderFp` 是那台手机文件夹共享服务自签证书的 sha256 指纹（hex），
/// 手机端没开过文件夹共享时没有这个字段。
public struct PairedDeviceRecord: Equatable {
    public let id: String
    public let name: String
    public let token: String
    public let folderFp: String?
}

/// 读 `paired-devices.json` 的小读取器：一次读全量 JSON、
/// 按文件（大小 + 修改时间）变化才重读、读失败沿用上一次的结果。
///
/// bridge.py 会原子替换这个文件，所以按 inode 级属性判变化即可，
/// 不需要 FSEvent 之类的 watcher —— 每轮重连前多一次 stat 而已。
public final class PairedDeviceStore: @unchecked Sendable {

    public static let shared = PairedDeviceStore(url: MacPaths.pairedDevices)

    private let url: URL
    private let lock = NSLock()
    private var cached: [PairedDeviceRecord] = []
    private var stamp: (size: Int, mtime: TimeInterval)?

    public init(url: URL) {
        self.url = url
    }

    /// 当前全部配对记录。文件缺失 / 解析失败时返回缓存（首次为空数组）。
    public func devices() -> [PairedDeviceRecord] {
        lock.lock()
        defer { lock.unlock() }

        let attrs = try? FileManager.default.attributesOfItem(atPath: url.path)
        let size = (attrs?[.size] as? NSNumber)?.intValue ?? -1
        let mtime = (attrs?[.modificationDate] as? Date)?.timeIntervalSince1970 ?? -1
        if let s = stamp, s.size == size, s.mtime == mtime {
            return cached
        }
        guard let data = try? Data(contentsOf: url),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            // 文件还没生成（一台都没配对过）或正在被原子替换：
            // 不算错误，沿用缓存，下次变了自然会重读。
            stamp = (size, mtime)
            return cached
        }
        var out: [PairedDeviceRecord] = []
        for (id, value) in obj {
            guard let dict = value as? [String: Any],
                  let name = dict["name"] as? String,
                  let token = dict["token"] as? String else { continue }
            out.append(PairedDeviceRecord(
                id: id, name: name, token: token,
                folderFp: (dict["folderFp"] as? String)?.lowercased()))
        }
        cached = out
        stamp = (size, mtime)
        return out
    }

    /// 解析一份 paired-devices JSON（纯函数，测试用）
    public static func parse(_ data: Data) -> [PairedDeviceRecord] {
        guard let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return []
        }
        var out: [PairedDeviceRecord] = []
        for (id, value) in obj {
            guard let dict = value as? [String: Any],
                  let name = dict["name"] as? String,
                  let token = dict["token"] as? String else { continue }
            out.append(PairedDeviceRecord(
                id: id, name: name, token: token,
                folderFp: (dict["folderFp"] as? String)?.lowercased()))
        }
        return out
    }
}

// MARK: - Bonjour 发现

/// 浏览 `_fileweave-folder._tcp.`，拿 TXT 里的 `v=1`、`fp=<hex>`。
///
/// 一次性同步搜索：开一条自己的线程跑 runloop，到点收工。
/// 调用方（AppState）本来就在把它扔进后台 Task，这里不必再做异步包装。
public enum FolderDiscovery {

    public struct Candidate: Equatable {
        public let name: String
        public let host: String
        public let port: Int
        /// 证书指纹，hex（小写）
        public let fp: String
    }

    public static let serviceType = "_fileweave-folder._tcp."

    /// TXT 记录 → 字典。抽成纯函数是为了单测。
    public static func parseTXT(_ data: Data) -> [String: String] {
        var out: [String: String] = [:]
        for (key, value) in NetService.dictionary(fromTXTRecord: data) {
            out[key] = String(data: value, encoding: .utf8) ?? ""
        }
        return out
    }

    /// 搜一轮局域网。返回的每个候选都带 TXT 且 v=1、fp 非空 ——
    /// 别的服务就算在广播也不关我们的事，一律不进结果。
    public static func browse(timeout: TimeInterval = 2.0) -> [Candidate] {
        final class Collector: NSObject, NetServiceBrowserDelegate, NetServiceDelegate {
            let lock = NSLock()
            var services: [NetService] = []
            var candidates: [Candidate] = []

            func netServiceBrowser(_ browser: NetServiceBrowser,
                                   didFind service: NetService,
                                   moreComing: Bool) {
                lock.lock(); services.append(service); lock.unlock()
                service.delegate = self
                // 不 resolve 拿不到 host/port/TXT —— TXT 是 resolve 完才给的
                service.resolve(withTimeout: 5)
            }

            func netServiceDidResolveAddress(_ sender: NetService) {
                guard let host = sender.hostName,
                      sender.port > 0,
                      let txt = sender.txtRecordData() else { return }
                let dict = FolderDiscovery.parseTXT(txt)
                guard dict["v"] == "1", let fp = dict["fp"], !fp.isEmpty else { return }
                lock.lock()
                candidates.append(Candidate(name: sender.name, host: host,
                                            port: sender.port, fp: fp.lowercased()))
                lock.unlock()
            }
        }

        let collector = Collector()
        let browser = NetServiceBrowser()
        browser.delegate = collector
        let done = DispatchSemaphore(value: 0)

        let thread = Thread {
            browser.schedule(in: .current, forMode: .default)
            browser.searchForServices(ofType: serviceType, inDomain: "local.")
            // run(mode:before:) 处理一次输入源就可能返回；发现服务和解析地址是
            // 不同回调，必须持续运行到截止时间，不能在 didFind 后提前停掉。
            runDiscoveryLoop(until: Date().addingTimeInterval(timeout))
            browser.stop()
            done.signal()
        }
        thread.name = "fileweave-folder-browse"
        thread.start()
        // resolve 超时给到 5 秒，最坏等 browse 时长 + 5 秒，别把调用方吊死
        _ = done.wait(timeout: .now() + timeout + 6)

        collector.lock.lock()
        var seen = Set<String>()
        let unique = collector.candidates.filter { seen.insert($0.fp).inserted }
        collector.lock.unlock()
        return unique
    }

    static func runDiscoveryLoop(until deadline: Date) {
        while Date() < deadline {
            RunLoop.current.run(mode: .default, before: deadline)
        }
    }
}

// MARK: - 文件夹共享通道

/// 走手机 FileWeave 的「共享文件夹」服务：HTTPS + 证书固定 + Bearer。
///
/// 安全模型与 Android 端 BridgeClient.kt 一致：
/// - 只信 leaf 证书 DER sha256 == 配对记录里的 folderFp，不看 CA / 主机名；
/// - 每个请求都带 `Authorization: Bearer <配对时发的 token>`；
/// - 禁止跟随重定向（重定向既可能是攻击也可能是配置错，直接当失败处理）。
public final class FileWeaveFolderSource: RecordingSource, @unchecked Sendable {

    public let host: String
    public let port: Int
    /// 期望的证书指纹（hex 小写）
    public let fp: String
    public let token: String
    public let deviceName: String

    public var displayName: String { deviceName }
    public var detail: String { "FileWeave \(host):\(port)" }
    public var identity: String { deviceName }

    private let session: URLSession

    public init(host: String, port: Int, fp: String, token: String, deviceName: String) {
        self.host = host
        self.port = port
        self.fp = fp.lowercased()
        self.token = token
        self.deviceName = deviceName
        let cfg = URLSessionConfiguration.ephemeral
        cfg.timeoutIntervalForRequest = 30
        // 列表要 SAF 逐级下钻，手机上可能要好几秒
        cfg.timeoutIntervalForResource = 120
        session = URLSession(configuration: cfg,
                             delegate: PinningDelegate(fp: self.fp),
                             delegateQueue: nil)
    }

    deinit {
        // 每次重连都会造一个新的 source；不 invalidate 的话系统会连着 delegate
        // 一起强持有旧 session，断连重连几轮就是几份泄漏。
        session.finishTasksAndInvalidate()
    }

    // MARK: 端点响应模型

    struct StatusResponse: Decodable {
        let proto: Int?
        let model: String?
        let files: Int?
        let root: String?
    }

    struct TreeItem: Decodable {
        let id: String
        let path: String
        let size: Int64
        /// 毫秒时间戳（契约：mtime(ms)）
        let mtime: Double
        let trashPath: String?
        let movedAt: Double?
    }

    struct TreeResponse: Decodable {
        let ok: Bool
        let items: [TreeItem]
    }

    struct ActionResponse: Decodable {
        let ok: Bool
        let trashPath: String?
    }

    // MARK: RecordingSource

    public func probe() -> Bool {
        guard let url = Self.endpointURL(host: host, port: port, path: "/v1/status") else {
            return false
        }
        var req = URLRequest(url: url)
        req.timeoutInterval = 2.5
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        guard let (data, resp) = try? session.dataTaskSync(req) else {
            return false
        }
        guard let http = resp as? HTTPURLResponse, http.statusCode == 200 else { return false }
        return (try? JSONDecoder().decode(StatusResponse.self, from: data)) != nil
    }

    public func scan() throws -> SourceSnapshot {
        let data = try get("/v1/tree", timeout: 45)
        return try Self.snapshot(fromTree: data)
    }

    /// `/v1/tree` JSON → 快照。抽成纯函数是为了单测（毫秒→Date、回收站拆分）。
    static func snapshot(fromTree data: Data) throws -> SourceSnapshot {
        let decoded = try JSONDecoder().decode(TreeResponse.self, from: data)
        guard decoded.ok else { throw SourceError.server("手机端返回了失败") }

        var live: [Recording] = []
        var trash: [Recording] = []
        for it in decoded.items {
            guard let rec = Recording(remotePath: it.path,
                                      size: it.size,
                                      modifiedAt: Date(timeIntervalSince1970: it.mtime / 1000),
                                      trashPath: it.trashPath,
                                      // 0 或缺失都按「不知道删除时间」处理；0 不是 1970 年。
                                      movedAt: it.movedAt.flatMap { $0 > 0
                                          ? Date(timeIntervalSince1970: $0 / 1000) : nil },
                                      remoteId: it.id)
            else { continue }
            if rec.isTrashed { trash.append(rec) } else { live.append(rec) }
        }
        return SourceSnapshot(live: live, trash: trash)
    }

    public func trash(_ rec: Recording) throws -> String {
        // 必须带 id：手机端写操作靠 documentId 定位，不按路径回找
        guard let rid = rec.remoteId else {
            throw SourceError.server("这条记录没有手机端标识，先刷新一次")
        }
        let body = try JSONSerialization.data(
            withJSONObject: ["id": rid, "path": rec.filePath])
        let data = try post("/v1/trash", body: body)
        let decoded = try JSONDecoder().decode(ActionResponse.self, from: data)
        guard decoded.ok, let dest = decoded.trashPath else {
            throw SourceError.server("移入回收站失败")
        }
        return dest
    }

    public func restore(_ rec: Recording) throws {
        guard let from = rec.trashPath else {
            throw SourceError.server("这条已经不在手机回收站里了")
        }
        guard let rid = rec.remoteId else {
            throw SourceError.server("这条记录没有手机端标识，先刷新一次")
        }
        let body = try JSONSerialization.data(
            withJSONObject: ["id": rid, "path": from, "to": rec.remotePath])
        let data = try post("/v1/restore", body: body)
        let decoded = try JSONDecoder().decode(ActionResponse.self, from: data)
        guard decoded.ok else { throw SourceError.server("还原失败") }
    }

    public func download(_ rec: Recording, to url: URL) throws -> URL {
        guard let target = Self.endpointURL(host: host, port: port, path: "/v1/file",
                                            query: ["path": rec.filePath]) else {
            throw SourceError.server("下载地址拼不出来")
        }
        var req = URLRequest(url: target)
        req.timeoutInterval = 300
        // 契约：每个请求都带 Bearer，下载也不例外
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        let (data, resp) = try session.dataTaskSync(req)
        // 契约：200 全量 / 206 Range。这里从不发 Range，所以 206 只在服务端异常时出现，
        // 且必须证明它恰好是「整份文件」才接受，否则宁可失败也不写半份。
        guard let http = resp as? HTTPURLResponse else {
            throw SourceError.server("拉取音频失败：手机未返回 HTTP 响应")
        }
        if http.statusCode == 206 {
            let header = http.value(forHTTPHeaderField: "Content-Range") ?? ""
            guard let range = Self.parseContentRange(header),
                  range.start == 0, range.end + 1 == range.total,
                  Int64(data.count) == range.total else {
                throw SourceError.server("Range 响应不完整（收到 \(data.count) 字节）")
            }
        } else if http.statusCode != 200 {
            throw SourceError.server("拉取音频失败：手机返回 HTTP \(http.statusCode)，请刷新后重试")
        }
        try FileManager.default.createDirectory(
            at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try data.write(to: url)
        return url
    }

    // MARK: 发现 + 配对匹配

    /// 广播里的 fp 只有和某条配对记录的 folderFp 对上才连 ——
    /// 广播是任何人都能发的，配对记录才是用户亲手批准过的那一份信任。
    ///
    /// 返回第一个探测存活的通道；一台都没有时返回 nil（不抛，
    /// 让上层按「未连接」走退避，而不是把发现失败当错误刷屏）。
    public static func discoverPaired(store: PairedDeviceStore = .shared,
                                      browse: (TimeInterval) -> [FolderDiscovery.Candidate] =
                                          { FolderDiscovery.browse(timeout: $0) }) -> FileWeaveFolderSource? {
        let records = store.devices()
        let byFp = Dictionary(grouping: records.filter { fp in fp.folderFp != nil },
                              by: { $0.folderFp! })
        guard !byFp.isEmpty else { return nil }

        for c in browse(2.0) {
            guard let matches = byFp[c.fp], let record = matches.first else { continue }
            let source = FileWeaveFolderSource(host: c.host, port: c.port,
                                               fp: c.fp, token: record.token,
                                               deviceName: record.name)
            if source.probe() {
                return source
            }
        }
        return nil
    }

    // MARK: HTTP

    /// 端点 URL 构造。契约路径（/v1/status、/v1/tree、/v1/file、
    /// POST /v1/trash、/v1/restore）全从这里过，抽成纯函数便于单测钉住。
    static func endpointURL(host: String, port: Int, path: String,
                            query: [String: String] = [:]) -> URL? {
        var comps = URLComponents(string: "https://\(host):\(port)\(path)")
        if !query.isEmpty {
            // 按名排序让生成稳定，断言才好写
            comps?.queryItems = query.sorted { $0.key < $1.key }
                .map { URLQueryItem(name: $0.key, value: $0.value) }
        }
        return comps?.url
    }

    /// 解析 `Content-Range: bytes 0-99/1000`（契约允许 206 响应）。
    /// 认不出的格式返回 nil，由调用方决定要不要较真。
    static func parseContentRange(_ value: String) -> (start: Int64, end: Int64, total: Int64)? {
        let parts = value.split(separator: " ")
        guard parts.count == 2, parts[0] == "bytes" else { return nil }
        let pieces = parts[1].split(separator: "/")
        guard pieces.count == 2 else { return nil }
        let bounds = pieces[0].split(separator: "-")
        guard bounds.count == 2,
              let start = Int64(bounds[0]), let end = Int64(bounds[1]),
              let total = Int64(pieces[1]), start <= end, end < total else { return nil }
        return (start, end, total)
    }

    private func get(_ path: String, timeout: TimeInterval) throws -> Data {
        guard let url = Self.endpointURL(host: host, port: port, path: path) else {
            throw SourceError.server("请求 \(path) 失败")
        }
        var req = URLRequest(url: url)
        req.timeoutInterval = timeout
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        let (data, resp) = try session.dataTaskSync(req)
        guard let http = resp as? HTTPURLResponse, http.statusCode == 200 else {
            throw SourceError.server("请求 \(path) 失败")
        }
        return data
    }

    private func post(_ path: String, body: Data) throws -> Data {
        guard let url = Self.endpointURL(host: host, port: port, path: path) else {
            throw SourceError.server("请求 \(path) 失败")
        }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.timeoutInterval = 60
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = body
        let (data, resp) = try session.dataTaskSync(req)
        // 非 2xx 时优先把 body 里的 error 透出来，而不是让调用方拿到一句解码错误
        if let http = resp as? HTTPURLResponse, !(200...299).contains(http.statusCode) {
            let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
            let detail = object?["error"] as? String
            throw SourceError.server("请求 \(path) 失败：\(detail ?? String(http.statusCode))")
        }
        return data
    }
}

// MARK: - 证书固定 + 禁止重定向

/// 与 Android 端 BridgeClient.kt 同思路的信任判定：
/// 拿 leaf 证书 DER 算 sha256，和配对指纹逐字节比对；不一致直接拒绝。
/// 主机名不参与判定 —— 自签证书的 CN 什么都不是，指纹才是身份。
final class PinningDelegate: NSObject, URLSessionDataDelegate, @unchecked Sendable {

    private let fp: String

    init(fp: String) {
        self.fp = fp
    }

    func urlSession(_ session: URLSession,
                    didReceive challenge: URLAuthenticationChallenge,
                    completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        guard let trust = challenge.protectionSpace.serverTrust else {
            completionHandler(.cancelAuthenticationChallenge, nil)
            return
        }
        guard let chain = SecTrustCopyCertificateChain(trust) as? [SecCertificate],
              let leaf = chain.first else {
            completionHandler(.cancelAuthenticationChallenge, nil)
            return
        }
        let der = SecCertificateCopyData(leaf) as Data
        let digest = SHA256.hash(data: der).map { String(format: "%02x", $0) }.joined()
        if digest == fp {
            completionHandler(.useCredential, URLCredential(trust: trust))
        } else {
            // 证书变了 = 那不是配对过的手机。必须拒绝，绝不能落回系统信任链
            completionHandler(.cancelAuthenticationChallenge, nil)
        }
    }

    /// 禁止重定向：被指向别处的「共享文件夹」不是用户批准过的那个
    func urlSession(_ session: URLSession,
                    task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}

// MARK: - URLSession 同步桥

extension URLSession {
    /// AppKit 主线程上不想引入一层 async 的话用它。
    /// 内部跑在自己的线程上，不占调用方。
    func dataTaskSync(_ request: URLRequest) throws -> (Data, URLResponse) {
        let box = SyncBox()
        let task = self.dataTask(with: request) { data, resp, err in
            box.data = data
            box.response = resp
            box.error = err
            box.sem.signal()
        }
        task.resume()
        box.sem.wait()
        if let err = box.error { throw err }
        guard let data = box.data, let resp = box.response else {
            throw SourceError.server("没有拿到响应")
        }
        return (data, resp)
    }

    final class SyncBox: @unchecked Sendable {
        let sem = DispatchSemaphore(value: 0)
        var data: Data?
        var response: URLResponse?
        var error: Error?
    }
}
