import Foundation
import Testing
@testable import CaveKit

/// The files of `fixtures/` shared with the Kotlin core.
enum SharedFixture {
    static func url(_ name: String) -> URL {
        URL(fileURLWithPath: #filePath).deletingLastPathComponent() // CaveKitTests
            .deletingLastPathComponent().deletingLastPathComponent() // CaveKit
            .deletingLastPathComponent().deletingLastPathComponent() // apple
            .deletingLastPathComponent().appendingPathComponent("fixtures/\(name)")
    }

    static func object(_ name: String) throws -> [String: Any] {
        try #require(JSONSerialization.jsonObject(with: Data(contentsOf: url(name))) as? [String: Any])
    }
}

private func double(_ value: Any?) -> Double? { (value as? NSNumber)?.doubleValue }
private func int(_ value: Any?) -> Int? { (value as? NSNumber)?.intValue }

/// The rating conversion from `fixtures/rating-scale-vectors.json`, read the same way by
/// `RatingScaleVectorTest.kt`.
@Suite struct RatingScaleVectorTests {
    let vectors: [String: Any]

    init() throws { vectors = try SharedFixture.object("rating-scale-vectors.json") }

    func rows(_ key: String) -> [Any] { vectors[key] as? [Any] ?? [] }

    func scale(_ name: Any?) throws -> RatingScale { try #require(RatingScale(rawValue: name as? String ?? "")) }

    @Test func normalizedStarsAndStarScores() throws {
        #expect(!rows("normalized").isEmpty && !rows("stars").isEmpty && rows("starScores").count == 5)
        for case let pair as [Any] in rows("normalized") {
            #expect(Score.normalized(double(pair[0])) == double(pair[1]), "normalized \(pair[0])")
        }
        for case let pair as [Any] in rows("stars") {
            #expect(Score.stars(score100: try #require(double(pair[0]))) == int(pair[1]), "stars \(pair[0])")
        }
        for case let pair as [Any] in rows("starScores") {
            #expect(Score.score100(stars: try #require(int(pair[0]))) == double(pair[1]), "star score \(pair[0])")
        }
        #expect(Score.normalized(.nan) == nil && Score.normalized(.infinity) == nil)
    }

    @Test func entered() throws {
        #expect(!rows("entered").isEmpty)
        for case let row as [String: Any] in rows("entered") {
            let value = try #require(double(row["value"]))
            let result = try #require(row["result"] as? [Any])
            let entered = Score.entered(value, on: try scale(row["scale"]))
            #expect(entered?.rating == int(result[0]), "\(row)")
            #expect(entered?.score100 == double(result[1]), "\(row)")
        }
    }

    @Test func shownOnEveryScale() throws {
        #expect(!rows("shown").isEmpty)
        for case let row as [String: Any] in rows("shown") {
            let rating = int(row["rating"]), score = double(row["score100"])
            for scale in RatingScale.allCases {
                #expect(Score.shown(rating: rating, score100: score, on: scale) == double(row[scale.rawValue]), "\(row) \(scale)")
            }
            #expect(Score.isFromStars(rating: rating, score100: score) == (row["fromStars"] as? Bool), "\(row)")
        }
    }

    @Test func keptOnlyWithItsStars() throws {
        #expect(!rows("kept").isEmpty)
        for case let row as [String: Any] in rows("kept") {
            #expect(Score.kept(double(row["score100"]), rating: int(row["rating"])) == double(row["result"]), "\(row)")
        }
    }

    /// The table in docs/SYNC.md.
    @Test func table() throws {
        for case let row as [String: Any] in rows("table") {
            let score = Score.score100(twenty: try #require(double(row["twenty"])))
            #expect(score == double(row["hundred"]))
            #expect(score.map { Score.stars(score100: $0) } == int(row["stars"]))
            #expect(score.map(Score.twenty(score100:)) == double(row["twenty"]))
        }
    }

    @Test func settingARatingKeepsBothInStep() {
        var tasting = Tasting(rating: 3)
        tasting.setRating(17.5, on: .twenty)
        #expect(tasting.rating == 4 && tasting.score100 == 93.75)
        tasting.setRating(95, on: .hundred)
        #expect(tasting.rating == 5 && tasting.score100 == 95)
        // Stars alone clear the score; so does a rating changed directly.
        tasting.setRating(4, on: .stars)
        #expect(tasting.rating == 4 && tasting.score100 == nil)
        tasting.setRating(90, on: .hundred)
        tasting.rating = 2
        #expect(tasting.score100 == nil)
        tasting.setRating(nil, on: .twenty)
        #expect(tasting.rating == nil && tasting.score100 == nil)
        var discovery = Discovery(wineID: UUID(), rating: 1)
        discovery.setRating(16, on: .twenty)
        #expect(discovery.rating == 4 && discovery.score100 == 90)
    }
}

/// The code's vocabulary is `fixtures/tasting-vocabulary.json`, as `VocabularyTest.kt` checks.
@Suite struct VocabularyTests {
    @Test func tablesMatchTheSharedFile() throws {
        let file = try SharedFixture.object("tasting-vocabulary.json")
        func colors(_ value: Any?) -> Set<WineColor> {
            Set((value as? [String] ?? []).compactMap(WineColor.init(rawValue:)))
        }
        let families = try #require(file["aromaFamilies"] as? [[String: Any]])
        #expect(families.count == Vocabulary.aromaFamilies.count)
        for (expected, family) in zip(families, Vocabulary.aromaFamilies) {
            #expect(expected["id"] as? String == family.id)
            let aromas = expected["aromas"] as? [[String: Any]] ?? []
            #expect(aromas.map { $0["id"] as? String } == family.aromas.map(\.id), "\(family.id)")
            #expect(aromas.map { colors($0["colors"]) } == family.aromas.map(\.colors), "\(family.id)")
        }
        let structure = try #require(file["structure"] as? [[String: Any]])
        #expect(structure.map { $0["id"] as? String } == Vocabulary.structure.map(\.id))
        #expect(structure.map { $0["scale"] as? String } == Vocabulary.structure.map(\.scale))
        #expect(structure.map { $0["colors"].map(colors) } == Vocabulary.structure.map(\.colors))
        #expect(file["structureScales"] as? [String: [String]] == Vocabulary.levels)
        #expect(file["intensities"] as? [String] == Vocabulary.intensities)
        let hues = try #require(file["hues"] as? [[String: Any]])
        #expect(hues.map { $0["id"] as? String } == Vocabulary.hues.map(\.id))
        #expect(hues.map { colors($0["colors"]) } == Vocabulary.hues.map(\.colors))
        #expect(file["dosage"] as? [String] == Vocabulary.dosages)
        #expect(file["sweetness"] as? [String] == Vocabulary.sweetnesses)
        #expect(file["ageing"] as? [String] == Vocabulary.ageings)
        #expect(file["farming"] as? [String] == Vocabulary.farmings)
        #expect(file["closure"] as? [String] == Vocabulary.closures)
        #expect(file["classificationSuggestions"] as? [String] == Vocabulary.classificationSuggestions)
    }

    @Test func idsAreUniqueWithinAKind() {
        for kind in Vocabulary.Kind.allCases {
            let ids = Vocabulary.ids(kind)
            #expect(!ids.isEmpty && Set(ids).count == ids.count, "\(kind)")
        }
        #expect((35...60).contains(Vocabulary.aromaIDs.count))
    }

    @Test func offeredByColor() throws {
        let tannin = try #require(Vocabulary.structure.first { $0.id == "tannin" })
        #expect(tannin.isOffered(for: .red) && tannin.isOffered(for: nil))
        #expect(!tannin.isOffered(for: .white) && !tannin.isOffered(for: .sparkling))
        // Set on a white (by the user or another device): still shown.
        #expect(tannin.isOffered(for: .white, value: 2))
        let violet = try #require(Vocabulary.aromaFamilies.flatMap(\.aromas).first { $0.id == "floral.violet" })
        #expect(violet.suits(.red) && !violet.suits(.white) && violet.suits(nil))
        #expect(Vocabulary.family(ofAroma: "floral.violet")?.id == "floral")
        #expect(Vocabulary.family(ofAroma: "future.aroma") == nil)
        #expect(Vocabulary.level(4, of: tannin) == "mediumPlus")
        #expect(Vocabulary.level(0, of: tannin) == nil)
    }
}

/// Reading and writing the guided tasting fields, the precise score and the wine details:
/// `fixtures/tasting-sheet-vectors.json`, read the same way by `TastingSheetTest.kt`.
@Suite struct TastingSheetTests {
    static let wineID = "00000000-0000-0000-0000-0000000000A1"
    static let bottleID = "00000000-0000-0000-0000-0000000000B1"
    static var wine: [String: Any] { ["id": wineID, "name": "Clos Test", "grapes": [String]()] }

    let vectors: [String: Any]

    init() throws { vectors = try SharedFixture.object("tasting-sheet-vectors.json") }

    /// A cellar file holding the record, as the app reads it.
    static func cellarData(wine: [String: Any] = wine, tasting: [String: Any]? = nil,
                           discovery: [String: Any]? = nil) throws -> Data {
        var bottles: [Any] = []
        if let tasting {
            bottles = [bottleID, ["id": bottleID, "wineID": wineID, "status": "consumed", "addedAt": 800_000_000,
                                  "sizeML": 750, "tasting": tasting] as [String: Any]]
        }
        let discoveries: [Any] = discovery.map { [$0["id"] as? String ?? "", $0] } ?? []
        let root: [String: Any] = ["wines": [wineID, wine], "bottles": bottles, "racks": [Any](),
                                   "discoveries": discoveries, "schemaVersion": 2, "settings": ["currency": "EUR"]]
        return try JSONSerialization.data(withJSONObject: root)
    }

    static func read(_ data: Data) throws -> Cellar {
        try JSONFileCellarStore.makeDecoder().decode(Cellar.self, from: data)
    }

    /// The record of a kind, as the cellar writes it back.
    static func written(_ cellar: Cellar, _ kind: String) throws -> NSDictionary {
        let data = try JSONFileCellarStore.makeEncoder().encode(cellar)
        let root = try #require(JSONSerialization.jsonObject(with: data) as? [String: Any])
        switch kind {
        case "wines": return try #require((root["wines"] as? [Any])?.last as? NSDictionary)
        case "discoveries": return try #require((root["discoveries"] as? [Any])?.last as? NSDictionary)
        default:
            let bottle = try #require((root["bottles"] as? [Any])?.last as? [String: Any])
            return try #require(bottle["tasting"] as? NSDictionary)
        }
    }

    func cases(_ key: String) -> [[String: Any]] { vectors[key] as? [[String: Any]] ?? [] }

    @Test func recordsAreReadTheSameWayEverywhere() throws {
        for kind in ["tastings", "wines", "discoveries"] {
            #expect(!cases(kind).isEmpty)
            for item in cases(kind) {
                let input = try #require(item["input"] as? [String: Any])
                let expected = try #require(item["read"] as? NSDictionary)
                let data = switch kind {
                case "tastings": try Self.cellarData(tasting: input)
                case "wines": try Self.cellarData(wine: input)
                default: try Self.cellarData(discovery: input)
                }
                let cellar = try Self.read(data)
                #expect(try Self.written(cellar, kind) == expected, "\(item["about"] ?? "")")
                // What is written reads back the same.
                let again = try Self.read(JSONFileCellarStore.makeEncoder().encode(cellar))
                #expect(again == cellar, "\(item["about"] ?? "")")
            }
        }
    }

    /// A version that knows only the previous keys keeps the new ones and writes them back
    /// unchanged; after its edit, this version reads what the vectors say (a stale score or
    /// grape share dropped, everything else kept).
    @Test func anOlderVersionKeepsTheNewKeys() throws {
        let old = try #require(vectors["oldVersion"] as? [String: Any])
        let keys = try #require(old["keys"] as? [String: [String]])
        for (name, kind) in [("tasting", "tastings"), ("wine", "wines"), ("discovery", "discoveries")] {
            let item = try #require(old[name] as? [String: Any])
            let record = try #require(item["record"] as? [String: Any])
            let known = Set(keys[name] ?? [])
            // The old version's record: the keys it knows, and the others kept as they were.
            let unknown = record.filter { !known.contains($0.key) }
            #expect(!unknown.isEmpty, "\(name) has new keys")
            let decoded = try JSONDecoder().decode([String: JSONValue].self, from: JSONSerialization.data(withJSONObject: record))
            var oldRecord = OldRecord(values: decoded, known: known)
            #expect(Set(oldRecord.unknownFields.values.keys) == Set(unknown.keys))
            // Written back without an edit: unchanged.
            let unchanged = try JSONSerialization.jsonObject(with: JSONEncoder().encode(oldRecord)) as? NSDictionary
            #expect(unchanged == record as NSDictionary, "\(name)")
            // Its edit, then this version reads it.
            let edit = try JSONDecoder().decode([String: JSONValue].self,
                                                from: JSONSerialization.data(withJSONObject: item["edit"] as? [String: Any] ?? [:]))
            for (key, value) in edit { oldRecord.known[key] = value }
            let edited = try #require(JSONSerialization.jsonObject(with: JSONEncoder().encode(oldRecord)) as? [String: Any])
            let data = switch kind {
            case "tastings": try Self.cellarData(tasting: edited)
            case "wines": try Self.cellarData(wine: edited)
            default: try Self.cellarData(discovery: edited)
            }
            let expected = try #require(item["afterEdit"] as? NSDictionary)
            #expect(try Self.written(Self.read(data), kind) == expected, "\(name)")
        }
    }

    @Test func formEditsKeepTheGuidedFields() throws {
        var cellar = SampleData.cellar(year: 2026, now: SampleInterop.now)
        let bottle = try #require(cellar.consumedBottles.first { $0.tasting?.aromas != nil })
        var tasting = try #require(bottle.tasting)
        tasting.note = "Edited"
        try cellar.updateTasting(bottle.id, tasting: tasting)
        let stored = try #require(cellar.bottles[bottle.id]?.tasting)
        #expect(stored.note == "Edited" && stored.aromas == bottle.tasting?.aromas && stored.score100 == 95)
        #expect(stored.acidity == 4 && stored.hue == "ruby")
    }

    @Test func searchFindsTheNewFieldsByTheirLabels() throws {
        let french: [String: String] = ["aroma/fruit.redCherry": "Cerise rouge", "farming/organic": "Agriculture biologique",
                                        "aroma/fruit.raspberry": "Framboise",
                                        "closure/naturalCork": "Bouchon de liège", "ageing/oakBarrel": "Fût de chêne",
                                        "hue/ruby": "Rubis"]
        Vocabulary.labelProvider = { kind, id in french["\(kind.rawValue)/\(id)"] }
        let cellar = SampleData.cellar(year: 2026, now: SampleInterop.now)
        // The guided tasting, by an aroma or its hue.
        #expect(cellar.diary(query: "cerise").count == 1)
        #expect(cellar.diary(query: "rubis").count == 1)
        #expect(cellar.diary(query: "framboise").count == 1)
        #expect(cellar.diary(query: "groseille").isEmpty)
        // The wine, by its details and critics.
        let wines = { (query: String) in cellar.wines.values.filter { $0.matches(query) }.map(\.name) }
        #expect(Set(wines("biologique")) == ["Clos des Tilleuls"])
        #expect(Set(wines("Cru Bourgeois")) == ["Clos des Tilleuls"])
        #expect(Set(wines("liège")) == ["Clos des Tilleuls"])
        #expect(Set(wines("fût")) == ["Clos des Tilleuls"])
        #expect(Set(wines("Concours Pauillac")) == ["Clos des Tilleuls"])
        #expect(Set(wines("Tilleuls")).count == 1)
    }

    @Test func statisticsCountAromasFarmingAndClosure() throws {
        let cellar = SampleData.cellar(year: 2026, now: SampleInterop.now)
        let stats = cellar.statistics(year: 2026, now: SampleInterop.now)
        #expect(stats.topAromas.map(\.label) == ["earth.forestFloor", "floral.violet", "fruit.raspberry",
                                                 "fruit.redCherry", "spice.clove"])
        #expect(stats.topAromas.allSatisfy { $0.count == 1 })
        let flagship = cellar.bottlesInCellar.filter { cellar.wines[$0.wineID]?.farming == "organic" }.count
        #expect(flagship > 0)
        #expect(stats.byFarming == [CellarStatistics.Slice(label: "organic", count: flagship)])
        #expect(stats.byClosure == [CellarStatistics.Slice(label: "naturalCork", count: flagship)])
    }

    @Test func wineDetailsHelpers() throws {
        var wine = Wine(name: "X", grapes: ["Syrah", "Grenache"])
        #expect(!wine.hasMoreDetails)
        wine.grapeShares = ["syrah": 60, "Grenache": 40, "Mourvèdre": 10]
        #expect(wine.grapeShares == ["syrah": 60, "Grenache": 40])
        #expect(wine.share(of: " SYRAH ") == 60)
        // Removing a grape drops its share.
        wine.grapes = ["Syrah"]
        #expect(wine.grapeShares == ["syrah": 60])
        wine.ageing = ["amphora", "amphora"]
        #expect(wine.ageing == ["amphora"] && wine.hasMoreDetails)
        wine.criticScores = []
        #expect(wine.criticScores == nil)
        #expect(CriticScore(source: "A", score: 17.5, scale: 20).value == "17.5/20")
        #expect(CriticScore(source: "A", score: 17.5, scale: 20).score100 == 93.75)
        #expect(CriticScore(source: "A", score: 94, scale: 100).score100 == 94)
        #expect(CriticScore(source: "A", award: " Gold ").value == "Gold")
        #expect(CriticScore(source: "A", score: 8, scale: 10).score100 == nil)
    }

    @Test func theAIRequestAsksForAlcoholAndClassification() {
        let request = LabelRequest(findsLabel: false)
        let required = request.schema["required"] as? [String] ?? []
        #expect(required.contains("abv") && required.contains("classification"))
        #expect(request.prompt.contains("- abv:") && request.prompt.contains("- classification:"))
    }
}

/// A record as a version that knows only `known` keys holds it: those values, and the
/// others kept as unknown fields and written back unchanged (docs/SYNC.md §1).
struct OldRecord: Encodable {
    var known: [String: JSONValue]
    var unknownFields: UnknownFields

    init(values: [String: JSONValue], known keys: Set<String>) {
        known = values.filter { keys.contains($0.key) }
        unknownFields = UnknownFields(values.filter { !keys.contains($0.key) })
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: AnyCodingKey.self)
        for (key, value) in known { try c.encode(value, forKey: AnyCodingKey(key)) }
        for (key, value) in unknownFields.values where known[key] == nil { try c.encode(value, forKey: AnyCodingKey(key)) }
    }
}
