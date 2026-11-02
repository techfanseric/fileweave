import Foundation
import Combine

public extension Notification.Name {
    static let fileWeaveLanguageChanged = Notification.Name("FileWeave.languageChanged")
}

/// An explicit app preference: English is the default, independently of the Mac's language.
public final class AppLanguage: ObservableObject {
    public static let shared = AppLanguage()
    private let defaults: UserDefaults
    @Published public var code: String {
        didSet {
            guard code != oldValue else { return }
            defaults.set(code, forKey: "FileWeave.language")
            defaults.set([code], forKey: "AppleLanguages")
            NotificationCenter.default.post(name: .fileWeaveLanguageChanged, object: nil)
        }
    }
    public var locale: Locale { Locale(identifier: code) }
    /// Configure native standard dialogs before AppKit creates its first window.
    public static func configureNativeLanguage() {
        UserDefaults.standard.set([selectedCode], forKey: "AppleLanguages")
    }
    public static var selectedCode: String {
        if let language = ProcessInfo.processInfo.environment["FILEWEAVE_CLI_LANGUAGE"], ["en", "zh-Hans"].contains(language) { return language }
        #if DEBUG
        if let forced = ProcessInfo.processInfo.environment["FILEWEAVE_LANGUAGE"] { return forced == "zh-Hans" ? forced : "en" }
        #endif
        let code = UserDefaults.standard.string(forKey: "FileWeave.language")
            ?? ProcessInfo.processInfo.environment["FILEWEAVE_LANGUAGE"] ?? "en"
        return code == "zh-Hans" ? code : "en"
    }
    init(defaults: UserDefaults = .standard, environment: [String: String] = ProcessInfo.processInfo.environment) {
        self.defaults = defaults
        let preferred = environment["FILEWEAVE_LANGUAGE"]
            ?? defaults.string(forKey: "FileWeave.language") ?? "en"
        code = preferred == "zh-Hans" ? "zh-Hans" : "en"
    }
}

/// Only developer-authored templates pass through this function. User names, paths and
/// transcript text are inserted afterwards, so they are never translated or interpreted.
public func L(_ key: String, _ arguments: Any...) -> String {
    let strings = arguments.map { String(describing: $0) }
    let value = LocalizedText.render(key, strings, language: AppLanguage.selectedCode)
    LocalizedText.remember(value, key: key, arguments: strings)
    return value
}

/// Keep the semantic template of recent status messages so switching languages can
/// redraw an in-flight task without re-running it or translating user-provided data.
/// The bounded, memory-only registry never writes names, paths or message content to disk.
enum LocalizedText {
    private struct Message { let key: String; let arguments: [String] }
    private static let lock = NSLock()
    private static let placeholders = try! NSRegularExpression(pattern: #"\{(\d+)\}"#)
    private static var messages: [String: Message] = [:]
    private static var order: [String] = []
    static func remember(_ value: String, key: String, arguments: [String]) {
        lock.lock(); defer { lock.unlock() }
        if messages[value] == nil { order.append(value) }
        messages[value] = Message(key: key, arguments: arguments)
        while order.count > 512 { messages.removeValue(forKey: order.removeFirst()) }
    }
    static func refresh(_ value: String) -> String { refresh(value, language: AppLanguage.selectedCode) }
    static func refresh(_ value: String, language: String) -> String {
        lock.lock(); let message = messages[value]; lock.unlock()
        guard let message else { return value }
        let result = render(message.key, message.arguments, language: language)
        remember(result, key: message.key, arguments: message.arguments)
        return result
    }
    static func render(_ key: String, _ arguments: [String], language: String) -> String {
        var template = language == "zh-Hans" ? key : (EnglishStrings.values[key] ?? key)
        if language != "zh-Hans", let singular = EnglishStrings.singular[key],
           singular.argument < arguments.count, arguments[singular.argument] == "1" {
            template = singular.template
        }
        let expression = placeholders
        let range = NSRange(template.startIndex..., in: template)
        var result = template
        for match in expression.matches(in: template, range: range).reversed() {
            guard let numberRange = Range(match.range(at: 1), in: template),
                  let index = Int(template[numberRange]), index < arguments.count,
                  let matchRange = Range(match.range, in: result) else { continue }
            result.replaceSubrange(matchRange, with: arguments[index])
        }
        return result
    }
}

public enum FileWeaveFormat {
    public static func bytes(_ value: Int64) -> String {
        ByteCountFormatStyle(style: .file, spellsOutZero: false,
                             locale: Locale(identifier: AppLanguage.selectedCode)).format(value)
    }
}
