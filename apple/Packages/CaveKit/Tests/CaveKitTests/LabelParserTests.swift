import Testing
@testable import CaveKit

@Suite struct LabelParserTests {
    @Test func readsNameProducerVintageAndAppellation() {
        let lines = [
            LabelLine(text: "DOMAINE ARNAUD", height: 0.05),
            LabelLine(text: "CLOS DES TILLEULS", height: 0.12),
            LabelLine(text: "2017", height: 0.06),
            LabelLine(text: "Pauillac Appellation Pauillac Contrôlée", height: 0.03),
            LabelLine(text: "Mis en bouteille au domaine", height: 0.02),
            LabelLine(text: "13,5% vol 75 cl", height: 0.02),
        ]
        let result = LabelTextParser.parse(lines, currentYear: 2026)
        #expect(result.vintage == 2017)
        #expect(result.producer == "Domaine Arnaud")
        #expect(result.name == "Clos des Tilleuls" || result.name == "Domaine Arnaud")
        #expect(result.appellation?.contains("Pauillac") == true)
    }

    @Test func ignoresImplausibleYearsAndNoise() {
        let lines = [LabelLine(text: "Cuvée 1850", height: 0.1), LabelLine(text: "Since 1789", height: 0.02), LabelLine(text: "Tel 0123", height: 0.01)]
        let result = LabelTextParser.parse(lines, currentYear: 2026)
        #expect(result.vintage == nil)
        #expect(result.name == "Cuvée 1850")
    }

    @Test func emptyInputGivesEmptyResult() {
        #expect(LabelTextParser.parse([]).isEmpty)
    }

    @Test func titleCaseKeepsMixedCase() {
        #expect(LabelTextParser.titleCase("Les Pierres Blanches") == "Les Pierres Blanches")
        #expect(LabelTextParser.titleCase("LES PIERRES DE LA ROCHE") == "Les Pierres de la Roche")
    }
}
