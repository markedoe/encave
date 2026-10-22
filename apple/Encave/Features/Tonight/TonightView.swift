import CaveKit
import SwiftUI

/// What to open: a suggestion for tonight, food pairing, then bottles by maturity.
/// On iPhone and iPad it pushes the wine; on the Mac it sets `selection`, shown in the next column.
struct TonightView: View {
    @Environment(CellarModel.self) private var model
    var selection: Binding<UUID?>?
    /// "Another idea" taps since the screen opened.
    @State private var pickOffset = 0
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        if let selection {
            list(selection: selection)
                .navigationTitle("Tonight")
                #if os(macOS)
                .toolbar {
                    ToolbarItem { MenuBarToggle() }
                }
                #endif
                .navigationDestination(for: FoodPairingRoute.self) { _ in FoodPairingView(selection: selection) }
                .navigationDestination(for: AskCellarRoute.self) { _ in AskCellarView(selection: selection) }
        } else {
            NavigationStack {
                list(selection: nil)
                    .navigationTitle("Tonight")
                    #if os(iOS)
                    .toolbar { SettingsToolbarItem() }
                    #endif
                    .navigationDestination(for: UUID.self) { WineDetailView(wineID: $0) }
                    .navigationDestination(for: FoodPairingRoute.self) { _ in FoodPairingView() }
                    .navigationDestination(for: AskCellarRoute.self) { _ in AskCellarView() }
            }
        }
    }

    @ViewBuilder
    private func list(selection: Binding<UUID?>?) -> some View {
        let sections = model.cellar.groupsByMaturity(year: model.currentYear)
        if model.bottlesInCellar.isEmpty {
            EmptyTonightView()
        } else if let selection {
            List(selection: selection) { rows(sections: sections, linking: false) }
        } else {
            List { rows(sections: sections, linking: true) }
        }
    }

    /// Today's place in the suggestions: a new wine each day, the same all day.
    private var dayOfYear: Int { Calendar.current.ordinality(of: .day, in: .year, for: .now) ?? 1 }

    @ViewBuilder
    private func rows(sections: [MaturitySection], linking: Bool) -> some View {
        let year = model.currentYear
        let candidates = model.cellar.tonightCandidates(year: year)
        let reserved = model.cellar.reservedBottleCount
        Section {
            if sections.isEmpty {
                // Every bottle is kept for an occasion.
                Label("All your bottles are kept for an occasion", systemImage: "gift")
                    .foregroundStyle(.secondary)
                    .accessibilityIdentifier("tonight.allReserved")
            } else if candidates.isEmpty {
                NothingReadyCard(next: model.cellar.nextToBeReady(year: year))
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
            } else {
                let start = Cellar.tonightStartIndex(dayOfYear: dayOfYear, count: candidates.count)
                TonightPick(group: candidates[(start + pickOffset) % candidates.count]) {
                    let next = pickOffset + 1
                    withMotion(.snappy, reduceMotion: reduceMotion) { pickOffset = next }
                    // The card changes without moving focus: say which wine it shows now.
                    let wine = candidates[(start + next) % candidates.count].wine
                    AccessibilityNotification.Announcement([wine.displayName, wine.styleLine]
                        .filter { !$0.isEmpty }.joined(separator: ", ")).post()
                }
                .listRowInsets(EdgeInsets())
                .listRowBackground(Color.clear)
            }
        } footer: {
            if reserved > 0, !sections.isEmpty {
                Text("Bottles kept for an occasion are not suggested: \(reserved)")
            }
        }
        ReminderOffer()
        Section {
            NavigationLink(value: FoodPairingRoute()) {
                Label {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Food pairing").font(.headline)
                        Text("Find the right bottle for your dish").font(.subheadline).foregroundStyle(.secondary)
                    }
                } icon: {
                    Image(systemName: "fork.knife").foregroundStyle(Color.accentColor)
                }
            }
            if CellarAssistant.isAvailable {
                NavigationLink(value: AskCellarRoute()) {
                    Label {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Ask your cellar").font(.headline)
                            Text("A question, answered with your bottles, on this device").font(.subheadline).foregroundStyle(.secondary)
                        }
                    } icon: {
                        Image(systemName: "sparkles").foregroundStyle(Color.accentColor)
                    }
                }
                .accessibilityIdentifier("tonight.ask")
            }
        }
        ForEach(sections) { section in
            Section {
                ForEach(section.groups) { group in
                    Group {
                        if linking {
                            NavigationLink(value: group.wine.id) { WineRow(group: group) }
                        } else {
                            WineRow(group: group).tag(group.wine.id)
                        }
                    }
                    .contextMenu { WineContextMenu(group: group) }
                }
            } header: {
                MaturityLabel(maturity: section.maturity)
                    .font(.subheadline.weight(.semibold))
                    .textCase(nil)
            }
        }
    }
}

/// No bottle in the cellar: say what Tonight does and how to start.
private struct EmptyTonightView: View {
    @Environment(CellarModel.self) private var model
    @Environment(SceneRouter.self) private var router: SceneRouter?
    @State private var confirmingSample = false

    var body: some View {
        ContentUnavailableView {
            Label("Nothing to suggest yet", systemImage: "moon.stars")
        } description: {
            Text("Add bottles with a drinking window to see what is ready tonight.")
        } actions: {
            VStack(spacing: 12) {
                Button {
                    router?.sheet = .addBottle(AddBottleContext())
                } label: {
                    Text("Add a Bottle").frame(minWidth: 200)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .accessibilityIdentifier("tonight.empty.add")
                if model.canLoadSample {
                    Button("Load sample cellar") { confirmingSample = true }
                }
            }
        }
        .confirmationDialog("Load the sample cellar?", isPresented: $confirmingSample, titleVisibility: .visible) {
            Button("Load sample cellar") { model.loadSample() }
        } message: {
            Text("Example wines, racks and tastings to try Encave. Remove them at any time with Remove Sample at the top of the Cellar.")
        }
    }
}

/// Nothing ready or to drink soon: say so, and when the next wine will be.
private struct NothingReadyCard: View {
    let next: WineGroup?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label("Nothing is at its peak right now", systemImage: "hourglass")
                .font(.headline)
            if let next, let from = next.wine.drinkFrom {
                let yearLabel = String(from)
                let wineName = next.wine.displayName
                Text("Ready from \(yearLabel): \(wineName)")
                    .foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(20)
        .background(Color.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 24, style: .continuous))
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("tonight.nothingReady")
    }
}

/// Offers the monthly reminder once, where it is useful: while bottles have a drinking
/// window and reminders were never turned on or off.
private struct ReminderOffer: View {
    @Environment(CellarModel.self) private var model
    @AppStorage(Reminders.offeredKey) private var offered = false
    @State private var refused = false

    var body: some View {
        let hasWindow = model.bottlesInCellar.contains { bottle in
            model.cellar.wines[bottle.wineID].map { $0.drinkFrom != nil && $0.drinkUntil != nil } ?? false
        }
        if refused || (!offered && !Reminders.isEnabled && hasWindow && !model.loadFailed) {
            Section {
                VStack(alignment: .leading, spacing: 12) {
                    Label {
                        if refused {
                            Text("Notifications are off for Encave. Turn them on in the system settings.")
                        } else {
                            Text("Get a reminder on the 1st of each month of what to drink?")
                        }
                    } icon: {
                        Image(systemName: "bell.badge").foregroundStyle(Color.accentColor)
                    }
                    HStack(spacing: 12) {
                        if refused {
                            NotificationSettingsButton()
                                .buttonStyle(.borderedProminent)
                        } else {
                            Button("Turn On", action: turnOn)
                                .buttonStyle(.borderedProminent)
                                .accessibilityIdentifier("tonight.reminders.on")
                        }
                        Button("Not Now") {
                            withAnimation { refused = false; offered = true }
                        }
                        .buttonStyle(.bordered)
                        .accessibilityIdentifier("tonight.reminders.later")
                    }
                }
                .padding(.vertical, 4)
            }
        }
    }

    private func turnOn() {
        Task {
            let granted = await Reminders.enable(for: model.cellar)
            withAnimation { refused = !granted }
            if granted {
                model.showToast(String(localized: "Reminders on"))
            } else {
                Reminders.announceRefused()
            }
        }
    }
}

/// Navigation value for the food pairing page.
struct FoodPairingRoute: Hashable {}

/// Hero card with tonight's suggestion.
private struct TonightPick: View {
    @Environment(CellarModel.self) private var model
    @Environment(SceneRouter.self) private var router: SceneRouter?
    let group: WineGroup
    let next: () -> Void

    var body: some View {
        let wine = group.wine
        VStack(alignment: .leading, spacing: 14) {
            Text("Tonight, open…")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.secondary)
            HStack(spacing: 16) {
                BottleEnd(color: wine.color, size: 64)
                VStack(alignment: .leading, spacing: 4) {
                    Text(wine.displayName)
                        .font(.title2.weight(.semibold))
                        .fontDesign(.serif)
                        .lineLimit(2)
                    // The style in words, as in the cellar list, not only by the bottle's color.
                    if !wine.styleLine.isEmpty {
                        Text(verbatim: wine.styleLine).foregroundStyle(.secondary)
                    }
                    MaturityLabel(maturity: Maturity.of(wine, year: model.currentYear))
                        .font(.footnote.weight(.medium))
                }
            }
            // The bottle Drink opens first: a free one, as in the drink sheet.
            if let bottle = group.bottles.bottleToOpen, let location = model.locationLabel(for: bottle) {
                if bottle.slot != nil {
                    Button {
                        router?.showInRack(bottle, model: model)
                    } label: {
                        Label(location, systemImage: model.locationIcon(for: bottle))
                            .font(.subheadline)
                    }
                    .buttonStyle(.borderless)
                    .accessibilityHint(Text("Show in Rack"))
                    .accessibilityIdentifier("action.tonight.showInRack")
                } else {
                    Label(location, systemImage: model.locationIcon(for: bottle))
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }
            HStack(spacing: 12) {
                Button {
                    if let bottle = group.bottles.bottleToOpen { router?.sheet = .drink(bottleID: bottle.id) }
                } label: {
                    ActionLabel("Drink", systemImage: "wineglass").frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .accessibilityIdentifier("action.tonight.drink")
                Button(action: next) {
                    ActionLabel("Another idea", systemImage: "shuffle").frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .accessibilityIdentifier("action.tonight.next")
            }
            .controlSize(.large)
        }
        .padding(20)
        .background(
            LinearGradient(colors: [(wine.color?.tint ?? .gray).opacity(0.25), Color.secondary.opacity(0.06)],
                           startPoint: .topLeading, endPoint: .bottomTrailing),
            in: RoundedRectangle(cornerRadius: 24, style: .continuous)
        )
        .padding(.vertical, 4)
        .id("pick-\(group.id)")
        .motionTransition(.asymmetric(insertion: .move(edge: .trailing).combined(with: .opacity), removal: .opacity))
    }
}
