# Changelog

All notable changes to Encave are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/).

## [Unreleased]

### Added
- First run: Join a Cellar from Another Device on the empty Cellar; while the
  sample cellar is there, a banner removes it alone (with Undo), and joining
  another device replaces a sample-only cellar
- Beta notices and the sample cellar come from a build flag (EncaveChannel on
  Apple, BuildConfig.BETA on Android), so a store build turns them off
- Mac and iPad: one sheet and section per window, a Wine menu (Edit ⌘E,
  Drink ⌘D, Delete ⌘⌫), the Delete key, several wines selected in the table,
  New Window (⌃⌘N); menu commands open the window when it is closed
- iPhone and iPad: Settings from every section; Select in the Cellar toolbar
- Backups on every platform: Back Up Now makes a readable ZIP archive (the
  cellar as JSON, photos and documents under their names) that restores on
  iPhone, iPad, Mac and Android; previous versions of the cellar kept on the
  device (7 daily, 4 weekly); a weekly archive in Files (On My iPhone ›
  Encave) or Download/Encave; restore with a preview, Add What Is Missing or
  Replace This Cellar (synced to the other devices), and Undo
- SwiftUI app for iPhone, iPad and Mac: Cellar, Racks, Tonight and Journal
- Local storage of the cellar, sample cellar, French and English
- Design tokens shared across platforms
- Label scanning on the device, wine photos, serving temperature and decanting
- Optional AI label recognition (Claude or an OpenAI-compatible server) with
  your own key, stored in the Keychain; only the label photo is sent
- Journal: one diary of the bottles opened from the cellar and the wines
  tasted elsewhere (place, rating, notes and tags), with filters and a
  shopping list that adds bottles to the cellar once bought; one "+" asks
  where the wine was drunk
- In-app help with topics, help on form fields (tooltips on the Mac), and a
  link to create an API key for every AI provider
