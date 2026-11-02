import SwiftUI
import AppKit

/// 面板尺寸与排版常量，集中一处便于统一调整
public enum Metrics {
    static let contentWidth: CGFloat = 440
    static let trendWidth: CGFloat = 240
    public static var panelWidth: CGFloat { min(720, (NSScreen.main?.visibleFrame.width ?? 1000) - 32) }
    static let rowPaddingH: CGFloat = 16
    static let rowPaddingV: CGFloat = 4
    /// 单行行高
    static let rowOneLine: CGFloat = 36
    /// 双行行高（带预览/摘要时）
    static let rowTwoLines: CGFloat = 56
    static let iconSize: CGFloat = 15
    public static var panelHeight: CGFloat {
        min(640, (NSScreen.main?.visibleFrame.height ?? 800) - 48)
    }
}

// MARK: - 菜单栏主面板

public struct RecorderMenuView: View {
    @Environment(\.fileWeaveOverviewVisible) private var overviewVisible
    @EnvironmentObject private var app: AppState
    /// 单例 + @ObservedObject：登录项的状态变化不会经过 AppState 的
    /// objectWillChange，用 environmentObject 是收不到的
    @ObservedObject private var login = LoginItem.shared
    @FocusState private var searchFocused: Bool
    /// 联想面板里当前悬停的条目，用来显示删除按钮
    @State private var hovered: String?
    /// 已经「第一下点中」待确认删除的行，再点一次图标才真删
    @State private var armedTrash: String?
    @State private var armTimer: Task<Void, Never>?

    public init() {}

    /// 第一次点：进入待确认；第二次点：直接删，不弹框。4 秒不动自动取消。
    /// 本机副本一律保留 —— 转录文字比音频难再生成，不能顺手抹掉。
    private func handleTrashTap(_ rec: Recording) {
        if app.trashing.contains(rec.id) { return }

        if armedTrash == rec.id {
            armTimer?.cancel()
            armedTrash = nil
            Task { await app.trash(rec, alsoRemoveLocal: false) }
        } else {
            armTimer?.cancel()
            armedTrash = rec.id
            armTimer = Task {
                try? await Task.sleep(nanoseconds: 4_000_000_000)
                if !Task.isCancelled { armedTrash = nil }
            }
        }
    }

    private func disarm() {
        armTimer?.cancel()
        armedTrash = nil
    }

    public var body: some View {
        Group {
            if let doc = app.reading {
                TranscriptReaderView(doc: doc)
            } else {
                listView
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .onChange(of: app.selection) { _, _ in disarm() }
        .onDisappear { searchFocused = false }
        .onChange(of: app.isPanelVisible) { _, visible in if visible { searchFocused = false } }
        .onExitCommand { searchFocused = false }
        .onReceive(NotificationCenter.default.publisher(for: .fileWeaveFind)) { _ in searchFocused = true }
    }

    private var listView: some View {
        VStack(spacing: 0) {
            // 焦点占位符。这不是视觉元素，是功能性的：
            // SwiftUI 让窗口成为 key 时会把焦点给视图树里第一个可聚焦的东西，
            // 不放它，第一个就是搜索框 —— 于是「打开面板，搜索框先亮一下」。
            // 放上它，焦点落在这个 1×1 空视图上，搜索框从头到尾不会被点亮。
            // 实测：不加这行，面板出现后的下一轮 searchFocused 就会变 true。
            //
            // 别改成在 AppKit 层拦（initialFirstResponder 或塞一个 acceptsFirstResponder
            // 的视图进去）：SwiftUI 会用 KeyViewProxy 抢回第一响应者，那层一律无效。
            Color.clear
                .frame(width: 1, height: 1)
                .opacity(0)
                .focusable()
                .accessibilityHidden(true)

            if !overviewVisible { header; Divider() }
            if app.hasRecordsToShow {
                filterBar
                Divider()
            }
            list
            if app.hasRecordsToShow {
                Divider()
                footer
            }
        }
    }

    // MARK: 顶栏

    private var header: some View {
        HStack(spacing: 8) {
            // 搜索框放在顶栏：最高频的动作，占最好的位置
            searchBar

            Spacer(minLength: 4)

            // 右上角只留两个图标按钮：刷新 + 更多。
            // 上次扫描时间显示在底栏，避免重复放置刷新按钮。
            if app.scanning {
                ProgressView()
                    .controlSize(.small)
                    .scaleEffect(0.5)
                    .frame(width: 12, height: 12)
            } else {
                Button {
                    Task { await app.userRefresh() }
                } label: {
                    Image(systemName: "arrow.clockwise")
                        .font(.system(size: 12))
                }
                .buttonStyle(.plain)
                .foregroundStyle(.secondary)
                .help(L("立即重新扫描"))
            }

            moreMenu
        }
        .padding(.horizontal, Metrics.rowPaddingH)
        .padding(.vertical, 8)
    }

    // MARK: 搜索框

    /// macOS 风格的搜索框：放大镜 + 输入 + 清除按钮。
    /// 搜号码/姓名/类型，也搜已转录条目的正文与摘要。
        /// 搜索框本体。联想面板是浮层，不参与布局流，不会把下面内容顶下去。
    private var searchBar: some View {
        NativeSearchField(text: $app.searchText, placeholder: L("搜索号码、姓名或转录内容"), onCommit: { app.commitSearch() })
            .frame(height: 24)
    }

    // MARK: 筛选栏

    /// 筛选标签的统一外观：文字 + 计数，选中时整条变强调色
    private func filterTab(_ title: String, count: Int, active: Bool,
                           action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 3) {
                Text(title)
                    .font(.system(size: 12, weight: active ? .semibold : .regular))
                Text("\(count)")
                    .font(.system(size: 12).monospacedDigit())
                    .foregroundStyle(active ? Color.accentColor : Color.secondary.opacity(0.55))
            }
            .foregroundStyle(active ? Color.accentColor : Color.secondary)
        }
        .buttonStyle(.plain)
    }

    private var filterBar: some View {
        HStack(spacing: 10) {
            ForEach(app.kindCounts, id: \.label) { item in
                filterTab(item.label, count: item.count,
                          active: app.kindFilter == item.kind) {
                    app.kindFilter = (app.kindFilter == item.kind) ? nil : item.kind
                }
            }

            Spacer(minLength: 6)

            if !app.activeQuery.isEmpty {
                Text(L("命中 {0}", app.filteredCount))
                    .font(.system(size: 12).monospacedDigit())
                    .foregroundStyle(.tertiary)
                    .help(L("已转录 {0} 条可搜正文，其余只能搜号码和姓名", app.searchableCount))
            }

            // 回收站入口：低频，混在筛选行里，不抢顶栏位置。
            // 只有手机里真躺着东西时才出现，不常驻一个永远显示 0 的按钮。
            if app.trashableCount > 0 || app.showTrashOnly {
                filterTab(L("回收站"), count: app.trashableCount,
                          active: app.showTrashOnly) {
                    app.setTrashView(!app.showTrashOnly)
                }
                .help(app.showTrashOnly ? L("回到录音列表")
                                       : L("手机回收站里有 {0} 条，点开可以还原", app.trashableCount))
            }

            Menu {
                Picker(L("分组"), selection: Binding(
                    get: { app.groupMode },
                    set: { app.groupMode = $0 }
                )) {
                    ForEach(GroupMode.allCases) { m in
                        Text(m.label).tag(m)
                    }
                }
                .pickerStyle(.inline)
            } label: {
                HStack(spacing: 2) {
                    Text(app.groupMode.label)
                        .font(.system(size: 12))
                    Image(systemName: "chevron.down")
                        .font(.system(size: 8, weight: .semibold))
                }
                .foregroundStyle(.secondary)
            }
            .menuStyle(.borderlessButton)
            .menuIndicator(.hidden)
            .fixedSize()
        }
        .padding(.horizontal, Metrics.rowPaddingH)
        .padding(.vertical, 6)
    }

    // MARK: 列表

    @ViewBuilder
    private var list: some View {
        // 聚焦搜索框时，列表区整个变成搜索面板（历史 + 联想），
        // 不再用浮层盖住 —— 免得分不清浮层和列表是两回事。
        recordingList
    }

    /// 搜索态的面板：占满列表区，可滚动
    @ViewBuilder
    private var searchPanel: some View {
        let items = app.suggestions
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                if items.isEmpty {
                    HStack(spacing: 6) {
                        Image(systemName: "magnifyingglass")
                            .font(.system(size: 12))
                            .foregroundStyle(.quaternary)
                        Text(app.activeQuery.isEmpty ? L("还没有搜索历史") : L("没有匹配的建议"))
                            .font(.system(size: 12))
                            .foregroundStyle(.tertiary)
                        Spacer()
                    }
                    .padding(.horizontal, Metrics.rowPaddingH)
                    .padding(.vertical, 12)
                } else {
                    ForEach(items) { s in suggestionRow(s) }
                }
            }
        }
        .frame(maxHeight: .infinity)
        // 点空白 = 取消搜索：失焦并清掉输入框里的字。
        // 条目是 Button，点了它自己吃掉这次点击、不会被这里截胡。
        //
        // `.contentShape(Rectangle())` 不能省：ScrollView 自身是透明的，
        // 内容不满时下方那片空白不显式声明形状就**完全收不到点击** ——
        // 这正是「点空白没反应」的原因。
        .contentShape(Rectangle())
        .onTapGesture { cancelSearch() }
    }

    /// 退出搜索态：失焦 + 清空。
    ///
    /// 和 Esc 分开是有意的：Esc 只收起面板、留着输入（半句词打了一半，
    /// 按 Esc 不该被清掉），点空白则是「我不搜了」，连输入一起作废。
    private func cancelSearch() {
        app.searchText = ""
        searchFocused = false
    }

    private func suggestionRow(_ s: AppState.Suggestion) -> some View {
        HStack(spacing: 4) {
            Button {
                app.chooseSuggestion(s.text)
                searchFocused = false
            } label: {
                HStack(spacing: 6) {
                    Image(systemName: s.origin == .history
                          ? "clock.arrow.circlepath" : "phone.and.waveform")
                        .font(.system(size: 12))
                        .foregroundStyle(.tertiary)
                        .frame(width: 13)

                    Text(s.text)
                        .font(.system(size: 13))
                        .lineLimit(1)

                    Spacer(minLength: 6)

                    if s.count > 0 {
                        Text(L("共 {0} 条", s.count))
                            .font(.system(size: 12).monospacedDigit())
                            .foregroundStyle(.tertiary)
                    }
                }
                .padding(.leading, Metrics.rowPaddingH)
                .padding(.trailing, 10)
                .padding(.vertical, 6)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            if s.origin == .history {
                Button {
                    app.removeSearchHistory(s.text)
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 12))
                        .foregroundStyle(.tertiary)
                }
                .buttonStyle(.plain)
                .opacity(hovered == s.id ? 1 : 0.3)
                .padding(.trailing, 10)
                .padding(.vertical, 6)
                .contentShape(Rectangle())
                .help(L("删除这条历史"))
                .onHover { inside in
                    hovered = inside ? s.id : (hovered == s.id ? nil : hovered)
                }
            }
        }
    }

    @ViewBuilder
    private var recordingList: some View {
        let secs = app.sections

        if secs.isEmpty {
            if app.link.isReady {
                emptyFilterState
            } else {
                disconnectedState
            }
        } else {
            List(selection: $app.selection) {
                ForEach(secs) { sec in
                    Section {
                        ForEach(sec.rows) { rec in
                            RecordingRow(
                                rec: rec,
                                armedTrash: armedTrash == rec.id,
                                onTrashTap: { handleTrashTap(rec) }
                            )
                            .tag(rec.id)
                            .fileWeaveListRow(isSelected: app.selection.contains(rec.id))
                            .listRowInsets(EdgeInsets(top: 2, leading: 0, bottom: 2, trailing: 0))
                        }
                    } header: {
                        sectionHeader(sec.title, count: sec.rows.count)
                    }
                }
            }
            .contextMenu(forSelectionType: String.self) { ids in
                if let rec = secs.flatMap({ $0.rows }).first(where: { ids.contains($0.id) }) {
                    RecordingContextMenu(rec: rec, onTrashTap: { handleTrashTap(rec) })
                }
            } primaryAction: { ids in
                guard let rec = secs.flatMap({ $0.rows }).first(where: { ids.contains($0.id) }) else { return }
                if app.transcript(for: rec) != nil { app.openTranscript(rec) }
                else { Task { await app.downloadOne(rec) } }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .environment(\.defaultMinListRowHeight, Metrics.rowOneLine)
            .frame(maxHeight: .infinity)
        }
    }

    /// 连上了、但当前条件下没有录音。
    ///
    /// 两种「空」含义完全不同，必须分开说：
    /// 过滤后为空，暗示搜索条件；而**整台手机一条都没有**，暗示连错了设备。
    private var emptyFilterState: some View {
        let wholePhoneEmpty = !app.showTrashOnly
            && app.activeQuery.isEmpty
            && app.recordings.isEmpty
            && app.trashRecordings.isEmpty
        return VStack(spacing: 8) {
            Image(systemName: app.showTrashOnly
                  ? "trash"
                  : (wholePhoneEmpty ? "iphone.slash" : "line.3.horizontal.decrease.circle"))
                .font(.system(size: 20))
                .foregroundStyle(.tertiary)
            Text(app.showTrashOnly
                 ? (app.trashRecordings.isEmpty
                    ? L("手机回收站里没有录音")
                    : L("回收站里没有符合条件的录音"))
                 : (wholePhoneEmpty
                    ? (app.recordingsSharingEnabled ? L("当前共享录音目录中没有音频，请在手机检查目录设置。") : L("手机录音共享已关闭，请在手机的录音与转录中开启。"))
                    : L("没有符合条件的录音")))
                .font(.system(size: 12))
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(.vertical, 28)
    }

    /// 顶栏里那台设备的简称。空状态文案要用它点明「是哪台没有」。
    private var linkTitle: String {
        if case .ready(let info) = app.link { return info.name }
        return L("这台手机")
    }

    /// 没连上时，这块地方就是唯一能说话的地方。
    ///
    /// 之前提示语只写在 `LinkState.hint` 里、没有任何视图去读它，
    /// 而底栏那行错误提示又只在「有录音要显示」时才渲染——
    /// 正好在最需要指引的时候整块不见了，界面只剩一行干巴巴的标题。
    private var disconnectedState: some View {
        VStack(spacing: 8) {
            Image(systemName: "iphone.slash")
                .font(.system(size: 20))
                .foregroundStyle(.tertiary)

            Text(app.link.title)
                .font(.system(size: 13, weight: .medium))

            if let hint = app.linkHint {
                Text(hint)
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                    .textSelection(.enabled)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(.horizontal, 22)
        .padding(.vertical, 28)
    }

    private func sectionHeader(_ title: String, count: Int) -> some View {
        HStack(spacing: 6) {
            Text(title)
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(.secondary)
            Text("\(count)")
                .font(.system(size: 12).monospacedDigit())
                .foregroundStyle(.quaternary)
            Spacer()
        }
        .padding(.horizontal, Metrics.rowPaddingH)
        .padding(.top, 8)
        .padding(.bottom, 4)
    }

    // MARK: 底栏

    private var footer: some View {
        VStack(spacing: 6) {

            if !overviewVisible, let err = app.errorText {
                Text(err)
                    .font(.system(size: 12))
                    .foregroundStyle(.red)
                    .lineLimit(2)
                    .frame(maxWidth: .infinity, alignment: .leading)
            } else if !overviewVisible && app.selection.isEmpty, let info = app.infoText {
                Text(info)
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }

            // 本地转录入口、扫描状态与当前选择操作。
            HStack(spacing: 8) {
                Group {
                    if app.transcriptFolderExists {
                        Button {
                            app.revealTranscriptFolder()
                        } label: {
                            Label(L("转录文件夹"), systemImage: "folder")
                                .labelStyle(.titleAndIcon)
                                .font(.system(size: 12))
                        }
                        .buttonStyle(.plain)
                        .foregroundStyle(Color.accentColor)
                        .help(app.transcriptFolderDisplay)
                    }
                }
                .frame(minWidth: 78, alignment: .leading)

                Spacer(minLength: 4)

                // 新增计数挪到刷新旁边：它是「这一轮扫到了什么」的一部分，
                // 和刷新时刻天然成对。放顶栏时它夹在机型和搜索框中间，
                // 扫一次跳一下，位置也不固定。
                if app.newCount > 0 {
                    Button {
                        app.clearNewFlags()
                    } label: {
                        HStack(spacing: 3) {
                            Image(systemName: "sparkles")
                                .font(.system(size: 12))
                            Text(L("{0} 条新增", app.newCount))
                                .font(.system(size: 12, weight: .medium))
                        }
                        .foregroundStyle(Color.accentColor)
                    }
                    .buttonStyle(.plain)
                    .help(L("点击标记为已读"))
                }

                if let at = app.lastScanAt {
                    Text(Fmt.clock.string(from: at))
                        .font(.system(size: 12).monospacedDigit())
                        .foregroundStyle(.tertiary)
                        .help(L("上次扫描时间"))
                }

                Spacer(minLength: 4)

                Group {
                    HStack(spacing: 8) {
                        if !app.selection.isEmpty {
                            Text(L("已选 {0}", app.selection.count))
                                .font(.system(size: 12))
                                .foregroundStyle(.secondary)
                        }
                        batchMenu
                    }
                }
                .frame(minWidth: 78, alignment: .trailing)
            }
        }
        .padding(.horizontal, Metrics.rowPaddingH)
        .padding(.vertical, 8)
    }

    /// 右上角「更多」：全库级别的批量动作，和底部的「批量操作」区分开
    /// ——那个作用于勾选项，这个作用于整个库。
    private var moreMenu: some View {
        Menu {
            if !app.suggestions.isEmpty || !app.searchHistory.isEmpty {
                Menu(L("搜索历史与建议")) {
                    ForEach(app.suggestions) { suggestion in
                        Button(suggestion.text) { app.chooseSuggestion(suggestion.text) }
                    }
                    if !app.searchHistory.isEmpty {
                        Divider()
                        Menu(L("移除搜索历史")) {
                            ForEach(app.searchHistory, id: \.self) { item in
                                Button(item) { app.removeSearchHistory(item) }
                            }
                        }
                        Button(L("清除搜索历史")) { app.clearSearchHistory() }
                    }
                }
                Divider()
            }
            Button(L("全选当前筛选结果")) { app.selectAll() }
            Button(L("反选当前筛选结果")) { app.invertSelection() }
            Button(L("取消全部勾选")) { app.clearSelection() }
            Divider()

            Button(L("一键转录全部（{0} 条未转录）", app.untranscribed.count)) {
                BulkConfirm.transcribeAll(app: app)
            }
            .disabled(!app.asrAvailable || app.untranscribed.isEmpty)

            Button(L("下载全部筛选结果…")) {
                Task { await app.downloadAllVisible() }
            }

            Button(L("补齐缺失的摘要")) {
                app.summarizeAllMissing()
            }
            .disabled(!app.aiSummaryEnabled || !app.aiSummaryAvailable)

            Divider()

            Button(L("清除新增标记（{0}）", app.newCount)) {
                app.clearNewFlags()
            }
            .disabled(app.newCount == 0)

            Button(L("查看转录文件夹")) { app.revealTranscriptFolder() }

            Divider()

            Menu(L("刷新间隔")) {
                Button(L("15 秒")) { app.pollInterval = 15 }
                Button(L("30 秒")) { app.pollInterval = 30 }
                Button(L("1 分钟")) { app.pollInterval = 60 }
                Button(L("5 分钟")) { app.pollInterval = 300 }
            }

            // Toggle 放进 Menu 里系统自己画勾，不用自己拼「开/关」文字
            Toggle(L("登录时启动"), isOn: Binding(
                get: { login.isEnabled },
                set: {
                    login.setEnabled($0)
                    if let err = login.lastError {
                        app.notify(L("设置开机启动失败：{0}", err))
                    }
                }
            ))
            .disabled(!login.isAvailable)
        } label: {
            Image(systemName: "ellipsis.circle")
                .font(.system(size: 13))
        }
        .menuStyle(.borderlessButton)
        .menuIndicator(.hidden)
        .fixedSize()
        .foregroundStyle(.secondary)
        .help(L("更多批量操作"))
    }

    /// 所有可批量做的事收在一个菜单里，按钮区只留这一个入口。
    /// 回收站视图下能做的操作不同，菜单内容跟着切换。
    private var batchMenu: some View {
        Menu {
            if app.showTrashOnly {
                Button(L("全选")) { app.selectAll() }
                Button(L("从回收站还原所选…")) {
                    Task { await app.restoreSelectedFromTrash() }
                }
            } else {
                Button(L("全选")) { app.selectAll() }
                Button(L("全不选")) { app.clearSelection() }
                Divider()
                Button(L("下载所选…")) {
                    Task { await app.downloadSelected() }
                }
                Button(L("转录所选…")) {
                    Task { await app.transcribeSelected() }
                }
                .disabled(!app.asrAvailable)
                Divider()
                Button(L("移到回收站…"), role: .destructive) {
                    TrashConfirm.present(for: app.selectedRecordings, app: app)
                }
            }
            Divider()
            Button(L("清除新增标记")) { app.clearNewFlags() }
            .disabled(app.newCount == 0)
        } label: {
            HStack(spacing: 3) {
                Image(systemName: "ellipsis.circle")
                    .font(.system(size: 12))
                Text(app.selection.isEmpty ? L("批量操作") : L("批量操作 {0}", app.selection.count))
                    .font(.system(size: 12, weight: .medium))
            }
        }
        .menuStyle(.borderlessButton)
        .menuIndicator(.hidden)
        .fixedSize()
        .disabled(app.selection.isEmpty)
        .help(L("选中若干条后可批量下载、转录或删除"))
    }
}

// MARK: - 单行

struct RecordingRow: View {
    @EnvironmentObject private var app: AppState
    let rec: Recording
    /// 是否处于「再点一次就删」的待确认状态
    var armedTrash: Bool = false
    var onTrashTap: () -> Void = {}

    private var transcript: TranscriptRecord? { app.transcript(for: rec) }

    /// 在回收站里的行第二行写「删除于」，别让人以为是可用条目
    private var trashLine: String? {
        guard isTrashed else { return nil }
        guard let at = rec.movedAt else { return L("手机回收站") }
        return L("手机回收站 · 删除于 {0}", Fmt.dayTime.string(from: at))
    }

    /// 回收站条目固定显示删除时间；否则搜索命中正文时给上下文，否则给摘要/预览
    private var secondLine: String? {
        if let t = trashLine { return t }
        return app.secondLine(for: rec)
    }
    private var isPicked: Bool { app.isSelected(rec) }
    private var isNew: Bool { app.isNew(rec) }
    private var isBusy: Bool { app.downloading.contains(rec.id) }
    private var isDone: Bool { app.isDownloaded(rec) }
    private var isTranscribing: Bool { app.isTranscribing(rec) }
    private var isTrashed: Bool { app.isTrashed(rec) }

    var body: some View {
        HStack(alignment: .center, spacing: 8) {
            // 类别图标：跨两行垂直居中，一眼分辨类型。
            // 在回收站里的换成垃圾桶图标，别让可用的和删掉的长得一样。
            Image(systemName: isTrashed ? "trash" : rec.kind.symbol)
                .font(.system(size: Metrics.iconSize))
                .foregroundStyle(isTrashed ? Color.secondary : Color.secondary.opacity(0.9))
                .frame(width: Metrics.iconSize)
                .help(isTrashed ? L("在手机回收站里") : rec.kind.label)

            VStack(alignment: .leading, spacing: 3) {
                // 第一行：对象名 + 右侧元信息（时间、大小），让第二行独占整宽。
                // 不要用 alignmentGuide(.firstTextBaseline) —— 与固定高度元素组合会
                // 形成布局反馈环，SwiftUI 反复重算把 CPU 打满。
                HStack(spacing: 6) {
                    Text(rec.subject)
                        .font(.system(size: 13, weight: isNew ? .semibold : .regular))
                        .lineLimit(1)

                    if isNew {
                        Circle()
                            .fill(Color.red)
                            .frame(width: 5, height: 5)
                    }

                    Spacer(minLength: 10)

                    Text(Fmt.dayTime.string(from: rec.displayTime))
                        .font(.system(size: 12).monospacedDigit())
                        .foregroundStyle(.secondary)
                        .lineLimit(1)

                    Text(rec.sizeText)
                        .font(.system(size: 12).monospacedDigit())
                        .foregroundStyle(.tertiary)
                        .lineLimit(1)
                }

                // 第二行：预览/摘要，整行宽度都是它的
                if let line = secondLine {
                    Text(line)
                        .font(.system(size: 12))
                        .foregroundStyle(.tertiary)
                        .lineLimit(2)
                        .multilineTextAlignment(.leading)
                        .fixedSize(horizontal: false, vertical: true)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            // 回收站里的整块压暗，一眼能和还能用的分开
            .opacity(isTrashed ? 0.75 : 1)

            // 主槽：回收站里是「还原」，否则是「转录」
            if isTrashed {
                restoreButton
            } else {
                transcribeButton
            }

            // 删除槽：只在还在库里时出现，已删的交给上面的还原按钮，
            // 免得一行上出现两个一模一样的还原图标。
            if !isTrashed {
                trashSlot
            }
        }
        .padding(.horizontal, Metrics.rowPaddingH)
        .padding(.vertical, Metrics.rowPaddingV)
        .frame(minHeight: secondLine == nil ? Metrics.rowOneLine : Metrics.rowTwoLines,
               alignment: .center)
        .background(armedTrash && !isPicked ? Color.red.opacity(0.10) : Color.clear)
        .contentShape(Rectangle())
    }

    /// 勾选标记：已勾选 ✓，未勾选空心圈
    @ViewBuilder
    private var marker: some View {
        if isPicked {
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 13))
                .foregroundStyle(Color.accentColor)
        } else {
            Circle()
                .strokeBorder(Color.secondary.opacity(0.35), lineWidth: 1)
                .frame(width: 6, height: 6)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    /// 还原按钮：把文件从手机回收站搬回原位置
    @ViewBuilder
    private var restoreButton: some View {
        if app.restoring.contains(rec.id) {
            ProgressView()
                .controlSize(.small)
                .scaleEffect(0.5)
                .frame(width: 14, height: 14)
                .help(L("正在还原"))
        } else {
            Button { Task { await app.restoreFromTrash(rec) } } label: {
                Image(systemName: "arrow.uturn.backward.circle")
                    .font(.system(size: 12))
                    .foregroundStyle(Color.accentColor)
            }
            .buttonStyle(.plain)
            .help(L("从手机回收站还原"))
        }
    }

    /// 删除槽：常态垃圾桶 → 第一下变红待确认 → 再点一次才真删。
    /// 二次确认不弹窗，靠再点一次同一个图标；4 秒不确认自动取消。
    @ViewBuilder
    private var trashSlot: some View {
        if app.trashing.contains(rec.id) {
            ProgressView()
                .controlSize(.small)
                .scaleEffect(0.5)
                .frame(width: 14, height: 14)
                .help(L("正在移到回收站"))
        } else {
            Button(action: onTrashTap) {
                Image(systemName: armedTrash ? "trash.fill" : "trash")
                    .font(.system(size: 12))
                    .foregroundStyle(armedTrash ? Color.red : Color.secondary.opacity(0.6))
            }
            .buttonStyle(.plain)
            .help(armedTrash ? L("再点一次确认删除") : L("移到手机回收站"))
        }
    }

    @ViewBuilder
    private var transcribeButton: some View {
        if !app.asrAvailable {
            Image(systemName: "text.bubble")
                .font(.system(size: 12))
                .foregroundStyle(.quaternary)
                .help(app.asrUnavailableReason ?? L("转录环境未就绪"))
        } else if isTranscribing {
            ProgressRing(progress: app.progress(for: rec))
                .help(L("转录中…"))
        } else if let t = transcript {
            Button { app.openTranscript(rec) } label: {
                Image(systemName: "text.bubble.fill")
                    .font(.system(size: 12))
                    .foregroundStyle(Color.accentColor)
            }
            .buttonStyle(.plain)
            .help(L("已转录 {0} 句，点击查看", t.sentenceCount))
        } else {
            Button { Task { await app.transcribe(rec) } } label: {
                Image(systemName: "text.bubble")
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
            }
            .buttonStyle(.plain)
            .help(L("转录为文字 · 按 {0}识别说话人", app.speakerHintText(for: rec)))
        }
    }

}

private struct RecordingContextMenu: View {
    @EnvironmentObject private var app: AppState
    let rec: Recording
    var onTrashTap: () -> Void
    private var transcript: TranscriptRecord? { app.transcript(for: rec) }
    private var isTrashed: Bool { app.isTrashed(rec) }
    private var isPicked: Bool { app.isSelected(rec) }

    var body: some View {
        // 在回收站里的条目文件还在（只是换了一个位置），
        // 所以转录、下载一样能用，菜单不要把它们藏掉。
        if isTrashed {
            Button(L("从手机回收站还原")) { Task { await app.restoreFromTrash(rec) } }
                .disabled(app.restoring.contains(rec.id))
            Divider()
        }

        Button(L("转录为文字…")) { Task { await app.transcribe(rec) } }
            .disabled(!app.asrAvailable)

        if let t = transcript {
            Button(L("重新生成摘要")) {
                app.generateSummary(for: rec,
                                    txtURL: URL(fileURLWithPath: t.txtPath))
            }
        }

        Menu(L("转录时假定人数")) {
            Button(L("自动判断")) { Task { await app.transcribe(rec, speakers: 0) } }
            Button(L("1 人")) { Task { await app.transcribe(rec, speakers: 1) } }
            Button(L("2 人")) { Task { await app.transcribe(rec, speakers: 2) } }
            Button(L("3 人")) { Task { await app.transcribe(rec, speakers: 3) } }
        }
        .disabled(!app.asrAvailable)

        if transcript != nil {
            Button(L("查看转录内容")) { app.openTranscript(rec) }
        }

        Divider()

        if app.isTrashed(rec) {
            Button(L("从手机回收站还原")) { Task { await app.restoreFromTrash(rec) } }
        } else {
            Button(L("移到手机回收站…"), role: .destructive) {
                onTrashTap()
            }
        }

        Button(L("下载这个…")) { Task { await app.downloadOne(rec) } }

        if isPicked {
            Button(L("取消勾选")) { app.deselect(rec) }
        } else {
            Button(L("勾选")) { app.select(rec) }
        }

        if let p = app.libraryPath(for: rec) {
            Button(L("在访达中显示音频")) { NSWorkspace.shared.activateFileViewerSelecting([p]) }
        }
    }
}

// MARK: - 应用内阅读转录

struct TranscriptReaderView: View {
    @EnvironmentObject private var app: AppState
    let doc: TranscriptDocument

    /// 复制路径后的短暂回执，图标变成对勾一会儿
    @State private var copiedPath = false

    var body: some View {
        VStack(spacing: 0) {
            headerBar
            Divider()
            metaBar
            Divider()
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 14) {
                    ForEach(doc.segments) { seg in
                        VStack(alignment: .leading, spacing: 4) {
                            HStack(spacing: 8) {
                                if let spk = seg.speaker {
                                    Text(L("说话人 {0}", spk))
                                        .font(.system(size: 12, weight: .semibold))
                                        .foregroundStyle(Color.accentColor)
                                } else {
                                    Text(L("未分离"))
                                        .font(.system(size: 12))
                                        .foregroundStyle(.tertiary)
                                }
                                Text("\(seg.start) → \(seg.end)")
                                    .font(.system(size: 12).monospacedDigit())
                                    .foregroundStyle(.tertiary)
                            }
                            Text(seg.text)
                                .font(.system(size: 13))
                                .lineSpacing(3)
                                .textSelection(.enabled)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                .padding(.horizontal, Metrics.rowPaddingH)
                .padding(.vertical, 12)
            }
            .frame(maxHeight: .infinity)
            Divider()
            footerBar
        }
    }

    private var headerBar: some View {
        HStack(spacing: 8) {
            Button { app.closeTranscript() } label: {
                Image(systemName: "chevron.left")
                    .font(.system(size: 12, weight: .semibold))
            }
            .buttonStyle(.plain)
            .help(L("返回列表"))

            VStack(alignment: .leading, spacing: 1) {
                Text(doc.title)
                    .font(.system(size: 13, weight: .semibold))
                    .lineLimit(1)
                if let rec = app.recordings.first(where: { $0.id == doc.remotePath }),
                   let s = app.transcript(for: rec)?.summary {
                    Text(s)
                        .font(.system(size: 12))
                        .foregroundStyle(.tertiary)
                        .lineLimit(1)
                } else {
                    Text(L("转录内容"))
                        .font(.system(size: 12))
                        .foregroundStyle(.secondary)
                }
            }

            Spacer(minLength: 4)

            Button {
                // 完整路径含文件名，粘到终端 / 对话框里直接能用
                let pb = NSPasteboard.general
                pb.clearContents()
                pb.setString(doc.txtURL.path, forType: .string)
                copiedPath = true
                Task {
                    try? await Task.sleep(nanoseconds: 1_600_000_000)
                    copiedPath = false
                }
            } label: {
                Image(systemName: copiedPath ? "checkmark" : "doc.on.doc")
                    .font(.system(size: 12))
            }
            .buttonStyle(.plain)
            .foregroundStyle(.secondary)
            .help(L("复制转录文件的完整路径"))

            Button {
                NSWorkspace.shared.activateFileViewerSelecting([doc.txtURL])
            } label: {
                Image(systemName: "folder")
                    .font(.system(size: 12))
            }
            .buttonStyle(.plain)
            .foregroundStyle(.secondary)
            .help(L("在访达中显示 TXT"))
        }
        .padding(.horizontal, Metrics.rowPaddingH)
        .padding(.vertical, 8)
    }

    private var metaBar: some View {
        HStack(spacing: 10) {
            if !doc.duration.isEmpty { Label(doc.duration, systemImage: "clock") }
            if !doc.model.isEmpty { Text(doc.model).lineLimit(1) }
            Spacer()
            Text(L("{0} 句", doc.segments.count))
        }
        .font(.system(size: 12))
        .labelStyle(.titleAndIcon)
        .foregroundStyle(.secondary)
        .padding(.horizontal, Metrics.rowPaddingH)
        .padding(.vertical, 5)
    }

    private var footerBar: some View {
        HStack(spacing: 8) {
            Text(L("识别可能有误，请对照原音核对"))
                .font(.system(size: 12))
                .foregroundStyle(.tertiary)
            Spacer(minLength: 4)
            Button(L("用其他程序打开")) { NSWorkspace.shared.open(doc.txtURL) }
                .buttonStyle(.plain)
                .font(.system(size: 12))
        }
        .padding(.horizontal, Metrics.rowPaddingH)
        .padding(.vertical, 8)
    }
}

// MARK: - 删除确认

/// 一键转录全部是长任务，动手前先说清楚会跑多久、产出在哪
enum BulkConfirm {
    @MainActor
    static func transcribeAll(app: AppState) {
        let targets = app.untranscribed
        guard !targets.isEmpty else { return }

        let alert = NSAlert()
        alert.alertStyle = .informational
        alert.messageText = L("转录全部 {0} 条未转录的录音？", targets.count)
        alert.informativeText = L("会按列表顺序逐条处理，每条大约 7 秒到 40 秒不等。\n过程中面板可以关掉，任务在后台继续。\n转录结果存在「转录文件夹」，随时能回来读。")
        alert.addButton(withTitle: L("开始转录"))
        alert.addButton(withTitle: L("取消"))

        if alert.runModal() == .alertFirstButtonReturn {
            Task { await app.transcribeAllRemaining() }
        }
    }
}

/// 用 NSAlert 而不是 SwiftUI .alert：popover 里 SwiftUI 弹层容易失焦或被 popover 吞掉。
enum TrashConfirm {

    @MainActor
    static func present(for recs: [Recording], app: AppState) {
        guard !recs.isEmpty else { return }

        let alert = NSAlert()
        alert.alertStyle = .warning
        alert.messageText = recs.count == 1
            ? L("把「{0}」移到手机回收站？", recs[0].subject)
            : L("把 {0} 条录音移到手机回收站？", recs.count)

        var detail = L("会移到手机自带的「最近删除」，")
        detail += L("在手机的「文件」或「图库」里可以恢复，不会永久删除。\n")
        if recs.count == 1 {
            let r = recs[0]
            detail += "\n\(r.subject) · \(r.kind.label) · \(r.sizeText)"
            if app.isDownloaded(r) || app.transcript(for: r) != nil {
                detail += L("\n本机已有副本，可选择一并删除。")
            }
        } else {
            let total = recs.reduce(Int64(0)) { $0 + $1.size }
            detail += L("\n合计 {0}", FileWeaveFormat.bytes(total))
        }
        alert.informativeText = detail
        alert.addButton(withTitle: L("移到回收站"))
        alert.addButton(withTitle: L("取消"))
        alert.buttons.first?.hasDestructiveAction = true

        if alert.runModal() == .alertFirstButtonReturn {
            let alsoLocal = recs.count == 1
                && (app.isDownloaded(recs[0]) || app.transcript(for: recs[0]) != nil)
                && LocalDeleteConfirm.askIfNeeded()
            Task { await app.trashSelectedIf(recs, alsoRemoveLocal: alsoLocal) }
        }
    }
}

/// 本机副本是否一起删，单独确认一次，避免误删已转录的成果
enum LocalDeleteConfirm {
    static func askIfNeeded() -> Bool {
        let alert = NSAlert()
        alert.alertStyle = .warning
        alert.messageText = L("同时删除本机的音频和转录文本？")
        alert.informativeText = L("选择「保留」会只动手机，本机副本留着。转录的文字比较贵重，建议保留。")
        alert.addButton(withTitle: L("保留本机副本"))
        alert.addButton(withTitle: L("一并删除"))
        alert.buttons.last?.hasDestructiveAction = true
        return alert.runModal() == .alertSecondButtonReturn
    }
}

// MARK: - 进度环

struct ProgressRing: View {
    var progress: Double

    var body: some View {
        ZStack {
            Circle()
                .stroke(Color.secondary.opacity(0.25), lineWidth: 1.5)
            Circle()
                .trim(from: 0, to: max(0.02, min(1, progress)))
                .stroke(Color.accentColor,
                        style: StrokeStyle(lineWidth: 1.5, lineCap: .round))
                .rotationEffect(.degrees(-90))
        }
        .frame(width: 12, height: 12)
    }
}
