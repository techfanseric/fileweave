import XCTest
@testable import FileWeaveMacKit

final class MenuPanelGeometryTests: XCTestCase {
    func testNormalScreenKeepsFullFileWorkspace() {
        XCTAssertEqual(MenuPanelGeometry.contentSize(available: CGRect(x: 0, y: 40, width: 1440, height: 860)),
                       CGSize(width: 720, height: 640))
    }

    func testSmallSecondaryScreenUsesItsAvailableSize() {
        XCTAssertEqual(MenuPanelGeometry.contentSize(available: CGRect(x: -600, y: 200, width: 600, height: 500)),
                       CGSize(width: 568, height: 452))
    }

    func testUnavailableScreenDoesNotCreateNegativeContentSize() {
        XCTAssertEqual(MenuPanelGeometry.contentSize(available: .zero), CGSize(width: 1, height: 1))
    }
}
