import SwiftUI
import AppKit
import Combine

private struct WorkspaceOverviewVisibleKey: EnvironmentKey { static let defaultValue = false }
extension EnvironmentValues {
    var fileWeaveOverviewVisible: Bool {
        get { self[WorkspaceOverviewVisibleKey.self] }
        set { self[WorkspaceOverviewVisibleKey.self] = newValue }
    }
}

public extension Notification.Name {
    static let fileWeaveShowDevice = Notification.Name("FileWeave.showDevice")
    static let fileWeaveFind = Notification.Name("FileWeave.find")
    static let fileWeaveRefresh = Notification.Name("FileWeave.refresh")
    static let fileWeaveSelectAll = Notification.Name("FileWeave.selectAll")
}

/// Shared by both hosts: opening a window never creates another directory/selection/task.
@MainActor
public final class WorkspaceSession: ObservableObject {
    @Published var page: WorkspacePage? = .files
    @Published var showsHistory = false
    let files = SharedFilesSession()
    let inbox = InboxSession()
    let screenshots = ScreenshotsSession()
    let clipboard = PhoneClipboardSession()
    private var previewCleanup: Task<Void, Never>?
    public init() {
        previewCleanup = Task {
            while !Task.isCancelled {
                FilePreviewStore.sweep(root: FilePreviewStore.root, before: Date().addingTimeInterval(-3600))
                do { try await Task.sleep(nanoseconds: 60_000_000_000) } catch { return }
            }
        }
        #if DEBUG
        if DebugPreview.isEnabled, ProcessInfo.processInfo.environment["SNAPSHOT_DEEP_PATH"] == "1" {
            files.rootElided = true
            files.path = ["Internal shared storage", "Documents", "Research notes", "A long current folder name from the research and product planning meeting"]
        }
        #endif
    }
    deinit { previewCleanup?.cancel() }
    #if DEBUG
    public var debugSelectedFileCount: Int { files.selectedIDs.count }
    public func setPreviewDestination(_ name: String) {
        switch name {
        case "screenshots": page = .screenshots
        case "clipboard": page = .clipboard
        case "recordings", "transcript": page = .recordings
        case "inbox", "transfers": page = .transfers
        case "device", "storage": page = .device; showsHistory = name == "storage"
        default: page = .files
        }
    }
    #endif
    public func showFiles() { if page != .files { page = .files } }
    public func refresh(app: AppState) {
        switch page ?? .files {
        case .files: files.refreshID = UUID()
        case .transfers: inbox.refreshID = UUID()
        case .screenshots: screenshots.refreshID = UUID()
        case .clipboard: Task { await clipboard.refresh() }
        case .recordings, .device: Task { await app.userRefresh() }
        }
    }
    public func selectAll(app: AppState) {
        switch page ?? .files {
        case .files: files.selectedIDs = Set(files.filteredItems.map(\.id))
        case .recordings: app.selectAll()
        case .transfers: inbox.selection = Set(inbox.files.filter { inbox.query.isEmpty || $0.lastPathComponent.localizedCaseInsensitiveContains(inbox.query) })
        case .screenshots: screenshots.selection = Set(screenshots.visible.map(\.id))
        case .clipboard, .device: break
        }
    }
}

enum WorkspacePage: Hashable {
    case files, screenshots, recordings, transfers, clipboard, device
    var title: String {
        switch self {
        case .files: return L("手机文件")
        case .screenshots: return L("手机截图")
        case .clipboard: return L("手机剪贴板")
        case .recordings: return L("录音与转录")
        case .transfers: return L("收件箱与任务")
        case .device: return L("设备与设置")
        }
    }
}

/// Optional larger window, with the same work session as the menu-bar panel.
public struct FileWeavePanelView: View {
    @EnvironmentObject private var app: AppState
    @ObservedObject private var bridge: BridgeProcess
    @ObservedObject private var language = AppLanguage.shared
    @ObservedObject private var workspace: WorkspaceSession

    public init(bridge: BridgeProcess, workspace: WorkspaceSession? = nil, showsRecordings: Bool = false, showsStorageHistory: Bool = false, showsTransfers: Bool = false, showsScreenshots: Bool = false) {
        self.bridge = bridge
        let model = workspace ?? WorkspaceSession()
        if showsScreenshots { model.page = .screenshots }
        else if showsStorageHistory { model.page = .device; model.showsHistory = true }
        else if showsTransfers { model.page = .transfers }
        else if showsRecordings { model.page = .recordings }
        _workspace = ObservedObject(wrappedValue: model)
    }
    public var body: some View {
        NavigationSplitView {
            List(selection: $workspace.page) {
                Section(L("工作区")) {
                    Label(L("手机文件"), systemImage: "folder").tag(WorkspacePage.files)
                    Label(L("手机截图"), systemImage: "photo.on.rectangle").tag(WorkspacePage.screenshots)
                    Label(L("录音"), systemImage: "waveform").tag(WorkspacePage.recordings)
                    Label(L("收件箱"), systemImage: "tray.and.arrow.down").tag(WorkspacePage.transfers)
                    Label(L("手机剪贴板"), systemImage: "clipboard").tag(WorkspacePage.clipboard)
                }
                Section(L("连接")) {
                    Label(L("设备与设置"), systemImage: "iphone").tag(WorkspacePage.device)
                }
            }
            .listStyle(.sidebar)
            .navigationSplitViewColumnWidth(min: 190, ideal: 210, max: 280)
            .safeAreaInset(edge: .bottom) {
                VStack(alignment: .leading, spacing: 6) {
                    Label(app.link.title, systemImage: app.link.isReady ? "checkmark.circle.fill" : "iphone.slash")
                        .font(.callout).lineLimit(1)
                    Text(bridge.statusLine).font(.caption).foregroundStyle(.secondary).lineLimit(2)
                }.padding(16).frame(maxWidth: .infinity, alignment: .leading)
            }
        } detail: {
            WorkspaceContentView(bridge: bridge, workspace: workspace)
                .navigationTitle((workspace.page ?? .files).title)
                .toolbar {
                    ToolbarItemGroup(placement: .primaryAction) {
                        Button { workspace.page = .screenshots } label: { Label(L("手机截图"), systemImage: "photo.on.rectangle") }
                            .help(L("手机截图"))
                        Button { workspace.page = .clipboard } label: { Label(L("手机剪贴板"), systemImage: "clipboard") }
                            .help(L("手机剪贴板"))
                        Button { workspace.refresh(app: app) } label: { Label(L("刷新"), systemImage: "arrow.clockwise") }
                            .help(L("刷新 · ⌘R"))
                        Menu {
                            Button(L("共享给手机的文件")) { bridge.openShared() }
                            Button(L("Mac 收件箱")) { bridge.openInbox() }
                            Divider()
                            Button(L("查看服务日志")) { bridge.openLog() }
                        } label: { Label(L("更多"), systemImage: "ellipsis.circle") }
                    }
                }
        }
        .navigationSplitViewStyle(.balanced)
        .frame(minWidth: 780, minHeight: 480)
        .background(Color(nsColor: .windowBackgroundColor))
        .environment(\.locale, language.locale)
        .onChange(of: bridge.highlightID) { _, _ in workspace.page = .files }
        .onAppear { app.notifyPanelOpened() }
        .id(language.code)
    }
}

/// Left-click is a full work surface. Its bounded content area is filled by the
/// active browser/list; there is no reserved shortcut-only band above or below it.
public struct FileWeavePopoverView: View {
    @EnvironmentObject private var app: AppState
    @ObservedObject private var bridge: BridgeProcess
    @ObservedObject private var language = AppLanguage.shared
    @ObservedObject private var workspace: WorkspaceSession
    @ObservedObject private var files: SharedFilesSession
    @ObservedObject private var inbox: InboxSession
    @ObservedObject private var screenshots: ScreenshotsSession
    @ObservedObject private var visibility = FileVisibilitySettings.shared
    private let openWindow: () -> Void
    private let panelSize: CGSize?
    public init(bridge: BridgeProcess, workspace: WorkspaceSession, panelSize: CGSize? = nil, openWindow: @escaping () -> Void) {
        self.bridge = bridge; self.workspace = workspace; self.openWindow = openWindow; self.panelSize = panelSize
        _files = ObservedObject(wrappedValue: workspace.files)
        _inbox = ObservedObject(wrappedValue: workspace.inbox)
        _screenshots = ObservedObject(wrappedValue: workspace.screenshots)
    }
    public var body: some View {
        VStack(spacing: 0) {
            overview
            Divider()
            if workspace.page != .files {
                HStack(spacing: 12) {
                    Button { workspace.showFiles() } label: { Label(L("返回文件"), systemImage: "chevron.left") }
                        .buttonStyle(.borderless).keyboardShortcut("[", modifiers: .command)
                    Text((workspace.page ?? .files).title).font(.headline)
                    Spacer()
                }.padding(.horizontal, 16).padding(.vertical, 10)
                Divider()
            }
            WorkspaceContentView(bridge: bridge, workspace: workspace)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            if workspace.page != .files {
            Divider()
            HStack(spacing: 6) {
                Image(systemName: app.link.isReady ? "iphone.gen3.radiowaves.left.and.right" : "iphone.slash")
                    .foregroundStyle(app.link.isReady ? Color.accentColor : .secondary)
                Text(app.link.title).lineLimit(1).truncationMode(.middle)
                Text(app.link.isReady ? L("已连接") : bridge.statusLine).foregroundStyle(.secondary).lineLimit(1)
                Spacer(minLength: 0)
                Button { workspace.page = .device } label: { Image(systemName: "info.circle") }
                    .buttonStyle(.borderless).help(L("设备与设置")).accessibilityLabel(L("设备与设置"))
            }
            .font(.caption).padding(.horizontal, 16).padding(.vertical, 8)
            }
        }
        .frame(maxWidth: panelSize?.width ?? Metrics.panelWidth)
        .frame(height: panelSize?.height ?? panelContentHeight)
        .background(Color(nsColor: .windowBackgroundColor))
        .environment(\.locale, language.locale)
        .environment(\.fileWeaveOverviewVisible, true)
        .onReceive(NotificationCenter.default.publisher(for: .fileWeaveShowDevice)) { _ in workspace.page = .device }
        .onChange(of: bridge.highlightID) { _, _ in workspace.showFiles() }
        .onAppear { app.notifyPanelOpened() }
        .task {
            workspace.inbox.reload()
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                if !Task.isCancelled { workspace.inbox.reload() }
            }
        }
        .id(language.code)
    }

    /// NSPopover measures the List's intrinsic content size. A maximum alone
    /// permits a zero-height work area; reserve the bounded panel height explicitly.
    private var panelContentHeight: CGFloat {
        #if DEBUG
        if let value = Double(ProcessInfo.processInfo.environment["SNAPSHOT_HEIGHT"] ?? ""), value > 0 {
            return min(CGFloat(value), Metrics.panelHeight)
        }
        #endif
        return Metrics.panelHeight
    }

    private var overview: some View {
        VStack(alignment: .leading, spacing: 8) {
            ViewThatFits(in: .horizontal) {
                workspaceToolbar(compactSearch: false, includesActivity: true)
                VStack(alignment: .leading, spacing: 8) {
                    workspaceToolbar(compactSearch: false, includesActivity: false)
                    activityStrip
                }
                VStack(alignment: .leading, spacing: 8) {
                    workspaceToolbar(compactSearch: true, includesActivity: false)
                    activityStrip
                }
            }
            if app.activeTaskTitle != nil || app.errorText != nil || app.infoText != nil { taskStatus }
        }
        .padding(.horizontal, 16).padding(.vertical, 12)
    }

    private var workspaceSearch: Binding<String> {
        Binding(get: {
            switch workspace.page ?? .files {
            case .files: workspace.files.search
            case .recordings: app.searchText
            case .transfers: workspace.inbox.query
            case .screenshots: workspace.screenshots.query
            case .clipboard, .device: ""
            }
        }, set: { value in
            switch workspace.page ?? .files {
            case .files: workspace.files.search = value
            case .recordings: app.searchText = value
            case .transfers: workspace.inbox.query = value
            case .screenshots: workspace.screenshots.query = value
            case .clipboard, .device: break
            }
        })
    }

    private var searchPlaceholder: String {
        switch workspace.page ?? .files {
        case .files: L("搜索当前目录")
        case .recordings: L("搜索号码、姓名或转录内容")
        case .transfers: L("搜索收件箱")
        case .screenshots: L("搜索手机截图")
        case .clipboard, .device: ""
        }
    }

    private func workspaceToolbar(compactSearch: Bool, includesActivity: Bool) -> some View {
        HStack(spacing: 8) {
            if workspace.page != .device && workspace.page != .clipboard {
                NativeSearchField(text: workspaceSearch, placeholder: searchPlaceholder, onCommit: {
                    if workspace.page == .recordings { app.commitSearch() }
                })
                .frame(minWidth: compactSearch ? 120 : 140, idealWidth: 200, maxWidth: .infinity)
                .frame(height: 24)
            } else { Spacer(minLength: 0) }
            if includesActivity {
                activityButton(title: L("截图"), count: screenshots.count, symbol: "photo.on.rectangle", detail: L("手机截图")) { workspace.page = .screenshots }
                activityButton(title: L("剪贴板"), count: nil, symbol: "clipboard", detail: L("手机剪贴板")) { workspace.page = .clipboard }
                activityButton(title: L("录音"), count: app.link.isReady && app.lastScanAt != nil ? app.recordings.count : nil, symbol: "waveform",
                    detail: recordingOverviewDetail) { workspace.page = .recordings }
                activityButton(title: L("收件箱"), count: workspace.inbox.overviewCount, symbol: "tray.and.arrow.down",
                    detail: inboxOverviewDetail) { workspace.page = .transfers }
            }
            Button { workspace.refresh(app: app) } label: {
                Image(systemName: "arrow.clockwise").font(.system(size: 15)).frame(width: 28, height: 28)
            }
                .buttonStyle(.borderless).help(L("刷新 · ⌘R")).accessibilityLabel(L("刷新"))
            workspaceMenu
        }
        .controlSize(.regular)
    }

    private var workspaceMenu: some View {
        Menu {
            Button(L("在独立窗口中打开"), action: openWindow)
            if workspace.page == .files {
                Picker(L("文件范围"), selection: Binding(get: { files.selected }, set: { value in
                    files.selected = value
                    files.path = []; files.selectedIDs = []; files.search = ""
                    files.refreshID = UUID()
                })) {
                    Text(L("全部文件")).tag(false)
                    Text(L("刚刚共享")).tag(true)
                }
            }
            if workspace.page == .files || workspace.page == .transfers {
                Toggle(L("显示隐藏文件"), isOn: $visibility.showsHiddenFiles)
            }
            Divider()
            Button(L("设备与设置")) { workspace.page = .device }
            Button(L("手机截图")) { workspace.page = .screenshots }
            Button(L("手机剪贴板")) { workspace.page = .clipboard }
            Button(L("录音与转录")) { workspace.page = .recordings }
            Button(L("Mac 收件箱")) { workspace.page = .transfers }
            Divider()
            Button(L("共享给手机的文件")) { bridge.openShared() }
            Button(L("在访达中打开 Mac 收件箱")) { bridge.openInbox() }
            Button(L("查看服务日志")) { bridge.openLog() }
        } label: { Image(systemName: "ellipsis.circle").font(.system(size: 15)).frame(width: 28, height: 28) }
        .menuStyle(.borderlessButton).menuIndicator(.hidden).fixedSize()
        .help(L("更多")).accessibilityLabel(L("更多"))
    }

    private var recordingOverviewDetail: String {
        guard app.lastScanAt != nil else { return app.scanning || app.link.isReady ? L("正在读取录音…") : L("连接后读取录音") }
        let latest = app.recordings.first?.subject ?? L("没有录音")
        return app.link.isReady ? latest : L("上次记录：{0}", latest)
    }

    @ViewBuilder private var taskStatus: some View {
        if let title = app.activeTaskTitle {
            HStack(spacing: 8) {
                ProgressView().controlSize(.small)
                Text(title).lineLimit(2).help(title)
                Spacer(minLength: 0)
                if app.fileTransferTitle != nil { Button(L("取消")) { app.cancelFileTransfer() }.buttonStyle(.borderless) }
            }.font(.callout)
        } else if let error = app.errorText {
            Label(LocalizedText.refresh(error), systemImage: "exclamationmark.triangle")
                .font(.caption).foregroundStyle(.red).lineLimit(2).help(error)
                .frame(maxWidth: .infinity, alignment: .leading)
        } else {
            Text(app.infoText ?? (app.link.isReady ? L("已连接") : bridge.statusLine))
                .font(.caption).foregroundStyle(.secondary).lineLimit(2)
                .help(app.infoText ?? bridge.statusLine)
        }
    }

    private var inboxOverviewDetail: String {
        workspace.inbox.loadError == nil
            ? (workspace.inbox.hasLoaded ? (workspace.inbox.latestFile?.lastPathComponent ?? L("还没有收到文件")) : L("正在读取收件箱…"))
            : L("收件箱暂不可用")
    }

    private var activityStrip: some View {
        HStack(spacing: 12) {
            activityButton(title: L("截图"), count: screenshots.count, symbol: "photo.on.rectangle", detail: L("手机截图")) { workspace.page = .screenshots }
            activityButton(title: L("剪贴板"), count: nil, symbol: "clipboard", detail: L("手机剪贴板")) { workspace.page = .clipboard }
            activityButton(title: L("录音"), count: app.link.isReady && app.lastScanAt != nil ? app.recordings.count : nil, symbol: "waveform",
                detail: recordingOverviewDetail) { workspace.page = .recordings }
            activityButton(title: L("收件箱"), count: workspace.inbox.overviewCount, symbol: "tray.and.arrow.down",
                detail: inboxOverviewDetail) { workspace.page = .transfers }
            Spacer(minLength: 0)
        }
    }

    private func activityButton(title: String, count: Int?, symbol: String, detail: String,
                                action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 5) {
                Image(systemName: symbol).font(.system(size: 15)).frame(width: 20, height: 20)
                Text(title)
                if let count { Text("\(count)").monospacedDigit().foregroundStyle(.secondary) }
            }.font(.system(size: 12)).frame(minHeight: 28).fixedSize().contentShape(Rectangle())
        }.buttonStyle(.borderless).help("\(title) · \(detail)").accessibilityLabel(title)
    }

}

private struct WorkspaceContentView: View {
    @ObservedObject var bridge: BridgeProcess
    @ObservedObject var workspace: WorkspaceSession
    var body: some View {
        Group {
            switch workspace.page ?? .files {
            case .files: SharedFilesView(bridge: bridge, session: workspace.files)
            case .screenshots: ScreenshotsView(session: workspace.screenshots, files: workspace.files)
            case .clipboard: PhoneClipboardView(session: workspace.clipboard)
            case .recordings: RecorderMenuView()
            case .transfers: TransferWorkspaceView(bridge: bridge, session: workspace.inbox)
            case .device: DeviceWorkspaceView(bridge: bridge, showsHistory: $workspace.showsHistory)
            }
        }
    }
}

@MainActor
final class InboxSession: ObservableObject {
    private let root: URL
    private let visibility: FileVisibilitySettings
    private var visibilityObserver: AnyCancellable?
    init(root: URL = MacPaths.state.appendingPathComponent("inbox", isDirectory: true), visibility: FileVisibilitySettings = .shared) {
        self.root = root; self.visibility = visibility
        visibilityObserver = visibility.$showsHiddenFiles.dropFirst().sink { [weak self] _ in
            DispatchQueue.main.async { self?.reload() }
        }
    }
    @Published var files: [URL] = []
    @Published var selection: Set<URL> = []
    @Published var query = ""
    @Published var loadError: String?
    @Published var latestFile: URL?
    @Published var hasLoaded = false
    var overviewCount: Int? { hasLoaded && loadError == nil ? files.count : nil }
    @Published var refreshID = UUID()
    func reload() {
        if DebugPreview.isEnabled {
            if !files.isEmpty { files = [] }
            latestFile = nil; loadError = nil; hasLoaded = true
            return
        }
        do {
            let values = FileManager.default.fileExists(atPath: root.path)
                ? try FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: [.contentModificationDateKey, .isHiddenKey],
                    options: visibility.showsHiddenFiles ? [] : .skipsHiddenFiles)
                    .filter { url in
                        !FileVisibilitySettings.isInboxStagingName(url.lastPathComponent)
                            && FileVisibilitySettings.isVisible(name: url.lastPathComponent, showsHiddenFiles: visibility.showsHiddenFiles)
                            && (visibility.showsHiddenFiles || (try? url.resourceValues(forKeys: [.isHiddenKey]).isHidden) != true)
                    }
                : []
            let sorted = values.sorted { $0.lastPathComponent.localizedStandardCompare($1.lastPathComponent) == .orderedAscending }
            let latest = values.max {
                ((try? $0.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast)
                    < ((try? $1.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast)
            }
            if files != sorted { files = sorted }
            if latestFile != latest { latestFile = latest }
            let remaining = selection.intersection(Set(sorted))
            if remaining != selection { selection = remaining }
            if loadError != nil { loadError = nil }
            if !hasLoaded { hasLoaded = true }
        } catch { loadError = error.localizedDescription }
    }
}

private struct DeviceWorkspaceView: View {
    @EnvironmentObject private var app: AppState
    @ObservedObject var bridge: BridgeProcess
    @ObservedObject private var language = AppLanguage.shared
    @ObservedObject private var login = LoginItem.shared
    @ObservedObject private var visibility = FileVisibilitySettings.shared
    @Binding var showsHistory: Bool
    @State private var showsStorage = false
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                VStack(alignment: .leading, spacing: 8) {
                    Label(app.link.title, systemImage: "iphone").font(.title2.weight(.semibold))
                    Text(app.linkHint ?? L("已配对设备将在同一网络内自动连接。"))
                        .foregroundStyle(.secondary).textSelection(.enabled)
                    Text(bridge.statusLine).font(.callout).foregroundStyle(.secondary)
                    Button(L("重新寻找手机")) { Task { await app.userRefresh() } }
                }
                Divider()
                VStack(alignment: .leading, spacing: 12) {
                    Text(L("配对与共享")).font(.headline)
                    Text(L("让手机与 Mac 连接同一网络并保持 FileWeave 运行。自动发现后，核对两端弹出的配对码，再在 Mac 上批准请求。"))
                        .foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                    HStack {
                        Button(L("共享给手机的文件")) { bridge.openShared() }
                        Button(L("Mac 收件箱")) { bridge.openInbox() }
                        Button(L("查看服务日志")) { bridge.openLog() }
                    }
                }
                Divider()
                DisclosureGroup(isExpanded: $showsStorage) {
                    VStack(spacing: 16) {
                        PhoneStorageHeader(showsHistory: $showsHistory)
                        if showsHistory { PhoneStorageHistoryView().frame(minHeight: 320) }
                        else { PhoneStorageDayTrendView().frame(height: 330) }
                    }.padding(.top, 12)
                } label: { Label(L("手机存储与容量历史"), systemImage: "internaldrive").font(.headline) }
                Divider()
                VStack(alignment: .leading, spacing: 16) {
                    Text(L("设置")).font(.headline)
                    Picker(L("界面语言"), selection: $language.code) {
                        Text("English").tag("en")
                        Text("简体中文").tag("zh-Hans")
                    }.pickerStyle(.menu).frame(maxWidth: 300, alignment: .leading)
                    Toggle(L("显示隐藏文件"), isOn: $visibility.showsHiddenFiles)
                        .help(L("在手机文件和 Mac 收件箱中显示以点开头的项目。"))
                    Toggle(L("登录时启动"), isOn: Binding(get: { login.isEnabled }, set: { login.setEnabled($0) }))
                        .disabled(!login.isAvailable)
                    if let error = login.lastError { Label(LocalizedText.refresh(error), systemImage: "exclamationmark.triangle").foregroundStyle(.red) }
                    if let reason = app.asrUnavailableReason {
                        Label(reason, systemImage: "waveform.badge.exclamationmark").font(.callout).foregroundStyle(.secondary)
                    }
                }
            }
            .padding(24).frame(maxWidth: 720, alignment: .leading).frame(maxWidth: .infinity, alignment: .leading)
        }
        .onAppear { showsStorage = showsHistory }
    }
}

private struct TransferWorkspaceView: View {
    @Environment(\.fileWeaveOverviewVisible) private var overviewVisible
    @EnvironmentObject private var app: AppState
    @ObservedObject var bridge: BridgeProcess
    @ObservedObject var session: InboxSession
    @ObservedObject private var visibility = FileVisibilitySettings.shared
    private var files: [URL] { get { session.files } nonmutating set { session.files = newValue } }
    private var selection: Set<URL> { get { session.selection } nonmutating set { session.selection = newValue } }
    private var query: String { get { session.query } nonmutating set { session.query = newValue } }
    private var loadError: String? { get { session.loadError } nonmutating set { session.loadError = newValue } }
    @FocusState private var searchFocused: Bool
    @StateObject private var preview = FilePreviewModel()
    @State private var previewAllowed = true
    @State private var previewRevision = UUID()
    private var previewURLs: [URL] { visible.filter { selection.contains($0) } }
    private var previewFiles: [PhoneFile] {
        previewURLs.map { url in
            let values = try? url.resourceValues(forKeys: [.isDirectoryKey, .fileSizeKey])
            return PhoneFile(name: url.lastPathComponent, directory: values?.isDirectory ?? false, size: Int64(values?.fileSize ?? 0))
        }
    }
    private var visible: [URL] { files.filter { query.isEmpty || $0.lastPathComponent.localizedCaseInsensitiveContains(query) } }
    var body: some View {
        VStack(spacing: 0) {
            if !overviewVisible { HStack {
                if let task = app.activeTaskTitle { ProgressView().controlSize(.small); Text(task) }
                else { Label(L("没有正在进行的任务"), systemImage: "checkmark.circle").foregroundStyle(.secondary) }
                Spacer()
                if app.fileTransferTitle != nil { Button(L("取消下载")) { app.cancelFileTransfer() } }
                Button(L("在访达中打开")) { bridge.openInbox() }
            }.padding(16) }
            if !overviewVisible, let message = app.errorText ?? app.infoText {
                Text(message).font(.callout).foregroundStyle(app.errorText == nil ? Color.secondary : .red)
                    .textSelection(.enabled).frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 16).padding(.bottom, 12)
            }
            if !overviewVisible { HStack {
                NativeSearchField(text: $session.query, placeholder: L("搜索收件箱"))
                    .frame(height: 24)
                Menu {
                    Toggle(L("显示隐藏文件"), isOn: $visibility.showsHiddenFiles)
                } label: { Image(systemName: "line.3.horizontal.decrease") }
                .menuStyle(.borderlessButton).menuIndicator(.hidden).fixedSize().help(L("显示"))
            }.padding(.horizontal, 16).padding(.vertical, 12) }
            if !overviewVisible { Divider() }
            GeometryReader { geometry in
            HStack(spacing: 0) {
            Group {
            if let error = loadError {
                ContentUnavailableView { Label(L("无法读取收件箱"), systemImage: "exclamationmark.triangle") }
                description: { Text(error) } actions: { Button(L("重试")) { reload() } }
            } else if visible.isEmpty {
                ContentUnavailableView {
                    Label(query.isEmpty ? L("收件箱为空") : L("没有匹配的文件"), systemImage: query.isEmpty ? "tray" : "magnifyingglass")
                } description: { Text(L("从手机发送文件，或在“手机文件”中下载，文件会显示在这里。")) }
            } else {
                List(selection: $session.selection) {
                    ForEach(visible, id: \.self) { url in
                    HStack(spacing: 12) {
                        Image(nsImage: NSWorkspace.shared.icon(forFile: url.path)).resizable().frame(width: 24, height: 24)
                        Text(url.lastPathComponent).lineLimit(1).truncationMode(.middle)
                        Spacer()
                    }.padding(.vertical, 5)
                    .contentShape(Rectangle())
                    .fileWeaveListRow(isSelected: selection.contains(url))
                    .listRowInsets(EdgeInsets(top: 2, leading: 16, bottom: 2, trailing: 16))
                    // A List-native provider preserves native click/range selection.
                    .itemProvider { NSItemProvider(object: url as NSURL) }
                    .tag(url)
                    }
                }
                .contextMenu(forSelectionType: URL.self) { urls in
                    if !urls.isEmpty {
                        Button(L("打开")) { for url in urls { NSWorkspace.shared.open(url) } }
                        Button(L("在访达中显示")) { NSWorkspace.shared.activateFileViewerSelecting(Array(urls)) }
                    }
                } primaryAction: { urls in
                    for url in urls { NSWorkspace.shared.open(url) }
                }
                .listStyle(.plain)
                .background(SelectionToggleObserver(ids: visible.map(\.path), selected: Set(selection.map(\.path))) { selection = [] })
            }
            }.frame(maxWidth: .infinity, maxHeight: .infinity)
            Divider()
            FilePreviewPane(model: preview, files: previewFiles, path: previewURLs.count == 1 ? previewURLs[0].path : "", emptyContext: .inbox, reload: { previewRevision = UUID() })
                .frame(width: min(340, max(180, geometry.size.width / 3)))
            }
            }
            Divider()
            HStack {
                Text(L("{0} 项", visible.count)).font(.caption).foregroundStyle(.secondary)
                Spacer()
                Button(L("刷新")) { reload() }
            }.padding(12)
        }
        .task(id: FilePreviewRequest(files: previewFiles, path: previewURLs.map(\.path), selected: false, source: nil, enabled: previewAllowed && loadError == nil, revision: previewRevision)) {
            await preview.load(file: previewAllowed && loadError == nil && previewFiles.count == 1 ? previewFiles.first : nil,
                               path: previewURLs.first?.path ?? "", source: nil, selected: false, localURL: previewURLs.first)
        }
        .onDisappear { preview.clear() }
        .onReceive(DistributedNotificationCenter.default().publisher(for: Notification.Name("com.apple.screenIsLocked"))) { _ in
            previewAllowed = false; preview.clear()
        }
        .onReceive(DistributedNotificationCenter.default().publisher(for: Notification.Name("com.apple.screenIsUnlocked"))) { _ in previewAllowed = true }
        .onReceive(NSWorkspace.shared.notificationCenter.publisher(for: NSWorkspace.sessionDidResignActiveNotification)) { _ in
            previewAllowed = false; preview.clear()
        }
        .onReceive(NSWorkspace.shared.notificationCenter.publisher(for: NSWorkspace.sessionDidBecomeActiveNotification)) { _ in previewAllowed = true }
        .onReceive(NSWorkspace.shared.notificationCenter.publisher(for: NSWorkspace.willSleepNotification)) { _ in
            previewAllowed = false; preview.clear()
        }
        .onReceive(NSWorkspace.shared.notificationCenter.publisher(for: NSWorkspace.didWakeNotification)) { _ in previewAllowed = true }
        .onChange(of: app.isPanelVisible) { _, visible in
            previewAllowed = visible
            if !visible { preview.clear() }
        }
        .onReceive(NotificationCenter.default.publisher(for: .fileWeaveFind)) { _ in searchFocused = true }
        .onAppear { reload() }
        .task(id: session.refreshID) { reload(); while !Task.isCancelled { try? await Task.sleep(nanoseconds: 1_000_000_000); if !Task.isCancelled { reload() } } }
    }
    private func reload() { session.reload() }
}
