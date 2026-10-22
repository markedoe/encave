import CaveKit
import CoreGraphics
import CoreText
import SwiftUI

/// A printable inventory of the cellar (PDF, A4): every wine with its bottles,
/// where they are and what they cost. For insurance, a move, or the family.
/// Made on the device from what is in the cellar; nothing is looked up.
@MainActor
enum InventoryPDF {
    static let pageSize = CGSize(width: 595, height: 842)
    static let margin: CGFloat = 40
    /// Column widths; the places take what is left.
    static let wineWidth: CGFloat = 150
    static let regionWidth: CGFloat = 95
    static let bottlesWidth: CGFloat = 34
    static let valueWidth: CGFloat = 64
    static let spacing: CGFloat = 8
    static var placesWidth: CGFloat {
        pageSize.width - 2 * margin - wineWidth - regionWidth - bottlesWidth - valueWidth - 4 * spacing
    }
    static let rowPadding: CGFloat = 4
    /// Room for the title, the column header and the footer, with some to spare.
    static let titleHeight: CGFloat = 80
    static let headerHeight: CGFloat = 26
    static let footerHeight: CGFloat = 24

    struct Row: Identifiable {
        let id: UUID
        let wine: String
        let producer: String
        let region: String
        let bottles: Int
        /// One line per rack ("Main rack: A1, A2, B3"), then the other places.
        let places: [String]
        let value: String
        /// Measured, so every place is printed: a row takes as many lines as it needs.
        var height: CGFloat = 0
    }

    static func rows(for model: CellarModel) -> [Row] {
        let cellar = model.cellar
        return cellar.groups().map { group in
            let wine = group.wine
            var totals: [String: Decimal] = [:]
            for bottle in group.bottles {
                guard let price = bottle.purchasePrice else { continue }
                totals[bottle.currency ?? cellar.settings.currency, default: 0] += price
            }
            let value = totals.keys.sorted().map { code in
                (totals[code] ?? 0).formatted(.currency(code: code).precision(.fractionLength(0)))
            }.joined(separator: " + ")
            var row = Row(id: wine.id, wine: wine.displayName, producer: wine.producer ?? "",
                          region: [wine.appellation ?? wine.region, wine.country].compactMap { $0 }.joined(separator: ", "),
                          bottles: group.count, places: places(of: group.bottles, in: cellar), value: value)
            row.height = height(of: row)
            return row
        }
    }

    /// The bottles' places grouped by rack, in the order of the racks: "Main rack: A1, A2, B3".
    /// Bottles kept elsewhere follow, with their note and how many are there.
    static func places(of bottles: [Bottle], in cellar: Cellar) -> [String] {
        var lines: [String] = []
        for rack in cellar.racks {
            let slots = bottles.compactMap(\.slot).filter { $0.rackID == rack.id }
                .sorted { ($0.row, $0.column, $0.depth) < ($1.row, $1.column, $1.depth) }
            guard !slots.isEmpty else { continue }
            let rackName = rack.name
            let labels = slots.map { rack.displayLabel(for: $0) }.joined(separator: ", ")
            lines.append(String(localized: "\(rackName): \(labels)"))
        }
        let elsewhere = bottles.filter { bottle in bottle.slot.flatMap { cellar.rack($0.rackID) } == nil }
            .compactMap { $0.storageNote?.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        let counts = Dictionary(elsewhere.map { ($0, 1) }, uniquingKeysWith: +)
        for note in counts.keys.sorted() {
            let count = counts[note] ?? 0
            lines.append(count > 1 ? "\(note) ×\(count)" : note)
        }
        return lines
    }

    /// The height a row needs: its tallest column, measured with the fonts of the page.
    static func height(of row: Row) -> CGFloat {
        let wine = textHeight(row.wine, size: 9.5, bold: true, width: wineWidth)
            + (row.producer.isEmpty ? 0 : 1 + textHeight(row.producer, size: 8, width: wineWidth))
        let columns = [
            wine,
            textHeight(row.region, size: 8.5, width: regionWidth),
            textHeight(row.places.joined(separator: "\n"), size: 8.5, width: placesWidth),
            textHeight(row.value, size: 8.5, width: valueWidth),
        ]
        // A little more than measured: the page lays text out with its own line spacing.
        return ceil((columns.max() ?? 0) * 1.12) + 2 * rowPadding
    }

    private static func textHeight(_ text: String, size: CGFloat, bold: Bool = false, width: CGFloat) -> CGFloat {
        guard !text.isEmpty else { return 0 }
        let font = CTFontCreateUIFontForLanguage(bold ? .emphasizedSystem : .system, size, nil)
            ?? CTFontCreateWithName("Helvetica" as CFString, size, nil)
        let attributed = NSAttributedString(
            string: text, attributes: [NSAttributedString.Key(kCTFontAttributeName as String): font])
        let setter = CTFramesetterCreateWithAttributedString(attributed)
        let fitted = CTFramesetterSuggestFrameSizeWithConstraints(
            setter, CFRange(location: 0, length: 0), nil,
            CGSize(width: width, height: .greatestFiniteMagnitude), nil)
        return ceil(fitted.height)
    }

    /// Rows split into pages by their heights: fewer on the first page, which also has the title.
    static func pages(of rows: [Row]) -> [[Row]] {
        let content = pageSize.height - 2 * margin - headerHeight - footerHeight
        var pages: [[Row]] = [[]]
        var used: CGFloat = 0
        var available = content - titleHeight
        for row in rows {
            let needed = row.height + 1 // and its divider
            if used + needed > available, !(pages.last?.isEmpty ?? true) {
                pages.append([])
                used = 0
                available = content
            }
            pages[pages.count - 1].append(row)
            used += needed
        }
        return pages
    }

    /// The PDF, or nil if it could not be made.
    static func make(for model: CellarModel) -> Data? {
        let rows = rows(for: model)
        let stats = model.cellar.statistics(year: model.currentYear)
        let total = stats.valueByCurrency.keys.sorted().map { code in
            (stats.valueByCurrency[code] ?? 0).formatted(.currency(code: code).precision(.fractionLength(0)))
        }.joined(separator: " + ")
        let pages = pages(of: rows)
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
                    let bottlesLabel = String(localized: "\(bottleCount) bottles")
                    Text(verbatim: "\(dateLabel) · \(bottlesLabel)")
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
                HStack(alignment: .firstTextBaseline, spacing: InventoryPDF.spacing) {
                    VStack(alignment: .leading, spacing: 1) {
                        Text(verbatim: row.wine).font(.system(size: 9.5, weight: .semibold))
                        if !row.producer.isEmpty {
                            Text(verbatim: row.producer).font(.system(size: 8)).foregroundStyle(.secondary)
                        }
                    }
                    .frame(width: InventoryPDF.wineWidth, alignment: .leading)
                    Text(verbatim: row.region)
                        .frame(width: InventoryPDF.regionWidth, alignment: .leading)
                    Text(verbatim: "\(row.bottles)")
                        .monospacedDigit()
                        .frame(width: InventoryPDF.bottlesWidth, alignment: .trailing)
                    // Every place, on as many lines as it takes: an inventory must not cut any off.
                    Text(verbatim: row.places.joined(separator: "\n"))
                        .frame(width: InventoryPDF.placesWidth, alignment: .leading)
                    Text(verbatim: row.value)
                        .monospacedDigit()
                        .frame(width: InventoryPDF.valueWidth, alignment: .trailing)
                }
                .font(.system(size: 8.5))
                .fixedSize(horizontal: false, vertical: true)
                .padding(.vertical, InventoryPDF.rowPadding)
                .frame(minHeight: row.height, alignment: .top)
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
        .padding(InventoryPDF.margin)
    }

    private var header: some View {
        HStack(spacing: InventoryPDF.spacing) {
            Text("Wine").frame(width: InventoryPDF.wineWidth, alignment: .leading)
            Text("Region").frame(width: InventoryPDF.regionWidth, alignment: .leading)
            Text("Bottles").frame(width: InventoryPDF.bottlesWidth, alignment: .trailing)
            Text("Location").frame(width: InventoryPDF.placesWidth, alignment: .leading)
            Text("Purchase value").frame(width: InventoryPDF.valueWidth, alignment: .trailing)
        }
        .font(.system(size: 8.5, weight: .semibold))
        .padding(.vertical, 6)
        .overlay(alignment: .bottom) { Rectangle().frame(height: 1) }
    }
}
