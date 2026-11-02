import SwiftUI
import Charts

/// Always visible beside the file/recordings column, independently of its navigation state.
struct PhoneStorageDayTrendView: View {
    @EnvironmentObject private var app: AppState
    @State private var selectedDate: Date?

    var body: some View {
        TimelineView(.periodic(from: .now, by: 60)) { context in
            let trend = PhoneStorageDayTrend(samples: app.storageSamples, latest: app.storageSample, now: context.date)
            content(trend)
        }
        .onChange(of: app.storageDeviceID) { _, _ in selectedDate = nil }
    }

    private func content(_ trend: PhoneStorageDayTrend) -> some View {
        let samples = trend.samples
        let selected = selectedDate.flatMap { date in
            samples.min { abs($0.date.timeIntervalSince(date)) < abs($1.date.timeIntervalSince(date)) }
        } ?? samples.last
        return VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text(L("容量趋势")).fontWeight(.medium)
                Spacer()
                Text(L("24 小时")).foregroundStyle(.secondary)
            }
            .font(.system(size: 13))
            if let selected {
                HStack(spacing: 4) {
                    Text(PhoneStorageFormat.date(selected.date))
                    Spacer(minLength: 0)
                    Text(PhoneStorageFormat.bytes(selected.capacity.availableBytes))
                }
                .font(.system(size: 12))
                .foregroundStyle(.secondary)
                .padding(.top, 12)

                Chart {
                    ForEach(trend.points) { point in
                        LineMark(x: .value(L("时间"), point.sample.date),
                                 y: .value(L("剩余 GB"), gigabytes(point.sample)),
                                 series: .value(L("连续采样"), point.segment))
                            .foregroundStyle(Color.accentColor)
                        PointMark(x: .value(L("时间"), point.sample.date),
                                  y: .value(L("剩余 GB"), gigabytes(point.sample)))
                            .symbolSize(8)
                            .foregroundStyle(Color.accentColor.opacity(0.5))
                    }
                    PointMark(x: .value(L("时间"), selected.date),
                              y: .value(L("剩余 GB"), gigabytes(selected)))
                        .symbolSize(28)
                        .foregroundStyle(Color.accentColor)
                }
                .chartXScale(domain: trend.start...trend.end)
                .chartYScale(domain: yDomain(samples))
                .chartYAxisLabel(L("剩余 GB"))
                .chartXAxis {
                    AxisMarks(values: [trend.start, trend.start.addingTimeInterval(43200), trend.end]) { value in
                        AxisGridLine()
                        AxisTick()
                        AxisValueLabel(anchor: value.index == 0 ? .topLeading : value.index == 2 ? .topTrailing : .top) {
                            Text(value.index == 0 ? "−24h" : value.index == 2 ? L("现在") : "−12h")
                        }
                    }
                }
                .chartXSelection(value: $selectedDate)
                .frame(height: 140)
                .padding(.top, 12)
                .accessibilityLabel(L("最近24小时手机剩余容量，未采样时段留空，长时间离线处断开"))

                Group {
                    if let first = samples.first, let last = samples.last,
                       let change = PhoneStorageHistory.change(from: first, to: last) {
                        Text(PhoneStorageFormat.change(change))
                        Text(L("较 {0}", PhoneStorageFormat.date(first.date)))
                    } else if samples.count == 1 {
                        Text(L("已记录首个采样点"))
                        Text(L("连接后逐步形成趋势"))
                    } else {
                        Text(L("总容量变化，已分段显示"))
                    }
                }
                .font(.system(size: 12))
                .foregroundStyle(.secondary)
                .padding(.top, 8)
            } else {
                Spacer()
                VStack(alignment: .leading, spacing: 6) {
                    Image(systemName: "chart.xyaxis.line").font(.system(size: 22))
                    Text(L("近 24 小时暂无记录"))
                    Text(L("连接手机后开始积累"))
                        .font(.system(size: 12))
                }
                .font(.system(size: 13))
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .center)
                Spacer()
            }

            Spacer(minLength: 12)
            Divider()
            Text(L("仅显示实际采样，未记录时段留空。"))
                .font(.system(size: 12))
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 8)
        }
        .monospacedDigit()
        .padding(12)
    }

    private func gigabytes(_ sample: PhoneStorageSample) -> Double {
        Double(sample.capacity.availableBytes) / 1_000_000_000
    }

    private func yDomain(_ samples: [PhoneStorageSample]) -> ClosedRange<Double> {
        let values = samples.map(gigabytes)
        let low = values.min() ?? 0
        let high = values.max() ?? 1
        let padding = max((high - low) * 0.15, 0.5)
        return max(0, low - padding)...(high + padding)
    }
}

struct PhoneStorageHeader: View {
    @EnvironmentObject private var app: AppState
    @Binding var showsHistory: Bool

    private var todayChange: Int64? {
        guard let latest = app.storageSample,
              let first = app.storageSamples.first(where: { Calendar.current.isDate($0.date, inSameDayAs: latest.date) })
        else { return nil }
        return PhoneStorageHistory.change(from: first, to: latest)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(L("手机存储")).fontWeight(.medium)
                Spacer()
                Button(showsHistory ? L("返回趋势") : L("查看历史")) { showsHistory.toggle() }
                    .buttonStyle(.borderless)
                    .accessibilityHint(L("在右侧切换容量历史与24小时趋势"))
            }
            Text(app.storageDeviceName ?? L("手机主存储"))
                .font(.system(size: 12))
                .foregroundStyle(.secondary)
                .lineLimit(1)
            if let sample = app.storageSample {
                VStack(alignment: .leading, spacing: 4) {
                    Text(L("剩余 {0}", PhoneStorageFormat.bytes(sample.capacity.availableBytes)))
                        .font(.system(size: 15, weight: .medium))
                    Text(L("总容量 {0}", PhoneStorageFormat.bytes(sample.capacity.totalBytes)))
                        .foregroundStyle(.secondary)
                }
                ProgressView(value: sample.capacity.usedFraction)
                    .tint(sample.capacity.availableBytes < sample.capacity.totalBytes / 10 ? .orange : .accentColor)
                    .accessibilityLabel(L("已用存储空间"))
                    .accessibilityValue("\(Int(sample.capacity.usedFraction * 100))%")
                Text(L("已用 {0}%", Int(sample.capacity.usedFraction * 100)))
                    .foregroundStyle(.secondary)
                VStack(alignment: .leading, spacing: 4) {
                    Text("\(app.storageIsCurrent && app.link.isReady ? L("更新于") : L("上次更新")) \(PhoneStorageFormat.date(sample.date))")
                    if !app.storageMessage.isEmpty {
                        Text(app.storageMessage)
                    }
                    if let change = todayChange {
                        Text(L("较今日首条 · {0}", PhoneStorageFormat.change(change)))
                    }
                }
                .font(.system(size: 12))
                .foregroundStyle(.secondary)
            } else {
                Text(app.storageMessage)
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
            }
        }
        .font(.system(size: 13))
        .monospacedDigit()
        .padding(12)
    }
}

struct PhoneStorageHistoryView: View {
    @EnvironmentObject private var app: AppState
    @State private var days = 7
    @State private var selectedDate: Date?

    private var samples: [PhoneStorageSample] {
        let cutoff = Date().addingTimeInterval(-Double(days) * 86400)
        var samples = app.storageSamples.filter { $0.date >= cutoff }
        if let latest = app.storageSample, latest.date >= cutoff, samples.last?.date != latest.date {
            samples.append(latest)
        }
        return samples
    }

    private var selected: PhoneStorageSample? {
        guard let selectedDate else { return samples.last }
        return samples.min { abs($0.date.timeIntervalSince(selectedDate)) < abs($1.date.timeIntervalSince(selectedDate)) }
    }

    private struct Point: Identifiable {
        let sample: PhoneStorageSample
        let segment: Int
        var id: Date { sample.date }
    }

    private var points: [Point] {
        var segment = 0
        let values = samples
        return values.enumerated().map { index, sample in
            if index > 0, sample.date.timeIntervalSince(values[index - 1].date) > 7200
                || sample.capacity.totalBytes != values[index - 1].capacity.totalBytes {
                segment += 1
            }
            return Point(sample: sample, segment: segment)
        }
    }

    private var yDomain: ClosedRange<Double> {
        let values = samples.map { Double($0.capacity.availableBytes) / 1_000_000_000 }
        let low = values.min() ?? 0
        let high = values.max() ?? 1
        let padding = max((high - low) * 0.15, 0.5)
        return max(0, low - padding)...(high + padding)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(L("容量历史")).fontWeight(.medium)
                Spacer()
            }
            Picker(L("历史时间范围"), selection: $days) {
                Text(L("24 小时")).tag(1)
                Text(L("7 天")).tag(7)
                Text(L("30 天")).tag(30)
                Text(L("90 天")).tag(90)
            }
            .pickerStyle(.menu)
            .controlSize(.small)
            .onChange(of: days) { _, _ in selectedDate = nil }

            if let sample = selected {
                HStack(spacing: 4) {
                    Text(PhoneStorageFormat.date(sample.date))
                        .foregroundStyle(.secondary)
                    Spacer(minLength: 0)
                    Text(PhoneStorageFormat.bytes(sample.capacity.availableBytes))
                        .fontWeight(.medium)
                }
                Chart {
                    ForEach(points) { point in
                        LineMark(x: .value(L("时间"), point.sample.date),
                                 y: .value(L("剩余 GB"), Double(point.sample.capacity.availableBytes) / 1_000_000_000),
                                 series: .value(L("连续采样"), point.segment))
                        .foregroundStyle(Color.accentColor)
                    }
                    PointMark(x: .value(L("时间"), sample.date),
                              y: .value(L("剩余 GB"), Double(sample.capacity.availableBytes) / 1_000_000_000))
                        .foregroundStyle(Color.accentColor)
                }
                .chartYScale(domain: yDomain)
                .chartYAxisLabel(L("剩余 GB"))
                .chartXSelection(value: $selectedDate)
                .frame(height: 100)
                .accessibilityLabel(L("手机剩余容量趋势，长时间离线处断开"))

                if let first = samples.first, let last = samples.last,
                   let change = PhoneStorageHistory.change(from: first, to: last) {
                    Text(L("较 {0} · {1}", PhoneStorageFormat.date(first.date), PhoneStorageFormat.change(change)))
                        .foregroundStyle(.secondary)
                } else if samples.count < 2 {
                    Text(L("已保存首条记录，连接后继续积累"))
                        .foregroundStyle(.secondary)
                } else {
                    Text(L("总容量已变化，此时段不合并计算增减"))
                        .foregroundStyle(.secondary)
                }
                Divider()
                ScrollView {
                    LazyVStack(spacing: 7) {
                        ForEach(Array(samples.reversed())) { item in
                            VStack(alignment: .leading, spacing: 3) {
                                HStack {
                                    Text(PhoneStorageFormat.date(item.date))
                                        .foregroundStyle(.secondary)
                                    Spacer(minLength: 0)
                                    Text(PhoneStorageFormat.bytes(item.capacity.availableBytes))
                                }
                                Text(L("总容量 {0}", PhoneStorageFormat.bytes(item.capacity.totalBytes)))
                                    .font(.system(size: 12))
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                }
                .frame(maxHeight: .infinity)
            } else {
                Spacer()
                Text(L("暂无这个时段的容量记录"))
                    .frame(maxWidth: .infinity)
                    .foregroundStyle(.secondary)
                Spacer()
            }
            Text(L("连接时采样，本地保留 90 天。"))
                .font(.system(size: 12))
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .font(.system(size: 13))
        .monospacedDigit()
        .padding(12)
        .onChange(of: app.storageDeviceID) { _, _ in selectedDate = nil }
    }
}
