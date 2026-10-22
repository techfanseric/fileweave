import Foundation

/// 用大模型给转录内容生成一句话摘要。
///
/// 默认走用户本地已有的阿里云百炼凭据（OpenAI 兼容端点），
/// 可以随时关掉 —— 关掉后仍然有本地生成的「预览」可用。
public enum SummaryService {

    public struct Config {
        public var endpoint: String
        public var apiKey: String
        public var model: String
    }

    public enum SummaryError: LocalizedError {
        case noCredential
        case badResponse(String)
        case empty

        public var errorDescription: String? {
            switch self {
            case .noCredential: return "找不到 API 凭据"
            case .badResponse(let m): return m
            case .empty: return "模型没有返回内容"
            }
        }
    }

    // MARK: 凭据

    /// 从 .env 风格的文本里取出指定键
    static func parseEnv(_ text: String, key: String) -> String? {
        for line in text.components(separatedBy: .newlines) {
            let t = line.trimmingCharacters(in: .whitespaces)
            guard !t.isEmpty, !t.hasPrefix("#"), let eq = t.firstIndex(of: "=") else { continue }
            let k = String(t[t.startIndex..<eq]).trimmingCharacters(in: .whitespaces)
            guard k == key else { continue }
            var v = String(t[t.index(after: eq)...])
            // 去掉可能的引号
            v = v.trimmingCharacters(in: CharacterSet(charactersIn: " \"'"))
            return v.isEmpty ? nil : v
        }
        return nil
    }

    /// AI 摘要用的百炼凭据。两个口子：
    ///
    /// 1. 环境变量 `FILEWEAVE_BAILIAN_ENV` —— 直接指到 env 文件，最省事
    /// 2. 应用数据目录下的 `bailian.env` —— 设置一次就长期生效，不用每次带环境变量
    ///
    /// 都找不到时摘要功能降级成「本地预览」，不会崩，只是没有 AI 摘要。
    /// （旧版还有一个回退到本机私人项目路径的口子，别人 clone 下来跑不了，已删。）
    public static func defaultCredentialPath() -> URL {
        if let override = ProcessInfo.processInfo.environment["FILEWEAVE_BAILIAN_ENV"],
           !override.isEmpty {
            return URL(fileURLWithPath: (override as NSString).expandingTildeInPath)
        }
        let appSupport = FileManager.default.urls(for: .applicationSupportDirectory,
                                                  in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSHomeDirectory() + "/Library/Application Support")
        return appSupport.appendingPathComponent("FileWeave/bailian.env")
    }

    public static func loadConfig(from path: URL,
                                  model: String = AppState.defaultSummaryModel) -> Config? {
        guard let text = try? String(contentsOf: path, encoding: .utf8) else { return nil }
        guard let key = parseEnv(text, key: "DASHSCOPE_API_KEY") else { return nil }
        let endpoint = parseEnv(text, key: "DASHSCOPE_OPENAI_COMPATIBLE_URL")
            ?? "https://dashscope.aliyuncs.com/compatible-mode/v1"
        return Config(endpoint: endpoint, apiKey: key, model: model)
    }

    // MARK: 本地预览（不联网）

    /// 从转录里截一句有信息量的开头，当作快速预览。
    /// 没有网络、不花钱、瞬时返回，摘要不可用时兜底用这个。
    public static func localPreview(from transcript: String, limit: Int = 42) -> String {
        // 正文在「────」分隔线之后，跳过文件/时长/生成等头部字段
        let all = transcript.components(separatedBy: .newlines)
        let sepIndex = all.firstIndex { $0.hasPrefix("─") || $0.hasPrefix("—") }
        let body = sepIndex.map { Array(all[($0 + 1)...]) } ?? all

        let flat = body
            .map { $0.trimmingCharacters(in: .whitespaces) }
            // 段头形如 [00:00.5 → 00:01.0] 说话人 1，跳过
            .filter { !$0.hasPrefix("[") && !$0.isEmpty }
            .joined(separator: "")
        guard !flat.isEmpty else { return "" }

        // 以句读切，取第一句
        let stopChars: [Character] = ["。", "！", "？", "；", "\n"]
        var cut = flat.endIndex
        if flat.count > limit {
            let head = flat.index(flat.startIndex, offsetBy: limit)
            var found: String.Index? = nil
            for ch in stopChars {
                if let idx = flat[flat.startIndex..<head].lastIndex(of: ch) {
                    found = idx
                    break
                }
            }
            cut = found.map { flat.index(after: $0) } ?? head
        }
        let s = String(flat[flat.startIndex..<cut])
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return flat.count > limit ? s + "…" : s
    }

    // MARK: AI 摘要

    private static let systemPrompt = """
    你是一个通话/语音记录的摘要助手。规则：
    1. 用**一句话**概括，**不超过 30 个字**。
    2. 直接说内容本身，不要写「这段录音讨论了」「主要内容是」这类前缀。
    3. 涉及时间、人名、决定时保留关键信息。
    4. 如果内容零碎没有主题，就写出最主要的一件事。
    """

    public static func summarize(transcript: String,
                                 config: Config,
                                 timeout: TimeInterval = 60) async throws -> String {
        let cfg = try await load(config: config, timeout: timeout)
        let text = transcript.count > 4000
            ? String(transcript.prefix(4000)) + "…"
            : transcript
        return try await run(prompt: text, config: cfg, timeout: timeout)
    }

    private struct Loaded {
        let endpoint: String
        let key: String
        let model: String
    }

    private static func load(config: Config, timeout: TimeInterval) async throws -> Loaded {
        Loaded(endpoint: config.endpoint, key: config.apiKey, model: config.model)
    }

    private static func run(prompt: String, config: Loaded, timeout: TimeInterval) async throws -> String {
        let body: [String: Any] = [
            "model": config.model,
            "messages": [
                ["role": "system", "content": systemPrompt],
                ["role": "user", "content": "转录文本：\n" + prompt],
            ],
            "max_tokens": 120,
            "temperature": 0.3,
        ]

        var req = URLRequest(url: URL(string: config.endpoint + "/chat/completions")!)
        req.httpMethod = "POST"
        req.timeoutInterval = timeout
        req.setValue("Bearer \(config.key)", forHTTPHeaderField: "Authorization")
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try JSONSerialization.data(withJSONObject: body)

        let (data, response) = try await URLSession.shared.data(for: req)
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw SummaryError.badResponse("HTTP \(http.statusCode)")
        }

        guard let obj = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw SummaryError.badResponse("返回格式无法解析")
        }
        if let err = obj["error"] as? [String: Any] {
            throw SummaryError.badResponse(err["message"] as? String ?? "接口报错")
        }
        guard let choices = obj["choices"] as? [[String: Any]],
              let msg = choices.first?["message"] as? [String: Any],
              let text = msg["content"] as? String else {
            throw SummaryError.empty
        }
        let cleaned = text
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "\n", with: " ")
        guard !cleaned.isEmpty else { throw SummaryError.empty }
        return cleaned
    }
}
