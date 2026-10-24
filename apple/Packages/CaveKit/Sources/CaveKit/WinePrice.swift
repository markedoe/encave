import Foundation

// Finds what a wine sells for today in web search results (general search engines
// only, no wine-platform API). Only the search terms are sent; the results are read
// here, on the device. The user always sees the prices found and accepts the value.

/// A price seen in a search result.
public struct FoundPrice: Identifiable, Hashable, Sendable {
    public var amount: Decimal
    /// ISO code: EUR, USD, GBP or CHF.
    public var currency: String
    public var domain: String
    public var url: URL
    /// The words around the price, to judge it.
    public var excerpt: String

    public var id: String { "\(url.absoluteString)|\(amount)|\(currency)" }
}

extension WebSearchQuery {
    /// "Domaine X Chablis 1er Cru 2019 magnum price": the wine's search terms, the bottle
    /// size when it is not 75 cl, and "price" in the user's language.
    public static func priceTerms(producer: String?, name: String, appellation: String?, vintage: Int?,
                                  sizeML: Int = 750, priceWord: String = "price") -> String {
        var parts = [terms(producer: producer, name: name, appellation: appellation, vintage: vintage, wineWord: "")]
        if let size = sizeWords(sizeML) { parts.append(size) }
        let word = priceWord.trimmingCharacters(in: .whitespaces)
        if !word.isEmpty { parts.append(word) }
        return parts.filter { !$0.isEmpty }.joined(separator: " ")
    }

    public static func priceTerms(for wine: Wine, sizeML: Int = 750, priceWord: String = "price") -> String {
        priceTerms(producer: wine.producer, name: wine.name, appellation: wine.appellation, vintage: wine.vintage,
                   sizeML: sizeML, priceWord: priceWord)
    }

    /// Nil for a 75 cl bottle.
    static func sizeWords(_ sizeML: Int) -> String? {
        switch sizeML {
        case 750: nil
        case 375: "37.5 cl"
        case 620: "clavelin"
        case 1000: "1 l"
        case 1500: "magnum"
        case 3000: "double magnum"
        default: sizeML % 10 == 0 ? "\(sizeML / 10) cl" : "\(sizeML) ml"
        }
    }
}

public enum PriceExtractor {
    // The same patterns are used by the Android app (PriceExtractor.kt).
    // At most four groups of thousands: a long run of digit groups is read in linear time.
    static let number = #"(?:\d{1,3}(?:[ \u00A0\u202F.,'’]\d{3}){1,4}(?:[.,]\d{1,2})?|\d+(?:[.,]\d{1,2})?)"#
    /// Swiss whole francs: "13.–", "13.-".
    static let noCents = #"(?:[.,][–—-]{1,2})?"#
    /// "€45", "CHF 45.90", "US$ 1,234.50", "CHF 13.–", "Fr. 13.50", "SFr. 13.50".
    static let prefixPattern = #"(?<![\p{L}\d])(US\$|€|\$|£|EUR|USD|GBP|CHF|SFr\.?|Fr\.)[ \u00A0\u202F]?("# + number + #")"#
        + noCents + #"(?!\d)"#
    /// "45 €", "1 234,50 €", "45 EUR", "45 euros", "13.– CHF", "13.50 Fr.".
    static let suffixPattern = #"(?<![\d.,'’])("# + number + #")"# + noCents
        + #"[ \u00A0\u202F]?(€|\$|£|EUR|USD|GBP|CHF|euros?|S?Fr\.?)(?!\p{L})"#

    /// Several bottles, a case or delivery: not the price of one bottle.
    static let notOneBottleBefore = #"(?:\b\d+ ?x\b|\bx ?\d+\b|\bcase\b|\bcases\b|\bcarton|\bcaisse|\blot\b|\bpack\b|\bcoffret|\bbox\b|\bshipping|\bdelivery|\blivraison|\bfrais de port|\bdozen|\bdouzaine)"#
    static let notOneBottleAfter = #"^[ \u00A0]*(?:(?:for|pour|les|the) \d{1,2}\b|x ?\d+|/ ?(?:case|caisse|carton|lot|pack|\d)|\( ?\d+ ?(?:x|bottles|bouteilles|btl)|(?:per|par|la|le) (?:case|caisse|carton|lot))"#
    /// Bottle sizes named near a price, larger sizes first ("double magnum" before "magnum").
    static let sizePatterns: [(Int, String)] = [
        (3000, #"\bdouble[- ]magnum|\bjeroboam|\b3 ?l\b|\b300 ?cl"#),
        (1500, #"\bmagnum|\b1[.,]5 ?l\b|\b150 ?cl|\b1500 ?ml"#),
        (1000, #"\b1 ?l\b|\b1 ?lit(?:re|er)\b|\b1[.,]0 ?l\b|\b100 ?cl|\b1000 ?ml"#),
        (375, #"\bhalf[- ]bottle|\bdemi[- ]bouteille|\bfillette|\b37[.,]5 ?cl|\b375 ?ml|\b0[.,]375 ?l\b"#),
        (750, #"\b75 ?cl|\b750 ?ml|\b0[.,]75 ?l\b"#),
        (700, #"\b70 ?cl|\b700 ?ml|\b0[.,]7 ?l\b"#),
        (620, #"\bclavelin|\b62 ?cl|\b620 ?ml|\b0[.,]62 ?l\b"#),
        (500, #"\b50 ?cl|\b500 ?ml|\b0[.,]5 ?l\b"#),
    ]

    static let range: ClosedRange<Decimal> = 3...20_000
    /// Matches of each pattern read in one text, at most.
    static let maxMatches = 50

    /// Prices of one bottle of `sizeML` in the titles and snippets of the results.
    public static func prices(in results: [WebSearchResult], sizeML: Int = 750) -> [FoundPrice] {
        var seen = Set<String>()
        var found: [FoundPrice] = []
        for result in results {
            for text in [result.title, result.snippet] {
                for (amount, currency, excerpt) in prices(in: text, sizeML: sizeML) {
                    let price = FoundPrice(amount: amount, currency: currency, domain: result.domain, url: result.url,
                                           excerpt: excerpt)
                    if seen.insert(price.id).inserted { found.append(price) }
                }
            }
        }
        return found
    }

    /// Amounts with a currency in a text, without those that are not for one bottle.
    static func prices(in text: String, sizeML: Int) -> [(Decimal, String, String)] {
        textPrices(in: text, sizeML: sizeML).map { ($0.amount, $0.currency, $0.excerpt) }
    }

    /// A price in a text: where it is, and the size named next to it, if any.
    struct TextPrice {
        var amount: Decimal
        var currency: String
        var excerpt: String
        /// UTF-16 offset of the price in the text.
        var location: Int
        var namedSize: Int?
    }

    /// As `prices(in:sizeML:)`; a price without a size next to it is for `defaultSize`.
    static func textPrices(in text: String, sizeML: Int, defaultSize: Int = 750) -> [TextPrice] {
        let nsText = text as NSString
        let whole = NSRange(location: 0, length: nsText.length)
        struct Match { var range: NSRange; var amount: String; var symbol: String; var isSuffix: Bool }
        var matches: [Match] = []
        for (pattern, isSuffix) in [(prefixPattern, false), (suffixPattern, true)] {
            guard let regex = regex(pattern) else { continue }
            var count = 0
            regex.enumerateMatches(in: text, range: whole) { match, _, stop in
                guard let match else { return }
                let first = nsText.substring(with: match.range(at: 1)), second = nsText.substring(with: match.range(at: 2))
                matches.append(Match(range: match.range, amount: isSuffix ? first : second,
                                     symbol: isSuffix ? second : first, isSuffix: isSuffix))
                count += 1
                if count >= maxMatches { stop.pointee = true }
            }
        }
        // "2019 €45": the vintage followed by a price is not "2019 €". Otherwise the
        // amount before its symbol ("45,00 € 39,90 €") wins.
        func isYear(_ amount: String) -> Bool {
            amount.count == 4 && amount.allSatisfy(\.isNumber) && (1900...2100).contains(Int(amount) ?? 0)
        }
        var kept: [Match] = []
        // Sorted, so only the last kept match can overlap the next one.
        for match in matches.sorted(by: { $0.range.location < $1.range.location }) {
            if let last = kept.last, NSIntersectionRange(last.range, match.range).length > 0 {
                let index = kept.count - 1
                let other = kept[index]
                let suffix = match.isSuffix ? match : other, prefix = match.isSuffix ? other : match
                if suffix.isSuffix != prefix.isSuffix {
                    kept[index] = isYear(suffix.amount) ? prefix : suffix
                }
                continue
            }
            kept.append(match)
        }

        return kept.compactMap { match in
            guard let amount = decimal(match.amount), range.contains(amount) else { return nil }
            let before = context(nsText, before: match.range.location)
            let after = context(nsText, after: match.range.location + match.range.length)
            if contains(before, notOneBottleBefore) || contains(after, notOneBottleAfter) { return nil }
            // A price without a size is for 75 cl (or the page's size); another size must be named.
            let size = namedSize(before + " " + after)
            if (size ?? defaultSize) != sizeML { return nil }
            let excerpt = (before + nsText.substring(with: match.range) + after)
                .split(whereSeparator: \.isWhitespace).joined(separator: " ")
            return TextPrice(amount: amount, currency: currencyCode(match.symbol), excerpt: excerpt,
                             location: match.range.location, namedSize: size)
        }
    }

    /// The median of the prices in `currency`, rounded to the cent; nil if there are none.
    public static func suggested(_ prices: [FoundPrice], currency: String) -> Decimal? {
        let amounts = prices.filter { $0.currency == currency }.map(\.amount).sorted()
        guard !amounts.isEmpty else { return nil }
        let middle = amounts.count / 2
        var median = amounts.count.isMultiple(of: 2) ? (amounts[middle - 1] + amounts[middle]) / 2 : amounts[middle]
        var rounded = Decimal()
        NSDecimalRound(&rounded, &median, 2, .plain)
        return rounded
    }

    /// "1 234,50" → 1234.5; "1,234.50" → 1234.5; "45" → 45.
    static func decimal(_ text: String) -> Decimal? {
        var integer = text, fraction = ""
        if let last = text.lastIndex(where: { $0 == "." || $0 == "," }) {
            let tail = text[text.index(after: last)...]
            if (1...2).contains(tail.count), tail.allSatisfy(\.isASCIIDigit) {
                integer = String(text[..<last])
                fraction = String(tail)
            }
        }
        let digits = integer.filter(\.isASCIIDigit)
        guard !digits.isEmpty else { return nil }
        return Decimal(string: fraction.isEmpty ? digits : "\(digits).\(fraction)", locale: Locale(identifier: "en_US_POSIX"))
    }

    static func currencyCode(_ symbol: String) -> String {
        switch symbol.uppercased() {
        case "€", "EUR", "EURO", "EUROS": "EUR"
        case "$", "US$", "USD": "USD"
        case "£", "GBP": "GBP"
        default: "CHF"
        }
    }

    /// Up to 30 characters before the price, in the same sentence ("0.7 l" does not end one).
    static func context(_ text: NSString, before location: Int) -> String {
        let start = max(0, location - 30)
        var piece = text.substring(with: NSRange(location: start, length: location - start))
        if let cut = piece.lastIndex(where: { ";|•·\n!?".contains($0) }) {
            piece = String(piece[piece.index(after: cut)...])
        }
        if let stop = piece.range(of: ". ", options: .backwards) { piece = String(piece[stop.upperBound...]) }
        return piece
    }

    /// Up to 25 characters after the price, in the same sentence.
    static func context(_ text: NSString, after location: Int) -> String {
        let length = min(25, text.length - location)
        guard length > 0 else { return "" }
        var piece = text.substring(with: NSRange(location: location, length: length))
        if let cut = piece.firstIndex(where: { ";|•·\n!?".contains($0) }) { piece = String(piece[..<cut]) }
        if let stop = piece.range(of: ". ") { piece = String(piece[..<stop.lowerBound]) }
        return piece
    }

    /// The patterns above, compiled once (compiling is slow, matching is not). Read only, so
    /// shared between tasks.
    nonisolated(unsafe) static let compiled: [String: NSRegularExpression] = {
        var all: [String: NSRegularExpression] = [:]
        for pattern in [prefixPattern, suffixPattern, notOneBottleBefore, notOneBottleAfter] + sizePatterns.map(\.1) {
            all[pattern] = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive])
        }
        return all
    }()

    static func regex(_ pattern: String) -> NSRegularExpression? {
        compiled[pattern] ?? (try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]))
    }

    /// The first text matching `pattern`, ignoring case.
    static func firstMatch(of pattern: String, in text: String) -> String? {
        let nsText = text as NSString
        guard let regex = regex(pattern),
              let match = regex.firstMatch(in: text, range: NSRange(location: 0, length: nsText.length)) else { return nil }
        return nsText.substring(with: match.range)
    }

    static func contains(_ text: String, _ pattern: String) -> Bool {
        guard let regex = regex(pattern) else { return false }
        return regex.firstMatch(in: text, range: NSRange(location: 0, length: (text as NSString).length)) != nil
    }

    static func namedSize(_ text: String) -> Int? {
        sizePatterns.first { contains(text, $0.1) }?.0
    }

    /// The size named in a text and its words as written ("70 cl"), the first pattern that matches.
    static func sizeWords(in text: String) -> (sizeML: Int, words: String)? {
        for (size, pattern) in sizePatterns {
            if let words = firstMatch(of: pattern, in: text) { return (size, words) }
        }
        return nil
    }
}

/// The prompt for the on-device model that reads a price from the results.
public enum PriceSuggestions {
    public static func prompt(terms: String, prices: [FoundPrice], currency: String, sizeML: Int, limit: Int = 12) -> String {
        let lines = prices.prefix(limit).enumerated().map { index, price in
            "\(index + 1). \(price.amount) \(price.currency) on \(price.domain): \(price.excerpt)"
        }.joined(separator: "\n")
        return """
        These prices were found on the web for this wine: \(terms).
        Give the usual shop price today for one bottle of \(sizeML) ml, in \(currency). Leave out prices \
        for several bottles, other sizes, other vintages or other wines, and prices that look wrong. \
        Answer 0 if none of these prices fits.

        \(lines)
        """
    }
}
