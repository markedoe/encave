import CaveKit
import CoreGraphics
import SwiftUI

/// A printable inventory of the cellar (PDF, A4): every wine with its bottles,
/// where they are and what they cost. For insurance, a move, or the family.
/// Made on the device from what is in the cellar; nothing is looked up.
@MainActor
enum InventoryPDF {
    static let pageSize = CGSize(width: 595, height: 842)
    static let rowsPerPage = 26

    struct Row: Identifiable {
        let id: UUID
        let wine: String
        let producer: String
        let region: String
        let bottles: Int
        let places: String
        let value: String
    }

    static func rows(for model: CellarModel) -> [Row] {
        model.cellar.groups().map { group in
            let wine = group.wine
            let labels = group.bottles.compactMap { model.locationLabel(for: $0) }
            var totals: [String: Decimal] = [:]
            for bottle in group.bottles {
                guard let price = bottle.purchasePrice else { continue }
                totals[bottle.currency ?? model.cellar.settings.currency, default: 0] += price
            }
            let value = totals.keys.sorted().map { code in
                (totals[code] ?? 0).formatted(.currency(code: code).precision(.fractionLength(0)))
            }.joined(separator: " + ")
            return Row(id: wine.id, wine: wine.displayName, producer: wine.producer ?? "",
                       region: [wine.appellation ?? wine.region, wine.country].compactMap { $0 }.joined(separator: ", "),
                       bottles: group.count, places: labels.joined(separator: ", "), value: value)
        }
    }

    /// The PDF, or nil if it could not be made.
    static func make(for model: CellarModel) -> Data? {
        let rows = rows(for: model)
        let stats = model.cellar.statistics(year: model.currentYear)
        let total = stats.valueByCurrency.keys.sorted().map { code in
            (stats.valueByCurrency[code] ?? 0).formatted(.currency(code: code).precision(.fractionLength(0)))
        }.joined(separator: " + ")
        let pages = stride(from: 0, to: max(rows.count, 1), by: rowsPerPage).map {
            Array(rows[min($0, rows.count)..<min($0 + rowsPerPage, rows.count)])
        }
        let data = NSMutableData()
        var box = CGRect(origin: .zero, size: pageSize)
        guard let consumer = CGDataConsumer(data: data as CFMutableData),
              let context = CGContext(consumer: consumer, mediaBox: &box, nil) else { return nil }
        for (index, rows) in pages.enumerated() {
            let page = InventoryPage(rows: rows, pageNumber: index + 1, pageCount: pages.count,
                                     bottleCount: stats.bottleCount, valueLabel: total, isFirst: index == 0)
                .frame(width: pageSize.width, height: pageSize.height)
                .environment(\.colorScheme, .light)
            let renderer = ImageRenderer(content: page)
            renderer.proposedSize = ProposedViewSize(pageSize)
            renderer.render { _, render in
                context.beginPDFPage(nil)
                render(context)
                context.endPDFPage()
            }
        }
        context.closePDF()
        return data as Data
    }
}

/// One A4 page of the inventory.
private struct InventoryPage: View {
    let rows: [InventoryPDF.Row]
    let pageNumber: Int
    let pageCount: Int
    let bottleCount: Int
    let valueLabel: String
    let isFirst: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if isFirst {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Cellar inventory")
                        .font(.system(size: 22, weight: .semibold, design: .serif))
                    let dateLabel = Date.now.formatted(date: .long, time: .omitted)
                    Text("\(dateLabel) · \(bottleCount) bottles")
                        .font(.system(size: 11))
                    if !valueLabel.isEmpty {
                        Text("Purchase value: \(valueLabel)")
                            .font(.system(size: 11))
                    }
                }
                .padding(.bottom, 14)
            }
            header
            ForEach(rows) { row in
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    VStack(alignment: .leading, spacing: 1) {
                        Text(verbatim: row.wine).font(.system(size: 9.5, weight: .semibold))
                        if !row.producer.isEmpty {
                            Text(verbatim: row.producer).font(.system(size: 8)).foregroundStyle(.secondary)
                        }
                    }
                    .frame(width: 170, alignment: .leading)
                    Text(verbatim: row.region).frame(width: 110, alignment: .leading)
                    Text(verbatim: "\(row.bottles)").frame(width: 40, alignment: .trailing).monospacedDigit()
                    Text(verbatim: row.places).frame(maxWidth: .infinity, alignment: .leading)
                    Text(verbatim: row.value).frame(width: 70, alignment: .trailing).monospacedDigit()
                }
                .font(.system(size: 8.5))
                .lineLimit(2)
                .frame(height: 26)
                Divider()
            }
            Spacer(minLength: 0)
            HStack {
                Text("Made with Encave")
                Spacer()
                Text("Page \(pageNumber) of \(pageCount)")
            }
            .font(.system(size: 8))
            .foregroundStyle(.secondary)
        }
        // Rendered in the light appearance (see `make`): dark text on the white page.
        .padding(40)
    }

    private var header: some View {
        HStack(spacing: 8) {
            Text("Wine").frame(width: 170, alignment: .leading)
            Text("Region").frame(width: 110, alignment: .leading)
            Text("Bottles").frame(width: 40, alignment: .trailing)
            Text("Location").frame(maxWidth: .infinity, alignment: .leading)
            Text("Purchase value").frame(width: 70, alignment: .trailing)
        }
        .font(.system(size: 8.5, weight: .semibold))
        .padding(.vertical, 6)
        .overlay(alignment: .bottom) { Rectangle().frame(height: 1) }
    }
}
