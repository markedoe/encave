import Foundation

/// A request for an evening's selection, as text for an assistant app the user picks in
/// their own shortcut ("Prepare an Evening Request"): a short instruction in the user's
/// language, then the bottles of the cellar, one line per wine. It is built on the device
/// and returned to the shortcut; Encave sends nothing. Prices, notes, places of purchase
/// and photos are never in it. The same text on Android (fixtures/evening-request-vectors.json).
public struct EveningRequest: Sendable, Equatable {
    public enum Language: String, Codable, Sendable {
        case en, fr

        /// French for a French language code, else English.
        public init(code: String?) {
            self = code?.lowercased().hasPrefix("fr") == true ? .fr : .en
        }
    }

    /// One wine of the list.
    public struct Wine: Codable, Sendable, Equatable {
        public var name: String
        public var vintage: Int?
        public var producer: String?
        /// red, white, rose, sparkling, dessert, fortified, orange.
        public var style: String?
        public var appellation: String?
        public var drinkFrom: Int?
        public var drinkUntil: Int?
        /// drink soon, ready, too young, past its peak, window unknown.
        public var maturity: String
        public var bottles: Int
        public var places: [String]

        public init(name: String, vintage: Int? = nil, producer: String? = nil, style: String? = nil,
                    appellation: String? = nil, drinkFrom: Int? = nil, drinkUntil: Int? = nil,
                    maturity: String, bottles: Int, places: [String] = []) {
            self.name = name
            self.vintage = vintage
            self.producer = producer
            self.style = style
            self.appellation = appellation
            self.drinkFrom = drinkFrom
            self.drinkUntil = drinkUntil
            self.maturity = maturity
            self.bottles = bottles
            self.places = places
        }

        public init(_ row: CellarReport.Row) {
            self.init(name: row.name, vintage: row.vintage, producer: row.producer, style: row.style,
                      appellation: row.appellation ?? row.region, drinkFrom: row.drinkFrom, drinkUntil: row.drinkUntil,
                      maturity: row.maturity, bottles: row.bottles, places: row.places)
        }
    }

    /// The occasion, dishes or guests, as the user typed them.
    public var occasion: String
    /// Bottles the user wants; nil lets the assistant choose.
    public var bottlesWanted: Int?
    /// The list holds only bottles ready to drink or to drink soon.
    public var onlyReady: Bool
    public var language: Language
    public var year: Int
    public var wines: [Wine]
    /// Wines left out to stay within the limit.
    public var omitted: Int

    public init(occasion: String = "", bottlesWanted: Int? = nil, onlyReady: Bool = true, language: Language = .en,
                year: Int, wines: [Wine], omitted: Int = 0) {
        self.occasion = occasion
        self.bottlesWanted = bottlesWanted
        self.onlyReady = onlyReady
        self.language = language
        self.year = year
        self.wines = wines
        self.omitted = omitted
    }

    public var text: String {
        let fr = language == .fr
        let occasion = Self.clean(self.occasion).trimmingCharacters(in: .whitespaces)
        let wanted = bottlesWanted.flatMap { $0 > 0 ? min($0, 24) : nil }
        var lines: [String] = []
        lines.append(fr ? "Aide-moi à choisir du vin de ma cave pour ce soir."
                        : "Help me choose wine from my cellar for this evening.")
        if !occasion.isEmpty { lines.append((fr ? "Occasion : " : "Occasion: ") + occasion) }
        let count: String = switch (wanted, fr) {
        case let (n?, true): n > 1 ? "\(n) bouteilles, prises" : "\(n) bouteille, prise"
        case let (n?, false): "\(n) bottle\(n == 1 ? "" : "s"), taken"
        case (nil, true): "quelques bouteilles, prises"
        case (nil, false): "a few bottles, taken"
        }
        lines.append(fr ? "Propose \(count) uniquement dans la liste ci-dessous."
                        : "Propose \(count) only from the list below.")
        lines.append(fr ? "Pour chaque bouteille, dis pourquoi elle convient, à quel moment la servir dans la soirée (ordre de service), "
                            + "sa température de service et où elle se trouve dans la cave (sa position)."
                        : "For each bottle, say why it fits, when to serve it in the evening (order of service), "
                            + "its serving temperature and where it is in the cellar (its position).")
        if onlyReady {
            lines.append(fr ? "La liste ne contient que des bouteilles prêtes à boire ou à boire bientôt."
                            : "The list holds only bottles ready to drink or to drink soon.")
        }
        lines.append(fr ? "Réponds en français." : "Answer in English.")
        lines.append("")

        let bottles = wines.reduce(0) { $0 + $1.bottles }
        var header = fr
            ? "Ma cave en \(year) : \(wines.count) vin\(wines.count > 1 ? "s" : ""), \(bottles) bouteille\(bottles > 1 ? "s" : "")"
            : "My cellar in \(year): \(wines.count) wine\(wines.count == 1 ? "" : "s"), \(bottles) bottle\(bottles == 1 ? "" : "s")"
        if omitted > 0 {
            header += fr ? " (\(omitted) autre\(omitted > 1 ? "s" : "") vin\(omitted > 1 ? "s" : "") non listé\(omitted > 1 ? "s" : ""))"
                         : " (\(omitted) more wine\(omitted == 1 ? "" : "s") left out)"
        }
        lines.append(header + ".")
        guard !wines.isEmpty else { return lines.joined(separator: "\n") }
        lines.append(fr ? "Colonnes : vin | producteur | style | appellation | fenêtre | maturité | bouteilles | positions"
                        : "Columns: wine | producer | style | appellation | window | maturity | bottles | positions")
        for (index, wine) in wines.enumerated() {
            let window: String = switch (wine.drinkFrom, wine.drinkUntil) {
            case let (from?, until?): "\(from)-\(until)"
            case let (from?, nil): fr ? "dès \(from)" : "from \(from)"
            case let (nil, until?): fr ? "jusqu’en \(until)" : "until \(until)"
            case (nil, nil): ""
            }
            let fields = [
                wine.vintage.map { "\(wine.name) \($0)" } ?? wine.name,
                wine.producer ?? "", Self.style(wine.style, fr: fr), wine.appellation ?? "", window,
                Self.maturity(wine.maturity, fr: fr), "\(wine.bottles)", wine.places.joined(separator: "; "),
            ]
            lines.append("\(index + 1). " + fields.map(Self.clean).joined(separator: " | "))
        }
        return lines.joined(separator: "\n")
    }

    static func style(_ style: String?, fr: Bool) -> String {
        guard let style else { return "" }
        guard fr else { return style }
        return switch style {
        case "red": "rouge"
        case "white": "blanc"
        case "rose": "rosé"
        case "sparkling": "effervescent"
        case "dessert": "liquoreux"
        case "fortified": "muté"
        default: style
        }
    }

    static func maturity(_ maturity: String, fr: Bool) -> String {
        guard fr else { return maturity }
        return switch maturity {
        case "drink soon": "à boire bientôt"
        case "ready": "prêt"
        case "too young": "trop jeune"
        case "past its peak": "après l’apogée"
        case "window unknown": "fenêtre inconnue"
        default: maturity
        }
    }

    /// Keeps a field on one line and free of the separator.
    static func clean(_ field: String) -> String {
        field.replacingOccurrences(of: "|", with: "/")
            .components(separatedBy: .newlines).joined(separator: " ")
    }
}

public extension Cellar {
    /// The request for an evening: with `onlyReady`, the bottles to drink soon then those
    /// ready, leaving out those kept for an occasion (as Tonight); else every bottle in the
    /// cellar, by name. At most `limit` wines.
    func eveningRequest(occasion: String = "", bottlesWanted: Int? = nil, onlyReady: Bool = true,
                        language: EveningRequest.Language, year: Int, limit: Int = 500,
                        location: ((Bottle) -> String?)? = nil) -> EveningRequest {
        let report = report(scope: onlyReady ? .toOpen : .all, year: year, limit: limit, location: location)
        return EveningRequest(occasion: occasion, bottlesWanted: bottlesWanted, onlyReady: onlyReady, language: language,
                              year: year, wines: report.rows.map(EveningRequest.Wine.init), omitted: report.omitted)
    }
}
