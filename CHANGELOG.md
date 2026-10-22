# Changelog

All notable changes to Encave are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/).

## [Unreleased]

### Added
- Tonight on every platform: a suggestion that changes each day, a note
  when nothing is ready yet or every bottle is kept for an occasion, a
  monthly reminder offered in context with a way to the notification
  settings, and the same occasion chips, drink sheet and food pairing
  wording on Apple and Android; the drink sheet picks a free bottle first
  and asks before discarding a note
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
