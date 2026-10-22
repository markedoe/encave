# Shortcuts for other apps and AI assistants

On iPhone, iPad and Mac (not on Android yet), Encave offers Shortcuts actions that return the cellar as text, so another app
on the same device (for example a local AI assistant) can answer questions
about it. The text is built on the device and handed only to the shortcut
that runs the action. Encave itself sends nothing over the network.

| Action | Returns |
|--------|---------|
| Get Cellar Contents | The bottles as text (one line per wine) or JSON. Options: which bottles (all, to open now, to drink soon, ready, too young, past their peak, on order, kept for an occasion) and words to look for (name, producer, region, grape, tag). |
| Get Wine Details | One wine as text: details, maturity, where its bottles are, tags, what they are kept for, notes and past tastings. |
| What to Open Tonight | A short spoken answer naming up to three bottles at their best, with their places. |
| Find a Wine | Where the bottles of a wine are. |
| Bottles to Drink Soon | How many bottles are in the last year of their drinking window. |
| Add Bottles | Opens Encave on the label scanner. |
| Open Wine | Opens a wine's page in Encave. |

Get Cellar Contents lists at most 500 wines; the header line then says how
many were left out.

Siri understands, in English: "What should I open tonight in Encave?", "What
to drink tonight with Encave?", "Where is *wine* in Encave?", "Find *wine* in
Encave", "What should I drink soon in Encave?", "Add a bottle to Encave" and
"Scan a wine label with Encave".

The returned text always uses English field names and words (for example
`drink soon`, `too young`) so tools can rely on them, whatever the app's
language. The text form starts with a header line and a `Columns:` line:

```
Encave cellar, 2026, all bottles: 2 wines, 4 bottles.
Columns: wine | producer | style | origin | grapes | window | maturity | bottles | places | tags | kept for
1. Chablis 1er Cru 2019 | Domaine A | white | Chablis, Burgundy, France | Chardonnay | 2022-2028 | ready | 2 | Main rack · B3; Main rack · B4 |  |
```

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
