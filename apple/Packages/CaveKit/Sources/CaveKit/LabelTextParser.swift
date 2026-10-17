import Foundation

/// A line of text read on a label, with its relative size (taller = more prominent).
public struct LabelLine: Sendable, Equatable {
    public var text: String
    public var height: Double

    public init(text: String, height: Double) {
        self.text = text
        self.height = height
    }
}

/// What could be read on a label.
public struct LabelScanResult: Sendable, Equatable {
    public var name: String?
    public var producer: String?
    public var vintage: Int?
    public var appellation: String?
    /// The bottle's barcode, if the camera saw one.
    public var barcode: String?

    public init(name: String? = nil, producer: String? = nil, vintage: Int? = nil, appellation: String? = nil,
                barcode: String? = nil) {
        self.name = name; self.producer = producer; self.vintage = vintage; self.appellation = appellation
        self.barcode = barcode
    }

    public var isEmpty: Bool { name == nil && producer == nil && vintage == nil }
}

/// Turns recognized label text into wine fields with simple, predictable rules.
/// Runs entirely on device; nothing is sent anywhere.
public enum LabelTextParser {
    static let producerWords = ["château", "chateau", "domaine", "maison", "clos", "cave", "caves", "weingut",
                                "bodega", "bodegas", "tenuta", "cantina", "quinta", "estate", "winery", "vignoble", "mas"]
    static let appellationWords = ["appellation", "contrôlée", "controlee", "aoc", "aop", "doc", "docg", "igp", "dop", "grand cru", "premier cru", "1er cru"]
    static let noiseWords = ["mis en bouteille", "bottled", "product of", "produit de", "contains sulfites", "contient des sulfites",
                             "alc.", "% vol", "%vol", "www.", "http", "tel", "tél"]

    public static func parse(_ lines: [LabelLine], currentYear: Int = Calendar.current.component(.year, from: .now)) -> LabelScanResult {
        let cleaned = lines
            .map { LabelLine(text: $0.text.trimmingCharacters(in: .whitespacesAndNewlines), height: $0.height) }
            .filter { $0.text.count >= 2 }

        var result = LabelScanResult()

        // Vintage: a plausible year, preferring the most prominent one.
        let years = cleaned.compactMap { line -> (Int, Double)? in
            guard let year = firstYear(in: line.text, currentYear: currentYear) else { return nil }
            return (year, line.height)
        }
        result.vintage = years.max { $0.1 < $1.1 }?.0

        let candidates = cleaned.filter { line in
            let lower = line.text.lowercased()
            if noiseWords.contains(where: { lower.contains($0) }) { return false }
            if isVolumeOrStrength(lower) { return false }
            if line.text.allSatisfy({ $0.isNumber || $0.isWhitespace || $0.isPunctuation }) { return false }
            return line.text.contains(where: \.isLetter)
        }

        let appellationLine = candidates.first { line in
            let lower = line.text.lowercased()
            return appellationWords.contains { lower.contains($0) }
        }
        result.appellation = appellationLine.map { stripAppellationBoilerplate($0.text) }

        let producerLine = candidates.first { line in
            let words = line.text.lowercased().split(whereSeparator: { !$0.isLetter })
            return words.contains { producerWords.contains(String($0)) }
        }
        result.producer = producerLine.map { titleCase(remove(result.vintage, from: $0.text)) }

        // Name: the most prominent remaining line.
        let nameLine = candidates
            .filter { $0.text != producerLine?.text && $0.text != appellationLine?.text }
            .max { $0.height < $1.height }
        if let nameLine {
            result.name = titleCase(remove(result.vintage, from: nameLine.text))
        } else if let producer = result.producer {
            result.name = producer
        }
        if result.name?.isEmpty == true { result.name = nil }
        return result
    }

    static func firstYear(in text: String, currentYear: Int) -> Int? {
        var digits = ""
        for character in text + " " {
            if character.isNumber {
                digits.append(character)
            } else {
                if digits.count == 4, let year = Int(digits), (1900...currentYear + 1).contains(year) { return year }
                digits = ""
            }
        }
        return nil
    }

    /// Removes the detected vintage from a line ("Clos X 2017" → "Clos X").
    static func remove(_ vintage: Int?, from text: String) -> String {
        guard let vintage else { return text }
        return text.split(separator: " ")
            .filter { $0 != String(vintage) }
            .joined(separator: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines.union(.punctuationCharacters))
    }

    /// "13,5% vol", "75 cl", "750ml".
    static func isVolumeOrStrength(_ lower: String) -> Bool {
        let tokens = lower.replacingOccurrences(of: ",", with: ".").split(whereSeparator: { $0 == " " })
        for (index, token) in tokens.enumerated() {
            let unitSuffixes = ["cl", "ml", "%", "l"]
            for unit in unitSuffixes where token.hasSuffix(unit) {
                let number = token.dropLast(unit.count)
                if !number.isEmpty, Double(number) != nil { return true }
            }
            if ["cl", "ml", "%", "vol"].contains(String(token)), index > 0, Double(tokens[index - 1]) != nil { return true }
        }
        return false
    }

    static func stripAppellationBoilerplate(_ text: String) -> String {
        var result = text
        for phrase in ["Appellation", "appellation", "Contrôlée", "Controlée", "Controlee", "contrôlée", "Protégée", "protégée", "d'Origine", "d’Origine", "Origine"] {
            result = result.replacingOccurrences(of: phrase, with: "")
        }
        var words: [Substring] = []
        for word in result.split(separator: " ") where words.last != word { words.append(word) }
        return words.joined(separator: " ").trimmingCharacters(in: .whitespaces)
    }

    /// "CHÂTEAU DES TILLEULS" → "Château des Tilleuls"; mixed case is kept as is.
    static func titleCase(_ text: String) -> String {
        guard text == text.uppercased() else { return text }
        let small: Set<String> = ["de", "des", "du", "la", "le", "les", "et", "di", "del", "della", "von", "of", "the"]
        return text.lowercased().split(separator: " ").enumerated().map { index, word in
            let w = String(word)
            if index > 0, small.contains(w) { return w }
            return w.prefix(1).uppercased() + w.dropFirst()
        }.joined(separator: " ")
    }
}
