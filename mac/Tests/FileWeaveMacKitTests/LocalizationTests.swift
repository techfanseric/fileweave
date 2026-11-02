import XCTest
@testable import FileWeaveMacKit

final class LocalizationTests: XCTestCase {
    func testEverySourceTemplateHasEnglishAndMatchingArguments() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        let sources = root.appendingPathComponent("Sources")
        let expression = try NSRegularExpression(pattern: #"\bL\("((?:\\.|[^"\\])*)""#)
        let files = try XCTUnwrap(FileManager.default.enumerator(at: sources, includingPropertiesForKeys: nil))
        var count = 0
        for case let file as URL in files where file.pathExtension == "swift" && file.lastPathComponent != "EnglishStrings.swift" {
            let text = try String(contentsOf: file, encoding: .utf8)
            for match in expression.matches(in: text, range: NSRange(text.startIndex..., in: text)) {
                let key = try JSONDecoder().decode(String.self, from: Data(("\"" + String(text[Range(match.range(at: 1), in: text)!]) + "\"").utf8))
                guard let english = EnglishStrings.values[key] else { XCTFail("Missing English template: \(key)"); continue }
                XCTAssertEqual(placeholders(key), placeholders(english), "Argument mismatch: \(key)")
                XCTAssertFalse(english.unicodeScalars.contains { (0x3400...0x9fff).contains($0.value) }, "Untranslated English template: \(key)")
                count += 1
            }
        }
        XCTAssertGreaterThan(count, 250)
    }

    func testArgumentsKeepUserNamesAndPlaceholdersLiterally() {
        let name = "共享文件 {1} — 会议记录.txt"
        XCTAssertEqual(LocalizedText.render("下载 {0}", [name], language: "en"), "Download \(name)")
        XCTAssertEqual(LocalizedText.render("下载 {0}", [name], language: "zh-Hans"), "下载 \(name)")
        XCTAssertEqual(LocalizedText.render("「{0}」下载失败：{1}", [name, "Error {0}"], language: "en"), "Could not download “\(name)”: Error {0}")
    }

    func testEnglishDefaultAndExplicitChinesePreferencePersist() throws {
        let name = "FileWeave.LocalizationTests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: name))
        defer { defaults.removePersistentDomain(forName: name) }
        defaults.set(["zh-Hans"], forKey: "AppleLanguages")
        let preference = AppLanguage(defaults: defaults, environment: [:])
        XCTAssertEqual(preference.code, "en")
        preference.code = "zh-Hans"
        XCTAssertEqual(AppLanguage(defaults: defaults, environment: [:]).code, "zh-Hans")
        preference.code = "en"
        XCTAssertEqual(AppLanguage(defaults: defaults, environment: [:]).code, "en")
    }

    func testSemanticRefreshPreservesOriginalUserArguments() {
        let name = "手机未连接 {0}.m4a"
        LocalizedText.remember("old status", key: "下载 {0}", arguments: [name])
        XCTAssertEqual(LocalizedText.refresh("old status"), L("下载 {0}", name))
        XCTAssertEqual(LocalizedText.refresh(name), name)
    }

    func testRunningTranscriptionCanSwitchLanguagesWithoutTranslatingTheName() {
        let name = "会议记录 {1}"
        let status = L("正在转录「{0}」…（音频 {1} 秒）", name, 180)
        let chinese = LocalizedText.refresh(status, language: "zh-Hans")
        XCTAssertEqual(chinese, "正在转录「\(name)」…（音频 180 秒）")
        XCTAssertEqual(LocalizedText.refresh(chinese, language: "en"), "Transcribing “\(name)”… (180 seconds of audio)")
    }

    func testEnglishCountsHaveSingularZeroAndPluralForms() {
        XCTAssertEqual(LocalizedText.render("{0} 项", ["0"], language: "en"), "0 items")
        XCTAssertEqual(LocalizedText.render("{0} 项", ["1"], language: "en"), "1 item")
        XCTAssertEqual(LocalizedText.render("{0} 项", ["8"], language: "en"), "8 items")
        XCTAssertEqual(LocalizedText.render("正在下载 {0} 项…", ["1"], language: "en"), "Downloading 1 item…")
        XCTAssertEqual(LocalizedText.render("{0} · {1} 条", ["1 items.txt", "1"], language: "en"), "1 items.txt · 1 recording")
        for (key, singular) in EnglishStrings.singular {
            XCTAssertEqual(placeholders(key), placeholders(singular.template), key)
        }
    }

    private func placeholders(_ text: String) -> [String] {
        let expression = try! NSRegularExpression(pattern: #"\{\d+\}"#)
        return expression.matches(in: text, range: NSRange(text.startIndex..., in: text))
            .map { String(text[Range($0.range, in: text)!]) }.sorted()
    }
}
