import Foundation
import Testing
@testable import CaveKit

/// Drinking windows from shared vectors. The Kotlin core (MaturityVectorTest.kt) reads the
/// same file, `fixtures/maturity-vectors.json`, so the two platforms cannot drift.
@Suite struct MaturityVectorTests {
    struct Vectors: Decodable {
        struct Edge: Decodable {
            var from: Int?
            var until: Int?
            var year: Int
            var maturity: String
        }
        struct Estimate: Decodable {
            var vintage: Int?
            var color: String?
            var from: Int?
            var until: Int?
        }
        struct Source: Decodable {
            var vintage: Int?
            var color: String?
            var from: Int?
            var until: Int?
            var source: String
        }
        struct Move: Decodable {
            var from: Int?
            var until: Int?
            var by: Int
            var untilAfter: Int?
            enum CodingKeys: String, CodingKey {
                case from, until, by
                case untilAfter = "until after"
            }
        }
        struct CellarWine: Decodable {
            var name: String
            var vintage: Int?
            var from: Int?
            var until: Int?
            var inCellar: Int
            var drunk: Int?
        }
        struct Digest: Decodable {
            var year: Int
            var wines: [CellarWine]
            var drinkSoon: Int
            var newlyReady: Int
            var pastPeak: Int
            var firstWine: String?
            var isEmpty: Bool
        }
        struct Plan: Decodable {
            var year: Int
            var month: Int
            var horizon: Int?
            var wines: [CellarWine]
            var years: [[Int]]
            var pastPeak: Int
            var withoutWindow: Int
            var bottlesPerMonth: Double
            var isEmpty: Bool
        }
        var maturity: [Edge]
        var urgencyOrder: [String]
        var estimatedWindow: [Estimate]
        var windowSource: [Source]
        var movingWindowEnd: [Move]
        var digests: [Digest]
        var drinkDownPlans: [Plan]
    }

    static func vectors() throws -> Vectors {
        let file = URL(fileURLWithPath: #filePath).deletingLastPathComponent() // CaveKitTests
            .deletingLastPathComponent().deletingLastPathComponent() // CaveKit
            .deletingLastPathComponent().deletingLastPathComponent() // apple
            .deletingLastPathComponent().appendingPathComponent("fixtures/maturity-vectors.json")
        return try JSONDecoder().decode(Vectors.self, from: Data(contentsOf: file))
    }

    static func color(_ raw: String?) -> WineColor? {
        guard let raw else { return nil }
        let color = WineColor(rawValue: raw)
        #expect(color != nil, "\(raw)")
        return color
    }

    /// A cellar with the wines' bottles: those in the cellar, and those already drunk.
    static func cellar(_ wines: [Vectors.CellarWine]) throws -> Cellar {
        var cellar = Cellar()
        let added = Date(timeIntervalSince1970: 1_767_225_600) // 2026-01-01 UTC
        for item in wines {
            let wine = Wine(name: item.name, vintage: item.vintage, drinkFrom: item.from, drinkUntil: item.until)
            let drunk = item.drunk ?? 0
            let bottles = try cellar.addBottles(of: wine, quantity: item.inCellar + drunk, addedAt: added)
            for bottle in bottles.prefix(drunk) {
                try cellar.drink(bottle.id, tasting: Tasting(date: added))
            }
        }
        return cellar
    }

    @Test func maturityAtTheWindowsEdges() throws {
        let vectors = try Self.vectors()
        #expect(vectors.maturity.count >= 10)
        for edge in vectors.maturity {
            let wine = Wine(name: "W", drinkFrom: edge.from, drinkUntil: edge.until)
            #expect(Maturity.of(wine, year: edge.year).rawValue == edge.maturity,
                    "\(String(describing: edge.from))–\(String(describing: edge.until)) in \(edge.year)")
        }
    }

    @Test func urgencyOrderIsTheSame() throws {
        let order = try Self.vectors().urgencyOrder
        #expect(Maturity.allCases.sorted().map(\.rawValue) == order)
    }

    @Test func estimatedWindowPerStyle() throws {
        let items = try Self.vectors().estimatedWindow
        #expect(!items.isEmpty)
        for item in items {
            let wine = Wine(name: "W", vintage: item.vintage, color: Self.color(item.color))
            let window = wine.estimatedWindow()
            #expect(window?.from == item.from && window?.until == item.until, "\(String(describing: item.color))")
        }
    }

    @Test func whereTheWindowComesFrom() throws {
        let items = try Self.vectors().windowSource
        #expect(!items.isEmpty)
        for item in items {
            let wine = Wine(name: "W", vintage: item.vintage, color: Self.color(item.color),
                            drinkFrom: item.from, drinkUntil: item.until)
            let source: String = switch wine.windowSource {
            case let .estimated(after, best): "estimated \(after) \(best)"
            case .entered: "entered"
            case .missing: "missing"
            }
            #expect(source == item.source, "\(item)")
        }
    }

    @Test func movingTheEndOfTheWindow() throws {
        let items = try Self.vectors().movingWindowEnd
        #expect(!items.isEmpty)
        for item in items {
            let wine = Wine(name: "W", drinkFrom: item.from, drinkUntil: item.until)
            let moved = wine.movingWindowEnd(by: item.by)
            #expect(moved.drinkUntil == item.untilAfter, "\(item)")
            #expect(moved.drinkFrom == item.from)
        }
    }

    @Test func monthlyReminderCounts() throws {
        let digests = try Self.vectors().digests
        #expect(!digests.isEmpty)
        for item in digests {
            let digest = try Self.cellar(item.wines).drinkingDigest(year: item.year)
            #expect(digest.drinkSoon == item.drinkSoon)
            #expect(digest.newlyReady == item.newlyReady)
            #expect(digest.pastPeak == item.pastPeak)
            #expect(digest.firstWine == item.firstWine)
            #expect(digest.isEmpty == item.isEmpty)
        }
    }

    @Test func drinkDownPace() throws {
        let plans = try Self.vectors().drinkDownPlans
        #expect(!plans.isEmpty)
        for item in plans {
            let cellar = try Self.cellar(item.wines)
            let plan = item.horizon.map { cellar.drinkDownPlan(year: item.year, month: item.month, horizon: $0) }
                ?? cellar.drinkDownPlan(year: item.year, month: item.month)
            #expect(plan.years.map { [$0.year, $0.leaving] } == item.years)
            #expect(plan.pastPeak == item.pastPeak)
            #expect(plan.withoutWindow == item.withoutWindow)
            #expect(abs(plan.bottlesPerMonth - item.bottlesPerMonth) < 1e-9, "\(plan.bottlesPerMonth)")
            #expect(plan.isEmpty == item.isEmpty)
        }
    }
}
