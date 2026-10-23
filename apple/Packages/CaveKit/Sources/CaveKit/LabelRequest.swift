import Foundation

/// What the AI label recognition asks about a label photo: the text sent with it and
/// the JSON schema of the answer, the same for every provider. With `findsLabel`
/// (the studio look on), the same request also asks where the whole front label is
/// in the photo ("label_box" and "label_angle", read by `AILabelPlacement`), so the
/// studio picture can be framed on it. Nothing else is sent either way.
public struct LabelRequest: Sendable, Equatable {
    public var findsLabel: Bool

    public init(findsLabel: Bool) { self.findsLabel = findsLabel }

    static let detailKeys = ["name", "producer", "vintage", "color", "appellation", "region", "country",
                             "grapes", "drinkFrom", "drinkUntil"]
    static let labelKeys = ["label_box", "label_angle"]

    public var prompt: String {
        var lines = [
            "This is a photo of a wine label. Read it and return the wine's details.",
            "- name: the wine or cuvée name as printed (for example the château or domaine and cuvée), without the vintage.",
            "- producer: the estate, domaine or house, if different from the name; otherwise \"\".",
            "- vintage: the year on the label, or 0 for a non-vintage wine.",
            "- color: one of red, white, rose, sparkling, dessert, fortified, orange; \"\" if you cannot tell.",
            "- appellation, region, country: from the label, or from your knowledge of the appellation. Country in English.",
            "- grapes: the grape varieties if printed or typical of the appellation; [] if unsure.",
            "- drinkFrom and drinkUntil: a realistic drinking window in years for this wine and vintage, or 0 if unknown.",
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
        lines.append("Use \"\" or 0 for anything you cannot determine. Do not invent a producer or vintage that is not visible.")
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
        return [
            "type": "object",
            "additionalProperties": false,
            // Strict schemas want every key listed: the label's place is null when not found.
            "required": Self.detailKeys + (findsLabel ? Self.labelKeys : []),
            "properties": properties,
        ]
    }
}
