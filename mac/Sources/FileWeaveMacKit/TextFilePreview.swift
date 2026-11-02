import Foundation
import SwiftUI
import WebKit

/// File extensions only choose a presentation; no content is executed.
enum TextFilePreview {
    static let markdownExtensions: Set<String> = ["md", "markdown", "mdown", "mkd", "mkdn"]
    static let htmlExtensions: Set<String> = ["html", "htm", "xhtml"]
    static let extensions: Set<String> = markdownExtensions.union(htmlExtensions).union([
        "txt", "text", "log", "rst", "tex", "srt", "vtt", "ass", "ssa", "nfo", "asc", "diff", "patch", "csv", "tsv", "json", "jsonl", "ndjson", "xml", "yaml", "yml", "toml", "ini", "cfg", "conf", "config", "properties", "env", "sql",
        "swift", "kt", "kts", "java", "py", "pyw", "js", "jsx", "mjs", "cjs", "ts", "tsx", "css", "scss", "less", "c", "h", "cpp", "hpp", "cc", "cs", "go", "rs", "rb", "php", "sh", "bash", "zsh", "bat", "ps1", "r", "vue", "svelte", "gradle", "gitignore", "gitattributes", "dockerignore"
    ])
    static func isText(name: String) -> Bool {
        let name = name.lowercased()
        return extensions.contains((name as NSString).pathExtension)
            || ["readme", "license", "licence", "notice", "authors", "changelog", "makefile", "dockerfile", ".env", ".gitignore", ".gitattributes"].contains(name)
    }
    static func formattedHTML(_ text: String, name: String) -> String? {
        let ext = (name as NSString).pathExtension.lowercased()
        if htmlExtensions.contains(ext) { return document(body: text) }
        guard markdownExtensions.contains(ext),
              let parsed = try? AttributedString(markdown: text, options: .init(interpretedSyntax: .full)) else { return nil }
        // Foundation parses CommonMark. Its block identities retain nesting across
        // inline runs, so headings, lists, code and tables share the same renderer.
        var stack: [PresentationIntent.IntentType] = []
        var body = ""
        for run in parsed.runs {
            let next = Array((run.presentationIntent?.components ?? []).reversed())
            let common = zip(stack, next).prefix { $0.identity == $1.identity }.count
            for block in stack.dropFirst(common).reversed() { body += closing(block.kind) }
            for block in next.dropFirst(common) { body += opening(block.kind) }
            stack = next
            var value = escape(String(parsed.characters[run.range]))
            let inline = run.inlinePresentationIntent ?? []
            if inline.contains(.code) { value = "<code>\(value)</code>" }
            if inline.contains(.stronglyEmphasized) { value = "<strong>\(value)</strong>" }
            if inline.contains(.emphasized) { value = "<em>\(value)</em>" }
            if inline.contains(.strikethrough) { value = "<del>\(value)</del>" }
            if let link = run.link { value = "<a href=\"\(escape(link.absoluteString))\">\(value)</a>" }
            body += value
        }
        for block in stack.reversed() { body += closing(block.kind) }
        return document(body: body)
    }
    private static func opening(_ kind: PresentationIntent.Kind) -> String {
        switch kind {
        case .paragraph: return "<p>"
        case .header(let level): return "<h\(min(6, max(1, level)))>"
        case .orderedList: return "<ol>"
        case .unorderedList: return "<ul>"
        case .listItem: return "<li>"
        case .codeBlock: return "<pre><code>"
        case .blockQuote: return "<blockquote>"
        case .thematicBreak: return "<hr>"
        case .table: return "<table>"
        case .tableHeaderRow, .tableRow: return "<tr>"
        case .tableCell: return "<td>"
        @unknown default: return "<div>"
        }
    }
    private static func closing(_ kind: PresentationIntent.Kind) -> String {
        switch kind {
        case .paragraph: return "</p>"
        case .header(let level): return "</h\(min(6, max(1, level)))>"
        case .orderedList: return "</ol>"
        case .unorderedList: return "</ul>"
        case .listItem: return "</li>"
        case .codeBlock: return "</code></pre>"
        case .blockQuote: return "</blockquote>"
        case .thematicBreak: return ""
        case .table: return "</table>"
        case .tableHeaderRow, .tableRow: return "</tr>"
        case .tableCell: return "</td>"
        @unknown default: return "</div>"
        }
    }
    static func escape(_ text: String) -> String {
        text.replacingOccurrences(of: "&", with: "&amp;").replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;").replacingOccurrences(of: "\"", with: "&quot;")
    }
    static func document(body: String) -> String {
        // Place the restrictive policy before user HTML. Later policies cannot
        // relax it. No filesystem base URL, remote resources, forms or frames.
        """
        <!doctype html><html><head><meta charset="utf-8">
        <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; img-src data:; script-src 'none'; connect-src 'none'; frame-src 'none'; object-src 'none'; form-action 'none'; base-uri 'none'">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <style>
        :root { color-scheme: light dark; font: 13px/1.55 -apple-system, sans-serif; }
        body { margin: 12px; overflow-wrap: anywhere; }
        h1 { font-size: 1.6em; } h2 { font-size: 1.35em; } h3 { font-size: 1.15em; }
        h1,h2,h3,h4 { line-height: 1.3; } pre,code { font-family: ui-monospace,monospace; }
        pre { overflow-x: auto; padding: 8px; background: light-dark(#f1f1f1,#292929); }
        pre code { white-space: pre; } ul,ol { padding-left: 20px; } li p { margin: 4px 0; }
        table { border-collapse: collapse; } td,th { border: 1px solid GrayText; padding: 4px 8px; }
        img { max-width: 100%; height: auto; } blockquote { margin: 12px; color: GrayText; }
        </style></head><body>\(body)</body></html>
        """
    }
}

/// A read-only, offline document surface. No script handlers, shared cookies,
/// file URL access or external navigation are installed.
struct PreviewHTMLView: NSViewRepresentable {
    let html: String
    func makeCoordinator() -> Coordinator { Coordinator() }
    func makeNSView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .nonPersistent()
        configuration.defaultWebpagePreferences.allowsContentJavaScript = false
        configuration.preferences.javaScriptCanOpenWindowsAutomatically = false
        let view = WKWebView(frame: .zero, configuration: configuration)
        view.navigationDelegate = context.coordinator
        view.loadHTMLString(html, baseURL: nil)
        context.coordinator.html = html
        return view
    }
    func updateNSView(_ view: WKWebView, context: Context) {
        if context.coordinator.html != html { context.coordinator.html = html; view.loadHTMLString(html, baseURL: nil) }
    }
    static func dismantleNSView(_ view: WKWebView, coordinator: Coordinator) {
        view.stopLoading(); view.navigationDelegate = nil; view.loadHTMLString("", baseURL: nil)
    }
    final class Coordinator: NSObject, WKNavigationDelegate {
        var html = ""
        func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction,
                     decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            let initial = navigationAction.navigationType == .other && navigationAction.request.url?.absoluteString == "about:blank"
            decisionHandler(initial ? .allow : .cancel)
        }
    }
}
