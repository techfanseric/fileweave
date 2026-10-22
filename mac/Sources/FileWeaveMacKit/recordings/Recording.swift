import Foundation

// MARK: - 类型

public enum RecordingKind: String, Codable, CaseIterable {
    case call      // 通话-xxx.m4a
    case wechat    // 微信-xxx.m4a
    case recorder  // 录音机-xxxx.m4a
    case other

    public var label: String {
        switch self {
        case .call: return "通话"
        case .wechat: return "微信"
        case .recorder: return "录音机"
        case .other: return "其他"
        }
    }

    public var symbol: String {
        switch self {
        case .call: return "phone.fill"
        case .wechat: return "message.fill"
        case .recorder: return "waveform"
        case .other: return "doc.fill"
        }
    }

    var tint: String {
        switch self {
        case .call: return "orange"
        case .wechat: return "green"
        case .recorder: return "red"
        case .other: return "gray"
        }
    }
}

// MARK: - 录音文件

/// 手机上一条录音/通话记录的轻量描述。
/// `id` 用远端路径，保证同一通电话被切成多段时各自独立。
public struct Recording: Identifiable, Equatable, Hashable {
    /// 身份仍然用原路径：删除、还原都不会改变它，
    /// 转录结果与已下载记录才能在来回移动后继续对上。
    public var id: String { remotePath }

    /// 录音在共享文件夹里的位置（相对共享根的路径），
    /// 也是本机台账的主键。
    ///
    /// 文件夹共享协议里的 path 一律是相对路径 —— 契约如此，
    /// 这里不再有「扫描根目录」这种硬编码，手机换目录结构也不用跟着改。
    public let remotePath: String
    public let size: Int64
    public let modifiedAt: Date

    /// 文件夹共享服务用的 documentId。
    ///
    /// 写操作必须靠它而不是路径 —— SAF 里按路径回找是逐层比对
    /// display name，对刚建出来的深层目录会失配。
    public let remoteId: String?

    /// 非 nil 表示当前躺在手机回收站里，这就是它在回收站里的实际路径。
    /// 这个字段完全来自对手机的实扫，不来自本机记忆，
    /// 所以在手机上还原之后，下一轮扫描它自然变回 nil，不会出现假状态。
    public let trashPath: String?

    /// 被移入回收站的时间（毫秒时间戳换算而来）
    public let movedAt: Date?

    public let kind: RecordingKind
    public let subject: String
    public let startedAt: Date?

    public var isTrashed: Bool { trashPath != nil }

    /// 文件当前的真实位置：在回收站就用回收站路径，否则用原路径。
    /// 下载、转录等一切要真正读文件的地方都必须用它，不能用 remotePath。
    public var filePath: String { trashPath ?? remotePath }

    /// 本地保存时保留的相对路径。协议里的 path 本来就是相对共享根的，
    /// 直接用即可（例如 `CallRecord/通话-xxx.m4a`）。
    public var relativePath: String { remotePath }

    public var fileName: String {
        (remotePath as NSString).lastPathComponent
    }

    /// 尺寸文案：B / KB / MB
    var sizeText: String {
        ByteCountFormatter.string(fromByteCount: size, countStyle: .file)
    }

    /// 列表主标题：优先用文件名里解析出的开始时间，否则用修改时间
    var displayTime: Date {
        startedAt ?? modifiedAt
    }

    public init?(remotePath rawPath: String,
                  size: Int64,
                  modifiedAt: Date,
                  trashPath: String? = nil,
                  movedAt: Date? = nil,
                  remoteId: String? = nil) {
        guard !rawPath.isEmpty else { return nil }
        self.remotePath = rawPath
        self.size = size
        self.modifiedAt = modifiedAt
        self.remoteId = remoteId
        self.trashPath = trashPath
        self.movedAt = movedAt

        let stem = ((rawPath as NSString).lastPathComponent as NSString).deletingPathExtension
        let (kind, subject, startedAt) = Recording.parse(stem: stem)
        self.kind = kind
        self.subject = subject
        self.startedAt = startedAt
    }

    // MARK: - 文件名解析

    /// 解析荣耀录音的命名规则：
    ///   录音机-20261009-1418          → 录音机应用
    ///   微信-Yan-202610110803         → 微信
    ///   通话-14743662996-202610121120 → 电话
    ///   通话-147 4366 2996-202610091200 → 电话（号码里混入空格，需要清洗）
    static func parse(stem: String) -> (RecordingKind, String, Date?) {
        if stem.hasPrefix("录音机-") {
            let rest = String(stem.dropFirst("录音机-".count))
            return (.recorder, "录音机应用录音", dashStampFormatter.date(from: rest))
        }

        for (prefix, kind) in [("微信-", RecordingKind.wechat), ("通话-", RecordingKind.call)] {
            guard stem.hasPrefix(prefix) else { continue }
            let rest = String(stem.dropFirst(prefix.count))
            // 去掉末尾的 12 位时间戳，主体即剩下部分
            guard let sep = rest.lastIndex(of: "-") else {
                return (kind, normalize(rest, kind: kind), nil)
            }
            let subject = String(rest[rest.startIndex..<sep])
            let stamp = String(rest[rest.index(after: sep)...])
            return (kind, normalize(subject, kind: kind), compactStampFormatter.date(from: stamp))
        }

        return (.other, stem, nil)
    }

    /// 电话号码可能因为切段而混入空格（`147 4366 2996`），
    /// 直接把纯数字+空白的主体压成连续号码，避免脚本/展示时解析错。
    private static func normalize(_ subject: String, kind: RecordingKind) -> String {
        guard kind == .call else { return subject }
        let trimmed = subject.trimmingCharacters(in: .whitespaces)
        let digits = trimmed.filter { $0.isNumber }
        let nonSpace = trimmed.filter { !$0.isWhitespace }
        if !digits.isEmpty && digits.count == nonSpace.count {
            return digits
        }
        return trimmed
    }
}

// MARK: - 时间解析

private func makeFormatter(_ format: String) -> DateFormatter {
    let f = DateFormatter()
    f.locale = Locale(identifier: "en_US_POSIX")
    f.timeZone = .current
    f.dateFormat = format
    return f
}

/// `20261009-1418`
let dashStampFormatter = makeFormatter("yyyyMMdd-HHmm")
/// `202610110803`
let compactStampFormatter = makeFormatter("yyyyMMddHHmm")

// MARK: - 展示格式

enum Fmt {
    static let dayTime: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "MM-dd HH:mm"
        return f
    }()

    static let clock: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "HH:mm"
        return f
    }()

    static let monthDay: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "MM-dd"
        return f
    }()

    static func relative(_ date: Date, now: Date = Date()) -> String {
        let secs = Int(now.timeIntervalSince(date))
        if secs < 60 { return "刚刚" }
        if secs < 3600 { return "\(secs / 60) 分钟前" }
        if secs < 86400 { return "\(secs / 3600) 小时前" }
        return "\(secs / 86400) 天前"
    }
}
