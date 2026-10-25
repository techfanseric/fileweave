import AppKit

// Folder outline derived from launcher_icon_monochrome.xml.
// Copyright (c) 2022 Hai Zhang <dreaming.in.code.zh@gmail.com>. All Rights Reserved.
public enum MenuBarIcon {
    public static func image() -> NSImage {
        let image = NSImage(size: NSSize(width: 18, height: 18), flipped: true) { _ in
            let path = NSBezierPath()
            path.move(to: NSPoint(x: 41.8, y: 32.2))
            path.line(to: NSPoint(x: 32.2, y: 32.2))
            path.curve(to: NSPoint(x: 29.016, y: 35.4), controlPoint1: NSPoint(x: 30.44, y: 32.2), controlPoint2: NSPoint(x: 29.016, y: 33.64))
            path.line(to: NSPoint(x: 29, y: 54.6))
            path.curve(to: NSPoint(x: 32.2, y: 57.8), controlPoint1: NSPoint(x: 29, y: 56.36), controlPoint2: NSPoint(x: 30.44, y: 57.8))
            path.line(to: NSPoint(x: 57.8, y: 57.8))
            path.curve(to: NSPoint(x: 61, y: 54.6), controlPoint1: NSPoint(x: 59.56, y: 57.8), controlPoint2: NSPoint(x: 61, y: 56.36))
            path.line(to: NSPoint(x: 61, y: 38.6))
            path.curve(to: NSPoint(x: 57.8, y: 35.4), controlPoint1: NSPoint(x: 61, y: 36.84), controlPoint2: NSPoint(x: 59.56, y: 35.4))
            path.line(to: NSPoint(x: 45, y: 35.4)); path.close()
            path.move(to: NSPoint(x: 40.472, y: 35.4))
            for point in [NSPoint(x: 43.672, y: 38.6), NSPoint(x: 57.8, y: 38.6), NSPoint(x: 57.8, y: 54.6), NSPoint(x: 32.2, y: 54.6), NSPoint(x: 32.2, y: 35.4)] { path.line(to: point) }
            path.close()
            path.windingRule = .evenOdd
            let transform = AffineTransform(m11: 0.5625, m12: 0, m21: 0, m22: 0.5625, tX: -29 * 0.5625, tY: 1.8 - 32.2 * 0.5625)
            path.transform(using: transform)
            NSColor.black.setFill(); path.fill()
            return true
        }
        image.isTemplate = true
        image.accessibilityDescription = "FileWeave"
        return image
    }
}
