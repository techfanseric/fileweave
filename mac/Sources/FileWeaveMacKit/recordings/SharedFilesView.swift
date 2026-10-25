import SwiftUI
import AppKit

struct SharedFilesView: View {
    @ObservedObject var bridge: BridgeProcess
    @EnvironmentObject private var app: AppState
    private enum FocusTarget: Hashable { case idle, search }
    @FocusState private var focus: FocusTarget?
    @State private var source: FileWeaveFolderSource?
    @State private var items: [PhoneFile] = []
    @State private var path: [String] = []
    @State private var selected = false
    @State private var enabled = true
    @State private var loading = false
    @State private var message: String?
    @State private var search = ""
    @State private var transfer: Task<Void, Never>?
    @State private var transferring = false
    @State private var refreshID = UUID()

    var body: some View {
        VStack(spacing: 0) {
            Color.clear.frame(width: 1, height: 1).focusable().focusEffectDisabled()
                .focused($focus, equals: .idle).accessibilityHidden(true)
            HStack(spacing: 8) {
                Picker("文件范围", selection: $selected) {
                    Text("全部文件").tag(false)
                    Text("刚刚共享").tag(true)
                }
                .pickerStyle(.menu)
                .labelsHidden()
                .fixedSize()
                .onChange(of: selected) { _, _ in
                    path = []; search = ""; refreshID = UUID()
                }
                HStack(spacing: 5) {
                    Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                    TextField("搜索当前目录", text: $search)
                        .focused($focus, equals: .search)
                        .textFieldStyle(.plain)
                    if !search.isEmpty {
                        Button { search = "" } label: { Image(systemName: "xmark.circle.fill") }
                            .buttonStyle(.plain)
                            .foregroundStyle(.secondary)
                            .help("清除搜索")
                            .accessibilityLabel("清除搜索")
                    }
                }
                .padding(.horizontal, 7)
                .padding(.vertical, 4)
                .background(.quaternary, in: RoundedRectangle(cornerRadius: 6))
                Button { refreshID = UUID() } label: { Image(systemName: "arrow.clockwise") }
                    .buttonStyle(.borderless)
                    .help("刷新目录")
                    .accessibilityLabel("刷新目录")
            }
            .font(.system(size: 12))
            .controlSize(.small)
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            if !path.isEmpty {
                HStack(spacing: 8) {
                    Button { path.removeLast(); search = ""; refreshID = UUID() } label: {
                        Image(systemName: "chevron.left")
                    }
                    .help("上一级目录")
                    .accessibilityLabel("上一级目录")
                    Button(selected ? "刚刚共享" : "手机存储") { path = []; search = ""; refreshID = UUID() }
                    Image(systemName: "chevron.right").font(.system(size: 9))
                    Text(path.joined(separator: " / "))
                        .lineLimit(1).truncationMode(.middle)
                    Spacer(minLength: 0)
                }
                .buttonStyle(.borderless)
                .font(.system(size: 11))
                .foregroundStyle(.secondary)
                .padding(.horizontal, 12)
                .padding(.bottom, 7)
            }
            Divider()
            Group {
                if loading { ProgressView("正在读取手机文件…").frame(maxWidth: .infinity, maxHeight: .infinity) }
                else if items.isEmpty {
                    VStack(spacing: 12) {
                        Image(systemName: enabled ? "folder" : "folder.badge.minus").font(.system(size: 24)).foregroundStyle(.secondary).accessibilityHidden(true)
                        Text(message ?? (enabled ? "这个目录还没有文件" : selected ? "在手机上选择文件，点“共享到 Mac”" : "手机已关闭全局文件共享"))
                            .font(.system(size: 13)).multilineTextAlignment(.center)
                        if !enabled && !selected { Text("主动共享的项目仍可在“刚刚共享”中使用").font(.system(size: 12)).foregroundStyle(.secondary) }
                    }.padding(24).frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    if filteredItems.isEmpty {
                        ContentUnavailableView {
                            Label("没有匹配的文件", systemImage: "magnifyingglass")
                        } description: {
                            Text("当前目录没有名称包含“\(search)”的项目")
                        }
                    } else {
                        List(filteredItems) { item in
                            fileRow(item)
                                .listRowInsets(EdgeInsets(top: 2, leading: 8, bottom: 2, trailing: 8))
                        }
                        .listStyle(.inset)
                        .alternatingRowBackgrounds()
                        .scrollContentBackground(.hidden)
                        .environment(\.defaultMinListRowHeight, 30)
                    }
                }
            }.frame(maxHeight: .infinity)
            if !items.isEmpty || transferring {
                Divider()
                HStack {
                    if transferring { ProgressView().controlSize(.small); Button("取消") { transfer?.cancel() } }
                    Text(message ?? "\(filteredItems.count) 项 · \(selected ? "刚刚共享" : source?.displayName ?? "等待手机连接")")
                        .font(.system(size: 11)).foregroundStyle(.secondary).lineLimit(2)
                    Spacer()
                }.padding(.horizontal, 12).padding(.vertical, 6)
            }
        }
        .task(id: refreshID) {
            await load()
            while app.isPanelVisible && !Task.isCancelled {
                try? await Task.sleep(nanoseconds: source == nil ? 5_000_000_000 : 10_000_000_000)
                if app.isPanelVisible && !Task.isCancelled { await load(showProgress: false) }
            }
        }
        .onChange(of: bridge.highlightID) { _, _ in
            source = nil; selected = true; path = []; refreshID = UUID()
        }
        .onAppear { focus = .idle; if !bridge.highlightID.isEmpty { selected = true; refreshID = UUID() } }
        .defaultFocus($focus, .idle)
        .onChange(of: app.isPanelVisible) { _, visible in if visible { focus = .idle; refreshID = UUID() } }
        .onExitCommand { focus = .idle }
        .onDisappear { transfer?.cancel() }
    }
    private var filteredItems: [PhoneFile] {
        items.filter { search.isEmpty || $0.name.localizedCaseInsensitiveContains(search) }
    }

    private func fileRow(_ item: PhoneFile) -> some View {
        HStack(spacing: 8) {
            Button {
                if item.directory { path.append(item.name); search = ""; refreshID = UUID() }
                else { save(item, copy: false) }
            } label: {
                HStack(spacing: 8) {
                    Image(systemName: item.directory ? "folder.fill" : "doc")
                        .foregroundStyle(item.directory ? Color.accentColor : Color.secondary)
                        .frame(width: 18)
                    Text(item.name)
                        .lineLimit(1)
                        .truncationMode(.middle)
                        .help(item.name)
                    Spacer(minLength: 4)
                    Text(item.directory ? "文件夹" : ByteCountFormatter.string(fromByteCount: item.size, countStyle: .file))
                        .font(.system(size: 11).monospacedDigit())
                        .foregroundStyle(.secondary)
                        .fixedSize()
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            Button { save(item, copy: false) } label: { Image(systemName: "arrow.down.to.line") }
                .buttonStyle(.borderless)
                .foregroundStyle(.secondary)
                .help("下载到 Mac 收件箱")
                .accessibilityLabel("下载 \(item.name)")
        }
        .font(.system(size: 13))
        .padding(.vertical, 2)
        .disabled(transferring)
        .contextMenu {
            if item.directory {
                Button("打开文件夹") { path.append(item.name); search = ""; refreshID = UUID() }
                    .disabled(transferring)
            }
            Button("下载到 Mac 收件箱") { save(item, copy: false) }
                .disabled(transferring)
            Button("复制文件，可在访达中粘贴") { save(item, copy: true) }
                .disabled(transferring)
        }
    }

    @MainActor private func load(showProgress: Bool = true) async {
        loading = showProgress; message = nil
        defer { loading = false }
        do {
            if source == nil {
                let hash = selected ? bridge.highlightDevice : nil
                source = await Task.detached { FileWeaveFolderSource.discoverPaired(deviceHash: hash) }.value
            }
            try Task.checkCancellation()
            guard let source else { throw SourceError.server("等待手机连接，连接后点刷新即可浏览") }
            let result = try await source.listStorage(path: path.joined(separator: "/"), selected: selected)
            try Task.checkCancellation()
            enabled = result.enabled
            items = result.items.sorted { a, b in a.directory != b.directory ? a.directory : a.name.localizedStandardCompare(b.name) == .orderedAscending }
        } catch is CancellationError { }
        catch { items = []; message = error.localizedDescription; source = nil }
    }
    @MainActor private func save(_ item: PhoneFile, copy: Bool) {
        guard let source, !transferring else { return }
        let remote = (path + [item.name]).joined(separator: "/")
        let selection = selected
        transferring = true; message = "正在下载 \(item.name)…"
        transfer = Task {
            defer { transferring = false }
            let root = MacPaths.state.appendingPathComponent("inbox")
            let staging = root.appendingPathComponent(".incoming-\(UUID().uuidString)")
            do {
                try FileManager.default.createDirectory(at: staging, withIntermediateDirectories: true)
                let target = staging.appendingPathComponent(item.name)
                try await download(item, path: remote, source: source, selected: selection, to: target, depth: 0)
                try Task.checkCancellation()
                var destination = root.appendingPathComponent(item.name)
                if FileManager.default.fileExists(atPath: destination.path) { destination = root.appendingPathComponent("\(UUID().uuidString.prefix(8))-\(item.name)") }
                try FileManager.default.moveItem(at: target, to: destination)
                if copy { NSPasteboard.general.clearContents(); NSPasteboard.general.writeObjects([destination as NSURL]) }
                else { NSWorkspace.shared.activateFileViewerSelecting([destination]) }
                message = copy ? "已复制，可在 Finder 中粘贴" : "已保存到 Mac 收件箱"
            } catch { message = Task.isCancelled ? "已取消下载" : error.localizedDescription }
            try? FileManager.default.removeItem(at: staging)
        }
    }
    private func download(_ item: PhoneFile, path: String, source: FileWeaveFolderSource, selected: Bool, to destination: URL, depth: Int) async throws {
        try Task.checkCancellation()
        guard depth < 64 else { throw SourceError.server("目录层级过深") }
        if item.directory {
            try FileManager.default.createDirectory(at: destination, withIntermediateDirectories: true)
            let listing = try await source.listStorage(path: path, selected: selected)
            guard listing.enabled else { throw SourceError.server("手机已停止共享") }
            for child in listing.items { try await download(child, path: path + "/" + child.name, source: source, selected: selected, to: destination.appendingPathComponent(child.name), depth: depth + 1) }
        } else { try await source.downloadStorage(path: path, selected: selected, to: destination) }
    }
}
