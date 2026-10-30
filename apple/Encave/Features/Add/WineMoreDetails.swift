import CaveKit
import SwiftUI

/// "More details" of the wine form, closed by default: alcohol, classification, lieu-dit,
/// dosage (sparkling) or sweetness (still wines), the share of each grape, ageing, farming
/// and closure. Everything is optional; the values are vocabulary ids, shown by their labels.
struct WineMoreDetailsSection: View {
    @Binding var draft: WineDraft
    var startsExpanded = false
    @State private var isExpanded = false
    @State private var expandedOnce = false

    var body: some View {
        Section {
            DisclosureGroup(isExpanded: $isExpanded) {
                alcoholRow
                classificationRow
                LabeledField("Lieu-dit", text: $draft.lieuDit, prompt: Text("Climat or named vineyard"))
                    .fieldHelp("The named vineyard or climat on the label, such as Les Clos.")
                if draft.color == .sparkling {
                    VocabularyPicker("Dosage", kind: .dosage, ids: Vocabulary.dosages, selection: $draft.dosage)
                        .fieldHelp("How much sugar was added after the second fermentation, from Brut Nature to Doux.")
                } else {
                    VocabularyPicker("Sweetness", kind: .sweetness, ids: Vocabulary.sweetnesses, selection: $draft.sweetness)
                }
                let grapes = draft.grapeList
                if !grapes.isEmpty {
                    Text("Blend")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(.secondary)
                        .accessibilityAddTraits(.isHeader)
                    ForEach(grapes, id: \.self) { grape in
                        GrapeShareRow(grape: grape, draft: $draft)
                    }
                }
                ageingRow
                VocabularyPicker("Farming", kind: .farming, ids: Vocabulary.farmings, selection: $draft.farming.animation())
                if draft.farming != nil {
                    Toggle("Certified", isOn: $draft.farmingCertified)
                        .fieldHelp("Certified by a recognized label, such as AB, Demeter or HVE.")
                }
                VocabularyPicker("Closure", kind: .closure, ids: Vocabulary.closures, selection: $draft.closure)
            } label: {
                Text("More details")
                    .accessibilityIdentifier("form.moreDetails")
            }
            .id(WineFormField.moreDetails)
        } footer: {
            Text("Optional: alcohol, classification, blend, ageing, farming and closure.")
        }
        .onAppear {
            guard !expandedOnce else { return }
            expandedOnce = true
            // Open when Edit was chosen on one of these details of the wine's page.
            if startsExpanded { isExpanded = true }
        }
    }

    private var alcoholRow: some View {
        LabeledContent {
            HStack(spacing: 4) {
                TextField("Alcohol", value: $draft.abv, format: .number, prompt: Text(verbatim: Double(13.5).formatted()))
                    .multilineTextAlignment(.trailing)
                    .numericKeyboard()
                    .accessibilityLabel(Text("Alcohol, in percent"))
                Text(verbatim: "%")
                    .foregroundStyle(.secondary)
                    .accessibilityHidden(true)
            }
        } label: {
            Text("Alcohol")
        }
        .fieldHelp("Alcohol by volume, as printed on the label.")
        .aiEstimated(draft.isEstimatedByAI("abv"))
    }

    private var classificationRow: some View {
        HStack {
            LabeledField("Classification", text: $draft.classification, prompt: Text("Grand Cru, Cru Bourgeois…"))
            Menu {
                ForEach(Vocabulary.classificationSuggestions, id: \.self) { suggestion in
                    Button(suggestion) { draft.classification = suggestion }
                }
            } label: {
                Label("Suggestions", systemImage: "chevron.up.chevron.down")
                    .labelStyle(.iconOnly)
            }
            .menuIndicator(.hidden)
            .buttonStyle(.borderless)
            .fixedSize()
        }
        .fieldHelp("A classification printed on the label. Type your own or choose a suggestion.")
        .aiEstimated(draft.isEstimatedByAI("classification"))
    }

    /// Several ways of ageing can apply (oak barrel and on lees): chips with a checkmark.
    private var ageingRow: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Ageing")
            FlowLayout(spacing: 8) {
                ForEach(Vocabulary.ageings + draft.ageing.filter { !Vocabulary.ageings.contains($0) }, id: \.self) { id in
                    let isSelected = draft.ageing.contains(id)
                    ChoiceChip(verbatim: VocabularyLabels.text(.ageing, id), isSelected: isSelected) {
                        if isSelected { draft.ageing.removeAll { $0 == id } } else { draft.ageing.append(id) }
                    } leading: {
                        if isSelected { Image(systemName: "checkmark").accessibilityHidden(true) }
                    }
                }
            }
        }
        .padding(.vertical, 2)
    }
}

/// A choice among vocabulary ids, or none. An id from a newer version stays listed, so it
/// is kept as it is.
struct VocabularyPicker: View {
    let title: LocalizedStringKey
    let kind: Vocabulary.Kind
    let ids: [String]
    @Binding var selection: String?

    init(_ title: LocalizedStringKey, kind: Vocabulary.Kind, ids: [String], selection: Binding<String?>) {
        self.title = title
        self.kind = kind
        self.ids = ids
        self._selection = selection
    }

    var body: some View {
        Picker(title, selection: $selection) {
            Text("Not set").tag(String?.none)
            ForEach(ids, id: \.self) { id in
                Text(verbatim: VocabularyLabels.text(kind, id)).tag(String?.some(id))
            }
            if let selection, !ids.contains(selection) {
                Text(verbatim: VocabularyLabels.text(kind, selection)).tag(String?.some(selection))
            }
        }
    }
}

/// The share of one grape in the blend, in percent.
private struct GrapeShareRow: View {
    let grape: String
    @Binding var draft: WineDraft

    var body: some View {
        let grapeName = grape
        LabeledContent {
            HStack(spacing: 4) {
                TextField(grape, value: Binding(get: { draft.share(of: grape) }, set: { draft.setShare($0, of: grape) }),
                          format: .number, prompt: Text(verbatim: "–"))
                    .multilineTextAlignment(.trailing)
                    .numericKeyboard()
                    .accessibilityLabel(Text("Share of \(grapeName), in percent"))
                Text(verbatim: "%")
                    .foregroundStyle(.secondary)
                    .accessibilityHidden(true)
            }
        } label: {
            Text(verbatim: grape)
        }
    }
}

/// The details of a wine as text, for its page and the scan summary.
enum WineDetailText {
    /// "13.5%" ("13,5 %" in French).
    static func alcohol(_ abv: Decimal) -> String {
        (abv / 100).formatted(.percent.precision(.fractionLength(0...2)))
    }

    /// "Cabernet Sauvignon 70%, Merlot 30%": the grapes with a share, in the order listed.
    static func blend(of wine: Wine) -> String? {
        let parts = wine.grapes.compactMap { grape -> String? in
            guard let share = wine.share(of: grape) else { return nil }
            return "\(grape) \((Decimal(share) / 100).formatted(.percent))"
        }
        return parts.isEmpty ? nil : parts.joined(separator: ", ")
    }

    /// "Organic, certified".
    static func farming(of wine: Wine) -> String? {
        guard let farming = wine.farming else { return nil }
        let label = VocabularyLabels.text(.farming, farming)
        return wine.farmingCertified == true ? String(localized: "\(label), certified") : label
    }

    /// "Oak barrel, New oak".
    static func ageing(of wine: Wine) -> String? {
        guard let ageing = wine.ageing, !ageing.isEmpty else { return nil }
        return ageing.map { VocabularyLabels.text(.ageing, $0) }.joined(separator: ", ")
    }
}
