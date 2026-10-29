import CaveKit
import SwiftUI

extension DiamondEdge {
    /// "Top triangles", for the switches.
    var togglesTitle: LocalizedStringKey {
        switch self {
        case .top: "Top triangles"
        case .left: "Left triangles"
        case .bottom: "Bottom triangles"
        case .right: "Right triangles"
        }
    }

    /// An arrow pointing to the side, so the switches do not rely on their words alone.
    var systemImage: String {
        switch self {
        case .top: "arrowtriangle.up"
        case .left: "arrowtriangle.left"
        case .bottom: "arrowtriangle.down"
        case .right: "arrowtriangle.right"
        }
    }
}

/// The triangles along the sides of a diamond rack: a switch per side, then how many bottles a
/// triangle holds. The same in the wizard and the editor (Android: DiamondEdgesSection.kt).
struct DiamondEdgesSection: View {
    @Binding var edges: Set<DiamondEdge>
    @Binding var triangleCapacity: Int
    let binCapacity: Int

    var body: some View {
        Section {
            ForEach(DiamondEdge.allCases, id: \.self) { edge in
                Toggle(isOn: binding(edge)) {
                    Label(edge.togglesTitle, systemImage: edge.systemImage)
                }
                .accessibilityIdentifier("rack.edges.\(edge.rawValue)")
            }
            if !edges.isEmpty {
                Stepper(value: $triangleCapacity.animation(.snappy), in: 1...max(1, binCapacity)) {
                    LabeledContent("Bottles per triangle") { Text(verbatim: "\(triangleCapacity)").monospacedDigit() }
                }
                .accessibilityIdentifier("rack.edges.capacity")
            }
        } header: {
            Text("Edge triangles")
        } footer: {
            Text("Along the sides of a diamond rack, the frame leaves half bins: triangles that hold bottles too, about half a bin each. They are named by their side, like “Top triangle 3”.")
        }
    }

    private func binding(_ edge: DiamondEdge) -> Binding<Bool> {
        Binding(
            get: { edges.contains(edge) },
            set: { on in
                withAnimation(.snappy) {
                    if on { edges.insert(edge) } else { edges.remove(edge) }
                }
            }
        )
    }
}
