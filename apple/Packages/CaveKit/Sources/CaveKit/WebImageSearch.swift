import Foundation

// Pictures of a bottle from the internet, with the image search of the service set up
// for web search. Only the search terms are sent to the search service; the thumbnails
// and the picture the user chooses are then downloaded from the sites that host them.
// The picture becomes the wine's cover (coverPhotoID); the label photo is never replaced.

/// A picture found.
public struct WebImageResult: Identifiable, Hashable, Sendable {
    public var title: String
    /// A small version to show in the grid.
    public var thumbnailURL: URL
    /// The full picture, downloaded only when chosen.
    public var imageURL: URL
    /// The page the picture is on.
    public var pageURL: URL?

    public var id: String { imageURL.absoluteString }

    /// The site of the page, or of the picture.
    public var domain: String {
        let host = (pageURL ?? imageURL).host ?? ""
        return host.hasPrefix("www.") ? String(host.dropFirst(4)) : host
    }

    public init(title: String, thumbnailURL: URL, imageURL: URL, pageURL: URL?) {
        self.title = title
        self.thumbnailURL = thumbnailURL
        self.imageURL = imageURL
        self.pageURL = pageURL
    }

    /// Nil without an https picture. A missing or plain-http thumbnail falls back to the picture.
    init?(title: String?, thumbnail: String?, image: String?, page: String?) {
        guard let imageURL = WebImageDownload.httpsURL(image) else { return nil }
        self.init(title: WebSearchText.plain(title ?? ""), thumbnailURL: WebImageDownload.httpsURL(thumbnail) ?? imageURL,
                  imageURL: imageURL, pageURL: page.flatMap { URL(string: $0.trimmingCharacters(in: .whitespaces)) })
    }
}

/// A service that can also search pictures.
public protocol WebImageSearchProvider: Sendable {
    func imageRequest(for query: String, count: Int) throws -> WebSearchRequest
    func imageResults(from data: Data, status: Int) throws -> [WebImageResult]
}

extension WebSearchService {
    public func imageProvider(apiKey: String?, serverURL: String) throws -> any WebImageSearchProvider {
        guard let provider = try provider(apiKey: apiKey, serverURL: serverURL) as? any WebImageSearchProvider else {
            throw WebSearchError.invalidResponse
        }
        return provider
    }
}

extension WebSearchQuery {
    /// "Domaine X Chablis 1er Cru 2019 bottle": the wine's terms and "bottle" in the user's language.
    public static func pictureTerms(for wine: Wine, bottleWord: String = "bottle") -> String {
        terms(producer: wine.producer, name: wine.name, appellation: wine.appellation, vintage: wine.vintage,
              wineWord: bottleWord)
    }
}

/// Brave Image Search: GET /res/v1/images/search, the same key.
/// Answer: {"results": [{"title", "url" (page), "thumbnail": {"src"}, "properties": {"url"}}]}.
extension BraveSearch: WebImageSearchProvider {
    public func imageRequest(for query: String, count: Int) throws -> WebSearchRequest {
        let count = min(max(count, 1), 100)
        let address = "https://api.search.brave.com/res/v1/images/search?q=\(WebSearchQuery.encode(query))&count=\(count)&safesearch=strict"
        guard let url = URL(string: address) else { throw WebSearchError.invalidResponse }
        return WebSearchRequest(url: url, method: "GET",
                                headers: ["Accept": "application/json", "X-Subscription-Token": apiKey], body: nil)
    }

    public func imageResults(from data: Data, status: Int) throws -> [WebImageResult] {
        try WebSearchStatus.check(status)
        struct Answer: Decodable {
            struct Source: Decodable { var src: String?; var url: String? }
            struct Item: Decodable { var title: String?; var url: String?; var thumbnail: Source?; var properties: Source? }
            var results: [Item]?
        }
        guard let answer = try? JSONDecoder().decode(Answer.self, from: data) else { throw WebSearchError.invalidResponse }
        return WebImageDownload.unique((answer.results ?? []).compactMap {
            WebImageResult(title: $0.title, thumbnail: $0.thumbnail?.src, image: $0.properties?.url, page: $0.url)
        })
    }
}

/// SearXNG: GET <server>/search?q=…&format=json&categories=images.
/// Answer: {"results": [{"title", "url" (page), "img_src", "thumbnail_src"}]}.
extension SearXNGSearch: WebImageSearchProvider {
    public func imageRequest(for query: String, count: Int) throws -> WebSearchRequest {
        guard let url = URL(string: "\(base)/search?q=\(WebSearchQuery.encode(query))&format=json&categories=images&safesearch=1") else {
            throw WebSearchError.invalidServerURL
        }
        return WebSearchRequest(url: url, method: "GET", headers: ["Accept": "application/json"], body: nil)
    }

    public func imageResults(from data: Data, status: Int) throws -> [WebImageResult] {
        if status == 403 { throw WebSearchError.jsonFormatDisabled }
        try WebSearchStatus.check(status)
        struct Answer: Decodable {
            struct Item: Decodable {
                var title: String?; var url: String?; var img_src: String?; var thumbnail_src: String?
            }
            var results: [Item]?
        }
        guard let answer = try? JSONDecoder().decode(Answer.self, from: data) else { throw WebSearchError.invalidResponse }
        return WebImageDownload.unique((answer.results ?? []).compactMap {
            WebImageResult(title: $0.title, thumbnail: $0.thumbnail_src, image: $0.img_src, page: $0.url)
        })
    }
}

/// Tavily: the same search with "include_images"; answer {"images": ["https://…"]} or
/// [{"url", "description"}].
extension TavilySearch: WebImageSearchProvider {
    public func imageRequest(for query: String, count: Int) throws -> WebSearchRequest {
        guard let url = URL(string: "https://api.tavily.com/search") else { throw WebSearchError.invalidResponse }
        struct Body: Encodable {
            var query: String
            var max_results: Int
            var include_images = true
            var include_image_descriptions = true
        }
        let body = try JSONEncoder().encode(Body(query: query, max_results: min(max(count, 1), 20)))
        return WebSearchRequest(url: url, method: "POST",
                                headers: ["Content-Type": "application/json", "Authorization": "Bearer \(apiKey)"],
                                body: body)
    }

    public func imageResults(from data: Data, status: Int) throws -> [WebImageResult] {
        try WebSearchStatus.check(status)
        struct Image: Decodable {
            var url: String?
            var description: String?
            init(from decoder: Decoder) throws {
                if let text = try? decoder.singleValueContainer().decode(String.self) {
                    url = text
                    return
                }
                enum Keys: String, CodingKey { case url, description }
                let container = try decoder.container(keyedBy: Keys.self)
                url = try container.decodeIfPresent(String.self, forKey: .url)
                description = try container.decodeIfPresent(String.self, forKey: .description)
            }
        }
        struct Answer: Decodable { var images: [Image]? }
        guard let answer = try? JSONDecoder().decode(Answer.self, from: data) else { throw WebSearchError.invalidResponse }
        return WebImageDownload.unique((answer.images ?? []).compactMap {
            WebImageResult(title: $0.description, thumbnail: nil, image: $0.url, page: nil)
        })
    }
}

/// Checks for downloading a chosen picture: https from a public host only, an image type, at most 8 MB.
public enum WebImageDownload {
    public static let maxBytes = 8 * 1024 * 1024
    public static let timeout: TimeInterval = 20
    static let types: Set<String> = ["image/jpeg", "image/jpg", "image/pjpeg", "image/png", "image/webp", "image/heic",
                                     "image/heif", "image/gif", "image/avif"]

    static func httpsURL(_ text: String?) -> URL? {
        guard let text, let url = URL(string: text.trimmingCharacters(in: .whitespaces)),
              url.scheme?.lowercased() == "https", let host = url.host, !host.isEmpty else { return nil }
        return url
    }

    /// https, and a public host: search results are not trusted, so a picture never comes from
    /// this device or the local network (a router, a NAS), by name or by address.
    public static func isAllowed(_ url: URL) -> Bool {
        guard let url = httpsURL(url.absoluteString), let host = url.host else { return false }
        // A name without a dot ("nas") is completed with the local network's domain.
        let name = PublicHost.bare(host)
        if !PublicHost.isAddress(name), !name.contains(".") { return false }
        return !isPrivateHost(host)
    }

    /// `isAllowed`, and the name resolves only to public addresses ("fritz.box" or a name
    /// pointing to the local network does not). An address is checked as it is.
    public static func resolvesPublicly(_ url: URL, resolver: LocalName.Resolver? = nil) async -> Bool {
        guard isAllowed(url), let host = url.host else { return false }
        return await PublicHost.resolvesPublicly(host, resolver: resolver)
    }

    /// localhost, *.local, private, loopback and link-local addresses, and IP addresses written in other forms.
    static func isPrivateHost(_ host: String) -> Bool {
        var name = host.lowercased()
        if name.hasPrefix("["), name.hasSuffix("]") { name = String(name.dropFirst().dropLast()) }
        while name.hasSuffix(".") { name.removeLast() }
        if name.isEmpty || LocalAddress.isLocal(host: name) || name.hasSuffix(".localhost") { return true }
        if name.contains(":") {
            // IPv6: the unspecified address, and IPv4 addresses mapped into it.
            return name == "::" || name.hasPrefix("::ffff:") || name.hasPrefix("0:0:0:0:0:ffff:")
        }
        // A name whose last label is a number is an IP address ("2130706433", "0x7f.1", "127.1"):
        // only the usual four decimal numbers, of a public address, are accepted.
        let last = name.split(separator: ".").last.map(String.init) ?? ""
        guard !last.isEmpty, last.allSatisfy({ $0.isASCII && $0.isNumber }) || last.hasPrefix("0x") else { return false }
        let octets = name.split(separator: ".", omittingEmptySubsequences: false).map { part -> Int? in
            guard (1...3).contains(part.count), part.allSatisfy({ $0.isASCII && $0.isNumber }),
                  part == "0" || part.first != "0" else { return nil }
            return Int(part)
        }
        guard octets.count == 4, octets.allSatisfy({ ($0 ?? 256) <= 255 }) else { return true }
        let a = octets[0]!, b = octets[1]!
        // This network, shared address space (carrier-grade NAT), multicast and reserved.
        return a == 0 || (a == 100 && (64...127).contains(b)) || a >= 224
    }

    /// "image/jpeg; charset=…" is an image; text, HTML and unknown types are not.
    public static func isImage(contentType: String?) -> Bool {
        guard let type = contentType?.split(separator: ";").first?.trimmingCharacters(in: .whitespaces).lowercased() else {
            return false
        }
        return types.contains(type)
    }

    /// False when the server announces more than the cap (-1 or nil: unknown, checked while reading).
    public static func acceptsLength(_ length: Int64?) -> Bool {
        guard let length, length >= 0 else { return true }
        return length <= Int64(maxBytes)
    }

    static func unique(_ results: [WebImageResult]) -> [WebImageResult] {
        var seen = Set<String>()
        return results.filter { seen.insert($0.id).inserted }
    }
}
