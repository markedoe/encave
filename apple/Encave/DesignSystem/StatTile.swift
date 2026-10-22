import SwiftUI

/// A caption under a figure, agreeing with it: "1" above "bottle", "12" above "bottles".
/// Each caption is two strings, "bottles [one]" and "bottles [other]" (apple/tools/l10n.py),
/// because the catalog's plural variations must show the number, and here the figure does.
struct CountCaption {
    let key: LocalizedStringKey

    static func count(_ name: String, _ count: Int) -> CountCaption {
        let key: String = name + (isOne(count) ? " [one]" : " [other]")
        return CountCaption(key: LocalizedStringKey(key))
    }

    /// French uses the singular for 0 and 1, English for 1 only (the app's two languages).
    static func isOne(_ count: Int) -> Bool {
        Bundle.main.preferredLocalizations.first?.hasPrefix("fr") == true ? count == 0 || count == 1 : count == 1
    }
}

/// A figure with a caption, for summaries and insights.
struct StatTile: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    let value: String
    let caption: LocalizedStringKey

    init(value: String, caption: LocalizedStringKey, systemImage: String? = nil, isSelected: Bool = false, opens: Bool = false) {
        self.value = value
        self.caption = caption
        self.systemImage = systemImage
        self.isSelected = isSelected
        self.opens = opens
    }

    /// A count with its caption, such as 12 "bottles": the figure grouped as the locale does.
    init(count: Int, caption: CountCaption, systemImage: String? = nil, isSelected: Bool = false, opens: Bool = false) {
        self.init(value: count.formatted(), caption: caption.key, systemImage: systemImage, isSelected: isSelected, opens: opens)
    }
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
