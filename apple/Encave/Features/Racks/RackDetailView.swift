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
    /// Checking the rack: places whose bottles were found.
    @State private var auditing = false
    @State private var checked: Set<Slot> = []
    @State private var confirmingAudit = false
    /// Checking a place's bottles one by one.
    @State private var checkingCell: OpenCell?
    /// The wine page opened from VoiceOver's actions on a place.
    @State private var wineRoute: WineRoute?

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
                    .presentationDetents([.medium, .large])
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
                    .presentationDetents([.medium, .large])
                    .solidSheetBackground()
                }
                .sheet(item: $checkingCell) { open in
                    CheckPlaceSheet(rack: rack, cell: open.cell, checked: $checked)
                        .presentationDetents([.medium, .large])
                        .solidSheetBackground()
                }
                .onChange(of: listSelection) { _, id in
                    // Chosen in the list: close it and point to the bottle in the drawing.
                    if id != nil { showsList = false }
                }
                .onChange(of: model.highlightedSlot, initial: true) { _, slot in
                    guard let slot, slot.rackID == rackID else { return }
                    withAnimation(.snappy) { select(slot, in: rack) }
                }
                .onChange(of: selectedPlace.map { model.cellar.bottles(at: $0) } ?? []) { _, bottles in
                    if bottles.isEmpty { selectedPlace = nil }
                }
                .task(id: model.highlightedSlot) {
                    guard model.highlightedSlot?.rackID == rackID else { return }
                    try? await Task.sleep(for: .seconds(4))
                    withAnimation { model.highlightedSlot = nil }
                }
        } else {
            ContentUnavailableView("Storage not found", systemImage: "questionmark")
        }
    }

    @ViewBuilder
    private func canvas(_ rack: Rack) -> some View {
        let grid = FittedRackGrid(
            rack: rack, zoom: min(3, max(0.6, zoom * pinch)),
            selected: auditing ? checked : (moving?.slot.map { [$0] } ?? []),
            highlighted: model.highlightedSlot,
            selectedPlace: selectedPlace, focusWine: focusWine,
            onTap: { place, bottles in tap(rack, place, bottles) },
            onDrop: { bottleID, place in drop(bottleID, on: place, rack) },
            zoomStep: zoomStep,
            interaction: interaction
        )
        #if os(macOS)
        grid
            .motionAnimation(.snappy, value: zoom)
            .gesture(
                MagnifyGesture()
                    .updating($pinch) { value, state, _ in state = value.magnification }
                    .onEnded { value in zoom = min(3, max(0.6, zoom * value.magnification)) }
            )
        #else
        grid
        #endif
    }

    @ViewBuilder
    private func bottomBar(_ rack: Rack) -> some View {
        VStack(spacing: 0) {
            if auditing {
                AuditBar(checked: checked.count, total: auditTotal(rack)) {
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

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        ToolbarItemGroup(placement: .primaryAction) {
            #if os(macOS)
            Button { zoom = max(0.6, zoom - 0.25) } label: { Label("Zoom Out", systemImage: "minus.magnifyingglass") }
                .keyboardShortcut("-")
                .disabled(zoom <= 0.6)
            Button { zoom = min(3, zoom + 0.25) } label: { Label("Zoom In", systemImage: "plus.magnifyingglass") }
                .keyboardShortcut("+")
                .disabled(zoom >= 3)
            #else
            if sizeClass == .regular {
                // Besides pinching: buttons, and ⌘– / ⌘+ with a keyboard.
                Button { zoomStep = zoomStep.next(factor: 1 / 1.5) } label: {
                    Label("Zoom Out", systemImage: "minus.magnifyingglass")
                }
                .keyboardShortcut("-")
                .accessibilityIdentifier("rack.zoom.out")
                Button { zoomStep = zoomStep.next(factor: 1.5) } label: {
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
        selectedPlace = place.holdsOne(in: rack) ? place : nil
    }

    private func auditTotal(_ rack: Rack) -> Int {
        model.bottlesInCellar.filter { $0.slot?.rackID == rack.id }.count
    }

    private func missing(_ rack: Rack) -> [Bottle] {
        model.bottlesInCellar.filter { $0.slot?.rackID == rack.id && !checked.contains($0.slot!) }
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
        } else if place.holdsOne(in: rack), let from = bottle.slot, from.rackID == rack.id {
            model.swap(from, place.firstSlot)
        } else {
            model.showToast(String(localized: "This position is full."))
            return false
        }
        withAnimation(.snappy) { selectedPlace = place.holdsOne(in: rack) ? place : nil }
        return true
    }

    private func tap(_ rack: Rack, _ place: RackPlace, _ bottles: [Bottle]) {
        if auditing {
            // A tap says "the bottles of this position are here" (or undoes it).
            let slots = Set(bottles.compactMap(\.slot))
            guard !slots.isEmpty else { return }
            if slots.isSubset(of: checked) { checked.subtract(slots) } else { checked.formUnion(slots) }
            return
        }
        if let bottle = moving {
            moving = nil
            guard let from = bottle.slot, !place.contains(from) else { return }
            if let free = model.cellar.freeSlot(at: place) {
                model.move(bottle.id, to: free)
            } else if place.holdsOne(in: rack) {
                model.swap(from, place.firstSlot)
            } else {
                model.showToast(String(localized: "This position is full."))
            }
            return
        }
        if bottles.isEmpty, let free = model.cellar.freeSlot(at: place) {
            selectedPlace = nil
            router?.sheet = .addBottle(AddBottleContext(slot: free))
        } else if place.holdsOne(in: rack), !bottles.isEmpty {
            withAnimation(.snappy) { selectedPlace = place }
        } else {
            selectedPlace = nil
            openCell = OpenCell(cell: place.cell)
        }
    }
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
                BottleEnd(color: wine?.color, size: 40)
                VStack(alignment: .leading, spacing: 2) {
                    Text(wine?.displayName ?? String(localized: "Unknown wine"))
                        .font(.headline)
                        .lineLimit(2)
                    let line = [wine?.color?.name, wine?.producer].compactMap { $0 }.joined(separator: " · ")
                    if !line.isEmpty {
                        Text(line).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                    }
                    HStack(spacing: 8) {
                        if let slot = bottle.slot {
                            Label(rack.displayLabel(for: slot), systemImage: "mappin.and.ellipse")
                        }
                        if let wine {
                            MaturityLabel(maturity: Maturity.of(wine, year: model.currentYear))
                                .labelStyle(CompactLabelStyle())
                        }
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
                        .buttonStyle(.borderedProminent)
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
                    Label("Wine Page", systemImage: "info.circle")
                        .labelStyle(.iconOnly)
                        .font(.title3)
                }
                .buttonStyle(.bordered)
                .accessibilityLabel(Text("Wine Page"))
                .accessibilityIdentifier("rack.selection.wine")
            }
        }
        .padding()
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("rack.selection")
    }
}

/// The wines of the rack, most bottles first. Tap one to see where its bottles are:
/// they stand out and the others fade. Tap it again to show all.
private struct RackKey: View {
    @Environment(CellarModel.self) private var model
    let rack: Rack
    @Binding var focusWine: UUID?

    private struct Entry: Identifiable {
        let wine: Wine
        let count: Int
        var id: UUID { wine.id }
    }

    var body: some View {
        let bottles: [Bottle] = model.bottlesInCellar.filter { $0.slot?.rackID == rack.id }
        let wines = entries(for: bottles)
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                Label {
                    Text("\(bottles.count) of \(rack.capacity) bottles")
                } icon: {
                    Image(systemName: rack.systemImage)
                }
                .font(.subheadline.weight(.semibold).monospacedDigit())
                .padding(.trailing, 6)
                ForEach(wines) { entry in
                    chip(entry)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
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
            a.count != b.count ? a.count > b.count : a.wine.displayName < b.wine.displayName
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
                // The style in words and by its symbol (as on the bottles), not only by color.
                if let color = entry.wine.color {
                    Label {
                        Text(verbatim: color.name)
                    } icon: {
                        Image(systemName: color.styleMark.systemImage)
                    }
                    .labelStyle(StyleLabelStyle())
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
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

/// A style in a key chip: its symbol, then its name, close together.
private struct StyleLabelStyle: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 3) {
            configuration.icon
                .imageScale(.small)
                .accessibilityHidden(true)
            configuration.title
        }
    }
}

enum CellAction {
    case drink(Bottle), move(Bottle), remove(Bottle), add(Slot)
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
                    if rack.positionsPerCell > 1 {
                        Text("\(bottles.count) of \(rack.positionsPerCell) bottles")
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
                BottleEnd(color: wine?.color, size: 40)
                VStack(alignment: .leading, spacing: 2) {
                    Text(wine?.displayName ?? String(localized: "Unknown wine"))
                        .font(.headline)
                    HStack(spacing: 6) {
                        if let producer = wine?.producer { Text(producer) }
                        if rack.kind != .bins, rack.positionsPerCell > 1, let slot = bottle.slot {
                            Text(verbatim: "· \(rack.depthName(slot.depth))")
                        }
                    }
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    if let wine {
                        MaturityLabel(maturity: Maturity.of(wine, year: model.currentYear))
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
                        .buttonStyle(.borderedProminent)
                    Button { onAction(.move(bottle)) } label: { ActionLabel("Move", systemImage: "arrow.up.and.down.and.arrow.left.and.right") }
                        .buttonStyle(.bordered)
                }
                Spacer()
                Menu {
                    if bottle.attachments != nil {
                        Button("Documents…", systemImage: "paperclip") { onAction(.documents(bottle, importing: false)) }
                    }
                    Button("Attach a Document…", systemImage: "doc.badge.plus") { onAction(.documents(bottle, importing: true)) }
                    Divider()
                    NoLongerInCellarButton { onAction(.remove(bottle)) }
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
            .buttonStyle(.borderedProminent)
            .accessibilityIdentifier("action.audit.finish")
    }
}

/// Checking a rack: the bottles of one place (a bin, or front and back), found one by one.
private struct CheckPlaceSheet: View {
    @Environment(CellarModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let rack: Rack
    let cell: RackCell
    @Binding var checked: Set<Slot>

    var body: some View {
        let bottles = model.cellar.bottles(in: cell)
        let slots = bottles.compactMap(\.slot)
        NavigationStack {
            List {
                Section {
                    ForEach(bottles) { bottle in
                        if let slot = bottle.slot {
                            Toggle(isOn: found(slot)) { row(bottle, slot) }
                        }
                    }
                }
                Section {
                    Button("All Here", systemImage: "checkmark.circle") {
                        checked.formUnion(slots)
                    }
                    .disabled(slots.allSatisfy { checked.contains($0) })
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

    private func found(_ slot: Slot) -> Binding<Bool> {
        Binding(
            get: { checked.contains(slot) },
            set: { isFound in
                if isFound { checked.insert(slot) } else { checked.remove(slot) }
            }
        )
    }

    /// The wine, and where it is in the place: front or back, or its number in a bin.
    private func row(_ bottle: Bottle, _ slot: Slot) -> some View {
        let wine = model.cellar.wines[bottle.wineID]
        let number = slot.depth + 1
        let position = rack.kind == .bins ? String(localized: "Bottle \(number)") : rack.depthName(slot.depth)
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
