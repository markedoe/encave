import Foundation

/// How to serve a wine: temperature range and decanting.
public struct ServingAdvice: Equatable, Sendable {
    /// Degrees Celsius.
    public var minTemperature: Int
    public var maxTemperature: Int
    /// Suggested decanting time in minutes, if any.
    public var decantMinutes: Int?

    public static func advice(for wine: Wine, year: Int) -> ServingAdvice? {
        guard let color = wine.color else { return nil }
        let text = ([wine.region, wine.appellation] + wine.grapes.map(Optional.some))
            .compactMap { $0?.lowercased() }.joined(separator: " ")
        func has(_ words: [String]) -> Bool { words.contains { text.contains($0) } }
        let age = wine.vintage.map { year - $0 }

        switch color {
        case .red:
            let light = has(["pinot", "gamay", "beaujolais", "bourgogne", "burgundy", "loire", "zweigelt", "frappato"])
            let full = has(["cabernet", "syrah", "shiraz", "malbec", "nebbiolo", "tannat", "mourvèdre", "bordeaux", "rhône", "rhone", "barolo"])
            let range = light ? (14, 16) : (full ? (16, 18) : (15, 17))
            var decant: Int?
            if full, let age { decant = age < 8 ? 60 : (age < 15 ? 30 : nil) }
            return ServingAdvice(minTemperature: range.0, maxTemperature: range.1, decantMinutes: decant)
        case .white:
            let rich = has(["chardonnay", "meursault", "bourgogne", "burgundy", "viognier", "marsanne", "roussanne", "savagnin", "jura"])
            return ServingAdvice(minTemperature: rich ? 10 : 8, maxTemperature: rich ? 13 : 10, decantMinutes: nil)
        case .rose:
            return ServingAdvice(minTemperature: 8, maxTemperature: 10, decantMinutes: nil)
        case .sparkling:
            let vintageSparkling = wine.vintage != nil
            return ServingAdvice(minTemperature: vintageSparkling ? 8 : 6, maxTemperature: vintageSparkling ? 10 : 8, decantMinutes: nil)
        case .dessert:
            return ServingAdvice(minTemperature: 8, maxTemperature: 11, decantMinutes: nil)
        case .fortified:
            let port = has(["porto", "port", "douro", "madeira", "banyuls", "maury"])
            return ServingAdvice(minTemperature: port ? 16 : 10, maxTemperature: port ? 18 : 14, decantMinutes: nil)
        case .orange:
            return ServingAdvice(minTemperature: 12, maxTemperature: 14, decantMinutes: 30)
        }
    }
}

#if canImport(Darwin)
public extension ServingAdvice {
    /// "16 – 18 °C", or Fahrenheit where the locale uses it: the unit once, after the range.
    func temperatureRange(locale: Locale = .autoupdatingCurrent) -> String {
        let style = Measurement<UnitTemperature>.FormatStyle(
            width: .abbreviated, locale: locale, usage: .weather,
            numberFormatStyle: .number.locale(locale).precision(.fractionLength(0))
        )
        let low = Measurement(value: Double(minTemperature), unit: UnitTemperature.celsius)
        let high = Measurement(value: Double(maxTemperature), unit: UnitTemperature.celsius)
        // The low end without its unit, in the unit the high end is shown in.
        let lowText = low.formatted(style.attributed)
        let lowNumber = lowText.runs.filter { $0.measurement == .value }
            .map { String(lowText[$0.range].characters) }.joined()
        return "\(lowNumber.isEmpty ? low.formatted(style) : lowNumber) – \(high.formatted(style))"
    }
}
#endif
