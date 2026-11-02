import XCTest
@testable import FileWeaveMacKit

final class TranscriptSyncTests: XCTestCase {
    /// Explicit opt-in only: reads a real recording from the paired phone and sends
    /// its transcript to the configured summary provider. Personal artifacts stay
    /// outside the checkout; ordinary test runs never make this API request.
    func testRealRecordingAndSummary() async throws {
        guard let path = ProcessInfo.processInfo.environment["FILEWEAVE_LIVE_RECORDING_DIR"] else {
            throw XCTSkip("Set FILEWEAVE_LIVE_RECORDING_DIR to opt in to live validation")
        }
        let directory = URL(fileURLWithPath: path, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true,
                                               attributes: [.posixPermissions: 0o700])
        XCTAssertNil(ASR.unavailableReason)
        let config = try XCTUnwrap(SummaryService.loadConfig(from: SummaryService.defaultCredentialPath()))
        let discovery = FileWeaveFolderSource.discoverPairedWithDiagnosis()
        let source = try XCTUnwrap(discovery.source, discovery.diagnosis?.hint ?? "No paired source")
        let snapshot = try source.scan()
        // Re-run a complete real recording with an existing transcript so an
        // unanswered/silent call cannot be mistaken for a usable speech fixture.
        let reference = Library.load()
        let recording = try XCTUnwrap(snapshot.live.filter {
            $0.fileName.hasSuffix(".m4a") && $0.size <= 3_000_000
                && (reference.transcript(for: $0.id)?.sentenceCount ?? 0) >= 5
        }.sorted { $0.size < $1.size }.first)
        try recording.remotePath.write(to: directory.appendingPathComponent("recording-source.txt"),
                                       atomically: true, encoding: .utf8)
        let audio = try source.download(recording, to: directory.appendingPathComponent("recording.m4a"))
        let size = try FileManager.default.attributesOfItem(atPath: audio.path)[.size] as? NSNumber
        XCTAssertEqual(size?.int64Value, recording.size)
        let wav = directory.appendingPathComponent("recording.wav")
        defer { try? FileManager.default.removeItem(at: wav) }
        try await ASR.convertToWav(input: audio, output: wav)
        let outcome = try await ASR.transcribe(wav: wav, txtOut: directory.appendingPathComponent("transcript.txt"))
        XCTAssertGreaterThan(outcome.audioDuration, 0)
        XCTAssertGreaterThan(outcome.sentenceCount, 0)
        XCTAssertGreaterThan(outcome.speakerCount, 0)
        let sidecar = try XCTUnwrap(outcome.jsonURL)
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: sidecar)) as? [String: Any])
        let sentences = try XCTUnwrap(json["sentences"] as? [[String: Any]])
        for sentence in sentences {
            let start = try XCTUnwrap(sentence["start"] as? Double)
            let end = try XCTUnwrap(sentence["end"] as? Double)
            XCTAssertGreaterThanOrEqual(start, 0)
            XCTAssertGreaterThan(end, start)
            XCTAssertLessThanOrEqual(end, outcome.audioDuration * 1000 + 100)
            XCTAssertFalse((sentence["text"] as? String ?? "").isEmpty)
        }
        let transcript = try String(contentsOf: outcome.txtURL, encoding: .utf8)
        XCTAssertFalse(SummaryService.localPreview(from: transcript).isEmpty)
        let summary = try await SummaryService.summarize(transcript: transcript, config: config)
        XCTAssertFalse(summary.isEmpty)
        try summary.write(to: directory.appendingPathComponent("summary.txt"), atomically: true, encoding: .utf8)
        let report: [String: Any] = [
            "recordingPath": recording.remotePath, "downloadedBytes": recording.size,
            "audioDuration": outcome.audioDuration, "sentences": outcome.sentenceCount,
            "speakers": outcome.speakerCount, "inferenceSeconds": outcome.inferSeconds,
            "summaryModel": config.model, "summaryCharacters": summary.count,
            "validatedAt": ISO8601DateFormatter().string(from: Date())
        ]
        try JSONSerialization.data(withJSONObject: report, options: [.prettyPrinted, .sortedKeys])
            .write(to: directory.appendingPathComponent("report.json"), options: .atomic)
    }

    func testOldTranscriptRemainsPendingForPhone() throws {
        let record = TranscriptRecord(txtPath: "/tmp/example.txt", sentenceCount: 1,
            speakerCount: 0, model: "test", audioDuration: 1, inferSeconds: 1, createdAt: Date())
        let data = try JSONEncoder().encode(record)
        let decoded = try JSONDecoder().decode(TranscriptRecord.self, from: data)
        XCTAssertNil(decoded.phoneSynced)
    }

    func testInstalledTranscriptionEngine() async throws {
        guard ASR.isInstalled else { throw XCTSkip("Local transcription models are not installed") }
        let sample = ASR.supportDir.appendingPathComponent("models/sherpa-onnx-paraformer-zh-2023-03-28/test_wavs/0.wav")
        guard FileManager.default.fileExists(atPath: sample.path) else { throw XCTSkip("Model sample is unavailable") }
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let wav = directory.appendingPathComponent("sample.wav")
        try await ASR.convertToWav(input: sample, output: wav)
        let result = try await ASR.transcribe(wav: wav, txtOut: directory.appendingPathComponent("result.txt"))
        XCTAssertGreaterThan(result.sentenceCount, 0)
        XCTAssertGreaterThan(result.audioDuration, 0)
        XCTAssertNotNil(result.jsonURL)
        XCTAssertFalse(try String(contentsOf: result.txtURL, encoding: .utf8).isEmpty)
    }
}
