import SwiftUI
import AppKit

/// Native selection and drag stay in charge. A second unmodified single click
/// clears the sole selected row after release; a file drag never clears it.
struct SelectionToggleObserver: NSViewRepresentable {
    let ids: [String]
    let selected: Set<String>
    var excludingTrailing: CGFloat = 0
    let clear: () -> Void
    func makeCoordinator() -> Coordinator { Coordinator() }
    func makeNSView(context: Context) -> NSView {
        let view = PassiveView()
        context.coordinator.view = view
        context.coordinator.owner = self
        context.coordinator.monitor = NSEvent.addLocalMonitorForEvents(matching: [.leftMouseDown, .leftMouseUp, .leftMouseDragged]) { [weak coordinator = context.coordinator] event in
            coordinator?.handle(event)
            return event
        }
        return view
    }
    func updateNSView(_ nsView: NSView, context: Context) { context.coordinator.owner = self }
    func sizeThatFits(_ proposal: ProposedViewSize, nsView: NSView, context: Context) -> CGSize? {
        CGSize(width: proposal.width ?? 0, height: proposal.height ?? 0)
    }
    static func dismantleNSView(_ nsView: NSView, coordinator: Coordinator) {
        if let monitor = coordinator.monitor { NSEvent.removeMonitor(monitor) }
        coordinator.monitor = nil; coordinator.candidate = nil
    }
    final class PassiveView: NSView { override func hitTest(_ point: NSPoint) -> NSView? { nil } }
    final class Coordinator {
        struct Click {
            let generation = UUID()
            let id: String
            let origin: NSPoint
            let dragCount: Int
        }
        weak var view: NSView?
        var owner: SelectionToggleObserver?
        var monitor: Any?
        var candidate: Click?
        func handle(_ event: NSEvent) {
            if event.type == .leftMouseDragged { candidate = nil; return }
            guard let view, let owner, view.window === event.window else { return }
            if event.type == .leftMouseDown {
                candidate = nil
                guard event.clickCount <= 1,
                      event.modifierFlags.intersection([.command, .shift, .option, .control]).isEmpty,
                      view.bounds.contains(view.convert(event.locationInWindow, from: nil)),
                      let root = view.window?.contentView, let table = table(in: root, event: event) else { return }
                let point = table.convert(event.locationInWindow, from: nil)
                let row = table.row(at: point)
                guard point.x < table.bounds.maxX - owner.excludingTrailing, owner.ids.indices.contains(row),
                      owner.selected == [owner.ids[row]] else { return }
                let click = Click(id: owner.ids[row], origin: NSEvent.mouseLocation, dragCount: NSPasteboard(name: .drag).changeCount)
                candidate = click
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.01) { [weak self] in self?.finish(click) }
            } else if event.type == .leftMouseUp, let click = candidate {
                if event.clickCount > 1 { candidate = nil } else { DispatchQueue.main.async { [weak self] in self?.finish(click) } }
            }
        }
        private func finish(_ click: Click, attempts: Int = 0) {
            guard candidate?.generation == click.generation else { return }
            // NSTableView can consume mouseUp/dragged in its private drag tracking
            // loop. Its drag pasteboard still distinguishes an actual file drag.
            guard NSPasteboard(name: .drag).changeCount == click.dragCount, attempts < 2000 else { candidate = nil; return }
            if NSEvent.pressedMouseButtons & 1 != 0 {
                let point = NSEvent.mouseLocation
                guard hypot(point.x - click.origin.x, point.y - click.origin.y) <= 4 else { candidate = nil; return }
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.01) { [weak self] in self?.finish(click, attempts: attempts + 1) }
                return
            }
            // A table may finish its native tracking loop after the physical
            // button is released. Apply the toggle once that selection settles.
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) { [weak self] in
                guard let self, self.candidate?.generation == click.generation else { return }
                self.candidate = nil
                guard let owner = self.owner, owner.selected == [click.id] else { return }
                owner.clear()
            }
        }
        private func table(in view: NSView, event: NSEvent) -> NSTableView? {
            guard !view.isHiddenOrHasHiddenAncestor else { return nil }
            if let table = view as? NSTableView, table.bounds.contains(table.convert(event.locationInWindow, from: nil)) { return table }
            for child in view.subviews { if let found = table(in: child, event: event) { return found } }
            return nil
        }
    }
}
