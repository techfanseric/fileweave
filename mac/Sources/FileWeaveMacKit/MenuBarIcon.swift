import AppKit

// Folder outline derived from launcher_icon_monochrome.xml.
// Copyright (c) 2022 Hai Zhang <dreaming.in.code.zh@gmail.com>. All Rights Reserved.
public enum MenuBarIcon {
    public enum Status: Equatable {
        case connected, disconnected, reconnecting, working, attention

        var description: String {
            switch self {
            case .connected: return L("已连接")
            case .disconnected: return L("手机未连接")
            case .reconnecting: return L("正在连接")
            case .working: return L("任务进行中")
            case .attention: return L("需要处理")
            }
        }
    }

    /// 同一时刻只画一种标记；需处理的事优先于后台任务和连接状态。
    public static func status(link: LinkState, bridge: BridgeAvailability,
                              awaitingApproval: Bool, hasActiveTask: Bool) -> Status {
        if awaitingApproval || bridge == .discoveryFailed { return .attention }
        if hasActiveTask { return .working }
        if bridge == .starting || bridge == .recovering { return .reconnecting }
        switch link {
        case .checking, .reconnecting: return .reconnecting
        case .ready: return .connected
        case .noDevice, .failed: return .disconnected
        }
    }

    public static func image(status: Status) -> NSImage {
        let image = NSImage(size: NSSize(width: 16, height: 16), flipped: true) { _ in
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
            let transform = AffineTransform(m11: 0.5, m12: 0, m21: 0, m22: 0.5, tX: -29 * 0.5, tY: 1.6 - 32.2 * 0.5)
            path.transform(using: transform)
            NSColor.black.setFill(); path.fill()
            switch status {
            case .connected:
                break
            case .disconnected:
                let slash = NSBezierPath()
                slash.move(to: NSPoint(x: 2.8, y: 3.0))
                slash.line(to: NSPoint(x: 13.2, y: 13.0))
                slash.lineWidth = 1.6
                slash.lineCapStyle = .round
                NSColor.black.setStroke(); slash.stroke()
            case .reconnecting:
                let arc = NSBezierPath()
                arc.appendArc(withCenter: NSPoint(x: 8, y: 8), radius: 2.7,
                              startAngle: 35, endAngle: 300)
                arc.lineWidth = 1.25
                arc.lineCapStyle = .round
                NSColor.black.setStroke(); arc.stroke()
            case .working:
                let arrow = NSBezierPath()
                arrow.move(to: NSPoint(x: 5.3, y: 8))
                arrow.line(to: NSPoint(x: 10.7, y: 8))
                arrow.move(to: NSPoint(x: 8.6, y: 5.9))
                arrow.line(to: NSPoint(x: 10.7, y: 8))
                arrow.line(to: NSPoint(x: 8.6, y: 10.1))
                arrow.lineWidth = 1.3
                arrow.lineCapStyle = .round
                arrow.lineJoinStyle = .round
                NSColor.black.setStroke(); arrow.stroke()
            case .attention:
                let mark = NSBezierPath()
                mark.move(to: NSPoint(x: 8, y: 5.5))
                mark.line(to: NSPoint(x: 8, y: 8.5))
                mark.lineWidth = 1.5
                mark.lineCapStyle = .round
                NSColor.black.setStroke(); mark.stroke()
                NSColor.black.setFill()
                NSBezierPath(ovalIn: NSRect(x: 7.25, y: 9.5,
                                            width: 1.5, height: 1.5)).fill()
            }
            return true
        }
        image.isTemplate = true
        image.accessibilityDescription = "FileWeave · \(status.description)"
        return image
    }
}
