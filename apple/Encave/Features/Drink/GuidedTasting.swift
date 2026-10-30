import CaveKit
import SwiftUI

/// The guided tasting sheet, under "More details" in a tasting: appearance (intensity and
/// hue), aromas as chips grouped by family, and structure from 1 to 5. Each part is
/// optional; what suits the wine's style comes first, the rest is one tap away.
struct GuidedTastingRows: View {
    @Binding var draft: TastingDraft
    let color: WineColor?
    /// What to expect from the wine, behind a disclosure so it does not lead the taster.
    var expected: ExpectedProfile?
    @State private var showsExpected = false
    @State private var showsAllHues = false
    @State private var showsAllAromas = false
    @State private var aromaQuery = ""

    var body: some View {
        GuidedHeader(title: "Appearance")
        intensityRow
        hueRow

        GuidedHeader(title: "Aromas")
        if let expected, !(expected.aromas ?? []).isEmpty || expected.structure != nil {
            DisclosureGroup(isExpanded: $showsExpected) {
                ExpectedProfileRows(profile: expected, compact: true)
            } label: {
                Label(showsExpected ? LocalizedStringKey("What to expect") : LocalizedStringKey("Show What to Expect"), systemImage: "eye")
                    .accessibilityIdentifier("tasting.showExpected")
            }
        }
        TextField("Search Aromas", text: $aromaQuery, prompt: Text("Cherry, vanilla, leather…"))
            .plainInput()
            .accessibilityIdentifier("tasting.aromaSearch")
        let families = visibleFamilies
        if families.isEmpty {
            Text("No aromas found")
                .foregroundStyle(.secondary)
        }
        ForEach(families) { family in
            VStack(alignment: .leading, spacing: 6) {
                Text(verbatim: VocabularyLabels.text(.aromaFamily, family.id))
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .accessibilityAddTraits(.isHeader)
                FlowLayout(spacing: 8) {
                    ForEach(family.aromas, id: \.id) { aroma in
                        let isSelected = draft.aromas.contains(aroma.id)
                        ChoiceChip(verbatim: VocabularyLabels.text(.aroma, aroma.id), isSelected: isSelected) {
                            toggleAroma(aroma.id)
                        } leading: {
                            // Chosen: a checkmark as well as the fill, not the color alone.
                            if isSelected { Image(systemName: "checkmark").accessibilityHidden(true) }
                        }
                    }
                }
            }
            .padding(.vertical, 2)
        }
        if aromaQuery.isEmpty, hasOtherAromas {
            Button {
                withAnimation { showsAllAromas.toggle() }
            } label: {
                if showsAllAromas {
                    Label("Fewer Aromas", systemImage: "chevron.up")
                } else {
                    Label("More Aromas", systemImage: "chevron.down")
                }
            }
            .accessibilityIdentifier("tasting.moreAromas")
        }

        GuidedHeader(title: "Structure")
        ForEach(Vocabulary.structure.filter { $0.isOffered(for: color, value: draft.levels[$0.id]) }) { attribute in
            LevelRow(attribute: attribute, value: Binding(
                get: { draft.levels[attribute.id] },
                set: { draft.levels[attribute.id] = $0 }
            ))
        }
    }

    // MARK: Appearance

    private var intensityRow: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("Intensity")
                Spacer()
                if draft.intensity != nil {
                    ClearButton(fieldName: String(localized: "Intensity")) { draft.intensity = nil }
                }
            }
            Picker("Intensity", selection: $draft.intensity) {
                ForEach(Vocabulary.intensities, id: \.self) { id in
                    Text(verbatim: VocabularyLabels.text(.intensity, id)).tag(Optional(id))
                }
            }
            .pickerStyle(.segmented)
            .labelsHidden()
        }
        .accessibilityIdentifier("tasting.intensity")
    }

    private var hueRow: some View {
        // The hues of the wine's style, and the one chosen; the others behind Show All.
        let shown = Vocabulary.hues.filter { showsAllHues || $0.suits(color) || $0.id == draft.hue }
        let hidden = Vocabulary.hues.count - shown.count
        return VStack(alignment: .leading, spacing: 8) {
            Text("Hue")
            FlowLayout(spacing: 8) {
                ForEach(shown, id: \.id) { hue in
                    let isSelected = draft.hue == hue.id
                    ChoiceChip(verbatim: VocabularyLabels.text(.hue, hue.id), isSelected: isSelected) {
                        draft.hue = isSelected ? nil : hue.id
                    } leading: {
                        if isSelected { Image(systemName: "checkmark").accessibilityHidden(true) }
                    }
                }
                if hidden > 0 || showsAllHues {
                    Button(showsAllHues ? LocalizedStringKey("Show Fewer") : LocalizedStringKey("Show All")) {
                        withAnimation { showsAllHues.toggle() }
                    }
                    .buttonStyle(.borderless)
                    .frame(minHeight: 44)
                }
            }
        }
        .padding(.vertical, 2)
    }

    // MARK: Aromas

    /// An aroma family with the aromas shown now.
    private struct ShownFamily: Identifiable {
        let id: String
        let aromas: [Vocabulary.Entry]
    }

    /// While searching: every aroma whose name (or family) matches. Otherwise those that
    /// suit the style and those chosen, or all of them after More Aromas.
    private var visibleFamilies: [ShownFamily] {
        let query = aromaQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        return Vocabulary.aromaFamilies.compactMap { family in
            let familyMatches = !query.isEmpty && Self.matches(VocabularyLabels.text(.aromaFamily, family.id), query)
            let aromas = family.aromas.filter { aroma in
                if !query.isEmpty { return familyMatches || Self.matches(VocabularyLabels.text(.aroma, aroma.id), query) }
                return showsAllAromas || aroma.suits(color) || draft.aromas.contains(aroma.id)
            }
            return aromas.isEmpty ? nil : ShownFamily(id: family.id, aromas: aromas)
        }
    }

    /// Some aromas are left out for this style.
    private var hasOtherAromas: Bool {
        Vocabulary.aromaFamilies.contains { family in family.aromas.contains { !$0.suits(color) } }
    }

    private static func matches(_ label: String, _ query: String) -> Bool {
        label.range(of: query, options: [.caseInsensitive, .diacriticInsensitive]) != nil
    }

    private func toggleAroma(_ id: String) {
        if let index = draft.aromas.firstIndex(of: id) {
            draft.aromas.remove(at: index)
        } else {
            draft.aromas.append(id)
        }
    }
}

/// A part of the guided sheet: Appearance, Aromas, Structure.
private struct GuidedHeader: View {
    let title: LocalizedStringKey

    var body: some View {
        Text(title)
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(.secondary)
            .padding(.top, 6)
            .accessibilityAddTraits(.isHeader)
    }
}

/// A small button that clears one field.
private struct ClearButton: View {
    let fieldName: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: "xmark.circle.fill")
                .foregroundStyle(.secondary)
        }
        .buttonStyle(.borderless)
        .accessibilityLabel(Text("Clear \(fieldName)"))
    }
}

/// One structure attribute from 1 to 5, as five segments filled up to the level, with its
/// word ("Medium+") written beside the name. Tapping the current level clears it, as
/// with the stars. For VoiceOver, one adjustable element that says the word.
struct LevelRow: View {
    let attribute: Vocabulary.Attribute
    @Binding var value: Int?

    var body: some View {
        let name = VocabularyLabels.text(.structure, attribute.id)
        let word = value.flatMap { Vocabulary.level($0, of: attribute) }.map { VocabularyLabels.levelText($0, of: attribute.id) }
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .firstTextBaseline) {
                Text(verbatim: name)
                Spacer()
                if let word {
                    Text(verbatim: word)
                        .fontWeight(.semibold)
                        .foregroundStyle(Color.accentColor)
                } else {
                    Text("Not noted")
                        .foregroundStyle(.secondary)
                }
            }
            HStack(spacing: 6) {
                ForEach(1...5, id: \.self) { level in
                    Button {
                        value = value == level ? nil : level
                    } label: {
                        Capsule()
                            .fill(level <= (value ?? 0) ? Color.accentColor : Color.secondary.opacity(0.2))
                            .frame(height: 10)
                            .frame(maxWidth: .infinity, minHeight: 36)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .sensoryFeedback(.selection, trigger: value)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(verbatim: name))
        .accessibilityValue(Text(verbatim: word ?? String(localized: "Not noted")))
        .accessibilityAdjustableAction { direction in
            switch direction {
            case .increment: value = min(5, (value ?? 0) + 1)
            case .decrement: value = (value ?? 0) <= 1 ? nil : (value ?? 1) - 1
            @unknown default: break
            }
        }
        .accessibilityIdentifier("tasting.level.\(attribute.id)")
    }
}
