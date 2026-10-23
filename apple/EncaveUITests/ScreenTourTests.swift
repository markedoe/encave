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
        if element("wine.row").waitForExistence(timeout: 10) {
            element("wine.row").press()
            snapshot("wine")
            goBack()
        }
        openSection(1, "racks")
        snapshot("racks")
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
        let bottles = element("cellar.stat.bottles"), wines = element("cellar.stat.wines")
        if bottles.waitForExistence(timeout: 10), wines.exists {
            XCTAssertGreaterThanOrEqual(wines.frame.minY, bottles.frame.maxY - 1, "The cellar tiles stack at AX5")
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

    /// The first wines of the sample cellar, whose label photos are drawn by the app
    /// (a tilted bottle on a busy counter, a close-up of a label, a bottle lying in a
    /// fridge, a bottle on a table): the list and each wine's page.
    private func studioPictures(dark: Bool) throws {
        language = dark ? "studio-dark" : "studio"
        step = 0
        continueAfterFailure = true
        app = XCUIApplication()
        app.launchArguments = ["--sample-data", "-AppleLanguages", "(en)", "-AppleLocale", "en_US"] + (dark ? ["--dark"] : [])
        app.launch()
        openSection(0, "cellar")
        // Time for the studio pictures to be made the first time.
        sleep(8)
        snapshot("cellar")
        let rows = app.descendants(matching: .any).matching(identifier: "wine.row")
        for index in 0..<4 {
            let row = rows.element(boundBy: index)
            guard row.waitForExistence(timeout: 5), row.isHittable else { break }
            row.press()
            sleep(3)
            snapshot("wine-\(index + 1)")
            goBack()
        }
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
        app.launchArguments = ["-AppleLanguages", "(\(language))",
                               "-AppleLocale", language == "fr" ? "fr_FR" : "en_US", "--sample-data"]
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
           element("wine.row").waitForExistence(timeout: 10), element("wine.row").isHittable {
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
            if element("action.wine.drink").exists {
                element("action.wine.drink").press()
                snapshot("drink-sheet")
                closeSheet()
            }
            checkExplore()
            goBack()
        } else {
            XCTFail("No wine in the sample cellar list")
        }
        if element("cellar.add").exists {
            element("cellar.add").press()
            snapshot("add-bottle")
            closeSheet()
        }

        // Storage: the list, every rack, fridge and set of bins, the editor.
        openSection(1, "racks")
        snapshot("racks")
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
        if element("racks.add").exists {
            // A new storage, step by step: its type, its settings, its name.
            element("racks.add").press()
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

        // Tonight.
        openSection(2, "tonight")
        snapshot("tonight")
        checkPair("action.tonight.drink", "action.tonight.next")
        askTheCellar()

        // Journal: the diary, a filter, and the two ways to add a tasting.
        openSection(3, "journal")
        snapshot("journal")
        checkJournal()

        #if os(iOS)
        // Settings and help (on the Mac they are in the app menu and the Help menu).
        openSection(0, "cellar")
        if element("cellar.settings").exists {
            element("cellar.settings").press()
            snapshot("settings")
            if element("settings.help").exists {
                element("settings.help").press()
                snapshot("help")
                goBack()
            }
            checkDevices()
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
            print("ask your cellar: no answer within 60 s")
            snapshot("ask-waiting")
        }
        goBack()
    }

    // MARK: The cellar as a table (iPad and Mac)

    private func checkTable() {
        let layout = element("cellar.layout")
        if layout.waitForExistence(timeout: 3), layout.buttons.count > 1 {
            layout.buttons.element(boundBy: 1).press()
        } else {
            // iPad: in the Sort and Filter menu of the list. iPhone has no table.
            guard !app.horizontalSizeClassIsCompact else { return }
            let sort = element("cellar.sort")
            guard sort.exists, sort.isHittable else { return }
            sort.press()
            let table = element("cellar.layout.table")
            guard table.waitForExistence(timeout: 3) else {
                XCTFail("cellar: Show as Table is missing from the Sort and Filter menu")
                dismissMenu()
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
        sleep(1)
    }

    // MARK: Journal

    /// The diary mixes both kinds of entries; a filter keeps one; "+" asks where the wine was drunk.
    private func checkJournal() {
        XCTAssertTrue(element("journal.row.cellar").waitForExistence(timeout: 5), "journal: no tasting from the cellar")
        XCTAssertTrue(element("journal.row.elsewhere").exists, "journal: no wine tasted elsewhere")
        let elsewhere = element("journal.filter.elsewhere")
        if !elsewhere.waitForExistence(timeout: 3) {
            XCTFail("journal: no filter chips")
        } else if elsewhere.isHittable {
            elsewhere.press()
            sleep(1)
            snapshot("journal-tasted-elsewhere")
            XCTAssertFalse(element("journal.row.cellar").exists, "journal: the filter still shows bottles from the cellar")
            element("journal.filter.all").press()
            sleep(1)
        } else {
            // The chips scroll sideways: at large sizes this one can be off screen.
            print("journal: the Tasted elsewhere chip is off screen")
        }
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
                XCTAssertTrue(element("cellar.select.delete").waitForExistence(timeout: 2), "cellar: More has no Delete")
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
            app.swipeUp()
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
        sleep(1)
        XCTAssertLessThan(rows.count, all, "cellar: \"to drink soon\" did not filter the list")
        snapshot("cellar-drink-soon")
        soon.press()
        sleep(1)
        XCTAssertEqual(rows.count, all, "cellar: tapping \"to drink soon\" again did not show all wines")

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
        let audit = element("rack.audit")
        if audit.waitForExistence(timeout: 2), audit.isHittable {
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

    // MARK: Navigation

    private func element(_ identifier: String) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: identifier).firstMatch
    }

    /// Tab titles as shown in each language.
    private func title(of section: String) -> String {
        let titles: [String: [String: String]] = [
            "en": ["cellar": "Cellar", "racks": "Storage", "tonight": "Tonight", "journal": "Journal"],
            "fr": ["cellar": "Cave", "racks": "Rangements", "tonight": "Ce soir", "journal": "Journal"],
        ]
        // The large-text tours ("xl", "ax5") run in English.
        return titles[language == "fr" ? "fr" : "en"]?[section] ?? section
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
        sleep(1)
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

    private func scrollIntoView(_ element: XCUIElement) {
        var tries = 0
        while !(element.exists && element.isHittable), tries < 6 {
            #if os(iOS)
            app.swipeUp()
            #else
            app.scroll(byDeltaX: 0, deltaY: -300)
            #endif
            tries += 1
        }
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

    /// At the default zoom, the whole rack is visible: no place is cut off by the screen edges.
    private func checkRackFits(screen: String) {
        let cells = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "rack.cell."))
        guard cells.count > 0 else {
            XCTFail("\(screen): no rack places found")
            return
        }
        let window = app.windows.firstMatch.frame
        var outside: [String] = []
        for index in 0..<cells.count {
            let frame = cells.element(boundBy: index).frame
            if frame.minX < window.minX - 1 || frame.maxX > window.maxX + 1 {
                outside.append(cells.element(boundBy: index).identifier)
            }
        }
        XCTAssertTrue(outside.isEmpty, "\(screen): \(outside.count) places are off screen, such as \(outside.prefix(3))")
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

    /// iPhone-like width (no split view).
    var horizontalSizeClassIsCompact: Bool {
        #if os(iOS)
        return frame.width < 600
        #else
        return false
        #endif
    }
}
