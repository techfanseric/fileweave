import XCTest
@testable import FileWeaveMacKit

final class MenuBarIconTests: XCTestCase {
    func testConnectionStatesKeepTheirOwnMarkers() {
        let ready = LinkState.ready(SourceInfo(name: "Phone", detail: "test"))
        XCTAssertEqual(MenuBarIcon.status(link: ready, bridge: .ready,
                                          awaitingApproval: false, hasActiveTask: false), .connected)
        XCTAssertEqual(MenuBarIcon.status(link: .noDevice, bridge: .ready,
                                          awaitingApproval: false, hasActiveTask: false), .disconnected)
        XCTAssertEqual(MenuBarIcon.status(link: .reconnecting, bridge: .ready,
                                          awaitingApproval: false, hasActiveTask: false), .reconnecting)
        XCTAssertEqual(MenuBarIcon.status(link: ready, bridge: .recovering,
                                          awaitingApproval: false, hasActiveTask: false), .reconnecting)
    }

    func testActionAndWorkTakePriorityOverConnection() {
        XCTAssertEqual(MenuBarIcon.status(link: .noDevice, bridge: .ready,
                                          awaitingApproval: false, hasActiveTask: true), .working)
        XCTAssertEqual(MenuBarIcon.status(link: .reconnecting, bridge: .ready,
                                          awaitingApproval: true, hasActiveTask: true), .attention)
        XCTAssertEqual(MenuBarIcon.status(link: .noDevice, bridge: .discoveryFailed,
                                          awaitingApproval: false, hasActiveTask: true), .attention)
    }

    func testEveryStatusKeepsCompactIconSize() {
        for status in [MenuBarIcon.Status.connected, .disconnected, .reconnecting, .working, .attention] {
            XCTAssertEqual(MenuBarIcon.image(status: status).size, NSSize(width: 16, height: 16))
        }
    }

    func testMarkersRenderAsDistinctImages() {
        let statuses: [MenuBarIcon.Status] = [.connected, .disconnected, .reconnecting, .working, .attention]
        let images = statuses.compactMap { MenuBarIcon.image(status: $0).tiffRepresentation }
        XCTAssertEqual(images.count, statuses.count)
        XCTAssertEqual(Set(images).count, statuses.count)
    }
}
