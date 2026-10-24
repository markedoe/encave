import Foundation

// Reads the price of a bottle on the web pages a price search found. After the search, the
// app opens the first result pages from the device (no cookies, cache, credentials or
// Referer) and reads them here, on the device. The Android app does the same
// (PagePrices.kt); both are checked with fixtures/prices.
//
// Pages come from anyone: the reading is linear in the page's size (no regular expression
// runs on the HTML), stops at `maxParsedUnits`, and checks `shouldStop` between steps.

public enum PagePrices {
    /// The first results opened, in their order.
    public static let maxPages = 5
    /// Pages read at the same time.
    public static let parallelPages = 3
    /// Reading stops after this many bytes; the price is near the top of a product page.
    public static let maxBytes = 1_500_000
    /// Only the start of the page is parsed (UTF-16 units).
    static let maxParsedUnits = 512 * 1024
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
    /// wine's name. Each keeps the result's site and address. Empty once `shouldStop` is true.
    public static func prices(inPage html: String, of result: WebSearchResult, sizeML: Int, wineName: String? = nil,
                              shouldStop: () -> Bool = { Task.isCancelled }) -> [FoundPrice] {
        guard let document = HTMLScanner.scan(html, shouldStop: shouldStop), !shouldStop() else { return [] }
        let page = Page(document: document, wineName: wineName)
        guard !shouldStop() else { return [] }
        let found = page.structuredPrices(sizeML: sizeML, shouldStop: shouldStop) ?? page.textPrices(sizeML: sizeML)
        guard !shouldStop() else { return [] }
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
    }

    static let currencies: Set<String> = ["EUR", "USD", "GBP", "CHF"]
    /// Text prices kept from one page at most: related products and the like are not wanted.
    static let maxTextPrices = 3
    /// How far after the wine's name (in UTF-16 units) a price or a size counts as near it.
    static let nearName = 400
    /// Places of the wine's name looked at, at most.
    static let maxAreas = 20
    /// Without the wine's name on the page, only the start of its text is looked at.
    static let maxTextWithoutName = 50_000

    struct Page {
        let document: HTMLDocument
        let text: String
        let heading: String?
        let title: String?
        /// Where to look in the text: after the places the wine's name is, or the start of it.
        let areas: [NSRange]
        /// The one size named near the wine's name, if only one is.
        let areaSize: (sizeML: Int, words: String)?

        init(document: HTMLDocument, wineName: String?) {
            self.document = document
            text = document.text
            heading = document.heading
            let ogTitle = document.meta.first { $0.key == "og:title" }?.value
            title = ogTitle.flatMap(HTML.cleanLine) ?? document.title
            areas = Page.areas(in: text, name: wineName)
            areaSize = Page.size(in: text, areas: areas)
        }

        static func areas(in text: String, name: String?) -> [NSRange] {
            let length = (text as NSString).length
            let start = [NSRange(location: 0, length: min(length, maxTextWithoutName))]
            let cleanName = (name ?? "").trimmingCharacters(in: .whitespacesAndNewlines).precomposedStringWithCanonicalMapping
            let needle = Fold.units(String(cleanName.prefix(100)))
            guard needle.count >= 3 else { return start }
            let haystack = Fold.units(text)
            var areas: [NSRange] = []
            var index = 0
            while index + needle.count <= haystack.count, areas.count < maxAreas {
                if haystack[index] == needle[0], haystack[index..<(index + needle.count)].elementsEqual(needle) {
                    let end = min(length, index + nearName)
                    // Places close together make one area.
                    if let last = areas.last, index <= NSMaxRange(last) {
                        areas[areas.count - 1] = NSRange(location: last.location, length: end - last.location)
                    } else {
                        areas.append(NSRange(location: index, length: end - index))
                    }
                    index += needle.count
                } else {
                    index += 1
                }
            }
            return areas.isEmpty ? start : areas
        }

        static func size(in text: String, areas: [NSRange]) -> (sizeML: Int, words: String)? {
            let nsText = text as NSString
            var sizes: [Int: String] = [:]
            for area in areas {
                let piece = nsText.substring(with: area)
                for (size, pattern) in PriceExtractor.sizePatterns where sizes[size] == nil {
                    if let range = piece.range(of: pattern, options: [.regularExpression, .caseInsensitive]) {
                        sizes[size] = String(piece[range])
                    }
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

        /// Nil when the page has no structured price for one bottle of the size.
        func structuredPrices(sizeML: Int, shouldStop: () -> Bool) -> [Item]? {
            for step in 0..<3 {
                if shouldStop() { return [] }
                let items = switch step {
                case 0: jsonLDPrices(sizeML: sizeML, shouldStop: shouldStop)
                case 1: metaPrices(sizeML: sizeML)
                default: microdataPrices(sizeML: sizeML)
                }
                if !items.isEmpty { return items }
            }
            return nil
        }

        func item(amount text: String, currency: String?, names: [String?], label: String?, sizeML: Int) -> Item? {
            guard let currency = currency?.trimmingCharacters(in: .whitespaces).uppercased(),
                  PagePrices.currencies.contains(currency),
                  let amount = PagePrices.structuredAmount(text), PriceExtractor.range.contains(amount) else { return nil }
            // A case, a lot or a pack is not one bottle.
            for name in names.compactMap({ $0 })
            where PriceExtractor.contains(String(name.prefix(300)), PriceExtractor.notOneBottleBefore) {
                return nil
            }
            let size = size(named: names)
            guard (size?.sizeML ?? 750) == sizeML else { return nil }
            return Item(amount: amount, currency: currency,
                        excerpt: excerpt(name: label, price: "\(PagePrices.twoDecimals(amount)) \(currency)", size: size?.words))
        }

        func jsonLDPrices(sizeML: Int, shouldStop: () -> Bool) -> [Item] {
            var items: [Item] = []
            for script in document.jsonLD {
                if shouldStop() { return [] }
                guard let data = script.data(using: .utf8),
                      let root = try? JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed]) else { continue }
                for product in JSONLD.products(in: root) {
                    let name = JSONLD.string(product["name"])
                    for offer in JSONLD.offers(of: product) {
                        if let quantity = offer.quantity, quantity > 1 { continue }
                        if let item = item(amount: offer.amount, currency: offer.currency, names: [offer.name, name],
                                           label: name, sizeML: sizeML) {
                            items.append(item)
                        }
                    }
                }
            }
            return items
        }

        func metaPrices(sizeML: Int) -> [Item] {
            func value(_ key: String) -> String? { document.meta.first { $0.key == key }?.value }
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
            guard let amount = document.itemPrice,
                  let item = item(amount: amount, currency: document.itemCurrency, names: [], label: nil, sizeML: sizeML)
            else { return [] }
            return [item]
        }

        // MARK: Visible text

        func textPrices(sizeML: Int) -> [Item] {
            let size = size(named: [])
            let nsText = text as NSString
            var prices: [PriceExtractor.TextPrice] = []
            for area in areas {
                // A little text around the area, for the words next to a price.
                let start = max(0, area.location - 40)
                let end = min(nsText.length, NSMaxRange(area) + 30)
                let piece = nsText.substring(with: NSRange(location: start, length: end - start))
                for price in PriceExtractor.textPrices(in: piece, sizeML: sizeML, defaultSize: size?.sizeML ?? 750)
                where NSLocationInRange(start + price.location, area) {
                    prices.append(price)
                }
                if prices.count >= PagePrices.maxTextPrices { break }
            }
            return prices.prefix(PagePrices.maxTextPrices).map { price in
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
                // "a < b": text.
                out.append(lt)
                index = open + 1
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
                // The content runs to "</name"; a page where it does not end is read no further.
                let selfClosing = end > nameStart && units[end - 1] == slash
                if selfClosing { continue }
                guard let close = findClose(units, name: name, from: end + 1) else { break scanning }
                let content = string((end + 1)..<close)
                if name == "script", attributes["type"]?.trimmingCharacters(in: .whitespaces).lowercased() == "application/ld+json" {
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
        document.text = HTML.lines(String(decoding: out, as: UTF16.self))
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
        while k + 1 + wanted.count < n {
            if units[k] == lt, units[k + 1] == slash {
                var match = true
                for (offset, unit) in wanted.enumerated() {
                    var have = units[k + 2 + offset]
                    if (0x41...0x5A).contains(have) { have += 0x20 }
                    if have != unit { match = false; break }
                }
                if match, !isNameUnit(units[k + 2 + wanted.count]) { return k }
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
    static func lines(_ raw: String) -> String {
        let text = WebSearchText.decodeEntities(raw).precomposedStringWithCanonicalMapping
        var out: [UInt16] = []
        var pendingSpace = false
        var lineHasText = false
        for unit in text.utf16 {
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
        return String(decoding: out, as: UTF16.self)
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
    static func units(_ text: String) -> [UInt16] {
        var cache: [UInt16: UInt16] = [:]
        return text.utf16.map { unit in
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
