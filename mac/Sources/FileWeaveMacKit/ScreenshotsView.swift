import SwiftUI
import AppKit

struct PhoneScreenshot: Decodable, Identifiable, Hashable {
    let id: String
    let path: String
    let size: Int64
    let mtime: Double
    let trashPath: String?
    var name: String { (path as NSString).lastPathComponent }
    var currentPath: String { trashPath ?? path }
    var file: PhoneFile { PhoneFile(name: name, directory: false, size: size) }
    static func isSafe(_ path: String, trash: Bool) -> Bool {
        let parts = path.split(separator: "/", omittingEmptySubsequences: false).map(String.init)
        let prefixes = [["Pictures", "Screenshots"], ["DCIM", "Screenshots"], ["Screenshots"]]
        guard !path.contains("\\"), !path.contains("\0"),
              let prefix = prefixes.first(where: { Array(parts.prefix($0.count)) == $0 }), parts.count > prefix.count else { return false }
        let rest = Array(parts.dropFirst(prefix.count))
        return rest.enumerated().allSatisfy { index, value in
            !value.isEmpty && value != "." && value != ".." && (!value.hasPrefix(".") || (trash && index == 0 && value == ".Trash"))
        }
    }
    static func decodeListing(_ data: Data) throws -> [Self] {
        struct Listing: Decodable { let ok: Bool; let items: [PhoneScreenshot] }
        let listing = try JSONDecoder().decode(Listing.self, from: data)
        guard listing.ok, Set(listing.items.map(\.id)).count == listing.items.count, listing.items.allSatisfy({
            $0.size >= 0 && isSafe($0.path, trash: false) && isSafe($0.id, trash: $0.trashPath != nil)
                && ($0.trashPath.map { isSafe($0, trash: true) } ?? true)
        }) else { throw SourceError.server(L("手机返回了无效截图列表")) }
        return listing.items.sorted { $0.mtime > $1.mtime }
    }
}

@MainActor
final class ScreenshotsSession: ObservableObject {
    @Published var items: [PhoneScreenshot] = []
    @Published var selection: Set<String> = []
    @Published var query = ""
    @Published var showsTrash = false
    @Published var loading = false
    @Published var changing = false
    @Published var error: String?
    @Published var message: String?
    @Published var refreshID = UUID()
    @Published private(set) var source: FileWeaveFolderSource?
    private(set) var hasLoaded = false
    private var refreshing = false
    var visible: [PhoneScreenshot] { items.filter { ($0.trashPath != nil) == showsTrash && (query.isEmpty || $0.name.localizedCaseInsensitiveContains(query)) } }
    var selected: PhoneScreenshot? { selection.count == 1 ? visible.first { selection.contains($0.id) } : nil }
    var count: Int? { hasLoaded && error == nil ? items.filter { $0.trashPath == nil }.count : nil }
    func load(fallback: FileWeaveFolderSource?) async {
        guard !refreshing, !changing else { return }
        refreshing = true
        if !hasLoaded { loading = true }
        defer { refreshing = false; if loading { loading = false } }
        do {
            let phone: FileWeaveFolderSource?
            if let source { phone = source }
            else if let fallback { phone = fallback }
            else { phone = await Task.detached { FileWeaveFolderSource.discoverPaired() }.value }
            try Task.checkCancellation()
            guard let phone else { throw SourceError.server(L("等待手机连接")) }
            let list = try await phone.listScreenshots()
            try Task.checkCancellation()
            if source !== phone { source = phone }
            if items != list { items = list }
            let valid = selection.intersection(Set(visible.map(\.id)))
            if selection != valid { selection = valid }
            hasLoaded = true
            if error != nil { error = nil }
        } catch {
            guard !Task.isCancelled else { return }
            self.error = error.localizedDescription; source = nil; items = []; selection = []
        }
    }
    func change(_ item: PhoneScreenshot, restore: Bool, expectedIdentity: String?) async {
        guard let source, expectedIdentity == source.storageIdentity, !changing else {
            message = L("手机连接已变化，请重新选择截图。")
            return
        }
        changing = true; message = nil
        defer { changing = false; refreshID = UUID() }
        do {
            try await source.changeScreenshot(item, restore: restore)
            selection = []; items.removeAll { $0.id == item.id }
            message = restore ? L("已还原手机截图") : L("已移到手机回收站，可在截图页还原。")
        } catch { self.message = error.localizedDescription }
    }
}

enum ScreenshotClipboard {
    /// Materialize original-size image bytes. Preview thumbnails must never be copied.
    static func copy(url: URL, to board: NSPasteboard = .general) throws {
        let data = try Data(contentsOf: url)
        guard let image = NSImage(data: data), let tiff = image.tiffRepresentation else { throw SourceError.server(L("无法复制这张截图")) }
        board.clearContents()
        guard board.setData(tiff, forType: .tiff) else { throw SourceError.server(L("无法复制这张截图")) }
        if url.pathExtension.lowercased() == "png" { board.setData(data, forType: .png) }
    }
}

struct ScreenshotsView: View {
    @Environment(\.fileWeaveOverviewVisible) private var overviewVisible
    @ObservedObject var session: ScreenshotsSession
    @ObservedObject var files: SharedFilesSession
    @StateObject private var preview = FilePreviewModel(downloader: { source, path, _, destination, limit in
        try await source.downloadScreenshot(path: path, to: destination, limit: limit)
    })
    @State private var revision = UUID()
    @State private var confirmsTrash = false
    @State private var pendingTrash: PhoneScreenshot?
    @State private var pendingTrashIdentity: String?
    @State private var unlocked = true
    private var selected: PhoneScreenshot? { session.selected }
    private var previewRequest: FilePreviewRequest {
        FilePreviewRequest(files: selected.map { [$0.file] } ?? [], path: [selected?.currentPath ?? ""], selected: false,
                           source: session.source.map(ObjectIdentifier.init), enabled: unlocked && session.error == nil, revision: revision)
    }
    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                if !overviewVisible {
                    NativeSearchField(text: $session.query, placeholder: L("搜索手机截图")).frame(height: 24)
                }
                Toggle(L("手机回收站"), isOn: $session.showsTrash).toggleStyle(.checkbox)
                Spacer(minLength: 0)
                if let item = selected {
                    Button { copySelected() } label: { Label(L("复制图片"), systemImage: "doc.on.doc") }
                        .disabled(preview.fileURL == nil || preview.loading)
                    if item.trashPath != nil {
                        Button(L("还原到手机")) {
                            let identity = session.source?.storageIdentity
                            Task { await session.change(item, restore: true, expectedIdentity: identity) }
                        }
                            .disabled(session.changing)
                    } else {
                        Button(L("删除手机原图"), role: .destructive) { pendingTrash = item; pendingTrashIdentity = session.source?.storageIdentity; confirmsTrash = true }
                            .disabled(session.changing)
                    }
                }
            }.controlSize(.small).padding(.horizontal, 12).padding(.vertical, 8)
            Divider()
            GeometryReader { geometry in
                HStack(spacing: 0) {
                    Group {
                        if let error = session.error {
                            ContentUnavailableView { Label(L("手机截图暂不可用"), systemImage: "iphone.slash") }
                            description: { Text(LocalizedText.refresh(error)) }
                            actions: { Button(L("重试")) { session.refreshID = UUID() } }
                        } else if session.visible.isEmpty {
                            ContentUnavailableView { Label(session.showsTrash ? L("手机回收站暂无截图") : L("暂无手机截图"), systemImage: "photo.on.rectangle") }
                            description: { Text(L("手机上的新截图会自动出现在这里。")) }
                        } else {
                            List(selection: $session.selection) {
                                ForEach(session.visible) { item in
                                    HStack(spacing: 10) {
                                        Image(systemName: "photo").frame(width: 22).foregroundStyle(.secondary)
                                        VStack(alignment: .leading, spacing: 4) {
                                            Text(item.name).lineLimit(1).truncationMode(.middle)
                                            Text(Date(timeIntervalSince1970: item.mtime / 1000), style: .date).font(.caption).foregroundStyle(.secondary)
                                        }
                                        Spacer(minLength: 0)
                                        Text(FileWeaveFormat.bytes(item.size)).font(.caption).foregroundStyle(.secondary).fixedSize()
                                    }.padding(.vertical, 5).tag(item.id)
                                    .fileWeaveListRow(isSelected: session.selection.contains(item.id))
                                }
                            }.listStyle(.plain)
                            .background(SelectionToggleObserver(ids: session.visible.map(\.id), selected: session.selection) { session.selection = [] })
                        }
                    }.frame(maxWidth: .infinity, maxHeight: .infinity)
                    Divider()
                    FilePreviewPane(model: preview, files: session.visible.filter { session.selection.contains($0.id) }.map(\.file),
                                    path: selected?.path ?? "", emptyContext: .screenshots, reload: { revision = UUID() })
                        .frame(width: min(340, max(220, geometry.size.width / 3)))
                }
            }
            Divider()
            HStack {
                Text(session.message.map(LocalizedText.refresh) ?? L("{0} 项", session.visible.count)).lineLimit(2)
                Spacer()
                if session.loading && !session.hasLoaded { ProgressView().controlSize(.small) }
            }.font(.caption).foregroundStyle(.secondary).padding(10)
        }
        .task(id: session.refreshID) {
            while !Task.isCancelled {
                await session.load(fallback: files.source)
                do { try await Task.sleep(nanoseconds: session.error == nil ? 1_000_000_000 : 4_000_000_000) } catch { return }
            }
        }
        .task(id: previewRequest) {
            guard previewRequest.enabled, let item = selected else { preview.clear(); return }
            await preview.load(file: item.file, path: item.currentPath, source: session.source, selected: false)
        }
        .onChange(of: session.showsTrash) { _, _ in session.selection = []; preview.clear() }
        .onDisappear { preview.clear() }
        .onReceive(DistributedNotificationCenter.default().publisher(for: Notification.Name("com.apple.screenIsLocked"))) { _ in unlocked = false; preview.clear() }
        .onReceive(DistributedNotificationCenter.default().publisher(for: Notification.Name("com.apple.screenIsUnlocked"))) { _ in unlocked = true }
        .confirmationDialog(L("将这张手机截图移到回收站？"), isPresented: $confirmsTrash, titleVisibility: .visible) {
            Button(L("删除手机原图"), role: .destructive) {
                guard let item = pendingTrash else { return }
                let identity = pendingTrashIdentity
                Task { await session.change(item, restore: false, expectedIdentity: identity) }
            }
            Button(L("取消"), role: .cancel) { pendingTrash = nil }
        } message: { Text(L("只移动手机原图，Mac 收件箱中已有的副本不会删除。可在手机回收站中还原。")) }
    }
    private func copySelected() {
        guard let url = preview.fileURL else { return }
        do { try ScreenshotClipboard.copy(url: url); session.message = L("图片已复制，可在 Mac 粘贴使用。") }
        catch { session.message = error.localizedDescription }
    }
}
