import CaveKit
import SwiftUI

extension Discovery {
    /// "Tasted elsewhere · Lisbon": where it comes from, in words.
    var sourceText: String {
        if let place = place ?? setting?.name {
            String(localized: "Tasted elsewhere · \(place)")
        } else {
            String(localized: "Tasted elsewhere")
        }
    }
}

/// Swipe actions and context menu of a wine tasted elsewhere: add it to the cellar,
/// put it on the shopping list or take it off, delete it.
struct ElsewhereActions: ViewModifier {
    @Environment(CellarModel.self) private var model
    let discovery: Discovery

    func body(content: Content) -> some View {
        content
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
                    toggleToBuy()
                } label: {
                    toBuyLabel
                }
                .tint(.indigo)
            }
            .contextMenu {
                Button("Edit", systemImage: "pencil") { model.sheet = .discovery(discoveryID: discovery.id) }
                Button("Add to Cellar", systemImage: "plus") { model.buy(discovery) }
                Button {
                    toggleToBuy()
                } label: {
                    toBuyLabel
                }
                Divider()
                Button("Delete", systemImage: "trash", role: .destructive) { model.deleteDiscovery(discovery.id) }
            }
    }

    @ViewBuilder private var toBuyLabel: some View {
        if discovery.wantsToBuy {
            Label("Remove from List", systemImage: "cart.badge.minus")
        } else {
            Label("To buy", systemImage: "cart.badge.plus")
        }
    }

    private func toggleToBuy() {
        model.update { cellar in
            cellar.discoveries[discovery.id]?.wantsToBuy.toggle()
            if cellar.discoveries[discovery.id]?.wantsToBuy == true { cellar.discoveries[discovery.id]?.boughtAt = nil }
        }
    }
}

/// One wine tasted elsewhere: photo or bottle, where, rating, note and tags.
struct DiscoveryRow: View {
    @Environment(CellarModel.self) private var model
    let discovery: Discovery
    var showsWine = true
    /// In the Journal: says in words that it was tasted elsewhere, and where.
    var showsSource = false

    var body: some View {
        let wine = model.cellar.wines[discovery.wineID]
        let row = HStack(alignment: .top, spacing: 12) {
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
                if showsSource {
                    Label(discovery.sourceText, systemImage: discovery.setting?.systemImage ?? "mappin.and.ellipse")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .labelStyle(CompactLabelStyle())
                }
                HStack(spacing: 10) {
                    // The style in words, not only by the bottle's color.
                    if showsWine, let style = wine?.color?.name {
                        Text(style)
                    }
                    if !showsSource {
                        if let place = discovery.place {
                            Label(place, systemImage: discovery.setting?.systemImage ?? "mappin.and.ellipse")
                        } else if let setting = discovery.setting {
                            Label(setting.title, systemImage: setting.systemImage)
                        }
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
        if showsSource {
            row
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(accessibilityText(wine: wine))
        } else {
            row.accessibilityElement(children: .combine)
        }
    }

    /// "Tasted elsewhere · Lisbon, Alvarinho 2023, 5 stars, March 3, …".
    private func accessibilityText(wine: Wine?) -> String {
        var parts = [discovery.sourceText, wine?.displayName ?? String(localized: "Unknown wine")]
        // The style in words, as shown under the name.
        if let style = wine?.color?.name { parts.append(style) }
        if let rating = discovery.rating { parts.append(String(localized: "\(rating) stars")) }
        parts.append(discovery.date.formatted(.dateTime.day().month(.wide)))
        if discovery.wantsToBuy {
            parts.append(String(localized: "To buy"))
        } else if discovery.boughtAt != nil {
            parts.append(String(localized: "Bought"))
        }
        if let note = discovery.note { parts.append(note) }
        if let price = discovery.price {
            parts.append(price.formatted(.currency(code: discovery.currency ?? model.cellar.settings.currency)))
        }
        if !discovery.tags.isEmpty { parts.append(discovery.tags.joined(separator: ", ")) }
        return parts.joined(separator: ", ")
    }
}
