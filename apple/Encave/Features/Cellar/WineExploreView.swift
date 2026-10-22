import CaveKit
import SwiftUI
#if canImport(UIKit)
import UIKit
#else
import AppKit
#endif

/// The wines of the cellar that share a detail of a wine (its producer, a grape, the
/// vintage…), opened by tapping that detail on the wine's page.
struct WineExploreView: View {
    @Environment(CellarModel.self) private var model
    let attribute: WineAttribute
    /// The wine whose page this was opened from: shown first, without a link back to itself.
    var currentWineID: UUID?

    var body: some View {
        let all = model.cellar.wines(sharing: attribute)
        let current = all.first { $0.wine.id == currentWineID }
        let groups = all.filter { $0.wine.id != currentWineID }
        let bottleCount = groups.reduce(0) { $0 + $1.count }
        List {
            if let current {
                Section("This wine") {
                    WineRow(group: current)
                }
            }
            if groups.isEmpty {
                ContentUnavailableView("No other wine in the cellar", systemImage: "wineglass",
                                       description: Text("No other bottle with this detail is in the cellar or on order."))
                    .listRowBackground(Color.clear)
            } else {
                Section {
                    ForEach(groups) { group in
                        NavigationLink {
                            WineDetailView(wineID: group.wine.id)
                        } label: {
                            WineRow(group: group)
                        }
                        .accessibilityIdentifier("explore.row")
                    }
                } header: {
                    if current != nil {
                        Text("\(groups.count) other wines") + Text(verbatim: " · ") + Text("\(bottleCount) bottles")
                    } else {
                        Text("\(groups.count) wines") + Text(verbatim: " · ") + Text("\(bottleCount) bottles")
                    }
                }
            }
        }
        .navigationTitle(attribute.title)
        .inlineTitle()
    }
}

extension WineAttribute {
    /// The value as shown, copied and searched.
    var value: String {
        switch self {
        case .producer(let text), .region(let text), .appellation(let text), .country(let text), .grape(let text), .tag(let text):
            text.trimmingCharacters(in: .whitespacesAndNewlines)
        case .vintage(let year): String(year)
        case .color(let color): color.name
        }
    }

    var title: Text {
        switch self {
        case .color(let color): Text(color.title)
        default: Text(verbatim: value)
        }
    }

    /// What a tap does, for VoiceOver.
    var hint: Text {
        switch self {
        case .producer: Text("Shows the other wines from this producer.")
        case .region: Text("Shows the other wines from this region.")
        case .appellation: Text("Shows the other wines of this appellation.")
        case .country: Text("Shows the other wines from this country.")
        case .grape: Text("Shows the other wines made with this grape.")
        case .vintage: Text("Shows the other wines of this vintage.")
        case .color: Text("Shows the other wines of this style.")
        case .tag: Text("Shows the other wines with this tag.")
        }
    }

    /// The field of the wine form that holds it.
    var field: WineFormField {
        switch self {
        case .producer: .producer
        case .region: .region
        case .appellation: .appellation
        case .country: .country
        case .grape: .grapes
        case .vintage: .vintage
        case .color: .style
        case .tag: .tags
        }
    }

    /// Only what describes the wine on its label may go to the web search (see the privacy
    /// rules): never tags, notes, prices or places.
    var isSearchable: Bool {
        switch self {
        case .producer, .appellation: true
        default: false
        }
    }

    var identifier: String {
        switch self {
        case .producer: "producer"
        case .region: "region"
        case .appellation: "appellation"
        case .country: "country"
        case .grape: "grape"
        case .vintage: "vintage"
        case .color: "style"
        case .tag: "tag"
        }
    }
}

/// A detail of the wine's page that other wines can share: tap to list them (or, when no
/// other wine has it, to edit it). Touch and hold to copy, edit or search the web for it.
struct ExploreDetailRow<Value: View>: View {
    @Environment(CellarModel.self) private var model
    let title: LocalizedStringKey
    let attribute: WineAttribute
    let wineID: UUID
    let content: Value
    /// Set when web search is on and the value may be searched.
    let onSearchWeb: ((String) -> Void)?

    init(title: LocalizedStringKey, attribute: WineAttribute, wineID: UUID,
         @ViewBuilder content: () -> Value, onSearchWeb: ((String) -> Void)? = nil) {
        self.title = title
        self.attribute = attribute
        self.wineID = wineID
        self.content = content()
        self.onSearchWeb = onSearchWeb
    }

    var body: some View {
        let sharing = model.cellar.wines(sharing: attribute)
        let others = sharing.filter { $0.wine.id != wineID }.count
        Group {
            if others > 0 {
                NavigationLink {
                    WineExploreView(attribute: attribute, currentWineID: wineID)
                } label: {
                    LabeledContent {
                        VStack(alignment: .trailing, spacing: 2) {
                            content
                            Text("\(sharing.count) wines")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        .multilineTextAlignment(.trailing)
                    } label: {
                        Text(title)
                    }
                }
                .accessibilityHint(attribute.hint)
                .accessibilityIdentifier("detail.explore.\(attribute.identifier)")
            } else {
                // No other wine shares it: a list of this wine alone would lead nowhere.
                EditDetailRow(hint: Text("Edits this detail of the wine.")) {
                    model.sheet = .editWine(wineID: wineID, field: attribute.field)
                } content: {
                    LabeledContent {
                        content.multilineTextAlignment(.trailing)
                    } label: {
                        Text(title)
                    }
                }
                .accessibilityIdentifier("detail.edit.\(attribute.identifier)")
            }
        }
        .contextMenu {
            DetailMenu(value: attribute.value,
                       onEdit: { model.sheet = .editWine(wineID: wineID, field: attribute.field) },
                       onSearchWeb: attribute.isSearchable ? onSearchWeb : nil)
        }
    }
}

extension ExploreDetailRow where Value == Text {
    init(title: LocalizedStringKey, attribute: WineAttribute, wineID: UUID, onSearchWeb: ((String) -> Void)? = nil) {
        self.init(title: title, attribute: attribute, wineID: wineID,
                  content: { Text(verbatim: attribute.value) }, onSearchWeb: onSearchWeb)
    }
}

/// A detail of the wine's page with nothing to explore: tap to edit it.
struct EditDetailRow<Content: View>: View {
    let hint: Text
    let action: () -> Void
    @ViewBuilder var content: Content

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                content
                    .frame(maxWidth: .infinity, alignment: .leading)
                // Said in words by the hint for VoiceOver; the pencil shows it can be changed.
                Image(systemName: "pencil")
                    .imageScale(.small)
                    .foregroundStyle(.tertiary)
                    .accessibilityHidden(true)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityHint(hint)
    }
}

/// Copy, Edit and, when web search is on, Search the Web for one detail.
struct DetailMenu: View {
    /// The text copied and searched; nil when there is nothing to copy.
    let value: String?
    var onEdit: (() -> Void)?
    var onSearchWeb: ((String) -> Void)?

    var body: some View {
        if let value {
            Button("Copy", systemImage: "doc.on.doc") { Clipboard.copy(value) }
        }
        if let onEdit {
            Button("Edit", systemImage: "pencil", action: onEdit)
        }
        if let onSearchWeb, let value {
            Button("Search the Web", systemImage: "globe") { onSearchWeb(value) }
        }
    }
}

enum Clipboard {
    @MainActor static func copy(_ text: String) {
        #if canImport(UIKit)
        UIPasteboard.general.string = text
        #else
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(text, forType: .string)
        #endif
    }
}

/// Search terms for "Search the Web", as a sheet item.
struct WebSearchTerms: Identifiable {
    let id = UUID()
    let terms: String
}
