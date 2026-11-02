import Foundation

public struct PhoneStorageCapacity: Codable, Equatable, Sendable {
    public let volume: String
    public let totalBytes: Int64
    public let availableBytes: Int64

    var isValid: Bool {
        volume == "primary" && totalBytes > 0 && availableBytes >= 0 && availableBytes <= totalBytes
    }
    var usedFraction: Double { 1 - Double(availableBytes) / Double(totalBytes) }
}

struct PhoneStorageSample: Codable, Equatable, Identifiable {
    let date: Date
    let capacity: PhoneStorageCapacity
    var id: Date { date }
}

struct PhoneStorageDevice: Codable {
    var name: String
    var samples: [PhoneStorageSample]
}

/// Local capacity measurements only: no file names, paths, tokens or remote reporting.
/// Keep each hour's first/latest measurement for 90 days, checkpointed at most every five minutes.
struct PhoneStorageHistory {
    static let retention: TimeInterval = 90 * 24 * 3600
    private let url: URL
    private(set) var devices: [String: PhoneStorageDevice] = [:]
    private(set) var activeDeviceID: String?

    private struct Archive: Codable {
        var devices: [String: PhoneStorageDevice]
        var activeDeviceID: String?
    }

    init(url: URL = Library.directory.appendingPathComponent("storage-history.json"), now: Date = Date()) {
        self.url = url
        if let data = try? Data(contentsOf: url),
           let archive = try? JSONDecoder().decode(Archive.self, from: data) {
            devices = archive.devices.mapValues { device in
                PhoneStorageDevice(name: device.name, samples: device.samples.filter {
                    $0.capacity.isValid && $0.date >= now.addingTimeInterval(-Self.retention) && $0.date <= now
                }.sorted { $0.date < $1.date })
            }
            activeDeviceID = archive.activeDeviceID
        }
    }

    mutating func record(_ capacity: PhoneStorageCapacity, deviceID: String, name: String,
                         at date: Date = Date()) throws {
        guard capacity.isValid else { return }
        let sample = PhoneStorageSample(date: date, capacity: capacity)
        var device = devices[deviceID] ?? PhoneStorageDevice(name: name, samples: [])
        if let last = device.samples.last {
            // Ignore clock rollback. Capacity changes are retained immediately.
            guard date >= last.date else { return }
            if date.timeIntervalSince(last.date) < 300 && last.capacity.totalBytes == capacity.totalBytes {
                return
            }
        }
        device.name = name
        let hour = Int(date.timeIntervalSince1970 / 3600)
        if device.samples.count >= 2,
           Int(device.samples[device.samples.count - 2].date.timeIntervalSince1970 / 3600) == hour,
           device.samples.last?.capacity.totalBytes == capacity.totalBytes {
            device.samples.removeLast()
        }
        device.samples.append(sample)
        var updated = devices.mapValues { device in
            PhoneStorageDevice(name: device.name, samples: device.samples.filter {
                $0.date >= date.addingTimeInterval(-Self.retention)
            })
        }
        device.samples.removeAll { $0.date < date.addingTimeInterval(-Self.retention) }
        updated[deviceID] = device
        let data = try JSONEncoder().encode(Archive(devices: updated, activeDeviceID: deviceID))
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(),
                                               withIntermediateDirectories: true)
        try data.write(to: url, options: .atomic)
        devices = updated
        activeDeviceID = deviceID
    }

    /// Changes are comparable only within the same volume capacity and a continuous observation run.
    static func change(from first: PhoneStorageSample, to last: PhoneStorageSample) -> Int64? {
        guard first.date < last.date, first.capacity.totalBytes == last.capacity.totalBytes,
              first.capacity.volume == last.capacity.volume else { return nil }
        return last.capacity.availableBytes - first.capacity.availableBytes
    }
}

enum PhoneStorageFormat {
    static func date(_ value: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: AppLanguage.selectedCode)
        formatter.setLocalizedDateFormatFromTemplate("MMM d HHmm")
        return formatter.string(from: value)
    }
    static func bytes(_ value: Int64) -> String {
        FileWeaveFormat.bytes(value)
    }
    static func change(_ value: Int64) -> String {
        if value == 0 { return L("无变化") }
        return (value > 0 ? L("增加 ") : L("减少 ")) + bytes(abs(value))
    }
}

/// A rolling observation window, without extrapolating into periods with no measurement.
struct PhoneStorageDayTrend {
    struct Point: Identifiable {
        let sample: PhoneStorageSample
        let segment: Int
        var id: Date { sample.date }
    }

    let start: Date
    let end: Date
    let points: [Point]
    var samples: [PhoneStorageSample] { points.map(\.sample) }

    init(samples: [PhoneStorageSample], latest: PhoneStorageSample?, now: Date) {
        start = now.addingTimeInterval(-86400)
        end = now
        var byDate = Dictionary(samples.map { ($0.date, $0) }, uniquingKeysWith: { _, newest in newest })
        if let latest { byDate[latest.date] = latest }
        let visible = byDate.values.filter {
            $0.capacity.isValid && $0.date >= now.addingTimeInterval(-86400) && $0.date <= now
        }.sorted { $0.date < $1.date }
        var segment = 0
        points = visible.enumerated().map { index, sample in
            if index > 0 {
                let previous = visible[index - 1]
                if sample.date.timeIntervalSince(previous.date) > 7200
                    || sample.capacity.totalBytes != previous.capacity.totalBytes
                    || sample.capacity.volume != previous.capacity.volume {
                    segment += 1
                }
            }
            return Point(sample: sample, segment: segment)
        }
    }
}
