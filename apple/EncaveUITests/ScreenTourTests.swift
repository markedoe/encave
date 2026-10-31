import XCTest
#if canImport(UIKit)
import UIKit
#endif

/// Walks through every main screen with the sample cellar, keeps a screenshot of each
/// (exported by CI), and checks the layout:
/// - every full-width action button (identifier "action.…") has its icon and text centered,
/// - buttons shown side by side have the same width,
/// - on iPhone, such a pair is centered on the screen.
@MainActor
final class ScreenTourTests: XCTestCase {
    private var app: XCUIApplication!
    private var language = "en"
    private var step = 0

    func testTourInEnglish() throws {
        try tour(language: "en")
    }

    #if os(iOS)
    func testTourInFrench() throws {
        // French strings are longer: this catches truncation and wrapping.
        try tour(language: "fr")
    }

    /// A few screens at a very large text size (Dynamic Type), on iPhone, where
    /// space is tightest.
    func testLargeText() throws {
        language = "xl"
        step = 0
        continueAfterFailure = true
        app = XCUIApplication()
        app.launchArguments = ["-AppleLanguages", "(en)", "-AppleLocale", "en_US",
                               "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityL",
                               "--sample-data"]
        app.launch()
        guard app.horizontalSizeClassIsCompact else { return }
        openSection(0, "cellar")
        snapshot("cellar")
        if let row = require("wine.row", "large text: no wine in the cellar list", timeout: 10) {
            row.press()
            XCTAssertTrue(wineAnchor.waitForExistence(timeout: 5), "large text: the wine page did not open")
            snapshot("wine")
            goBack()
        }
        openSection(1, "racks")
        snapshot("racks")
        // The bottles out of storage at this size: the list and its Put Away buttons.
        if let card = require("racks.notInStorage", "large text: no card for the bottles not in storage", timeout: 5) {
            card.press()
            snapshot("not-in-storage")
            goBack()
        }
        openSection(2, "tonight")
        snapshot("tonight")
        openSection(3, "journal")
        snapshot("journal")
    }

    /// The cellar at the largest text size (AX5): the summary tiles stack, one above the other.
    func testLargestText() throws {
        language = "ax5"
        step = 0
        continueAfterFailure = true
        app = XCUIApplication()
        app.launchArguments = ["-AppleLanguages", "(en)", "-AppleLocale", "en_US",
                               "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL",
                               "--sample-data"]
        app.launch()
        guard app.horizontalSizeClassIsCompact else { return }
        openSection(0, "cellar")
        snapshot("cellar")
        let bottles = element("cellar.stat.bottles"), keep = element("cellar.stat.keep"), soon = element("cellar.stat.soon")
        XCTAssertTrue(bottles.waitForExistence(timeout: 10), "AX5: no bottles figure")
        XCTAssertTrue(keep.exists, "AX5: no \"to keep\" figure")
        XCTAssertTrue(soon.exists, "AX5: no \"to drink soon\" figure")
        if bottles.exists, keep.exists, soon.exists {
            XCTAssertGreaterThanOrEqual(keep.frame.minY, bottles.frame.maxY - 1, "The cellar figures are a list at AX5")
            // One short line a figure: the wines still start on the first screen.
            XCTAssertLessThan(soon.frame.maxY - bottles.frame.minY, app.frame.height / 2, "The cellar figures fill the screen at AX5")
        }
    }
    #endif

    // MARK: Studio look

    #if os(iOS)
    func testStudioPicturesInLight() throws {
        try studioPictures(dark: false)
    }

    func testStudioPicturesInDark() throws {
        try studioPictures(dark: true)
    }

    /// The studio pictures with the French interface: the wine page's text around them.
    func testStudioPicturesInFrench() throws {
        try studioPictures(dark: false, french: true)
    }

    /// The main screens in dark mode, on iPhone.
    func testDarkInEnglish() throws {
        try darkScreens(french: false)
    }

    func testDarkInFrench() throws {
        try darkScreens(french: true)
    }

    /// Cellar, a wine, Storage and Tonight in dark mode (`--dark`), on iPhone only: a short
    /// pass, the full tour runs in light mode.
    private func darkScreens(french: Bool) throws {
        language = french ? "dark-fr" : "dark-en"
        step = 0
        continueAfterFailure = true
        app = XCUIApplication()
        app.launchArguments = ["-AppleLanguages", french ? "(fr)" : "(en)", "-AppleLocale", french ? "fr_FR" : "en_US",
                               "--dark", "--sample-data"]
        app.launch()
        guard app.horizontalSizeClassIsCompact else { return }
        openSection(0, "cellar")
        snapshot("cellar")
        if let row = require("wine.row", "dark: no wine in the cellar list", timeout: 10) {
            row.press()
            XCTAssertTrue(wineAnchor.waitForExistence(timeout: 5), "dark: the wine page did not open")
            snapshot("wine")
            goBack()
        }
        openSection(1, "racks")
        snapshot("racks")
        openSection(2, "tonight")
        snapshot("tonight")
    }

    /// The first wines of the sample cellar, whose label photos are drawn by the app
    /// (a tilted bottle on a busy counter, a close-up of a label, a bottle lying in a
    /// fridge, a bottle on a table): the list and each wine's page.
    private func studioPictures(dark: Bool, french: Bool = false) throws {
        language = french ? "studio-fr" : dark ? "studio-dark" : "studio"
        step = 0
        continueAfterFailure = true
        app = XCUIApplication()
        app.launchArguments = ["--sample-data", "-AppleLanguages", french ? "(fr)" : "(en)",
                               "-AppleLocale", french ? "fr_FR" : "en_US"] + (dark ? ["--dark"] : [])
        app.launch()
        openSection(0, "cellar")
        // Time for the studio pictures to be made the first time.
        sleep(8)
        snapshot("cellar")
        let rows = app.descendants(matching: .any).matching(identifier: "wine.row")
        XCTAssertTrue(rows.firstMatch.waitForExistence(timeout: 10), "studio: no wine in the cellar list")
        var opened = 0
        for index in 0..<4 {
            let row = rows.element(boundBy: index)
            guard row.waitForExistence(timeout: 5), row.isHittable else { break }
            row.press()
            XCTAssertTrue(wineAnchor.waitForExistence(timeout: 5), "studio: wine \(index + 1) did not open")
            opened += 1
            // Time for the wine's studio picture to be drawn.
            sleep(3)
            snapshot("wine-\(index + 1)")
            goBack()
        }
        XCTAssertGreaterThan(opened, 0, "studio: no wine page opened")
    }
    #endif

    // MARK: Tour

    private func tour(language: String) throws {
        self.language = language
        step = 0
        continueAfterFailure = true
        app = XCUIApplication()
        // --sample-data last: on the Mac, AppKit takes "--sample-data -AppleLanguages" for
        // a setting and opens the word after it as a document, without a window.
        // --sample-ai: made-up AI connections and models in Settings, with no key.
        app.launchArguments = ["-AppleLanguages", "(\(language))",
                               "-AppleLocale", language == "fr" ? "fr_FR" : "en_US", "--sample-ai", "--sample-data"]
        app.launch()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 20))
        #if os(macOS)
        app.activate()
        sleep(3)
        snapshot("launch")
        if !app.windows.firstMatch.waitForExistence(timeout: 20) {
            XCTFail("The main window opens")
            print(app.debugDescription)
        }
        #endif

        // Cellar, a wine, its drink sheet, adding bottles.
        openSection(0, "cellar")
        snapshot("cellar")
        checkCellarFigures()
        checkSelection()
        checkTable()
        #if os(iOS)
        // The swipe actions on a wine ("Drink" on the leading edge). iPhone only: on iPad
        // the revealed action can cover the narrow list column.
        if app.horizontalSizeClassIsCompact,
           require("wine.row", "cellar: no wine to swipe", timeout: 10) != nil, element("wine.row").isHittable {
            element("wine.row").swipeRight()
            snapshot("cellar-swipe")
            // Close the actions again; the revealed button can cover the row on iPad.
            let row = element("wine.row")
            if row.isHittable { row.swipeLeft() } else { app.swipeLeft() }
            sleep(1)
        }
        #endif
        if element("wine.row").waitForExistence(timeout: 10) {
            element("wine.row").press()
            snapshot("wine")
            checkPair("action.wine.drink", "action.wine.add")
            if let drink = require("action.wine.drink", "wine: no Drink button") {
                drink.press()
                snapshot("drink-sheet")
                closeSheet()
            }
            checkExplore()
            goBack()
            checkCriticScores()
        } else {
            XCTFail("No wine in the sample cellar list")
        }
        if let add = requireControl("cellar.add", titles: ["Add Bottle", "Ajouter une bouteille"], "cellar: no Add button") {
            add.press()
            snapshot("add-bottle")
            closeSheet()
        }

        // Storage: the list, every rack, fridge and set of bins, the editor.
        openSection(1, "racks")
        snapshot("racks")
        checkNotInStorage()
        // The sample cellar has one rack of every kind: open each one.
        for index in 0..<8 {
            let card = element("rack.card.\(index)")
            scrollIntoView(card)
            guard card.exists else {
                XCTFail("Rack \(index + 1) of the sample cellar is missing")
                continue
            }
            card.press()
            snapshot("rack-\(index + 1)")
            checkRackFits(screen: "rack \(index + 1)")
            if index == 0 { exploreMainRack() }
            if index == 7 { exploreHeadToTailFridge() }
            if index == 1 {
                // The cabinet holds two bottles per position: tapping one lists both.
                let cell = element("rack.cell.0-0")
                if cell.waitForExistence(timeout: 5) {
                    cell.press()
                    snapshot("rack-place-sheet")
                    closeSheet()
                }
            }
            goBack()
        }
        if let add = requireControl("racks.add", titles: ["Add Storage", "Ajouter un rangement"], "storage: no Add button") {
            // A new storage, step by step: its type, its settings, its name.
            add.press()
            snapshot("rack-wizard-1")
            for step in 2...3 {
                let next = element("wizard.next")
                guard next.waitForExistence(timeout: 5) else { break }
                next.press()
                snapshot("rack-wizard-\(step)")
            }
            #if !os(macOS)
            // Cancel is on the first step only.
            for _ in 0..<2 where !element("sheet.cancel").exists { goBack() }
            #endif
            closeSheet()
        }
        if let add = requireControl("racks.add", titles: ["Add Storage", "Ajouter un rangement"], "storage: no Add button") {
            // A diamond rack whose top and left edge triangles hold bottles, drawn by the wizard.
            add.press()
            if let next = require("wizard.next", "wizard: no Next button") {
                next.press()
                // The layout's option, the one that can be tapped: a rack card of the same name
                // stays behind the sheet on iPad.
                let titles = ["Diamond bins", "Cases en losange"]
                let matches = app.descendants(matching: .any)
                    .matching(NSPredicate(format: "identifier == %@ OR label IN %@", "wizard.layout.diamond", titles))
                _ = matches.firstMatch.waitForExistence(timeout: 5)
                // Below the preview, out of view in the iPad sheet: scrolled to first.
                var diamond = matches.allElementsBoundByIndex.first { $0.isHittable }
                for _ in 0..<8 where diamond == nil {
                    #if os(iOS)
                    swipeContentUp()
                    #else
                    app.scroll(byDeltaX: 0, deltaY: -300)
                    #endif
                    diamond = matches.allElementsBoundByIndex.first { $0.isHittable }
                }
                if let diamond {
                    diamond.press()
                    for edge in ["top", "left"] { turnOn(element("rack.edges.\(edge)"), "wizard: \(edge) triangles") }
                    snapshot("rack-wizard-diamond-switches")
                    scrollToTop()
                    snapshot("rack-wizard-diamond-triangles")
                } else {
                    XCTFail("wizard: no diamond layout")
                }
            }
            #if !os(macOS)
            for _ in 0..<2 where !element("sheet.cancel").exists { goBack() }
            #endif
            closeSheet()
        }

        // Tonight.
        openSection(2, "tonight")
        snapshot("tonight")
        checkPair("action.tonight.drink", "action.tonight.next")
        askTheCellar()

        // Journal: the diary, a filter, and the two ways to add a tasting.
        openSection(3, "journal")
        snapshot("journal")
        checkJournal()

        // What to expect, the aroma game, the tasting compared, and My palate. Last, as it
        // opens a bottle of the sample cellar.
        checkWhatToExpect()

        #if os(iOS)
        // Settings and help (on the Mac they are in the app menu and the Help menu).
        openSection(0, "cellar")
        if let settings = requireControl("cellar.settings", titles: ["Settings", "Réglages"], "cellar: no Settings button") {
            settings.press()
            snapshot("settings")
            if let help = require("settings.help", "settings: no Help entry") {
                help.press()
                snapshot("help")
                goBack()
            }
            // In the order of Settings, top to bottom: the tour only scrolls down.
            checkDevices()
            checkAI()
            checkPreviousVersions()
            closeSheet()
        }
        #endif
    }

    // MARK: Ask your cellar (devices with the on-device model only)

    private func askTheCellar() {
        let ask = element("tonight.ask")
        scrollIntoView(ask)
        guard ask.exists, ask.isHittable else { return }
        ask.press()
        let field = element("ask.question")
        guard field.waitForExistence(timeout: 5) else { goBack(); return }
        field.press()
        field.typeText(language == "fr" ? "Quelque chose de léger pour une ratatouille\n" : "Something light for a ratatouille\n")
        // The model answers on the device; it can take a while the first time.
        // The answer itself, not the footer that also says "Answered".
        let answer = element("ask.answer")
        if answer.waitForExistence(timeout: 60) {
            sleep(1)
            snapshot("ask-answer")
        } else {
            // No shot: it would show "No answer this time" or a spinner, not the feature.
            print("ask your cellar: no answer within 60 s")
        }
        goBack()
    }

    // MARK: The cellar as a table (iPad and Mac)

    private func checkTable() {
        let layout = element("cellar.layout")
        if layout.waitForExistence(timeout: 3), layout.buttons.count > 1 {
            layout.buttons.element(boundBy: 1).press()
        } else {
            // iPad: in the Sort and Filter menu of the list. iPhone has no table, in any
            // orientation: the app decides by device, so does the tour (the app's frame can be
            // misread right after a relaunch).
            guard app.isPad else { return }
            let sort = element("cellar.sort")
            guard sort.exists, sort.isHittable else { return }
            // The menu item, by identifier or by its title (menus do not always carry identifiers);
            // the menu is opened again once if the first press came too early.
            func tableItem() -> XCUIElement? {
                menuItem("cellar.layout.table", titles: ["Show as Table", "Afficher en tableau"])
            }
            sort.press()
            var found = tableItem()
            if found == nil, sort.exists, sort.isHittable {
                sort.press()
                found = tableItem()
            }
            guard let table = found else {
                XCTFail("cellar: Show as Table is missing from the Sort and Filter menu")
                // A menu left open blocks every later tap: start the app again.
                app.terminate()
                app.launch()
                openSection(0, "cellar")
                return
            }
            table.press()
        }
        sleep(1)
        snapshot("cellar-table")
        // Back to the list, which the rest of the tour expects.
        let back = element("cellar.layout")
        if back.waitForExistence(timeout: 3), back.buttons.count > 1 {
            back.buttons.element(boundBy: 0).press()
        } else {
            XCTFail("cellar table: the List/Table switch is missing in the table")
        }
        XCTAssertTrue(element("wine.row").waitForExistence(timeout: 5), "cellar: the list did not come back after the table")
    }

    // MARK: Journal

    /// The diary mixes both kinds of entries; a filter keeps one; "+" asks where the wine was drunk.
    private func checkJournal() {
        XCTAssertTrue(cellarTasting.waitForExistence(timeout: 5), "journal: no tasting from the cellar")
        XCTAssertTrue(element("journal.row.elsewhere").exists, "journal: no wine tasted elsewhere")
        let elsewhere = element("journal.filter.elsewhere")
        if !elsewhere.waitForExistence(timeout: 3) {
            XCTFail("journal: no filter chips")
        } else if elsewhere.isHittable {
            elsewhere.press()
            XCTAssertTrue(waitUntil { !cellarTasting.exists }, "journal: the filter still shows bottles from the cellar")
            snapshot("journal-tasted-elsewhere")
            element("journal.filter.all").press()
            XCTAssertTrue(cellarTasting.waitForExistence(timeout: 5), "journal: All did not bring back the bottles from the cellar")
        } else {
            // The chips scroll sideways: at large sizes this one can be off screen.
            print("journal: the Tasted elsewhere chip is off screen")
        }
        #if os(iOS)
        checkGuidedTasting()
        // A tasting from the cellar shows its wine and opens it on the Journal's stack.
        let tasting = cellarTasting
        if tasting.isHittable {
            tasting.press()
            let wine = element("tasting.wine")
            if wine.waitForExistence(timeout: 3) {
                wine.press()
                XCTAssertTrue(element("action.wine.add").waitForExistence(timeout: 5), "journal: Show Wine did not open the wine")
                goBack()
            } else {
                XCTFail("journal: the tasting does not show its wine")
                closeSheet()
            }
            XCTAssertTrue(cellarTasting.waitForExistence(timeout: 5), "journal: not back from the wine")
        }
        #endif
        // From the cellar: the wines to choose from.
        if openAddMenu(), pressMenuItem("journal.add.cellar") {
            snapshot("journal-open-bottle")
            XCTAssertTrue(element("drink.pick.row").waitForExistence(timeout: 3), "journal: no bottle to choose")
            closeSheet()
        }
        // Somewhere else: the editor of a wine tasted elsewhere.
        if openAddMenu() {
            snapshot("journal-add-menu")
            if pressMenuItem("journal.add.elsewhere") {
                snapshot("journal-tasted-elsewhere-editor")
                closeSheet()
            }
        }
    }

    #if os(iOS)
    /// The newest tasting from the cellar (Vieilles Vignes in the sample, a full guided
    /// tasting scored out of 100): its More details open, appearance and aromas first, then
    /// the structure.
    private func checkGuidedTasting() {
        let tasting = cellarTasting
        guard tasting.waitForExistence(timeout: 5), tasting.isHittable else {
            XCTFail("journal: no tasting from the cellar to open")
            return
        }
        tasting.press()
        // The guided sheet is at the end of the form, which builds its rows only as they come
        // into view: scroll down to it. A tasting with guided notes opens it by itself.
        let intensity = element("tasting.intensity")
        // Its title comes before its rows: scroll on until the rows show, or to the end.
        var tries = 0
        while !intensity.exists, tries < 8 {
            swipeContentUp()
            tries += 1
        }
        if !intensity.exists {
            // By identifier, or by title: a disclosure's label does not always carry it.
            guard let more = requireControl("tasting.more", titles: ["More details", "Plus de détails"],
                                            "tasting: no More details") else {
                closeSheet()
                return
            }
            scrollIntoView(more)
            more.press()
        }
        if intensity.waitForExistence(timeout: 3) {
            scrollIntoView(intensity)
            swipeContentUp()
            snapshot("tasting-guided")
            let length = element("tasting.level.length")
            scrollIntoView(length)
            snapshot("tasting-guided-structure")
        } else {
            XCTFail("tasting: More details did not open the guided sheet")
        }
        // Nothing was changed: Cancel closes without asking.
        closeSheet()
        XCTAssertTrue(cellarTasting.waitForExistence(timeout: 5), "tasting: the guided sheet did not close")
    }
    #endif

    // MARK: Critic scores

    /// The wine of the sample cellar with critics' scores and every detail (Clos des
    /// Tilleuls, second by name): its page scrolled to the scores.
    private func checkCriticScores() {
        let rows = app.descendants(matching: .any).matching(identifier: "wine.row")
        let row = rows.element(boundBy: 1)
        guard row.waitForExistence(timeout: 5), row.isHittable else {
            XCTFail("cellar: no second wine in the list")
            return
        }
        row.press()
        guard wineAnchor.waitForExistence(timeout: 5) else {
            XCTFail("critic scores: the wine page did not open")
            return
        }
        let critic = element("wine.critic")
        scrollIntoView(critic)
        if critic.exists {
            // The add button under the scores too.
            scrollIntoView(element("wine.critics.add"))
            snapshot("wine-critic-scores")
        } else if app.horizontalSizeClassIsCompact {
            XCTFail("critic scores: the second wine of the sample cellar shows no critic's score")
        } else {
            // The detail column can be out of reach of the scroll on some layouts, as for Explore.
            print("critic scores: not reached on this layout")
        }
        goBack()
    }

    private func openAddMenu() -> Bool {
        let add = element("journal.add")
        guard add.waitForExistence(timeout: 3), add.isHittable else {
            XCTFail("journal: no + button")
            return false
        }
        add.press()
        sleep(1)
        return true
    }

    private func pressMenuItem(_ identifier: String) -> Bool {
        let item = element(identifier)
        guard item.waitForExistence(timeout: 3) else {
            XCTFail("journal: no menu item \(identifier)")
            #if os(macOS)
            app.typeKeyEscapeIfMac()
            #else
            // Away from the menu, which opens under the button at the top right.
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.2, dy: 0.6)).tap()
            #endif
            return false
        }
        item.press()
        sleep(1)
        return true
    }

    // MARK: Selecting several wines

    private func checkSelection() {
        let select = element("cellar.select")
        guard select.waitForExistence(timeout: 3), select.isHittable else {
            XCTFail("cellar: no Select button")
            return
        }
        select.press()
        let row = element("wine.select.row")
        if row.waitForExistence(timeout: 3) {
            row.press()
            snapshot("cellar-select")
            // The actions must be above the tab bar (iPhone) and open.
            let more = element("cellar.select.more")
            if more.waitForExistence(timeout: 2), more.isHittable {
                more.press()
                sleep(1)
                // By identifier or title (menus do not always carry identifiers); opened again
                // once if the first press came too early.
                let titles = ["Delete Wines…", "Supprimer les vins…"]
                var delete = menuItem("cellar.select.delete", titles: titles)
                if delete == nil, more.exists, more.isHittable {
                    more.press()
                    sleep(1)
                    delete = menuItem("cellar.select.delete", titles: titles)
                }
                XCTAssertNotNil(delete, "cellar: More has no Delete")
                snapshot("cellar-select-more")
                #if os(iOS)
                // XCUITest on iOS leaves this menu over the screen after closing it
                // (Done and the rows can no longer be tapped): start the app again.
                app.terminate()
                app.launch()
                openSection(0, "cellar")
                return
                #else
                dismissMenu()
                #endif
            } else {
                XCTFail("cellar: the More button of the selection cannot be pressed")
            }
        } else {
            XCTFail("cellar: no wines to select")
        }
        // Leave the selection: the rest of the tour needs the list and the tab bar back.
        for _ in 0..<3 {
            let done = element("cellar.select.done")
            if done.waitForExistence(timeout: 2), done.isHittable { done.press() }
            sleep(1)
            if element("cellar.select").waitForExistence(timeout: 2) { return }
        }
        XCTFail("cellar: the selection did not end")
    }

    // MARK: Devices

    /// Settings › AI: the connections, and the models in order (made-up ones, no key).
    private func checkAI() {
        for (identifier, name) in [("settings.ai.connections", "ai-connections"), ("settings.ai.models", "ai-models")] {
            let entry = element(identifier)
            scrollIntoView(entry)
            guard entry.exists, entry.isHittable else {
                XCTFail("settings: no \(identifier) entry")
                continue
            }
            entry.press()
            snapshot(name)
            // Back is the leftmost bar button: the Models screen also has Edit and Add.
            let leftmost = app.navigationBars.buttons.allElementsBoundByIndex
                .filter { $0.exists && $0.isHittable }
                .min { $0.frame.minX < $1.frame.minX }
            if let leftmost { leftmost.press() } else { goBack() }
            sleep(1)
            XCTAssertTrue(element(identifier).waitForExistence(timeout: 5), "settings: not back from \(name)")
        }
    }

    /// Settings › Devices: inviting shows a code and a QR code; joining asks for the code.
    private func checkDevices() {
        let devices = element("settings.devices")
        scrollIntoView(devices)
        guard devices.exists, devices.isHittable else {
            XCTFail("settings: no Devices entry")
            return
        }
        devices.press()
        snapshot("devices")
        let add = element("devices.add")
        if add.waitForExistence(timeout: 3) {
            add.press()
            XCTAssertTrue(element("devices.code").waitForExistence(timeout: 3), "devices: no code to show")
            snapshot("devices-invite")
            closeSheet()
        } else {
            XCTFail("devices: no Add a Device button")
        }
        let join = element("devices.join")
        if join.waitForExistence(timeout: 3) {
            join.press()
            XCTAssertTrue(element("devices.codeField").waitForExistence(timeout: 3), "devices: no code field")
            snapshot("devices-join")
            closeSheet()
        }
        goBack()
    }

    // MARK: Previous versions

    /// Settings › Your data › Previous Versions (sample versions), and a version's preview.
    private func checkPreviousVersions() {
        let versions = element("settings.versions")
        scrollIntoView(versions)
        guard versions.exists, versions.isHittable else {
            XCTFail("settings: no Previous Versions entry")
            return
        }
        snapshot("settings-backup")
        versions.press()
        let row = element("versions.row")
        guard row.waitForExistence(timeout: 5) else {
            XCTFail("previous versions: no version listed")
            goBack()
            return
        }
        snapshot("previous-versions")
        row.press()
        let replace = element("restore.replace")
        // The buttons are at the bottom of the form: not there until scrolled to on iPhone.
        if element("restore.preview").waitForExistence(timeout: 5) {
            snapshot("restore-preview")
            scrollIntoView(replace)
            // Down to the last button, so the capture shows both actions.
            #if os(iOS)
            swipeContentUp()
            #else
            app.scroll(byDeltaX: 0, deltaY: -300)
            #endif
            XCTAssertTrue(replace.exists, "restore preview: no Replace This Cellar button")
            snapshot("restore-actions")
            if replace.isHittable {
                replace.press()
                sleep(1)
                snapshot("restore-replace-confirm")
                #if os(iOS)
                // The last step on iPhone and iPad. XCUITest leaves this confirmation
                // over the screen after closing it: start the app again, untouched.
                app.terminate()
                app.launch()
                return
                #else
                // Cancel: the sample cellar stays for the rest of the tour.
                dismissMenu()
                #endif
            }
        } else {
            XCTFail("previous versions: the preview did not open")
        }
        goBack()
        goBack()
    }

    // MARK: Cellar

    /// A wine's page scrolled to what to expect; the aroma game played; the tasting sheet with
    /// what to expect shown, and the comparison after Drink; My palate in Insights.
    private func checkWhatToExpect() {
        openSection(0, "cellar")
        guard let row = require("wine.row", "what to expect: no wine", timeout: 10) else { return }
        row.press()
        guard wineAnchor.waitForExistence(timeout: 5) else {
            XCTFail("what to expect: the wine page did not open")
            return
        }
        // Below the bottles, the window and serving: scrolled until Guess the Aromas shows,
        // so the section is on screen and Drink, at the top, is off it.
        guard let play = reach("wine.aromaGame") else {
            snapshot("wine-expected-missing")
            XCTFail("wine: no Guess the Aromas button")
            goBack()
            return
        }
        snapshot("wine-expected")
        play.press()
        // The first aromas on screen: enough for a score, without scrolling the whole list.
        let chips = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "game.aroma."))
        _ = chips.firstMatch.waitForExistence(timeout: 5)
        var picked = 0
        for index in 0..<min(chips.count, 8) where picked < 3 {
            let chip = chips.element(boundBy: index)
            if chip.isHittable {
                chip.press()
                picked += 1
            }
        }
        if let reveal = reach("game.reveal") {
            reveal.press()
            XCTAssertTrue(element("game.points").waitForExistence(timeout: 5), "game: no score after Reveal")
            snapshot("game-result")
        } else {
            XCTFail("game: no Reveal button")
        }
        if let done = require("game.done", "game: no Done button") { done.press() }
        sleep(1)
        // The tasting sheet: More details, then Show What to Expect.
        guard let drink = reach("action.wine.drink", down: true) else {
            XCTFail("wine: no Drink button to reach the tasting sheet")
            goBack()
            return
        }
        drink.press()
        reach("tasting.more")?.press()
        if let show = reach("tasting.showExpected") {
            show.press()
            snapshot("tasting-expected")
        } else {
            XCTFail("tasting sheet: no Show What to Expect")
        }
        let aroma = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "tasting.aroma.")).firstMatch
        scrollIntoView(aroma)
        if aroma.exists, aroma.isHittable { aroma.press() }
        if let confirm = require("drink.confirm", "tasting sheet: no Drink button") {
            confirm.press()
            if element("comparison.summary").waitForExistence(timeout: 5) {
                snapshot("tasting-comparison")
                element("comparison.done").press()
            } else {
                XCTFail("tasting sheet: no comparison after Drink")
                closeSheet()
            }
        }
        sleep(1)
        goBack()
        // My palate, in Insights (made-up games of the sample cellar, or the one just played).
        openSection(0, "cellar")
        let bottles = element("cellar.stat.bottles")
        if bottles.waitForExistence(timeout: 5), bottles.isHittable {
            bottles.press()
            if reach("palate.card") != nil || reach("palate.level") != nil {
                snapshot("my-palate")
            } else {
                XCTFail("insights: no My palate card")
            }
            closeSheet()
        }
    }

    /// Scrolls (down the content, or back up with `down`) until the element can be tapped.
    @discardableResult
    private func reach(_ identifier: String, down: Bool = false) -> XCUIElement? {
        let item = element(identifier)
        for _ in 0..<15 where !(item.exists && item.isHittable) {
            #if os(iOS)
            // A drag of a third of the screen, not a fling, which can carry the element past the
            // screen between two checks.
            let from = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: down ? 0.3 : 0.7))
            from.press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: down ? 0.7 : 0.3)),
                       withVelocity: .default, thenHoldForDuration: 0.1)
            #else
            app.scroll(byDeltaX: 0, deltaY: down ? 300 : -300)
            #endif
        }
        return item.exists && item.isHittable ? item : nil
    }

    /// The figures at the top of the cellar: "to drink soon" filters, "bottles" opens insights.
    private func checkCellarFigures() {
        let rows = app.descendants(matching: .any).matching(identifier: "wine.row")
        let soon = element("cellar.stat.soon")
        guard soon.waitForExistence(timeout: 5), soon.isHittable else {
            XCTFail("cellar: no \"to drink soon\" figure")
            return
        }
        let all = rows.count
        soon.press()
        XCTAssertTrue(waitUntil { rows.count < all }, "cellar: \"to drink soon\" did not filter the list")
        snapshot("cellar-drink-soon")
        soon.press()
        XCTAssertTrue(waitUntil { rows.count == all }, "cellar: tapping \"to drink soon\" again did not show all wines")

        // "To keep" filters as well, and a second tap shows all again.
        let keep = element("cellar.stat.keep")
        if keep.isHittable {
            keep.press()
            XCTAssertTrue(waitUntil { rows.count < all }, "cellar: \"to keep\" did not filter the list")
            keep.press()
            XCTAssertTrue(waitUntil { rows.count == all }, "cellar: tapping \"to keep\" again did not show all wines")
        } else {
            XCTFail("cellar: no \"to keep\" figure")
        }

        let bottles = element("cellar.stat.bottles")
        if bottles.isHittable {
            bottles.press()
            XCTAssertTrue(element("sheet.done").waitForExistence(timeout: 3), "cellar: \"bottles\" did not open insights")
            snapshot("cellar-insights")
            closeSheet()
        }
    }

    // MARK: Racks

    /// The main rack: a bottle's card, the wine key, and pinch to zoom.
    private func exploreMainRack() {
        // The first place holds one bottle: tapping it shows its card with Drink and Move.
        let cell = element("rack.cell.0-0")
        guard cell.waitForExistence(timeout: 5) else {
            XCTFail("main rack: no first place")
            return
        }
        checkEmptyPlacesAreQuiet()
        checkFreePlaceChoice()
        cell.press()
        XCTAssertTrue(element("rack.selection").waitForExistence(timeout: 3), "main rack: no card for the tapped bottle")
        snapshot("rack-bottle-card")
        // From the card to the wine page and back.
        let winePage = element("rack.selection.wine")
        if winePage.exists, winePage.isHittable {
            winePage.press()
            XCTAssertTrue(element("action.wine.drink").waitForExistence(timeout: 3) || element("action.wine.add").exists,
                          "main rack: the wine page did not open from the card")
            snapshot("rack-wine-page")
            goBack()
        } else {
            XCTFail("main rack: no wine page button on the card")
        }
        let close = element("rack.selection.close")
        if close.waitForExistence(timeout: 2), close.isHittable { close.press() }

        #if os(iOS)
        // The bottle list: choosing a bottle closes it and shows that bottle's card.
        let list = element("rack.list")
        if list.waitForExistence(timeout: 2), list.isHittable {
            list.press()
            let row = element("rack.list.row")
            if row.waitForExistence(timeout: 3) {
                snapshot("rack-bottle-list")
                row.press()
                XCTAssertTrue(element("rack.selection").waitForExistence(timeout: 3),
                              "main rack: choosing a bottle in the list did not show it")
                let close = element("rack.selection.close")
                if close.waitForExistence(timeout: 2), close.isHittable { close.press() }
            } else {
                XCTFail("main rack: the bottle list is empty")
                closeSheet()
            }
        } else {
            XCTFail("main rack: no bottle list button")
        }
        #endif

        // The key: choosing a wine highlights its bottles.
        let chip = element("rack.key.wine")
        if chip.waitForExistence(timeout: 3), chip.isHittable {
            chip.press()
            snapshot("rack-wine-focus")
            chip.press()
        } else {
            XCTFail("main rack: no wine in the key")
        }

        // Checking the rack: tap the bottles found.
        // By identifier or title; a narrow toolbar may also move it into its overflow menu.
        if let audit = toolbarAction("rack.audit", titles: ["Check This Storage", "Vérifier ce rangement"]) {
            audit.press()
            cell.press()
            snapshot("rack-audit")
            let cancel = element("sheet.cancel")
            if cancel.waitForExistence(timeout: 2) { cancel.press() }
        } else {
            XCTFail("main rack: no Check This Storage button")
        }

        #if os(iOS)
        // Pinch to zoom: places get larger and stay readable.
        let canvas = element("rack.canvas")
        guard canvas.waitForExistence(timeout: 3) else {
            XCTFail("main rack: no zoomable canvas")
            return
        }
        let before = cell.frame.width
        canvas.pinch(withScale: 2.2, velocity: 1.5)
        sleep(2)
        let after = cell.frame.width
        let zoom = (canvas.value as? String) ?? "?"
        XCTAssertGreaterThan(after, before * 1.3,
                             "main rack: pinching did not enlarge places (\(before) → \(after) pt, canvas zoom \(zoom), canvas \(canvas.frame))")
        snapshot("rack-zoomed")
        // iPad: the zoom buttons (also ⌘+ and ⌘–) zoom in and out too.
        let zoomIn = element("rack.zoom.in"), zoomOut = element("rack.zoom.out")
        if zoomIn.exists, zoomOut.exists {
            zoomOut.press()
            sleep(2)
            let out = cell.frame.width
            XCTAssertLessThan(out, after * 0.8, "main rack: Zoom Out did not shrink places (\(after) → \(out) pt)")
            zoomIn.press()
            sleep(2)
            XCTAssertGreaterThan(cell.frame.width, out * 1.2, "main rack: Zoom In did not enlarge places (\(out) → \(cell.frame.width) pt)")
        }
        #endif
    }

    /// The head-to-tail fridge, two deep: each shelf shows its front bottles, then the ones
    /// behind (necks facing you). A back place is tapped directly; pinching still zooms.
    private func exploreHeadToTailFridge() {
        // Shelf 1, bottom layer, place 1, behind: one bottle, shown in the card.
        let back = element("rack.cell.1-0.back")
        guard back.waitForExistence(timeout: 5) else {
            XCTFail("head-to-tail fridge: no back place")
            return
        }
        XCTAssertTrue(element("rack.cell.1-0").exists, "head-to-tail fridge: no front place")
        back.press()
        XCTAssertTrue(element("rack.selection").waitForExistence(timeout: 3), "head-to-tail fridge: no card for the back bottle")
        snapshot("rack-fridge-back-bottle")
        let close = element("rack.selection.close")
        if close.exists { close.press() }

        #if os(iOS)
        let canvas = element("rack.canvas")
        guard canvas.waitForExistence(timeout: 3) else {
            XCTFail("head-to-tail fridge: no zoomable canvas")
            return
        }
        let before = back.frame.width
        canvas.pinch(withScale: 2.2, velocity: 1.5)
        sleep(2)
        let after = back.frame.width
        XCTAssertGreaterThan(after, before * 1.3, "head-to-tail fridge: pinching did not enlarge places (\(before) → \(after) pt)")
        snapshot("rack-fridge-zoomed")
        #endif
    }

    // MARK: Links

    #if os(iOS)
    /// The sample's wine 6 with its last bottle drunk (--drink-out-wine): its name-based ID
    /// (SampleData.id(.wine, 6)) and its name, the same in every language.
    private let drunkWineID = "6046A0A3-FF3A-5085-A405-951154C9495F"
    private let drunkWineName = "Vendanges Tardives"

    /// Its Journal entry.
    private var drunkTasting: XCUIElement {
        app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@", "journal.row.cellar", drunkWineName))
            .firstMatch
    }

    /// The sample cellar in English, with these switches for the tour.
    private func launchSample(_ switches: [String]) {
        continueAfterFailure = true
        app = XCUIApplication()
        app.launchArguments = ["-AppleLanguages", "(en)", "-AppleLocale", "en_US", "--sample-data"] + switches
        app.launch()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 20))
    }

    /// What Add opened: the scanner, or the form with or without the name being typed.
    private func addScreen() -> String {
        guard element("sheet.cancel").waitForExistence(timeout: 5) else { return "scanner or nothing" }
        sleep(1)
        let typing = app.textFields.matching(NSPredicate(format: "hasKeyboardFocus == true")).count > 0
        return typing ? "form, typing the name" : "form"
    }

    /// The empty cellar's Add a Bottle opens what the "+" opens: the label scanner where the
    /// device has one, else the form, without typing the name (the scanner's way).
    func testEmptyCellarAddMatchesPlus() {
        language = "links"
        launchSample(["--empty-cellar"])
        guard let empty = require("empty.add", "empty cellar: no Add a Bottle button", timeout: 15) else { return }
        empty.press()
        let fromEmpty = addScreen()
        closeSheet()
        guard let plus = requireControl("cellar.add", titles: ["Add Bottle"], "empty cellar: no + button") else { return }
        plus.press()
        XCTAssertEqual(addScreen(), fromEmpty, "empty cellar: Add a Bottle and + open different screens")
        closeSheet()
    }

    private func checkWinePage(_ link: String) {
        XCTAssertTrue(wineAnchor.waitForExistence(timeout: 5), "\(link) did not open the wine's page")
        XCTAssertTrue(element("wine.header.picture").exists, "\(link): the wine's page shows no picture")
    }

    /// Every bottle of a wine drunk: its Journal entry shows its picture, and the tasting's
    /// wine link, the entry's Show Wine and a widget's link all open its page.
    func testDrunkWineStaysReachable() {
        language = "links"
        launchSample(["--drink-out-wine"])
        openSection(3, "journal")
        guard drunkTasting.waitForExistence(timeout: 10) else {
            XCTFail("journal: no entry for the drunk wine")
            return
        }
        XCTAssertEqual(drunkTasting.identifier, "journal.row.cellar.picture", "journal: the drunk wine's entry shows no picture")
        drunkTasting.press()
        if let link = require("tasting.wine", "journal: the tasting does not show its wine") {
            link.press()
            checkWinePage("the tasting's wine link")
        }
        launchSample(["--drink-out-wine"])
        openSection(3, "journal")
        if drunkTasting.waitForExistence(timeout: 10) {
            // The menu sometimes does not open on a slow simulator (the long press lands
            // while the list still settles): press once more before failing.
            drunkTasting.press(forDuration: 1.2)
            if !element("journal.menu.showWine").waitForExistence(timeout: 8) {
                drunkTasting.press(forDuration: 2)
            }
            if let show = require("journal.menu.showWine", "journal: no Show Wine in the entry's menu", timeout: 10) {
                show.press()
                checkWinePage("the entry's Show Wine")
            }
        }
        app.open(URL(string: "encave://wine/\(drunkWineID)")!)
        checkWinePage("encave://wine of a wine with no bottle left")
    }

    /// No dead ends: each link (or button that navigates) opens a screen that shows
    /// something. Every new link gets a row here (docs/development.md). Each starts from a
    /// new launch, with one wine whose last bottle was drunk.
    func testNavigationLinks() {
        language = "links"
        struct Link {
            let name: String
            let section: (Int, String)
            let steps: [() -> XCUIElement]
            let destination: () -> XCUIElement
        }
        let winePage = { self.wineAnchor }
        let links = [
            Link(name: "cellar row", section: (0, "cellar"), steps: [{ self.element("wine.row") }], destination: winePage),
            Link(name: "tonight card", section: (2, "tonight"), steps: [{ self.element("action.tonight.open") }], destination: winePage),
            Link(name: "rack position", section: (1, "racks"),
                 steps: [{ self.element("rack.card.0") }, { self.element("rack.cell.0-0") }], destination: { self.element("rack.selection") }),
            Link(name: "rack card wine", section: (1, "racks"),
                 steps: [{ self.element("rack.card.0") }, { self.element("rack.cell.0-0") }, { self.element("rack.selection.wine") }],
                 destination: winePage),
            Link(name: "journal entry", section: (3, "journal"), steps: [{ self.cellarTasting }], destination: { self.element("tasting.wine") }),
            Link(name: "tasting wine link", section: (3, "journal"),
                 steps: [{ self.cellarTasting }, { self.element("tasting.wine") }], destination: winePage),
            Link(name: "tasting wine link, no bottle left", section: (3, "journal"),
                 steps: [{ self.drunkTasting }, { self.element("tasting.wine") }], destination: winePage),
        ]
        for link in links {
            launchSample(["--drink-out-wine"])
            openSection(link.section.0, link.section.1)
            var reached = true
            for step in link.steps {
                let item = step()
                guard item.waitForExistence(timeout: 5) else {
                    XCTFail("\(link.name): a step is missing (\(item))")
                    reached = false
                    break
                }
                item.press()
            }
            guard reached else { continue }
            XCTAssertTrue(link.destination().waitForExistence(timeout: 5), "\(link.name) leads nowhere")
            XCTAssertGreaterThan(app.staticTexts.count, 2, "\(link.name) leads to an empty screen")
        }
        // From outside the app (a widget, Spotlight, a reminder), to a wine with no bottle left.
        launchSample(["--drink-out-wine"])
        app.open(URL(string: "encave://wine/\(drunkWineID)")!)
        XCTAssertTrue(wineAnchor.waitForExistence(timeout: 5), "encave://wine of a wine with no bottle left leads nowhere")
    }
    #endif

    // MARK: Navigation

    private func element(_ identifier: String) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: identifier).firstMatch
    }

    /// Waits for a condition the app reaches by itself (a list filtered, a row gone), instead
    /// of a fixed delay; false if it is still not met after `timeout`.
    private func waitUntil(timeout: TimeInterval = 5, _ condition: () -> Bool) -> Bool {
        let end = Date().addingTimeInterval(timeout)
        while !condition() {
            if Date() > end { return false }
            RunLoop.current.run(until: Date().addingTimeInterval(0.2))
        }
        return true
    }

    /// The first element with one of these identifiers.
    private func anyElement(_ identifiers: [String]) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "identifier IN %@", identifiers)).firstMatch
    }

    /// A wine's page is open: its Drink or Add Bottles button.
    private var wineAnchor: XCUIElement { anyElement(["action.wine.drink", "action.wine.add"]) }

    /// A Journal entry from the cellar (".picture" when it shows the wine's picture).
    private var cellarTasting: XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "journal.row.cellar")).firstMatch
    }

    /// What a step needs: a missing element fails the tour instead of skipping the step.
    @discardableResult
    private func require(_ identifier: String, _ message: String, timeout: TimeInterval = 5) -> XCUIElement? {
        let item = element(identifier)
        guard item.waitForExistence(timeout: timeout) else {
            XCTFail(message)
            return nil
        }
        return item
    }

    /// A toolbar control a step needs, by identifier or by title (a menu in a toolbar does
    /// not always carry its identifier); missing, it fails the tour.
    private func requireControl(_ identifier: String, titles: [String], _ message: String) -> XCUIElement? {
        let byID = element(identifier)
        if byID.waitForExistence(timeout: 5) { return byID }
        let byTitle = app.descendants(matching: .any).matching(NSPredicate(format: "label IN %@", titles)).firstMatch
        if byTitle.waitForExistence(timeout: 2) { return byTitle }
        XCTFail(message)
        print(app.debugDescription)
        return nil
    }

    /// Something each section always shows with the sample cellar, to know it is open.
    private func sectionAnchor(_ name: String) -> XCUIElement {
        let identifiers: [String: [String]] = [
            "cellar": ["wine.row", "cellar.select"],
            // Or the list of bottles not in storage, open in Storage.
            "racks": ["rack.card.0", "racks.add", "notInStorage.row", "notInStorage.putAll"],
            "tonight": ["action.tonight.drink", "tonight.ask"],
            "journal": ["journal.add", "journal.row.cellar", "journal.row.cellar.picture"],
        ]
        return anyElement(identifiers[name] ?? [])
    }

    /// A button of an open menu, by identifier or by title: menu items do not always carry
    /// their identifier on iOS 26.
    private func menuItem(_ identifier: String, titles: [String], timeout: TimeInterval = 5) -> XCUIElement? {
        let byID = element(identifier)
        if byID.waitForExistence(timeout: timeout) { return byID }
        let byTitle = app.buttons.matching(NSPredicate(format: "label IN %@", titles)).firstMatch
        return byTitle.waitForExistence(timeout: 2) ? byTitle : nil
    }

    /// A toolbar button that can be pressed, by identifier or title, or found in the toolbar's
    /// overflow menu when the toolbar has no room for it.
    private func toolbarAction(_ identifier: String, titles: [String]) -> XCUIElement? {
        func hittable(_ item: XCUIElement?) -> XCUIElement? {
            guard let item, item.exists, item.isHittable else { return nil }
            return item
        }
        if let item = hittable(menuItem(identifier, titles: titles, timeout: 3)) { return item }
        #if os(iOS)
        let overflow = app.navigationBars.buttons.matching(NSPredicate(format: "label IN %@",
                                                                       ["More", "Plus", "Plus d’options"])).firstMatch
        if overflow.exists, overflow.isHittable {
            overflow.press()
            sleep(1)
            if let item = hittable(menuItem(identifier, titles: titles, timeout: 3)) { return item }
            dismissMenu()
        }
        #endif
        // What the screen holds, to see where the button went.
        print(app.debugDescription)
        return nil
    }

    /// Tab titles as shown in each language.
    private func title(of section: String) -> String {
        let titles: [String: [String: String]] = [
            "en": ["cellar": "Cellar", "racks": "Storage", "tonight": "Tonight", "journal": "Journal"],
            "fr": ["cellar": "Cave", "racks": "Rangements", "tonight": "Ce soir", "journal": "Journal"],
        ]
        // The large-text tours ("xl", "ax5") run in English; "dark-fr" and "studio-fr" in French.
        let french = language == "fr" || language.hasSuffix("-fr")
        return titles[french ? "fr" : "en"]?[section] ?? section
    }

    private func openSection(_ index: Int, _ name: String) {
        #if os(macOS)
        let item = element("section.\(name)")
        if item.waitForExistence(timeout: 5) {
            item.press()
        } else {
            app.outlines.staticTexts[title(of: name)].firstMatch.press()
        }
        #else
        // iPhone: tab bar at the bottom. iPad: tabs at the top, which may also show a sidebar button.
        let title = title(of: name)
        let inTabBar = app.tabBars.buttons[title]
        if inTabBar.waitForExistence(timeout: 5), inTabBar.isHittable {
            inTabBar.press()
        } else {
            let button = app.buttons.matching(NSPredicate(format: "label == %@", title)).firstMatch
            if button.exists, button.isHittable {
                button.press()
            } else {
                XCTFail("Could not open the \(name) section")
            }
        }
        #endif
        // Shown once its content is there, rather than after a fixed delay.
        XCTAssertTrue(sectionAnchor(name).waitForExistence(timeout: 10), "The \(name) section shows nothing")
    }

    private func goBack() {
        #if os(macOS)
        app.typeKey("[", modifierFlags: .command)
        #else
        let back = app.navigationBars.buttons.matching(identifier: "BackButton").firstMatch
        if back.exists, back.isHittable {
            back.press()
        } else if app.horizontalSizeClassIsCompact {
            let first = app.navigationBars.buttons.element(boundBy: 0)
            if first.exists, first.isHittable { first.press() }
        }
        #endif
        sleep(1)
    }

    private func closeSheet() {
        for identifier in ["sheet.cancel", "sheet.done"] {
            let button = element(identifier)
            if button.waitForExistence(timeout: 2), button.isHittable {
                button.press()
                sleep(1)
                return
            }
        }
        #if os(macOS)
        app.typeKey(.escape, modifierFlags: [])
        #endif
        sleep(1)
    }

    /// The wine's country (or producer) lists the other wines of the cellar that share it.
    /// A detail no other wine shares edits the wine instead.
    private func checkExplore() {
        let country = element("detail.explore.country")
        // Below the fold on iPhone: the list makes the row only once it is scrolled to.
        scrollIntoView(country)
        let producer = country.exists ? country : element("detail.explore.producer")
        guard producer.waitForExistence(timeout: 5) else {
            XCTFail("wine: no detail shared with other wines")
            return
        }
        // The detail column can be out of reach of the scroll on some layouts: the step is skipped there.
        guard producer.isHittable else { return }
        producer.press()
        if element("explore.row").waitForExistence(timeout: 5) {
            snapshot("wine-explore")
        } else {
            XCTFail("wine-explore: no wine listed for the producer")
        }
        goBack()
    }

    /// Closes an open menu or confirmation without choosing anything.
    private func dismissMenu() {
        #if os(macOS)
        app.typeKeyEscapeIfMac()
        #else
        // iPad shows menus and confirmations as popovers, closed by a tap outside them.
        let outside = app.otherElements["PopoverDismissRegion"].firstMatch
        let cancel = app.buttons["Cancel"].exists ? app.buttons["Cancel"] : app.buttons["Annuler"]
        if outside.exists {
            outside.tap()
        } else if cancel.exists, cancel.isHittable {
            cancel.press()
        } else {
            // Away from the menu, which opens under its button: the status bar, which
            // has nothing to tap (the tab bar sits below it on iPad).
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.01)).tap()
        }
        #endif
        sleep(1)
    }

    #if os(iOS)
    /// Scrolls the screen's content up: a flick between two points of the screen, as
    /// swipeUp() on the app needs a hit point XCUITest sometimes cannot find for the app itself.
    /// Around the middle, so it stays inside a sheet centered on an iPad.
    private func swipeContentUp() {
        let start = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.62))
        start.press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.38)),
                    withVelocity: .fast, thenHoldForDuration: 0)
    }
    #endif

    /// Turns a switch on (scrolled to first); fails the tour when it stays off.
    private func turnOn(_ toggle: XCUIElement, _ message: String) {
        scrollIntoView(toggle)
        guard toggle.exists else { return XCTFail("\(message): no switch") }
        if toggle.value as? String == "1" { return }
        #if os(iOS)
        // The switch sits at the trailing end of its row.
        toggle.coordinate(withNormalizedOffset: CGVector(dx: 0.93, dy: 0.5)).tap()
        if toggle.value as? String != "1" { toggle.switches.firstMatch.tap() }
        #else
        toggle.click()
        #endif
        sleep(1)
        XCTAssertEqual(toggle.value as? String, "1", "\(message): the switch stays off")
    }

    /// Back to the top of the screen's content.
    private func scrollToTop() {
        for _ in 0..<6 {
            #if os(iOS)
            let start = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.35))
            start.press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.65)),
                        withVelocity: .fast, thenHoldForDuration: 0)
            #else
            app.scroll(byDeltaX: 0, deltaY: 300)
            #endif
        }
        sleep(1)
    }

    private func scrollIntoView(_ element: XCUIElement) {
        var tries = 0
        while !(element.exists && element.isHittable), tries < 10 {
            #if os(iOS)
            swipeContentUp()
            #else
            app.scroll(byDeltaX: 0, deltaY: -300)
            #endif
            tries += 1
        }
    }

    /// The sample's rosé is in the kitchen, not in a storage: Storage lists it (a card on
    /// iPhone and iPad, a sidebar row on the Mac), and Put Away opens the Move sheet on it.
    private func checkNotInStorage() {
        #if os(macOS)
        let entry = "section.notInStorage"
        #else
        let entry = "racks.notInStorage"
        #endif
        guard let card = require(entry, "storage: nothing leads to the bottles not in storage", timeout: 10) else { return }
        card.press()
        snapshot("not-in-storage")
        if let putAway = require("notInStorage.putAway", "not in storage: no Put Away button") {
            putAway.press()
            snapshot("put-away-sheet")
            closeSheet()
        }
        // Its wine's page says it too, above the bottles: Show Wine in the bottle's menu.
        let row = element("notInStorage.row")
        if row.exists {
            #if os(macOS)
            row.rightClick()
            #else
            row.press(forDuration: 1.2)
            #endif
            let show = app.buttons.matching(NSPredicate(format: "label == %@ OR label == %@", "Show Wine", "Voir le vin")).firstMatch
            if show.waitForExistence(timeout: 3) {
                show.press()
                if require("wine.putAwayBanner", "wine page: no banner for its bottle out of storage", timeout: 10) != nil {
                    snapshot("wine-put-away")
                }
                openSection(1, "racks")
            } else {
                #if os(macOS)
                app.typeKey(.escape, modifierFlags: [])
                #else
                app.coordinate(withNormalizedOffset: CGVector(dx: 0.02, dy: 0.02)).tap()
                #endif
            }
        }
        #if os(iOS)
        goBack()
        #endif
    }

    // MARK: Screenshots and checks

    private func snapshot(_ name: String) {
        sleep(1)
        step += 1
        #if os(macOS)
        // The whole screen: shows the window even if the app cannot be captured on its own.
        let screenshot = XCUIScreen.main.screenshot()
        #else
        let screenshot = app.screenshot()
        #endif
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = String(format: "%@-%02d-%@", language, step, name)
        attachment.lifetime = .keepAlways
        add(attachment)
        // The same screenshot: each one is a request a slow simulator may time out on.
        checkActionButtons(screen: name, in: screenshot)
    }

    /// Every "action.…" button must have its icon and text centered.
    private func checkActionButtons(screen: String, in screenshot: XCUIScreenshot) {
        #if os(iOS)
        let buttons = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "action."))
        guard buttons.count > 0, let image = screenshot.image.cgImage else { return }
        let scale = CGFloat(image.width) / app.frame.width
        for index in 0..<buttons.count {
            let button = buttons.element(boundBy: index)
            guard button.exists, button.isHittable else { continue }
            guard let offset = contentOffset(of: button.frame, in: image, scale: scale) else { continue }
            // 4 pt: glyph shapes (a thin "+") move the ink a little; the bug this catches was 10 pt and more.
            XCTAssertLessThanOrEqual(abs(offset), 4,
                "\(screen): the content of \(button.identifier) is \(Int(offset)) pt off center")
        }
        #endif
    }

    /// Two buttons side by side: same width, and centered on the screen on iPhone.
    private func checkPair(_ left: String, _ right: String) {
        let a = element(left), b = element(right)
        guard a.waitForExistence(timeout: 5), b.exists else {
            XCTFail("\(left) or \(right) is missing")
            return
        }
        XCTAssertEqual(a.frame.width, b.frame.width, accuracy: 2, "\(left) and \(right) differ in width")
        XCTAssertLessThan(a.frame.maxX, b.frame.minX + 1, "\(left) overlaps \(right)")
        #if os(iOS)
        if app.horizontalSizeClassIsCompact {
            let screen = app.frame
            let leftMargin = a.frame.minX - screen.minX
            let rightMargin = screen.maxX - b.frame.maxX
            XCTAssertEqual(leftMargin, rightMargin, accuracy: 3,
                           "\(left)/\(right) are not centered: margins \(Int(leftMargin)) and \(Int(rightMargin))")
        }
        #endif
    }

    #if os(iOS)
    /// Horizontal offset (points) between the center of what is drawn in a button
    /// (icon and text) and the center of the button. Nil if nothing stands out.
    private func contentOffset(of frame: CGRect, in image: CGImage, scale: CGFloat) -> CGFloat? {
        // Leave out the rounded ends: glass highlights there would count as content.
        let inset = frame.insetBy(dx: max(8, frame.height / 2), dy: 6)
        // Reference color: the button's fill just inside its left end.
        let fillPoint = CGPoint(x: (frame.minX + max(4, frame.height / 4)) * scale, y: frame.midY * scale)
        let rect = CGRect(x: inset.minX * scale, y: inset.minY * scale,
                          width: inset.width * scale, height: inset.height * scale).integral
        guard rect.width > 4, rect.height > 4, let crop = image.cropping(to: rect) else { return nil }
        let width = crop.width, height = crop.height
        var pixels = [UInt8](repeating: 0, count: width * height * 4)
        guard let context = CGContext(data: &pixels, width: width, height: height, bitsPerComponent: 8,
                                      bytesPerRow: width * 4, space: CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
        context.draw(crop, in: CGRect(x: 0, y: 0, width: width, height: height))
        guard let background = color(at: fillPoint, in: image) else { return nil }
        // Columns holding at least two strongly contrasting pixels are content (icon, text).
        var counts = [Int](repeating: 0, count: width)
        for y in stride(from: 0, to: height, by: 2) {
            for x in 0..<width {
                let i = (y * width + x) * 4
                let diff = abs(Int(pixels[i]) - background.0) + abs(Int(pixels[i + 1]) - background.1)
                    + abs(Int(pixels[i + 2]) - background.2)
                if diff > 120 { counts[x] += 1 }
            }
        }
        guard let minX = counts.firstIndex(where: { $0 >= 2 }),
              let maxX = counts.lastIndex(where: { $0 >= 2 }), maxX > minX else { return nil }
        // The crop is centered on the button, so its middle is the button's middle.
        let contentCenter = CGFloat(minX + maxX) / 2
        return (contentCenter - CGFloat(width) / 2) / scale
    }

    private func color(at point: CGPoint, in image: CGImage) -> (Int, Int, Int)? {
        guard let pixel = image.cropping(to: CGRect(x: Int(point.x), y: Int(point.y), width: 1, height: 1)) else { return nil }
        var rgba = [UInt8](repeating: 0, count: 4)
        guard let context = CGContext(data: &rgba, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4,
                                      space: CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
        context.draw(pixel, in: CGRect(x: 0, y: 0, width: 1, height: 1))
        return (Int(rgba[0]), Int(rgba[1]), Int(rgba[2]))
    }
    #endif

    /// A free position tapped while the sample's rosé is out of storage: Put a Bottle Here or
    /// Add a Bottle Here. Left through Add a Bottle Here's Cancel, so the cellar stays as it is.
    private func checkFreePlaceChoice() {
        let empty = app.descendants(matching: .any).matching(NSPredicate(
            format: "identifier BEGINSWITH %@ AND (label ENDSWITH %@ OR label ENDSWITH %@)", "rack.cell.", ", empty", ", vide"))
        guard empty.count > 0 else { return }
        let place = empty.element(boundBy: 0)
        guard place.isHittable else { return }
        place.press()
        let putHere = app.buttons.matching(NSPredicate(format: "label == %@ OR label == %@",
                                                       "Put a Bottle Here…", "Ranger une bouteille ici…")).firstMatch
        guard putHere.waitForExistence(timeout: 5) else {
            XCTFail("main rack: a free position offers no Put a Bottle Here while a bottle is out of storage")
            return
        }
        snapshot("rack-free-position")
        // Closed through Add a Bottle Here and its Cancel: the same on iPhone, iPad (a popover
        // with no Cancel button) and Mac, and the tour's cellar does not change.
        let add = app.buttons.matching(NSPredicate(format: "label == %@ OR label == %@",
                                                   "Add a Bottle Here", "Ajouter une bouteille ici")).firstMatch
        if add.waitForExistence(timeout: 3) {
            add.press()
            closeSheet()
        } else {
            XCTFail("main rack: a free position offers no Add a Bottle Here")
        }
        sleep(1)
    }

    /// Browsing a rack, an empty place shows no name ("B1"), but VoiceOver still reads
    /// "B1, empty" (checkRackFits covers the places' size).
    private func checkEmptyPlacesAreQuiet() {
        let empty = app.descendants(matching: .any).matching(NSPredicate(
            format: "identifier BEGINSWITH %@ AND (label ENDSWITH %@ OR label ENDSWITH %@)", "rack.cell.", ", empty", ", vide"))
        guard empty.count > 0 else {
            XCTFail("main rack: no empty place with an accessibility label \"…, empty\"")
            return
        }
        let place = empty.element(boundBy: 0)
        let name = place.label.components(separatedBy: ", ").first ?? ""
        XCTAssertFalse(name.isEmpty, "main rack: an empty place has no name in its label")
        XCTAssertEqual(place.staticTexts.matching(NSPredicate(format: "label == %@", name)).count, 0,
                       "main rack: the empty place \(name) shows its name while browsing")
        XCTAssertFalse(app.staticTexts[name].exists, "main rack: the empty place \(name) shows its name while browsing")
    }

    /// At the default zoom, either the whole rack is in view (no place cut off by the screen
    /// edges), or it scrolls: then it opens at its top-leading place and every place is at
    /// least a touch target (44 pt).
    private func checkRackFits(screen: String) {
        let cells = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "rack.cell."))
        guard cells.count > 0 else {
            XCTFail("\(screen): no rack places found")
            return
        }
        let window = app.windows.firstMatch.frame
        var outside: [String] = [], small: [String] = []
        for index in 0..<cells.count {
            let cell = cells.element(boundBy: index)
            let frame = cell.frame
            if frame.minX < window.minX - 1 || frame.maxX > window.maxX + 1 {
                outside.append(cell.identifier)
            }
            if min(frame.width, frame.height) < 44 - 0.5 {
                small.append("\(cell.identifier) (\(Int(frame.width))×\(Int(frame.height)) pt)")
            }
        }
        guard !outside.isEmpty else { return }
        // A rack that scrolls.
        XCTAssertTrue(small.isEmpty, "\(screen): the rack scrolls but \(small.count) places are under 44 pt, such as \(small.prefix(3))")
        let first = element("rack.cell.0-0").frame
        XCTAssertTrue(first.minX >= window.minX - 1 && first.maxX <= window.maxX + 1
                          && first.minY >= window.minY - 1 && first.maxY <= window.maxY + 1,
                      "\(screen): the rack scrolls but does not open at its first place (\(first))")
    }
}

private extension XCUIElement {
    /// Tap on iPhone and iPad, click on the Mac.
    func press() {
        #if os(macOS)
        click()
        #else
        tap()
        #endif
    }
}

private extension XCUIApplication {
    /// Closes an open menu on the Mac.
    func typeKeyEscapeIfMac() {
        #if os(macOS)
        typeKey(.escape, modifierFlags: [])
        #endif
    }

    /// An iPad (the app shows the cellar table there only).
    var isPad: Bool {
        #if os(iOS)
        return UIDevice.current.userInterfaceIdiom == .pad
        #else
        return false
        #endif
    }

    /// iPhone-like width (no split view).
    var horizontalSizeClassIsCompact: Bool {
        #if os(iOS)
        return frame.width < 600
        #else
        return false
        #endif
    }
}
