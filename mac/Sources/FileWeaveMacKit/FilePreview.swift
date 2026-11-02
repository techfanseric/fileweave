import SwiftUI
import AppKit
import AVKit
import PDFKit
import UniformTypeIdentifiers
import ImageIO

/// Mirrors the phone's built-in preview families; decoding still depends on macOS codecs.
enum FilePreviewKind: Equatable {
    case image, media, pdf, text, properties
    static func resolve(_ file: PhoneFile) -> Self {
        guard !file.directory else { return .properties }
        let ext = (file.name as NSString).pathExtension.lowercased()
        if TextFilePreview.isText(name: file.name) { return .text }
        if ext == "pdf" { return .pdf }
        guard let type = UTType(filenameExtension: ext) else { return .properties }
        if type.conforms(to: .image) { return .image }
        if type.conforms(to: .audio) || type.conforms(to: .movie) { return .media }
        if type.conforms(to: .plainText) { return .text }
        return .properties
    }
}

struct FilePreviewRequest: Hashable {
    let files: [PhoneFile]
    let path: [String]
    let selected: Bool
    let source: ObjectIdentifier?
    let enabled: Bool
    var version: Int64 = 0
    var revision: UUID? = nil
}

/// Each visible host owns one bounded, private preview slot. Original inbox files
/// are never put in this store. Incomplete transfers are removed along with the slot.
final class FilePreviewStore {
    static let byteLimit: Int64 = 256 * 1024 * 1024
    static let lifetime: TimeInterval = 300
    static var root: URL { FileManager.default.temporaryDirectory.appendingPathComponent("org.fileweave.previews", isDirectory: true) }
    let directory: URL
    init(root: URL = FilePreviewStore.root, now: Date = Date()) {
        directory = root.appendingPathComponent(UUID().uuidString, isDirectory: true)
        Self.sweep(root: root, before: now.addingTimeInterval(-3600))
    }
    static func sweep(root: URL, before cutoff: Date) {
        let fm = FileManager.default
        for url in (try? fm.contentsOfDirectory(at: root, includingPropertiesForKeys: [.contentModificationDateKey])) ?? [] {
            if let date = try? url.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate, date < cutoff {
                try? fm.removeItem(at: url)
            }
        }
    }
    func destination(for name: String) throws -> URL {
        guard !name.isEmpty, name != ".", name != "..", !name.contains("/"), !name.contains("\\"), !name.contains("\0") else {
            throw SourceError.server(L("手机返回了无效文件名"))
        }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        return directory.appendingPathComponent(name)
    }
    func clear() { try? FileManager.default.removeItem(at: directory) }
    deinit { clear() }
}

@MainActor
final class FilePreviewModel: ObservableObject {
    @Published private(set) var image: NSImage?
    @Published private(set) var document: PDFDocument?
    @Published private(set) var text: String?
    @Published private(set) var formattedHTML: String?
    @Published private(set) var fileURL: URL?
    @Published private(set) var player: AVPlayer?
    @Published private(set) var loading = false
    @Published private(set) var message: String?
    @Published private(set) var childCount: Int?
    typealias Downloader = (FileWeaveFolderSource, String, Bool, URL, Int64) async throws -> Void
    private let downloader: Downloader
    private let lifetime: TimeInterval
    init(lifetime: TimeInterval = FilePreviewStore.lifetime, downloader: @escaping Downloader = { source, path, selected, destination, limit in
        try await source.downloadPreview(path: path, selected: selected, to: destination, limit: limit)
    }) {
        self.lifetime = lifetime; self.downloader = downloader
    }
    private var store: FilePreviewStore?
    private var generation = UUID()
    private var expiry: Task<Void, Never>?
    // Explicit cancellation also covers a window becoming hidden while still mounted.
    private var work: Task<Void, Never>?

    func clear() {
        generation = UUID()
        work?.cancel(); work = nil
        expiry?.cancel(); expiry = nil
        player?.pause(); player?.replaceCurrentItem(with: nil); player = nil
        image = nil; document = nil; text = nil; formattedHTML = nil; fileURL = nil; childCount = nil; message = nil; loading = false
        store?.clear(); store = nil
    }

    func load(file: PhoneFile?, path: String, source: FileWeaveFolderSource?, selected: Bool, localURL: URL? = nil) async {
        clear()
        guard let file else { return }
        let token = generation
        let task = Task { [weak self] in
            guard let self else { return }
            // Rapid keyboard navigation should not download every crossed row.
            do { try await Task.sleep(nanoseconds: 180_000_000); try Task.checkCancellation() } catch { return }
            guard generation == token else { return }
            if file.directory {
                do {
                    if let localURL {
                        childCount = try FileManager.default.contentsOfDirectory(atPath: localURL.path).count
                    } else if let source {
                        let listing = try await source.listStorage(path: path, selected: selected)
                        try Task.checkCancellation()
                        guard generation == token else { return }
                        guard listing.enabled else { throw SourceError.server(L("手机已停止共享")) }
                        childCount = listing.items.count
                    }
                } catch { if generation == token && !Task.isCancelled { message = L("无法读取预览，显示文件属性") } }
                return
            }
            guard FilePreviewKind.resolve(file) != .properties else { return }
            guard file.size >= 0, file.size <= FilePreviewStore.byteLimit else {
                message = L("文件超过 {0}，显示属性；可下载后打开。", FileWeaveFormat.bytes(FilePreviewStore.byteLimit)); return
            }
            loading = true
            let slot = FilePreviewStore()
            store = slot
            do {
                let url: URL
                if let localURL { url = localURL }
                else {
                    guard let source else { throw SourceError.server(L("等待手机连接")) }
                    url = try slot.destination(for: file.name)
                    try await downloader(source, path, selected, url, FilePreviewStore.byteLimit)
                }
                try Task.checkCancellation()
                guard generation == token else { slot.clear(); return }
                switch FilePreviewKind.resolve(file) {
                case .image:
                    guard let data = CGImageSourceCreateWithURL(url as CFURL, nil),
                          let cg = CGImageSourceCreateThumbnailAtIndex(data, 0, [
                            kCGImageSourceCreateThumbnailFromImageAlways: true,
                            kCGImageSourceThumbnailMaxPixelSize: 1600,
                            kCGImageSourceCreateThumbnailWithTransform: true
                          ] as CFDictionary) else { throw SourceError.server(L("无法读取预览，显示文件属性")) }
                    image = NSImage(cgImage: cg, size: .zero)
                case .pdf:
                    guard let pdf = PDFDocument(url: url), !pdf.isLocked else { throw SourceError.server(L("无法读取预览，显示文件属性")) }
                    document = pdf
                case .text:
                    // Read a bounded prefix rather than expanding a huge text file into memory.
                    let handle = try FileHandle(forReadingFrom: url)
                    defer { try? handle.close() }
                    let bytes = try handle.read(upToCount: 2 * 1024 * 1024) ?? Data()
                    text = Self.decodeText(bytes, truncated: file.size > bytes.count)
                    guard let text else { throw SourceError.server(L("无法读取预览，显示文件属性")) }
                    let formatted = await Task.detached(priority: .userInitiated) { TextFilePreview.formattedHTML(text, name: file.name) }.value
                    try Task.checkCancellation()
                    guard generation == token else { slot.clear(); return }
                    formattedHTML = formatted
                    if file.size > bytes.count { message = L("仅预览前 2 MB 文本") }
                case .media:
                    let asset = AVURLAsset(url: url)
                    guard try await asset.load(.isPlayable) else { throw SourceError.server(L("此媒体编码无法预览，显示文件属性")) }
                    try Task.checkCancellation()
                    guard generation == token else { slot.clear(); return }
                    player = AVPlayer(playerItem: AVPlayerItem(asset: asset))
                case .properties: break
                }
                fileURL = url
                loading = false
                let delay = lifetime
                expiry = Task { [weak self] in
                    do { try await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000)) } catch { return }
                    guard let self, generation == token else { return }
                    clear(); message = L("临时预览已清理，可重新预览")
                }
            } catch {
                slot.clear()
                guard generation == token else { return }
                loading = false
                if !Task.isCancelled { message = L("无法读取预览，显示文件属性") }
            }
        }
        work = task
        await withTaskCancellationHandler { await task.value } onCancel: { task.cancel() }
    }

    nonisolated static func decodeText(_ data: Data, truncated: Bool = false) -> String? {
        if data.starts(with: [0xef, 0xbb, 0xbf]) { return decodeText(Data(data.dropFirst(3)), truncated: truncated) }
        if data.starts(with: [0xff, 0xfe, 0x00, 0x00]) || data.starts(with: [0x00, 0x00, 0xfe, 0xff]) { return String(data: data, encoding: .utf32) }
        if data.starts(with: [0xff, 0xfe]) || data.starts(with: [0xfe, 0xff]) { return String(data: data, encoding: .utf16) }
        if let utf8 = String(data: data, encoding: .utf8) { return utf8 }
        if truncated {
            for trim in 1...3 where data.count > trim {
                if let value = String(data: data.dropLast(trim), encoding: .utf8) { return value }
            }
        }
        // Honor HTML/XML charset declarations before guessing legacy encodings.
        let prefix = String(decoding: data.prefix(4096), as: UTF8.self)
        if let range = prefix.range(of: #"(?:charset|encoding)\s*=\s*["']?([A-Za-z0-9_-]+)"#, options: [.regularExpression, .caseInsensitive]) {
            let declaration = String(prefix[range])
            let charset = declaration.split(separator: "=").last?.trimmingCharacters(in: CharacterSet(charactersIn: " \"'")) ?? ""
            let cf = CFStringConvertIANACharSetNameToEncoding(charset as CFString)
            if cf != kCFStringEncodingInvalidId,
               let value = String(data: data, encoding: String.Encoding(rawValue: CFStringConvertEncodingToNSStringEncoding(cf))) { return value }
        }
        let gb = String.Encoding(rawValue: CFStringConvertEncodingToNSStringEncoding(CFStringEncoding(CFStringEncodings.GB_18030_2000.rawValue)))
        for encoding in [gb, .windowsCP1252] {
            if let value = String(data: data, encoding: encoding), !value.contains("\0") { return value }
        }
        return nil
    }

}

enum PreviewEmptyContext { case selection, inbox, screenshots }

struct FilePreviewPane: View {
    @ObservedObject var model: FilePreviewModel
    let files: [PhoneFile]
    let path: String
    var emptyContext: PreviewEmptyContext = .selection
    var reload: () -> Void = {}
    @State private var showsSource = false
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(L("预览")).font(.headline).padding(12)
            Divider()
            if files.isEmpty {
                if emptyContext == .inbox {
                    ScrollView {
                        VStack(alignment: .leading, spacing: 16) {
                            Label(L("Mac 收件箱"), systemImage: "tray.and.arrow.down").font(.headline)
                            Text(L("这里保存已到达这台 Mac 的文件副本。"))
                            Text(L("包括手机主动发送、在 Mac 点击下载，以及已启用的截图与录屏自动同步。"))
                            Text(L("仅浏览或临时预览手机文件，不会存入收件箱。"))
                            Text(L("副本与手机原件相互独立，不是双向同步；删除手机原件不会自动删除这里的副本。"))
                            Text(L("选择项目查看预览；再次点击已选项目可取消选择。"))
                                .font(.caption).foregroundStyle(.secondary)
                        }.font(.callout).fixedSize(horizontal: false, vertical: true).padding(12)
                    }
                } else {
                    ContentUnavailableView { Label(emptyContext == .screenshots ? L("选择手机截图") : L("选择文件或文件夹"), systemImage: "doc.text.magnifyingglass") }
                    description: { Text(emptyContext == .screenshots ? L("新截图自动更新。选中后可预览、复制图片，或将手机原图移到回收站。") : L("选中后在这里预览内容或查看属性")) }
                }
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 12) {
                        if files.count == 1, let file = files.first {
                            content
                            Label(file.name, systemImage: file.directory ? "folder.fill" : "doc")
                                .font(.headline).textSelection(.enabled).fixedSize(horizontal: false, vertical: true)
                            Divider()
                            property(L("类型"), file.directory ? L("文件夹") : (UTType(filenameExtension: (file.name as NSString).pathExtension)?.localizedDescription ?? L("文件")))
                            if !file.directory { property(L("大小"), FileWeaveFormat.bytes(file.size)) }
                            if let count = model.childCount { property(L("直接子项"), L("{0} 项", count)) }
                            property(L("位置"), path)
                            if let message = model.message {
                                Text(LocalizedText.refresh(message)).font(.caption).foregroundStyle(.secondary)
                                if !model.loading, model.image == nil, model.document == nil, model.text == nil, model.player == nil,
                                   !file.directory, file.size <= FilePreviewStore.byteLimit, FilePreviewKind.resolve(file) != .properties {
                                    Button(L("重新预览"), action: reload).buttonStyle(.borderless)
                                }
                            }
                        } else {
                            Label(L("已选 {0}", files.count), systemImage: "doc.on.doc").font(.headline)
                            property(L("文件夹"), String(files.filter(\.directory).count))
                            property(L("文件"), String(files.filter { !$0.directory }.count))
                            property(L("文件大小合计"), FileWeaveFormat.bytes(files.filter { !$0.directory }.reduce(Int64(0)) { value, file in
                                let (sum, overflow) = value.addingReportingOverflow(max(0, file.size))
                                return overflow ? Int64.max : sum
                            }))
                        }
                    }.padding(12)
                }
            }
            Spacer(minLength: 0)
        }.frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(Color(nsColor: .controlBackgroundColor))
        .onChange(of: files) { _, _ in showsSource = false }
    }
    @ViewBuilder private var content: some View {
        if model.loading {
            ProgressView(L("正在准备预览…")).frame(maxWidth: .infinity).frame(height: 120)
        } else if let image = model.image {
            Image(nsImage: image).resizable().scaledToFit().frame(maxWidth: .infinity).frame(maxHeight: 260)
        } else if let document = model.document {
            PreviewPDFView(document: document).frame(height: 300)
        } else if let player = model.player {
            PreviewPlayerView(player: player).frame(height: 200)
        } else if let text = model.text {
            if model.formattedHTML != nil {
                Picker(L("文本预览方式"), selection: $showsSource) {
                    Text(L("预览")).tag(false)
                    Text(L("源代码")).tag(true)
                }.pickerStyle(.segmented).labelsHidden()
            }
            if let html = model.formattedHTML, !showsSource {
                PreviewHTMLView(html: html).frame(height: 300)
            } else {
                ScrollView([.horizontal, .vertical]) {
                    Text(verbatim: text).font(.system(size: 12, design: .monospaced)).textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .topLeading).padding(8)
                }.frame(height: 260)
            }
        }
    }
    private func property(_ title: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.caption).foregroundStyle(.secondary)
            Text(verbatim: value).font(.callout).textSelection(.enabled).fixedSize(horizontal: false, vertical: true)
        }
    }
}

private struct PreviewPDFView: NSViewRepresentable {
    let document: PDFDocument
    func makeNSView(context: Context) -> PDFView {
        let view = PDFView(); view.autoScales = true; view.displayMode = .singlePageContinuous
        view.document = document; return view
    }
    func updateNSView(_ view: PDFView, context: Context) { if view.document !== document { view.document = document } }
    static func dismantleNSView(_ view: PDFView, coordinator: ()) { view.document = nil }
}
private struct PreviewPlayerView: NSViewRepresentable {
    let player: AVPlayer
    func makeNSView(context: Context) -> AVPlayerView {
        let view = AVPlayerView(); view.controlsStyle = .inline; view.player = player; return view
    }
    func updateNSView(_ view: AVPlayerView, context: Context) { view.player = player }
    static func dismantleNSView(_ view: AVPlayerView, coordinator: ()) { view.player?.pause(); view.player = nil }
}
