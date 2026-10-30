import Foundation

/// "Guess the Aromas": the player picks aromas (and, if they like, structure levels) before
/// seeing what to expect, then gets points. Games stay on the device that played: never
/// synced, not in the cellar file, backups or exports (`AromaGameStore`). Scored the same
/// way by the Kotlin core (`fixtures/aroma-game-vectors.json`).
public enum AromaGame {
    /// Aromas a player may pick in one game.
    public static let maxPicks = 6
    public static let exactPoints = 10
    public static let familyPoints = 4
    public static let structurePoints = 5

    /// The score of a game: each picked aroma (the first `maxPicks`, each once) that is
    /// expected gets 10; then each other pick whose family has an expected aroma not found
    /// yet gets 4 (one expected aroma per pick, in the order picked); each structure level
    /// guessed within one level of the expected one gets 5. Accuracy is the points out of
    /// the most possible: 10 per expected aroma and 5 per expected level the player guessed,
    /// as a whole percentage (halves up).
    public static func score(guesses: [String], structure: [String: Int], expected: [String],
                             expectedStructure: [String: Int]) -> AromaGameScore {
        var seen = Set<String>()
        let picks = Array(guesses.filter { !$0.isEmpty && seen.insert($0).inserted }.prefix(maxPicks))
        seen = []
        var remaining = expected.filter { !$0.isEmpty && seen.insert($0).inserted }
        let expectedCount = remaining.count
        var exact: [String] = [], family: [AromaGameScore.FamilyMatch] = [], wrong: [String] = []
        for pick in picks {
            if let index = remaining.firstIndex(of: pick) {
                exact.append(pick)
                remaining.remove(at: index)
            }
        }
        for pick in picks where !exact.contains(pick) {
            if let familyID = Vocabulary.family(ofAroma: pick)?.id,
               let index = remaining.firstIndex(where: { Vocabulary.family(ofAroma: $0)?.id == familyID }) {
                family.append(.init(guess: pick, expected: remaining[index]))
                remaining.remove(at: index)
            } else {
                wrong.append(pick)
            }
        }
        var levels: [AromaGameScore.LevelMatch] = []
        for attribute in Vocabulary.structure.map(\.id) {
            guard let guess = structure[attribute], let target = expectedStructure[attribute] else { continue }
            levels.append(.init(attribute: attribute, guess: guess, expected: target))
        }
        let points = exact.count * exactPoints + family.count * familyPoints
            + levels.filter(\.isClose).count * structurePoints
        let maxPoints = expectedCount * exactPoints + levels.count * structurePoints
        let accuracy = maxPoints > 0 ? (points * 100 + maxPoints / 2) / maxPoints : 0
        return AromaGameScore(points: points, maxPoints: maxPoints, accuracy: accuracy, exact: exact, family: family,
                              missed: remaining, wrong: wrong, levels: levels)
    }
}

public struct AromaGameScore: Equatable, Sendable {
    public struct FamilyMatch: Equatable, Sendable {
        public var guess: String
        public var expected: String
    }

    public struct LevelMatch: Equatable, Sendable {
        public var attribute: String
        public var guess: Int
        public var expected: Int
        /// Within one level: 5 points.
        public var isClose: Bool { abs(guess - expected) <= 1 }
    }

    public var points: Int
    public var maxPoints: Int
    /// 0…100.
    public var accuracy: Int
    /// Picked and expected.
    public var exact: [String]
    /// Picked, of the family of an expected aroma.
    public var family: [FamilyMatch]
    /// Expected, neither picked nor matched by family.
    public var missed: [String]
    /// Picked, no credit.
    public var wrong: [String]
    public var levels: [LevelMatch]
}

/// A game played, kept on this device only.
public struct AromaGameRecord: Codable, Equatable, Identifiable, Sendable {
    public var id: UUID
    public var wineID: UUID
    public var bottleID: UUID?
    public var date: Date
    public var guesses: [String]
    public var structure: [String: Int]
    public var expected: [String]
    public var expectedStructure: [String: Int]
    public var points: Int
    public var maxPoints: Int

    public init(id: UUID = UUID(), wineID: UUID, bottleID: UUID? = nil, date: Date, guesses: [String], structure: [String: Int],
                expected: [String], expectedStructure: [String: Int]) {
        self.id = id; self.wineID = wineID; self.bottleID = bottleID; self.date = date
        self.guesses = guesses; self.structure = structure; self.expected = expected; self.expectedStructure = expectedStructure
        let score = AromaGame.score(guesses: guesses, structure: structure, expected: expected, expectedStructure: expectedStructure)
        points = score.points
        maxPoints = score.maxPoints
    }

    public var score: AromaGameScore {
        AromaGame.score(guesses: guesses, structure: structure, expected: expected, expectedStructure: expectedStructure)
    }
}

/// "My palate": what the games played on this device add up to.
public struct Palate: Equatable, Sendable {
    public enum Level: String, CaseIterable, Sendable {
        case novice, amateur, connoisseur, sommelier

        /// The level reached with these points: 0, 100, 500 and 1500.
        public static func of(points: Int) -> Level {
            switch points {
            case ..<100: .novice
            case ..<500: .amateur
            case ..<1500: .connoisseur
            default: .sommelier
            }
        }

        /// Points needed for the next level, nil at the top.
        public var nextAt: Int? {
            switch self {
            case .novice: 100
            case .amateur: 500
            case .connoisseur: 1500
            case .sommelier: nil
            }
        }
    }

    public var games: Int
    public var points: Int
    public var level: Level
    /// All points out of all possible points, 0…100 (halves up); nil before a game.
    public var accuracy: Int?
    /// Days in a row with a game, ending today or yesterday.
    public var streak: Int
    /// The aroma families found best and worst (exact finds out of times expected), among
    /// those expected at least twice; the weakest only when it differs from the best.
    public var bestFamily: String?
    public var weakestFamily: String?
    /// The latest games, newest first (at most 5).
    public var recent: [AromaGameRecord]

    public init(records: [AromaGameRecord], today: Date, calendar: Calendar = .current) {
        games = records.count
        points = records.reduce(0) { $0 + $1.points }
        level = .of(points: points)
        let possible = records.reduce(0) { $0 + $1.maxPoints }
        accuracy = possible > 0 ? (points * 100 + possible / 2) / possible : nil
        recent = Array(records.sorted { $0.date > $1.date }.prefix(5))
        // Days played, as local calendar days.
        let days = Set(records.map { calendar.startOfDay(for: $0.date) })
        var day = calendar.startOfDay(for: today)
        if !days.contains(day), let yesterday = calendar.date(byAdding: .day, value: -1, to: day) { day = yesterday }
        var streak = 0
        while days.contains(day) {
            streak += 1
            guard let previous = calendar.date(byAdding: .day, value: -1, to: day) else { break }
            day = previous
        }
        self.streak = streak
        // Families: exact finds out of times expected.
        var expectedCount: [String: Int] = [:], found: [String: Int] = [:]
        for record in records {
            let exact = Set(record.score.exact)
            for aroma in Set(record.expected) {
                guard let family = Vocabulary.family(ofAroma: aroma)?.id else { continue }
                expectedCount[family, default: 0] += 1
                if exact.contains(aroma) { found[family, default: 0] += 1 }
            }
        }
        let order = Vocabulary.aromaFamilies.map(\.id)
        let rated = order.compactMap { family -> (id: String, rate: Double, count: Int)? in
            guard let count = expectedCount[family], count >= 2 else { return nil }
            return (family, Double(found[family] ?? 0) / Double(count), count)
        }
        // Higher rate first; then more often expected; then the vocabulary's order.
        let best = rated.min { lhs, rhs in
            lhs.rate != rhs.rate ? lhs.rate > rhs.rate : lhs.count != rhs.count ? lhs.count > rhs.count
                : order.firstIndex(of: lhs.id)! < order.firstIndex(of: rhs.id)!
        }
        let weakest = rated.min { lhs, rhs in
            lhs.rate != rhs.rate ? lhs.rate < rhs.rate : lhs.count != rhs.count ? lhs.count > rhs.count
                : order.firstIndex(of: lhs.id)! < order.firstIndex(of: rhs.id)!
        }
        bestFamily = best?.id
        weakestFamily = weakest.flatMap { $0.id == best?.id ? nil : $0.id }
    }
}

/// Where the games are kept: in this device's defaults, never in the cellar, sync, backups
/// or exports. The latest 500 games.
public struct AromaGameStore: @unchecked Sendable {
    public static let defaultsKey = "aromaGame.games"
    static let limit = 500
    let defaults: UserDefaults

    public init(defaults: UserDefaults = .standard) { self.defaults = defaults }

    public func load() -> [AromaGameRecord] {
        guard let data = defaults.data(forKey: Self.defaultsKey) else { return [] }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return (try? decoder.decode([AromaGameRecord].self, from: data)) ?? []
    }

    public func add(_ record: AromaGameRecord) {
        save(Array((load() + [record]).suffix(Self.limit)))
    }

    public func removeAll() { defaults.removeObject(forKey: Self.defaultsKey) }

    func save(_ records: [AromaGameRecord]) {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        if let data = try? encoder.encode(records) { defaults.set(data, forKey: Self.defaultsKey) }
    }
}
