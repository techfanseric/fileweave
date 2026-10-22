import Foundation

/// 每条远端文件的已知状态，用于跨轮次判断「新增 / 被更新」。
struct LibraryEntry: Codable, Equatable {
    var size: Int64
    var modifiedAt: Date
    var downloadedPath: String?
}

/// 一条已完成的转录结果，用于跨次启动保留「已转录」状态
public struct TranscriptRecord: Codable, Equatable {
    var txtPath: String
    var sentenceCount: Int
    var speakerCount: Int
    var model: String
    var audioDuration: Double
    var inferSeconds: Double
    var createdAt: Date
    /// 本地截取的预览首句（不联网，瞬时）
    var preview: String?
    /// AI 生成的摘要（联网，可关闭）
    var summary: String?
    /// 摘要是否正在生成
    var summarizing: Bool?

    public init(txtPath: String, sentenceCount: Int, speakerCount: Int,
                model: String, audioDuration: Double,
                inferSeconds: Double, createdAt: Date,
                preview: String? = nil, summary: String? = nil,
                summarizing: Bool? = nil) {
        self.txtPath = txtPath
        self.sentenceCount = sentenceCount
        self.speakerCount = speakerCount
        self.model = model
        self.audioDuration = audioDuration
        self.inferSeconds = inferSeconds
        self.createdAt = createdAt
        self.preview = preview
        self.summary = summary
        self.summarizing = summarizing
    }

    /// 行面上展示的第二行内容：优先 AI 摘要，其次本地预览
    var displayLine: String? {
        if let s = summary, !s.isEmpty { return s }
        if let p = preview, !p.isEmpty { return p }
        return nil
    }
}

struct Library: Codable {
    var entries: [String: LibraryEntry] = [:]
    /// remotePath → 转录结果
    var transcripts: [String: TranscriptRecord] = [:]
    /// 「已读到哪一刻」的水位线。录音的通话时间晚于它就算新增。
    ///
    /// 之前这里是逐条账本 `pendingNew: Set<String>`，问题很实际：
    /// 首次运行或换机器时整库都会被算成新增（一次 15 条全是新的），
    /// 删掉又重新出现的录音会被反复标新增，换台 Mac 打开同一批录音
    /// 又全部变新 —— 新增与否完全取决于「这台机器记过什么」，
    /// 而不是「这条录音对用户来说是不是新的」。
    ///
    /// 单一时间戳没有这个问题：它描述的是用户读到哪里了，
    /// 跟用哪台机器、装了几次无关。
    var seenWatermark: Date?

    /// 用户已单独处理过、不再算新增的条目（remotePath）。
    ///
    /// 水位线是「已读到哪一刻」，推它一下会把它之前**所有**新增一并标成已读；
    /// 但转录只处理了其中一条 —— 所以单独记账，从新增判断里剔除，
    /// 不动水位线。只记手机上还在的条目，见 `apply` 里的修剪。
    var individuallySeen: Set<String> = []

    /// 旧账本，**只用于一次性迁移**，迁移完就清空，不再写回有意义的值。
    ///
    /// 键名必须保持 `pendingNew` 不变：换了名字旧库就读不出那批待处理条目，
    /// 而 CodingKeys 是合成的，改属性名等于换键。
    /// 取其中最新的通话时间当水位线 —— 宁可少标几条，
    /// 也不要把整库重新标成新增。
    var pendingNew: Set<String> = []
    var lastScanAt: Date?

    // MARK: 存储位置

    static var directory: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSHomeDirectory() + "/Library/Application Support")
        return base.appendingPathComponent("FileWeave", isDirectory: true)
    }

    static var fileURL: URL {
        directory.appendingPathComponent("library.json")
    }

    /// 从荣耀录音监视器时代的旧目录整体搬家。
    ///
    /// 搬 library.json / transcripts / asr / bailian.env —— 新目录里已存在的
    /// 条目不覆盖（不做合并，避免两份账本来回覆盖）。某条搬不动时只记日志、
    /// 不写完成标记，下次启动还会重试；全部搬完才立 `.migration-complete`，
    /// 之后旧目录剩下的内容一概不再看。任何失败都不中断启动：
    /// 丢一条迁移好过硬生生起不来。
    static func migrateLegacyStoreIfNeeded() {
        let fm = FileManager.default
        let legacy = directory.deletingLastPathComponent()
            .appendingPathComponent("HonorRecorderBar", isDirectory: true)
        let marker = directory.appendingPathComponent(".migration-complete")
        guard !fm.fileExists(atPath: marker.path),
              fm.fileExists(atPath: legacy.path) else { return }
        do {
            try fm.createDirectory(at: directory, withIntermediateDirectories: true)
            NSLog("迁移旧数据目录：%@ → %@", legacy.path, directory.path)
            var pending = false
            for name in ["library.json", "transcripts", "asr", "bailian.env"] {
                let from = legacy.appendingPathComponent(name)
                let to = directory.appendingPathComponent(name)
                guard fm.fileExists(atPath: from.path),
                      !fm.fileExists(atPath: to.path) else { continue }
                do {
                    try fm.moveItem(at: from, to: to)
                } catch {
                    pending = true
                    NSLog("迁移 %@ 失败（下次启动重试）：%@",
                          name, String(describing: error))
                }
            }
            if !pending {
                fm.createFile(atPath: marker.path, contents: nil)
            }
        } catch {
            NSLog("创建新数据目录失败，跳过迁移：%@", String(describing: error))
        }
    }

    static func load() -> Library {
        guard let data = try? Data(contentsOf: fileURL) else { return Library() }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        do {
            return try decoder.decode(Library.self, from: data)
        } catch {
            // 以前这里是静默回退空库。结果加一个新字段就让整份库作废，
            // 新增标记/转录记录/已下载状态每次重启全丢，而且完全查不出来。
            NSLog("library.json 解析失败，已回退为空库：%@", String(describing: error))
            return Library()
        }
    }

    /// 自定义 init(from:) 会顶掉合成的成员初始化器，空库要自己留着
    init() {}

    // Swift 合成的 Codable 遇到缺失的键会抛错，声明时的默认值根本不生效。
    // 库文件要跨版本持久化，加字段不该让旧文件整份作废，所以自己解。
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        func value<T: Decodable>(_ key: CodingKeys) -> T? {
            (try? c.decodeIfPresent(T.self, forKey: key)) as? T
        }
        entries = value(.entries) ?? [:]
        transcripts = value(.transcripts) ?? [:]
        seenWatermark = value(.seenWatermark)
        individuallySeen = value(.individuallySeen) ?? []
        pendingNew = value(.pendingNew) ?? []
        lastScanAt = value(.lastScanAt)
        // seededAt / baselineCount 曾用于「首启建基线」，水位线模型接管后
        // 已经没有读取方了。旧库里还留着这两个键，解不出来也无妨 ——
        // Decodable 会忽略未知键，正好让它们自然退役。
    }

    func save() {
        try? FileManager.default.createDirectory(at: Library.directory,
                                                 withIntermediateDirectories: true)
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        guard let data = try? encoder.encode(self) else { return }
        try? data.write(to: Library.fileURL, options: .atomic)
    }

    // MARK: 差分

    struct Diff {
        var added: [Recording] = []
        var updated: [Recording] = []
        var removed: [String] = []
    }

    /// 这条录音现在算不算「新增」：晚于水位线、且没被单独处理过。
    ///
    /// 水位线和单独已读是两套并行的语义：前者管「读到哪一刻」，
    /// 后者管「这一条我已经动手处理过了」。
    func isNew(_ rec: Recording) -> Bool {
        guard let mark = seenWatermark else { return false }
        return !rec.isTrashed && rec.displayTime > mark
            && !individuallySeen.contains(rec.remotePath)
    }

    /// 当前该点亮红点的条目。
    ///
    /// 从水位线现算，而不是只取「本轮变了的」——
    /// 一条录音在用户点「已读」之前，每次扫描都还应该是新的。
    /// 取 diff 的话刷新一次红点就消失了，看着像 bug。
    func newlySeen(in current: [Recording]) -> [Recording] {
        current.filter { isNew($0) }
    }

    /// 与本轮扫描结果做差分，并把本轮结果写入库。
    ///
    /// 首次启动时只建基线：存量文件不算「新增」，避免一上来就点亮 20 个红点。
    ///
    /// - Parameter current: 本轮在手机上扫到的全部条目（正常位置 + 回收站）
    /// - Parameter trash: 回收站里的那些。它们的条目要留着，
    ///   否则删掉再还原之后「已下载」和转录记录会对不上号；
    ///   但它们一律不算新增，不该点亮红点。
    mutating func apply(_ current: [Recording], at now: Date = Date()) -> Diff {
        var diff = Diff()
        let known = entries

        // 水位线初值：老库折算，或者首轮就把当时已有的全部当成已读。
        // 首轮不该满屏「新增」—— 那些是存量，不是刚刚冒出来的。
        if seenWatermark == nil {
            seenWatermark = pendingNew.isEmpty
                ? nil
                : current.filter { pendingNew.contains($0.remotePath) }
                    .map(\.displayTime).max()
        }
        let seeding = seenWatermark == nil

        for rec in current where !rec.isTrashed {
            if let old = known[rec.remotePath] {
                if old.size != rec.size || old.modifiedAt != rec.modifiedAt {
                    diff.updated.append(rec)
                }
            } else {
                diff.added.append(rec)
            }
        }

        // 回收站里的文件不算「离开了手机」，它只是换了个位置
        let liveIDs = Set(current.filter { !$0.isTrashed }.map(\.remotePath))
        diff.removed = known.keys.filter { !liveIDs.contains($0) }.sorted()

        // 写回本轮快照，保留已下载路径记录
        var next: [String: LibraryEntry] = [:]
        for rec in current {
            next[rec.remotePath] = LibraryEntry(
                size: rec.size,
                modifiedAt: rec.modifiedAt,
                downloadedPath: known[rec.remotePath]?.downloadedPath
            )
        }
        entries = next

        // 首轮：把水位线定在「此刻」，存量从此不算新增
        if seeding {
            seenWatermark = now
        }
        pendingNew = []

        // 已读记账只对手机上还在的条目有意义：
        // 文件删了，这条记录留着只会让集合无界地长。
        // 回收站里的也算「还在」—— 还原之后它得继续是已读。
        individuallySeen = individuallySeen.filter { path in
            current.contains(where: { $0.remotePath == path })
        }

        lastScanAt = now
        save()
        return diff
    }

    mutating func markDownloaded(_ remotePath: String, localPath: String) {
        entries[remotePath]?.downloadedPath = localPath

        save()
    }

    mutating func markAllSeen() {
        seenWatermark = Date()
        save()
    }

    /// 单独把一条标成已读，**不动水位线** ——
    /// 推水位线会把它之前所有新增一并标成已读，那是「全部已读」的语义。
    mutating func markIndividuallySeen(_ remotePath: String) {
        guard individuallySeen.insert(remotePath).inserted else { return }
        save()
    }

    mutating func saveTranscript(_ remotePath: String, record: TranscriptRecord) {
        transcripts[remotePath] = record
        save()
    }

    mutating func updateTranscript(_ remotePath: String, _ mutate: (inout TranscriptRecord) -> Void) {
        guard var r = transcripts[remotePath] else { return }
        mutate(&r)
        transcripts[remotePath] = r
        save()
    }

    public func transcript(for remotePath: String) -> TranscriptRecord? {
        transcripts[remotePath]
    }

    mutating func removeTranscript(_ remotePath: String) {
        transcripts.removeValue(forKey: remotePath)
        save()
    }

    /// 清掉文件已被删除的转录记录。
    /// 否则底栏的「打开转录」会指向不存在的路径，点下去毫无反应。
    mutating func pruneMissingTranscripts() -> Int {
        let before = transcripts.count
        transcripts = transcripts.filter { FileManager.default.fileExists(atPath: $0.value.txtPath) }
        if transcripts.count != before { save() }
        return before - transcripts.count
    }

    func downloadedPath(for remotePath: String) -> String? {
        entries[remotePath]?.downloadedPath
    }

    // MARK: 索引重建

    /// 侧车 json 里那几项统计，补索引时要还原回去
    struct SidecarMeta {
        var sentenceCount: Int?
        var speakerCount: Int?
        var model: String?
        var duration: Double?
        var totalSeconds: Double?
    }

    private static func sidecarMeta(at url: URL) -> SidecarMeta? {
        guard let data = try? Data(contentsOf: url),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }
        return SidecarMeta(
            sentenceCount: obj["sentenceCount"] as? Int,
            speakerCount: obj["speakerCount"] as? Int,
            model: obj["model"] as? String,
            duration: obj["duration"] as? Double,
            totalSeconds: obj["totalSeconds"] as? Double)
    }

    /// 把「文件还在、索引没了」的转录补回库。返回补回的文件名。
    ///
    /// **为什么必须有它**：转录索引只有一个写入口 —— 转录当场完成时
    /// `saveTranscript`。一旦索引丢了条目，那份转录就**永久失联**：
    /// txt 还在盘上，界面里却查不到、搜不到、也点不开，
    /// 而且没有任何机制会把它找回来。
    ///
    /// 实测踩过：库里只剩 1 条，盘上有 17 份转录。
    /// 不重建的话，用户看到的就是「文件夹里一堆 transcript，App 却说没有」。
    ///
    /// **必须排在 `pruneMissingTranscripts()` 之后跑**：
    /// prune 误删的记录这里立刻补回来；而补回来的记录 txt 确实存在，
    /// 下一轮 prune 就不会再删它 —— 两者合起来才闭环。
    ///
    /// - Parameter knownRemotePaths: 能对上的录音全路径。
    ///   启动时传 `entries` 的键，每轮扫描后再传当前扫到的列表，
    ///   这样「转录先于扫描发生」的那批也能补上。
    @discardableResult
    mutating func rebuildTranscriptIndex(knownRemotePaths: [String]) -> [String] {
        let dir = Library.directory.appendingPathComponent("transcripts", isDirectory: true)
        guard let names = try? FileManager.default.contentsOfDirectory(atPath: dir.path) else {
            return []
        }

        // 按「去掉扩展名的文件名」匹配，不写死 .m4a：
        // 录音的 stem 就是 txt 的 stem（txt 名由录音名去扩展名而来），
        // 比扩展名更不容易对不上。
        //
        // 同名只留第一个：真出现两台设备各有一条同名录音时，
        // 宁可漏补，也不能把转录指到另一台机器的录音上。
        var byStem: [String: String] = [:]
        for rp in knownRemotePaths {
            let name = (rp as NSString).lastPathComponent as NSString
            let stem = name.deletingPathExtension
            if byStem[stem] == nil { byStem[stem] = rp }
        }

        var recovered: [String] = []
        for name in names.sorted() where name.hasSuffix(".txt") {
            let stem = (name as NSString).deletingPathExtension
            guard let remotePath = byStem[stem] else { continue }
            guard transcripts[remotePath] == nil else { continue }

            let txtURL = dir.appendingPathComponent(name)
            guard let text = try? String(contentsOf: txtURL, encoding: .utf8) else { continue }
            guard let doc = TranscriptDocument.parse(remotePath: remotePath,
                                                     title: stem, txtURL: txtURL) else {
                continue
            }

            let meta = Self.sidecarMeta(at: dir.appendingPathComponent("\(stem).json"))
            let modified = (try? FileManager.default
                .attributesOfItem(atPath: txtURL.path)[.modificationDate]) as? Date

            transcripts[remotePath] = TranscriptRecord(
                txtPath: txtURL.path,
                // 侧车是转录当时的原始统计，比从 txt 重新数更准
                sentenceCount: meta?.sentenceCount ?? doc.segments.count,
                speakerCount: meta?.speakerCount
                    ?? Set(doc.segments.compactMap(\.speaker)).count,
                model: meta?.model ?? doc.model,
                audioDuration: meta?.duration ?? 0,
                inferSeconds: meta?.totalSeconds ?? 0,
                // 用文件修改时间当转录时间：这是那份转录真正产出的时刻，
                // 比「App 什么时候发现它」更贴近用户预期
                createdAt: modified ?? Date(),
                preview: SummaryService.localPreview(from: text))
            recovered.append(name)
        }

        if !recovered.isEmpty {
            save()
            NSLog("已从磁盘补回 %d 份转录索引：%@",
                  recovered.count, recovered.joined(separator: ","))
        }
        return recovered
    }
}
