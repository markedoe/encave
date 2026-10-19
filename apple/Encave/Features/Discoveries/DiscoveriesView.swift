import CaveKit
import SwiftUI

/// Wines found elsewhere, with the shopping list and tag filters. Sections for the Journal list.
struct DiscoverySections: View {
    @Environment(CellarModel.self) private var model
    let query: String
    @State private var toBuyOnly = false
    @State private var tag: String?

    /// The chosen tag while some discovery still has it: otherwise its chip is gone and
    /// the filter could not be cleared.
    private var activeTag: String? {
        tag.flatMap { model.cellar.discoveryTags.contains($0) ? $0 : nil }
    }

    var body: some View {
        let tag = activeTag
        let items = model.cellar.discoveries(filter: DiscoveryFilter(query: query, tag: tag, toBuyOnly: toBuyOnly))
        if model.cellar.discoveries.isEmpty {
            ContentUnavailableView {
                Label("No discoveries yet", systemImage: "sparkles")
            } description: {
                Text("Liked a bottle at friends’ or in a restaurant? Scan it to remember it, add notes and tags, and put it on your shopping list.")
            } actions: {
                Button {
                    model.sheet = .discovery(discoveryID: nil)
                } label: {
                    Text("Remember a Wine").frame(minWidth: 200)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
            }
            .listRowBackground(Color.clear)
        } else {
            Section {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ChoiceChip(title: "To buy", isSelected: toBuyOnly) {
                            withAnimation(.snappy) { toBuyOnly.toggle() }
                        } leading: {
                            Image(systemName: "cart")
                            Text(verbatim: "\(model.cellar.shoppingListCount)")
                                .monospacedDigit()
                                .foregroundStyle(.secondary)
                        }
                        ForEach(model.cellar.discoveryTags, id: \.self) { name in
                            ChoiceChip(verbatim: name, isSelected: tag == name) {
                                withAnimation(.snappy) { self.tag = tag == name ? nil : name }
                            } leading: {
                                Image(systemName: "number").font(.caption.weight(.semibold))
                            }
                        }
                    }
                    .padding(.horizontal, 2)
                }
                .listRowInsets(EdgeInsets(top: 4, leading: 0, bottom: 4, trailing: 0))
                .listRowBackground(Color.clear)
            }
            Section {
                if items.isEmpty {
                    ContentUnavailableView.search(text: query)
                        .listRowBackground(Color.clear)
                }
                ForEach(items) { discovery in
                    Button {
                        model.sheet = .discovery(discoveryID: discovery.id)
                    } label: {
                        DiscoveryRow(discovery: discovery)
                    }
                    .buttonStyle(.plain)
                    .swipeActions(edge: .leading, allowsFullSwipe: true) {
                        Button {
                            model.buy(discovery)
                        } label: {
                            Label("Add to Cellar", systemImage: "plus")
                        }
                        .tint(.accentColor)
                    }
                    .swipeActions(edge: .trailing) {
                        Button(role: .destructive) {
                            model.deleteDiscovery(discovery.id)
                        } label: {
                            Label("Delete", systemImage: "trash")
                        }
                        Button {
                            toggleToBuy(discovery)
                        } label: {
                            if discovery.wantsToBuy {
                                Label("Remove from List", systemImage: "cart.badge.minus")
                            } else {
                                Label("To buy", systemImage: "cart.badge.plus")
                            }
                        }
                        .tint(.indigo)
                    }
                    .contextMenu {
                        Button("Edit", systemImage: "pencil") { model.sheet = .discovery(discoveryID: discovery.id) }
                        Button("Add to Cellar", systemImage: "plus") { model.buy(discovery) }
                        Button {
                            toggleToBuy(discovery)
                        } label: {
                            if discovery.wantsToBuy {
                                Label("Remove from List", systemImage: "cart.badge.minus")
                            } else {
                                Label("To buy", systemImage: "cart.badge.plus")
                            }
                        }
                        Divider()
                        Button("Delete", systemImage: "trash", role: .destructive) { model.deleteDiscovery(discovery.id) }
                    }
                }
            } header: {
                if toBuyOnly { Text("Shopping list") }
            }
        }
    }

    private func toggleToBuy(_ discovery: Discovery) {
        model.update { cellar in
            cellar.discoveries[discovery.id]?.wantsToBuy.toggle()
            if cellar.discoveries[discovery.id]?.wantsToBuy == true { cellar.discoveries[discovery.id]?.boughtAt = nil }
        }
    }
}

/// One wine found elsewhere: photo or bottle, where, rating, note and tags.
struct DiscoveryRow: View {
    @Environment(CellarModel.self) private var model
    let discovery: Discovery
    var showsWine = true

    var body: some View {
        let wine = model.cellar.wines[discovery.wineID]
        HStack(alignment: .top, spacing: 12) {
            if showsWine {
                if let wine, wine.hasShownPhoto {
                    WineImage(wine: wine, maxPixelSize: 200, inset: 3)
                        .frame(width: 44, height: 56)
                        .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                        .accessibilityHidden(true)
                } else {
                    BottleEnd(color: wine?.color, size: 30)
                }
            }
            VStack(alignment: .leading, spacing: 4) {
                HStack(alignment: .firstTextBaseline) {
                    if showsWine {
                        Text(wine?.displayName ?? String(localized: "Unknown wine"))
                            .font(.headline)
                    } else {
                        Text(discovery.date, format: .dateTime.day().month(.wide).year())
                            .font(.headline)
                    }
                    Spacer()
                    if discovery.wantsToBuy {
                        Label("To buy", systemImage: "cart.fill")
                            .labelStyle(.iconOnly)
                            .foregroundStyle(Color.accentColor)
                    } else if discovery.boughtAt != nil {
                        Label("Bought", systemImage: "checkmark.circle.fill")
                            .labelStyle(.iconOnly)
                            .foregroundStyle(.secondary)
                    }
                }
                HStack(spacing: 10) {
                    // The style in words, not only by the bottle's color.
                    if showsWine, let style = wine?.color?.name {
                        Text(style)
                    }
                    if let place = discovery.place {
                        Label(place, systemImage: discovery.setting?.systemImage ?? "mappin.and.ellipse")
                    } else if let setting = discovery.setting {
                        Label(setting.title, systemImage: setting.systemImage)
                    }
                    if showsWine {
                        Text(discovery.date, format: .dateTime.day().month(.abbreviated))
                    }
                }
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .labelStyle(CompactLabelStyle())
                if let rating = discovery.rating {
                    RatingView(rating: rating)
                }
                if let note = discovery.note {
                    Text(note)
                        .font(.subheadline)
                        .lineLimit(2)
                }
                if !discovery.tags.isEmpty || discovery.price != nil {
                    HStack(spacing: 8) {
                        if let price = discovery.price {
                            Text(price, format: .currency(code: discovery.currency ?? model.cellar.settings.currency))
                        }
                        if !discovery.tags.isEmpty {
                            Text(verbatim: discovery.tags.map { "#\($0)" }.joined(separator: " "))
                                .foregroundStyle(Color.accentColor)
                        }
                    }
                    .font(.caption)
                }
            }
        }
        .padding(.vertical, 2)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}
