import XCTest
@testable import FileWeaveMacKit

final class TextPreviewTests: XCTestCase {
    func testCommonTextFilesIncludingMarkdownHTMLAndExtensionlessNames() {
        for name in ["note.md", "note.MARKDOWN", "a.htm", "a.html", "a.xhtml", "a.txt", "a.log", "a.json", "a.xml", "a.yaml", "a.toml", "a.ini", "a.csv", "a.tsv", "a.swift", "a.py", "a.js", "a.kt", "a.sh", "a.srt", "README", "LICENSE", "Dockerfile", ".env"] {
            XCTAssertEqual(FilePreviewKind.resolve(PhoneFile(name: name, directory: false, size: 20)), .text, name)
        }
        XCTAssertFalse(TextFilePreview.isText(name: "binary.exe"))
    }
    func testMarkdownUsesCommonMarkBlocksInlineFormattingAndEscapedCode() throws {
        let html = try XCTUnwrap(TextFilePreview.formattedHTML("# Title\n\n**Bold** and *italic*.\n\n- First\n- Second\n\n| A | B |\n|---|---|\n| 1 | 2 |\n\n```html\n<script>alert(1)</script>\n```", name: "README.md"))
        for expected in ["<h1>Title</h1>", "<strong>Bold</strong>", "<em>italic</em>", "<ul>", "<li>", "<table>", "<td>A</td>", "&lt;script&gt;alert(1)&lt;/script&gt;"] { XCTAssertTrue(html.contains(expected), expected) }
        XCTAssertFalse(html.contains("<script>alert(1)</script>"))
        XCTAssertNil(TextFilePreview.formattedHTML("plain text", name: "a.log"))
    }
    func testHTMLPolicyPrecedesUntrustedMarkupAndBlocksActiveResources() throws {
        let html = try XCTUnwrap(TextFilePreview.formattedHTML("<h1>Title</h1><script>bad()</script><iframe src='https://example.test'></iframe>", name: "a.html"))
        let policy = try XCTUnwrap(html.range(of: "Content-Security-Policy"))
        let user = try XCTUnwrap(html.range(of: "<h1>Title"))
        XCTAssertLessThan(policy.lowerBound, user.lowerBound)
        for directive in ["default-src 'none'", "script-src 'none'", "connect-src 'none'", "frame-src 'none'", "form-action 'none'", "base-uri 'none'"] { XCTAssertTrue(html.contains(directive)) }
    }
    func testLegacyAndUnicodeEncodingsPreserveChineseText() throws {
        let chinese = "中文文本预览"
        let gb = String.Encoding(rawValue: CFStringConvertEncodingToNSStringEncoding(CFStringEncoding(CFStringEncodings.GB_18030_2000.rawValue)))
        XCTAssertEqual(FilePreviewModel.decodeText(try XCTUnwrap(chinese.data(using: gb))), chinese)
        for encoding: String.Encoding in [.utf8, .utf16, .utf32] {
            XCTAssertEqual(FilePreviewModel.decodeText(try XCTUnwrap(chinese.data(using: encoding))), chinese)
        }
        XCTAssertEqual(FilePreviewModel.decodeText(Data([0xef, 0xbb, 0xbf]) + Data("# Title".utf8)), "# Title")
        let declared = "<meta charset=windows-1252><p>café</p>"
        XCTAssertEqual(FilePreviewModel.decodeText(try XCTUnwrap(declared.data(using: .windowsCP1252))), declared)
    }
    @MainActor
    func testMarkdownSelectionProducesRenderedContentAndClearInvalidatesIt() async throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("\(UUID()).md")
        defer { try? FileManager.default.removeItem(at: file) }
        try Data("# Title\n\n**Body**".utf8).write(to: file)
        let model = FilePreviewModel()
        await model.load(file: PhoneFile(name: "a.md", directory: false, size: 17), path: "a.md", source: nil, selected: false, localURL: file)
        XCTAssertTrue(model.formattedHTML?.contains("<h1>Title</h1>") == true)
        XCTAssertNotNil(model.text)
        model.clear()
        XCTAssertNil(model.formattedHTML)
        XCTAssertNil(model.text)
        XCTAssertTrue(FileManager.default.fileExists(atPath: file.path))
    }
}
