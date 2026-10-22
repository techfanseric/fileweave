import Foundation

/// 转录结果里的一句话
public struct TranscriptSegment: Identifiable {
    public let id = UUID()
    public let start: String
    public let end: String
    public let speaker: String?
    public let text: String
}

/// 一份可在应用内直接阅读的转录
public struct TranscriptDocument: Identifiable {
    public var id: String { remotePath }
    public let remotePath: String
    public let title: String
    public let duration: String
    public let generatedAt: String
    public let model: String
    public let summary: String
    public let segments: [TranscriptSegment]
    public let txtURL: URL

    /// 解析 TXT 产物。格式是我们自己写死的，解析失败返回 nil 而不是崩溃。
    public static func parse(remotePath: String, title: String, txtURL: URL) -> TranscriptDocument? {
        guard let raw = try? String(contentsOf: txtURL, encoding: .utf8) else { return nil }

        var duration = ""
        var generated = ""
        var model = ""
        var summary = ""
        var segments: [TranscriptSegment] = []

        var pendingTime: (start: String, end: String, speaker: String?)?
        var pendingText: [String] = []

        func flush() {
            defer { pendingTime = nil; pendingText.removeAll() }
            guard let t = pendingTime else { return }
            let body = pendingText.joined(separator: " ").trimmingCharacters(in: .whitespaces)
            guard !body.isEmpty else { return }
            segments.append(TranscriptSegment(
                start: t.start, end: t.end, speaker: t.speaker, text: body))
        }

        for rawLine in raw.components(separatedBy: .newlines) {
            let line = rawLine.trimmingCharacters(in: .whitespaces)

            if line.isEmpty {
                flush()
                continue
            }
            // 分隔线
            if line.hasPrefix("─") || line.hasPrefix("—") {
                flush()
                continue
            }
            // 头部字段
            if !line.hasPrefix("["), let sep = line.firstIndex(of: "：") {
                let key = String(line[line.startIndex..<sep])
                let value = String(line[line.index(after: sep)...])
                switch key {
                case "文件", "时长", "生成", "模型", "句子", "耗时":
                    flush()
                    switch key {
                    case "时长": duration = value
                    case "生成": generated = value
                    case "模型": model = value
                    case "句子": summary = value
                    default: break
                    }
                    continue
                default:
                    break
                }
            }
            // 段头：[00:00.5 → 00:01.0] 说话人 1
            if line.hasPrefix("["),
               let close = line.firstIndex(of: "]"),
               close > line.startIndex {   // 单独一个「[」时 close == startIndex，切片会越界
                flush()
                let inside = String(line[line.index(after: line.startIndex)..<close])
                    .trimmingCharacters(in: .whitespaces)
                let parts = inside.components(separatedBy: "→")
                let start = parts.first?.trimmingCharacters(in: .whitespaces) ?? ""
                let end = parts.count > 1
                    ? parts[1].trimmingCharacters(in: .whitespaces) : ""
                let tail = line[line.index(after: close)...]
                    .trimmingCharacters(in: .whitespaces)
                // 「说话人 1」或「说话人A」，没有则说明未分离
                var speaker: String?
                if tail.hasPrefix("说话人") {
                    speaker = tail
                        .replacingOccurrences(of: "说话人", with: "")
                        .trimmingCharacters(in: .whitespaces)
                }
                pendingTime = (start, end, speaker)
                continue
            }
            // 正文续行
            pendingText.append(line)
        }
        flush()

        guard !segments.isEmpty else { return nil }

        return TranscriptDocument(
            remotePath: remotePath,
            title: title,
            duration: duration,
            generatedAt: generated,
            model: model,
            summary: summary,
            segments: segments,
            txtURL: txtURL
        )
    }
}