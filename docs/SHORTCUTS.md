# Shortcuts for other apps and AI assistants

On iPhone, iPad and Mac (not on Android yet), Encave offers Shortcuts actions that return the cellar as text, so another app
on the same device (for example a local AI assistant) can answer questions
about it. The text is built on the device and handed only to the shortcut
that runs the action. Encave itself sends nothing over the network.

| Action | Returns |
|--------|---------|
| Get Cellar Contents | The bottles as text (one line per wine) or JSON. Options: which bottles (all, to open now, to drink soon, ready, too young, past their peak, on order, kept for an occasion) and words to look for (name, producer, region, grape, tag). |
| Get Wine Details | One wine as text: details, maturity, where its bottles are, tags, what they are kept for, notes and past tastings. |
| Ask My Cellar | A spoken answer to a question about an evening, from Apple Intelligence on the device, naming bottles you have with their positions; returns the wines, and tapping one opens it. Needs Apple Intelligence; elsewhere it says so. Nothing is sent anywhere. |
| Prepare an Evening Request | A request for an assistant app, in the app's language (English or French), followed by your bottles: see [An evening with an assistant app](#an-evening-with-an-assistant-app). Options: occasion (dishes, guests), number of bottles (1 to 24), only bottles ready to drink (on by default). |
| What to Open Tonight | A short spoken answer naming up to three bottles at their best, with their positions. |
| Find a Wine | Where the bottles of a wine are. |
| Bottles to Drink Soon | How many bottles are in the last year of their drinking window. |
| Add Bottles | Opens Encave on the label scanner. |
| Open Wine | Opens a wine's page in Encave. |

Get Cellar Contents lists at most 500 wines; the header line then says how
many were left out.

Siri understands, in English: "Ask my cellar in Encave" (then the question),
"What should I open tonight in Encave?", "What
to drink tonight with Encave?", "Where is *wine* in Encave?", "Find *wine* in
Encave", "What should I drink soon in Encave?", "Add a bottle to Encave" and
"Scan a wine label with Encave". In French: « Demander à ma cave dans
Encave », « Que boire ce soir avec Encave », « Où est *vin* dans Encave »…

The returned text always uses English field names and words (for example
`drink soon`, `too young`) so tools can rely on them, whatever the app's
language. The text form starts with a header line and a `Columns:` line:

```
Encave cellar, 2026, all bottles: 2 wines, 4 bottles.
Columns: wine | producer | style | origin | grapes | window | maturity | bottles | places | tags | kept for
1. Chablis 1er Cru 2019 | Domaine A | white | Chablis, Burgundy, France | Chardonnay | 2022-2028 | ready | 2 | Main rack · B3; Main rack · B4 |  |
```

Each position is named after its storage, then shelf, side, layer and
position, leaving out what does not apply: `Main rack · B3`,
`Cabinet · Shelf 2, back, bottom layer, position 3`, `Diamond bins · Bin B3`.
The field is still called `places`.

For bottles on order, the text adds an `expected` column with their delivery
days.

The JSON form is an array of objects with the keys `wineID`, `name`,
`vintage`, `producer`, `style`, `appellation`, `region`, `country`, `grapes`,
`drinkFrom`, `drinkUntil`, `maturity`, `bottles`, `places`, `tags`, `keptFor`
and `expected` (delivery days of bottles on order, `yyyy-MM-dd`).

## Using it from another app

1. In Shortcuts, make a shortcut named, say, "Encave cellar" with **Get Cellar
   Contents**, then **Stop and Output** with its result.
2. Let the other app run it. On a Mac, any tool can run it from the command
   line: `shortcuts run "Encave cellar"` prints the text; pass input with
   `--input-path` if the shortcut asks for words to look for.
3. Give the text to the assistant as context for the question.

The actions that return text are read-only: they never change the cellar.
**Add Bottles** and **Open Wine** only open the app.

## Ask My Cellar

Ask My Cellar (Demander à ma cave) answers with the same on-device model as
**Ask Your Cellar** in Tonight (Apple Intelligence, iOS, iPadOS and macOS 26):
the question and a compact list of the cellar stay on the device. Siri says
the answer and where the bottles are; under it, each suggested wine opens its
page in Encave. Where Apple Intelligence is not available, it says so and
points to the shortcut below. Like the other actions, it may run while the
phone is locked.

## An evening with an assistant app

**Prepare an Evening Request** returns a text: a short request in the app's
language (choose a selection for the evening, why each bottle, the order of
service, serving temperatures and positions), then the bottles, one line per
wine:

```
Help me choose wine from my cellar for this evening.
Occasion: Dinner for 6 with a tagine, friends who like Loire reds
Propose 3 bottles, taken only from the list below.
For each bottle, say why it fits, when to serve it in the evening (order of service), its serving temperature and where it is in the cellar (its position).
The list holds only bottles ready to drink or to drink soon.
Answer in English.

My cellar in 2026: 2 wines, 3 bottles.
Columns: wine | producer | style | appellation | window | maturity | bottles | positions
1. Chinon Les Picasses 2018 | Olga Raffault | red | Chinon | 2022-2026 | drink soon | 2 | Main rack · B3; Main rack · B4
2. Saumur-Champigny 2020 | Clos Rougeard | red | Saumur-Champigny | 2024-2032 | ready | 1 | Cabinet · Shelf 2, position 3
```

With **Only Bottles Ready to Drink** (on by default) the list holds the
bottles to drink soon, then those ready, without those kept for an occasion;
off, every bottle in the cellar. It never holds prices, notes, places of
purchase, tags or photos. At most 500 wines are listed; the header then says
how many were left out. The same text is built on Android
(`fixtures/evening-request-vectors.json`).

To ask the Claude app (or another assistant app with a Shortcuts action):

1. In Shortcuts, make a new shortcut and add **Encave › Prepare an Evening
   Request** (Préparer une demande de soirée). Tap **Occasion** and choose
   **Ask Each Time**. Set the number of bottles if you like.
2. Add **Claude › Ask Claude** and give it the **Evening Request** result
   of the first action (Shortcuts may name it after the action).
3. Name the shortcut "Soirée" (or "Evening"), so Siri runs it with « Dis
   Siri, Soirée » ("Hey Siri, Evening").

Encave itself sends nothing: like Get Cellar Contents, the action only returns
the text to your shortcut on the device. Passing it to an assistant app is your
choice; the text then leaves the device through that app, under that app's
privacy policy (for Claude, Anthropic's). Nothing happens unless you make
this shortcut.

Without Siri or a shortcut, **Share an Evening Request** (Partager une demande
de soirée) in Tonight asks for the occasion, then opens the share sheet with
the same text; you pick Claude or another app. On iPhone, iPad and Mac the
form also has the number of bottles (1 to 24, or the assistant's choice) and
Only Bottles Ready to Drink (on by default); on a Mac, Share opens the share
menu. Encave sends nothing: the text leaves through the app you pick.
