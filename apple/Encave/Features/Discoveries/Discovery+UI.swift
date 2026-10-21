import CaveKit
import SwiftUI

extension Discovery.Setting {
    var title: LocalizedStringKey {
        switch self {
        case .friends: "At friends'"
        case .restaurant: "Restaurant"
        case .travel: "Travel"
        case .shop: "Shop"
        case .tasting: "Tasting event"
        case .other: "Elsewhere"
        }
    }

    /// The title as a string, to build sentences and VoiceOver labels.
    var name: String {
        switch self {
        case .friends: String(localized: "At friends'")
        case .restaurant: String(localized: "Restaurant")
        case .travel: String(localized: "Travel")
        case .shop: String(localized: "Shop")
        case .tasting: String(localized: "Tasting event")
        case .other: String(localized: "Elsewhere")
        }
    }

    var systemImage: String {
        switch self {
        case .friends: "person.2"
        case .restaurant: "fork.knife"
        case .travel: "airplane"
        case .shop: "bag"
        case .tasting: "wineglass"
        case .other: "mappin.and.ellipse"
        }
    }
}

/// Tags shown as chips: the ones chosen, then suggestions, plus a field to add one.
struct TagEditor: View {
    @Binding var tags: [String]
    let suggestions: [String]
    /// Text being typed; the editor adds it on save so nothing is lost.
    @Binding var newTag: String

    private var shown: [String] {
        let chosen = Set(tags.map { $0.lowercased() })
        return tags + suggestions.filter { !chosen.contains($0.lowercased()) }.prefix(12)
    }

    var body: some View {
        if !shown.isEmpty {
            FlowLayout(spacing: 8) {
                ForEach(shown, id: \.self) { tag in
                    let isSelected = tags.contains(tag)
                    ChoiceChip(verbatim: tag, isSelected: isSelected) {
                        withAnimation(.snappy) {
                            if isSelected { tags.removeAll { $0 == tag } } else { tags.append(tag) }
                        }
                    } leading: {
                        Image(systemName: isSelected ? "checkmark" : "number")
                            .font(.caption.weight(.semibold))
                    }
                }
            }
            .padding(.vertical, 4)
        }
        TextField("Add a tag", text: $newTag, prompt: Text("Gift idea, for summer…"))
            .submitLabel(.done)
            .onSubmit(add)
            .onChange(of: newTag) { _, text in
                if text.contains(",") { add() }
            }
    }

    private func add() {
        let typed = newTag.split(separator: ",").map(String.init)
        withAnimation(.snappy) { tags = Discovery.normalized(tags + typed) }
        newTag = ""
    }
}
