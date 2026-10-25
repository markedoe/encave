import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

// Reads the price of a bottle on the web pages a price search found. After the search, the
// app opens the first result pages from the device (no cookies, cache, credentials or
// Referer) and reads them here, on the device. The Android app does the same
// (PagePrices.kt); both are checked with fixtures/prices.
//
// Pages come from anyone: the reading is linear in the page's size (no regular expression
// runs on the HTML), stops at `maxParsedUnits`, and checks `shouldStop` between steps. Any
// text, cut anywhere, is read without failing (fixtures and fuzz tests on both platforms).

public enum PagePrices {
    /// The first results opened, in their order.
    public static let maxPages = 5
    /// Pages read at the same time.
    public static let parallelPages = 3
    /// Reading stops after this many bytes; the price is near the top of a product page.
    public static let maxBytes = 1_500_000
    /// At most this much of the page is parsed (UTF-16 units): all that was read, since
    /// product data often comes after a lot of inline styles.
    static let maxParsedUnits = maxBytes
    /// For the whole page: name lookup, redirects and reading.
    public static let timeout: TimeInterval = 8
    public static let maxRedirects = 3

    /// The results whose pages are opened: the first `maxPages`, if their address may be.
    public static func pages(of results: [WebSearchResult]) -> [WebSearchResult] {
        results.prefix(maxPages).filter { isAllowed($0.url) }
    }

    /// https on the usual port, no user or password, and a public name: never an IP address,
    /// this device or the local network (a router, a NAS), whatever the search results say.
    /// Redirects are checked too.
    public static func isAllowed(_ url: URL) -> Bool {
        guard let checked = WebImageDownload.httpsURL(url.absoluteString), let host = checked.host,
              checked.user == nil, checked.password == nil, checked.port == nil || checked.port == 443 else { return false }
        let name = PublicHost.bare(host)
        return !PublicHost.isAddress(name) && PublicHost.mayBePublic(name)
    }

    /// True when every address a name resolves to is public (`PublicHost.isPublicAddress`).
    public static func arePublic(_ addresses: [String]) -> Bool {
        PublicHost.arePublic(addresses)
    }

    /// Resolves the name and checks its addresses before the page is asked for. The connection
    /// resolves the name again: a name that changes its answer in between still needs a valid
    /// certificate for that public name.
    public static func resolvesPublicly(_ url: URL, resolver: LocalName.Resolver? = nil) async -> Bool {
        guard isAllowed(url), let host = url.host else { return false }
        return await PublicHost.resolvesPublicly(host, resolver: resolver)
    }

    /// Fields never sent on to a redirect's target.
    static let droppedOnRedirect = ["Referer", "Cookie", "Authorization"]

    /// The request for redirect number `hop` (1 for the first): nil after `maxRedirects`, or
    /// when the target is not allowed or does not resolve to public addresses only; otherwise
    /// `request` without Referer, cookies or credentials.
    public static func nextRequest(hop: Int, request: URLRequest, resolver: LocalName.Resolver? = nil) async -> URLRequest? {
        guard hop <= maxRedirects, let target = request.url, isAllowed(target),
              await resolvesPublicly(target, resolver: resolver) else { return nil }
        var next = request
        for field in droppedOnRedirect { next.setValue(nil, forHTTPHeaderField: field) }
        return next
    }

    /// True when a page's answer is read: a 2xx status, at an allowed final address, HTML, and
    /// not announced longer than `maxBytes` (-1: unknown, capped while reading).
    public static func acceptsResponse(statusCode: Int, url: URL?, contentType: String?, expectedLength: Int64) -> Bool {
        (200..<300).contains(statusCode) && (url.map(isAllowed) ?? false) && isHTML(contentType: contentType)
            && acceptsLength(expectedLength)
    }

    /// "text/html; charset=utf-8" or XHTML; anything else is not read.
    public static func isHTML(contentType: String?) -> Bool {
        guard let type = contentType?.split(separator: ";").first?.trimmingCharacters(in: .whitespaces).lowercased() else {
            return false
        }
        return type == "text/html" || type == "application/xhtml+xml"
    }

    /// False when the server announces more than `maxBytes` (-1: unknown, capped while reading).
    public static func acceptsLength(_ length: Int64) -> Bool { length <= Int64(maxBytes) }

    /// The page's text, in the character set the server or the page names (UTF-8 otherwise).
    public static func text(of data: Data, contentType: String?) -> String {
        let head = String(decoding: data.prefix(2048), as: UTF8.self)
        let charset = charsetName(in: contentType ?? "") ?? charsetName(in: head)
        switch charset {
        case "iso-8859-1", "latin1", "iso8859-1", "us-ascii", "ascii":
            return String(data: data, encoding: .isoLatin1) ?? String(decoding: data, as: UTF8.self)
        case "windows-1252", "cp1252", "iso-8859-15", "latin-9":
            return String(data: data, encoding: .windowsCP1252) ?? String(decoding: data, as: UTF8.self)
        default:
            return String(decoding: data, as: UTF8.self)
        }
    }

    static func charsetName(in text: String) -> String? {
        guard let range = text.range(of: #"charset\s*=\s*["']?([A-Za-z0-9_.:-]+)"#, options: [.regularExpression, .caseInsensitive])
        else { return nil }
        let value = text[range].split(separator: "=", maxSplits: 1).last ?? ""
        return value.trimmingCharacters(in: CharacterSet(charactersIn: "\"' ")).lowercased()
    }

    // MARK: Prices on a page

    /// Prices of one bottle of `sizeML` on a result's page, most trusted first: schema.org data
    /// (JSON-LD), then the product meta tags, then microdata, then the visible text near the
    /// wine's name. A structured amount the text shows for a case (or 6 or 12 times a price of
    /// one bottle there) is left out, and the text's price is used. With `vintage`, prices
    /// named for another vintage are left out. A page that does not name the wine (its name,
    /// else the words of its name and producer) gives no price from its text, and a structured
    /// one only for a product named so. A page without a price for `sizeML` gives those of one
    /// bottle of another size it names, with that size (`FoundPrice.sizeML`). Each keeps the
    /// result's site and address. Empty once `shouldStop` is true.
    public static func prices(inPage html: String, of result: WebSearchResult, sizeML: Int, wineName: String? = nil,
                              producer: String? = nil, vintage: Int? = nil,
                              shouldStop: () -> Bool = { Task.isCancelled }) -> [FoundPrice] {
        guard let document = HTMLScanner.scan(html, shouldStop: shouldStop), !shouldStop() else { return [] }
        let page = Page(document: document, wineName: wineName, producer: producer)
        guard !shouldStop() else { return [] }
        let text = page.textReading(sizeML: sizeML, vintage: vintage)
        guard !shouldStop() else { return [] }
        var found = text.items
        let structured = page.structuredPrices(sizeML: sizeML, vintage: vintage, shouldStop: shouldStop)
        if !structured.oneBottle.isEmpty {
            let kept = Page.withoutCasePrices(structured.oneBottle, text: text)
            if !kept.isEmpty || text.items.isEmpty { found = kept }
        }
        if found.isEmpty {
            found = Array((Page.withoutCasePrices(structured.otherSize, text: text) + text.otherSize)
                .prefix(PagePrices.maxTextPrices))
        }
        guard !shouldStop() else { return [] }
        var seen = Set<String>()
        return found.compactMap { item in
            let price = FoundPrice(amount: item.amount, currency: item.currency, domain: result.domain, url: result.url,
                                   excerpt: item.excerpt, sizeML: item.sizeML)
            return seen.insert(price.id).inserted ? price : nil
        }
    }

    /// `prices` with those of the page added, without the ones already found at the same address.
    public static func merging(_ pagePrices: [FoundPrice], into prices: [FoundPrice]) -> [FoundPrice] {
        var ids = Set(prices.map(\.id))
        return prices + pagePrices.filter { ids.insert($0.id).inserted }
    }

    /// A schema.org, meta or microdata amount: digits with an optional decimal point ("13",
    /// "13.90"), rounded to the cent. "13,90", "1e3" or "1.234,50" are not read.
    static func structuredAmount(_ text: String) -> Decimal? {
        let value = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let parts = value.split(separator: ".", omittingEmptySubsequences: false)
        guard (1...2).contains(parts.count), parts.allSatisfy({ !$0.isEmpty && $0.count <= 9 && $0.allSatisfy(\.isASCIIDigit) }),
              var amount = Decimal(string: value, locale: Locale(identifier: "en_US_POSIX")) else { return nil }
        var rounded = Decimal()
        NSDecimalRound(&rounded, &amount, 2, .plain)
        return rounded
    }

    /// "13.90", "13.00": an amount with two decimals, the same on both platforms.
    static func twoDecimals(_ amount: Decimal) -> String {
        let text = "\(amount)"
        let parts = text.split(separator: ".", maxSplits: 1)
        let fraction = parts.count > 1 ? String(parts[1].prefix(2)) : ""
        return "\(parts[0]).\(fraction.padding(toLength: 2, withPad: "0", startingAt: 0))"
    }

    struct Item {
        var amount: Decimal
        var currency: String
        var excerpt: String
        /// For one bottle of this size, another than the one searched.
        var sizeML: Int?
    }

    /// The prices of the text near the wine's name.
    struct TextReading {
        /// Prices of one bottle, at most `maxTextPrices`.
        var items: [Item] = []
        /// Prices of one bottle of another size named on the page, at most `maxTextPrices`.
        var otherSize: [Item] = []
        /// Amounts and currencies of one bottle (all of them), and of several bottles, a case or delivery.
        var oneBottle: [(Decimal, String)] = []
        var notOneBottle: [(Decimal, String)] = []
    }

    /// How far before a price (UTF-16 units) a vintage counts as its own.
    static let vintageBefore = 150
    /// How far after a price, on its line, a vintage counts as its own when none is before it.
    static let vintageAfter = 60

    /// Years 1900 to 2099 written as four digits on their own in `units[start..<end]`.
    static func years(in units: [UInt16], from start: Int, to end: Int) -> Set<Int> {
        var found = Set<Int>()
        var index = max(0, start)
        let end = min(end, units.count)
        func isDigit(_ unit: UInt16) -> Bool { unit >= 0x30 && unit <= 0x39 }
        while index < end {
            guard isDigit(units[index]) else { index += 1; continue }
            var stop = index
            while stop < end, isDigit(units[stop]) { stop += 1 }
            let before = index > 0 ? units[index - 1] : 0x20
            let after = stop < units.count ? units[stop] : 0x20
            if stop - index == 4, !isDigit(before), !isDigit(after) {
                let value = units[index..<stop].reduce(0) { $0 * 10 + Int($1 - 0x30) }
                if (1900...2099).contains(value) { found.insert(value) }
            }
            index = stop
        }
        return found
    }

    static func years(in text: String) -> Set<Int> {
        let units = Array(text.utf16.prefix(300))
        return years(in: units, from: 0, to: units.count)
    }

    static let currencies: Set<String> = ["EUR", "USD", "GBP", "CHF"]
    /// Text prices kept from one page at most: related products and the like are not wanted.
    static let maxTextPrices = 3
    /// How far after the wine's name (in UTF-16 units) a price or a size counts as near it.
    static let nearName = 400
    /// Places of the wine's name looked at, at most.
    static let maxAreas = 20
    /// Without a name to look for, only the start of its text is looked at.
    static let maxTextWithoutName = 50_000
    /// Places of a word of the name checked for the other words near them, at most.
    static let maxWordChecks = 200
    /// Small words that do not tell a wine apart.
    static let commonWords: Set<String> = ["les", "the", "des", "del", "della", "and", "aux", "sur", "une", "von", "vom",
                                           "der", "die", "das", "und", "vin", "vins", "wine", "wines"]

    struct Page {
        let document: HTMLDocument
        let text: String
        /// The text's UTF-16 units, for pieces of it (offsets as in `areas`).
        let units: [UInt16]
        let heading: String?
        let title: String?
        /// Where to look in the text: after the places the wine's name is, the start of it when
        /// there is no name to look for, or none when the page does not name the wine.
        let areas: [NSRange]
        /// The one size named near the wine's name, if only one is.
        let areaSize: (sizeML: Int, words: String)?
        /// The wine's name folded (`Fold`), when it is long enough to look for.
        let needle: [UInt16]?
        /// The words of the name (else of the producer and name) folded, that name the wine
        /// when the whole name is not found.
        let words: [[UInt16]]

        /// True when there is a name to look for and the page's text does not have it.
        var lacksName: Bool { (needle != nil || !words.isEmpty) && areas.isEmpty }

        init(document: HTMLDocument, wineName: String?, producer: String? = nil) {
            self.document = document
            text = document.text
            units = Array(text.utf16)
            heading = document.heading
            let ogTitle = document.meta.first { $0.key == "og:title" }?.value
            title = ogTitle.flatMap(HTML.cleanLine) ?? document.title
            needle = Page.needle(wineName)
            let nameWords = Page.words(of: wineName ?? "")
            words = nameWords.isEmpty ? Page.words(of: [producer, wineName].compactMap { $0 }.joined(separator: " ")) : nameWords
            let all = units, haystack = Fold.units(all), wanted = words
            var found: [NSRange] = []
            if let needle { found = Page.areas(in: all, folded: haystack, needle: needle) }
            if found.isEmpty, !wanted.isEmpty { found = Page.areas(in: all, folded: haystack, words: wanted) }
            if needle == nil, wanted.isEmpty { found = [NSRange(location: 0, length: min(all.count, maxTextWithoutName))] }
            areas = found
            areaSize = Page.size(in: units, areas: areas)
        }

        static func needle(_ name: String?) -> [UInt16]? {
            let cleanName = (name ?? "").trimmingCharacters(in: .whitespacesAndNewlines).precomposedStringWithCanonicalMapping
            let needle = Fold.units(String(cleanName.prefix(100)))
            return needle.count >= 3 ? needle : nil
        }

        /// Words of 3 letters or more, folded, without `commonWords`, longest first, at most 6.
        static func words(of name: String) -> [[UInt16]] {
            let folded = Fold.units(String(name.precomposedStringWithCanonicalMapping.prefix(200)))
            var found: [[UInt16]] = []
            var current: [UInt16] = []
            for unit in folded + [0x20] {
                if PriceExtractor.isWordUnit(unit) { current.append(unit); continue }
                let word = String(decoding: current, as: UTF16.self)
                if current.count >= 3, !PagePrices.commonWords.contains(word), !found.contains(current) { found.append(current) }
                current = []
            }
            return Array(found.enumerated().sorted { ($0.element.count, -$0.offset) > ($1.element.count, -$1.offset) }
                .map(\.element).prefix(6))
        }

        /// The places of the name in the text, as for `areas(in:name:)`.
        static func areas(in text: String, name: String?) -> [NSRange] {
            let units = Array(text.utf16)
            guard let needle = needle(name) else { return [NSRange(location: 0, length: min(units.count, maxTextWithoutName))] }
            return areas(in: units, folded: Fold.units(units), needle: needle)
        }

        /// Whole words at `index` in the folded text.
        static func isWord(_ haystack: [UInt16], at index: Int, length: Int) -> Bool {
            (index == 0 || !PriceExtractor.isWordUnit(haystack[index - 1]))
                && (index + length >= haystack.count || !PriceExtractor.isWordUnit(haystack[index + length]))
        }

        /// The first place of `word` in `haystack[from..<to]`, as a whole word.
        static func find(_ word: [UInt16], in haystack: [UInt16], from: Int, to: Int) -> Int? {
            var index = max(0, from)
            let end = min(to, haystack.count)
            while index + word.count <= end {
                if haystack[index] == word[0], haystack[index..<(index + word.count)].elementsEqual(word),
                   isWord(haystack, at: index, length: word.count) {
                    return index
                }
                index += 1
            }
            return nil
        }

        /// The places of the longest word of the name with every other word near it
        /// (`nearName` before or after), at most `maxWordChecks` places looked at.
        static func areas(in text: [UInt16], folded haystack: [UInt16], words: [[UInt16]]) -> [NSRange] {
            guard let first = words.first else { return [] }
            var areas: [NSRange] = []
            var index = 0
            var checks = 0
            while checks < maxWordChecks, areas.count < maxAreas,
                  let at = find(first, in: haystack, from: index, to: haystack.count) {
                checks += 1
                let near = words.dropFirst().allSatisfy {
                    find($0, in: haystack, from: at - nearName, to: at + first.count + nearName) != nil
                }
                if near {
                    let end = min(text.count, at + nearName)
                    if let last = areas.last, at <= NSMaxRange(last) {
                        areas[areas.count - 1] = NSRange(location: last.location, length: end - last.location)
                    } else {
                        areas.append(NSRange(location: at, length: end - at))
                    }
                }
                index = at + first.count
            }
            return areas
        }

        /// True when a product's name (as structured data or the page's title give it) names the
        /// wine: it has the wine's name, or every word of it (`words`).
        func names(_ name: String?) -> Bool {
            guard let name else { return false }
            let folded = Fold.units(String(name.precomposedStringWithCanonicalMapping.prefix(300)))
            if let needle, folded.count >= needle.count,
               (0...(folded.count - needle.count)).contains(where: { folded[$0..<($0 + needle.count)].elementsEqual(needle) }) {
                return true
            }
            return !words.isEmpty && words.allSatisfy { Page.find($0, in: folded, from: 0, to: folded.count) != nil }
        }

        static func areas(in text: [UInt16], folded haystack: [UInt16], needle: [UInt16]) -> [NSRange] {
            let length = text.count
            var areas: [NSRange] = []
            var index = 0
            // At most `maxAreas` × `nearName` units in all, however often the name appears.
            var covered = 0
            while index + needle.count <= haystack.count, areas.count < maxAreas, covered < maxAreas * nearName {
                if haystack[index] == needle[0], haystack[index..<(index + needle.count)].elementsEqual(needle) {
                    let end = min(length, index + nearName)
                    // Places close together make one area.
                    if let last = areas.last, index <= NSMaxRange(last) {
                        covered += end - NSMaxRange(last)
                        areas[areas.count - 1] = NSRange(location: last.location, length: end - last.location)
                    } else {
                        covered += end - index
                        areas.append(NSRange(location: index, length: end - index))
                    }
                    index += needle.count
                } else {
                    index += 1
                }
            }
            return areas
        }

        static func size(in text: String, areas: [NSRange]) -> (sizeML: Int, words: String)? {
            size(in: Array(text.utf16), areas: areas)
        }

        static func size(in text: [UInt16], areas: [NSRange]) -> (sizeML: Int, words: String)? {
            var sizes: [Int: String] = [:]
            for area in areas {
                let piece = String(decoding: text[area.location..<NSMaxRange(area)], as: UTF16.self)
                for (size, pattern) in PriceExtractor.sizePatterns where sizes[size] == nil {
                    if let words = PriceExtractor.firstMatch(of: pattern, in: piece) { sizes[size] = words }
                }
            }
            return sizes.count == 1 ? sizes.first.map { ($0.key, $0.value) } : nil
        }

        /// The product's name, as the page shows it.
        var label: String? { heading ?? title }

        /// The size of what the page sells: named in `names` (the product, the offer), its
        /// heading, or near the wine's name.
        func size(named names: [String?]) -> (sizeML: Int, words: String)? {
            for name in names + [heading, title] {
                if let name, let found = PriceExtractor.sizeWords(in: String(name.prefix(300))) { return found }
            }
            return areaSize
        }

        func excerpt(name: String?, price: String, size: String?) -> String {
            var parts: [String] = []
            if let name = (name ?? label).flatMap(HTML.cleanLine) { parts.append(String(name.prefix(100))) }
            parts.append(price)
            if let size, !parts.contains(where: { $0.localizedCaseInsensitiveContains(size) }) { parts.append(size) }
            return parts.joined(separator: " · ")
        }

        // MARK: Structured data

        /// The structured prices of one bottle of the size, of the first kind of data that has
        /// some, and of one bottle of another size named, of the first kind that has some.
        func structuredPrices(sizeML: Int, vintage: Int?, shouldStop: () -> Bool) -> (oneBottle: [Item], otherSize: [Item]) {
            var otherSize: [Item] = []
            for step in 0..<3 {
                if shouldStop() { return ([], []) }
                let items = switch step {
                case 0: jsonLDPrices(sizeML: sizeML, vintage: vintage, shouldStop: shouldStop)
                case 1: metaPrices(sizeML: sizeML, vintage: vintage)
                default: microdataPrices(sizeML: sizeML, vintage: vintage)
                }
                let oneBottle = items.filter { $0.sizeML == nil }
                if !oneBottle.isEmpty { return (oneBottle, []) }
                if otherSize.isEmpty { otherSize = items }
            }
            return ([], otherSize)
        }

        func item(amount text: String, currency: String?, names: [String?], label: String?, sizeML: Int,
                  vintage: Int?, vintageNames: [String?]) -> Item? {
            // A page that does not name the wine: only a product named for it. A product whose
            // name is given (JSON-LD) is another wine when the name is not the wine's.
            let given = names.compactMap { $0 }
            if lacksName || (!given.isEmpty && (needle != nil || !words.isEmpty)),
               !(names + [label]).contains(where: self.names) {
                return nil
            }
            guard let currency = currency?.trimmingCharacters(in: .whitespaces).uppercased(),
                  PagePrices.currencies.contains(currency),
                  let amount = PagePrices.structuredAmount(text), PriceExtractor.range.contains(amount) else { return nil }
            // A product named for another vintage.
            if let vintage {
                let years = vintageNames.compactMap { $0 }.reduce(into: Set<Int>()) { $0.formUnion(PagePrices.years(in: $1)) }
                if !years.isEmpty, !years.contains(vintage) { return nil }
            }
            // A case, a lot or a pack is not one bottle.
            for name in names.compactMap({ $0 })
            where PriceExtractor.contains(String(name.prefix(300)), PriceExtractor.notOneBottleBefore) {
                return nil
            }
            let size = size(named: names)
            let excerpt = excerpt(name: label, price: "\(PagePrices.twoDecimals(amount)) \(currency)", size: size?.words)
            guard (size?.sizeML ?? 750) == sizeML else {
                // Another size, kept only when it is named.
                return size.map { Item(amount: amount, currency: currency, excerpt: excerpt, sizeML: $0.sizeML) }
            }
            return Item(amount: amount, currency: currency, excerpt: excerpt)
        }

        func jsonLDPrices(sizeML: Int, vintage: Int?, shouldStop: () -> Bool) -> [Item] {
            var items: [Item] = []
            for script in document.jsonLD {
                if shouldStop() { return [] }
                guard let data = JSONLD.withoutExponents(script).data(using: .utf8),
                      let root = try? JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed]) else { continue }
                for product in JSONLD.products(in: root) {
                    let name = JSONLD.string(product["name"])
                    for offer in JSONLD.offers(of: product) {
                        if let quantity = offer.quantity, quantity > 1 { continue }
                        if let item = item(amount: offer.amount, currency: offer.currency, names: [offer.name, name],
                                           label: name, sizeML: sizeML, vintage: vintage, vintageNames: [offer.name, name]) {
                            items.append(item)
                        }
                    }
                }
            }
            return items
        }

        func metaPrices(sizeML: Int, vintage: Int?) -> [Item] {
            func value(_ key: String) -> String? { document.meta.first { $0.key == key }?.value }
            for prefix in ["product:price:", "og:price:"] {
                if let amount = value(prefix + "amount"),
                   let item = item(amount: amount, currency: value(prefix + "currency"), names: [], label: label, sizeML: sizeML,
                                   vintage: vintage, vintageNames: [label]) {
                    return [item]
                }
            }
            return []
        }

        /// The first itemprop="price" of the page, with the first itemprop="priceCurrency".
        func microdataPrices(sizeML: Int, vintage: Int?) -> [Item] {
            guard let amount = document.itemPrice,
                  let item = item(amount: amount, currency: document.itemCurrency, names: [], label: label, sizeML: sizeML,
                                  vintage: vintage, vintageNames: [label])
            else { return [] }
            return [item]
        }

        // MARK: Visible text

        /// The prices near the wine's name. With `vintage`, a price whose own words (before it,
        /// else after it on its line) name another vintage is left out, and none is kept when
        /// the text names several vintages but not this one.
        func textReading(sizeML: Int, vintage: Int?) -> TextReading {
            let size = size(named: [])
            var reading = TextReading()
            var otherVintagesOnly = false
            if let vintage {
                var seen = Set<Int>()
                for area in areas { seen.formUnion(PagePrices.years(in: units, from: area.location, to: NSMaxRange(area))) }
                otherVintagesOnly = seen.count > 1 && !seen.contains(vintage)
            }
            var prices: [(price: PriceExtractor.TextPrice, area: NSRange)] = []
            var otherSize: [(price: PriceExtractor.TextPrice, area: NSRange)] = []
            for area in areas {
                // A little text around the area, for the words next to a price.
                let start = max(0, area.location - 40)
                let end = min(units.count, NSMaxRange(area) + 30)
                let piece = String(decoding: units[start..<end], as: UTF16.self)
                let all = PriceExtractor.allTextPrices(in: piece, sizeML: sizeML, defaultSize: size?.sizeML)
                for (index, price) in all.enumerated() where NSLocationInRange(start + price.location, area) {
                    switch price.kind {
                    case .otherSize:
                        // Kept when the size is named, next to it or near the wine's name.
                        if price.seenSize != nil { otherSize.append((price, area)) }
                        continue
                    case .notAPrice: continue
                    case .notOneBottle: reading.notOneBottle.append((price.amount, price.currency)); continue
                    case .oneBottle: break
                    }
                    if let vintage {
                        if otherVintagesOnly { continue }
                        let location = start + price.location, priceEnd = location + price.length
                        let previousEnd = index > 0 ? start + all[index - 1].location + all[index - 1].length : 0
                        var years = PagePrices.years(in: units, from: max(area.location, previousEnd,
                                                                         location - PagePrices.vintageBefore), to: location)
                        if years.isEmpty {
                            var stop = min(units.count, priceEnd + PagePrices.vintageAfter)
                            if index + 1 < all.count { stop = min(stop, start + all[index + 1].location) }
                            if let newline = units[priceEnd..<max(priceEnd, stop)].firstIndex(of: 0x0A) { stop = newline }
                            years = PagePrices.years(in: units, from: priceEnd, to: stop)
                        }
                        if !years.isEmpty, !years.contains(vintage) { continue }
                    }
                    reading.oneBottle.append((price.amount, price.currency))
                    prices.append((price, area))
                }
            }
            // The product's name: the page's title when it names the wine (not a guide's or a
            // producer's page title), else the line where the wine's name is.
            let productLabel = label.flatMap(HTML.cleanLine).flatMap { needle == nil && words.isEmpty || names($0) ? $0 : nil }
            func item(_ price: PriceExtractor.TextPrice, area: NSRange, sizeML: Int?) -> Item {
                var parts: [String] = []
                if let name = productLabel ?? line(at: area.location), !price.excerpt.localizedCaseInsensitiveContains(name) {
                    parts.append(String(name.prefix(80)))
                }
                parts.append(price.excerpt)
                let words = price.namedSize == nil ? size?.words : nil
                if let words, !parts.contains(where: { $0.localizedCaseInsensitiveContains(words) }) { parts.append(words) }
                return Item(amount: price.amount, currency: price.currency, excerpt: parts.joined(separator: " · "), sizeML: sizeML)
            }
            reading.items = prices.prefix(PagePrices.maxTextPrices).map { item($0.price, area: $0.area, sizeML: nil) }
            reading.otherSize = otherSize.prefix(PagePrices.maxTextPrices).map {
                item($0.price, area: $0.area, sizeML: $0.price.seenSize)
            }
            return reading
        }

        /// The line of the text at `location` (at most 80 units of it on each side), when the
        /// wine's name was looked for.
        func line(at location: Int) -> String? {
            guard needle != nil || !words.isEmpty, location < units.count else { return nil }
            var start = location
            while start > max(0, location - 80), units[start - 1] != 0x0A { start -= 1 }
            var end = location
            while end < min(units.count, location + 80), units[end] != 0x0A { end += 1 }
            return HTML.cleanLine(String(decoding: units[start..<end], as: UTF16.self))
        }

        /// Without the structured amounts that the text near the wine's name shows for several
        /// bottles, or that are 6 or 12 times (±2 %) a price of one bottle there: a case.
        static func withoutCasePrices(_ items: [Item], text: TextReading) -> [Item] {
            let tolerance = Decimal(string: "0.02")!
            return items.filter { item in
                if text.notOneBottle.contains(where: { $0.0 == item.amount && $0.1 == item.currency }) { return false }
                return !text.oneBottle.contains { amount, currency in
                    currency == item.currency && [6, 12].contains { count in
                        let expected = amount * Decimal(count)
                        let difference = item.amount - expected
                        return (difference < 0 ? -difference : difference) <= expected * tolerance
                    }
                }
            }
        }
    }
}

// MARK: Public hosts

/// Whether a name or an address leads outside this device and the local network. Used for the
/// result pages and for pictures from the web.
public enum PublicHost {
    /// Lowercased, without brackets or a final dot.
    static func bare(_ host: String) -> String {
        var name = host.lowercased()
        if name.hasPrefix("["), name.hasSuffix("]") { name = String(name.dropFirst().dropLast()) }
        while name.hasSuffix(".") { name.removeLast() }
        return name
    }

    /// IPv6, or a name whose last label is a number ("203.0.113.10", "2130706433", "0x7f.1").
    static func isAddress(_ host: String) -> Bool {
        if host.contains(":") { return true }
        let last = host.split(separator: ".").last.map(String.init) ?? host
        return last.allSatisfy { $0.isASCII && $0.isNumber } || last.hasPrefix("0x")
    }

    /// A name that may be public: it has a dot (a name without one is completed with the local
    /// network's domain) and is not localhost, .local or .home.arpa.
    static func mayBePublic(_ name: String) -> Bool {
        name.contains(".") && !WebImageDownload.isPrivateHost(name)
    }

    /// True when every address is public; false for none.
    public static func arePublic(_ addresses: [String]) -> Bool {
        !addresses.isEmpty && addresses.allSatisfy(isPublicAddress)
    }

    /// Resolves `host` (a name, or an address used as is) and checks every address.
    public static func resolvesPublicly(_ host: String, resolver: LocalName.Resolver? = nil) async -> Bool {
        let name = bare(host)
        if isAddress(name) { return isPublicAddress(name) }
        guard mayBePublic(name) else { return false }
        let addresses = (try? await (resolver ?? LocalName.system)(name)) ?? []
        return arePublic(addresses)
    }

    /// A numeric IPv4 or IPv6 address (a zone such as "%en0" is ignored) that is public, read
    /// by its bytes (`isPublic(bytes:)`). False for anything that is not an address.
    public static func isPublicAddress(_ text: String) -> Bool {
        let address = String(bare(text).split(separator: "%", maxSplits: 1).first ?? "")
        var v4 = in_addr()
        if inet_pton(AF_INET, address, &v4) == 1 {
            return isPublic(bytes: withUnsafeBytes(of: &v4) { Array($0) })
        }
        var v6 = in6_addr()
        if inet_pton(AF_INET6, address, &v6) == 1 {
            return isPublic(bytes: withUnsafeBytes(of: &v6) { Array($0) })
        }
        return false
    }

    /// 4 or 16 bytes. Not public: this network, private, shared (carrier-grade NAT),
    /// loopback, link-local, benchmark, multicast and reserved IPv4; unspecified, loopback,
    /// unique local, link-local, site-local and multicast IPv6; Teredo and local NAT64; and
    /// the IPv4 address inside a mapped, compatible, NAT64 or 6to4 address is checked.
    public static func isPublic(bytes: [UInt8]) -> Bool {
        if bytes.count == 4 { return isPublicIPv4(bytes[0], bytes[1], bytes[2]) }
        guard bytes.count == 16 else { return false }
        let b = bytes
        func v4(_ at: Int) -> Bool { isPublicIPv4(b[at], b[at + 1], b[at + 2]) }
        let zeros = { (range: Range<Int>) in b[range].allSatisfy { $0 == 0 } }
        if zeros(0..<10), b[10] == 0xFF, b[11] == 0xFF { return v4(12) } // ::ffff:a.b.c.d
        if zeros(0..<12) { return v4(12) } // ::, ::1, ::a.b.c.d
        if b[0] == 0x00, b[1] == 0x64, b[2] == 0xFF, b[3] == 0x9B {
            if zeros(4..<12) { return v4(12) } // 64:ff9b::/96
            if b[4] == 0x00, b[5] == 0x01 { return false } // 64:ff9b:1::/48
        }
        if b[0] == 0x20, b[1] == 0x02 { return v4(2) } // 6to4
        if b[0] == 0x20, b[1] == 0x01, b[2] == 0x00, b[3] == 0x00 { return false } // Teredo
        if b[0] & 0xFE == 0xFC { return false } // unique local
        if b[0] == 0xFE, b[1] & 0x80 != 0 { return false } // link-local, site-local
        if b[0] == 0xFF { return false } // multicast
        return true
    }

    static func isPublicIPv4(_ a: UInt8, _ b: UInt8, _ c: UInt8) -> Bool {
        !(a == 0 || a == 10 || a == 127 || a >= 224
            || (a == 100 && (64...127).contains(b))
            || (a == 169 && b == 254)
            || (a == 172 && (16...31).contains(b))
            || (a == 192 && b == 168)
            || (a == 192 && b == 0 && c == 0)
            || (a == 198 && (b == 18 || b == 19)))
    }
}

// MARK: HTML

/// What a page holds for prices.
struct HTMLDocument {
    /// The text a reader sees, a line per block (paragraph, cell, item), entities decoded.
    var text = ""
    var heading: String?
    var title: String?
    var jsonLD: [String] = []
    /// (property or name, content) of each meta tag, keys lowercased.
    var meta: [(key: String, value: String)] = []
    /// The first itemprop="price" and itemprop="priceCurrency": content, or the text after the tag.
    var itemPrice: String?
    var itemCurrency: String?
}

/// Reads a page in one pass, without regular expressions: the time grows with the page's size
/// only. A tag, comment or script that does not end stops the reading there.
enum HTMLScanner {
    static let blocks: Set<String> = ["p", "div", "li", "ul", "ol", "tr", "td", "th", "table", "h1", "h2", "h3", "h4",
                                      "h5", "h6", "section", "article", "header", "footer", "nav", "main", "aside",
                                      "form", "dt", "dd", "dl", "blockquote", "figure", "figcaption", "option", "br", "hr"]
    /// Elements whose content is not text to read (scripts, styles, and so on).
    static let raw: Set<String> = ["script", "style", "noscript", "template", "svg", "textarea", "select", "title", "iframe"]

    static let lt: UInt16 = 0x3C, gt: UInt16 = 0x3E, slash: UInt16 = 0x2F, bang: UInt16 = 0x21, question: UInt16 = 0x3F
    static let dash: UInt16 = 0x2D, equals: UInt16 = 0x3D, quote: UInt16 = 0x22, apostrophe: UInt16 = 0x27

    static func scan(_ html: String, shouldStop: () -> Bool) -> HTMLDocument? {
        let units = Array(html.utf16.prefix(PagePrices.maxParsedUnits))
        let n = units.count
        var document = HTMLDocument()
        var out: [UInt16] = []
        out.reserveCapacity(min(n, 64 * 1024))
        var headingStart: Int?
        var headingDone = false
        var index = 0
        var tags = 0
        /// Raw elements found not to end: later ones cannot end either, so they are not looked for again.
        var unclosed = Set<String>()

        func find(_ unit: UInt16, from start: Int) -> Int? {
            var k = start
            while k < n { if units[k] == unit { return k }; k += 1 }
            return nil
        }
        func string(_ range: Range<Int>) -> String { String(decoding: units[range], as: UTF16.self) }

        scanning: while index < n {
            guard let open = find(lt, from: index) else {
                out.append(contentsOf: units[index..<n])
                break
            }
            out.append(contentsOf: units[index..<open])
            tags += 1
            if tags % 512 == 0, shouldStop() { return nil }
            let next = open + 1 < n ? units[open + 1] : 0
            // A comment ends at "-->"; a declaration or "<?…" at ">".
            if next == bang || next == question {
                if next == bang, open + 3 < n, units[open + 2] == dash, units[open + 3] == dash {
                    var k = open + 4
                    while k + 2 < n, !(units[k] == dash && units[k + 1] == dash && units[k + 2] == gt) { k += 1 }
                    guard k + 2 < n else { break scanning }
                    index = k + 3
                } else {
                    guard let end = find(gt, from: open + 1) else { break scanning }
                    index = end + 1
                }
                continue
            }
            let closing = next == slash
            let nameStart = closing ? open + 2 : open + 1
            guard nameStart < n, isLetter(units[nameStart]) else {
                // "a < b": text, up to the next "<" that starts a tag.
                var k = open + 1
                while let next = find(lt, from: k), next + 1 < n, !isLetter(units[next + 1]),
                      units[next + 1] != slash, units[next + 1] != bang, units[next + 1] != question {
                    k = next + 1
                }
                let stop = find(lt, from: k) ?? n
                out.append(contentsOf: units[open..<stop])
                index = stop
                continue
            }
            guard let end = find(gt, from: nameStart) else { break scanning }
            var nameEnd = nameStart
            while nameEnd < end, isNameUnit(units[nameEnd]) { nameEnd += 1 }
            let name = string(nameStart..<nameEnd).lowercased()
            index = end + 1

            if closing {
                if name == "h1", let start = headingStart, !headingDone {
                    document.heading = HTML.cleanLine(String(decoding: out[start..<min(out.count, start + 1000)], as: UTF16.self))
                    headingDone = true
                    headingStart = nil
                }
                out.append(blocks.contains(name) ? 0x0A : 0x20)
                continue
            }

            let attributes = parseAttributes(units, from: nameEnd, to: end)
            if raw.contains(name) {
                // The content runs to "</name".
                let selfClosing = end > nameStart && units[end - 1] == slash
                if selfClosing { continue }
                // A script or style that does not end runs to the end of the page; another
                // element that does not end is only a tag, and the rest is read.
                guard !unclosed.contains(name), let close = findClose(units, name: name, from: end + 1) else {
                    if name == "script" || name == "style" { break scanning }
                    unclosed.insert(name)
                    out.append(0x20)
                    continue
                }
                let content = string((end + 1)..<close)
                let type = attributes["type"]?.split(separator: ";").first?.trimmingCharacters(in: .whitespaces).lowercased()
                if name == "script", type == "application/ld+json" {
                    document.jsonLD.append(content)
                } else if name == "title", document.title == nil {
                    document.title = HTML.cleanLine(content)
                }
                guard let closeEnd = find(gt, from: close) else { break scanning }
                index = closeEnd + 1
                out.append(0x20)
                continue
            }
            if name == "meta", let key = attributes["property"] ?? attributes["name"], let content = attributes["content"] {
                document.meta.append((key.lowercased(), content))
            }
            if let itemprop = attributes["itemprop"]?.lowercased() {
                let props = itemprop.split(separator: " ")
                func value() -> String? {
                    if let content = attributes["content"] { return content.trimmingCharacters(in: .whitespacesAndNewlines) }
                    let stop = find(lt, from: end + 1) ?? n
                    return HTML.number(in: string((end + 1)..<min(stop, end + 1 + 200)))
                }
                if document.itemPrice == nil, props.contains("price") { document.itemPrice = value() }
                if document.itemCurrency == nil, props.contains("pricecurrency") { document.itemCurrency = value() }
            }
            if name == "h1", headingStart == nil, !headingDone { headingStart = out.count }
            out.append(blocks.contains(name) ? 0x0A : 0x20)
        }
        if let start = headingStart, !headingDone {
            document.heading = HTML.cleanLine(String(decoding: out[start..<min(out.count, start + 1000)], as: UTF16.self))
        }
        if shouldStop() { return nil }
        document.text = HTML.lines(units: out)
        return document
    }

    static func isLetter(_ unit: UInt16) -> Bool { (0x41...0x5A).contains(unit) || (0x61...0x7A).contains(unit) }

    static func isNameUnit(_ unit: UInt16) -> Bool {
        isLetter(unit) || (0x30...0x39).contains(unit) || unit == dash || unit == 0x3A || unit == 0x5F
    }

    static func isSpace(_ unit: UInt16) -> Bool { unit == 0x20 || unit == 0x09 || unit == 0x0A || unit == 0x0D || unit == 0x0C }

    /// The start of the first "</name" (any case, followed by a non-name character) after `start`.
    static func findClose(_ units: [UInt16], name: String, from start: Int) -> Int? {
        let wanted = Array(name.utf16)
        let n = units.count
        var k = start
        // "</" and the name must fit; the end of the text counts as a non-name character.
        while k + 2 + wanted.count <= n {
            if units[k] == lt, units[k + 1] == slash {
                var match = true
                for (offset, unit) in wanted.enumerated() {
                    var have = units[k + 2 + offset]
                    if (0x41...0x5A).contains(have) { have += 0x20 }
                    if have != unit { match = false; break }
                }
                let after = k + 2 + wanted.count
                if match, after == n || !isNameUnit(units[after]) { return k }
            }
            k += 1
        }
        return nil
    }

    /// The attributes of a tag (between its name and its ">"), names lowercased, values with
    /// entities decoded. The first of a name is kept.
    static func parseAttributes(_ units: [UInt16], from start: Int, to end: Int) -> [String: String] {
        var values: [String: String] = [:]
        var k = start
        while k < end {
            while k < end, isSpace(units[k]) || units[k] == slash { k += 1 }
            let nameStart = k
            while k < end, !isSpace(units[k]), units[k] != equals, units[k] != slash, units[k] != gt { k += 1 }
            guard k > nameStart else { k += 1; continue }
            let name = String(decoding: units[nameStart..<k], as: UTF16.self).lowercased()
            while k < end, isSpace(units[k]) { k += 1 }
            var value = ""
            if k < end, units[k] == equals {
                k += 1
                while k < end, isSpace(units[k]) { k += 1 }
                if k < end, units[k] == quote || units[k] == apostrophe {
                    let mark = units[k]
                    let valueStart = k + 1
                    k = valueStart
                    while k < end, units[k] != mark { k += 1 }
                    value = String(decoding: units[valueStart..<k], as: UTF16.self)
                    k += 1
                } else {
                    let valueStart = k
                    while k < end, !isSpace(units[k]) { k += 1 }
                    value = String(decoding: units[valueStart..<k], as: UTF16.self)
                }
            }
            if values[name] == nil { values[name] = WebSearchText.decodeEntities(value) }
        }
        return values
    }
}

enum HTML {
    /// Spaces of the text, no-break and narrow ones too, collapsed to one; line breaks kept.
    static func isSpace(_ unit: UInt16) -> Bool {
        switch unit {
        case 0x20, 0x09, 0x0D, 0x0C, 0x0B, 0xA0, 0x2007, 0x2009, 0x200A, 0x202F, 0x3000: true
        default: false
        }
    }

    /// Entities decoded, spaces collapsed, one line per block, no empty line.
    static func lines(_ raw: String) -> String { lines(units: Array(raw.utf16)) }

    /// The same on UTF-16 units, in one pass each: a whole page's text.
    static func lines(units raw: [UInt16]) -> String {
        let decoded = decodeEntities(raw)
        var out: [UInt16] = []
        out.reserveCapacity(decoded.count)
        var pendingSpace = false
        var lineHasText = false
        for unit in decoded {
            if unit == 0x0A {
                if lineHasText { out.append(0x0A) }
                lineHasText = false
                pendingSpace = false
            } else if isSpace(unit) {
                pendingSpace = lineHasText
            } else {
                if pendingSpace { out.append(0x20) }
                pendingSpace = false
                lineHasText = true
                out.append(unit)
            }
        }
        if out.last == 0x0A { out.removeLast() }
        let text = String(decoding: out, as: UTF16.self)
        // Only combining marks change in NFC: most pages have none.
        return out.contains(where: isCombining) ? text.precomposedStringWithCanonicalMapping : text
    }

    static func isCombining(_ unit: UInt16) -> Bool {
        (0x0300...0x036F).contains(unit) || (0x1AB0...0x1AFF).contains(unit) || (0x1DC0...0x1DFF).contains(unit)
            || (0x20D0...0x20FF).contains(unit) || (0xFE20...0xFE2F).contains(unit) || (0x3099...0x309A).contains(unit)
            || (0x1100...0x11FF).contains(unit)
    }

    /// `WebSearchText.decodeEntities` on UTF-16 units: "&amp;", "&#8364;", "&#x20AC;" (a name or
    /// number of up to 10 characters before ";").
    static func decodeEntities(_ units: [UInt16]) -> [UInt16] {
        guard units.contains(0x26) else { return units }
        var out: [UInt16] = []
        out.reserveCapacity(units.count)
        var k = 0
        while k < units.count {
            let unit = units[k]
            if unit == 0x26 {
                var end = k + 1
                while end < min(units.count, k + 12), units[end] != 0x3B { end += 1 }
                if end < units.count, units[end] == 0x3B, end > k + 1 {
                    let entity = String(decoding: units[(k + 1)..<end], as: UTF16.self)
                    if let value = WebSearchText.named[entity.lowercased()] ?? WebSearchText.numeric(entity) {
                        out.append(contentsOf: value.utf16)
                        k = end + 1
                        continue
                    }
                }
            }
            out.append(unit)
            k += 1
        }
        return out
    }

    /// One line of plain text, or nil if empty.
    static func cleanLine(_ text: String) -> String? {
        let line = lines(WebSearchText.plain(text).replacingOccurrences(of: "\n", with: " "))
        return line.isEmpty ? nil : line
    }

    /// "Fr. 13.90" or "13,90 €": the number only (the first one).
    static func number(in html: String) -> String? {
        let text = WebSearchText.plain(html)
        let units = Array(text.utf16)
        guard let start = units.firstIndex(where: { (0x30...0x39).contains($0) }) else { return text.isEmpty ? nil : text }
        var end = start
        let allowed: Set<UInt16> = [0x20, 0x2E, 0x2C, 0x27, 0x2019]
        while end < units.count, (0x30...0x39).contains(units[end]) || allowed.contains(units[end]) { end += 1 }
        return String(decoding: units[start..<end], as: UTF16.self).trimmingCharacters(in: .whitespaces)
    }

    /// The visible text of a page (for tests and other readers).
    static func visibleText(_ html: String) -> String {
        HTMLScanner.scan(html, shouldStop: { false })?.text ?? ""
    }
}

// MARK: JSON-LD

enum JSONLD {
    /// The JSON with each number written with an exponent ("1e3") replaced by null: such a
    /// number is never read as a price, on either platform. One pass; strings are kept as they are.
    static func withoutExponents(_ json: String) -> String {
        let units = Array(json.utf16)
        var out: [UInt16] = []
        out.reserveCapacity(units.count)
        var k = 0
        while k < units.count {
            let unit = units[k]
            if unit == 0x22 { // a string, with its escapes
                let start = k
                k += 1
                while k < units.count, units[k] != 0x22 { k += units[k] == 0x5C ? 2 : 1 }
                k = min(k + 1, units.count)
                out.append(contentsOf: units[start..<k])
            } else if unit == 0x2D || (0x30...0x39).contains(unit) { // a number
                let start = k
                var exponent = false
                while k < units.count, (0x30...0x39).contains(units[k]) || [0x2D, 0x2B, 0x2E, 0x65, 0x45].contains(units[k]) {
                    if units[k] == 0x65 || units[k] == 0x45 { exponent = true }
                    k += 1
                }
                if exponent { out.append(contentsOf: Array("null".utf16)) } else { out.append(contentsOf: units[start..<k]) }
            } else {
                out.append(unit)
                k += 1
            }
        }
        return String(decoding: out, as: UTF16.self)
    }

    struct Offer {
        var amount: String
        var currency: String?
        var name: String?
        var quantity: Double?
    }

    static let productTypes: Set<String> = ["product", "productgroup", "individualproduct", "productmodel", "wine"]

    /// Products at the top, in arrays, in @graph or as the page's main entity, and their variants.
    static func products(in node: Any, depth: Int = 0) -> [[String: Any]] {
        guard depth < 6 else { return [] }
        if let list = node as? [Any] { return list.prefix(200).flatMap { products(in: $0, depth: depth + 1) } }
        guard let object = node as? [String: Any] else { return [] }
        var found: [[String: Any]] = []
        if types(of: object).contains(where: productTypes.contains) || object["offers"] != nil {
            found.append(object)
        }
        for key in ["@graph", "mainEntity", "hasVariant"] {
            if let child = object[key] { found += products(in: child, depth: depth + 1) }
        }
        return found
    }

    static func types(of object: [String: Any]) -> [String] {
        let value = object["@type"]
        let names = (value as? [Any])?.compactMap { $0 as? String } ?? (value as? String).map { [$0] } ?? []
        return names.map { $0.split(separator: "/").last.map(String.init)?.lowercased() ?? "" }
    }

    static func list(_ value: Any?) -> [[String: Any]] {
        (value as? [Any])?.compactMap { $0 as? [String: Any] } ?? (value as? [String: Any]).map { [$0] } ?? []
    }

    /// The offers of a product: price, currency, name and quantity, in their price
    /// specifications or in an aggregate offer's own offers when needed. An aggregate offer's
    /// lowPrice counts only for a single price (lowPrice = highPrice, or one offer).
    static func offers(of product: [String: Any]) -> [Offer] {
        var found: [Offer] = []
        func read(_ object: [String: Any], depth: Int) {
            var amount = string(object["price"])
            var currency = string(object["priceCurrency"])
            if amount == nil, let low = string(object["lowPrice"]) {
                let lowAmount = PagePrices.structuredAmount(low)
                let highAmount = string(object["highPrice"]).flatMap(PagePrices.structuredAmount)
                let single = lowAmount != nil && (highAmount == lowAmount || string(object["offerCount"]) == "1")
                if single { amount = low }
            }
            let specification = list(object["priceSpecification"]).first { string($0["price"]) != nil }
            if amount == nil, let specification { amount = string(specification["price"]) }
            if currency == nil, let specification { currency = string(specification["priceCurrency"]) }
            if let amount {
                found.append(Offer(amount: amount, currency: currency, name: string(object["name"]),
                                   quantity: quantity(object["eligibleQuantity"])))
            } else if depth < 2 {
                list(object["offers"]).prefix(50).forEach { read($0, depth: depth + 1) }
            }
        }
        list(product["offers"]).prefix(50).forEach { read($0, depth: 0) }
        return found
    }

    /// Text or a number as text ("13.00", 13.5 → "13.5"; a number written with an exponent is
    /// not read as a price later).
    static func string(_ value: Any?) -> String? {
        switch value {
        case let text as String:
            let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty ? nil : trimmed
        case let number as NSNumber:
            // true and false read as 1 and 0, below any price.
            return number.stringValue
        case let list as [Any]:
            return list.lazy.compactMap { string($0) }.first
        default:
            return nil
        }
    }

    static func quantity(_ value: Any?) -> Double? {
        if let object = value as? [String: Any] { return quantity(object["value"]) }
        if let number = value as? NSNumber { return number.doubleValue }
        if let text = value as? String { return Double(text) }
        return nil
    }
}

/// Lowercase without accents, one UTF-16 unit for each, so offsets match the text's.
enum Fold {
    static func units(_ text: String) -> [UInt16] { units(Array(text.utf16)) }

    static func units(_ text: [UInt16]) -> [UInt16] {
        var cache: [UInt16: UInt16] = [:]
        return text.map { unit in
            if unit < 0x80 { return (0x41...0x5A).contains(unit) ? unit + 0x20 : unit }
            if unit == 0x2019 || unit == 0x2018 { return 0x27 }
            if let known = cache[unit] { return known }
            var folded = unit
            if let scalar = Unicode.Scalar(unit) {
                let text = String(Character(scalar)).folding(options: [.caseInsensitive, .diacriticInsensitive], locale: nil)
                if text.utf16.count == 1 { folded = text.utf16.first! }
            }
            cache[unit] = folded
            return folded
        }
    }
}
