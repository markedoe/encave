# Changelog

All notable changes to Encave are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/).

## [Unreleased]

### Added
- Studio look framed on the label: Apple turns the photo level from its text,
  cuts the bottle out and frames it on its label (about 80% of the picture,
  the glass running off its edges), straightens a close-up of a label, and
  otherwise shows the photo centred on its label; Android shows the photo cut
  to the band of its label (where its print is densest). On the device only,
  on a backdrop of a softly lit cellar in the wine's color; Apple pictures are
  made again once (photos that gave nothing before are tried again)
- First run: Join a Cellar from Another Device on the empty Cellar; while the
  sample cellar is there, a banner removes it alone (with Undo), and joining
  another device replaces a sample-only cellar
- Beta notices and the sample cellar come from a build flag (EncaveChannel on
  Apple, BuildConfig.BETA on Android), so a store build turns them off
- Mac and iPad: one sheet and section per window, a Wine menu (Edit ⌘E,
  Drink ⌘D, Delete ⌘⌫), the Delete key, several wines selected in the table,
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
  cellar as JSON, photos and documents under their names) that restores on
  iPhone, iPad, Mac and Android; previous versions of the cellar kept on the
  device (7 daily, 4 weekly); a weekly archive in Files (On My iPhone ›
  Encave) or Download/Encave; restore with a preview, Add What Is Missing or
  Replace This Cellar (synced to the other devices), and Undo
- Import from CellarTracker, Vivino and spreadsheets; CSV export; printable
  PDF inventory
- Monthly and delivery reminders; Siri, Shortcuts actions and Spotlight
  (Apple); a menu bar glass (Mac); home screen widgets (Android)
- In-app help with topics, help on form fields (tooltips on the Mac), and a
  link to create an API key for every AI provider
