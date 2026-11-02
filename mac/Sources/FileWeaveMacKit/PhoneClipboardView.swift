import SwiftUI
import AppKit

struct ReceivedPhoneClipboard: Decodable, Equatable {
    let id: String
    let time: Double
    let text: String
}
@MainActor
final class PhoneClipboardSession: ObservableObject {
    @Published private(set) var received: ReceivedPhoneClipboard?
    @Published private(set) var error: String?
    func clear() { received = nil; error = nil }
    func refresh() async {
        #if DEBUG
        if DebugPreview.isEnabled {
            let value = DebugPreview.mode == "clipboard-long" ? ReceivedPhoneClipboard(
                id: "layout-preview", time: 1_793_580_000,
                text: Array(repeating: "Clipboard layout preview · 剪贴板排版预览\nA long paragraph copied on the phone stays readable and selectable on this Mac.\nhttps://example.com/" + String(repeating: "long-path-", count: 12), count: 8).joined(separator: "\n\n")) : nil
            if received != value { received = value }
            error = nil
            return
        }
        #endif
        do {
            let pairing = try JSONSerialization.jsonObject(with: Data(contentsOf: MacPaths.state.appendingPathComponent("pairing.json"))) as? [String: Any]
            let credentials = try JSONSerialization.jsonObject(with: Data(contentsOf: MacPaths.state.appendingPathComponent("credentials.json"))) as? [String: Any]
            guard let port = pairing?["port"] as? Int, let pin = pairing?["pin"] as? String,
                  let token = credentials?["token"] as? String,
                  let url = FileWeaveFolderSource.endpointURL(host: "127.0.0.1", port: port, path: "/v1/received-clipboard") else { return }
            let session = URLSession(configuration: .ephemeral, delegate: PinningDelegate(fp: pin), delegateQueue: nil)
            defer { session.invalidateAndCancel() }
            var request = URLRequest(url: url); request.timeoutInterval = 3
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
            let (data, response) = try await session.data(for: request)
            guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw SourceError.server(L("等待接收手机剪贴板")) }
            struct Response: Decodable { let clipboard: ReceivedPhoneClipboard? }
            let result = try JSONDecoder().decode(Response.self, from: data).clipboard
            try Task.checkCancellation()
            if received != result { received = result }
            error = nil
        } catch { if !Task.isCancelled { self.error = L("等待接收手机剪贴板") } }
    }
}
struct PhoneClipboardView: View {
    @ObservedObject var session: PhoneClipboardSession
    @State private var unlocked = true
    @State private var copied = false
    @State private var showsDetails = false
    var body: some View {
        VStack(spacing: 0) {
            if let value = session.received {
                HStack {
                    Text(Date(timeIntervalSince1970: value.time), style: .time)
                        .font(.caption).foregroundStyle(.secondary)
                    Spacer()
                    Button(copied ? L("已复制") : L("复制文本")) {
                        NSPasteboard.general.clearContents(); NSPasteboard.general.setString(value.text, forType: .string); copied = true
                    }
                }.padding(16)
                Divider()
                ScrollView {
                    Text(verbatim: value.text).textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading).padding(16)
                }.frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                VStack(spacing: 12) {
                    Image(systemName: "clipboard").font(.system(size: 32)).foregroundStyle(.secondary)
                    Text(L("等待接收手机剪贴板")).font(.headline)
                    Text(L("在手机复制文本后，回到 FileWeave，或点击通知中的“发送剪贴板”。"))
                        .font(.callout).foregroundStyle(.secondary)
                        .multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: 420).padding(24)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            Divider()
            VStack(alignment: .leading, spacing: 8) {
                Text(L("仅显示最近一次收到的手机文本，不保存历史。"))
                    .foregroundStyle(.secondary)
                DisclosureGroup(L("剪贴板共享说明"), isExpanded: $showsDetails) {
                    Text(L("Android 限制后台读取剪贴板。回到手机 FileWeave，或点击通知中的“发送剪贴板”。密码等标记为敏感的内容不会自动发送。"))
                        .foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                        .frame(maxWidth: .infinity, alignment: .leading).padding(.top, 4)
                }
            }.font(.caption).padding(16).frame(maxWidth: .infinity, alignment: .leading)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .task(id: unlocked) {
            guard unlocked else { session.clear(); return }
            while !Task.isCancelled {
                await session.refresh()
                do { try await Task.sleep(nanoseconds: 1_000_000_000) } catch { return }
            }
        }
        .onChange(of: session.received?.id) { _, _ in copied = false }
        .onDisappear { session.clear() }
        .onReceive(DistributedNotificationCenter.default().publisher(for: Notification.Name("com.apple.screenIsLocked"))) { _ in unlocked = false; session.clear() }
        .onReceive(DistributedNotificationCenter.default().publisher(for: Notification.Name("com.apple.screenIsUnlocked"))) { _ in unlocked = true }
    }
}
