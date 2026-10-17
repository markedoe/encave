import CaveKit
import SwiftUI

/// "Clos des Tilleuls 2017 ×3" with producer, region and where the bottles are.
struct BottleGroupRow: View {
    @Environment(CellarModel.self) private var model
    let group: WineGroup

    private var subtitle: String {
        [group.wine.producer, group.wine.region].compactMap { $0 }.joined(separator: " · ")
    }

    private var location: String? {
        let labels = group.bottles.compactMap { model.cellar.locationLabel(for: $0) }
        guard let first = labels.first else { return nil }
        return labels.count > 1 ? "\(first) +\(labels.count - 1)" : first
    }

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            WineColorDot(color: group.wine.color)
            VStack(alignment: .leading, spacing: 2) {
                Text(group.wine.displayName)
                    .font(.headline)
                if !subtitle.isEmpty {
                    Text(subtitle)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                if let location {
                    Label(location, systemImage: "square.grid.3x3")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            Spacer(minLength: 8)
            Text(verbatim: "×\(group.count)")
                .font(.body.monospacedDigit())
                .foregroundStyle(.secondary)
                .accessibilityLabel(Text("\(group.count) bottles"))
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
    }
}
