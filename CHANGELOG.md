# Changelog

All notable changes to Encave are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/).

## [Unreleased]

### Added
- Honest AI fields: the label request asks, for each detail, whether it was
  read on the label or comes from the model's knowledge, and to leave a detail
  empty when unsure. The form and the scan summary mark estimates "Estimated by
  AI" until you change them
- What to expect: a wine's page shows typical aromas, structure, serving
  temperature, decanting and pairings, from 72 profiles bundled for common
  appellations, blends, grapes and styles (offline), or from the AI: asked in
  the same label request, or with What to Expect, which sends only the wine's
  identity. Kept on the wine as a new synced field that older versions keep. In
  the tasting sheet it stays behind Show What to Expect; after Drink, the
  aromas you noted are compared ("You found 4 of 6 expected aromas")
- AI web search, off by default: a connection to Claude, ChatGPT, Grok or
  OpenRouter can allow web search. Complete from the Web on a wine's page, or
  after adding a wine when turned on, fills only empty details and what to
  expect, lists the pages used and offers Undo; never while reading a label
- Guess the Aromas: pick aromas (and the structure if you like) before seeing
  what to expect, then get points (10 for an expected aroma, 4 for the same
  family, 5 for a level within one) and the accuracy; copy the guesses into the
  tasting. My palate in Insights shows points, level, accuracy, streak, best
  and weakest families and the last games. Games stay on the device that played
- AI label recognition with several models: Settings › AI now has Connections
  (a provider, an optional name, a server address and a key; several per
  provider, for example two OpenRouter accounts) and Models, in order of
  preference (dragged to reorder, picked from the connection's list or typed;
  through OpenRouter only the models that read images). The label photo goes to
  the first model; if it fails (network, timeout, a server that cannot be
  reached, key refused, no credit, too many requests, a server error, a refusal
  or an answer that cannot be read), the next one is tried, one at a time,
  within six minutes in all. The model that read the label is shown, and Read
  Again with Another Model sends the same photo to the model picked; when every
  model fails, the message lists what failed with each. Test checks a model with
  a made-up label bundled with the app. Requests through OpenRouter ask it to
  skip providers that keep or train on the data, unless turned off per
  connection. The earlier provider, model, address and key become the first
  connection and model by themselves; keys stay on the device, one per
  connection, never synced, exported or backed up
- Diamond racks hold bottles in their edge triangles: when creating or editing a
  diamond rack, turn on the triangles at the top, left, bottom or right. Each
  triangle is a bin of its own holding half a bin (adjustable), drawn as a
  triangle, with its count and pile, and named by its side ("Top triangle 3")
  for VoiceOver and TalkBack. Turning a side on moves no bottle; bottles in
  triangles you turn off are listed first. Older versions show the triangles as
  whole bins and keep every bottle in place
- Diamond racks: each bin's count sits at the middle of its diamond
- Guided tasting sheet: under More details in a tasting, aromas as chips grouped
  in families (red fruit, citrus, oak and toast…; those that suit the wine's
  colour first, searchable), structure from 1 to 5 (sweetness, acidity, tannin
  for reds, body, alcohol, length; read aloud by VoiceOver and TalkBack) and the
  appearance (intensity and hue). Everything stays optional: stars alone still
  take one tap, and the free-text notes stay. Aromas are found by search and
  the most noted ones show in Insights
- Ratings out of 5 stars, 20 or 100: a setting of each device. A score out of 20
  or 100 is kept precisely and its stars are always saved too, so older versions
  show stars. One conversion on every device: 16/20 is 90/100 and 4 stars,
  17.5/20 is 93.75/100. The spreadsheet export adds a score column, and imports
  read "17.5/20" and "93/100" as scores
- Critics' and guides' scores on the wine page: the source, a score out of 100
  or 20 or an award, and a note, typed by you (nothing is looked up); in backups
  and the spreadsheet export
- More wine details, under More details (closed, so scanning stays quick):
  alcohol, classification (with suggestions such as Grand Cru or Cru Bourgeois),
  lieu-dit, dosage for sparkling wines, sweetness for still wines, the share of
  each grape, ageing, farming (with a certified mark) and closure. Search finds
  them, Insights counts farming and closures, and the AI label recognition fills
  in the alcohol and classification printed on the label

### Changed
- A bare rating above 5 in an imported spreadsheet is a score out of 100 read
  with the new conversion: 92 is now 4 stars (it was 5), 50 is 1 star (it was 3)
- Storage: the rack now sits at the top of the screen, and empty places are quiet
  while browsing (no visible name, a lighter outline; VoiceOver and TalkBack still
  read "B1, empty"; names show when choosing a place). The key's chips no longer
  repeat the style in words, so wine names are not cut
- Cellar: the bottles come first under "In the cellar", then "On order". The three
  summary tiles all show a chevron and the figures' secondary text is easier to
  read in light and dark mode
- Encave is now under the Functional Source License 1.1 (FSL-1.1-ALv2) instead
  of the AGPL-3.0: each version becomes Apache-2.0 two years after its release.
  The relay stays under the AGPL-3.0. Settings › About › Licences shows the new
  licence in English and French; contributions need a contributor licence
  agreement
- Find Current Price no longer reads thresholds and ranges ("à moins de
  30 €", "under £20", "10-20 €") as prices, and takes no price from a page that
  does not name the wine (a guide or a producer's page listing other cuvées)
- Prices seen for another bottle size (a Swiss 70 cl for bottles saved as
  75 cl) are listed apart under Other sizes, never suggested; tapping one offers
  to change the size of the wine's bottles and use the price, with Undo
- A price's excerpt shows the product's name, not a guide's or producer's title
- Prices read 70 cl, 50 cl, 1 litre and clavelin (62 cl) bottles, and Swiss
  formats such as "CHF 13.–", "Fr. 13.50" and "13.50 Fr."
- Bottles can be 50 cl, clavelin (62 cl), 70 cl or 1 litre when added, so a
  Swiss 70 cl bottle finds its price
- Find Current Price leaves out case prices a page gives as the bottle's price,
  prices of other vintages on a page that lists several, and sizes from 4.5 to
  18 litres (Impériale, Mathusalem…)
- Racks are now Storage (Rangements in French): a storage is a rack, a wine
  fridge (Cave à vin réfrigérée) or bins and crates (a bin is a Case); "Cave"
  now means only your whole cellar
- Every position has one name, in the same order everywhere (app, VoiceOver
  and TalkBack, Shortcuts, reports): "Shelf 2, back, bottom layer, position 3";
  racks keep codes such as "B3"
- CSV export adds a readable location column ("Cabinet · Shelf 1, position 6")
  after the slot column; imports ignore it
- The sample cellar's wine fridges are named Cabinet (Armoire)
- Diamond bins made as a rack stay a Rack: "Rack · 6 bins" in the Storage
  list, edited as a rack; crates stay Crates
- Android uses the Encave burgundy by default; "Use wallpaper colors" stays in
  Settings, off unless you turned it on
- Tonight suggests the bottles to drink soon first, then the ready ones, each
  in the day's order
- Plain http also works with a server name of your home network (such as
  http://searxng.home:8888 or a .local name) for web search, AI, WebDAV and
  relay; Encave connects only to a local address of that name (a public IPv6
  next to it is left out) and refuses a name that has none
- iPhone, iPad and Mac: a Nextcloud server reached over plain http by a home
  name (a .local name too) now gets requests at its local address; if Encave
  asks for it, add that address to Nextcloud's trusted_domains, or use https
- WebDAV setup warns, in words, when the server address uses plain http: the
  password then travels unencrypted on your network
- Android Tonight shows the monthly reminder offer after the drink soon
  bottles, so they come right after the suggestion
- CSV import tests pin how ratings are read: a bare "16" is on a 100-point
  scale, "16/20" is 4 stars

### Fixed
- Settings › AI: the model lists offer only models that read images, from each
  service's own description when it gives one (OpenRouter, Mistral, xAI) and
  otherwise from its documented model names; a name can still be typed
- Settings › AI: a model can be chosen right where it is needed. A new connection
  offers its models once its key is checked (searchable, OpenRouter lists
  hundreds), and a connection's page lists its models with Add a Model
- Racks on iPhone zoom again with two fingers: the pinch no longer gives way to
  scrolling, which only moved the rack
- The Add a Bottle button of an empty cellar opens the label scanner first, as
  the "+" does (iPhone, iPad and Mac)
- Journal tastings from the cellar show the wine's picture, and the tasting
  shows it at the top with Show Wine, which opens the wine's page (also once
  no bottle of it is left) on the Journal on iPhone, iPad and Android
- On iPhone, a widget, Spotlight or reminder link to a wine with no bottle
  left opens its page instead of an empty Cellar
- CSV import reads "12,50 €", "45.00€" or "45.0000" as the price (not 100
  times more), and skips prices over 100,000; "4/5", "16/20" and "8/10"
  ratings are that share of 5 stars
- "Free shipping from €59.95" is no longer read as a bottle's price
- Plain http to the local network never goes through a proxy set up on the
  device; answers from a relay, WebDAV, AI or search server are limited in size
- Replace This Cellar and Erase All keep a previous version first; with
  "also erase backups", photos and documents go once Undo has passed
- Removing a device changes the cellar key on every device that has it, also
  when the device that removed it does not sync away from home; mixed app
  versions no longer rotate keys in turn
- CSV files from Excel (French) and CellarTracker keep their accents, vintages
  and quantities; prices such as 8.333 no longer come back as 8333
- Restoring a recent backup no longer shows every bottle as changed, and
  Replace no longer sends the whole cellar again
- Undo after editing a storage or swapping two bottles never moves other
  bottles; a bottle already opened cannot be moved
- Scan Again while the AI reads a label no longer mixes two wines (iPhone, iPad)
- Mac sheets fit the window; Android text on wallpaper colours is readable;
  large text no longer cuts figures, charts, cards and the Storage tab
- The relay keeps room for cellars when someone drops anonymous messages, and
  the Cloudflare relay has a daily upload budget
- Android no longer sends the phone model to the services it contacts, and
  camera photos (with their location) are deleted after use
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
- Ask My Cellar (Demander à ma cave) for Siri, Shortcuts and Spotlight: "Ask
  my cellar in Encave", then a question about an evening; Apple Intelligence
  answers on the device with bottles you have, their positions, and opens one
- Prepare an Evening Request (Préparer une demande de soirée) in Shortcuts, and
  Share an Evening Request in Android's Tonight: a request for an evening's
  selection with your bottles and positions, without prices, notes or places
  of purchase, for the assistant app you choose, such as Claude; Encave sends
  nothing (docs/SHORTCUTS.md)
- Share an Evening Request in Tonight on iPhone, iPad and Mac, as on Android:
  the occasion, number of bottles and only ready bottles, then the share
  sheet with the same text, for Claude or another app; no Siri or shortcut
- iPhone and iPad widgets, as on Android: Tonight (the day's bottle and where
  it is; also on the lock screen), Drink Soon (the first three wines, and on
  the lock screen how many bottles) and Cellar (bottles, to keep, to drink
  soon); a tap opens the wine or the section. An Add Bottles control for
  Control Center, the lock screen and the Action button opens the label
  scanner. The widgets read a short summary kept on the device
- Rows of a storage can differ again, and in depth too: when adding or
  editing one, "Rows are not all the same" (for a wine fridge, "Shelves are
  not all the same") sets each row's positions and depth, and on a fridge in
  layers each shelf's layers, depth and head-to-tail, so one shelf can be two
  deep head-to-tail and the next single. Synced as rack.rowDepths and
  rack.rowBackReversed (docs/SYNC.md); older versions show every position
- Mac: drop a wine or a bottle onto a storage in the sidebar to put one bottle
  in its first free position, with Undo (never into a full one); Open in New
  Window for a wine (Mac and iPad, ⌥⌘O); Android tablets and foldables show
  Storage, Tonight and Journal as list and detail panes
- Find Current Price also opens the first five result pages from the device
  and reads the bottle's price on them (product data, then the page text near
  the wine's name), for shops whose search result shows no price; pages are
  read without cookies, and pages on the local network are refused
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
- Erase All Data also erases the previous versions and the weekly backups
  Encave made (a choice, on by default); exported files are never touched
- Replace and Join saves the cellar as a previous version first; Restore with
  Replace brings back the backup's currency and saved searches too
- Store builds offer the sample cellar in Help (Try with a sample cellar)
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
