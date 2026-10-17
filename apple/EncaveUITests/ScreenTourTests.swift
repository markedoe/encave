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
    #endif

    // MARK: Tour

    private func tour(language: String) throws {
        self.language = language
        step = 0
        continueAfterFailure = true
        app = XCUIApplication()
        app.launchArguments = ["--sample-data", "-AppleLanguages", "(\(language))",
                               "-AppleLocale", language == "fr" ? "fr_FR" : "en_US"]
        app.launch()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 20))
        #if os(macOS)
        app.activate()
        XCTAssertTrue(app.windows.firstMatch.waitForExistence(timeout: 20), "The main window opens")
        #endif

        // Cellar, a wine, its drink sheet, adding bottles.
        openSection(0, "cellar")
        snapshot("cellar")
        if element("wine.row").waitForExistence(timeout: 10) {
            element("wine.row").press()
            snapshot("wine")
            checkPair("action.wine.drink", "action.wine.add")
            if element("action.wine.drink").exists {
                element("action.wine.drink").press()
                snapshot("drink-sheet")
                closeSheet()
            }
            goBack()
        } else {
            XCTFail("No wine in the sample cellar list")
        }
        if element("cellar.add").exists {
            element("cellar.add").press()
            snapshot("add-bottle")
            closeSheet()
        }

        // Racks: the list, every rack, the editor.
        openSection(1, "racks")
        snapshot("racks")
        // The sample cellar has one rack of every kind: open each one.
        for index in 0..<6 {
            let card = element("rack.card.\(index)")
            scrollIntoView(card)
            guard card.exists else {
                XCTFail("Rack \(index + 1) of the sample cellar is missing")
                continue
            }
            card.press()
            snapshot("rack-\(index + 1)")
            goBack()
        }
        if element("racks.add").exists {
            element("racks.add").press()
            snapshot("rack-editor")
            closeSheet()
        }

        // Tonight.
        openSection(2, "tonight")
        snapshot("tonight")
        checkPair("action.tonight.drink", "action.tonight.next")

        // Journal: tastings, discoveries, remembering a wine.
        openSection(3, "journal")
        snapshot("journal")
        let segments = app.segmentedControls.firstMatch
        if segments.exists, segments.buttons.count > 1 {
            segments.buttons.element(boundBy: 1).press()
            snapshot("discoveries")
        }
        if element("journal.add").exists {
            element("journal.add").press()
            snapshot("remember-wine")
            closeSheet()
        }

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
            closeSheet()
        }
        #endif
    }

    // MARK: Navigation

    private func element(_ identifier: String) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: identifier).firstMatch
    }

    /// Tab titles as shown in each language.
    private func title(of section: String) -> String {
        let titles: [String: [String: String]] = [
            "en": ["cellar": "Cellar", "racks": "Racks", "tonight": "Tonight", "journal": "Journal"],
            "fr": ["cellar": "Cave", "racks": "Casiers", "tonight": "Ce soir", "journal": "Journal"],
        ]
        return titles[language]?[section] ?? section
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
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = String(format: "%@-%02d-%@", language, step, name)
        attachment.lifetime = .keepAlways
        add(attachment)
        checkActionButtons(screen: name)
    }

    /// Every "action.…" button must have its icon and text centered.
    private func checkActionButtons(screen: String) {
        #if os(iOS)
        let buttons = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "action."))
        guard buttons.count > 0, let image = app.screenshot().image.cgImage else { return }
        let scale = CGFloat(image.width) / app.frame.width
        for index in 0..<buttons.count {
            let button = buttons.element(boundBy: index)
            guard button.exists, button.isHittable else { continue }
            guard let offset = contentOffset(of: button.frame, in: image, scale: scale) else { continue }
            XCTAssertLessThanOrEqual(abs(offset), 3,
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
        let inset = frame.insetBy(dx: 6, dy: 6)
        let rect = CGRect(x: inset.minX * scale, y: inset.minY * scale,
                          width: inset.width * scale, height: inset.height * scale).integral
        guard rect.width > 4, rect.height > 4, let crop = image.cropping(to: rect) else { return nil }
        let width = crop.width, height = crop.height
        var pixels = [UInt8](repeating: 0, count: width * height * 4)
        guard let context = CGContext(data: &pixels, width: width, height: height, bitsPerComponent: 8,
                                      bytesPerRow: width * 4, space: CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
        context.draw(crop, in: CGRect(x: 0, y: 0, width: width, height: height))
        // The button's own fill: the pixel at the middle of the left edge.
        let mid = (height / 2) * width * 4
        let background = (Int(pixels[mid]), Int(pixels[mid + 1]), Int(pixels[mid + 2]))
        var minX = width, maxX = -1
        for y in stride(from: 0, to: height, by: 2) {
            for x in 0..<width {
                let i = (y * width + x) * 4
                let diff = abs(Int(pixels[i]) - background.0) + abs(Int(pixels[i + 1]) - background.1)
                    + abs(Int(pixels[i + 2]) - background.2)
                if diff > 120 {
                    minX = min(minX, x)
                    maxX = max(maxX, x)
                }
            }
        }
        guard maxX > minX else { return nil }
        let contentCenter = CGFloat(minX + maxX) / 2
        return (contentCenter - CGFloat(width) / 2) / scale
    }
    #endif
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
    /// iPhone-like width (no split view).
    var horizontalSizeClassIsCompact: Bool {
        #if os(iOS)
        return frame.width < 600
        #else
        return false
        #endif
    }
}
