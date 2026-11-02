import SwiftUI
import AppKit

/// Selection belongs to native List, including inactive and keyboard focus states.
/// The only custom surface is a modest hover preview on an unselected row.
private struct FileListRowStyle: ViewModifier {
    @State private var isHovered = false
    let isSelected: Bool

    func body(content: Content) -> some View {
        content
            .listRowBackground(isSelected ? nil : Rectangle()
                .fill(isHovered ? Color(nsColor: .quaternaryLabelColor).opacity(0.5) : .clear))
            .onHover { isHovered = $0 }
    }
}

extension View {
    func fileWeaveListRow(isSelected: Bool) -> some View {
        modifier(FileListRowStyle(isSelected: isSelected))
    }
}
