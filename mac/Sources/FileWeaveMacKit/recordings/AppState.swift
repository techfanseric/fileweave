import Foundation
import AppKit
import Network

public enum LinkState: Equatable {
    case checking
    case ready(SourceInfo)
    case reconnecting
    case noDevice
    case failed(String)

    public var isReady: Bool { if case .ready = self { return true }; return false }

    public var title: String {
        switch self {
        case .checking: return "正在检查…"
        case .ready(let s): return s.name
        case .reconnecting: return "正在重新连接…"
        case .noDevice: return "手机未连接"
        case .failed(let m): return m
        }
    }

    /// 没能连上时，给用户一句能照着做的话。
    /// 只说「手机未连接」没有用，他不知道该动哪一边。
    public var hint: String? {
        switch self {
        case .noDevice:
            return "手机和这台 Mac 连同一网络，在手机 FileWeave 里与 Mac 配对，并打开共享文件夹"
        case .reconnecting:
            // 之前这一档返回 nil，于是那八九秒里界面只有一行标题，
            // 既没说在等什么、也没说等不到该怎么办。
            return "正在重新找配对的手机——找到了会自己连上"
        default: return nil
        }
    }
}

/// 当前正在用的通道。菜单栏只显示 `name`，诊断信息在 `detail`。
public struct SourceInfo: Equatable {
    public let name: String
    public let detail: String
}

enum TranscriptionPhase {
    case fetching       // 从手机取音频
    case converting     // 转码
    case transcribing   // 识别中
}

public enum GroupMode: String, CaseIterable, Identifiable {
    case time, number, contact
    public var id: String { rawValue }
    var label: String {
        switch self {
        case .time: return "按时间"
        case .number: return "按号码"
        case .contact: return "按对象"
        }
    }
}

public enum Purpose {
    case download
    case transcribe

    var title: String {
        switch self {
        case .download: return "选择保存位置"
        case .transcribe: return "转录结果保存位置"
        }
    }

    var prompt: String {
        switch self {
        case .download: return "下载到此处"
        case .transcribe: return "转录到此处"
        }
    }

    func message(count: Int) -> String? {
        // 原生文件夹选择框不需要自我解释，不加说明文字
        switch self {
        case .download: return nil
        case .transcribe: return nil
        }
    }
}

@MainActor
public final class AppState: ObservableObject {

    // MARK: 对外状态

    @Published public private(set) var link: LinkState = .checking
    @Published public private(set) var recordings: [Recording] = []

    /// 手机回收站里的录音。每轮扫描现读现用，本机不留副本，
    /// 所以在手机上还原 / 清空之后这里一定跟着变，不会出现假状态。
    @Published public private(set) var trashRecordings: [Recording] = []

    @Published public private(set) var lastScanAt: Date?
    @Published public private(set) var scanning = false
    @Published public private(set) var errorText: String?
    @Published public private(set) var infoText: String?

    /// 面板每次打开时 +1，列表据此回到顶部。
    /// popover 的内容视图只创建一次，SwiftUI 状态跨次保留，
    /// 不显式复位的话第二次打开会停在上次滚到的位置。
    @Published public private(set) var panelOpenToken = 0

    /// remotePath → 下载中
    @Published private(set) var downloading: Set<String> = []

    /// remotePath → 正在删除
    @Published private(set) var trashing: Set<String> = []

    /// remotePath → 正在从手机回收站还原
    @Published private(set) var restoring: Set<String> = []

    /// remotePath → 正在转录
    @Published private(set) var transcribing: Set<String> = []

    /// remotePath → 进度 0…1（估算值，实时推断会有跳动）
    @Published private(set) var transcriptProgress: [String: Double] = [:]

    /// remotePath → 当前阶段，用于底栏显示「正在做什么」
    @Published private(set) var phases: [String: TranscriptionPhase] = [:]

    /// 已完成的转录结果
    @Published private(set) var transcripts: [String: TranscriptRecord] = [:]

    /// 转录环境是否就绪
    public var asrAvailable: Bool { ASR.isInstalled }
    public var asrUnavailableReason: String? { ASR.unavailableReason }

    /// 多选：已勾选的 remotePath
    @Published public var selection: Set<String> = []

    /// 正在弹出目录选择框（避免重复弹窗）
    @Published private(set) var pickingDirectory = false

    // MARK: 设置

    @Published public var pollInterval: Double {
        didSet { UserDefaults.standard.set(pollInterval, forKey: Keys.interval) }
    }

    private enum Keys {
        static let interval = "pollInterval"
        static let lastDir = "lastPickedDir"
        static let transcriptDir = "transcriptFolder"
        static let aiSummary = "aiSummaryEnabled"
    }

    /// 上一次选择的目录，只用来给选择框当起始位置。
    /// 记住路径不等于创建目录 —— 绝不因为「上次选过」就把文件夹建出来。
    var lastPickedDir: URL? {
        didSet { UserDefaults.standard.set(lastPickedDir?.path, forKey: Keys.lastDir) }
    }

    // MARK: 内部

    private var library = Library()
    private var pollTask: Task<Void, Never>?

    /// 上次尝试 Bonjour 重连的时间，配合退避序列限速。
    private var lastReconnectAt: Date?

    /// 连续重连失败的次数，驱动退避：见 `reconnectCooldown(afterConsecutiveFailures:)`。
    ///
    /// 手机不在（共享文件夹关了、不在同一网）时，若每轮轮询都全力跑
    /// Bonjour 发现 + 逐台探测 —— 一轮要好几秒的网络广播和 TLS 握手 ——
    /// 连不上就该越试越省，但人一到场（面板打开/点刷新）必须立刻回满血。
    private var reconnectFailures = 0

    /// 连续失败 n 次后，下一次重连前至少等多少秒。纯函数，单测锁行为。
    ///
    /// 第 0 次失败 = 刚断线，立刻试；之后逐级松劲，60 秒封顶 ——
    /// 再往上松没有意义：面板关闭时的轮询间隔（默认 30 秒）本身就会带着重试走，
    /// 冷却再长也只是让每一轮轮询都白跑一趟发现。
    nonisolated static func reconnectCooldown(afterConsecutiveFailures failures: Int) -> TimeInterval {
        switch failures {
        case 0: return 0
        case 1: return 5
        case 2: return 10
        case 3: return 20
        case 4: return 40
        default: return 60
        }
    }

    /// 没连上时给用户的那句话。空着表示没什么可指引的。
    ///
    /// 这句必须真的被视图读到（disconnectedState 在用），
    /// 不能只写在状态枚举里没人渲染 —— 之前就犯过这个错，
    /// 正好在最需要指引的时候界面什么都不说。
    public var linkHint: String? { link.hint }

    /// 当前在用的通道。每轮先验它还活着，死了才重新发现，
    /// 免得每一轮都重新广播探测，「这一轮是谁扫的」始终确定。
    private var source: RecordingSource?

    public var newCount: Int { library.newlySeen(in: recordings).count }

    public func isNew(_ rec: Recording) -> Bool { library.isNew(rec) }

public func notifyPanelOpened() { panelOpenToken &+= 1 }

    /// 必须确认文件真的还在，删了就别再显示「已下载」
    public func isDownloaded(_ rec: Recording) -> Bool {
        guard let p = library.downloadedPath(for: rec.id) else { return false }
        return FileManager.default.fileExists(atPath: p)
    }

    public func isSelected(_ rec: Recording) -> Bool { selection.contains(rec.id) }

    public var selectedRecordings: [Recording] {
        // 正常列表和回收站都要算：勾选之后切了视图，勾选不该凭空失效
        (recordings + trashRecordings).filter { selection.contains($0.id) }
    }

    /// 切换正常列表 / 回收站。清掉勾选，避免底栏对着一屏看不见的条目动手。
    public func setTrashView(_ show: Bool) {
        guard show != showTrashOnly else { return }
        showTrashOnly = show
        selection.removeAll()
    }

    public var selectedSize: Int64 {
        selectedRecordings.reduce(0) { $0 + $1.size }
    }

    // MARK: 生命周期

    public init() {
        let saved = UserDefaults.standard.double(forKey: Keys.interval)
        pollInterval = saved > 0 ? saved : 30

        let savedAI = UserDefaults.standard.object(forKey: Keys.aiSummary) as? Bool
        aiSummaryEnabled = savedAI ?? true

        if let p = UserDefaults.standard.string(forKey: Keys.lastDir) {
            lastPickedDir = URL(fileURLWithPath: p)
        }

        // 荣耀录音监视器时代的库 / 转录 / ASR 环境整体搬到 FileWeave 目录。
        // 必须赶在 Library.load() 之前。
        Library.migrateLegacyStoreIfNeeded()

        library = Library.load()
        transcripts = library.transcripts
        transcriptText = Self.loadTranscriptText(from: library)
        searchHistory = loadHistory()
        NSLog("索引诊断: 库内转录 %d 条，可索引正文 %d 份（目录 %@）",
              library.transcripts.count, transcriptText.count,
              Library.directory.appendingPathComponent("transcripts").path)
        // 清掉指向已删除文件的陈旧记录
        let pruned = library.pruneMissingTranscripts()
        if pruned > 0 {
            transcripts = library.transcripts
            NSLog("已清理 %d 条失效的转录记录", pruned)
        }
        // 紧接着补回「文件还在、索引没了」的。
        // 必须排在 prune 之后：prune 误删的这里立刻补回来，
        // 而补回来的记录 txt 确实存在，下一轮 prune 便不会再删 —— 闭环。
        applyRebuiltTranscripts()
        // 恢复最近一次仍在的转录结果入口
        if let newest = library.transcripts.values
            .filter({ FileManager.default.fileExists(atPath: $0.txtPath) })
            .max(by: { $0.createdAt < $1.createdAt }) {
            lastTranscriptURL = URL(fileURLWithPath: newest.txtPath)
        }

        // 不在启动时创建任何本地目录。
        // 下载目录只在用户点下载、当场选好之后，才由下载实现创建。
        // 菜单栏应用本身只读手机，不往本机写任何录音数据。

        // 菜单栏常驻，轮询不依赖菜单是否被打开。
        // 配对记录的首次读取是几百毫秒级的文件活，放后台，别占启动主线程。
        Task.detached(priority: .utility) { _ = PairedDeviceStore.shared.devices() }
        start()
    }

    public func start() {
        installEnvironmentWatchers()
        pollTask?.cancel()
        pollTask = Task { [weak self] in
            guard let self else { return }
            while !Task.isCancelled {
                await self.refresh()
                try? await Task.sleep(
                    nanoseconds: UInt64(self.effectiveInterval * 1_000_000_000))
            }
        }
    }

    /// 面板开着的时候人在盯着屏幕，这时的轮询必须明显更密。
    /// 面板关闭后退回用户设定的间隔，别白烧电和 Bonjour 发现。
    public var isPanelVisible = false {
        didSet {
            guard isPanelVisible != oldValue else { return }
            if isPanelVisible {
                // 面板打开 = 人在看。退避清零，下面 wakePolling 重启的循环
                // 第一轮立即全力连 —— 别让用户对着「未连接」，
                // 等的却是他打开面板之前某次失败留下的冷却。
                resetReconnectBackoff()
            }
            wakePolling()
        }
    }

    private var effectiveInterval: Double {
        isPanelVisible ? min(6, pollInterval) : pollInterval
    }

    /// 立刻按新间隔重启轮询循环，避免还在睡旧的长间隔
    private func wakePolling() {
        pollTask?.cancel()
        pollTask = nil
        start()
    }

    public func stop() {
        pollTask?.cancel()
        pollTask = nil
    }

    // MARK: 环境预判

    /// 用户还没动作、但连接条件刚变好的时刻，必须主动跟上，而不是等下一轮轮询：
    ///
    /// - **网络恢复**（Wi-Fi 重连、插上网线、切回覆盖的网段）：
    ///   手机多半也刚换了地址，这正是重新发现该出手的瞬间。
    /// - **Mac 从睡眠醒来**：TCP 会话全死了、网络要几秒才通，
    ///   等一等再扫，否则只会白报一轮「未连接」、还把失败计进退避。
    ///
    /// 两个 watch 都是一次回调配一次去抖刷新，稳态下零开销。
    private var pathMonitor: NWPathMonitor?
    private var lastPathSatisfied = true
    private var wakeObserver: NSObjectProtocol?
    private var settleTask: Task<Void, Never>?

    private func installEnvironmentWatchers() {
        // start() 会被 wakePolling 反复调用来重置轮询节奏，watch 只装一次
        guard pathMonitor == nil else { return }

        let monitor = NWPathMonitor()
        monitor.pathUpdateHandler = { [weak self] path in
            let satisfied = path.status == .satisfied
            Task { @MainActor in
                guard let self else { return }
                let was = self.lastPathSatisfied
                self.lastPathSatisfied = satisfied
                // 只在「恢复」那一下动作；首次回调（本来就是通的）与持续在线都跳过
                guard satisfied, !was else { return }
                NSLog("网络路径恢复，稍候自动重扫")
                self.settleThenRefresh(after: 2.0)
            }
        }
        monitor.start(queue: DispatchQueue(label: "network.path.monitor"))
        pathMonitor = monitor

        wakeObserver = NSWorkspace.shared.notificationCenter.addObserver(
            forName: NSWorkspace.didWakeNotification, object: nil, queue: .main
        ) { [weak self] _ in
            self?.settleThenRefresh(after: 3.0)
        }
    }

    /// 去抖的「稍等片刻再扫」。唤醒和网络恢复常常接踵而至，
    /// 连续事件只保留最后一次；等待本身就是给网络收敛留的时间。
    /// 退避清零放在**延迟之后**而不是事件当下：中间若插进一轮轮询
    /// 且恰好失败，别让新攒下的冷却把这次该出手的一扫挡回去。
    private func settleThenRefresh(after seconds: TimeInterval) {
        settleTask?.cancel()
        settleTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
            guard !Task.isCancelled else { return }
            self?.resetReconnectBackoff()
            await self?.refresh()
        }
    }

    // MARK: 通道解析

    /// 拿一条真正可用的通道，拿不到就先 Bonjour 重新发现再试一次。
    ///
    /// 只有广播 fp 和某条配对记录的 folderFp 对上号才会连 ——
    /// 广播谁都能发，配对记录才是用户亲手批准过的信任。
    ///
    /// 覆盖的失效面：手机换了 IP / 端口、共享文件夹重开、Mac 换了网段，
    /// 全部靠重新广播发现解决，**全程不需要碰手机**。
    private func resolveSource() async throws -> RecordingSource {
        // 探测已有通道是否还活着，避免每轮都重新发现一遍。
        if let current = source, await sourceStillWorks(current) {
            return current
        }

        // 退避期内不重复广播探测；没连上就如实抛出去，让界面显示未连接。
        // 清退避的入口见 resetReconnectBackoff() —— 人到场或环境好转时不等。
        let cooldown = Self.reconnectCooldown(afterConsecutiveFailures: reconnectFailures)
        if let last = lastReconnectAt,
           Date().timeIntervalSince(last) < cooldown {
            throw SourceError.noSource
        }
        lastReconnectAt = Date()
        link = .reconnecting

        // 丢到后台线程：发现 + 逐台 TLS 探测都是秒级网络活，
        // 放主线程上会把菜单栏面板卡住不动。
        let found = await Task.detached(priority: .utility) {
            FileWeaveFolderSource.discoverPaired()
        }.value

        if let made = found {
            // 连着呢，过去的失败一笔勾销 —— 重开共享文件夹、换网段都算新的开始
            reconnectFailures = 0
            source = made
            NSLog("已连上配对设备：%@（%@）", made.identity, made.detail)
            return made
        }
        reconnectFailures += 1
        throw SourceError.noSource
    }

    /// 探测已有通道是否还活着：一次最轻的 /v1/status，不做全盘扫描。
    private func sourceStillWorks(_ s: RecordingSource) async -> Bool {
        await Task.detached(priority: .utility) { () -> Bool in
            s.probe()
        }.value
    }

    /// 提示语设下的时刻。轮询成功**不该立刻抹掉**用户还没读完的话 ——
    /// 面板开着时 6 秒一轮，无条件 errorText = nil 会让任何提示活不过一轮。
    private var messageAt: Date?
    private static let messageTTL: TimeInterval = 12

    /// 轮询成功时清掉过期提示。只清过期的，活着的那句留着给用户读完。
    private func expireStaleMessage() {
        guard let at = messageAt, Date().timeIntervalSince(at) >= Self.messageTTL else { return }
        errorText = nil
        infoText = nil
        messageAt = nil
    }

    /// 统一出口，保证「设提示」和「记时刻」不会走散。
    ///
    /// **底栏任何提示都必须走这里。** `errorText` 的可清性靠 `messageAt`
    /// 记时刻，成功轮询只清「过了期的」—— 绕过这个出口直接给 `errorText`
    /// 赋值，`messageAt` 就是 nil，`expireStaleMessage` 永远清不掉它，
    /// 提示会卡死在底栏（实测：连接恢复之后错误还挂着，就是这个原因）。
    private func note(_ message: String?) {
        errorText = message
        messageAt = message == nil ? nil : Date()
    }

    /// 给面板外的动作（登录项开关之类）往底部提示栏发一句话。
    /// 走同一条出口，提示才会一样会过期。
    public func notify(_ message: String?) { note(message) }

    // MARK: 刷新

    /// 「现在就要」的用户信号到达（打开面板、点刷新、网络恢复、Mac 唤醒）：
    /// 退避和冷却全部清零，下一轮 refresh 立刻全力连。
    ///
    /// 「连不上要越试越省」是对无人值守的后台说的；人就在跟前、
    /// 或连接条件刚刚变好时，慢半拍才是错的 —— 过去的失败不该由现在买单。
    public func resetReconnectBackoff() {
        reconnectFailures = 0
        lastReconnectAt = nil
    }

    /// 用户亲手触发的刷新（面板按钮、右键菜单、快捷键）。
    /// 与轮询走的 `refresh()` 分开：这一脚要先清退避，
    /// 否则刚失败过一次时点了刷新，还得干等冷却走完才有动作。
    public func userRefresh() async {
        resetReconnectBackoff()
        await refresh()
    }

    public func refresh() async {
        guard !scanning else { return }
        scanning = true
        defer { scanning = false }

        do {
            let current = try await resolveSource()
            link = .ready(SourceInfo(name: current.displayName, detail: current.detail))

            // 一轮扫描同时拿在位和回收站（/v1/tree 一次返回全部）。
            // 扫描失败时上面已经整轮抛错、界面沿用上一轮，不存在「回收站这一半失败」的中间态。
            // 扫描内部是同步网络调用（手机要 SAF 逐级下钻，秒级），
            // 必须扔出主线程 —— 否则开面板的第一轮刷新会把面板冻到扫完为止。
            let snap = try await Task.detached(priority: .utility) {
                try current.scan()
            }.value
            // 这轮扫到的录音里，也许有「txt 在、索引没了」的。
            // 扫完再补一次，才能覆盖「转录发生在录音入库之前」那种顺序。
            // 目录已经全对上时它扫一遍就返回，几乎不花开销。
            applyRebuiltTranscripts(liveRemotePaths: snap.live.map(\.remotePath))
            let live = snap.live
            let livePaths = Set(live.map(\.remotePath))
            // 原位置已经又有同名文件，说明手机上现在是两份。
            // 列表只留还在位的那份，避免同一个 id 出现两次。
            let trash = snap.trash.filter { !livePaths.contains($0.remotePath) }

            // 上一轮记着在回收站、这轮已经不在了、并且回到了原位置
            // → 用户在手机上点了还原，界面要如实说一声，不能装作没发生过。
            let previousTrashPaths = Set(trashRecordings.map(\.remotePath))
            let backHome = previousTrashPaths
                .subtracting(trash.map(\.remotePath))
                .intersection(live.map(\.remotePath))

            let diff = library.apply(live + trash)

            recordings = live.sorted {
                if $0.modifiedAt != $1.modifiedAt { return $0.modifiedAt > $1.modifiedAt }
                return $0.id < $1.id
            }
            trashRecordings = trash.sorted { ($0.movedAt ?? .distantPast) > ($1.movedAt ?? .distantPast) }
            lastScanAt = Date()
            expireStaleMessage()

            // 每轮一行，方便对着手机现场核对到底是哪边不同步
            NSLog("SYNC 正常 %d 条 · 手机回收站 %d 条",
                  recordings.count, trashRecordings.count)

            // 扫描默认**不出声**。
            //
            // 这一行是「刚刚发生了一件事」的提示位，不是扫描报告。
            // 原来在这里报「已建立基线 N 条」「新增 N 条」「更新 N 条」——
            // 三条都是内部记账：基线是首启的一次性动作、新增在 footer 有角标、
            // 更新是 size/mtime 变了，用户对这些都做不了任何事。
            // 面板开着时 6 秒刷一轮，于是这行每隔几秒就换一次内容，纯粹噪音。
            //
            // 只留一种：用户在手机上把回收站里的东西还原了。
            // 那是用户在 App 之外做的动作，界面得跟上，不然他会以为删错了。
            infoText = backHome.isEmpty ? nil
                : (backHome.count == 1 ? "手机上还原了 1 条"
                                       : "手机上还原了 \(backHome.count) 条")
        } catch let e as SourceError {
            if case .noSource = e {
                link = .noDevice
            } else {
                link = .failed("连接异常")
                note(e.localizedDescription)
            }
            infoText = nil
        } catch {
            link = .failed("未知错误")
            note(error.localizedDescription)
            infoText = nil
        }
    }

    // MARK: 多选

    public func toggleSelection(_ rec: Recording) {
        if selection.contains(rec.id) {
            selection.remove(rec.id)
        } else {
            selection.insert(rec.id)
        }
    }

    public func select(_ rec: Recording) { selection.insert(rec.id) }

    public func deselect(_ rec: Recording) { selection.remove(rec.id) }

    public func selectAllNew() {
        for rec in recordings where library.isNew(rec) {
            selection.insert(rec.id)
        }
    }

    public func selectAll() {
        selection = Set(visibleRecordings.map(\.id))
    }

    public func clearSelection() { selection.removeAll() }

    /// 在当前筛选结果范围内反选
    public func invertSelection() {
        selection.formSymmetricDifference(Set(visibleRecordings.map(\.id)))
    }

    /// 还没转录过的条目
    public var untranscribed: [Recording] {
        (showTrashOnly ? trashRecordings : recordings)
            .filter { transcript(for: $0) == nil }
    }

    /// 一键转录全部尚未转录的条目（逐条后台跑）
    public func transcribeAllRemaining() async {
        guard ASR.isInstalled else {
            note(ASR.unavailableReason)
            return
        }
        let targets = untranscribed
        guard !targets.isEmpty else {
            infoText = "全部都已转录过"
            return
        }
        for rec in targets {
            await transcribe(rec)
        }
    }

    /// 下载当前筛选出来的全部条目到同一个目录
    public func downloadAllVisible() async {
        let targets = filteredRecordings
        guard !targets.isEmpty else { return }
        guard let dir = pickDownloadDirectory(count: targets.count) else { return }
        await download(recs: targets, into: dir)
    }

    public func clearNewFlags() {
        library.markAllSeen()
        objectWillChange.send()
    }

    // MARK: 目录选择

    /// 每次下载/转录都问一次要存到哪。取消返回 nil，什么都不会发生。
    /// - Parameter purpose: 说明这个目录是用来干嘛的，避免弹窗看起来像「下载」
    public func pickDownloadDirectory(count: Int, purpose: Purpose = .download) -> URL? {
        guard !pickingDirectory else { return nil }
        pickingDirectory = true
        defer { pickingDirectory = false }

        let panel = NSOpenPanel()
        panel.canChooseDirectories = true
        panel.canChooseFiles = false
        panel.canCreateDirectories = true
        panel.allowsMultipleSelection = false
        panel.prompt = purpose.prompt
        panel.title = purpose.title
        // 刻意不写 message：原生选择框不解释自己，长说明反而让人以为出了错
        if let msg = purpose.message(count: count) {
            panel.message = msg
        }

        // 起始位置用「上次选过且确实还存在」的目录，否则用下载文件夹
        if let last = lastPickedDir, FileManager.default.fileExists(atPath: last.path) {
            panel.directoryURL = last
        } else {
            panel.directoryURL = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first
        }

        guard panel.runModal() == .OK, let url = panel.url else { return nil }
        lastPickedDir = url
        return url
    }

    // MARK: 下载

    public func destination(for rec: Recording, in directory: URL) -> URL {
        directory.appendingPathComponent(rec.relativePath)
    }

    /// 已下载文件的本地位置
    public func libraryPath(for rec: Recording) -> URL? {
        guard let p = library.downloadedPath(for: rec.id) else { return nil }
        return URL(fileURLWithPath: p)
    }

    /// 单条下载：先问目录，再拉取。
    public func downloadOne(_ rec: Recording) async {
        guard !downloading.contains(rec.id) else { return }
        guard let dir = pickDownloadDirectory(count: 1) else { return }
        await download(recs: [rec], into: dir)
    }

    /// 批量下载：目录已经由调用方选定。
    public func download(recs: [Recording], into directory: URL) async {
        guard !recs.isEmpty else { return }
        let pending = recs.filter { !downloading.contains($0.id) }
        guard !pending.isEmpty else { return }

        // 全部标记为下载中，完成一个撤一个
        for r in pending { downloading.insert(r.id) }

        do {
            let channel = try await resolveSource()
            var failures = 0

            for rec in pending {
                do {
                    let dest = destination(for: rec, in: directory)
                    // 拉文件是长 HTTP（超时给到 300 秒），一条 9MB 的录音
                    // 就够把面板冻几秒 —— 扔后台，进度标记照常走。
                    let pulled = try await Task.detached(priority: .userInitiated) {
                        try channel.download(rec, to: dest)
                    }.value
                    library.markDownloaded(rec.id, localPath: pulled.path)
                } catch {
                    failures += 1
                    note("「\(rec.subject)」下载失败：\(error.localizedDescription)")
                }
                downloading.remove(rec.id)
                selection.remove(rec.id)
                objectWillChange.send()
            }

            if failures == 0 {
                note(nil)
                infoText = "已保存 \(pending.count) 个到 \(directory.lastPathComponent)"
            }
        } catch {
            for r in pending { downloading.remove(r.id) }
            note(error.localizedDescription)
        }
    }

    /// 批量下载入口：问一次目录，然后整批拉。
    public func downloadSelected() async {
        let targets = selectedRecordings
        guard !targets.isEmpty else { return }
        guard let dir = pickDownloadDirectory(count: targets.count) else { return }
        await download(recs: targets, into: dir)
    }

    // MARK: 转录

    public func transcript(for rec: Recording) -> TranscriptRecord? {
        library.transcript(for: rec.id)
    }

    /// 最近一次生成的 TXT，底栏用来放「打开转录」入口
    @Published public private(set) var lastTranscriptURL: URL?

    /// 正在应用内阅读的转录，非 nil 时面板切换到阅读视图
    @Published public private(set) var reading: TranscriptDocument?

    // MARK: 筛选与分组

    /// nil = 不按类型过滤
    @Published public var kindFilter: RecordingKind?
    @Published public var groupMode: GroupMode = .time

    /// 面板当前是在看正常列表还是手机回收站
    @Published public var showTrashOnly: Bool = false

    /// AI 摘要开关。关掉后只用本地预览（不联网）。
    @Published public var aiSummaryEnabled: Bool {
        didSet { UserDefaults.standard.set(aiSummaryEnabled, forKey: Keys.aiSummary) }
    }

    /// nonisolated：SummaryService（非隔离上下文）要拿它当默认参数
    public nonisolated static let defaultSummaryModel = "qwen-flash"

    /// 各类录音数量，用于筛选按钮上的计数
    public var kindCounts: [(kind: RecordingKind?, label: String, count: Int)] {
        var base: [(RecordingKind?, String, Int)] = [(nil, "全部", visibleRecordings.count)]
        for k in RecordingKind.allCases where k != .other {
            base.append((k, k.label, visibleRecordings.filter { $0.kind == k }.count))
        }
        return base
    }

    /// 当前这一屏真正要显示的条目：正常列表或手机回收站，二选一。
    /// 两者永远不混在一起 —— 主列表只放「手机上还在原位」的，
    /// 删掉的另开一屏看，这样回收站里躺着的和能用的不会看着打架。
    public var visibleRecordings: [Recording] {
        var base = showTrashOnly ? trashRecordings : recordings
        let q = activeQuery
        if !q.isEmpty {
            base = base.filter { matches($0, query: q) }
        }
        guard let f = kindFilter else { return base }
        return base.filter { $0.kind == f }
    }

    public var filteredRecordings: [Recording] {
        visibleRecordings
    }

    /// 底下还有没有东西可看——判断搜索框/筛选栏该不该显示要用这个，
    /// 不能用 visibleRecordings：搜索没结果时搜索框本身必须留着，
    /// 否则用户连改关键词的地方都没有。
    public var hasRecordsToShow: Bool {
        showTrashOnly ? !trashRecordings.isEmpty : !recordings.isEmpty
    }

    public struct Section: Identifiable {
        public let id: String
        public let title: String
        public let rows: [Recording]
    }

    /// 按当前分组方式切段，供列表渲染
    public var sections: [Section] {
        let rows = visibleRecordings
        guard !rows.isEmpty else { return [] }

        switch groupMode {
        case .time:
            // 按自然日分组，标题用「今天/昨天/MM-dd」
            // 回收站视图改按「删除当天」分：那才是你此刻关心的时间，
            // 录音本身的录制日期在这屏里没有参考价值。
            let cal = Calendar.current
            let groupDay: (Recording) -> Date = showTrashOnly
                ? { cal.startOfDay(for: $0.movedAt ?? $0.displayTime) }
                : { cal.startOfDay(for: $0.displayTime) }
            let groups = Dictionary(grouping: rows, by: groupDay)
            return groups.keys.sorted(by: >).compactMap { day in
                guard let items = groups[day] else { return nil }
                let title: String
                if cal.isDateInToday(day) { title = "今天" }
                else if cal.isDateInYesterday(day) { title = "昨天" }
                else { title = Fmt.monthDay.string(from: day) }
                let sortKey: (Recording) -> Date = showTrashOnly
                    ? { $0.movedAt ?? $0.displayTime }
                    : { $0.displayTime }
                return Section(id: day.timeIntervalSince1970.description,
                               title: title,
                               rows: items.sorted { sortKey($0) > sortKey($1) })
            }

        case .number:
            // 通话按真实号码归组，微信/录音机等没有号码的归到「非号码」
            let groups = Dictionary(grouping: rows) { rec -> String in
                let digits = rec.subject.filter { $0.isNumber }
                return (rec.kind == .call && !digits.isEmpty) ? digits : "非号码（\(rec.kind.label)）"
            }
            return groups.keys.sorted().compactMap { key in
                guard let items = groups[key] else { return nil }
                return Section(id: key, title: key,
                               rows: items.sorted { $0.displayTime > $1.displayTime })
            }

        case .contact:
            let groups = Dictionary(grouping: rows) { $0.subject }
            return groups.keys.sorted().compactMap { key in
                guard let items = groups[key] else { return nil }
                let n = items.count
                return Section(id: key,
                               title: n > 1 ? "\(key) · \(n) 条" : key,
                               rows: items.sorted { $0.displayTime > $1.displayTime })
            }
        }
    }

    /// 在应用内打开转录内容。失败时退回用系统程序打开。
    public func openTranscript(_ rec: Recording) {
        guard let t = transcript(for: rec) else { return }
        let url = URL(fileURLWithPath: t.txtPath)
        guard FileManager.default.fileExists(atPath: url.path) else {
            // 文件没了就别留这条记录
            library.removeTranscript(rec.id)
            transcripts = library.transcripts
            objectWillChange.send()
            note("转录文件已不在原位置：\(url.lastPathComponent)")
            return
        }
        if let doc = TranscriptDocument.parse(remotePath: rec.id,
                                              title: rec.subject,
                                              txtURL: url) {
            reading = doc
        } else {
            NSWorkspace.shared.open(url)
        }
    }

    public func closeTranscript() { reading = nil }

    // MARK: 删除

    /// 把录音移入手机自带回收站（可恢复），并从列表移除
    /// - Parameter alsoRemoveLocal: 是否一并删掉本机已下载的音频与转录
    public func trash(_ rec: Recording, alsoRemoveLocal: Bool) async {
        guard !trashing.contains(rec.id) else { return }
        trashing.insert(rec.id)
        infoText = "正在删除「\(rec.subject)」…"
        defer { trashing.remove(rec.id) }

        do {
            let channel = try await resolveSource()
            // 用 filePath：万一它已经在回收站里，移动的是它现在真正待着的位置
            let dest = try await Task.detached(priority: .userInitiated) {
                try channel.trash(rec)
            }.value

            if alsoRemoveLocal {
                if let p = libraryPath(for: rec), FileManager.default.fileExists(atPath: p.path) {
                    try? FileManager.default.removeItem(at: p)
                }
                if let t = transcript(for: rec) {
                    try? FileManager.default.removeItem(at: URL(fileURLWithPath: t.txtPath))
                    let sidecar = (t.txtPath as NSString).deletingPathExtension + ".json"
                    try? FileManager.default.removeItem(atPath: sidecar)
                }
                library.markDownloaded(rec.id, localPath: "")
            }

            // 本机不记账。手机上现在是什么状态，下一轮扫描就会读到什么状态。
            // 先乐观挪一下，界面不用等下一轮才动。
            if let moved = Recording(remotePath: rec.remotePath,
                                     size: rec.size,
                                     modifiedAt: rec.modifiedAt,
                                     trashPath: dest,
                                     movedAt: Date()) {
                trashRecordings.removeAll { $0.id == moved.id }
                trashRecordings.insert(moved, at: 0)
            }
            recordings.removeAll { $0.id == rec.id }
            selection.remove(rec.id)

            note(nil)
            infoText = "「\(rec.subject)」已移入手机回收站，可恢复"
        } catch {
            NSLog("删除失败 %@: %@", rec.subject, String(describing: error))
            note("「\(rec.subject)」删除失败：\(error.localizedDescription)")
        }
    }

    public func trashSelected(alsoRemoveLocal: Bool) async {
        for rec in selectedRecordings {
            await trash(rec, alsoRemoveLocal: alsoRemoveLocal)
        }
    }

    /// 批量转录：逐条后台跑，某条失败不影响其余
    public func transcribeSelected() async {
        let targets = selectedRecordings
        guard !targets.isEmpty else { return }
        guard ASR.isInstalled else {
            note(ASR.unavailableReason)
            return
        }
        for rec in targets {
            await transcribe(rec)
        }
    }

    /// 按给定集合删除（确认框走这条路，不依赖当前勾选状态）
    public func trashSelectedIf(_ recs: [Recording], alsoRemoveLocal: Bool) async {
        for rec in recs { await trash(rec, alsoRemoveLocal: alsoRemoveLocal) }
    }

    /// 从手机回收站还原（拿回原路径）。
    ///
    /// 源路径取 `trashPath`（文件现在真正待着的地方），不是本机记账的旧值；
    /// 这条被用户自己在手机上还原过时，trashPath 会是 nil，
    /// 此时直接说清楚并重新扫一遍，不做一次注定失败的还原请求。
    public func restoreFromTrash(_ rec: Recording) async {
        guard let from = rec.trashPath else {
            note("这条已经不在手机回收站里了")
            await refresh()
            return
        }
        guard !restoring.contains(rec.id) else { return }
        restoring.insert(rec.id)
        defer { restoring.remove(rec.id) }

        do {
            let channel = try await resolveSource()
            // 存在性检查、还原、落地校验全在通道内部做完了。
            try await Task.detached(priority: .userInitiated) {
                try channel.restore(rec)
            }.value

            trashRecordings.removeAll { $0.id == rec.id }
            selection.remove(rec.id)
            note(nil)
            infoText = "「\(rec.subject)」已从手机回收站还原"
            await refresh()
        } catch {
            note("还原失败：\(error.localizedDescription)")
        }
    }

    /// 批量还原（回收站里勾选后用）
    public func restoreSelectedFromTrash() async {
        let targets = selectedRecordings.filter { $0.isTrashed }
        guard !targets.isEmpty else { return }
        for rec in targets {
            await restoreFromTrash(rec)
        }
    }

    /// 是否在手机回收站里。只认这一轮实扫的结果，本机没有任何"记得"。
    public func isTrashed(_ rec: Recording) -> Bool {
        rec.isTrashed
    }

    /// 回收站里现在有几条（每轮实扫，删干净了就是 0）
    public var trashableCount: Int {
        trashRecordings.count
    }

    // MARK: 搜索

    @Published public var searchText: String = ""

    /// remotePath → 转录全文。搜索文字内容靠它，不去临时 grep 磁盘。
    @Published public private(set) var transcriptText: [String: String] = [:]

    /// 从磁盘补回丢失的转录索引，并把补回来的正文并进搜索索引。
    ///
    /// 已知录音范围取「库里的 entries + 当前这轮扫到的」——
    /// 只用 entries 会漏掉从没下载过、因而没进过 entries 的那些录音。
    func applyRebuiltTranscripts(liveRemotePaths: [String] = []) {
        var known = Array(library.entries.keys)
        for p in liveRemotePaths where !known.contains(p) { known.append(p) }

        let rebuilt = library.rebuildTranscriptIndex(knownRemotePaths: known)
        guard !rebuilt.isEmpty else { return }

        transcripts = library.transcripts
        transcriptText = Self.loadTranscriptText(from: library)
        NSLog("转录索引已重建：%d 条（正文 %d 份）",
              transcripts.count, transcriptText.count)
    }

    private static func loadTranscriptText(from library: Library) -> [String: String] {
        var out: [String: String] = [:]
        for (path, rec) in library.transcripts {
            guard rec.txtPath.hasSuffix(".txt") else { continue }
            guard let text = try? String(contentsOf: URL(fileURLWithPath: rec.txtPath),
                                         encoding: .utf8) else {
                NSLog("索引跳过（读不出）: %@", rec.txtPath)
                continue
            }
            out[path] = text
        }
        return out
    }

    /// 号码归一化：去掉空格和连字符，让「147 4366 2996」也能被「14743662996」搜到
    private static func normalizeNumber(_ s: String) -> String {
        s.filter { $0.isNumber }
    }

    public var activeQuery: String {
        searchText.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// 当前条件下命中的条数
    public var filteredCount: Int {
        sections.reduce(0) { $0 + $1.rows.count }
    }

    public func matches(_ rec: Recording, query raw: String) -> Bool {
        let q = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { return true }

        // 对象名 / 号码
        if rec.subject.localizedCaseInsensitiveContains(q) { return true }
        let qNum = Self.normalizeNumber(q)
        if !qNum.isEmpty,
           Self.normalizeNumber(rec.subject).contains(qNum),
           Self.normalizeNumber(rec.subject).count == qNum.count || rec.subject.contains(q) {
            return true
        }
        // 类型
        if rec.kind.label.contains(q) { return true }

        // 摘要 / 预览
        if let t = transcript(for: rec), let line = t.displayLine, line.contains(q) {
            return true
        }
        // 转录全文
        if let text = transcriptText[rec.id], text.contains(q) { return true }

        return false
    }

    /// 命中在转录正文里时，截一段上下文当第二行，说明「为什么匹配上了」
    public func snippet(for rec: Recording, query raw: String, radius: Int = 18) -> String? {
        let q = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty, let text = transcriptText[rec.id] else { return nil }
        guard let r = text.range(of: q) else { return nil }

        let body = text[(text.startIndex)..<r.lowerBound]
            .filter { !$0.isNewline && $0 != " " && $0 != "\t" }
        let tail = text[r.upperBound...]
            .filter { !$0.isNewline && $0 != " " && $0 != "\t" }

        let headIdx = body.index(body.endIndex, offsetBy: -min(radius, body.count))
        var snippet = String(body[headIdx...]) + String(text[r]) + String(tail.prefix(radius))

        // 去掉残留的段头标记
        snippet = snippet.replacingOccurrences(of: "]", with: "")
        snippet = snippet.replacingOccurrences(of: "说话人", with: "")
        if headIdx > body.startIndex { snippet = "…" + snippet }
        if tail.count > radius { snippet += "…" }
        return snippet
    }

    /// 某条录音的展示第二行：搜索命中正文时用上下文，否则用摘要/预览
    public func secondLine(for rec: Recording) -> String? {
        if let s = snippet(for: rec, query: activeQuery) { return s }
        return transcript(for: rec)?.displayLine
    }

    /// 有多少条已经带可搜索的文字（用来提示「未转录的搜不到内容」）
    public var searchableCount: Int {
        recordings.filter { transcriptText[$0.id] != nil }.count
    }

    // MARK: 搜索历史与联想

    /// 一条联想项
    public struct Suggestion: Identifiable, Equatable {
        public enum Origin: Equatable { case history, record }
        public var id: String { text }
        public let text: String
        public let count: Int
        public let origin: Origin
    }

    private enum SearchKeys {
        static let history = "searchHistory"
        static let maxHistory = 24
    }

    @Published public private(set) var searchHistory: [String] = []

    private func loadHistory() -> [String] {
        UserDefaults.standard.stringArray(forKey: SearchKeys.history) ?? []
    }

    /// 记一次搜索。回车搜什么就记什么；
    /// 点选完整号码则记那个完整号码 —— 两条路径记的东西本来就该不同。
    public func recordSearch(_ raw: String) {
        let q = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard q.count >= 1 else { return }
        searchHistory.removeAll { $0.caseInsensitiveCompare(q) == .orderedSame }
        searchHistory.insert(q, at: 0)
        if searchHistory.count > SearchKeys.maxHistory {
            searchHistory.removeLast(searchHistory.count - SearchKeys.maxHistory)
        }
        UserDefaults.standard.set(searchHistory, forKey: SearchKeys.history)
    }

    public func clearSearchHistory() {
        searchHistory.removeAll()
        UserDefaults.standard.set([] , forKey: SearchKeys.history)
    }

    public func removeSearchHistory(_ item: String) {
        searchHistory.removeAll { $0 == item }
        UserDefaults.standard.set(searchHistory, forKey: SearchKeys.history)
    }

    /// 提交当前输入（回车）：用输入框里的字搜，并记为历史
    public func commitSearch() {
        recordSearch(activeQuery)
    }

    /// 点选联想项：直接用它搜，并记为历史
    public func chooseSuggestion(_ text: String) {
        searchText = text
        recordSearch(text)
    }

    /// 根据当前输入给出联想：先是匹配的历史，再是录音里出现的完整号码与姓名
    public var suggestions: [Suggestion] {
        let q = activeQuery
        guard !q.isEmpty else {
            // 没输入字时，把最近用过的几条摆出来
            return searchHistory.prefix(6).map {
                Suggestion(text: $0, count: matchCount($0), origin: .history)
            }
        }

        var out: [Suggestion] = []
        var seen = Set<String>()

        // 1) 历史里前缀匹配的排前面
        let qLower = q.lowercased()
        let historyHits = searchHistory.filter {
            $0.lowercased().hasPrefix(qLower) && seen.insert($0).inserted
        }
        for h in historyHits {
            out.append(Suggestion(text: h, count: matchCount(h), origin: .history))
        }

        // 2) 录音里的完整号码 / 姓名：前缀匹配优先，其次包含匹配
        let pool = (recordings + trashRecordings)
            .reduce(into: [String: Int]()) { acc, rec in
                acc[rec.subject, default: 0] += 1
            }
        let prefixed = pool.keys.filter {
            $0.lowercased().hasPrefix(qLower) && seen.insert($0).inserted
        }.sorted()
        let contained = pool.keys.filter {
            !$0.lowercased().hasPrefix(qLower) && $0.lowercased().contains(qLower)
                && seen.insert($0).inserted
        }.sorted()

        for s in prefixed + contained {
            out.append(Suggestion(text: s, count: pool[s] ?? 0, origin: .record))
        }

        return Array(out.prefix(8))
    }

    /// 某个词在当前库里能命中多少条
    public func matchCount(_ text: String) -> Int {
        let source = showTrashOnly ? trashRecordings : recordings
        return source.filter { matches($0, query: text) }.count
    }

    /// 转录完成后把正文并进搜索索引
    func indexTranscript(_ rec: Recording) {
        guard let t = transcript(for: rec),
              let text = try? String(contentsOf: URL(fileURLWithPath: t.txtPath),
                                     encoding: .utf8) else {
            transcriptText.removeValue(forKey: rec.id)
            return
        }
        transcriptText[rec.id] = text
    }

    // MARK: 摘要

    /// 给已转录的录音补一句 AI 摘要
    public func generateSummary(for rec: Recording, txtURL: URL) {
        guard let cfg = SummaryService.loadConfig(from: SummaryService.defaultCredentialPath(),
                                                 model: AppState.defaultSummaryModel) else {
            NSLog("摘要跳过：找不到百炼凭据（%@）",
                  SummaryService.defaultCredentialPath().path)
            return
        }
        guard let raw = try? String(contentsOf: txtURL, encoding: .utf8) else { return }

        library.updateTranscript(rec.id) { $0.summarizing = true }
        transcripts[rec.id] = library.transcript(for: rec.id)

        Task {
            let text = raw.replacingOccurrences(of: "\n", with: " ")
            do {
                let s = try await SummaryService.summarize(transcript: text, config: cfg)
                library.updateTranscript(rec.id) {
                    $0.summary = s
                    $0.summarizing = false
                }
            } catch {
                NSLog("摘要失败: %@", String(describing: error))
                library.updateTranscript(rec.id) { $0.summarizing = false }
            }
            transcripts[rec.id] = library.transcript(for: rec.id)
        }
    }

    /// 对所有已转录但还没有 AI 摘要的补一遍
    public func summarizeAllMissing() {
        for rec in recordings {
            guard let t = transcript(for: rec), t.summary == nil, t.summarizing != true else { continue }
            generateSummary(for: rec, txtURL: URL(fileURLWithPath: t.txtPath))
        }
    }

    public var aiSummaryAvailable: Bool {
        SummaryService.loadConfig(from: SummaryService.defaultCredentialPath(),
                                  model: AppState.defaultSummaryModel) != nil
    }

    public func isTranscribing(_ rec: Recording) -> Bool { transcribing.contains(rec.id) }
    public func progress(for rec: Recording) -> Double { transcriptProgress[rec.id] ?? 0 }

    /// 按录音类型推断说话人数。
    /// 自动聚类在这类单声道通话录音上会严重过分割（实测 88 秒 2 人通话被判成 10 人），
    /// 所以电话/微信一律按 2 人处理，录音机录音不猜测。
    public func defaultSpeakerHint(for rec: Recording) -> Int {
        switch rec.kind {
        case .call, .wechat: return 2
        default: return 0
        }
    }

    public func speakerHintText(for rec: Recording) -> String {
        switch defaultSpeakerHint(for: rec) {
        case 0: return "自动判断"
        case let n: return "\(n) 人"
        }
    }

    /// 转录一条录音：先问保存位置，再拉音频（已下载则复用）、转码、转写。
    /// - Parameter speakers: 0 表示自动判断人数
    /// 转录产物默认位置。应用自己的数据目录，不打扰 ~/Downloads。
    /// 只在真的写出第一份转录时才创建，启动不会建。
    public var transcriptFolder: URL {
        get {
            if let p = UserDefaults.standard.string(forKey: Keys.transcriptDir) {
                return URL(fileURLWithPath: p)
            }
            return Library.directory.appendingPathComponent("transcripts", isDirectory: true)
        }
        set { UserDefaults.standard.set(newValue.path, forKey: Keys.transcriptDir) }
    }

    public var transcriptFolderExists: Bool {
        FileManager.default.fileExists(atPath: transcriptFolder.path)
    }

    public var transcriptFolderDisplay: String {
        transcriptFolder.path.replacingOccurrences(of: NSHomeDirectory(), with: "~")
    }

    public func revealTranscriptFolder() {
        try? FileManager.default.createDirectory(at: transcriptFolder,
                                                 withIntermediateDirectories: true)
        NSWorkspace.shared.open(transcriptFolder)
    }

    public func chooseTranscriptFolder() {
        let panel = NSOpenPanel()
        panel.canChooseDirectories = true
        panel.canChooseFiles = false
        panel.canCreateDirectories = true
        panel.allowsMultipleSelection = false
        panel.prompt = "选择"
        panel.directoryURL = transcriptFolder
        if panel.runModal() == .OK, let url = panel.url {
            transcriptFolder = url
        }
    }

    /// 转录一条录音最多尝试几次（首跑 + 重试）。
    /// 失败多半是暂时的：无线抖一下、模型加载崩一回；
    /// 再多就让用户看到失败自己决定，别把底栏变成重试流水账。
    static let transcribeMaxAttempts = 3

    /// 第 n 次失败后等多久再试。纯函数，单测锁行为。
    /// 立刻重试多半撞上同一个故障，拉开一点给两边恢复的时间；封顶 15 秒。
    nonisolated static func retryDelay(afterAttempt attempt: Int) -> TimeInterval {
        switch attempt {
        case 1: return 3
        case 2: return 8
        default: return 15
        }
    }

    /// 只有「环境本身坏了」不值得重试 —— 没装就是没装，重试不会好。
    /// 其余（取音频断连、ffmpeg 抖、转写超时）都可能是暂时的。
    nonisolated static func isRetryableTranscriptionError(_ error: Error) -> Bool {
        switch error as? ASR.ASRError {
        case .notInstalled, .scriptMissing: return false
        default: return true
        }
    }

    /// 转录一条录音：直接存到固定位置，不打断用户。
    /// 失败按 `retryDelay` 退避后自动重试，至多 `transcribeMaxAttempts` 次。
    /// - Parameter speakers: 0 表示自动判断人数
    public func transcribe(_ rec: Recording, speakers: Int? = nil) async {
        guard !transcribing.contains(rec.id) else { return }
        if let reason = ASR.unavailableReason {
            note(reason)
            return
        }

        let dir = transcriptFolder
        let spk = speakers ?? defaultSpeakerHint(for: rec)

        transcribing.insert(rec.id)
        transcriptProgress[rec.id] = 0
        phases[rec.id] = .fetching
        infoText = "正在从手机取「\(rec.subject)」…"
        defer {
            transcribing.remove(rec.id)
            transcriptProgress[rec.id] = nil
            phases[rec.id] = nil
        }

        for attempt in 1...Self.transcribeMaxAttempts {
            do {
                try await runTranscriptionPass(rec, speakers: spk, dir: dir)
                return
            } catch {
                if attempt < Self.transcribeMaxAttempts,
                   Self.isRetryableTranscriptionError(error),
                   !Task.isCancelled {
                    let delay = Self.retryDelay(afterAttempt: attempt)
                    NSLog("转录失败（第 %d/%d 次）%@: %@，%d 秒后重试",
                          attempt, Self.transcribeMaxAttempts,
                          rec.subject, String(describing: error), Int(delay))
                    note("「\(rec.subject)」失败：\(error.localizedDescription)，\(Int(delay)) 秒后自动重试")
                    try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
                    guard !Task.isCancelled else { break }
                    // 从头再来。取音频那步会复用已有副本，不会重复下载。
                    phases[rec.id] = .fetching
                    transcriptProgress[rec.id] = 0
                    continue
                }
                NSLog("转录失败 %@: %@", rec.subject, String(describing: error))
                note("「\(rec.subject)」转录失败：\(error.localizedDescription)")
                // 失败时不留下半成品 txt
                let stem = (rec.fileName as NSString).deletingPathExtension
                let junk = dir.appendingPathComponent("\(stem).txt")
                if FileManager.default.fileExists(atPath: junk.path) {
                    try? FileManager.default.removeItem(at: junk)
                }
                return
            }
        }
    }

    /// 单次转录尝试：取音频（已有副本则复用）→ 转码 → 转写 → 落库。
    /// 中途任何一步抛错都交给调用方决定要不要重试；成功路径在这里全部收尾。
    private func runTranscriptionPass(_ rec: Recording, speakers spk: Int, dir: URL) async throws {
        let channel = try await resolveSource()

        // 音频：已有本地副本就复用，避免重复下载
        let audioSource: URL
        if let existing = libraryPath(for: rec), FileManager.default.fileExists(atPath: existing.path) {
            audioSource = existing
            infoText = "复用已有音频「\(rec.subject)」"
        } else {
            let dest = destination(for: rec, in: dir)
            downloading.insert(rec.id)
            let pulled: URL
            do {
                pulled = try await Task.detached(priority: .userInitiated) {
                    try channel.download(rec, to: dest)
                }.value
            } catch {
                downloading.remove(rec.id)
                throw error
            }
            downloading.remove(rec.id)
            library.markDownloaded(rec.id, localPath: pulled.path)
            audioSource = pulled
        }

        // 转码到 16kHz 单声道，临时文件放同一目录下的 .asr 临时区
        let workDir = dir.appendingPathComponent(".asr-tmp", isDirectory: true)
        let stem = (rec.fileName as NSString).deletingPathExtension
        let wav = workDir.appendingPathComponent("\(stem).wav")
        phases[rec.id] = .converting
        infoText = "正在转换音频格式…"
        try await ASR.convertToWav(input: audioSource, output: wav)

        let txtOut = dir.appendingPathComponent("\(stem).txt")
        phases[rec.id] = .transcribing
        infoText = "正在转录「\(rec.subject)」…"

        let outcome = try await ASR.transcribe(
            wav: wav,
            txtOut: txtOut,
            speakers: spk,
            onMeta: { [weak self] dur in
                Task { @MainActor in
                    self?.infoText = String(
                        format: "正在转录「%@」…（音频 %.0f 秒）",
                        rec.subject, dur)
                }
            },
            onProgress: { [weak self] p in
                Task { @MainActor in self?.transcriptProgress[rec.id] = p }
            }
        )

        let record = TranscriptRecord(
            txtPath: outcome.txtURL.path,
            sentenceCount: outcome.sentenceCount,
            speakerCount: outcome.speakerCount,
            model: outcome.model,
            audioDuration: outcome.audioDuration,
            inferSeconds: outcome.inferSeconds,
            createdAt: Date()
        )
        library.saveTranscript(rec.id, record: record)
        transcripts[rec.id] = record
        lastTranscriptURL = URL(fileURLWithPath: record.txtPath)

        // 本地预览：瞬时、不联网，作为第二行内容
        let raw = (try? String(contentsOf: outcome.txtURL, encoding: .utf8)) ?? ""
        let preview = SummaryService.localPreview(from: raw)
        library.updateTranscript(rec.id) { $0.preview = preview }
        transcripts[rec.id] = library.transcript(for: rec.id)
        indexTranscript(rec)

        // 转录过就算处理过了：单独把这条从「新增」里去掉。
        // 不能推水位线 —— 那会把它之前所有新增一并标成已读。
        if library.isNew(rec) {
            library.markIndividuallySeen(rec.id)
            objectWillChange.send()
        }

        // AI 摘要：走本地已有的百炼凭据，可在菜单里关掉
        if aiSummaryEnabled {
            generateSummary(for: rec, txtURL: outcome.txtURL)
        }

        // 清理临时 wav 与空目录
        try? FileManager.default.removeItem(at: wav)
        if let left = try? FileManager.default.contentsOfDirectory(atPath: workDir.path),
           left.isEmpty {
            try? FileManager.default.removeItem(at: workDir)
        }

        note(nil)
        infoText = "「\(rec.subject)」转录完成 · \(outcome.sentenceCount) 句 / \(outcome.speakerCount) 人"
        // 转录完直接把内容摊开，用户点一下就想看结果
        openTranscript(rec)
    }

    /// 下载全部新增：问一次目录。
    public func downloadAllNew() async {
        let targets = recordings.filter { library.isNew($0) }
        guard !targets.isEmpty else { return }
        guard let dir = pickDownloadDirectory(count: targets.count) else { return }
        await download(recs: targets, into: dir)
    }

    // MARK: 杂项

    public func revealLastDir() {
        guard let last = lastPickedDir, FileManager.default.fileExists(atPath: last.path) else { return }
        NSWorkspace.shared.activateFileViewerSelecting([last])
    }

    public var lastDirDisplay: String {
        guard let last = lastPickedDir else { return "" }
        let shown = last.path.replacingOccurrences(of: NSHomeDirectory(), with: "~")
        return FileManager.default.fileExists(atPath: last.path) ? "上次存到 \(shown)" : "\(shown)（已不存在）"
    }

    public func copyDiagnostic() {
        let text = """
        设备: \(link.title)
        上次选择目录: \(lastPickedDir?.path ?? "尚未选择")
        已选: \(selection.count)
        文件数: \(recordings.count)
        新增标记: \(newCount)
        库文件: \(Library.fileURL.path)
        """
        let pb = NSPasteboard.general
        pb.clearContents()
        pb.setString(text, forType: .string)
    }
}
