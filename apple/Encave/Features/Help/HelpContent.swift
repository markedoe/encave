import SwiftUI

/// Topics of the in-app help. Screens open the topic that matches them.
enum HelpTopic: String, CaseIterable, Identifiable, Hashable {
    case start, adding, scanning, racks, tonight, drinking, journal, devices, sync, ai, webSearch, data, privacy, shortcuts
    var id: String { rawValue }
}

/// A paragraph of help, with an optional heading. Text can use **bold**.
/// Shown by position in its article: a new ID at each rendering would rebuild the list.
struct HelpSection {
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
                HelpSection(title: "Beta version", body: "Encave is in beta: features can still change and some bugs remain. Make a backup from time to time (**Settings › Your data**), and report problems on the project page.", systemImage: "testtube.2"),
                HelpSection(title: "Cellar", body: "Every bottle you keep, grouped by wine. Search by name, producer, region, grape or vintage, and filter by style, grape or when to drink: the chips show only what your bottles have.", systemImage: "wineglass"),
                HelpSection(title: "Racks", body: "Your racks, wine fridges and bins drawn as they look, so you know exactly where each bottle is.", systemImage: "square.grid.3x3"),
                HelpSection(title: "Tonight", body: "What to open now: bottles at their peak or to drink soon first, and ideas to match a dish.", systemImage: "moon.stars"),
                HelpSection(title: "Journal", body: "Your tasting diary: the bottles you opened from your cellar and the wines you tasted elsewhere, with your notes, your shopping list and insights.", systemImage: "book.closed"),
                HelpSection(title: "List or table", body: "On the Mac and the iPad, the buttons at the top of the Cellar show it as a list or as a table that sorts by any column, with the selected wine beside it.", systemImage: "tablecells"),
                HelpSection(title: "Saved searches", body: "Search or choose filters, then **Save This Search…** in the sort menu. The search appears first among the filter chips; tap it to apply it again, or touch and hold it to delete it. Saved searches sync with your devices.", systemImage: "bookmark"),
                HelpSection(title: "Try it", body: "While your cellar is empty and no other device syncs with it, **Load sample cellar** (on the empty Cellar screen or in **Settings**) fills it with example wines to explore every screen. **Erase all data** in Settings removes them when you are ready to add your own bottles; in a cellar shared with other devices, it erases it on them too."),
            ]),
            HelpArticle(topic: .adding, title: "Adding bottles",
                        summary: "A new wine, or more bottles of one you already have.", systemImage: "plus.circle", sections: [
                HelpSection(title: "Already in your cellar?", body: "Start typing the name: if the wine is already there, choose it and only enter how many bottles you add and where they go."),
                HelpSection(title: "Wine", body: "Only the **name** is required. The producer and the vintage help tell wines apart; leave the vintage on **Non-vintage** for champagnes and blends without a year."),
                HelpSection(title: "Style and origin", body: "The style (red, white, sparkling…) is used for colors, serving temperature and food pairing. The appellation, region, country and grapes make search and pairing better."),
                HelpSection(title: "Drinking window", body: "The years when the wine is at its best. With **Estimate automatically**, it is worked out from the style and the vintage; turn it off to enter the years from the producer or a guide."),
                HelpSection(title: "Quantity, size and location", body: "Add several bottles at once. Choose a rack to place them: the first free places are used unless you pick them. Without a rack, write where they are (kitchen, box in the garage)."),
                HelpSection(title: "Purchase details", body: "Optional: price per bottle, date, shop or the person who gave it to you. Prices are used for the value of your cellar in Insights."),
                HelpSection(title: "Tags", body: "Group wines your own way (gift, birth year, for a friend) in the **Tags** of a wine. The Cellar shows a chip for each tag under the styles: tap it to see only those wines. Search finds tags too.", systemImage: "tag"),
                HelpSection(title: "Documents", body: "Keep an invoice, a certificate of authenticity, a technical sheet or an article with a wine: **Add a Document…** (a PDF, an image, a text or an office file, up to 8 MB) or, on iPhone and iPad, **Scan a Document**, which makes a PDF. Add them while adding bottles, or later under **Documents** on the wine's page: they are for every bottle of the wine. For one bottle only (its certificate, its receipt), touch and hold the bottle on the wine's page or in a rack and choose **Attach a Document…**; a paperclip shows how many it has. Tap a document to open it; touch and hold it to share, rename or delete it. Documents sync with your devices, encrypted, and are never sent to the AI service or the web search.", systemImage: "paperclip"),
                HelpSection(title: "Bottles on order", body: "Bought bottles that have not arrived yet, or wines bought en primeur? Turn on **Not delivered yet** and, if you know it, the expected delivery date. They are listed under **On order** in the Cellar and are not counted or suggested until you swipe them and choose **Received**. They then join the cellar without a place, ready to be put in a rack.", systemImage: "shippingbox"),
            ]),
            HelpArticle(topic: .scanning, title: "Scanning a label",
                        summary: "Fill in a wine from a photo of its label.", systemImage: "camera.viewfinder", sections: [
                HelpSection(title: "On this device", body: "Point the camera at the label and tap **Use This Label**, or choose a photo. The text is read on the device and fills in the name, producer, vintage and appellation. Nothing is sent anywhere."),
                HelpSection(title: "With AI (optional)", body: "If an AI service is set up in Settings, the label photo is also sent to it to complete the style, region, grapes and drinking window. Only that photo is sent, never your cellar."),
                HelpSection(title: "What you typed stays", body: "A new scan or the AI only replaces fields that are empty or that were filled automatically. Everything you typed yourself is kept."),
                HelpSection(title: "The photo", body: "The label photo is kept with the wine, resized and without location data. You can change or remove it when editing the wine. In the lists it is shown in a studio look: the bottle or label cut out and straightened on a clean background, made on this device. Touch and hold it on the wine's page to see it as taken, or turn the look off in **Settings**."),
                HelpSection(title: "Another picture", body: "On the wine's page, **Change Picture…** shows instead an illustration made on this device with Image Playground (with Apple Intelligence), or a picture found on the internet if a search service is set up. The label photo is always kept."),
                HelpSection(title: "Barcodes", body: "While you scan, the camera also reads the bottle's barcode and keeps it with the wine. The next time you scan a bottle of that wine, **Already in your cellar** appears: tap **Use This Wine** to add bottles without filling anything in. Barcodes are matched on this device only; Encave looks nothing up online."),
            ]),
            HelpArticle(topic: .racks, title: "Racks",
                        summary: "Describe your storage so every bottle has a place.", systemImage: "square.grid.3x3", sections: [
                HelpSection(title: "Grid rack", body: "One bottle per cell, in rows and columns. For racks deep enough to hold one bottle behind the other, set **Bottles per cell** to **Two, one behind the other**.", systemImage: "square.grid.3x3"),
                HelpSection(title: "Honeycomb rack", body: "Every other row is offset by half a bottle and holds one fewer, like metal or terracotta racks. Enter the number of bottles in the widest row, and in **Rows deep** whether a second bottle fits behind each one.", systemImage: "circle.hexagongrid"),
                HelpSection(title: "Fridge with bottles in layers", body: "For a wine fridge whose shelves hold bottles stacked in quincunx, choose **Honeycomb rack**, then **On shelves**. Each shelf holds one to three layers: for example 6 bottles at the bottom and 5 on top, resting in the gaps. Shelves can differ: set the layers and bottles of each one, or set one and choose **Apply to All Shelves** (touch and hold it). Places are named by shelf, layer and number, like **Shelf 1, top layer, place 3**; each shelf fills from its bottom layer.", systemImage: "circle.hexagongrid.fill"),
                HelpSection(title: "Wine fridge or cabinet", body: "Shelves holding a row of bottles, one to three deep. The dots under each bottle show which places are filled, front first.", systemImage: "refrigerator"),
                HelpSection(title: "Bins or crates", body: "Diamond bins or crates hold several bottles in a pile. Set how many bottles fit in each; tap a bin to see what is inside.", systemImage: "diamond"),
                HelpSection(title: "Places", body: "Rows are lettered from the top and cells numbered from the left: **B3** is the second row, third cell. When a cell holds several bottles, **B3-2** is the second one (behind, or in the pile)."),
                HelpSection(title: "Finding a bottle", body: "Each bottle shows its vintage, and its name once you zoom in. Tap a bottle to see what it is and where. Tap a wine in the key under the rack to light up its bottles; tap it again to show all.", systemImage: "magnifyingglass"),
                HelpSection(title: "Zooming", body: "Pinch to zoom in and out, or double-tap a spot. On the iPad and the Mac, the zoom buttons at the top and ⌘+ and ⌘– work too. On iPhone and iPad, **Bottle List** lists the bottles of the rack place by place.", systemImage: "plus.magnifyingglass"),
                HelpSection(title: "Moving bottles", body: "Tap a bottle, then **Move**, then the new place. A place with room takes the bottle; in racks with one bottle per place, tapping another bottle swaps them."),
                HelpSection(title: "Drag and drop", body: "On iPad and Mac, drag a bottle to another place to move it, or onto another bottle of the same rack to swap them (in racks with one bottle per place)."),
                HelpSection(title: "Several wines at once", body: "In the Cellar list (not the table), choose **Select Wines** in the sort menu, tick the wines, then place them in a rack, take them out of their places or delete them. **Undo** puts everything back."),
                HelpSection(title: "Checking a rack", body: "Choose **Check This Rack**, tap each bottle you find, then **End Check**. For the bottles not found, choose **Take Them Out of the Rack** (they stay in the cellar without a place) or **Mark Them as Gone** (they leave the cellar). **Undo** puts everything back.", systemImage: "checklist"),
                HelpSection(title: "Irregular racks", body: "Turn on **Rows of different lengths** for a rack under a sloped ceiling or a staircase, and set the length of each row."),
            ]),
            HelpArticle(topic: .tonight, title: "Tonight and drinking windows",
                        summary: "When each wine is at its best.", systemImage: "moon.stars", sections: [
                HelpSection(title: "Why this window", body: "Under the gauge, each wine says where its window comes from: estimated from the style and the vintage, or entered by you. **Drink Sooner** and **Keep Longer** move its end by a year."),
                HelpSection(title: "Keeping bottles for an occasion", body: "On a bottle in a wine’s page, choose **Keep for an Occasion…** and name it (a birthday, Christmas dinner). The bottle stays in its place, is no longer suggested in Tonight, and the Cellar shows a chip for the occasion to find its bottles. **Free This Bottle** gives it back.", systemImage: "gift"),
                HelpSection(title: "Maturity", body: "Each wine is **too young**, **ready**, **to drink soon** (last year of its window) or **past its peak**. The label and the symbol always say it, not only the color. A wine without a drinking window shows none of these and comes last in Tonight: set its window on its page."),
                HelpSection(title: "Suggestions", body: "Tonight shows the bottles to drink soon first. Choose a dish to see the wines from your cellar that go with it, and why."),
                HelpSection(title: "Ask your cellar", body: "On devices with Apple Intelligence, **Ask your cellar** in Tonight answers a question such as “something light for a ratatouille” with bottles you own. The answer is worked out on the device: neither the question nor your cellar is sent anywhere.", systemImage: "sparkles"),
                HelpSection(title: "Serving", body: "Each wine page suggests a serving temperature and, for young full-bodied reds, how long to decant."),
                HelpSection(title: "Reminders", body: "Turn on **Reminders** in Settings: on the first of each month, a notification tells you how many bottles to drink this year, which have just become ready and which are past their peak, and another comes on the day bottles on order are expected. It is worked out on this device."),
            ]),
            HelpArticle(topic: .drinking, title: "Drinking a bottle",
                        summary: "Keep track of what you opened and what you thought.", systemImage: "wineglass", sections: [
                HelpSection(title: "Open a bottle", body: "Swipe a wine to the right, use **Drink** on its page, or tap **+** in the Journal and choose **A Bottle from My Cellar**. The bottle leaves the cellar and its place in the rack becomes free. **Undo** appears for a few seconds."),
                HelpSection(title: "Your note", body: "Add a rating, a few words and the occasion, or open the full tasting note (appearance, nose, palate, finish, food). Everything is optional and can be edited later from the Journal."),
                HelpSection(title: "Given away or broken", body: "Use **Given away or broken** for a bottle that left the cellar without being drunk by you."),
            ]),
            HelpArticle(topic: .journal, title: "Journal and shopping list",
                        summary: "Bottles opened from your cellar and wines tasted elsewhere, in one diary.", systemImage: "book.closed", sections: [
                HelpSection(title: "One diary", body: "The Journal lists everything you tasted, newest first and by month. Each entry says where it comes from: **From my cellar** for a bottle you opened, **Tasted elsewhere** and the place for a wine you had in a restaurant, at friends’ or at a fair."),
                HelpSection(title: "Add a tasting", body: "Tap **+** in the Journal and choose where you drank it. **A Bottle from My Cellar**: choose the wine, then add your note; the bottle leaves your cellar. **Somewhere Else**: scan the bottle and add where you were, a rating, notes and tags; your cellar does not change.", systemImage: "plus"),
                HelpSection(title: "Filters", body: "The chips at the top show **All**, or only **From my cellar**, **Tasted elsewhere** or **To buy**. Search looks in the wines, notes, places and tags.", systemImage: "line.3.horizontal.decrease"),
                HelpSection(title: "Tags", body: "Tags group wines tasted elsewhere your way: gift ideas, for summer, to try again. Tap a tag to select it, or type a new one; tags you used before are suggested. In the Journal, their chips come after the filters."),
                HelpSection(title: "Shopping list", body: "Turn on **To buy** to add the wine to your shopping list, with the price you saw. When you buy it, swipe it and choose **Add to Cellar**: it leaves the list once the bottles are added."),
                HelpSection(title: "Not in the cellar", body: "Wines tasted elsewhere are not counted as bottles in your cellar and do not appear in racks or Tonight."),
            ]),
            HelpArticle(topic: .devices, title: "Your devices",
                        summary: "The same cellar on your iPhone, iPad and Mac.", systemImage: "laptopcomputer.and.iphone", sections: [
                HelpSection(title: "Add a device", body: "On the device that has your cellar, open **Settings › Devices › Add a Device**: it shows a QR code and a 6-digit code. On the new device, choose **Join Another Device’s Cellar**, then scan the QR code (iPhone and iPad) or type the 6-digit code. Both devices must be on the same Wi-Fi."),
                HelpSection(title: "Compare the codes", body: "Both devices then show the same 6 digits and ask if they match. Check them before answering **They Match**: different codes mean another device is in between, so answer **No**. This is what keeps your cellar private, even on a shared network."),
                HelpSection(title: "Staying in sync", body: "Devices sync by themselves when they are on the same Wi-Fi and Encave is open, a moment after each change. You can edit on each device while apart: changes merge when they meet again. If the same wine was edited on both, the later change wins."),
                HelpSection(title: "Bottles already on the new device", body: "When joining, choose **Combine** to put both cellars together, or **Replace** to keep only the other device's cellar (for example after trying the sample cellar)."),
                HelpSection(title: "Removing a device", body: "Swipe a device in the list and choose **Remove**, or on the Mac, right-click it and choose **Remove Device**. It keeps what it already has but can no longer sync with your devices: each device proves it belongs to the cellar with its own key, and the removed one's key is no longer accepted. To use it with your cellar again later, choose **Stop Syncing** on it, then add it as a new device."),
                HelpSection(title: "Away from home", body: "To sync when your devices are not on the same Wi-Fi, open **Devices › Sync Away from Home**. Choose **Use iCloud** if it is offered (for devices on the same Apple Account), a **WebDAV folder** you already have, or the address of a relay: a small open-source service that only stores encrypted files. You can run your own (instructions in the link on that page) or use one run by someone you trust. Devices that do not sync away from home yet take it up the next time they meet on the same Wi-Fi; turning it off or changing it applies to this device only."),
                HelpSection(title: "WebDAV folder", body: "A NAS (Synology, QNAP), Nextcloud, pCloud and many other services offer WebDAV. Enter the folder's address, for example https://…/remote.php/dav/files/username/Encave for Nextcloud or https://webdav.pcloud.com/Encave for pCloud, with a username and an app password if the service offers one. Encave creates the folder if needed and checks that it can write there. Only encrypted files are stored in it. The username and password stay in the Keychain on this device: the other devices receive only the address, and show **Sign in to this WebDAV folder** until you enter a login on them too."),
                HelpSection(title: "Privacy", body: "On the same Wi-Fi, devices talk directly; no server is involved. Through iCloud, a WebDAV folder or a relay, only encrypted files are stored there. Every exchange is encrypted from end to end. The 6-digit code only helps the devices find each other: the comparison is what protects you."),
            ]),
            HelpArticle(topic: .sync, title: "How sync works",
                        summary: "What travels between your devices, and how changes come together.", systemImage: "arrow.triangle.2.circlepath", sections: [
                HelpSection(title: "Two steps", body: "First you pair your devices, once (see **Your devices**). After that they exchange their changes by themselves: directly when they are on the same Wi-Fi, or through a storage you choose when they are apart. Your cellar is encrypted from end to end all the way."),
                HelpSection(title: "At home, on the same Wi-Fi", body: "Paired devices find each other on the local network and connect directly, without going through the internet. A moment after each change (a bottle opened, added or moved), they send it to each other, photos included, over an encrypted connection. Encave must be open on both."),
                HelpSection(title: "Away from home", body: "Optional, in **Devices › Sync Away from Home**: iCloud (Apple devices on the same Apple Account), a WebDAV folder (a NAS, Nextcloud, pCloud) or a relay you trust. Each device leaves an encrypted copy of the cellar there and reads the others’, every minute while Encave is open unless you choose otherwise. iPhone and iPad also sync in the background when iOS allows, typically every 15 minutes to a few hours; a Mac syncs while Encave is open. The storage only ever sees encrypted files."),
                HelpSection(title: "Sync Now and the status", body: "**Devices** shows where sync stands, on the same Wi-Fi and away from home: syncing now, the last sync, the last attempt that failed and why, changes on this device not sent yet, and when the next sync is due. **Sync Now** syncs right away, with the devices on the same Wi-Fi and through the storage away from home."),
                HelpSection(title: "How often", body: "In **Devices › Sync Away from Home**, **How often** sets how often this device syncs away from home by itself: every minute, every 5 or 15 minutes, or only when you tap **Sync Now**. Background sync on iPhone and iPad follows it, no more often than iOS allows. On the same Wi-Fi, changes are still sent a few seconds after you make them. The setting is for this device only."),
                HelpSection(title: "Changes from other devices", body: "Turn on **Tell Me About Changes from Other Devices** in **Devices** for a notification on this device when bottles are opened, added or removed on another one, for example “2 bottles opened, 1 bottle added” from Marc’s iPhone. Repeated syncs update the same notification. While Encave is in front, a short banner says where the changes come from instead. It is worked out on this device: nothing is sent anywhere."),
                HelpSection(title: "How changes come together", body: "Every bottle, wine, rack, Journal entry and saved search remembers when it last changed. Changes to different things on two devices are all kept. If the same thing was changed on both, the later change wins. A deletion stays deleted, except that a wine comes back if someone added a bottle of it meanwhile on another device, so nothing added is lost. If two bottles end up in the same place, the one moved there last keeps it; the other stays in the cellar without a place. Every device ends up with the same cellar, whatever the order of the syncs."),
                HelpSection(title: "Removing a device", body: "When you remove a device (lost or sold), the cellar’s key changes by itself and the remaining devices receive the new one in an encrypted message. The removed device keeps what it already had but cannot read anything new."),
                HelpSection(title: "What stays on each device", body: "Your AI and web search keys, your WebDAV login, the studio look and other display settings, how often to sync away from home, the notifications of changes, and the choice of storage away from home: devices that do not sync away from home yet take it up the next time they meet on the same Wi-Fi, but turning it off or changing it applies to one device only, so do the same on the others."),
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
            HelpArticle(topic: .webSearch, title: "Searching online",
                        summary: "Complete a wine's details from the web, with the search service you choose.", systemImage: "globe", sections: [
                HelpSection(title: "Off by default", body: "Nothing is looked up online until you choose a service in **Settings → Web Search**."),
                HelpSection(title: "Choose a service", body: "**Brave Search** and **Tavily** need an API key, created on their website; both offer a free monthly allowance. **SearXNG** is a search engine you can run yourself, for example on a NAS: enter its address, such as `http://nas.local:8080` (plain `http://` only for a server on your local network; `https://` otherwise). Its JSON format must be on: add `json` under `search: formats:` in its `settings.yml`. Tap **Test Search** to check."),
                HelpSection(title: "Search Online", body: "On a wine's page, or while adding or editing a wine, tap **Search Online**. The search terms are shown first and you can change them; nothing is sent until you tap **Search**. Each result shows its title, a snippet and the site, and opens in your browser.", systemImage: "globe"),
                HelpSection(title: "Current price", body: "On a wine's page, **Find Current Price** searches for the wine, its bottle size and the word “price”. Every price found is listed with its site; prices for several bottles, delivery or another size are left out. The middle value in your currency is suggested: change it or tap another price, then **Save Price**. Insights adds up these prices for the bottles in your cellar as the **estimated value today**. With Apple Intelligence, **Estimate on This Device** reads the prices on the device.", systemImage: "tag"),
                HelpSection(title: "Pictures", body: "On a wine's page, **Change Picture…** then **Search Pictures** finds pictures of the bottle with the same service. The one you tap is downloaded from its site and shown for the wine; the label photo is always kept. The thumbnails and the picture come from the sites that host them, which see the request. Pictures from shops are for your private use.", systemImage: "photo.on.rectangle.angled"),
                HelpSection(title: "Suggest Details", body: "On devices with Apple Intelligence, **Suggest Details** reads the results on the device and proposes the producer, region, appellation, country, grapes and drinking window. Each suggestion is shown next to the current value, and only the ones you select are used. The results are not sent to any AI service.", systemImage: "sparkles"),
                HelpSection(title: "What is sent", body: "Only the search terms, to the service you chose: by default the producer, the name, the appellation and the vintage, and for a price the bottle size and the word “price”. Your cellar, notes, prices and places are never sent. The key is stored in the Keychain on this device."),
            ]),
            HelpArticle(topic: .data, title: "Backup, export and import",
                        summary: "Keep a copy and move your cellar.", systemImage: "externaldrive", sections: [
                HelpSection(title: "Back up", body: "**Back Up (JSON)** saves your whole cellar: wines, bottles, racks, the Journal and settings, but not the photos. Import it on another device to restore it; nothing already there is replaced."),
                HelpSection(title: "Spreadsheet", body: "**Export Spreadsheet (CSV)** opens in Numbers or Excel, one line per bottle with its place."),
                HelpSection(title: "Printed inventory", body: "**Inventory for Printing (PDF)** lists every wine with its number of bottles, places and purchase value: for your insurer, a move, or your family. The values are what you paid; current prices found online (**Find Current Price**) appear in Insights, not in the inventory."),
                HelpSection(title: "Coming from another app", body: "Export your cellar as CSV from CellarTracker, a spreadsheet or another app, then choose **Import…**. Columns are recognized by their name in English or French (wine, producer, vintage, color, quantity, location, bin, price, drinking window…). A Vivino history becomes wines tasted elsewhere in the Journal, with your ratings and notes. The file is read on this device."),
                HelpSection(title: "Photos and documents", body: "Label photos and documents stay on your devices and sync between them, but backups do not include their files yet: a backup lists the documents, and they show as not on this device until a device that has them syncs."),
            ]),
            HelpArticle(topic: .privacy, title: "Privacy",
                        summary: "Your cellar stays yours.", systemImage: "lock.shield", sections: [
                HelpSection(body: "Your cellar is stored only on your devices. There is no account, no tracking and no advertising."),
                HelpSection(body: "Sync between your devices is encrypted from end to end. On the same Wi-Fi they talk directly; through iCloud, a WebDAV folder or a relay, only encrypted files are stored, and nobody else can read them."),
                HelpSection(body: "Apart from sync, only two things can leave the device, and only if you set them up yourself: the label photo, sent to your AI service, and the search terms of a wine, sent to your web search service when you tap Search. When you search pictures, the thumbnails and the picture you choose are downloaded from the sites that host them, which see the request."),
                HelpSection(body: "Encave is open source: anyone can check what the app does."),
            ]),
            HelpArticle(topic: .shortcuts, title: "Shortcuts and Siri",
                        summary: "Keyboard shortcuts, Siri, Shortcuts and Spotlight.", systemImage: "keyboard", sections: [
                HelpSection(title: "⌘N", body: "Add bottles"),
                HelpSection(title: "⌥⌘N", body: "Note a wine tasted elsewhere"),
                HelpSection(title: "⇧⌘N", body: "Add a rack"),
                HelpSection(title: "⌘1 – ⌘4", body: "Cellar, Racks, Tonight, Journal"),
                HelpSection(title: "⌘+ / ⌘–", body: "Zoom a rack in and out"),
                HelpSection(title: "⌘Z", body: "Undo, while the banner with Undo shows"),
                HelpSection(title: "⌥⌘T", body: "Show the cellar as a table or a list (Mac)"),
                HelpSection(title: "Menu bar (Mac)", body: "The glass in the menu bar lists the bottles to open now and where they are; click one to open it. Turn it off in **Settings**.", systemImage: "menubar.arrow.up.rectangle"),
                HelpSection(title: "Other apps and AI assistants", body: "In Shortcuts, **Get Cellar Contents** gives your bottles as text or JSON (all of them, those to open now, on order…), and **Get Wine Details** gives one wine with its tasting notes. An app on this device that runs shortcuts, such as a local AI assistant, can use them to answer questions about your cellar. Encave itself sends nothing.", systemImage: "square.stack.3d.up"),
                HelpSection(title: "Siri and Shortcuts", body: "Ask Siri “What should I open tonight in Encave?”, “Where is Chablis in Encave?” or “Add a bottle to Encave”. The same actions are in the Shortcuts app. Wines also appear in Spotlight search. The answers are worked out on this device."),
            ]),
        ]
    }

    static func article(_ topic: HelpTopic) -> HelpArticle? { all.first { $0.topic == topic } }
}
