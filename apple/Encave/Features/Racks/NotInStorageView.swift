import CaveKit
import SwiftUI

/// Navigation value for the bottles not in storage, from Storage.
struct NotInStorageRoute: Hashable {}

/// The bottles of the cellar without a position, by wine: each is put away from here (the
/// Move sheet), or drunk. On the Mac, a row dragged onto a storage of the sidebar goes to
/// its first free position. The same list as Android's.
struct NotInStorageView: View {
    @Environment(CellarModel.self) private var model

    var body: some View {
        let bottles = model.cellar.looseBottlesByWine
        List {
            if bottles.isEmpty {
                ContentUnavailableView("Every bottle is in storage", systemImage: BottlePlace.notInStorageIcon,
                                       description: Text("Bottles without a position appear here, to put them away."))
                    .listRowBackground(Color.clear)
            } else {
                Section {
                    ForEach(bottles) { bottle in
                        LooseBottleRow(bottle: bottle)
                            .sidebarDraggable(BottleDrag(bottleID: bottle.id,
                                                         name: model.cellar.wines[bottle.wineID]?.displayName ?? ""))
                    }
                } footer: {
                    Text("Put Away opens the storages to choose a position. You can also tap a free position in a storage.")
                }
            }
        }
        .navigationTitle("Not in Storage")
    }
}

extension Cellar {
    /// The bottles not in storage, wine by wine (by name), oldest first within a wine.
    var looseBottlesByWine: [Bottle] {
        bottlesNotInStorage.sorted { a, b in
            let nameA = wines[a.wineID]?.displayName ?? "", nameB = wines[b.wineID]?.displayName ?? ""
            if nameA != nameB { return nameA.localizedStandardCompare(nameB) == .orderedAscending }
            if a.wineID != b.wineID { return a.wineID.uuidString < b.wineID.uuidString }
            return Bottle.addedOrder(a, b)
        }
    }
}

/// A bottle out of storage: the wine, where it is (its note, or "Not in storage"), and the
/// occasion it is kept for.
struct LooseBottleLabel: View {
    @Environment(CellarModel.self) private var model
    let bottle: Bottle

    var body: some View {
        let wine = model.cellar.wines[bottle.wineID]
        HStack(spacing: 12) {
            WineThumbnail(wine: wine)
            VStack(alignment: .leading, spacing: 3) {
                Text(wine?.displayName ?? String(localized: "Unknown wine"))
                    .font(.body.weight(.semibold))
                if let style = wine?.styleLine, !style.isEmpty {
                    Text(style)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                Label(model.placeLabel(for: bottle), systemImage: model.locationIcon(for: bottle))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .labelStyle(CompactLabelStyle())
                if let occasionName = bottle.reservedFor {
                    Label("Kept for \(occasionName)", systemImage: "gift")
                        .font(.caption)
                        .foregroundStyle(Color.accentColor)
                }
            }
        }
        .accessibilityElement(children: .combine)
    }
}

/// One bottle of the list, with Put Away in words and the bottle's actions.
private struct LooseBottleRow: View {
    @Environment(CellarModel.self) private var model
    @Environment(SceneRouter.self) private var router: SceneRouter?
    let bottle: Bottle

    var body: some View {
        HStack(spacing: 8) {
            LooseBottleLabel(bottle: bottle)
            Spacer(minLength: 8)
            Button {
                router?.sheet = .moveBottle(bottleID: bottle.id)
            } label: {
                ActionLabel("Put Away", systemImage: BottlePlace.putAwayIcon)
            }
            .buttonStyle(.bordered)
            .controlSize(.small)
            .accessibilityIdentifier("notInStorage.putAway")
        }
        .swipeActions(edge: .leading, allowsFullSwipe: true) {
            Button {
                router?.sheet = .drink(bottleID: bottle.id)
            } label: {
                Label("Drink", systemImage: "wineglass")
            }
            .tint(.accentColor)
        }
        .contextMenu {
            Button("Drink", systemImage: "wineglass") { router?.sheet = .drink(bottleID: bottle.id) }
            Button("Put Away…", systemImage: BottlePlace.putAwayIcon) { router?.sheet = .moveBottle(bottleID: bottle.id) }
            Button("Show Wine", systemImage: "info.circle") { router?.perform(.openWine(bottle.wineID)) }
            BottleDocumentButtons(bottle: bottle) { router?.sheet = $0 }
            Divider()
            NoLongerInCellarButton { model.markGone(bottle.id) }
        }
        .accessibilityIdentifier("notInStorage.row")
    }
}

/// Storage: how many bottles are not in storage, opening their list.
struct NotInStorageCard: View {
    let count: Int

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: BottlePlace.notInStorageIcon)
                .font(.title2)
                .foregroundStyle(.secondary)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text("\(count) bottles not in storage")
                    .font(.headline)
                Text("Put them away in a storage.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
            Image(systemName: "chevron.forward")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(.tertiary)
                .accessibilityHidden(true)
        }
        .padding(16)
        .background(Color.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .contentShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
        .accessibilityElement(children: .combine)
        .accessibilityHint(Text("Shows the bottles to put away."))
    }
}

/// A free position tapped while bottles are out of storage: one of them goes there.
struct PutBottleHereView: View {
    @Environment(CellarModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let slot: Slot

    var body: some View {
        NavigationStack {
            List {
                ForEach(model.cellar.looseBottlesByWine) { bottle in
                    Button {
                        // Taken meanwhile (sync, another window): nothing moves.
                        if model.cellar.bottle(at: slot) == nil { model.move(bottle.id, to: slot) }
                        dismiss()
                    } label: {
                        LooseBottleLabel(bottle: bottle)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("putHere.row")
                }
            }
            .navigationTitle(Text(placeName))
            .inlineTitle()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                        .accessibilityIdentifier("sheet.cancel")
                }
            }
        }
        .macSheetFrame(width: 440, height: 520)
    }

    /// "Main rack · B3".
    private var placeName: String {
        guard let rack = model.cellar.rack(slot.rackID) else { return "" }
        return "\(rack.name) · \(rack.displayLabel(for: slot))"
    }
}
