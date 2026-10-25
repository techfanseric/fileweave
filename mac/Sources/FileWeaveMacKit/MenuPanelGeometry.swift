import Foundation
import CoreGraphics

public enum MenuPanelGeometry {
    /// Menu-bar popovers stay below the menu bar and wholly inside the current screen.
    public static func frame(_ frame: CGRect, available: CGRect, anchor: CGRect) -> CGRect {
        let bounds = available.insetBy(dx: 4, dy: 4)
        let size = CGSize(width: min(frame.width, bounds.width), height: min(frame.height, bounds.height))
        let x = min(max(anchor.midX - size.width / 2, bounds.minX), bounds.maxX - size.width)
        let top = min(available.maxY, anchor.minY)
        let y = min(max(top - size.height, bounds.minY), bounds.maxY - size.height)
        return CGRect(origin: CGPoint(x: x, y: y), size: size)
    }
}
