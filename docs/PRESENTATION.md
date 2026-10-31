# How a wine and its bottles are shown

One presentation of a wine and of its bottles on every screen and on both apps
(Apple: SwiftUI, Android: Compose). A change to one surface follows these
tables, and changes them here first. The shared rules that are data (which
bottle an action acts on, the order bottles are listed in, which storage the
Move sheet opens on) live in CaveKit and the Android core, pinned by
`fixtures/bottle-place-vectors.json`.

## The wine: identity

Always in this order, in three sizes:

| Size | Where | What |
|---|---|---|
| Row | Cellar list, Tonight list, search, Journal, Not in storage, Put a Bottle Here | thumbnail 34 pt/dp · **Name Vintage** · Style · Producer · Appellation (else region) · maturity (short) · place line · ×N |
| Card | Tonight's card, Drink sheet, rack card, cell sheet, Move sheet, Add Bottles (wine already in the cellar), tasting and discovery headers | thumbnail 40–56 · **Name Vintage** · Style · Producer · Appellation · maturity (short) |
| Hero | Wine page | picture (else bottle end) · **Name** · Vintage + Style capsule · Producer · Appellation · Region · Country |

- **Thumbnail**: the wine's picture (its cover, else its label photo, in the
  studio look when on), else its **bottle end** in the style's color with its
  style mark. Never the color alone: the style is also said in words.
  Apple `WineThumbnail(wine:width:)` (`DesignSystem/WineRow.swift`), Android
  `WineThumbnail(wine, width)` (`ui/components/WineRow.kt`).
- **Maturity**: icon in the maturity color and the words; the short words
  ("Ready", « Prêt ») in rows, cards and the table, the full words ("Ready to
  drink", « Prêt à boire ») and the gauge on the wine page only.

## A bottle: its place

Never blank. One symbol per kind of place:

| Place | Text | Apple symbol | Android icon |
|---|---|---|---|
| In a storage | "Main rack · B3" | the storage's symbol | the storage's icon |
| A note of where it is | the note ("Kitchen") | `mappin` | `Place` |
| Neither | Not in storage / Hors rangement | `tray` | `Inbox` |

On a wine row, the bottles' places in place order, "Not in storage" counted as
a place: "Main rack · A1 and 2 more". Apple `CellarModel.placeLabel(for:)` and
`locationIcon(for:)` (`Features/Racks/Rack+UI.swift`, with `BottlePlace`),
Android `Cellar.placeLabel(bottle)`, `Cellar.locationIcon(bottle)` and
`BottlePlaceIcons` (`ui/components/WineRow.kt`). Bottles are listed in place
order (`inPlaceOrder`): storage, shelf or row, depth, layer, position; bottles
out of storage last, oldest first.

## Actions and their order

Sorted by use, then by safety: Drink first; the storage actions next; the rare
ones; the destructive ones last, behind a divider (an explicit
`HorizontalDivider` on Android). An action that would do nothing on a surface is
**left out, never greyed out**.

### A bottle

| # | Action | Wine page row | Rack card (one bottle) | Cell sheet row | Bottle list (rack) | Not in storage row |
|---|---|---|---|---|---|---|
| 1 | Drink | menu, leading swipe (Apple), TalkBack/VoiceOver action | button | button | menu | menu, leading swipe |
| 2 | Put Away… (out of storage) or Move… (stored) | trailing button "Put Away" when out of storage; menu; first trailing swipe | Move (button, in place) | Move (button) | Move… | Put Away (button), menu |
| 3 | Show in Storage | trailing button (scope) when stored; the place is a link too | left out (already there) | left out | left out | left out |
| 4 | Move to Other Storage… | — (Move… opens every storage) | More menu | More menu | — | — |
| 5 | Take Out of Storage | menu (stored only) | More menu | More menu | — | — |
| 6 | Keep for an Occasion… / Free This Bottle | menu | More menu | More menu | — | — |
| 7 | Documents… / Attach a Document… | count button, menu, swipe | paperclip button | More menu | menu | menu |
| 8 | Show Wine | left out (already there) | info button | More menu | — | menu |
| — | divider | | | | | |
| 9 | No Longer in Cellar | menu, outermost trailing swipe (no full swipe) | More menu | More menu | menu | menu |
| 10 | Delete Bottle (entered by mistake) | menu | — | — | — | — |

Take Out of Storage is not destructive: the bottle stays in the cellar,
without a position, with Undo.

### A wine

Cellar list, Tonight list, table (one row), Android table rows; the Mac and
iPad menu bar's Wine menu in the same order.

| # | Action | Notes |
|---|---|---|
| 1 | Drink a Bottle | the bottle Drink would open (`bottleToOpen`: one not kept for an occasion first) |
| 2 | Put Away… | when a bottle has no position: that bottle (`bottleToPutAway`); with no storage yet, opens Storage. ⌥⌘P on the Mac and iPad |
| 2 | Move… | otherwise, when the bottle Drink would open is stored |
| 3 | Show in Storage | the storage holding most of the wine's bottles (`bottleToShow`) |
| 4 | Add Bottles | |
| 5 | Edit Wine | |
| 6 | Open in New Window | Apple, where windows exist |
| 7 | Select | Cellar list only |
| — | divider | |
| 8 | Delete Wine | Undo for one bottle; asked first for several |

Several wines selected: **Put Away in ▸** (each bottle without a position at the
first free position of the storage chosen), **Take Out**, **More** (Keep for an
Occasion, No Longer in Cellar, Delete Wines). In words on iPhone, iPad and
Android, never a symbol alone.

## Putting bottles away

- **Wine page**: a banner above the bottles, only when some have no position:
  "2 bottles not in storage — Put Away" / « 2 bouteilles hors rangement —
  Ranger ». Each such bottle has a **Put Away** button; its place ("Not in
  storage", with a chevron) is a link to the same sheet.
- **Move sheet** (one for Move and Put Away; title "Put Away Bottle" / « Ranger la
  bouteille » or "Move Bottle" / « Déplacer la bouteille »): the wine and where the
  bottle is now; storages with their free positions ("Main rack · 42 free
  positions"), No storage last; opens on the bottle's storage, else the one
  holding most bottles of its wine, else the last one used on this device (while
  it has room), else the one with most free positions (`storageToOpen`); **First
  Free Position** in one tap, or tap a position. Ends with a banner and Undo, and a
  light haptic.
- **Storage**: a "N bottles not in storage" card first, when there are some, opening
  their list (searchable, by wine) with **Put Away** on each and **Put All Away**
  (`putAllAway`: each beside its wine's other bottles, else where there is most
  room). On the Mac, Not in Storage sits in the sidebar; its bottles drag onto a
  storage.
- **A free position** tapped while bottles are out of storage: **Put a Bottle
  Here…** (searchable list) or **Add a Bottle Here**.
- After **Mark Received** and **Put Back in the Cellar**, Apple's banner offers
  **Put Away** beside Undo (Android's snackbar has one action: Undo).

## Wording and symbols

Apple uses Title Case, Android sentence case (Material). The French is the
same on both.

| Concept | English | Français | Apple symbol | Android icon |
|---|---|---|---|---|
| Drink | Drink | Boire | `wineglass` | `LocalBar` |
| Sheet title | Open a Bottle | Ouvrir une bouteille | | |
| Put away one bottle | Put Away… | Ranger… | `tray.and.arrow.down` | `MoveToInbox` |
| Put away several | Put Away in ▸ | Ranger dans ▸ | `tray.and.arrow.down` | `MoveToInbox` |
| Put away all | Put All Away | Tout ranger | `tray.and.arrow.down` | — |
| Relocate | Move… | Déplacer… | `arrow.up.and.down.and.arrow.left.and.right` | `OpenWith` |
| To another storage | Move to Other Storage… | Déplacer vers un autre rangement… | same as Move | `OpenWith` |
| Out of the storages | Take Out of Storage | Sortir du rangement | `tray.and.arrow.up` | `Outbox` |
| State | Not in storage | Hors rangement | `tray` | `Inbox` |
| Locate | Show in Storage | Voir dans le rangement | `scope` | `MyLocation` |
| Go to the wine | Show Wine | Voir le vin | `info.circle` | `Info` |
| Add | Add Bottles | Ajouter des bouteilles | `plus` | `Add` |
| Edit | Edit Wine | Modifier le vin | `pencil` | `Edit` |
| Reserve | Keep for an Occasion… | Garder pour une occasion… | `gift` | `CardGiftcard` |
| Leave | No Longer in Cellar | Plus en cave | `minus.circle` | `RemoveCircleOutline` |
| Delete a bottle | Delete Bottle | Supprimer la bouteille | `trash` | `DeleteForever` |
| Documents | Documents… / Attach a Document… | Documents… / Joindre un document… | `paperclip` | `AttachFile` |
| One tap in the sheet | First Free Position | Première position libre | `arrow.down.to.line` | `VerticalAlignBottom` |
| Wine page section | Tastings | Dégustations | | |
| Bottles header | N bottles in the cellar | N bouteilles en cave | | |
| iPad table toggle | Show Inspector | Afficher l’inspecteur | `sidebar.trailing` | — |

French uses « position » for a place in a storage, never « place » or
« emplacement » (`apple/tools/l10n.py` checks it).

## Accessibility

- VoiceOver and TalkBack offer Drink and Put Away or Move on every bottle row,
  without opening the menu; the place link says what it does ("Shows this bottle
  in its storage", "Puts this bottle away in a storage").
- At the largest text sizes and Android font scale 2.0, action rows wrap or stack
  (`ActionButtonRow`, `ActionButtonPair`); from font scale 1.5, a row's Put away
  button keeps its icon only, with its label for TalkBack.

## Tasted elsewhere

A wine tasted elsewhere has the same tasting as a bottle of the cellar: rating
and note, the full note and the guided sheet, Guess the Aromas, and after Save
the comparison with what to expect (`discovery.tasting`, docs/SYNC.md).
