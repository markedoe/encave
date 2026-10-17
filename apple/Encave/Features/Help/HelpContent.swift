import SwiftUI

/// Topics of the in-app help. Screens open the topic that matches them.
enum HelpTopic: String, CaseIterable, Identifiable, Hashable {
    case start, adding, scanning, racks, tonight, drinking, discoveries, ai, data, privacy, shortcuts
    var id: String { rawValue }
}

/// A paragraph of help, with an optional heading. Text can use **bold**.
struct HelpSection: Identifiable {
    let id = UUID()
    var title: LocalizedStringKey?
    var body: LocalizedStringKey
    var systemImage: String?
}

struct HelpArticle: Identifiable {
    let topic: HelpTopic
    let title: LocalizedStringKey
    let summary: LocalizedStringKey
    let systemImage: String
    let sections: [HelpSection]
    var id: HelpTopic { topic }

    // Computed: LocalizedStringKey is not Sendable, so the content cannot be a stored static.
    static var all: [HelpArticle] {
        [
            HelpArticle(topic: .start, title: "Getting started",
                        summary: "The four sections and how they work together.", systemImage: "sparkles", sections: [
                HelpSection(title: "Cellar", body: "Every bottle you keep, grouped by wine. Search by name, producer, region, grape or vintage, and filter by style or by when to drink.", systemImage: "wineglass"),
                HelpSection(title: "Racks", body: "Your racks, wine fridges and bins drawn as they look, so you know exactly where each bottle is.", systemImage: "square.grid.3x3"),
                HelpSection(title: "Tonight", body: "What to open now: bottles at their peak or to drink soon first, and ideas to match a dish.", systemImage: "moon.stars"),
                HelpSection(title: "Journal", body: "The bottles you opened with your notes, the wines you discovered elsewhere, your shopping list and insights.", systemImage: "book.closed"),
                HelpSection(title: "Try it", body: "In **Settings**, load the sample cellar to explore every screen, then erase it when you are ready to add your own bottles."),
            ]),
            HelpArticle(topic: .adding, title: "Adding bottles",
                        summary: "A new wine, or more bottles of one you already have.", systemImage: "plus.circle", sections: [
                HelpSection(title: "Already in your cellar?", body: "Start typing the name: if the wine is already there, choose it and only enter how many bottles you add and where they go."),
                HelpSection(title: "Wine", body: "Only the **name** is required. The producer and the vintage help tell wines apart; leave the vintage on **Non-vintage** for champagnes and blends without a year."),
                HelpSection(title: "Style and origin", body: "The style (red, white, sparkling…) is used for colors, serving temperature and food pairing. The appellation, region, country and grapes make search and pairing better."),
                HelpSection(title: "Drinking window", body: "The years when the wine is at its best. With **Estimate automatically**, it is worked out from the style and the vintage; turn it off to enter the years from the producer or a guide."),
                HelpSection(title: "Quantity, size and location", body: "Add several bottles at once. Choose a rack to place them: the first free places are used unless you pick them. Without a rack, write where they are (kitchen, box in the garage)."),
                HelpSection(title: "Purchase details", body: "Optional: price per bottle, date, shop or the person who gave it to you. Prices are used for the value of your cellar in Insights."),
            ]),
            HelpArticle(topic: .scanning, title: "Scanning a label",
                        summary: "Fill in a wine from a photo of its label.", systemImage: "camera.viewfinder", sections: [
                HelpSection(title: "On this device", body: "Point the camera at the label and tap **Use This Label**, or choose a photo. The text is read on the device and fills in the name, producer, vintage and appellation. Nothing is sent anywhere."),
                HelpSection(title: "With AI (optional)", body: "If an AI service is set up in Settings, the label photo is also sent to it to complete the style, region, grapes and drinking window. Only that photo is sent, never your cellar."),
                HelpSection(title: "What you typed stays", body: "A new scan or the AI only replaces fields that are empty or that were filled automatically. Everything you typed yourself is kept."),
                HelpSection(title: "The photo", body: "The label photo is kept with the wine, resized and without location data. You can change or remove it when editing the wine."),
            ]),
            HelpArticle(topic: .racks, title: "Racks",
                        summary: "Describe your storage so every bottle has a place.", systemImage: "square.grid.3x3", sections: [
                HelpSection(title: "Grid rack", body: "One bottle per cell, in rows and columns. Choose **two bottles per cell** for racks deep enough to hold one behind the other.", systemImage: "square.grid.3x3"),
                HelpSection(title: "Honeycomb rack", body: "Every other row is offset by half a bottle and holds one fewer, like metal or terracotta racks. Enter the number of bottles in the widest row.", systemImage: "circle.hexagongrid"),
                HelpSection(title: "Wine fridge or cabinet", body: "Shelves holding a row of bottles, one to three deep. The dots under each bottle show which places are filled, front first.", systemImage: "refrigerator"),
                HelpSection(title: "Bins or crates", body: "Diamond bins or crates hold several bottles in a pile. Set how many bottles fit in each; tap a bin to see what is inside.", systemImage: "diamond"),
                HelpSection(title: "Places", body: "Rows are lettered from the top and cells numbered from the left: **B3** is the second row, third cell. When a cell holds several bottles, **B3-2** is the second one (behind, or in the pile)."),
                HelpSection(title: "Moving bottles", body: "Tap a bottle, then **Move**, then the new place. Tapping another bottle swaps them; a bin takes the bottle if it has room."),
                HelpSection(title: "Irregular racks", body: "Turn on **Rows of different lengths** for a rack under a sloped ceiling or a staircase, and set the length of each row."),
            ]),
            HelpArticle(topic: .tonight, title: "Tonight and drinking windows",
                        summary: "When each wine is at its best.", systemImage: "moon.stars", sections: [
                HelpSection(title: "Maturity", body: "Each wine is **too young**, **ready**, **to drink soon** (last year of its window) or **past its peak**. The label and the symbol always say it, not only the color."),
                HelpSection(title: "Suggestions", body: "Tonight shows the bottles to drink soon first. Choose a dish to see the wines from your cellar that go with it, and why."),
                HelpSection(title: "Serving", body: "Each wine page suggests a serving temperature and, for young full-bodied reds, how long to decant."),
            ]),
            HelpArticle(topic: .drinking, title: "Drinking a bottle",
                        summary: "Keep track of what you opened and what you thought.", systemImage: "wineglass", sections: [
                HelpSection(title: "Drink", body: "Swipe a wine to the right, or use **Drink** on its page. The bottle leaves the cellar and its place in the rack becomes free. **Undo** appears for a few seconds."),
                HelpSection(title: "Your note", body: "Add a rating, a few words and the occasion, or open the full tasting note (appearance, nose, palate, finish, food). Everything is optional and can be edited later from the Journal."),
                HelpSection(title: "Given away or broken", body: "Use **Given away or broken** for a bottle that left the cellar without being drunk by you."),
            ]),
            HelpArticle(topic: .discoveries, title: "Discoveries and shopping list",
                        summary: "Remember wines you tasted elsewhere.", systemImage: "cart", sections: [
                HelpSection(title: "Remember a wine", body: "At friends', in a restaurant or on a trip, tap **+** in the Journal and scan the bottle. Add where you were, a rating, notes and tags."),
                HelpSection(title: "Tags", body: "Tags group discoveries your way: gift ideas, for summer, to try again. Tap a tag to select it, or type a new one; tags you used before are suggested."),
                HelpSection(title: "Shopping list", body: "Turn on **To buy** to add the wine to your shopping list, with the price you saw. When you buy it, swipe it and choose **Add to Cellar**: it leaves the list once the bottles are added."),
                HelpSection(title: "Not in the cellar", body: "Discoveries are not counted as bottles in your cellar and do not appear in racks or Tonight."),
            ]),
            HelpArticle(topic: .ai, title: "Setting up AI",
                        summary: "Use your own AI service for label recognition.", systemImage: "cpu", sections: [
                HelpSection(title: "Choose a provider", body: "In **Settings → AI**, choose a provider. You need an account with it and an API key; the provider bills you directly for what you use, usually a fraction of a cent per label."),
                HelpSection(title: "Create a key", body: "Open the provider's page with the link below the key field, create a key and paste it. The key is stored in the Keychain on this device and sent only to that provider."),
                HelpSection(title: "Check the key", body: "Tap **Check Key and Load Models**: the app asks the provider which models your key can use and fills the model menu. Choose a model that reads images."),
                HelpSection(title: "Local server", body: "Choose **Ollama**, **vLLM** or **LM Studio** if one runs on your computer, then tap **Connect and Load Models**. For Ollama, only the models that read images are listed: install one with `ollama pull gemma3`. On iPhone and iPad, enter the computer's IP address or its name ending in `.local` (for example `http://my-mac.local:11434`), and make the server listen on the network: `OLLAMA_HOST=0.0.0.0` for Ollama, `--host 0.0.0.0` for vLLM, “Serve on Local Network” in LM Studio."),
                HelpSection(title: "Server URL", body: "Leave it empty to use the provider's usual address. Change it only if the provider gives you another one, for example for its China region."),
                HelpSection(title: "What is sent", body: "Only the label photo, when you scan a label or tap **Complete with AI**. Your cellar, notes and prices are never sent."),
            ]),
            HelpArticle(topic: .data, title: "Backup, export and import",
                        summary: "Keep a copy and move your cellar.", systemImage: "externaldrive", sections: [
                HelpSection(title: "Back up", body: "**Back Up (JSON)** saves everything: wines, bottles, racks, tastings and discoveries. Import it on another device to restore it; nothing already there is replaced."),
                HelpSection(title: "Spreadsheet", body: "**Export Spreadsheet (CSV)** opens in Numbers or Excel, one line per bottle with its place. A CSV with at least a **name** column can be imported, including from another app."),
                HelpSection(title: "Photos", body: "Label photos stay on the device and are not included in backups yet."),
            ]),
            HelpArticle(topic: .privacy, title: "Privacy",
                        summary: "Your cellar stays yours.", systemImage: "lock.shield", sections: [
                HelpSection(body: "Your cellar is stored only on your devices. There is no account, no tracking and no advertising."),
                HelpSection(body: "Sync between your devices and with the people you share a cellar with will be end-to-end encrypted: the server that relays changes will never be able to read them."),
                HelpSection(body: "The only data that can leave the device is the label photo, and only if you set up an AI service yourself."),
                HelpSection(body: "Encave is open source: anyone can check what the app does."),
            ]),
            HelpArticle(topic: .shortcuts, title: "Keyboard shortcuts",
                        summary: "On the Mac, and on iPad with a keyboard.", systemImage: "keyboard", sections: [
                HelpSection(title: "⌘N", body: "Add bottles"),
                HelpSection(title: "⌥⌘N", body: "Remember a wine tasted elsewhere"),
                HelpSection(title: "⇧⌘N", body: "Add a rack"),
                HelpSection(title: "⌘1 – ⌘4", body: "Cellar, Racks, Tonight, Journal"),
                HelpSection(title: "⌘+ / ⌘–", body: "Zoom a rack in and out"),
            ]),
        ]
    }

    static func article(_ topic: HelpTopic) -> HelpArticle? { all.first { $0.topic == topic } }
}
