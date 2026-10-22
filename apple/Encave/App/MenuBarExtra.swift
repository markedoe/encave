#if os(macOS)
import AppKit
import CaveKit
import SwiftUI

/// The Mac menu bar: what to open tonight at a glance, without opening the window.
/// Shown once turned on in Settings or in Tonight.
struct CellarMenuBarContent: View {
    @Environment(CellarModel.self) private var model
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        let candidates = model.cellar.groupsByMaturity(year: model.currentYear)
            .filter { $0.maturity == .drinkSoon || $0.maturity == .ready }
            .flatMap(\.groups)
            .prefix(6)
        VStack(alignment: .leading, spacing: 10) {
            Text("Tonight")
                .font(.headline)
            if candidates.isEmpty {
                Text("Nothing to suggest yet")
                    .foregroundStyle(.secondary)
                // A new cellar: the way in is to add bottles.
                if model.cellar.bottlesInCellar.isEmpty {
                    Button("Add a Bottle…") {
                        model.request(.present(.addBottle(AddBottleContext())))
                        MainWindow.show(openWindow)
                    }
                    .buttonStyle(.borderless)
                }
            } else {
                ForEach(Array(candidates)) { group in
                    Button {
                        open(group.wine.id)
                    } label: {
                        HStack(spacing: 10) {
                            BottleEnd(color: group.wine.color, size: 22)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(verbatim: group.wine.displayName)
                                    .fontWeight(.medium)
                                    .lineLimit(1)
                                HStack(spacing: 6) {
                                    let maturity = Maturity.of(group.wine, year: model.currentYear)
                                    MaturityLabel(maturity: maturity, short: true)
                                    if let place = group.bottles.first.flatMap({ model.locationLabel(for: $0) }) {
                                        Text(verbatim: place).foregroundStyle(.secondary).lineLimit(1)
                                    }
                                }
                                .font(.caption)
                                .labelStyle(CompactLabelStyle())
                            }
                            Spacer(minLength: 4)
                            CountBadge(count: group.count)
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityElement(children: .combine)
                    .accessibilityHint(Text("Opens the wine in Encave."))
                }
            }
            Divider()
            Button("Open Encave") { MainWindow.show(openWindow) }
                .buttonStyle(.borderless)
        }
        .padding(14)
        .frame(width: 320)
    }

    private func open(_ wineID: UUID) {
        model.open(wine: wineID)
        MainWindow.show(openWindow)
    }
}

/// The cellar window, for the menu bar and the menu commands.
enum MainWindow {
    /// Brings the cellar window forward, or opens one if it was closed. A request made just
    /// before (`CellarModel.request`) is shown in it.
    @MainActor
    static func show(_ openWindow: OpenWindowAction) {
        NSApplication.shared.activate()
        if let window = NSApplication.shared.windows.first(where: {
            $0.isVisible && $0.canBecomeMain && ($0.identifier?.rawValue.hasPrefix("main") ?? false)
        }) {
            window.makeKeyAndOrderFront(nil)
        } else {
            openWindow(id: "main")
        }
    }
}
#endif
