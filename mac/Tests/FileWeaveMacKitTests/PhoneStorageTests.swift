import XCTest
@testable import FileWeaveMacKit

final class PhoneStorageTests: XCTestCase {
    private let start = Date(timeIntervalSince1970: 1_793_491_200)
    private func capacity(_ free: Int64 = 400, total: Int64 = 1000) -> PhoneStorageCapacity {
        PhoneStorageCapacity(volume: "primary", totalBytes: total, availableBytes: free)
    }
    private func temporaryURL() -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
            .appendingPathComponent("history.json")
    }

    func testStatusDecodes64BitCapacityAndToleratesOldOrMalformedStorage() throws {
        let json = #"{"proto":1,"storage":{"volume":"primary","totalBytes":512000000000,"availableBytes":123000000000}}"#
        let value = try XCTUnwrap(FileWeaveFolderSource.capacity(fromStatus: Data(json.utf8)))
        XCTAssertEqual(value.totalBytes, 512_000_000_000)
        XCTAssertEqual(value.availableBytes, 123_000_000_000)
        for json in [#"{"proto":1}"#, #"{"proto":1,"storage":null}"#,
                     #"{"proto":1,"storage":{"totalBytes":"unknown"}}"#] {
            XCTAssertNil(try FileWeaveFolderSource.capacity(fromStatus: Data(json.utf8)))
            XCTAssertEqual(try JSONDecoder().decode(FileWeaveFolderSource.StatusResponse.self, from: Data(json.utf8)).proto, 1)
        }
    }

    func testStatusRejectsImpossibleValuesAndUnknownVolumesButAllowsFullVolume() throws {
        for (total, available, volume) in [(0, 0, "primary"), (100, -1, "primary"),
                                           (100, 101, "primary"), (100, 1, "sdcard")] {
            let json = "{\"storage\":{\"volume\":\"\(volume)\",\"totalBytes\":\(total),\"availableBytes\":\(available)}}"
            XCTAssertNil(try FileWeaveFolderSource.capacity(fromStatus: Data(json.utf8)))
        }
        XCTAssertTrue(capacity(0).isValid)
        XCTAssertEqual(capacity(0).usedFraction, 1)
    }

    func testHistoryPersistsAndSeparatesPhonesWithEqualNames() throws {
        let url = temporaryURL()
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        var history = PhoneStorageHistory(url: url, now: start)
        try history.record(capacity(), deviceID: "phone-a", name: "Phone", at: start)
        try history.record(capacity(200), deviceID: "phone-b", name: "Phone", at: start)
        let loaded = PhoneStorageHistory(url: url, now: start)
        XCTAssertEqual(loaded.activeDeviceID, "phone-b")
        XCTAssertEqual(loaded.devices["phone-a"]?.samples.last?.capacity.availableBytes, 400)
        XCTAssertEqual(loaded.devices["phone-b"]?.samples.last?.capacity.availableBytes, 200)
        let text = try String(contentsOf: url, encoding: .utf8)
        XCTAssertFalse(text.contains("token"))
    }

    func testCheckpointsAreThrottledAndKeepFirstAndLastOfEachHour() throws {
        let url = temporaryURL()
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        var history = PhoneStorageHistory(url: url, now: start)
        for minutes in [0, 1, 5, 10, 60, 65, 70] {
            try history.record(capacity(Int64(400 - minutes)), deviceID: "a", name: "A",
                               at: start.addingTimeInterval(Double(minutes * 60)))
        }
        let samples = try XCTUnwrap(history.devices["a"]?.samples)
        XCTAssertEqual(samples.map(\.capacity.availableBytes), [400, 390, 340, 330])
    }

    func testRetentionAndClockRollbackNeverInventSamples() throws {
        let url = temporaryURL()
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        var history = PhoneStorageHistory(url: url, now: start)
        try history.record(capacity(), deviceID: "a", name: "A", at: start)
        try history.record(capacity(100), deviceID: "a", name: "A", at: start.addingTimeInterval(-60))
        XCTAssertEqual(history.devices["a"]?.samples.count, 1)
        let later = start.addingTimeInterval(PhoneStorageHistory.retention + 1)
        try history.record(capacity(300), deviceID: "b", name: "B", at: later)
        XCTAssertTrue(history.devices["a"]?.samples.isEmpty == true)
        let loaded = PhoneStorageHistory(url: url, now: later.addingTimeInterval(PhoneStorageHistory.retention + 1))
        XCTAssertTrue(loaded.devices.values.allSatisfy { $0.samples.isEmpty })
    }

    func testCapacityChangeStartsNewComparisonAndIsNotThrottled() throws {
        let url = temporaryURL()
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        var history = PhoneStorageHistory(url: url, now: start)
        try history.record(capacity(), deviceID: "a", name: "A", at: start)
        try history.record(capacity(900, total: 2000), deviceID: "a", name: "A", at: start.addingTimeInterval(1))
        let samples = try XCTUnwrap(history.devices["a"]?.samples)
        XCTAssertEqual(samples.count, 2)
        XCTAssertNil(PhoneStorageHistory.change(from: samples[0], to: samples[1]))
        let next = PhoneStorageSample(date: start.addingTimeInterval(10), capacity: capacity(390))
        XCTAssertEqual(PhoneStorageHistory.change(from: samples[0], to: next), -10)
        XCTAssertNil(PhoneStorageHistory.change(from: next, to: samples[0]))
    }

    func testWriteFailureDoesNotClaimHistoryWasSaved() throws {
        let url = temporaryURL()
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data().write(to: url)
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        var history = PhoneStorageHistory(url: url.appendingPathComponent("blocked"), now: start)
        XCTAssertThrowsError(try history.record(capacity(), deviceID: "a", name: "A", at: start))
        XCTAssertTrue(history.devices.isEmpty)
        XCTAssertNil(history.activeDeviceID)
    }

    func testDayTrendUsesExactRollingWindowAndDeduplicatesLiveSample() {
        let now = start.addingTimeInterval(86400)
        let first = PhoneStorageSample(date: start, capacity: capacity())
        let latest = PhoneStorageSample(date: now, capacity: capacity(300))
        let trend = PhoneStorageDayTrend(samples: [
            latest,
            PhoneStorageSample(date: start.addingTimeInterval(-1), capacity: capacity()),
            first,
            PhoneStorageSample(date: now.addingTimeInterval(1), capacity: capacity())
        ], latest: latest, now: now)
        XCTAssertEqual(trend.start, start)
        XCTAssertEqual(trend.end, now)
        XCTAssertEqual(trend.samples, [first, latest])
    }

    func testDayTrendSplitsOfflineGapsAndTotalCapacityChanges() {
        let offsets: [TimeInterval] = [0, 3600, 10801, 10802]
        let samples = offsets.enumerated().map { index, offset in
            PhoneStorageSample(date: start.addingTimeInterval(offset),
                               capacity: capacity(300, total: index == 3 ? 2000 : 1000))
        }
        let trend = PhoneStorageDayTrend(samples: samples, latest: nil, now: start.addingTimeInterval(12000))
        XCTAssertEqual(trend.points.map(\.segment), [0, 0, 1, 2])
    }

    func testDayTrendDoesNotFillEmptyOrStaleWindows() {
        let latest = PhoneStorageSample(date: start, capacity: capacity())
        let trend = PhoneStorageDayTrend(samples: [latest], latest: latest,
                                        now: start.addingTimeInterval(86401))
        XCTAssertTrue(trend.points.isEmpty)
        XCTAssertTrue(PhoneStorageDayTrend(samples: [], latest: nil, now: start).samples.isEmpty)
    }
}
