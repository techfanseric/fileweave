import Foundation
import AppKit

/// 本地转录服务：ffmpeg 转码 + FunASR 转写。
///
/// 全部在本机完成，音频不出网。
/// 依赖一次性的安装脚本（venv + 模型），安装后 isInstalled 为 true。
public enum ASR {

    /// 语音环境与模型都在这里，和 library.json 同属应用数据目录
    public static var supportDir: URL {
        Library.directory.appendingPathComponent("asr", isDirectory: true)
    }

    public static var python: URL {
        supportDir.appendingPathComponent("venv/bin/python")
    }

    /// 转录脚本随 app 打包
    public static var script: URL? {
        Bundle.main.url(forResource: "transcribe", withExtension: "py")
    }

    public static var ffmpeg: String {
        for p in ["/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg", "/usr/bin/ffmpeg"] {
            if FileManager.default.isExecutableFile(atPath: p) { return p }
        }
        return "ffmpeg"
    }

    public static var isInstalled: Bool {
        FileManager.default.isExecutableFile(atPath: python.path)
    }

    /// 给用户看的缺失说明，能区分「没装」和「脚本丢了」
    public static var unavailableReason: String? {
        if isInstalled { return nil }
        return "未安装转录环境。需要先运行安装脚本创建 Python 环境与下载模型。"
    }

    // MARK: 转码

    /// 手机录音是 48kHz AAC，FunASR 要 16kHz 单声道 PCM
    public static func convertToWav(input: URL, output: URL) async throws {
        // ffmpeg 不会自动建目录，父目录不存在会直接失败
        try FileManager.default.createDirectory(
            at: output.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
        try await run(
            ffmpeg, ["-y", "-i", input.path,
                     "-ar", "16000", "-ac", "1",
                     "-c:a", "pcm_s16le", output.path],
            timeout: 600
        )
        guard FileManager.default.fileExists(atPath: output.path) else {
            throw ASRError.ffmpegFailed("未生成 wav")
        }
    }

    // MARK: 转录

    public struct Outcome: Sendable {
        public var txtURL: URL
        public var jsonURL: URL?
        public var audioDuration: Double
        public var sentenceCount: Int
        public var speakerCount: Int
        public var model: String
        public var inferSeconds: Double
    }

    /// 进度用的共享盒，避免在并发闭包里直接改捕获的 var
    private final class ProgressBox: @unchecked Sendable {
        var duration: Double = 0
        var estimate: Double = 0
    }

    /// - Parameter onMeta: 拿到音频总时长（秒），用于估算进度
    /// - Parameter onProgress: 0…1 的估算进度
    public static func transcribe(
        wav: URL,
        txtOut: URL,
        speakers: Int = 0,
        model: String = "paraformer",
        onMeta: @escaping @Sendable (Double) -> Void = { _ in },
        onProgress: @escaping @Sendable (Double) -> Void = { _ in }
    ) async throws -> Outcome {

        guard isInstalled else { throw ASRError.notInstalled }
        guard let script else { throw ASRError.scriptMissing }

        var args = [script.path, wav.path, txtOut.path, "--model", model]
        if speakers > 0 { args += ["--speakers", "\(speakers)"] }

        // 模型首次加载可能十几秒，之后按经验值估进度：
        // Paraformer-zh 在 Apple Silicon CPU 上约 3–5 倍实时
        let start = Date()
        let box = ProgressBox()

        let stdout = try await runStreaming(
            python.path, args, timeout: 3600,
            onLine: { line in
                if line.hasPrefix("META ") {
                    box.duration = Double(line.dropFirst(5)) ?? 0
                    box.estimate = max(8, box.duration / 4)
                    onMeta(box.duration)
                } else if line.hasPrefix("SEG ") {
                    // 用最后一条句子的结束时间推算真实进度
                    let parts = line.split(separator: " ", maxSplits: 3)
                    if parts.count >= 3, let endMs = Double(parts[2]), box.duration > 0 {
                        let pct = min(0.97, endMs / 1000 / box.duration)
                        onProgress(max(pct, min(0.97, Date().timeIntervalSince(start) / box.estimate)))
                    }
                }
            },
            onTick: { elapsed in
                if box.estimate > 0, box.duration > 0 {
                    onProgress(min(0.97, elapsed / box.estimate))
                }
            }
        )

        _ = stdout
        let elapsed = Date().timeIntervalSince(start)

        guard FileManager.default.fileExists(atPath: txtOut.path) else {
            throw ASRError.noOutput
        }

        // 从 JSON 侧车文件读取统计信息
        var sentenceCount = 0
        var speakerCount = 0
        var inferSeconds = elapsed
        var jsonURL: URL?
        let sidecar = URL(fileURLWithPath: txtOut.path)
            .deletingPathExtension()
            .appendingPathExtension("json")

        if let data = try? Data(contentsOf: sidecar),
           let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            sentenceCount = (obj["sentences"] as? [[String: Any]])?.count ?? 0
            speakerCount = obj["speakerCount"] as? Int ?? 0
            // transcribe.py 写的是 totalSeconds；inferSeconds 只作旧侧车的兼容回退。
            inferSeconds = obj["totalSeconds"] as? Double
                ?? obj["inferSeconds"] as? Double
                ?? elapsed
            jsonURL = sidecar
        }

        return Outcome(
            txtURL: txtOut,
            jsonURL: jsonURL,
            audioDuration: box.duration,
            sentenceCount: sentenceCount,
            speakerCount: speakerCount,
            model: model == "sensevoice" ? "SenseVoice" : "Paraformer-zh",
            inferSeconds: inferSeconds
        )
    }

    // MARK: 进程执行

    public enum ASRError: LocalizedError {
        case notInstalled
        case scriptMissing
        case noOutput
        case ffmpegFailed(String)
        case transcribeFailed(String)

        public var errorDescription: String? {
            switch self {
            case .notInstalled: return "转录环境未安装"
            case .scriptMissing: return "转录脚本缺失（app 安装不完整）"
            case .noOutput: return "转录没有产出结果"
            case .ffmpegFailed(let m): return "音频转换失败：\(m)"
            case .transcribeFailed(let m): return m
            }
        }
    }

    private static func run(_ exe: String, _ args: [String], timeout: TimeInterval) async throws {
        let r = try await exec(exe, args, timeout: timeout)
        guard r.code == 0 else {
            let detail = (r.err.isEmpty ? r.out : r.err).trimmingCharacters(in: .whitespacesAndNewlines)
            if exe.contains("ffmpeg") { throw ASRError.ffmpegFailed(detail) }
            throw ASRError.transcribeFailed(detail)
        }
    }

    private struct ExecResult: Sendable { var code: Int32; var out: String; var err: String }

    private static func exec(_ exe: String, _ args: [String], timeout: TimeInterval) async throws -> ExecResult {
        try await withCheckedThrowingContinuation { cont in
            let p = Process()
            p.executableURL = URL(fileURLWithPath: exe)
            p.arguments = args
            let o = Pipe(), e = Pipe()
            p.standardOutput = o; p.standardError = e
            p.standardInput = FileHandle.nullDevice
            var outData = Data(), errData = Data()
            let group = DispatchGroup()
            let q = DispatchQueue(label: "asr.exec", attributes: .concurrent)
            q.async(group: group) { outData = o.fileHandleForReading.readDataToEndOfFile() }
            q.async(group: group) { errData = e.fileHandleForReading.readDataToEndOfFile() }
            do { try p.run() } catch {
                cont.resume(throwing: ASRError.transcribeFailed("无法启动 \(exe): \(error.localizedDescription)"))
                return
            }
            if group.wait(timeout: .now() + timeout) == .timedOut {
                p.terminate()
                _ = group.wait(timeout: .now() + 3)
                cont.resume(throwing: ASRError.transcribeFailed("命令超时"))
                return
            }
            p.waitUntilExit()
            cont.resume(returning: ExecResult(
                code: p.terminationStatus,
                out: String(data: outData, encoding: .utf8) ?? "",
                err: String(data: errData, encoding: .utf8) ?? ""
            ))
        }
    }

    private static func runStreaming(
        _ exe: String, _ args: [String], timeout: TimeInterval,
        onLine: @escaping @Sendable (String) -> Void,
        onTick: @escaping @Sendable (Double) -> Void
    ) async throws -> String {
        try await withCheckedThrowingContinuation { cont in
            let p = Process()
            p.executableURL = URL(fileURLWithPath: exe)
            p.arguments = args
            let o = Pipe(), e = Pipe()
            p.standardOutput = o; p.standardError = e
            p.standardInput = FileHandle.nullDevice

            var collected = ""
            var errCollected = ""
            let start = Date()

            // 逐行读 stdout
            let reader = DispatchQueue(label: "asr.stdout")
            reader.async {
                let h = o.fileHandleForReading
                var buffer = Data()
                while let chunk = try? h.availableData, !chunk.isEmpty {
                    buffer.append(chunk)
                    while let idx = buffer.firstIndex(of: UInt8(ascii: "\n")) {
                        let lineData = buffer[buffer.startIndex..<idx]
                        buffer.removeSubrange(buffer.startIndex...idx)
                        if let line = String(data: lineData, encoding: .utf8), !line.isEmpty {
                            onLine(line)
                            reader.async(flags: .barrier) { collected += line + "\n" }
                        }
                    }
                }
                if let line = String(data: buffer, encoding: .utf8), !line.isEmpty {
                    onLine(line)
                    collected += line + "\n"
                }
            }

            // 定期回报耗时，供上层估进度
            let ticker = DispatchSource.makeTimerSource(queue: DispatchQueue.global())
            ticker.schedule(deadline: .now() + 1, repeating: 1)
            ticker.setEventHandler { onTick(Date().timeIntervalSince(start)) }
            ticker.resume()

            let errReader = DispatchQueue(label: "asr.stderr")
            errReader.async {
                let d = e.fileHandleForReading.readDataToEndOfFile()
                errCollected = String(data: d, encoding: .utf8) ?? ""
            }

            var finished = false
            let finish: (Int32) -> Void = { code in
                guard !finished else { return }
                finished = true
                ticker.cancel()
                let err = errCollected.trimmingCharacters(in: .whitespacesAndNewlines)
                let out = collected.trimmingCharacters(in: .whitespacesAndNewlines)
                if code == 0 {
                    cont.resume(returning: out)
                } else {
                    cont.resume(throwing: ASRError.transcribeFailed(err.isEmpty ? out : err))
                }
            }

            p.terminationHandler = { proc in finish(proc.terminationStatus) }

            do {
                try p.run()
            } catch {
                finished = true
                ticker.cancel()
                cont.resume(throwing: ASRError.transcribeFailed("无法启动: \(error.localizedDescription)"))
                return
            }

            DispatchQueue.global().asyncAfter(deadline: .now() + timeout) {
                if p.isRunning { p.terminate() }
            }
        }
    }
}