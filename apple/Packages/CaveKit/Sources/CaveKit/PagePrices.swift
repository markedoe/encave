import Foundation

// Reads the price of a bottle on the web pages a price search found. After the search, the
// app opens the first result pages from the device (no cookies, cache, credentials or
// Referer) and reads them here, on the device: the sites see the device's address and the
// page asked for, nothing else. The Android app does the same (PagePrices.kt); both are
// checked with fixtures/prices.

public enum PagePrices {
    /// The first results opened, in their order.
    public static let maxPages = 5
    /// Pages read at the same time.
    public static let parallelPages = 3
    /// Reading stops after this many bytes; the price is near the top of a product page.
    public static let maxBytes = 1_500_000
    public static let timeout: TimeInterval = 8
    public static let maxRedirects = 3

    /// The results whose pages are opened: the first `maxPages`, if their address may be.
    public static func pages(of results: [WebSearchResult]) -> [WebSearchResult] {
        results.prefix(maxPages).filter { isAllowed($0.url) }
    }

    /// https, no user or password, and a public name: never an IP address, this device or the
    /// local network (a router, a NAS), whatever the search results say. Redirects are checked too.
    public static func isAllowed(_ url: URL) -> Bool {
        guard let checked = WebImageDownload.httpsURL(url.absoluteString), let host = checked.host,
              checked.user == nil, checked.password == nil else { return false }
        var name = host.lowercased()
        while name.hasSuffix(".") { name.removeLast() }
        // A name without a dot is completed with the local network's domain.
        guard name.contains("."), !isAddress(name) else { return false }
        return !WebImageDownload.isPrivateHost(name)
    }

    /// IPv6, or a name whose last label is a number ("203.0.113.10", "2130706433", "0x7f.1").
    static func isAddress(_ host: String) -> Bool {
        if host.contains(":") || host.hasPrefix("[") { return true }
        let last = host.split(separator: ".").last.map(String.init) ?? host
        return last.allSatisfy { $0.isASCII && $0.isNumber } || last.hasPrefix("0x")
    }

    /// True when the addresses a name resolves to are all public: the name does not lead to
    /// this device or the local network.
    public static func arePublic(_ addresses: [String]) -> Bool {
        !addresses.isEmpty && addresses.allSatisfy { address in
            let bare = String(address.split(separator: "%", maxSplits: 1).first ?? "")
            return !bare.isEmpty && !WebImageDownload.isPrivateHost(bare)
        }
    }

    /// Resolves the name and checks its addresses (`arePublic`) before the page is asked for.
    public static func resolvesPublicly(_ url: URL, resolver: LocalName.Resolver? = nil) async -> Bool {
        guard isAllowed(url), let host = url.host else { return false }
        let addresses = (try? await (resolver ?? LocalName.system)(host)) ?? []
        return arePublic(addresses)
    }

    /// "text/html; charset=utf-8" or XHTML; anything else is not read.
    public static func isHTML(contentType: String?) -> Bool {
        guard let type = contentType?.split(separator: ";").first?.trimmingCharacters(in: .whitespaces).lowercased() else {
            return false
        }
        return type == "text/html" || type == "application/xhtml+xml"
    }

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
    /// wine's name. Each keeps the result's site and address.
    public static func prices(inPage html: String, of result: WebSearchResult, sizeML: Int,
                              wineName: String? = nil) -> [FoundPrice] {
        let page = Page(html: html, wineName: wineName)
        let found = page.structuredPrices(sizeML: sizeML) ?? page.textPrices(sizeML: sizeML)
        var seen = Set<String>()
        return found.compactMap { item in
            let price = FoundPrice(amount: item.amount, currency: item.currency, domain: result.domain, url: result.url,
                                   excerpt: item.excerpt)
            return seen.insert(price.id).inserted ? price : nil
        }
    }

    /// `prices` with those of the page added, without the ones already found at the same address.
    public static func merging(_ pagePrices: [FoundPrice], into prices: [FoundPrice]) -> [FoundPrice] {
        var ids = Set(prices.map(\.id))
        return prices + pagePrices.filter { ids.insert($0.id).inserted }
    }

    struct Item {
        var amount: Decimal
        var currency: String
        var excerpt: String
    }

    static let currencies: Set<String> = ["EUR", "USD", "GBP", "CHF"]
    /// Text prices kept from one page at most: related products and the like are not wanted.
    static let maxTextPrices = 3
    /// How far after the wine's name (in UTF-16 units) a price or a size counts as near it.
    static let nearName = 400

    struct Page {
        let html: String
        let text: String
        let heading: String?
        let title: String?
        /// Where to look in the text: after each place the wine's name is, or everywhere.
        let areas: [NSRange]

        init(html: String, wineName: String?) {
            self.html = html
            text = HTML.visibleText(html)
            heading = HTML.firstElementText(html, tag: "h1")
            let ogTitle = HTML.metaTags(html).first { $0.key == "og:title" }?.value
            title = ogTitle.flatMap(HTML.cleanLine) ?? HTML.firstElementText(html, tag: "title")
            areas = Page.areas(in: text, name: wineName)
        }

        static func areas(in text: String, name: String?) -> [NSRange] {
            let length = (text as NSString).length
            let whole = [NSRange(location: 0, length: length)]
            let needle = Fold.units((name ?? "").trimmingCharacters(in: .whitespacesAndNewlines).precomposedStringWithCanonicalMapping)
            guard needle.count >= 3 else { return whole }
            let haystack = Fold.units(text)
            var areas: [NSRange] = []
            var index = 0
            while index + needle.count <= haystack.count {
                if haystack[index] == needle[0], Array(haystack[index..<(index + needle.count)]) == needle {
                    areas.append(NSRange(location: index, length: min(nearName, length - index)))
                    index += needle.count
                } else {
                    index += 1
                }
            }
            return areas.isEmpty ? whole : areas
        }

        /// The product's name, as the page shows it.
        var label: String? { heading ?? title }

        /// The one size named near the wine's name, if only one is.
        var areaSize: (sizeML: Int, words: String)? {
            let nsText = text as NSString
            var sizes: [Int: String] = [:]
            for area in areas {
                let piece = nsText.substring(with: area)
                for (size, pattern) in PriceExtractor.sizePatterns {
                    if let range = piece.range(of: pattern, options: [.regularExpression, .caseInsensitive]),
                       sizes[size] == nil {
                        sizes[size] = String(piece[range])
                    }
                }
            }
            return sizes.count == 1 ? sizes.first.map { ($0.key, $0.value) } : nil
        }

        /// The size of what the page sells: named in `names` (the product, the offer), its
        /// heading, or near the wine's name.
        func size(named names: [String?]) -> (sizeML: Int, words: String)? {
            for name in names + [heading, title] {
                if let name, let found = PriceExtractor.sizeWords(in: name) { return found }
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

        /// Nil when the page has no structured price for one bottle of the size.
        func structuredPrices(sizeML: Int) -> [Item]? {
            for items in [jsonLDPrices(sizeML: sizeML), metaPrices(sizeML: sizeML), microdataPrices(sizeML: sizeML)]
            where !items.isEmpty {
                return items
            }
            return nil
        }

        func item(amount text: String, currency: String?, names: [String?], label: String?, sizeML: Int) -> Item? {
            guard let currency = currency?.trimmingCharacters(in: .whitespaces).uppercased(),
                  PagePrices.currencies.contains(currency),
                  let amount = PriceExtractor.decimal(text), PriceExtractor.range.contains(amount) else { return nil }
            // A case, a lot or a pack is not one bottle.
            for name in names.compactMap({ $0 }) where PriceExtractor.contains(name, PriceExtractor.notOneBottleBefore) {
                return nil
            }
            let size = size(named: names)
            guard (size?.sizeML ?? 750) == sizeML else { return nil }
            return Item(amount: amount, currency: currency,
                        excerpt: excerpt(name: label, price: "\(text) \(currency)", size: size?.words))
        }

        func jsonLDPrices(sizeML: Int) -> [Item] {
            var items: [Item] = []
            for script in HTML.jsonLDScripts(html) {
                guard let data = script.data(using: .utf8),
                      let root = try? JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed]) else { continue }
                for product in JSONLD.products(in: root) {
                    let name = JSONLD.string(product["name"])
                    for offer in JSONLD.offers(of: product) {
                        guard let amount = offer.amount else { continue }
                        if let quantity = offer.quantity, quantity > 1 { continue }
                        if let item = item(amount: amount, currency: offer.currency, names: [offer.name, name],
                                           label: name, sizeML: sizeML) {
                            items.append(item)
                        }
                    }
                }
            }
            return items
        }

        func metaPrices(sizeML: Int) -> [Item] {
            let tags = HTML.metaTags(html)
            func value(_ key: String) -> String? { tags.first { $0.key == key }?.value }
            for prefix in ["product:price:", "og:price:"] {
                if let amount = value(prefix + "amount"),
                   let item = item(amount: amount, currency: value(prefix + "currency"), names: [], label: nil, sizeML: sizeML) {
                    return [item]
                }
            }
            return []
        }

        /// The first itemprop="price" of the page, with the first itemprop="priceCurrency".
        func microdataPrices(sizeML: Int) -> [Item] {
            guard let amount = HTML.itemprop(html, "price"),
                  let item = item(amount: amount, currency: HTML.itemprop(html, "priceCurrency"), names: [],
                                  label: nil, sizeML: sizeML) else { return [] }
            return [item]
        }

        // MARK: Visible text

        func textPrices(sizeML: Int) -> [Item] {
            let size = size(named: [])
            let prices = PriceExtractor.textPrices(in: text, sizeML: sizeML, defaultSize: size?.sizeML ?? 750)
            let near = prices.filter { price in areas.contains { NSLocationInRange(price.location, $0) } }
            return near.prefix(PagePrices.maxTextPrices).map { price in
                var parts: [String] = []
                if let label = label.flatMap(HTML.cleanLine), !price.excerpt.localizedCaseInsensitiveContains(label) {
                    parts.append(String(label.prefix(100)))
                }
                parts.append(price.excerpt)
                if price.namedSize == nil, let words = size?.words,
                   !parts.contains(where: { $0.localizedCaseInsensitiveContains(words) }) {
                    parts.append(words)
                }
                return Item(amount: price.amount, currency: price.currency, excerpt: parts.joined(separator: " · "))
            }
        }
    }
}

// MARK: HTML

/// Just enough HTML reading for prices: no layout, no scripts run.
enum HTML {
    static func regex(_ pattern: String) -> NSRegularExpression {
        // The patterns are constants, checked by the tests.
        try! NSRegularExpression(pattern: pattern, options: [.caseInsensitive, .dotMatchesLineSeparators])
    }

    static let hidden = regex(#"<!--.*?-->|<(script|style|noscript|template|svg|head|select)\b[^>]*>.*?</\1\s*>"#)
    static let block = regex(#"<(?:/?(?:p|div|li|ul|ol|tr|td|th|table|h[1-6]|section|article|header|footer|nav|main|aside|form|dt|dd|dl|blockquote|figure|figcaption|option)\b[^>]*|br\s*/?)>"#)
    static let tag = regex(#"<[^>]*>"#)
    /// Line breaks in the source are spaces; only blocks start a line.
    static let spaces = regex(#"[ \t\n\r\f]+"#)
    static let jsonLD = regex(#"<script\b[^>]*\btype\s*=\s*["']?application/ld\+json["']?[^>]*>(.*?)</script\s*>"#)
    static let meta = regex(#"<meta\b[^>]*>"#)
    static let attribute = regex(#"([a-z_:.-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))"#)

    /// The text a reader sees, a line per block (paragraph, cell, item), entities decoded.
    static func visibleText(_ html: String) -> String {
        var text = replace(hidden, in: html, with: " ")
        text = replace(spaces, in: text, with: " ")
        text = replace(block, in: text, with: "\n")
        text = replace(tag, in: text, with: " ")
        text = WebSearchText.decodeEntities(text).precomposedStringWithCanonicalMapping
        return text.split(separator: "\n").compactMap { line in
            let words = line.split(whereSeparator: { $0.isWhitespace }).joined(separator: " ")
            return words.isEmpty ? nil : words
        }.joined(separator: "\n")
    }

    static func replace(_ regex: NSRegularExpression, in text: String, with template: String) -> String {
        regex.stringByReplacingMatches(in: text, range: NSRange(location: 0, length: (text as NSString).length),
                                       withTemplate: template)
    }

    /// One line of plain text, or nil if empty.
    static func cleanLine(_ text: String) -> String? {
        let line = WebSearchText.plain(text).precomposedStringWithCanonicalMapping
        return line.isEmpty ? nil : line
    }

    static func firstElementText(_ html: String, tag name: String) -> String? {
        let pattern = regex("<\(name)\\b[^>]*>(.*?)</\(name)\\s*>")
        guard let match = pattern.firstMatch(in: html, range: NSRange(location: 0, length: (html as NSString).length)) else {
            return nil
        }
        return cleanLine((html as NSString).substring(with: match.range(at: 1)))
    }

    static func jsonLDScripts(_ html: String) -> [String] {
        let nsHTML = html as NSString
        return jsonLD.matches(in: html, range: NSRange(location: 0, length: nsHTML.length)).map {
            nsHTML.substring(with: $0.range(at: 1))
        }
    }

    /// The attributes of a tag, names lowercased, values with entities decoded.
    static func attributes(_ tag: String) -> [String: String] {
        let nsTag = tag as NSString
        var values: [String: String] = [:]
        for match in attribute.matches(in: tag, range: NSRange(location: 0, length: nsTag.length)) {
            let name = nsTag.substring(with: match.range(at: 1)).lowercased()
            let value = (2...4).lazy.map { match.range(at: $0) }.first { $0.location != NSNotFound }
                .map { nsTag.substring(with: $0) } ?? ""
            if values[name] == nil { values[name] = WebSearchText.decodeEntities(value) }
        }
        return values
    }

    /// (property or name, content) of each meta tag, keys lowercased.
    static func metaTags(_ html: String) -> [(key: String, value: String)] {
        let nsHTML = html as NSString
        return meta.matches(in: html, range: NSRange(location: 0, length: nsHTML.length)).compactMap { match in
            let values = attributes(nsHTML.substring(with: match.range))
            guard let key = values["property"] ?? values["name"], let content = values["content"] else { return nil }
            return (key.lowercased(), content)
        }
    }

    /// The first element with itemprop=`name`: its content attribute, or the text right after it.
    static func itemprop(_ html: String, _ name: String) -> String? {
        let pattern = regex(#"<[a-z][a-z0-9]*\b[^>]*\bitemprop\s*=\s*["']?"# + name + #"(?=["'\s>/])[^>]*>([^<]*)"#)
        let nsHTML = html as NSString
        guard let match = pattern.firstMatch(in: html, range: NSRange(location: 0, length: nsHTML.length)) else { return nil }
        let tag = nsHTML.substring(with: NSRange(location: match.range.location,
                                                 length: match.range(at: 1).location - match.range.location))
        if let content = attributes(tag)["content"] {
            return content.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        let text = WebSearchText.plain(nsHTML.substring(with: match.range(at: 1)))
        // "Fr. 13.90" or "13,90 €": the number only.
        guard let number = text.range(of: #"\d[\d .,'’]*"#, options: .regularExpression) else { return text.isEmpty ? nil : text }
        return String(text[number]).trimmingCharacters(in: .whitespaces)
    }
}

// MARK: JSON-LD

enum JSONLD {
    struct Offer {
        var amount: String?
        var currency: String?
        var name: String?
        var quantity: Double?
    }

    static let productTypes: Set<String> = ["product", "productgroup", "individualproduct", "productmodel", "wine"]

    /// Products at the top, in arrays, in @graph or as the page's main entity, and their variants.
    static func products(in node: Any) -> [[String: Any]] {
        if let list = node as? [Any] { return list.flatMap { products(in: $0) } }
        guard let object = node as? [String: Any] else { return [] }
        var found: [[String: Any]] = []
        if types(of: object).contains(where: productTypes.contains) || object["offers"] != nil {
            found.append(object)
        }
        for key in ["@graph", "mainEntity", "hasVariant"] {
            if let child = object[key] { found += products(in: child) }
        }
        return found
    }

    static func types(of object: [String: Any]) -> [String] {
        let value = object["@type"]
        let names = (value as? [Any])?.compactMap { $0 as? String } ?? (value as? String).map { [$0] } ?? []
        return names.map { $0.split(separator: "/").last.map(String.init)?.lowercased() ?? "" }
    }

    /// The offers of a product: price (or lowPrice), currency, name and quantity, in their
    /// price specifications or in an aggregate offer's own offers when needed.
    static func offers(of product: [String: Any]) -> [Offer] {
        func list(_ value: Any?) -> [[String: Any]] {
            (value as? [Any])?.compactMap { $0 as? [String: Any] } ?? (value as? [String: Any]).map { [$0] } ?? []
        }
        var found: [Offer] = []
        func read(_ object: [String: Any], depth: Int) {
            var offer = Offer(amount: string(object["price"]) ?? string(object["lowPrice"]),
                              currency: string(object["priceCurrency"]), name: string(object["name"]),
                              quantity: quantity(object["eligibleQuantity"]))
            let specification = list(object["priceSpecification"]).first { string($0["price"]) != nil }
            if offer.amount == nil, let specification { offer.amount = string(specification["price"]) }
            if offer.currency == nil, let specification { offer.currency = string(specification["priceCurrency"]) }
            if offer.amount != nil {
                found.append(offer)
            } else if depth < 2 {
                list(object["offers"]).forEach { read($0, depth: depth + 1) }
            }
        }
        list(product["offers"]).forEach { read($0, depth: 0) }
        return found
    }

    /// Text or a number as text ("13.00", 13.5 → "13.5").
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
    static func units(_ text: String) -> [UInt16] {
        text.utf16.map { unit in
            if unit < 0x80 { return (0x41...0x5A).contains(unit) ? unit + 0x20 : unit }
            if unit == 0x2019 || unit == 0x2018 { return 0x27 }
            guard let scalar = Unicode.Scalar(unit) else { return unit }
            let folded = String(Character(scalar)).folding(options: [.caseInsensitive, .diacriticInsensitive], locale: nil)
            return folded.utf16.count == 1 ? folded.utf16.first! : unit
        }
    }
}
