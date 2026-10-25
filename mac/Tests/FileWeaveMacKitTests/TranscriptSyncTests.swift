import XCTest
@testable import FileWeaveMacKit

final class TranscriptSyncTests: XCTestCase {
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
