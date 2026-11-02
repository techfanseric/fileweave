import Foundation

/// Synthetic, memory-only states for repeatable screenshots. Disabled in release builds.
public enum DebugPreview {
    public static var mode: String? {
        #if DEBUG
        ProcessInfo.processInfo.environment["FILEWEAVE_PREVIEW"]
            ?? (Bundle.main.bundleIdentifier == "org.fileweave.mac.qa" ? "populated" : nil)
        #else
        nil
        #endif
    }
    static var isEnabled: Bool { mode != nil }
}
