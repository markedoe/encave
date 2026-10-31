import CaveKit
import SwiftUI

/// Opens a wine's page from a rack.
struct WineRoute: Hashable {
    let id: UUID
}

/// A rack drawn as it looks. Tap a bottle to see what it is, a place with several
/// bottles for the list, an empty place to add one there. "Move" picks the destination.
/// The key at the bottom lists the wines of the rack: tap one to see where its bottles are.
struct RackDetailView: View {
    @Environment(CellarModel.self) private var model
    @Environment(SceneRouter.self) private var router: SceneRouter?
    let rackID: UUID
    @State private var zoom: CGFloat = 1
    /// Mac: below this zoom the drawing no longer changes (`RackZoom.minimum`).
    @State private var minimumZoom = RackZoom.lowest
    /// iPad: the zoom buttons ask the native zooming view for a step in or out.
    @State private var zoomStep = RackZoomStep()
    #if os(iOS)
    @Environment(\.horizontalSizeClass) private var sizeClass
    #endif
    @GestureState private var pinch: CGFloat = 1
    @State private var openCell: OpenCell?
    @State private var moving: Bottle?
    @State private var selectedPlace: RackPlace?
    @State private var focusWine: UUID?
    @State private var showsList = false
    @State private var listSelection: UUID?
    @State private var afterList: AppSheet?
    /// Chosen in the place's sheet: done once that sheet is gone, so a sheet it opens is not lost.
    @State private var afterCell: CellAction?
    /// Checking the rack: the bottles found (by ID, so a bottle found and then moved within
    /// the rack stays found).
    @State private var auditing = false
    @State private var checked: Set<UUID> = []
    @State private var confirmingAudit = false
    /// Checking a place's bottles one by one.
    @State private var checkingCell: OpenCell?
    /// The wine page opened from VoiceOver's actions on a place.
    @State private var wineRoute: WineRoute?
    /// A free position tapped while bottles are out of storage: add one, or put one there.
    @State private var freePlace: FreePlace?
    /// A bottle being kept for an occasion, and the occasion typed.
    @State private var reservingBottle: Bottle?
    @State private var occasion = ""
    @State private var puttingHere: FreePlace?

    var body: some View {
        if let rack = model.cellar.rack(rackID) {
            canvas(rack)
                .safeAreaInset(edge: .bottom, spacing: 0) { bottomBar(rack) }
                .navigationTitle(rack.name)
                .inlineTitle()
                .toolbar { toolbar }
                .navigationDestination(for: WineRoute.self) { WineDetailView(wineID: $0.id) }
                .navigationDestination(item: $wineRoute) { WineDetailView(wineID: $0.id) }
                .confirmationDialog(
                    Text("Bottles not found: \(missing(rack).count)"),
                    isPresented: $confirmingAudit, titleVisibility: .visible
                ) {
                    Button("Take Them Out of Storage") {
                        model.takeOut(Set(missing(rack).map(\.id)))
                        withAnimation { auditing = false; checked = [] }
                    }
                    Button("Mark Them as Gone", role: .destructive) {
                        model.markGone(Set(missing(rack).map(\.id)))
                        withAnimation { auditing = false; checked = [] }
                    }
                    Button("Keep Checking", role: .cancel) {}
                } message: {
                    Text("Taken out, they stay in the cellar without a position. Gone, they leave the cellar (given away, drunk elsewhere, broken). You can undo right after.")
                }
                .sheet(item: $openCell, onDismiss: {
                    if let afterCell { perform(afterCell) }
                    afterCell = nil
                }) { open in
                    CellSheet(rack: rack, cell: open.cell) { action in
                        afterCell = action
                        openCell = nil
                    }
                    .halfSheetDetents()
                    .solidSheetBackground()
                }
                .sheet(isPresented: $showsList, onDismiss: {
                    if let afterList { router?.sheet = afterList }
                    afterList = nil
                }) {
                    NavigationStack {
                        RackBottleList(rackID: rackID, selection: $listSelection, present: { sheet in
                            afterList = sheet
                            showsList = false
                        })
                        .inlineTitle()
                        .toolbar {
                            ToolbarItem(placement: .confirmationAction) {
                                Button("Done") { showsList = false }
                                    .accessibilityIdentifier("sheet.done")
                            }
                        }
                    }
                    .halfSheetDetents()
                    .solidSheetBackground()
                }
                // A free position while bottles are out of storage: a new bottle, or one of those.
                .modifier(FreePlaceChoice(rack: rack, freePlace: $freePlace, puttingHere: $puttingHere))
                .modifier(KeepForOccasionAlert(bottle: $reservingBottle, occasion: $occasion))
                .sheet(item: $checkingCell) { open in
                    CheckPlaceSheet(rack: rack, cell: open.cell, checked: $checked)
                        .halfSheetDetents()
                        .solidSheetBackground()
                }
                .onChange(of: listSelection) { _, id in
                    // Chosen in the list: close it and point to the bottle in the drawing.
                    if id != nil { showsList = false }
                }
                .onChange(of: router?.highlightedSlot, initial: true) { _, slot in
                    guard let slot, slot.rackID == rackID else { return }
                    withAnimation(.snappy) { select(slot, in: rack) }
                }
                .onChange(of: selectedPlace.map { model.cellar.bottles(at: $0) } ?? []) { _, bottles in
                    if bottles.isEmpty { selectedPlace = nil }
                }
                .task(id: router?.highlightedSlot) {
                    guard router?.highlightedSlot?.rackID == rackID else { return }
                    try? await Task.sleep(for: .seconds(4))
                    withAnimation { router?.highlightedSlot = nil }
                }
        } else {
            ContentUnavailableView("Storage not found", systemImage: "questionmark")
        }
    }

    @ViewBuilder
    private func canvas(_ rack: Rack) -> some View {
        let grid = FittedRackGrid(
            rack: rack, zoom: RackZoom.clamped(zoom * pinch, minimum: minimumZoom),
            selected: auditing ? foundSlots(rack) : (moving?.slot.map { [$0] } ?? []),
            highlighted: router?.highlightedSlot,
            selectedPlace: selectedPlace, focusWine: focusWine,
            onTap: { place, bottles in tap(rack, place, bottles) },
            // Nothing moves while checking: a bottle found must stay where it was seen.
            onDrop: auditing ? nil : { bottleID, place in drop(bottleID, on: place, rack) },
            zoomStep: zoomStep,
            interaction: interaction,
            onMinimumZoom: { value in
                minimumZoom = value
                zoom = RackZoom.clamped(zoom, minimum: value)
            }
        )
        #if os(macOS)
        grid
            .motionAnimation(.snappy, value: zoom)
            .gesture(
                MagnifyGesture()
                    .updating($pinch) { value, state, _ in state = value.magnification }
                    .onEnded { value in zoom = RackZoom.clamped(zoom * value.magnification, minimum: minimumZoom) }
            )
            .background { zoomInEqualsShortcut }
        #else
        grid
            .background { if sizeClass == .regular { zoomInEqualsShortcut } }
        #endif
    }

    @ViewBuilder
    private func bottomBar(_ rack: Rack) -> some View {
        VStack(spacing: 0) {
            if auditing {
                AuditBar(checked: auditTotal(rack) - missing(rack).count, total: auditTotal(rack)) {
                    finishAudit(rack)
                } cancel: {
                    withAnimation { auditing = false; checked = [] }
                }
            } else if let moving {
                let displayName = model.cellar.wines[moving.wineID]?.displayName ?? ""
                HStack {
                    Label("Choose where to put \(displayName)", systemImage: "hand.tap")
                        .font(.subheadline.weight(.medium))
                        .lineLimit(2)
                    Spacer()
                    // Esc cancels the move (not a check of the rack, which would lose what was found).
                    Button("Cancel") { self.moving = nil }
                        .keyboardShortcut(.cancelAction)
                        .accessibilityIdentifier("sheet.cancel")
                }
                .padding()
                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                .padding()
            } else {
                if let place = selectedPlace, let bottle = model.cellar.bottles(at: place).first {
                    SelectedBottleCard(rack: rack, bottle: bottle) { action in
                        perform(action)
                    } onClose: {
                        withAnimation(.snappy) { selectedPlace = nil }
                    }
                    .padding(.horizontal)
                    .padding(.bottom, 8)
                    .motionTransition(.move(edge: .bottom).combined(with: .opacity))
                }
                RackKey(rack: rack, focusWine: $focusWine)
            }
        }
    }

    private func zoomIn() {
        #if os(macOS)
        zoom = RackZoom.clamped(zoom + RackZoom.step, minimum: minimumZoom)
        #else
        zoomStep = zoomStep.next(factor: 1.5)
        #endif
    }

    /// ⌘= zooms in too, as in other apps: "+" needs Shift on most keyboards.
    private var zoomInEqualsShortcut: some View {
        Button("Zoom In", action: zoomIn)
            .keyboardShortcut("=")
            .disabled(zoom >= RackZoom.maximum)
            .opacity(0)
            .frame(width: 0, height: 0)
            .accessibilityHidden(true)
    }

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        ToolbarItemGroup(placement: .primaryAction) {
            #if os(macOS)
            Button { zoom = RackZoom.clamped(zoom - RackZoom.step, minimum: minimumZoom) } label: {
                Label("Zoom Out", systemImage: "minus.magnifyingglass")
            }
            .keyboardShortcut("-")
            .disabled(zoom <= minimumZoom)
            Button(action: zoomIn) { Label("Zoom In", systemImage: "plus.magnifyingglass") }
                .keyboardShortcut("+")
                .disabled(zoom >= RackZoom.maximum)
            #else
            if sizeClass == .regular {
                // Besides pinching: buttons, and ⌘– / ⌘+ (or ⌘=) with a keyboard.
                Button { zoomStep = zoomStep.next(factor: 1 / 1.5) } label: {
                    Label("Zoom Out", systemImage: "minus.magnifyingglass")
                }
                .keyboardShortcut("-")
                .accessibilityIdentifier("rack.zoom.out")
                Button(action: zoomIn) {
                    Label("Zoom In", systemImage: "plus.magnifyingglass")
                }
                .keyboardShortcut("+")
                .accessibilityIdentifier("rack.zoom.in")
            }
            Button { listSelection = nil; showsList = true } label: { Label("Bottle List", systemImage: "list.bullet") }
                .accessibilityIdentifier("rack.list")
            #endif
            #if os(macOS)
            storageActions
            #else
            // In one menu on every width and in both languages, so the storage's name stays
            // readable between the buttons.
            Menu { storageActions } label: { Label("More", systemImage: "ellipsis.circle") }
                .accessibilityIdentifier("rack.more")
            #endif
        }
    }

    @ViewBuilder
    private var storageActions: some View {
        Button {
            selectedPlace = nil
            moving = nil
            checked = []
            withAnimation { auditing = true }
        } label: {
            Label("Check This Storage", systemImage: "checklist")
        }
        .disabled(auditing)
        .accessibilityIdentifier("rack.audit")
        Button { router?.sheet = .editRack(rackID: rackID) } label: { Label("Edit Storage", systemImage: "slider.horizontal.3") }
    }

    private func perform(_ action: CellAction) {
        switch action {
        case .drink(let bottle): router?.sheet = .drink(bottleID: bottle.id)
        case .move(let bottle):
            selectedPlace = nil
            moving = bottle
            let displayName = model.cellar.wines[bottle.wineID]?.displayName ?? ""
            AccessibilityNotification.Announcement(String(localized: "Choose where to put \(displayName)")).post()
        case .remove(let bottle): model.markGone(bottle.id)
        case .add(let slot): router?.sheet = .addBottle(AddBottleContext(slot: slot))
        case .documents(let bottle, let importing): router?.sheet = .bottleDocuments(bottleID: bottle.id, importing: importing)
        case .moveElsewhere(let bottle): router?.sheet = .moveBottle(bottleID: bottle.id)
        case .takeOut(let bottle):
            selectedPlace = nil
            model.takeOutOfRack(bottle.id, note: nil)
        case .reserve(let bottle):
            if bottle.reservedFor == nil {
                occasion = model.cellar.reservations.first ?? ""
                reservingBottle = bottle
            } else {
                model.reserve([bottle.id], for: nil)
            }
        case .showWine(let bottle): wineRoute = WineRoute(id: bottle.wineID)
        }
    }

    /// What the places do beyond a tap: VoiceOver's hints and actions, checking one by one.
    private var interaction: RackInteraction {
        let mode: RackMode = auditing ? .auditing : (moving != nil ? .moving : .browsing)
        return RackInteraction(
            mode: mode,
            perform: { action, bottle in quickAction(action, bottle) },
            checkOneByOne: { cell in checkingCell = OpenCell(cell: cell) }
        )
    }

    private func quickAction(_ action: RackCellQuickAction, _ bottle: Bottle) {
        switch action {
        case .move: perform(.move(bottle))
        case .drink: perform(.drink(bottle))
        case .winePage: wineRoute = WineRoute(id: bottle.wineID)
        }
    }

    /// One bottle per place (or a place of a fridge drawn front and back): show it in the
    /// card. Several: no card.
    private func select(_ slot: Slot, in rack: Rack) {
        let place = RackPlace(slot, in: rack)
        selectedPlace = place.holdsOne(in: rack, cellar: model.cellar) ? place : nil
    }

    private func auditTotal(_ rack: Rack) -> Int {
        model.bottlesInCellar.filter { $0.slot?.rackID == rack.id }.count
    }

    private func missing(_ rack: Rack) -> [Bottle] {
        model.cellar.missingBottles(in: rack.id, found: checked)
    }

    /// Where the bottles found are now.
    private func foundSlots(_ rack: Rack) -> Set<Slot> {
        Set(model.bottlesInCellar.compactMap { checked.contains($0.id) && $0.slot?.rackID == rack.id ? $0.slot : nil })
    }

    private func finishAudit(_ rack: Rack) {
        if missing(rack).isEmpty {
            withAnimation { auditing = false; checked = [] }
            model.showToast(String(localized: "Every bottle is where it should be."))
        } else {
            confirmingAudit = true
        }
    }

    /// A bottle dragged onto a place: moves it there, or swaps it with the bottle there.
    private func drop(_ bottleID: UUID, on place: RackPlace, _ rack: Rack) -> Bool {
        guard let bottle = model.cellar.bottles[bottleID], bottle.status == .inCellar else { return false }
        guard !(bottle.slot.map(place.contains) ?? false) else { return false }
        if let free = model.cellar.freeSlot(at: place) {
            model.move(bottle.id, to: free)
        } else if place.holdsOne(in: rack, cellar: model.cellar), let from = bottle.slot, from.rackID == rack.id {
            model.swap(from, place.firstSlot)
        } else {
            model.showToast(String(localized: "This position is full."))
            return false
        }
        withAnimation(.snappy) { selectedPlace = place.holdsOne(in: rack, cellar: model.cellar) ? place : nil }
        return true
    }

    private func tap(_ rack: Rack, _ place: RackPlace, _ bottles: [Bottle]) {
        if auditing {
            // A tap says "the bottles of this position are here" (or undoes it).
            let ids = Set(bottles.map(\.id))
            guard !ids.isEmpty else { return }
            if ids.isSubset(of: checked) { checked.subtract(ids) } else { checked.formUnion(ids) }
            return
        }
        if let chosen = moving {
            moving = nil
            // As it is now: opened or moved meanwhile (sync, another window), it stays as it is.
            guard let bottle = model.cellar.bottles[chosen.id], bottle.status == .inCellar, bottle.slot == chosen.slot,
                  let from = bottle.slot, !place.contains(from) else { return }
            if let free = model.cellar.freeSlot(at: place) {
                model.move(bottle.id, to: free)
            } else if place.holdsOne(in: rack, cellar: model.cellar) {
                model.swap(from, place.firstSlot)
            } else {
                model.showToast(String(localized: "This position is full."))
            }
            return
        }
        if bottles.isEmpty, let free = model.cellar.freeSlot(at: place) {
            selectedPlace = nil
            // Bottles out of storage: one of them may go here instead of a new one.
            if model.cellar.bottlesNotInStorage.isEmpty {
                router?.sheet = .addBottle(AddBottleContext(slot: free))
            } else {
                freePlace = FreePlace(slot: free)
            }
        } else if place.holdsOne(in: rack, cellar: model.cellar), !bottles.isEmpty {
            withAnimation(.snappy) { selectedPlace = place }
        } else {
            selectedPlace = nil
            openCell = OpenCell(cell: place.cell)
        }
    }
}

/// A free position tapped while bottles are out of storage: Put a Bottle Here (one of them)
/// or Add a Bottle Here (a new one).
private struct FreePlaceChoice: ViewModifier {
    @Environment(CellarModel.self) private var model
    @Environment(SceneRouter.self) private var router: SceneRouter?
    let rack: Rack
    @Binding var freePlace: FreePlace?
    @Binding var puttingHere: FreePlace?

    func body(content: Content) -> some View {
        content
            .confirmationDialog(
                Text(verbatim: freePlace.map { rack.displayLabel(for: $0.slot) } ?? ""),
                isPresented: Binding(get: { freePlace != nil }, set: { if !$0 { freePlace = nil } }),
                titleVisibility: .visible, presenting: freePlace
            ) { place in
                Button("Put a Bottle Here…") { puttingHere = place }
                Button("Add a Bottle Here") { router?.sheet = .addBottle(AddBottleContext(slot: place.slot)) }
                Button("Cancel", role: .cancel) {}
            } message: { _ in
                Text("\(model.cellar.bottlesNotInStorage.count) bottles not in storage")
            }
            .sheet(item: $puttingHere) { place in
                PutBottleHereView(slot: place.slot)
            }
    }
}

/// Keep for an Occasion on a bottle of the rack: the occasion asked first.
private struct KeepForOccasionAlert: ViewModifier {
    @Environment(CellarModel.self) private var model
    @Binding var bottle: Bottle?
    @Binding var occasion: String

    func body(content: Content) -> some View {
        content
            .alert("Keep for an Occasion", isPresented: Binding(get: { bottle != nil }, set: { if !$0 { bottle = nil } })) {
                TextField("Occasion", text: $occasion)
                Button("Keep Bottle") {
                    if let bottle { model.reserve([bottle.id], for: occasion) }
                    bottle = nil
                }
                .disabled(occasion.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                Button("Cancel", role: .cancel) { bottle = nil }
            } message: {
                Text("The bottle stays where it is but is no longer suggested for tonight.")
            }
    }
}

/// A free position, for the choice between adding a bottle and putting one there.
struct FreePlace: Identifiable {
    let slot: Slot
    var id: String { "\(slot.rackID)-\(slot.row)-\(slot.column)-\(slot.depth)" }
}

private struct OpenCell: Identifiable {
    let cell: RackCell
    var id: String { "\(cell.row)-\(cell.column)" }
}

/// The bottle tapped in the rack: what it is and where, with its actions.
private struct SelectedBottleCard: View {
    @Environment(CellarModel.self) private var model
    let rack: Rack
    let bottle: Bottle
    let onAction: (CellAction) -> Void
    let onClose: () -> Void

    var body: some View {
        let wine = model.cellar.wines[bottle.wineID]
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top, spacing: 12) {
                WineThumbnail(wine: wine, width: 40)
                VStack(alignment: .leading, spacing: 2) {
                    Text(wine?.displayName ?? String(localized: "Unknown wine"))
                        .font(.headline)
                        .lineLimit(2)
                    let line = [wine?.color?.name, wine?.producer].compactMap { $0 }.joined(separator: " · ")
                    if !line.isEmpty {
                        Text(line).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                    }
                    // Position and maturity side by side if both fit, else one above the other,
                    // never as two narrow wrapped columns.
                    ViewThatFits(in: .horizontal) {
                        HStack(spacing: 8) { placeAndMaturity(wine).lineLimit(1) }
                        VStack(alignment: .leading, spacing: 2) { placeAndMaturity(wine) }
                    }
                    .font(.caption)
                    .foregroundStyle(.secondary)
                }
                Spacer(minLength: 0)
                Button(action: onClose) {
                    Image(systemName: "xmark.circle.fill")
                        .font(.title2)
                        .symbolRenderingMode(.hierarchical)
                        .foregroundStyle(.secondary)
                        // A 44-point target around the drawn symbol, without moving it.
                        .frame(minWidth: 44, minHeight: 44)
                        .contentShape(Rectangle())
                        .padding(-8)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text("Close"))
                .accessibilityIdentifier("rack.selection.close")
            }
            HStack(spacing: 10) {
                ActionButtonRow(spacing: 10) {
                    Button { onAction(.drink(bottle)) } label: { ActionLabel("Drink", systemImage: "wineglass") }
                        .prominentButtonStyle()
                        .accessibilityIdentifier("action.rack.drink")
                    Button { onAction(.move(bottle)) } label: { ActionLabel("Move", systemImage: "arrow.up.and.down.and.arrow.left.and.right") }
                        .buttonStyle(.bordered)
                        .accessibilityIdentifier("action.rack.move")
                }
                Spacer(minLength: 0)
                Button {
                    onAction(.documents(bottle, importing: bottle.attachments == nil))
                } label: {
                    if let count = bottle.attachments?.count {
                        DocumentCount(count: count).font(.body)
                    } else {
                        Label("Attach a Document…", systemImage: "paperclip").labelStyle(.iconOnly).font(.title3)
                    }
                }
                .buttonStyle(.bordered)
                .accessibilityLabel(bottle.attachments == nil ? Text("Attach a Document…")
                                    : Text("\(bottle.attachments?.count ?? 0) documents"))
                .accessibilityIdentifier("rack.selection.documents")
                NavigationLink(value: WineRoute(id: bottle.wineID)) {
                    Label("Show Wine", systemImage: "info.circle")
                        .labelStyle(.iconOnly)
                        .font(.title3)
                }
                .buttonStyle(.bordered)
                .accessibilityLabel(Text("Show Wine"))
                .accessibilityIdentifier("rack.selection.wine")
                // The bottle's other actions, in the same order as everywhere (and on Android).
                Menu {
                    BottleMoreActions(bottle: bottle, onAction: onAction)
                } label: {
                    Label("More", systemImage: "ellipsis.circle")
                        .labelStyle(.iconOnly)
                        .font(.title3)
                }
                .buttonStyle(.bordered)
                .fixedSize()
                .accessibilityIdentifier("rack.selection.more")
            }
        }
        .padding()
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("rack.selection")
    }
}

extension SelectedBottleCard {
    @ViewBuilder
    fileprivate func placeAndMaturity(_ wine: Wine?) -> some View {
        if let slot = bottle.slot {
            Label(rack.displayLabel(for: slot), systemImage: "mappin.and.ellipse")
        }
        if let wine {
            MaturityLabel(maturity: Maturity.of(wine, year: model.currentYear), short: true)
                .labelStyle(CompactLabelStyle())
        }
    }
}

/// The wines of the rack, most bottles first. Tap one to see where its bottles are:
/// they stand out and the others fade. Tap it again to show all.
private struct RackKey: View {
    @Environment(CellarModel.self) private var model
    let rack: Rack
    @Binding var focusWine: UUID?

    private static let countID = "rack.key.count"
    private static let fade: CGFloat = 24

    private struct Entry: Identifiable {
        let wine: Wine
        let count: Int
        var id: UUID { wine.id }
    }

    var body: some View {
        let bottles: [Bottle] = model.bottlesInCellar.filter { $0.slot?.rackID == rack.id }
        let wines = entries(for: bottles)
        ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    Label {
                        Text("\(bottles.count) of \(model.cellar.capacity(of: rack)) bottles")
                    } icon: {
                        Image(systemName: rack.systemImage)
                    }
                    .font(.subheadline.weight(.semibold).monospacedDigit())
                    .padding(.trailing, 6)
                    .id(Self.countID)
                    ForEach(wines) { entry in
                        chip(entry)
                    }
                }
                .padding(.leading, 16)
                .padding(.trailing, Self.fade)
                .padding(.vertical, 10)
            }
            // The chosen chip comes first and in view, with its count and its close mark.
            .onChange(of: focusWine) {
                withAnimation(.snappy) { proxy.scrollTo(Self.countID, anchor: .leading) }
            }
            // Fades out on the trailing edge: a cue that more chips follow.
            .mask {
                HStack(spacing: 0) {
                    Rectangle()
                    LinearGradient(colors: [.black, .clear], startPoint: .leading, endPoint: .trailing)
                        .frame(width: Self.fade)
                }
            }
        }
        .background(.bar)
        .accessibilityIdentifier("rack.key")
    }

    /// One entry per wine, most bottles first, then by name.
    private func entries(for bottles: [Bottle]) -> [Entry] {
        var counts: [UUID: Int] = [:]
        for bottle in bottles { counts[bottle.wineID, default: 0] += 1 }
        var result: [Entry] = []
        for (id, count) in counts {
            if let wine = model.cellar.wines[id] { result.append(Entry(wine: wine, count: count)) }
        }
        return result.sorted { a, b in
            if (a.wine.id == focusWine) != (b.wine.id == focusWine) { return a.wine.id == focusWine }
            return a.count != b.count ? a.count > b.count : a.wine.displayName < b.wine.displayName
        }
    }

    private func chip(_ entry: Entry) -> some View {
        let isOn = focusWine == entry.wine.id
        return Button {
            withAnimation(.snappy) { focusWine = isOn ? nil : entry.wine.id }
        } label: {
            HStack(spacing: 6) {
                BottleEnd(color: entry.wine.color, size: 16)
                Text(verbatim: entry.wine.displayName)
                    .lineLimit(1)
                // The style by its symbol (as on the bottles), not by color alone; its name
                // is left to VoiceOver, so the wine's name has the room.
                if let color = entry.wine.color {
                    Image(systemName: color.styleMark.systemImage)
                        .imageScale(.small)
                        .foregroundStyle(.secondary)
                        .accessibilityHidden(true)
                }
                Text(verbatim: "×\(entry.count)")
                    .monospacedDigit()
                    .foregroundStyle(isOn ? Color.primary : Color.secondary)
                if isOn {
                    Image(systemName: "xmark")
                        .font(.caption2.weight(.bold))
                        .accessibilityHidden(true)
                }
            }
            .font(.subheadline)
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background {
                Capsule().fill(isOn ? Color.accentColor.opacity(0.22) : Color.secondary.opacity(0.12))
            }
            .overlay {
                Capsule().strokeBorder(isOn ? Color.accentColor : Color.clear, lineWidth: 1.5)
            }
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(verbatim: [entry.wine.displayName, entry.wine.color?.name].compactMap { $0 }.joined(separator: ", "))
            + Text(verbatim: ", ") + Text("\(entry.count) bottles"))
        .accessibilityHint(Text("Shows where its bottles are in this storage."))
        .accessibilityAddTraits(isOn ? .isSelected : [])
        .accessibilityIdentifier("rack.key.wine")
    }
}

enum CellAction {
    case drink(Bottle), move(Bottle), remove(Bottle), add(Slot)
    /// The Move sheet, for another storage or out of storage.
    case moveElsewhere(Bottle)
    case takeOut(Bottle)
    /// Keep for an occasion (asked first), or free a kept bottle.
    case reserve(Bottle)
    case showWine(Bottle)
    /// The bottle's documents; `importing` opens the file picker right away.
    case documents(Bottle, importing: Bool)
}

/// What is in one place of a rack: one bottle, front and back, or a whole bin.
private struct CellSheet: View {
    @Environment(CellarModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let rack: Rack
    let cell: RackCell
    let onAction: (CellAction) -> Void

    var body: some View {
        let bottles = model.cellar.bottles(in: cell)
        let free = model.cellar.freeSlot(in: cell)
        NavigationStack {
            List {
                if bottles.isEmpty {
                    ContentUnavailableView("Empty", systemImage: rack.systemImage)
                        .listRowBackground(Color.clear)
                }
                Section {
                    ForEach(bottles) { bottle in
                        BottleInCellRow(rack: rack, bottle: bottle, onAction: onAction)
                    }
                } header: {
                    let positions = rack.shownPositions(of: cell, deepest: bottles.compactMap(\.slot?.depth).max())
                    if positions > 1 {
                        Text("\(bottles.count) of \(positions) bottles")
                    }
                }
                if let free {
                    Section {
                        Button {
                            onAction(.add(free))
                        } label: {
                            if rack.kind == .bins {
                                Label("Add a Bottle to This Bin", systemImage: "plus.circle.fill")
                            } else {
                                Label("Add a Bottle Here", systemImage: "plus.circle.fill")
                            }
                        }
                    }
                }
            }
            .navigationTitle(rack.displayLabel(for: cell))
            .inlineTitle()
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                        .accessibilityIdentifier("sheet.done")
                }
            }
        }
        .macSheetFrame(width: 440, height: 460)
    }
}

/// A bottle in a cell, with its actions.
private struct BottleInCellRow: View {
    @Environment(CellarModel.self) private var model
    let rack: Rack
    let bottle: Bottle
    let onAction: (CellAction) -> Void

    var body: some View {
        let wine = model.cellar.wines[bottle.wineID]
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 12) {
                WineThumbnail(wine: wine, width: 40)
                VStack(alignment: .leading, spacing: 2) {
                    Text(wine?.displayName ?? String(localized: "Unknown wine"))
                        .font(.headline)
                    // One text, so the gap is the same on both sides of the dot.
                    let depth = bottle.slot.flatMap { slot in
                        rack.kind != .bins && rack.shownPositions(inRow: slot.row, deepest: slot.depth) > 1
                            ? rack.depthName(slot.depth, row: slot.row) : nil
                    }
                    let subtitle = [wine?.producer, depth].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · ")
                    if !subtitle.isEmpty {
                        Text(verbatim: subtitle)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                    if let wine {
                        MaturityLabel(maturity: Maturity.of(wine, year: model.currentYear), short: true)
                            .font(.caption)
                            .labelStyle(CompactLabelStyle())
                    }
                }
                if let documents = bottle.attachments?.count {
                    Spacer(minLength: 4)
                    DocumentCount(count: documents)
                }
            }
            HStack(spacing: 10) {
                ActionButtonRow(spacing: 10) {
                    Button { onAction(.drink(bottle)) } label: { ActionLabel("Drink", systemImage: "wineglass") }
                        .prominentButtonStyle()
                    Button { onAction(.move(bottle)) } label: { ActionLabel("Move", systemImage: "arrow.up.and.down.and.arrow.left.and.right") }
                        .buttonStyle(.bordered)
                }
                Spacer()
                Menu {
                    BottleMoreActions(bottle: bottle, onAction: onAction, showsDocuments: true)
                } label: {
                    Label("More", systemImage: "ellipsis.circle")
                        .labelStyle(.iconOnly)
                }
                .fixedSize()
            }
            .controlSize(.regular)
        }
        .padding(.vertical, 4)
    }
}

/// A stored bottle's actions beyond Drink and Move, in the order every bottle lists them:
/// Keep, (its documents,) Show Wine, then Move to Other Storage, Take Out of Storage and
/// No Longer in Cellar.
private struct BottleMoreActions: View {
    let bottle: Bottle
    let onAction: (CellAction) -> Void
    /// The cell sheet's rows have no documents button of their own.
    var showsDocuments = false

    var body: some View {
        if bottle.reservedFor == nil {
            Button("Keep for an Occasion…", systemImage: "gift") { onAction(.reserve(bottle)) }
        } else {
            Button("Free This Bottle", systemImage: "gift.slash") { onAction(.reserve(bottle)) }
        }
        if showsDocuments {
            if bottle.attachments != nil {
                Button("Documents…", systemImage: "paperclip") { onAction(.documents(bottle, importing: false)) }
            }
            Button("Attach a Document…", systemImage: "doc.badge.plus") { onAction(.documents(bottle, importing: true)) }
        }
        Button("Show Wine", systemImage: "info.circle") { onAction(.showWine(bottle)) }
        Divider()
        Button("Move to Other Storage…", systemImage: "arrow.up.and.down.and.arrow.left.and.right") { onAction(.moveElsewhere(bottle)) }
        Button("Take Out of Storage", systemImage: "tray.and.arrow.up") { onAction(.takeOut(bottle)) }
        NoLongerInCellarButton { onAction(.remove(bottle)) }
    }
}

/// While checking a rack: how many bottles were found, and Finish.
private struct AuditBar: View {
    let checked: Int
    let total: Int
    let finish: () -> Void
    let cancel: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label("Tap each bottle you find in this storage.", systemImage: "checklist")
                .font(.subheadline.weight(.medium))
            ProgressView(value: Double(checked), total: Double(max(total, 1)))
                .tint(Color.accentColor)
            // The count beside the buttons when all fit on one line, else above them.
            ViewThatFits(in: .horizontal) {
                HStack {
                    count
                    Spacer()
                    buttons
                }
                .environment(\.actionLabelIcon, .shown)
                VStack(alignment: .leading, spacing: 8) {
                    count
                    HStack {
                        Spacer()
                        buttons
                    }
                }
            }
        }
        .padding()
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .padding()
        .accessibilityElement(children: .contain)
    }

    private var count: some View {
        Text("Found: \(checked) of \(total)")
            .font(.subheadline.monospacedDigit())
            .foregroundStyle(.secondary)
            .lineLimit(1)
    }

    @ViewBuilder
    private var buttons: some View {
        Button("Cancel", action: cancel)
            .accessibilityIdentifier("sheet.cancel")
        Button(action: finish) { ActionLabel("End Check", systemImage: "checkmark") }
            .prominentButtonStyle()
            .accessibilityIdentifier("action.audit.finish")
    }
}

/// Checking a rack: the bottles of one place (a bin, or front and back), found one by one.
private struct CheckPlaceSheet: View {
    @Environment(CellarModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let rack: Rack
    let cell: RackCell
    @Binding var checked: Set<UUID>

    var body: some View {
        let bottles = model.cellar.bottles(in: cell)
        let ids = bottles.map(\.id)
        NavigationStack {
            List {
                Section {
                    ForEach(bottles) { bottle in
                        if let slot = bottle.slot {
                            Toggle(isOn: found(bottle.id)) { row(bottle, slot) }
                        }
                    }
                }
                Section {
                    Button("All Here", systemImage: "checkmark.circle") {
                        checked.formUnion(ids)
                    }
                    .disabled(ids.allSatisfy { checked.contains($0) })
                }
            }
            .navigationTitle(rack.displayLabel(for: cell))
            .inlineTitle()
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                        .accessibilityIdentifier("sheet.done")
                }
            }
        }
        .macSheetFrame(width: 440, height: 460)
    }

    private func found(_ id: UUID) -> Binding<Bool> {
        Binding(
            get: { checked.contains(id) },
            set: { isFound in
                if isFound { checked.insert(id) } else { checked.remove(id) }
            }
        )
    }

    /// The wine, and where it is in the place: front or back, or its number in a bin.
    private func row(_ bottle: Bottle, _ slot: Slot) -> some View {
        let wine = model.cellar.wines[bottle.wineID]
        let number = slot.depth + 1
        let position = rack.kind == .bins ? String(localized: "Bottle \(number)") : rack.depthName(slot.depth, row: slot.row)
        return HStack(spacing: 12) {
            BottleEnd(color: wine?.color, size: 28)
            VStack(alignment: .leading, spacing: 2) {
                Text(wine?.displayName ?? String(localized: "Unknown wine"))
                    .lineLimit(2)
                Text(position)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }
}
