import CaveKit
import SwiftUI

struct RacksView: View {
    @Environment(CellarModel.self) private var model
    @Environment(SceneRouter.self) private var router: SceneRouter?
    @State private var path = NavigationPath()
    @State private var deletingRackID: UUID?

    var body: some View {
        NavigationStack(path: $path) {
            Group {
                if model.cellar.racks.isEmpty {
                    ContentUnavailableView {
                        Label("No storage yet", systemImage: "square.grid.3x3")
                    } description: {
                        Text("Describe your storage to see exactly where each bottle is.")
                    } actions: {
                        Button {
                            router?.sheet = .editRack(rackID: nil)
                        } label: {
                            Text("Add Storage").frame(minWidth: 200)
                        }
                        .prominentButtonStyle()
                        .controlSize(.large)
                    }
                } else {
                    ScrollView {
                        // Bottles without a position: one tap from putting them away.
                        let loose = model.cellar.bottlesNotInStorage.count
                        if loose > 0 {
                            NavigationLink(value: NotInStorageRoute()) {
                                NotInStorageCard(count: loose)
                            }
                            .buttonStyle(.plain)
                            .padding([.horizontal, .top])
                            .accessibilityIdentifier("racks.notInStorage")
                        }
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: 260), spacing: 16, alignment: .top)], alignment: .leading, spacing: 16) {
                            ForEach(Array(model.cellar.racks.enumerated()), id: \.element.id) { index, rack in
                                NavigationLink(value: rack.id) {
                                    RackCard(rack: rack)
                                }
                                .accessibilityIdentifier("rack.card.\(index)")
                                .buttonStyle(.plain)
                                .contextMenu {
                                    Button("Edit Storage", systemImage: "pencil") { router?.sheet = .editRack(rackID: rack.id) }
                                    Button("Move Up", systemImage: "arrow.up") { moveRack(at: index, by: -1) }
                                        .disabled(index == 0)
                                    Button("Move Down", systemImage: "arrow.down") { moveRack(at: index, by: 1) }
                                        .disabled(index == model.cellar.racks.count - 1)
                                    Divider()
                                    Button("Delete Storage…", systemImage: "trash", role: .destructive) {
                                        deletingRackID = rack.id
                                    }
                                }
                                .accessibilityActions {
                                    if index > 0 {
                                        Button("Move Up") { moveRack(at: index, by: -1) }
                                    }
                                    if index < model.cellar.racks.count - 1 {
                                        Button("Move Down") { moveRack(at: index, by: 1) }
                                    }
                                }
                            }
                        }
                        .padding()
                    }
                }
            }
            .navigationTitle("Storage")
            .confirmsRackDeletion($deletingRackID)
            .navigationDestination(for: UUID.self) { RackDetailView(rackID: $0) }
            .navigationDestination(for: NotInStorageRoute.self) { _ in NotInStorageView() }
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        router?.sheet = .editRack(rackID: nil)
                    } label: {
                        Label("Add Storage", systemImage: "plus")
                    }
                    .accessibilityIdentifier("racks.add")
                }
                #if os(iOS)
                SettingsToolbarItem()
                #endif
            }
            .onChange(of: router?.highlightedSlot, initial: true) { _, slot in
                guard let slot else { return }
                let rack = NavigationPath([slot.rackID])
                if path != rack { path = rack }
            }
        }
    }

    /// One place up (-1) or down (1) in the list of racks.
    private func moveRack(at index: Int, by step: Int) {
        let target = index + step
        guard model.cellar.racks.indices.contains(index), model.cellar.racks.indices.contains(target) else { return }
        // "Insert before" offsets, as List.onMove gives them.
        let offset = step > 0 ? target + 1 : target
        withAnimation(.snappy) {
            _ = model.update { $0.moveRacks(fromOffsets: IndexSet(integer: index), toOffset: offset) }
        }
    }
}

private struct RackCard: View {
    @Environment(CellarModel.self) private var model
    let rack: Rack

    var body: some View {
        let used = model.cellar.occupancy(of: rack)
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(rack.name)
                        .font(.title3.weight(.semibold))
                    Text(rack.storageSummary)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Image(systemName: rack.systemImage)
                    .foregroundStyle(.secondary)
                    .accessibilityHidden(true)
            }
            RackThumbnail(rack: rack)
                .frame(maxWidth: .infinity, alignment: .leading)
            // The count at the bottom, level with the other cards of the row.
            Spacer(minLength: 0)
            VStack(alignment: .leading, spacing: 6) {
                ProgressView(value: Double(used), total: Double(max(1, model.cellar.capacity(of: rack))))
                    .tint(Color.accentColor)
                Text("\(used) of \(model.cellar.capacity(of: rack)) bottles")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(18)
        // As tall as the tallest card of its row in the grid.
        .frame(maxHeight: .infinity, alignment: .top)
        .background(Color.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .contentShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
        .accessibilityElement(children: .combine)
    }
}
