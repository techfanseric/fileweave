import Foundation
import CoreGraphics

public enum MenuPanelGeometry {
    /// Size the workspace for the status item's screen before showing it. Native
    /// NSPopover positioning accounts for its arrow, border and screen edges.
    public static func contentSize(available: CGRect) -> CGSize {
        CGSize(width: min(720, max(1, available.width - 32)),
               height: min(640, max(1, available.height - 48)))
    }
}
