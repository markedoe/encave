import Foundation

/// What the AI label recognition asks about a label photo: the text sent with it and
/// the JSON schema of the answer, the same for every provider. With `findsLabel`
/// (the studio look on), the same request also asks where the whole front label is
/// in the photo ("label_box" and "label_angle", read by `AILabelPlacement`), so the
/// studio picture can be framed on it. Nothing else is sent either way.
///
/// Every answer also says where each detail comes from ("sources"): read on the label, or
/// the model's own estimate from what it knows ("knowledge"), which the form marks as
/// "Estimated by AI" (`LabelSources`). A detail the model is unsure of is left empty.
public struct LabelRequest: Sendable, Equatable {
    public var findsLabel: Bool
    /// The language of the person ("en", "fr"): the same request also asks what to expect
    /// from the wine ("expected", `ExpectedRequest`), its short text in that language. Nil
    /// does not ask it.
    public var expectedLanguage: String?

    public init(findsLabel: Bool, expectedLanguage: String? = nil) {
        self.findsLabel = findsLabel
        self.expectedLanguage = expectedLanguage
    }

    static let detailKeys = ["name", "producer", "vintage", "color", "appellation", "region", "country",
                             "grapes", "drinkFrom", "drinkUntil", "abv", "classification"]
    static let labelKeys = ["label_box", "label_angle"]
    /// The details whose source the answer gives: the drinking window as one.
    public static let sourceKeys = ["name", "producer", "vintage", "color", "appellation", "region", "country",
                                    "grapes", "drinkWindow", "abv", "classification"]

    public var prompt: String {
        var lines = [
            "This is a photo of a wine label. Read it and return the wine's details.",
            "- name: the wine or cuvée name as printed (for example the château or domaine and cuvée), without the vintage.",
            "- producer: the estate, domaine or house, if different from the name; otherwise \"\".",
            "- vintage: the year on the label, or 0 for a non-vintage wine.",
            "- color: one of red, white, rose, sparkling, dessert, fortified, orange; \"\" if you cannot tell.",
            "- appellation, region, country: from the label, or from your knowledge of the appellation. Country in English.",
            "- grapes: the grape varieties if printed, or if you know them for this wine or appellation; [] if unsure.",
            "- drinkFrom and drinkUntil: a realistic drinking window in years for this wine and vintage, or 0 if unknown.",
            "- abv: the alcohol by volume printed on the label, in percent (for example 13.5), or 0 if not printed.",
            "- classification: a classification printed on the label (for example Grand Cru, Premier Cru, Cru Bourgeois), "
                + "or \"\" if none is printed.",
        ]
        if findsLabel {
            lines += [
                "- label_box: where the whole paper front label is in the photo, including its borders and any logo or "
                    + "picture printed on it, as {\"x\", \"y\", \"w\", \"h\"}: fractions from 0 to 1 of the photo's width "
                    + "and height, from its top-left corner; null if you cannot see it.",
                "- label_angle: the label's slant in degrees, positive when its text runs uphill to the right, 0 when level; "
                    + "null if unknown.",
            ]
        }
        if let expectedLanguage { lines += ExpectedRequest.lines(language: expectedLanguage) }
        lines += [
            "- sources: for each detail, \"label\" if you read it on the label, \"knowledge\" if it comes from what you "
                + "know rather than from the label (an estimate), \"\" if you left it empty. drinkWindow is the source of "
                + "drinkFrom and drinkUntil.",
            "Leave a detail empty (\"\", 0 or []) when you are not sure of it rather than guessing. "
                + "Do not invent a producer or vintage that is not visible.",
        ]
        return lines.joined(separator: "\n")
    }

    public var schema: [String: Any] {
        var properties: [String: Any] = [
            "name": ["type": "string"],
            "producer": ["type": "string"],
            "vintage": ["type": "integer"],
            "color": ["type": "string", "enum": [""] + WineColor.allCases.map(\.rawValue)] as [String: Any],
            "appellation": ["type": "string"],
            "region": ["type": "string"],
            "country": ["type": "string"],
            "grapes": ["type": "array", "items": ["type": "string"]] as [String: Any],
            "drinkFrom": ["type": "integer"],
            "drinkUntil": ["type": "integer"],
            "abv": ["type": "number"],
            "classification": ["type": "string"],
            "sources": Self.sourcesSchema,
        ]
        if findsLabel {
            let box: [String: Any] = [
                "type": "object",
                "additionalProperties": false,
                "required": ["x", "y", "w", "h"],
                "properties": ["x": ["type": "number"], "y": ["type": "number"],
                               "w": ["type": "number"], "h": ["type": "number"]],
            ]
            properties["label_box"] = ["anyOf": [box, ["type": "null"]]]
            properties["label_angle"] = ["anyOf": [["type": "number"], ["type": "null"]]]
        }
        if expectedLanguage != nil { properties["expected"] = ExpectedRequest.schema }
        return [
            "type": "object",
            "additionalProperties": false,
            // Strict schemas want every key listed: the label's place is null when not found.
            "required": Self.detailKeys + ["sources"] + (findsLabel ? Self.labelKeys : [])
                + (expectedLanguage != nil ? ["expected"] : []),
            "properties": properties,
        ]
    }
}

extension LabelRequest {
    /// "label", "knowledge" or "" for each detail.
    static var sourcesSchema: [String: Any] {
        let source: [String: Any] = ["type": "string", "enum": ["", LabelSources.label, LabelSources.knowledge]]
        return [
            "type": "object",
            "additionalProperties": false,
            "required": sourceKeys,
            "properties": Dictionary(uniqueKeysWithValues: sourceKeys.map { ($0, source) }),
        ]
    }
}

/// Where each detail of an AI answer comes from: the details the model gave from its own
/// knowledge rather than read on the label, which the form marks "Estimated by AI" until
/// they are edited. Read the same way by the Kotlin app (`fixtures/ai-label-answer-vectors.json`).
public enum LabelSources {
    public static let label = "label"
    public static let knowledge = "knowledge"

    /// The `LabelRequest.sourceKeys` given as "knowledge" in the answer (a JSON object, or
    /// text holding one). The drinking window is never printed on a label: always an
    /// estimate. Anything else (no sources, another value, a label source) is not marked.
    public static func estimated(inAnswer text: String) -> Set<String> {
        var estimated: Set<String> = ["drinkWindow"]
        guard let start = text.firstIndex(of: "{"), let end = text.lastIndex(of: "}"), start < end,
              let object = try? JSONSerialization.jsonObject(with: Data(text[start...end].utf8)) as? [String: Any],
              let sources = object["sources"] as? [String: Any] else { return estimated }
        for key in LabelRequest.sourceKeys {
            guard let value = sources[key] as? String else { continue }
            if value.trimmingCharacters(in: .whitespaces).lowercased() == knowledge { estimated.insert(key) }
        }
        return estimated
    }
}
