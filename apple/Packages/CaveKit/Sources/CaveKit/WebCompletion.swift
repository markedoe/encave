import Foundation

/// "Complete from the Web": a model whose connection allows web search looks up a saved
/// wine from its identity alone (producer, name, appellation, vintage, colour, grapes) and
/// answers with its details and what to expect. Only empty details are filled; the expected
/// profile is replaced; the pages it cited are kept in `webSources`. Read the same way by
/// the Kotlin core (`fixtures/web-completion-vectors.json`).
public struct WebCompletion: Equatable, Sendable {
    public var producer = ""
    public var appellation = ""
    public var region = ""
    public var country = ""
    public var grapes: [String] = []
    public var drinkFrom = 0
    public var drinkUntil = 0
    public var abv = 0.0
    public var classification = ""
    public var expected: ExpectedProfile?

    public init() {}

    static let detailKeys = ["producer", "appellation", "region", "country", "grapes", "drinkFrom", "drinkUntil", "abv", "classification"]

    /// The question, from the identity alone.
    public static func prompt(for identity: WineIdentity, language: String) -> String {
        (["Search the web for this wine and complete its details from reliable pages (the producer's own site, the "
            + "appellation's, merchants, guides). Answer with an object holding the details and \"expected\"."]
            + identity.lines
            + [
                "- producer: the estate, domaine or house; appellation, region, country (in English).",
                "- grapes: the grape varieties of this wine.",
                "- drinkFrom and drinkUntil: its drinking window in years, or 0.",
                "- abv: its alcohol by volume in percent, or 0; classification: its classification (Grand Cru…), or \"\".",
            ]
            + ExpectedRequest.lines(language: language)
            + ["Leave a detail empty (\"\", 0 or []) when the pages do not say it. Do not invent."]).joined(separator: "\n")
    }

    public static var schema: [String: Any] {
        let string: [String: Any] = ["type": "string"], integer: [String: Any] = ["type": "integer"]
        return [
            "type": "object",
            "additionalProperties": false,
            "required": detailKeys + ["expected"],
            "properties": [
                "producer": string, "appellation": string, "region": string, "country": string,
                "grapes": ["type": "array", "items": string] as [String: Any],
                "drinkFrom": integer, "drinkUntil": integer, "abv": ["type": "number"], "classification": string,
                "expected": ExpectedRequest.schema,
            ] as [String: Any],
        ]
    }

    /// The answer (JSON text, or text holding it); nil when it holds no object.
    public static func parse(answer text: String, model: String?, language: String, date: Date) -> WebCompletion? {
        guard let start = text.firstIndex(of: "{"), let end = text.lastIndex(of: "}"), start < end,
              let root = try? JSONSerialization.jsonObject(with: Data(text[start...end].utf8)) as? [String: Any] else { return nil }
        func string(_ key: String) -> String { ((root[key] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines) }
        func int(_ key: String) -> Int {
            guard let number = (root[key] as? NSNumber)?.doubleValue, number.isFinite, abs(number) < 100_000 else { return 0 }
            return Int(number.rounded())
        }
        var completion = WebCompletion()
        completion.producer = string("producer")
        completion.appellation = string("appellation")
        completion.region = string("region")
        completion.country = string("country")
        completion.classification = string("classification")
        completion.grapes = ((root["grapes"] as? [Any]) ?? []).compactMap { ($0 as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        completion.drinkFrom = int("drinkFrom")
        completion.drinkUntil = int("drinkUntil")
        completion.abv = (root["abv"] as? NSNumber)?.doubleValue ?? 0
        if let expected = root["expected"] as? [String: Any] {
            completion.expected = ExpectedRequest.profile(from: expected, source: ExpectedProfile.web, model: model,
                                                          language: language, date: date)
        }
        return completion
    }

    /// The wine with its empty details filled, its drinking window when it had none or only
    /// the estimate, the expected profile from the web and the pages cited; `aiCompletedAt`
    /// set when anything changed. The wine itself when nothing did.
    public func completing(_ wine: Wine, sources: [WineWebSource], date: Date) -> Wine {
        var result = wine
        func fill(_ field: WritableKeyPath<Wine, String?>, _ value: String) {
            if (result[keyPath: field] ?? "").trimmingCharacters(in: .whitespaces).isEmpty, !value.isEmpty {
                result[keyPath: field] = value
            }
        }
        fill(\.producer, producer)
        fill(\.appellation, appellation)
        fill(\.region, region)
        fill(\.country, country)
        fill(\.classification, classification)
        if result.grapes.isEmpty, !grapes.isEmpty { result.grapes = grapes }
        if result.abv == nil, (0.5...25).contains(abv) {
            result.abv = Decimal(string: String(format: "%.2f", locale: Locale(identifier: "en_US_POSIX"), abv))
        }
        // None, or only the estimate from the style and vintage.
        if result.windowSource != .entered, drinkFrom > 1900, drinkUntil >= drinkFrom, drinkUntil < 2200 {
            result.drinkFrom = drinkFrom
            result.drinkUntil = drinkUntil
        }
        if let expected { result.expectedProfile = expected }
        let cited = AIWebSearch.unique(sources)
        if !cited.isEmpty { result.webSources = cited }
        guard result != wine else { return wine }
        result.aiCompletedAt = date
        return result
    }
}

extension AIConfiguration {
    /// The models that may search the web, in order: those whose connection allows it.
    public var webSearchOrder: [AIModelChoice] {
        usableModels.filter { connection(for: $0)?.searchesWeb == true }
    }
}
