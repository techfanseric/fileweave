import XCTest
@testable import FileWeaveMacKit

final class FileVisibilityTests: XCTestCase {
    private func isolatedSettings() -> (FileVisibilitySettings, UserDefaults, String) {
        let name = "FileWeave.visibility-test.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: name)!
        defaults.removePersistentDomain(forName: name)
        return (FileVisibilitySettings(defaults: defaults), defaults, name)
    }

    func testDefaultIsOffAndExplicitPreferenceSurvivesRecreation() {
        let (settings, defaults, name) = isolatedSettings()
        defer { defaults.removePersistentDomain(forName: name) }
        XCTAssertFalse(settings.showsHiddenFiles)
        settings.showsHiddenFiles = true
        XCTAssertTrue(FileVisibilitySettings(defaults: defaults).showsHiddenFiles)
        settings.showsHiddenFiles = false
        XCTAssertFalse(FileVisibilitySettings(defaults: defaults).showsHiddenFiles)
    }

    func testPhoneFilesFilterBeforeSearchAndPruneHiddenSelection() {
        let (settings, defaults, name) = isolatedSettings()
        defer { defaults.removePersistentDomain(forName: name) }
        let session = SharedFilesSession(visibility: settings)
        session.items = [PhoneFile(name: "Visible.pdf", directory: false, size: 10),
                         PhoneFile(name: ".Secret.pdf", directory: false, size: 20),
                         PhoneFile(name: ".Private", directory: true, size: 0)]
        XCTAssertEqual(session.visibleItems.map(\.name), ["Visible.pdf"])
        session.search = "Secret"
        XCTAssertTrue(session.filteredItems.isEmpty)
        settings.showsHiddenFiles = true
        XCTAssertEqual(session.visibleItems.count, 3)
        XCTAssertEqual(session.filteredItems.map(\.name), [".Secret.pdf"])
        session.selectedIDs = ["Visible.pdf", ".Secret.pdf", ".Private"]
        settings.showsHiddenFiles = false
        XCTAssertEqual(session.selectedIDs, ["Visible.pdf"])
        XCTAssertTrue(session.filteredItems.isEmpty)
        session.path = [".Explicitly opened folder"]
        session.search = ""
        XCTAssertEqual(session.filteredItems.map(\.name), ["Visible.pdf"])
    }

    func testHiddenOnlyDirectoryIsVisiblyEmpty() {
        let (settings, defaults, name) = isolatedSettings()
        defer { defaults.removePersistentDomain(forName: name) }
        let session = SharedFilesSession(visibility: settings)
        session.items = [PhoneFile(name: ".Private", directory: true, size: 0)]
        XCTAssertTrue(session.visibleItems.isEmpty)
        XCTAssertTrue(session.filteredItems.isEmpty)
        settings.showsHiddenFiles = true
        XCTAssertEqual(session.visibleItems.count, 1)
    }

    @MainActor
    func testInboxHonorsNativeHiddenFlagAsWellAsDotNames() throws {
        let (settings, defaults, name) = isolatedSettings()
        defer { defaults.removePersistentDomain(forName: name) }
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        var file = root.appendingPathComponent("Finder hidden.txt")
        try Data().write(to: file)
        var values = URLResourceValues()
        values.isHidden = true
        try file.setResourceValues(values)
        guard try file.resourceValues(forKeys: [.isHiddenKey]).isHidden == true else {
            throw XCTSkip("This temporary volume does not support the native hidden flag")
        }
        let inbox = InboxSession(root: root, visibility: settings)
        inbox.reload()
        XCTAssertTrue(inbox.files.isEmpty)
        settings.showsHiddenFiles = true
        inbox.reload()
        XCTAssertEqual(inbox.files.map(\.lastPathComponent), ["Finder hidden.txt"])
        settings.showsHiddenFiles = false
        inbox.reload()
        XCTAssertTrue(inbox.files.isEmpty)
    }

    @MainActor
    func testInboxIncludesUserDotfilesButNeverInternalStagingAndPrunesSelection() throws {
        let (settings, defaults, name) = isolatedSettings()
        defer { defaults.removePersistentDomain(forName: name) }
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let names = ["Visible.txt", ".user-notes.txt", ".incoming-notes",
                     ".incoming-\(UUID().uuidString)", ".incoming-0123456789abcdef0123456789abcdef"]
        for (index, item) in names.enumerated() {
            let url = root.appendingPathComponent(item)
            try Data().write(to: url)
            try FileManager.default.setAttributes([.modificationDate: Date(timeIntervalSince1970: Double(index + 1))], ofItemAtPath: url.path)
        }
        let inbox = InboxSession(root: root, visibility: settings)
        inbox.reload()
        XCTAssertEqual(inbox.files.map(\.lastPathComponent), ["Visible.txt"])
        XCTAssertEqual(inbox.overviewCount, 1)
        settings.showsHiddenFiles = true
        inbox.reload()
        XCTAssertEqual(Set(inbox.files.map(\.lastPathComponent)), Set(names.prefix(3)))
        XCTAssertEqual(inbox.overviewCount, 3)
        XCTAssertEqual(inbox.latestFile?.lastPathComponent, ".incoming-notes")
        let recreated = InboxSession(root: root, visibility: FileVisibilitySettings(defaults: defaults))
        recreated.reload()
        XCTAssertEqual(recreated.overviewCount, 3)
        inbox.selection = Set(inbox.files)
        settings.showsHiddenFiles = false
        inbox.reload()
        XCTAssertEqual(inbox.selection.map(\.lastPathComponent), ["Visible.txt"])
        XCTAssertEqual(inbox.overviewCount, 1)
    }
}
