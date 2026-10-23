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

/// A figure on one line, for the largest text sizes, where tiles would fill the first
/// screen: icon, figure, caption, then a chevron (opens) or a checkmark (filter on).
struct CompactStatRow: View {
    let value: String
    let caption: LocalizedStringKey
    var systemImage: String?
    var isSelected = false
    var opens = false

    init(count: Int, caption: CountCaption, systemImage: String? = nil, isSelected: Bool = false, opens: Bool = false) {
        value = count.formatted()
        self.caption = caption.key
        self.systemImage = systemImage
        self.isSelected = isSelected
        self.opens = opens
    }

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            if let systemImage {
                Image(systemName: systemImage)
                    .foregroundStyle(Color.accentColor)
                    .accessibilityHidden(true)
            }
            // The figure and its caption read as one phrase and wrap together.
            (Text(verbatim: value).fontWeight(.semibold).monospacedDigit() + Text(verbatim: " ") + Text(caption))
                .frame(maxWidth: .infinity, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)
            if isSelected {
                Image(systemName: "checkmark")
                    .fontWeight(.semibold)
                    .foregroundStyle(Color.accentColor)
                    .accessibilityHidden(true)
            } else if opens {
                Image(systemName: "chevron.right")
                    .foregroundStyle(.tertiary)
                    .accessibilityHidden(true)
            }
        }
        .padding(.vertical, 8)
        .padding(.horizontal, 12)
        .contentShape(Rectangle())
        .background(isSelected ? Color.accentColor.opacity(0.15) : .clear)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}

extension DynamicTypeSize {
    /// The body text size against the default (Large, 17 pt), as Android's font scale:
    /// the accessibility sizes start at 28 pt, about 1.65.
    var textScale: Double {
        let points: Double = switch self {
        case .xSmall: 14
        case .small: 15
        case .medium: 16
        case .large: 17
        case .xLarge: 19
        case .xxLarge: 21
        case .xxxLarge: 23
        case .accessibility1: 28
        case .accessibility2: 33
        case .accessibility3: 40
        case .accessibility4: 47
        case .accessibility5: 53
        @unknown default: isAccessibilitySize ? 28 : 17
        }
        return points / 17
    }
}
