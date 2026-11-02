import Foundation
import Combine

/// A display preference shared by both file browsers. It never changes scanning,
/// transfer, encryption or the underlying file contents.
public final class FileVisibilitySettings: ObservableObject {
    public static let shared = FileVisibilitySettings()
    private static let key = "FileWeave.showHiddenFiles"
    private let defaults: UserDefaults

    @Published public var showsHiddenFiles: Bool {
        didSet {
            if showsHiddenFiles != oldValue { defaults.set(showsHiddenFiles, forKey: Self.key) }
        }
    }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        showsHiddenFiles = defaults.bool(forKey: Self.key)
    }

    static func isVisible(name: String, showsHiddenFiles: Bool) -> Bool {
        name != "." && name != ".." && (showsHiddenFiles || !name.hasPrefix("."))
    }

    /// Both transfer producers reserve these names: Swift uses a dashed UUID,
    /// and bridge.py uses secrets.token_hex(16). Ordinary `.incoming-notes`
    /// remains a user file governed by the display preference.
    static func isInboxStagingName(_ name: String) -> Bool {
        let prefix = ".incoming-"
        guard name.hasPrefix(prefix) else { return false }
        let suffix = String(name.dropFirst(prefix.count))
        let hexBytes = suffix.utf8
        let isBridgeToken = hexBytes.count == 32 && hexBytes.allSatisfy {
            (48...57).contains($0) || (65...70).contains($0) || (97...102).contains($0)
        }
        return isBridgeToken || UUID(uuidString: suffix) != nil
    }
}
