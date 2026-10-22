import SwiftUI

/// A figure with a caption, for summaries and insights.
struct StatTile: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    let value: String
    let caption: LocalizedStringKey
    var systemImage: String?
    /// For tiles that act as a filter: shown with an accent outline while on.
    var isSelected = false
    /// For tiles that open something: a chevron in the corner.
    var opens = false

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            if let systemImage {
                Image(systemName: systemImage)
                    .font(.subheadline)
                    .foregroundStyle(Color.accentColor)
            }
            Text(verbatim: value)
                .font(.title2.weight(.semibold).monospacedDigit())
                .minimumScaleFactor(0.7)
                .lineLimit(1)
            Text(caption)
                .font(.footnote)
                .foregroundStyle(.secondary)
                // At the accessibility sizes the tiles are stacked: the caption takes the lines it needs.
                .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 2)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .overlay(alignment: .topTrailing) {
            if opens {
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.tertiary)
                    .accessibilityHidden(true)
            }
        }
        .padding(14)
        .background(isSelected ? Color.accentColor.opacity(0.15) : Color.secondary.opacity(0.08),
                    in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay {
            if isSelected {
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .strokeBorder(Color.accentColor, lineWidth: 1.5)
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}
