# Changelog

All notable changes to Encave are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/).

## [Unreleased]

### Changed
- Racks are now Storage (Rangements in French): a storage is a rack, a wine
  fridge (Cave à vin réfrigérée) or bins and crates (a bin is a Case); "Cave"
  now means only your whole cellar
- Every position has one name, in the same order everywhere (app, VoiceOver
  and TalkBack, Shortcuts, reports): "Shelf 2, back, bottom layer, position 3";
  racks keep codes such as "B3"
- CSV export adds a readable location column ("Cabinet · Shelf 1, position 6")
  after the slot column; imports ignore it
- The sample cellar's wine fridges are named Cabinet (Armoire)

### Fixed
- Empty positions in shelves and wine fridges show their number only, and
  positions read "Shelf 1, position 6" everywhere (Shortcuts and reports too); layered
  fridges are listed as wine fridges with the fridge icon
- iPhone: Cellar selection actions no longer sit under the tab bar; Mac:
  Cmd-Delete in a text field no longer deletes the selected wine
- Reduce Motion is respected; paired buttons stack at large text sizes; rack
  places tell VoiceOver and TalkBack their maturity, bin contents and check state
- Tonight's suggestion stays the same all day, on both platforms; Another idea
  needs two wines; French discard prompts no longer read as delete
- AI completion says when it found nothing to add, and offers to merge a wine
  that became a duplicate; tilted labels are straightened more often
- A failed save no longer spreads a deletion; the sample cellar is never sent
  to other devices; a refused WebDAV login stops automatic retries; damaged
  backups say so; web search follows redirects only on the same host over HTTPS
- Android matches Apple on drink-down plans, search folding (ß) and currency
  codes typed in lower case
- Help, privacy policy and store notes describe backups exactly (on by default,
  not encrypted, where they are kept)
- Studio photos keep the whole label (script, logos, drawings, blurry lines):
  it is found from its paper, and a photo without a label is shown whole

### Added
- Privacy policy (docs/PRIVACY.md, linked from the apps) and store paperwork
  (docs/STORE.md); App Store listing in French and English (name, subtitle,
  texts, age rating, privacy policy URL, the build to attach) applied from
  docs/store/appstore.json, and a product page header image, by hand-run
  workflows; TestFlight status and external build workflows; every build of
  main goes to the external testers once the screen tour has passed
- Fridge shelves can differ: layers and bottles set per shelf, or Apply to All
  Shelves; adding, duplicating or removing a shelf keeps each bottle on its
  shelf and layer
- Fridge shelves numbered from the top everywhere
- Deleting a rack asks first, naming it and how many bottles it holds; they
  stay in the cellar without a place, and Undo brings the rack back
- Combining with another device's cellar removes the sample cellar first
- Studio look framed on the label: Apple turns a leaning bottle upright,
  cuts it out and frames it on its label (about 80% of the picture,
  the glass running off its edges), straightens a close-up of a label, and
  otherwise shows the photo centred on its label; Android shows the photo cut
  to the band of its label (where its print is densest). On the device only,
  on a backdrop of a softly lit cellar in the wine's color; Apple pictures are
  made again once (photos that gave nothing before are tried again)
- AI label recognition: Complete with AI has a full-width row of its own, reads
  Complete Again once used and shows "Completed by AI" with the day, also on the
  wine's page (wine.aiCompletedAt, synced)
- Wine fridges two deep with shelves in layers show each shelf twice: the
  front bottles, then the back ones, each place tapped directly; with Back
  bottles head-to-tail (synced), the back band shows their necks
- First run: Join a Cellar from Another Device on the empty Cellar; while the
  sample cellar is there, a banner removes it alone (with Undo), and joining
  another device replaces a sample-only cellar
- Beta notices and the sample cellar come from a build flag (EncaveChannel on
  Apple, BuildConfig.BETA on Android), so a store build turns them off
- Mac and iPad: one sheet and section per window, a Wine menu (Edit ⌘E,
  Drink ⌘D, Delete), the Delete key on the Mac, several wines selected in the table,
  New Window (⌃⌘N); menu commands open the window when it is closed
- iPhone and iPad: Settings from every section; Select in the Cellar toolbar
- SwiftUI app for iPhone, iPad and Mac, and a Kotlin and Jetpack Compose app
  for Android phones, tablets and foldables: Cellar, Racks, Tonight and Journal
- Local storage of the cellar, sample cellar, French and English
- Design tokens shared across platforms
- Label scanning on the device, barcodes that recognize wines you already
  have, several photos per wine, serving temperature and decanting
- Optional AI label recognition with your own key (ChatGPT, Claude, Gemini
  and other cloud providers, or Ollama, vLLM, LM Studio on your network); only
  the label photo is sent
- Optional web search (Brave Search, Tavily, SearXNG): Search Online, Find
  Current Price and Search Pictures; only the search terms are sent
- Documents attached to a wine or a bottle (up to 8 MB), synced and encrypted
- Racks drawn as they look: grids, honeycombs, wine fridges with depth or with
  bottles in layers and two zones, bins, irregular rows; rack check
- Tonight: maturity, why this window, food pairing, bottles kept for an
  occasion; Ask your cellar on Apple Intelligence devices
- Tonight on every platform: a suggestion that changes each day, a note
  when nothing is ready yet or every bottle is kept for an occasion, a
  monthly reminder offered in context with a way to the notification
  settings, and the same occasion chips, drink sheet and food pairing
  wording on Apple and Android; the drink sheet picks a free bottle first
  and asks before discarding a note
- Journal: one diary of the bottles opened from the cellar and the wines
  tasted elsewhere (place, rating, notes and tags), with filters and a
  shopping list that adds bottles to the cellar once bought; one "+" asks
  where the wine was drunk; Insights and the year in wine
- Sync between your devices, end-to-end encrypted: pairing with a QR code or a
  6-digit code, directly on the same Wi-Fi, or away from home through a WebDAV
  folder, a self-hostable relay or iCloud (Apple)
- Backups on every platform: Back Up Now makes a readable ZIP archive (the
  cellar as JSON, photos and documents under their names, not encrypted) that
  restores on iPhone, iPad, Mac and Android; previous versions of the cellar
  kept on the device (7 daily, 4 weekly); a weekly archive, on by default and
  not encrypted, the last two kept in Files (On My iPhone › Encave), the Mac
  app's container or Download/Encave; restore with a preview, Add What Is
  Missing or Replace This Cellar (synced to the other devices), and Undo
- Import from CellarTracker, Vivino and spreadsheets; CSV export; printable
  PDF inventory
- Monthly and delivery reminders; Siri, Shortcuts actions and Spotlight
  (Apple); a menu bar glass (Mac); home screen widgets (Android)
- In-app help with topics, help on form fields (tooltips on the Mac), and a
  link to create an API key for every AI provider
- Sync, the cellar file and backups keep fields written by a newer version of
  Encave and send them back unchanged, instead of erasing them
