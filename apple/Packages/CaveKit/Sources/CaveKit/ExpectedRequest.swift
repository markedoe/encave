import Foundation

/// What identifies a wine for the AI, and all it is sent when the photo is not: producer,
/// name, appellation, vintage, colour and grapes. Never the cellar, notes, prices or places.
public struct WineIdentity: Equatable, Sendable {
    public var producer: String
    public var name: String
    public var appellation: String
    public var vintage: Int?
    public var color: String
    public var grapes: [String]

    public init(producer: String = "", name: String, appellation: String = "", vintage: Int? = nil,
                color: String = "", grapes: [String] = []) {
        self.producer = producer; self.name = name; self.appellation = appellation
        self.vintage = vintage; self.color = color; self.grapes = grapes
    }

    public init(_ wine: Wine) {
        self.init(producer: wine.producer ?? "", name: wine.name, appellation: wine.appellation ?? "",
                  vintage: wine.vintage, color: wine.color?.rawValue ?? "", grapes: wine.grapes)
    }

    /// One line per detail known, as sent.
    public var lines: [String] {
        func clean(_ text: String) -> String { text.trimmingCharacters(in: .whitespacesAndNewlines) }
        var lines: [String] = []
        if !clean(producer).isEmpty { lines.append("Producer: \(clean(producer))") }
        if !clean(name).isEmpty { lines.append("Name: \(clean(name))") }
        if !clean(appellation).isEmpty { lines.append("Appellation: \(clean(appellation))") }
        lines.append("Vintage: \(vintage.map(String.init) ?? "non-vintage")")
        if !clean(color).isEmpty { lines.append("Colour: \(clean(color))") }
        let grapes = grapes.map(clean).filter { !$0.isEmpty }
        if !grapes.isEmpty { lines.append("Grapes: \(grapes.joined(separator: ", "))") }
        return lines
    }
}

/// "What to expect" from a wine, asked of the AI: with the label photo (part of
/// `LabelRequest`, no extra request), or from the wine's identity only (`prompt(for:)`).
/// The answer's "expected" object becomes an `ExpectedProfile` (`profile(fromAnswer:)`).
public enum ExpectedRequest {
    /// The language of the short text, by its code.
    static func languageName(_ code: String) -> String {
        switch code.lowercased().prefix(2) {
        case "fr": "French"
        default: "English"
        }
    }

    public static let structureKeys = ["sweetness", "acidity", "tannin", "body", "alcohol", "length"]

    /// The lines describing "expected", in a label request or on its own.
    static func lines(language: String) -> [String] {
        [
            "- expected: what to expect when tasting this wine and vintage, from what you know of it, its producer and "
                + "its appellation; leave a detail empty ([], 0 or \"\") when you do not know it.",
            "  - aromas: up to 6 of these ids, the most typical first: " + Vocabulary.aromaIDs.joined(separator: ", ") + ".",
            "  - structure: " + structureKeys.joined(separator: ", ")
                + " from 1 (low) to 5 (high); 0 when unknown, and tannin 0 for a white wine.",
            "  - servingMin and servingMax: serving temperature in degrees Celsius; decantMinutes: minutes of decanting, 0 for none.",
            "  - pairings: up to 4 of these ids: " + DishCategory.allCases.map(\.rawValue).joined(separator: ", ") + ".",
            "  - text: one or two short sentences in \(languageName(language)) on what to expect, or \"\".",
        ]
    }

    public static var schema: [String: Any] {
        let integer: [String: Any] = ["type": "integer"]
        let structure: [String: Any] = [
            "type": "object",
            "additionalProperties": false,
            "required": structureKeys,
            "properties": Dictionary(uniqueKeysWithValues: structureKeys.map { ($0, integer) }),
        ]
        return [
            "type": "object",
            "additionalProperties": false,
            "required": ["aromas", "structure", "servingMin", "servingMax", "decantMinutes", "pairings", "text"],
            "properties": [
                "aromas": ["type": "array", "items": ["type": "string", "enum": Vocabulary.aromaIDs] as [String: Any]] as [String: Any],
                "structure": structure,
                "servingMin": integer,
                "servingMax": integer,
                "decantMinutes": integer,
                "pairings": ["type": "array",
                             "items": ["type": "string", "enum": DishCategory.allCases.map(\.rawValue)] as [String: Any]] as [String: Any],
                "text": ["type": "string"],
            ] as [String: Any],
        ]
    }

    /// The text of a request from the wine's identity alone (no photo).
    public static func prompt(for identity: WineIdentity, language: String) -> String {
        (["What can one expect when tasting this wine? Answer with an object holding \"expected\"."]
            + identity.lines + lines(language: language)
            + ["Do not invent: an empty detail is better than a wrong one."]).joined(separator: "\n")
    }

    /// The schema of a request from the identity alone.
    public static var answerSchema: [String: Any] {
        ["type": "object", "additionalProperties": false, "required": ["expected"], "properties": ["expected": schema]]
    }

    /// The "expected" object of an answer (JSON text, or text holding it): ids it does not
    /// know, levels of 0 and temperatures of 0 or less left out, the text cut at 400
    /// characters. Nil when nothing is left.
    public static func profile(fromAnswer text: String, source: String, model: String?, language: String,
                               date: Date) -> ExpectedProfile? {
        guard let start = text.firstIndex(of: "{"), let end = text.lastIndex(of: "}"), start < end,
              let root = try? JSONSerialization.jsonObject(with: Data(text[start...end].utf8)) as? [String: Any],
              let object = root["expected"] as? [String: Any] else { return nil }
        return profile(from: object, source: source, model: model, language: language, date: date)
    }

    static func profile(from object: [String: Any], source: String, model: String?, language: String,
                        date: Date) -> ExpectedProfile? {
        func int(_ value: Any?) -> Int? {
            guard let double = (value as? NSNumber)?.doubleValue else { return nil }
            return double.isFinite && abs(double) < 100_000 ? Int(double.rounded()) : nil
        }
        let known = Set(Vocabulary.aromaIDs), dishes = Set(DishCategory.allCases.map(\.rawValue))
        let aromas = Array(((object["aromas"] as? [Any]) ?? []).compactMap { $0 as? String }.filter(known.contains).prefix(8))
        let pairings = Array(((object["pairings"] as? [Any]) ?? []).compactMap { $0 as? String }.filter(dishes.contains).prefix(6))
        var structure: [String: Int] = [:]
        for (key, value) in (object["structure"] as? [String: Any]) ?? [:] where structureKeys.contains(key) {
            if let level = int(value), level >= 1 { structure[key] = level }
        }
        let low = int(object["servingMin"]).flatMap { $0 > 0 ? $0 : nil }
        let high = int(object["servingMax"]).flatMap { $0 > 0 ? $0 : nil }
        var text = ((object["text"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if text.count > 400 { text = String(text.prefix(400)) }
        let profile = ExpectedProfile(source: source, aromas: aromas, structure: structure, servingMin: low, servingMax: high,
                                      decantMinutes: int(object["decantMinutes"]), pairings: pairings, text: text,
                                      language: text.isEmpty ? nil : String(language.lowercased().prefix(2)),
                                      model: model, date: date)
        return profile.isEmpty ? nil : profile
    }
}
