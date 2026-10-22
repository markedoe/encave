# Changelog

All notable changes to Encave are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/).

## [Unreleased]

### Added
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
