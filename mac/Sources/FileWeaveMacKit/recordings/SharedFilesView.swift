import SwiftUI
import AppKit
import Combine

struct SharedFilesLoadToken: Equatable {
    let generation: UUID
    let path: [String]
    let selected: Bool
}

/// Owned by the controller's workspace so both hosts share directory and selection.
/// Only the latest request for the unchanged path/scope may commit a result.
final class SharedFilesSession: ObservableObject {
    let visibility: FileVisibilitySettings
    private var visibilityObserver: AnyCancellable?

    init(visibility: FileVisibilitySettings = .shared) {
        self.visibility = visibility
        visibilityObserver = visibility.$showsHiddenFiles.dropFirst().sink { [weak self] showsHidden in
            guard let self else { return }
            let visibleIDs = Set(self.items.filter { FileVisibilitySettings.isVisible(name: $0.name, showsHiddenFiles: showsHidden) }.map(\.id))
            self.selectedIDs.formIntersection(visibleIDs)
        }
    }

    var visibleItems: [PhoneFile] {
        items.filter { FileVisibilitySettings.isVisible(name: $0.name, showsHiddenFiles: visibility.showsHiddenFiles) }
    }
    var filteredItems: [PhoneFile] {
        visibleItems.filter { search.isEmpty || $0.name.localizedCaseInsensitiveContains(search) }
    }

    var lastHighlightID = ""
    private(set) var latestLoad: SharedFilesLoadToken?
    func beginLoad() -> SharedFilesLoadToken {
        let token = SharedFilesLoadToken(generation: UUID(), path: path, selected: selected)
        latestLoad = token
        return token
    }
    func accepts(_ token: SharedFilesLoadToken) -> Bool {
        latestLoad == token && path == token.path && selected == token.selected
    }
    @discardableResult
    func commit(_ token: SharedFilesLoadToken, items: [PhoneFile], effectivePath: [String], rootElided: Bool, enabled: Bool, version: Int64 = 0) -> Bool {
        guard accepts(token) else { return false }
        self.version = version
        self.items = items.sorted { a, b in a.directory != b.directory ? a.directory : a.name.localizedStandardCompare(b.name) == .orderedAscending }
        self.path = effectivePath; self.rootElided = rootElided; self.enabled = enabled
        selectedIDs.formIntersection(Set(visibleItems.map(\.id)))
        return true
    }
    @Published var version: Int64 = 0
    @Published var source: FileWeaveFolderSource?
    @Published var items: [PhoneFile] = []
    @Published var path: [String] = []
    @Published var rootElided = false
    @Published var selected = false
    @Published var selectedIDs: Set<String> = []
    @Published var enabled = true
    @Published var loading = false
    @Published var message: String?
    @Published var search = ""
    @Published var transfer: Task<Void, Never>?
    @Published var transferring = false
    @Published var refreshID = UUID()
}

struct SharedFilesView: View {
    @Environment(\.fileWeaveOverviewVisible) private var overviewVisible
    @ObservedObject var bridge: BridgeProcess
    @EnvironmentObject private var app: AppState
    private enum FocusTarget: Hashable { case idle, search }
    @FocusState private var focus: FocusTarget?
    @ObservedObject var session: SharedFilesSession
    @ObservedObject private var visibility = FileVisibilitySettings.shared
    @StateObject private var preview = FilePreviewModel()
    @State private var previewAllowed = true
    @State private var previewRevision = UUID()
    private var previewFiles: [PhoneFile] { filteredItems.filter { selectedIDs.contains($0.id) } }
    private var previewRequest: FilePreviewRequest {
        FilePreviewRequest(files: previewFiles, path: path, selected: selected,
                           source: source.map(ObjectIdentifier.init), enabled: enabled && previewAllowed, version: session.version, revision: previewRevision)
    }
    private var source: FileWeaveFolderSource? { get { session.source } nonmutating set { session.source = newValue } }
    private var items: [PhoneFile] { get { session.items } nonmutating set { session.items = newValue } }
    private var path: [String] { get { session.path } nonmutating set { session.path = newValue } }
    private var rootElided: Bool { get { session.rootElided } nonmutating set { session.rootElided = newValue } }
    private var selected: Bool { get { session.selected } nonmutating set { session.selected = newValue } }
    private var selectedIDs: Set<String> { get { session.selectedIDs } nonmutating set { session.selectedIDs = newValue } }
    private var enabled: Bool { get { session.enabled } nonmutating set { session.enabled = newValue } }
    private var loading: Bool { get { session.loading } nonmutating set { session.loading = newValue } }
    private var message: String? { get { session.message } nonmutating set { session.message = newValue } }
    private var search: String { get { session.search } nonmutating set { session.search = newValue } }
    private var transfer: Task<Void, Never>? { get { session.transfer } nonmutating set { session.transfer = newValue } }
    private var transferring: Bool { get { session.transferring } nonmutating set { session.transferring = newValue } }
    private var refreshID: UUID { get { session.refreshID } nonmutating set { session.refreshID = newValue } }

    var body: some View {
        VStack(spacing: 0) {
            Color.clear.frame(width: 1, height: 1).focusable().focusEffectDisabled()
                .focused($focus, equals: .idle).accessibilityHidden(true)
            if !overviewVisible { HStack(spacing: 8) {
                NativeSearchField(text: $session.search, placeholder: L("搜索当前目录"))
                    .frame(height: 24)
                Menu {
                Picker(L("文件范围"), selection: Binding(get: { selected }, set: { value in
                    selected = value; path = []; selectedIDs = []; search = ""; refreshID = UUID()
                })) {
                    Text(L("全部文件")).tag(false)
                    Text(L("刚刚共享")).tag(true)
                }
                .pickerStyle(.menu)
                .labelsHidden()
                .frame(width: 150)
                    Divider()
                    Toggle(L("显示隐藏文件"), isOn: $visibility.showsHiddenFiles)
                } label: { Image(systemName: "ellipsis.circle") }
                .menuStyle(.borderlessButton).menuIndicator(.hidden).fixedSize().help(L("显示"))
                Button { refreshID = UUID() } label: { Image(systemName: "arrow.clockwise") }
                    .buttonStyle(.borderless)
                    .help(L("刷新目录"))
                    .accessibilityLabel(L("刷新目录"))
            }
            .font(.system(size: 12))
            .controlSize(.regular)
            .padding(.horizontal, 16)
            .padding(.vertical, 7)
            }
            if path.count > (rootElided ? 1 : 0) { breadcrumbs }
            if !overviewVisible || path.count > (rootElided ? 1 : 0) { Divider() }
            GeometryReader { geometry in
            HStack(spacing: 0) {
            Group {
                if loading {
                    // 发现（秒级广播 + 逐台探测）和读取是两件事：
                    // 找不到手机时一直说「正在读取」会让人以为下一秒就出结果。
                    ProgressView(source == nil ? L("正在寻找手机…") : L("正在读取手机文件…"))
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else if session.visibleItems.isEmpty {
                    if source == nil {
                        disconnectedState
                    } else {
                        emptyDirectoryState
                    }
                } else {
                    if filteredItems.isEmpty {
                        ContentUnavailableView {
                            Label(L("没有匹配的文件"), systemImage: "magnifyingglass")
                        } description: {
                            Text(L("当前目录没有名称包含“{0}”的项目", search))
                        }
                    } else {
                        // Retain native List selection and keyboard navigation.
                        List(selection: $session.selectedIDs) {
                            ForEach(filteredItems) { item in
                            fileRow(item)
                                .tag(item.id)
                                .fileWeaveListRow(isSelected: selectedIDs.contains(item.id))
                                .listRowInsets(EdgeInsets(top: 2, leading: 16, bottom: 2, trailing: 16))
                            }
                        }
                        .contextMenu(forSelectionType: String.self) { ids in
                            let targets = filteredItems.filter { ids.contains($0.id) }
                            if let item = targets.first, targets.count == 1, item.directory {
                                Button(L("打开文件夹")) { navigate(to: path + [item.name]) }.disabled(transferring)
                            }
                            if !targets.isEmpty {
                                Button(L("下载到 Mac 收件箱")) { saveItems(targets, copy: false) }.disabled(transferring)
                                Button(L("复制文件，可在访达中粘贴")) { saveItems(targets, copy: true) }.disabled(transferring)
                            }
                        } primaryAction: { ids in
                            guard !transferring else { return }
                            let targets = filteredItems.filter { ids.contains($0.id) }
                            if let item = targets.first, targets.count == 1, item.directory {
                                navigate(to: path + [item.name])
                            } else { saveItems(targets, copy: false) }
                        }
                        .listStyle(.plain)
                        .background(SelectionToggleObserver(ids: filteredItems.map(\.id), selected: selectedIDs, excludingTrailing: 32) { selectedIDs = [] })
                        .scrollContentBackground(.hidden)
                        .environment(\.defaultMinListRowHeight, 30)
                    }
                }
            }.frame(maxWidth: .infinity, maxHeight: .infinity)
            Divider()
            FilePreviewPane(model: preview, files: previewFiles,
                            path: "/" + (path + (previewFiles.count == 1 ? [previewFiles[0].name] : [])).joined(separator: "/"),
                            reload: { previewRevision = UUID() })
                .frame(width: min(340, max(180, geometry.size.width / 3)))
            }
            }.frame(maxHeight: .infinity)
            if !session.visibleItems.isEmpty || transferring || overviewVisible {
                Divider()
                HStack(spacing: 8) {
                    if overviewVisible {
                        Button { NotificationCenter.default.post(name: .fileWeaveShowDevice, object: nil) } label: {
                            HStack(spacing: 5) {
                                Image(systemName: app.link.isReady ? "iphone.gen3.radiowaves.left.and.right" : "iphone.slash")
                                    .foregroundStyle(app.link.isReady ? Color.accentColor : .secondary)
                                Text(app.link.title).lineLimit(1).truncationMode(.middle)
                            }
                        }.buttonStyle(.borderless).help(L("设备与设置"))
                        Text(app.link.isReady ? L("已连接") : bridge.statusLine).lineLimit(1).foregroundStyle(.secondary)
                        Text("·").foregroundStyle(.tertiary).accessibilityHidden(true)
                    }
                    if transferring && !overviewVisible { ProgressView().controlSize(.small); Button(L("取消")) { transfer?.cancel() } }
                    Text((overviewVisible && (transferring || message == app.infoText || message == app.errorText) ? nil : message)
                        ?? (selected ? L("{0} 项 · {1}", filteredItems.count, L("刚刚共享")) : L("{0} 项", filteredItems.count)))
                        .font(.system(size: 11)).foregroundStyle(.secondary).lineLimit(2)
                    Spacer()
                    if !selectedIDs.isEmpty && !transferring {
                        Text(L("已选 {0}", selectedIDs.count)).font(.caption).foregroundStyle(.secondary)
                        Button(L("下载所选…")) { saveItems(filteredItems.filter { selectedIDs.contains($0.id) }, copy: false) }
                    }
                }.padding(.horizontal, 12).padding(.vertical, 10)
            }
        }
        .task(id: previewRequest) {
            let request = previewRequest
            #if DEBUG
            if DebugPreview.isEnabled, let fixture = ProcessInfo.processInfo.environment["SNAPSHOT_PREVIEW_FILE"] {
                await preview.load(file: request.files.count == 1 ? request.files.first : nil, path: fixture, source: nil,
                                   selected: false, localURL: URL(fileURLWithPath: fixture))
                return
            }
            #endif
            await preview.load(file: request.enabled && request.files.count == 1 ? request.files.first : nil,
                               path: (request.path + (request.files.count == 1 ? [request.files[0].name] : [])).joined(separator: "/"),
                               source: source, selected: request.selected)
        }
        .onDisappear { preview.clear() }
        .onReceive(DistributedNotificationCenter.default().publisher(for: Notification.Name("com.apple.screenIsLocked"))) { _ in
            previewAllowed = false; preview.clear()
        }
        .onReceive(DistributedNotificationCenter.default().publisher(for: Notification.Name("com.apple.screenIsUnlocked"))) { _ in
            previewAllowed = true; refreshID = UUID()
        }
        .onReceive(NSWorkspace.shared.notificationCenter.publisher(for: NSWorkspace.sessionDidResignActiveNotification)) { _ in
            previewAllowed = false; preview.clear()
        }
        .onReceive(NSWorkspace.shared.notificationCenter.publisher(for: NSWorkspace.sessionDidBecomeActiveNotification)) { _ in
            previewAllowed = true; refreshID = UUID()
        }
        .onReceive(NSWorkspace.shared.notificationCenter.publisher(for: NSWorkspace.willSleepNotification)) { _ in
            previewAllowed = false; preview.clear()
        }
        .onReceive(NSWorkspace.shared.notificationCenter.publisher(for: NSWorkspace.didWakeNotification)) { _ in
            previewAllowed = true; refreshID = UUID()
        }
        .task(id: refreshID) {
            await load()
            while app.isPanelVisible && !Task.isCancelled {
                try? await Task.sleep(nanoseconds: source == nil ? 5_000_000_000 : 10_000_000_000)
                if app.isPanelVisible && !Task.isCancelled { await load(showProgress: false) }
            }
        }
        .onChange(of: bridge.highlightID) { _, _ in
            session.lastHighlightID = bridge.highlightID
            source = nil; selected = true; path = []; selectedIDs = []; refreshID = UUID()
        }
         .onAppear {
            focus = .idle
            if !bridge.highlightID.isEmpty && bridge.highlightID != session.lastHighlightID {
                session.lastHighlightID = bridge.highlightID
                selected = true; path = []; selectedIDs = []; refreshID = UUID()
            }
        }
        .onChange(of: path) { _, _ in selectedIDs = [] }
        .defaultFocus($focus, .idle)
        .onChange(of: app.isPanelVisible) { _, visible in
            if visible { focus = .idle; previewAllowed = true; refreshID = UUID() }
            else { previewAllowed = false; preview.clear() }
        }
        .onExitCommand { focus = .idle }
        .onReceive(NotificationCenter.default.publisher(for: .fileWeaveFind)) { _ in focus = .search }

    }
    private var breadcrumbs: some View {
        HStack(spacing: 8) {
            if path.count > (rootElided ? 1 : 0) {
                Button { path.removeLast(); search = ""; refreshID = UUID() } label: {
                    Image(systemName: "chevron.left")
                }
                .help(L("上一级目录")).accessibilityLabel(L("上一级目录"))
            }
            ViewThatFits(in: .horizontal) {
                breadcrumbTrail(compact: false).fixedSize(horizontal: true, vertical: false)
                breadcrumbTrail(compact: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .buttonStyle(.borderless)
        .font(.system(size: 11))
        .foregroundStyle(.secondary)
        .help(path.joined(separator: "/"))
        .padding(.horizontal, 16).padding(.top, overviewVisible ? 8 : 0).padding(.bottom, 8)
    }

    private func breadcrumbTrail(compact: Bool) -> some View {
        let first = rootElided ? 1 : 0
        let ancestors = Array(first..<max(first, path.count - 1))
        return HStack(spacing: 6) {
            Button(selected ? L("刚刚共享") : (rootElided ? path[0] : L("手机存储"))) {
                navigate(to: rootElided ? Array(path.prefix(1)) : [])
            }
            .lineLimit(1).truncationMode(.middle)
            .frame(maxWidth: compact ? 120 : nil, alignment: .leading)
            if compact && !ancestors.isEmpty {
                Image(systemName: "chevron.right").font(.system(size: 9))
                Menu {
                    ForEach(ancestors, id: \.self) { index in
                        Button(path[index]) { navigate(to: Array(path.prefix(index + 1))) }
                    }
                } label: { Image(systemName: "ellipsis") }
                .menuStyle(.borderlessButton).menuIndicator(.hidden).fixedSize()
                .accessibilityLabel(L("上一级目录"))
            }
            ForEach(compact ? Array(path.indices.suffix(path.count > first ? 1 : 0)) : Array(first..<path.count), id: \.self) { index in
                Image(systemName: "chevron.right").font(.system(size: 9))
                if index == path.count - 1 {
                    Text(path[index]).foregroundStyle(.primary).lineLimit(1).truncationMode(.middle)
                        .layoutPriority(1)
                } else {
                    Button(path[index]) { navigate(to: Array(path.prefix(index + 1))) }
                        .lineLimit(1).truncationMode(.middle).help(L("跳到 {0}", path[index]))
                }
            }
        }
    }

    private func navigate(to destination: [String]) {
        path = destination; search = ""; refreshID = UUID()
    }

    private var filteredItems: [PhoneFile] {
        session.filteredItems
    }

    /// 没找到手机：说明真话（是没配对、手机不在线还是配对失效），
    /// 给一句能照着做的指引，再给一个当场重试的入口。
    /// 底栏的连接状态来自录音通道的轮询，这一页自己的发现结果独立，
    /// 但「连没连上手机」是同一件事，所以标题和指引复用同一份。
    private var disconnectedState: some View {
        VStack(spacing: 10) {
            Image(systemName: "iphone.slash")
                .font(.system(size: 24))
                .foregroundStyle(.secondary)
                .accessibilityHidden(true)
            Text(app.link.isReady ? (message ?? L("等待手机连接")) : app.link.title)
                .font(.system(size: 13))
                .multilineTextAlignment(.center)
            if !app.link.isReady, let hint = app.linkHint {
                Text(hint)
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                    .textSelection(.enabled)
            }
            Button(L("重新寻找手机")) { refreshID = UUID() }
                .controlSize(.small)
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    /// 找到了手机、但这个目录是空的（或共享已全局关闭）。
    private var emptyDirectoryState: some View {
        VStack(spacing: 12) {
            Image(systemName: enabled ? "folder" : "folder.badge.minus").font(.system(size: 24)).foregroundStyle(.secondary).accessibilityHidden(true)
            Text(message ?? (enabled ? L("这个目录还没有文件") : selected ? L("在手机上选择文件，点“共享到 Mac”") : L("手机已关闭全局文件共享")))
                .font(.system(size: 13)).multilineTextAlignment(.center)
            if !enabled && !selected { Text(L("主动共享的项目仍可在“刚刚共享”中使用")).font(.system(size: 12)).foregroundStyle(.secondary) }
        }.padding(24).frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func fileRow(_ item: PhoneFile) -> some View {
        HStack(spacing: 8) {
            HStack(spacing: 10) {
                Group {
                    if selectedIDs.contains(item.id) {
                        Image(systemName: item.directory ? "folder.fill" : "doc")
                    } else {
                        Image(systemName: item.directory ? "folder.fill" : "doc")
                            .foregroundStyle(item.directory ? Color.accentColor : Color.secondary)
                    }
                }.frame(width: 22)
                Text(item.name).lineLimit(1).truncationMode(.middle).help(item.name)
                Spacer(minLength: 8)
                Text(item.directory ? L("文件夹") : FileWeaveFormat.bytes(item.size))
                    .font(.callout.monospacedDigit()).foregroundStyle(.secondary).lineLimit(1).fixedSize()
            }
            .contentShape(Rectangle())
            Button { save(item, copy: false) } label: { Image(systemName: "arrow.down.to.line") }
                .buttonStyle(.borderless)
                .foregroundStyle(.secondary)
                .help(L("下载到 Mac 收件箱"))
                .accessibilityLabel(L("下载 {0}", item.name))
        }
        .font(.system(size: 13))
        .padding(.vertical, 7)
        .disabled(transferring)
    }

    @MainActor private func load(showProgress: Bool = true) async {
        if DebugPreview.isEnabled {
            loading = false; message = nil
            enabled = true
            items = DebugPreview.mode == "offline" || DebugPreview.mode == "empty" ? [] : [
                PhoneFile(name: "Documents", directory: true, size: 0),
                PhoneFile(name: "Recordings", directory: true, size: 0),
                PhoneFile(name: "会议记录 — 项目计划.pdf", directory: false, size: 2_480_000),
                PhoneFile(name: "A long filename that keeps the important beginning and extension readable on a smaller Mac window.zip", directory: false, size: 120_000_000),
                PhoneFile(name: "Prototype.png", directory: false, size: 854_000),
                PhoneFile(name: "Readme.md", directory: false, size: 120),
                PhoneFile(name: "Sample.html", directory: false, size: 256),
                PhoneFile(name: "Sample.wav", directory: false, size: 32_044),
                PhoneFile(name: ".Hidden notes.txt", directory: false, size: 128),
                PhoneFile(name: ".Private folder", directory: true, size: 0)
            ]
            if let n = Int(ProcessInfo.processInfo.environment["SNAPSHOT_SELECT"] ?? "") { selectedIDs = Set(items.prefix(n).map(\.id)) }
            if let name = ProcessInfo.processInfo.environment["SNAPSHOT_SELECTED_FILE"] { selectedIDs = [name] }
            return
        }
        let token = session.beginLoad()
        let originalSource = source
        let deviceHash = token.selected ? bridge.highlightDevice : nil
        loading = showProgress; message = nil
        defer { if session.latestLoad == token { loading = false } }
        do {
            let resolved: FileWeaveFolderSource?
            if let originalSource { resolved = originalSource }
            else { resolved = await Task.detached { FileWeaveFolderSource.discoverPaired(deviceHash: deviceHash) }.value }
            try Task.checkCancellation()
            guard let resolved else { throw SourceError.server(L("等待手机连接，连接后点刷新即可浏览")) }
            var effectivePath = token.path
            var elided = rootElided
            var result = try await resolved.listStorage(path: effectivePath.joined(separator: "/"), selected: token.selected)
            // Work only from this request's captured path. A late root response must
            // never navigate another host back out of a directory the user opened.
            if effectivePath.isEmpty {
                elided = result.items.count == 1 && result.items[0].directory
                if elided {
                    effectivePath = [result.items[0].name]
                    result = try await resolved.listStorage(path: effectivePath.joined(separator: "/"), selected: token.selected)
                }
            }
            try Task.checkCancellation()
            if session.commit(token, items: result.items, effectivePath: effectivePath, rootElided: elided, enabled: result.enabled, version: result.version) {
                source = resolved
            }
        } catch is CancellationError { }
        catch {
            guard session.accepts(token) else { return }
            items = []; message = error.localizedDescription; source = nil
        }
    }

    @MainActor private func save(_ item: PhoneFile, copy: Bool) { saveItems([item], copy: copy) }
    @MainActor private func saveItems(_ targets: [PhoneFile], copy: Bool) {
        guard let source, !transferring, !targets.isEmpty else { return }
        let parent = path
        let selection = selected
        transferring = true; message = L("正在下载 {0} 项…", targets.count)
        app.beginFileTransfer(message ?? L("正在下载 {0} 项…", targets.count), cancel: { session.transfer?.cancel() })
        transfer = Task {
            var failed = false
            defer { transferring = false; app.finishFileTransfer(message, failed: failed) }
            let root = MacPaths.state.appendingPathComponent("inbox")
            let staging = root.appendingPathComponent(".incoming-\(UUID().uuidString)")
            do {
                try FileManager.default.createDirectory(at: staging, withIntermediateDirectories: true)
                var destinations: [URL] = []
                for item in targets {
                let remote = (parent + [item.name]).joined(separator: "/")
                let target = staging.appendingPathComponent(item.name)
                try await download(item, path: remote, source: source, selected: selection, to: target, depth: 0)
                try Task.checkCancellation()
                var destination = root.appendingPathComponent(item.name)
                if FileManager.default.fileExists(atPath: destination.path) { destination = root.appendingPathComponent("\(UUID().uuidString.prefix(8))-\(item.name)") }
                try FileManager.default.moveItem(at: target, to: destination)
                destinations.append(destination)
                }
                if copy { NSPasteboard.general.clearContents(); NSPasteboard.general.writeObjects(destinations.map { $0 as NSURL }) }
                else { NSWorkspace.shared.activateFileViewerSelecting(destinations) }
                message = copy ? L("已复制，可在 Finder 中粘贴") : L("已保存到 Mac 收件箱")
            } catch { failed = !Task.isCancelled; message = Task.isCancelled ? L("已取消下载") : error.localizedDescription }
            try? FileManager.default.removeItem(at: staging)
        }
    }
    private func download(_ item: PhoneFile, path: String, source: FileWeaveFolderSource, selected: Bool, to destination: URL, depth: Int) async throws {
        try Task.checkCancellation()
        guard depth < 64 else { throw SourceError.server(L("目录层级过深")) }
        if item.directory {
            try FileManager.default.createDirectory(at: destination, withIntermediateDirectories: true)
            let listing = try await source.listStorage(path: path, selected: selected)
            guard listing.enabled else { throw SourceError.server(L("手机已停止共享")) }
            for child in listing.items { try await download(child, path: path + "/" + child.name, source: source, selected: selected, to: destination.appendingPathComponent(child.name), depth: depth + 1) }
        } else { try await source.downloadStorage(path: path, selected: selected, to: destination) }
    }
}
