import CaveKit
import SwiftUI

struct RacksView: View {
    @Environment(CellarModel.self) private var model
    @Environment(SceneRouter.self) private var router: SceneRouter?
    @State private var path = NavigationPath()

    var body: some View {
        NavigationStack(path: $path) {
            Group {
                if model.cellar.racks.isEmpty {
                    ContentUnavailableView {
                        Label("No racks yet", systemImage: "square.grid.3x3")
                    } description: {
                        Text("Describe your racks to see exactly where each bottle is.")
                    } actions: {
                        Button {
                            router?.sheet = .editRack(rackID: nil)
                        } label: {
                            Text("Add a Rack").frame(minWidth: 200)
                        }
                        .buttonStyle(.borderedProminent)
                        .controlSize(.large)
                    }
                } else {
                    ScrollView {
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: 260), spacing: 16)], spacing: 16) {
                            ForEach(Array(model.cellar.racks.enumerated()), id: \.element.id) { index, rack in
                                NavigationLink(value: rack.id) {
                                    RackCard(rack: rack)
                                }
                                .accessibilityIdentifier("rack.card.\(index)")
                                .buttonStyle(.plain)
                                .contextMenu {
                                    Button("Edit Rack", systemImage: "pencil") { router?.sheet = .editRack(rackID: rack.id) }
                                    Button("Delete Rack", systemImage: "trash", role: .destructive) {
                                        model.deleteRack(rack.id)
                                    }
                                }
                            }
                        }
                        .padding()
                    }
                }
            }
            .navigationTitle("Racks")
            .navigationDestination(for: UUID.self) { RackDetailView(rackID: $0) }
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        router?.sheet = .editRack(rackID: nil)
                    } label: {
                        Label("Add Rack", systemImage: "plus")
                    }
                    .accessibilityIdentifier("racks.add")
                }
                #if os(iOS)
                SettingsToolbarItem()
                #endif
            }
            .onChange(of: model.highlightedSlot, initial: true) { _, slot in
                guard let slot else { return }
                let rack = NavigationPath([slot.rackID])
                if path != rack { path = rack }
            }
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
                    Text(rack.kind.title)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Image(systemName: rack.kind.systemImage)
                    .foregroundStyle(.secondary)
                    .accessibilityHidden(true)
            }
            RackThumbnail(rack: rack)
                .frame(maxWidth: .infinity, alignment: .leading)
            VStack(alignment: .leading, spacing: 6) {
                ProgressView(value: Double(used), total: Double(max(1, rack.capacity)))
                    .tint(Color.accentColor)
                Text("\(used) of \(rack.capacity) bottles")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(18)
        .background(Color.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .contentShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
        .accessibilityElement(children: .combine)
    }
}
