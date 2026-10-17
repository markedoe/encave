#!/usr/bin/env python3
"""French translations for the Apple app.

Writes apple/Encave/Resources/Localizable.xcstrings and InfoPlist.xcstrings.
  python3 apple/tools/l10n.py           # regenerate catalogs
  python3 apple/tools/l10n.py --check   # fail if a UI string has no translation
"""
import glob
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "Encave" / "Resources"

FR = {
    "A backup contains everything and can be imported on another device. CSV opens in Numbers or Excel.":
        "Une sauvegarde contient tout et peut être importée sur un autre appareil. Le CSV s’ouvre dans Numbers ou Excel.",
    "About": "À propos",
    "Add": "Ajouter",
    "Add Bottle": "Ajouter une bouteille",
    "Add Bottles": "Ajouter des bouteilles",
    "Add Rack": "Ajouter un casier",
    "Add a Bottle": "Ajouter une bouteille",
    "Add a Rack": "Ajouter un casier",
    "Add bottles with a drinking window to see what is ready tonight.":
        "Ajoutez des bouteilles avec une fenêtre de dégustation pour voir ce qui est prêt ce soir.",
    "Add the bottles you keep at home to know what you have, where it is and when to open it.":
        "Ajoutez les bouteilles que vous gardez chez vous pour savoir ce que vous avez, où elles sont et quand les ouvrir.",
    "Added %@": "Ajoutée le %@",
    "Added %lld × %@": "Ajouté : %1$lld × %2$@",
    "All data erased": "Toutes les données ont été effacées",
    "Already in your cellar?": "Déjà dans votre cave ?",
    "Another idea": "Autre idée",
    "Aperitif": "Apéritif",
    "Appearance": "Robe",
    "Appellation": "Appellation",
    "Automatic": "Automatique",
    "Back Up (JSON)": "Sauvegarder (JSON)",
    "Bottle": "Bouteille",
    "Bottle not found": "Bouteille introuvable",
    "Bottle removed from the cellar": "Bouteille retirée de la cave",
    "Bottles": "Bouteilles",
    "Bottles in this rack stay in your cellar without a position.":
        "Les bouteilles de ce casier restent dans votre cave, sans emplacement.",
    "Bottles opened, last 12 months": "Bouteilles ouvertes, 12 derniers mois",
    "Bottles you open and your notes will appear here.": "Les bouteilles ouvertes et vos notes apparaîtront ici.",
    "Bought at": "Acheté chez",
    "By country": "Par pays",
    "By style": "Par style",
    "By vintage": "Par millésime",
    "Cancel": "Annuler",
    "Celebration": "Fête",
    "Cellar": "Cave",
    "Change": "Changer",
    "Cheese": "Fromage",
    "Cherry, violet, a touch of undergrowth": "Cerise, violette, une touche de sous-bois",
    "Choose a Photo": "Choisir une photo",
    "Choose a photo of the label": "Choisissez une photo de l’étiquette",
    "Choose a vintage to estimate the window.": "Choisissez un millésime pour estimer la fenêtre.",
    "Château, cuvée…": "Château, cuvée…",
    "Clear": "Effacer",
    "Clear filters": "Effacer les filtres",
    "Columns": "Colonnes",
    "Country": "Pays",
    "Currency": "Devise",
    "Date": "Date",
    "Delete Rack": "Supprimer le casier",
    "Delete Wine": "Supprimer le vin",
    "Delete this rack?": "Supprimer ce casier ?",
    "Deleted %@": "%@ supprimé",
    "Describe your racks to see exactly where each bottle is.":
        "Décrivez vos casiers pour voir exactement où se trouve chaque bouteille.",
    "Dessert": "Liquoreux",
    "Details": "Détails",
    "Diamond": "Losange",
    "Diamond bins, 36": "Losanges, 36",
    "Dinner at home": "Dîner à la maison",
    "Dinner with friends": "Dîner entre amis",
    "Done": "OK",
    "Double magnum": "Double magnum",
    "Drink": "Boire",
    "Drink a Bottle": "Boire une bouteille",
    "Drink soon": "À boire bientôt",
    "Drinking window": "Fenêtre de dégustation",
    "Drinking window from %lld to %lld": "Fenêtre de dégustation de %1$lld à %2$lld",
    "Edit Rack": "Modifier le casier",
    "Edit Wine": "Modifier le vin",
    "Enjoy your %@": "Bonne dégustation : %@",
    "Erase all data": "Effacer toutes les données",
    "Erase all data?": "Effacer toutes les données ?",
    "Estimate automatically": "Estimer automatiquement",
    "Export Spreadsheet (CSV)": "Exporter en tableur (CSV)",
    "Find the right bottle for your dish": "Trouvez la bonne bouteille pour votre plat",
    "Fine tannins, fresh acidity": "Tanins fins, acidité fraîche",
    "Finish": "Finale",
    "Fish": "Poisson",
    "Food pairing": "Accords mets-vins",
    "Food served": "Plat servi",
    "Fortified": "Muté",
    "From": "De",
    "From your cellar": "Dans votre cave",
    "Full note": "Note complète",
    "Full tasting note": "Note de dégustation complète",
    "Game": "Gibier",
    "Gift from": "Offert par",
    "Given away or broken": "Donnée ou cassée",
    "Grapes": "Cépages",
    "Grid": "Grille",
    "Half bottle": "Demi-bouteille",
    "How was it?": "C’était comment ?",
    "Import": "Importer",
    "Imported %lld bottles of %lld new wines.": "%1$lld bouteilles importées, dont %2$lld nouveaux vins.",
    "Import…": "Importer…",
    "Insights": "Statistiques",
    "Its bottles, drinking window and tastings appear here.": "Ses bouteilles, sa fenêtre de dégustation et vos dégustations apparaissent ici.",
    "Journal": "Journal",
    "Keep": "À garder",
    "Keep for later": "À garder",
    "Kitchen": "Cuisine",
    "Kitchen, box in the garage…": "Cuisine, carton au garage…",
    "Large cellar, 120": "Grande cave, 120",
    "Layout": "Disposition",
    "Load sample cellar": "Charger une cave d’exemple",
    "Location": "Emplacement",
    "Magnum": "Magnum",
    "Main rack": "Casier principal",
    "More": "Plus",
    "Move": "Déplacer",
    "Move Bottle": "Déplacer la bouteille",
    "Move the bottles out of the removed rows or columns first.":
        "Déplacez d’abord les bouteilles des rangées ou colonnes supprimées.",
    "Move…": "Déplacer…",
    "Name": "Nom",
    "New Bottle…": "Nouvelle bouteille…",
    "New Rack…": "Nouveau casier…",
    "New Rack": "Nouveau casier",
    "No bottle in your cellar matches this dish yet.": "Aucune bouteille de votre cave ne correspond encore à ce plat.",
    "No drinking window": "Pas de fenêtre de dégustation",
    "No location": "Sans emplacement",
    "No rack": "Aucun casier",
    "No racks yet": "Aucun casier",
    "No tastings yet": "Aucune dégustation",
    "No text found. Try a sharper photo.": "Aucun texte trouvé. Essayez une photo plus nette.",
    "Non-vintage": "Non millésimé",
    "Nose": "Nez",
    "Notes": "Notes",
    "Notes about this wine": "Notes sur ce vin",
    "Nothing to suggest yet": "Rien à suggérer pour l’instant",
    "OK": "OK",
    "Occasion": "Occasion",
    "Only %lld free positions: the other bottles will have no position.":
        "Seulement %lld emplacements libres : les autres bouteilles n’auront pas d’emplacement.",
    "Orange": "Orange",
    "Origin": "Origine",
    "Palate": "Bouche",
    "Past its peak": "Après l’apogée",
    "Past peak": "Après l’apogée",
    "Pasta, pizza": "Pâtes, pizza",
    "Pinot Noir, Gamay": "Pinot noir, Gamay",
    "Point the camera at the label": "Visez l’étiquette avec l’appareil photo",
    "Pork": "Porc",
    "Positions": "Emplacements",
    "Poultry": "Volaille",
    "Preferences": "Préférences",
    "Preview": "Aperçu",
    "Price per bottle": "Prix par bouteille",
    "Privacy": "Confidentialité",
    "Producer": "Producteur",
    "Purchase date": "Date d’achat",
    "Purchase details": "Détails d’achat",
    "Put Back in the Cellar": "Remettre en cave",
    "Quantity": "Quantité",
    "Quick start": "Démarrage rapide",
    "Rack": "Casier",
    "Rack not found": "Casier introuvable",
    "Rack, 48": "Casier, 48",
    "Racks": "Casiers",
    "Rated %lld out of 5": "Noté %lld sur 5",
    "Reads the label on this device to fill in the name and vintage.":
        "Lit l’étiquette sur cet appareil pour remplir le nom et le millésime.",
    "Ready": "Prêt",
    "Ready first": "Prêts d’abord",
    "Ready to drink": "Prêt à boire",
    "Recently added": "Ajoutés récemment",
    "Red": "Rouge",
    "Red meat": "Viande rouge",
    "Region": "Région",
    "Remove": "Retirer",
    "Restaurant": "Restaurant",
    "Roast duck": "Canard rôti",
    "Rosé": "Rosé",
    "Rows": "Rangées",
    "Sample cellar loaded": "Cave d’exemple chargée",
    "Save": "Enregistrer",
    "Scan the Label": "Scanner l’étiquette",
    "Search your wines": "Rechercher dans vos vins",
    "Select a wine": "Choisissez un vin",
    "Set a drinking window": "Définir une fenêtre de dégustation",
    "Settings": "Réglages",
    "Shellfish": "Fruits de mer",
    "Show in Rack": "Voir dans le casier",
    "Silky, red fruit, long finish.": "Soyeux, fruits rouges, longue finale.",
    "Size": "Format",
    "Something went wrong": "Une erreur est survenue",
    "Sort": "Trier",
    "Sort by": "Trier par",
    "Source code": "Code source",
    "Sparkling": "Effervescent",
    "Spicy": "Épicé",
    "Style": "Style",
    "Suggested": "Suggérée",
    "Sync between your devices and with the people you share a cellar with will be end-to-end encrypted.":
        "La synchronisation entre vos appareils et avec les personnes avec qui vous partagez une cave sera chiffrée de bout en bout.",
    "Tap a position. Tapping another bottle swaps them.": "Touchez un emplacement. Toucher une autre bouteille les échange.",
    "Tap the new position": "Touchez le nouvel emplacement",
    "Tasting": "Dégustation",
    "Tasting Note": "Note de dégustation",
    "Tastings": "Dégustations",
    "Test data": "Données de test",
    "That position is already taken.": "Cet emplacement est déjà occupé.",
    "That position is outside the rack.": "Cet emplacement est hors du casier.",
    "The change could not be saved.": "La modification n’a pas pu être enregistrée.",
    "The file could not be read.": "Le fichier n’a pas pu être lu.",
    "The first free positions are used unless you choose them.":
        "Les premiers emplacements libres sont utilisés, sauf si vous les choisissez.",
    "The text is read on this device to fill in the name, producer and vintage.":
        "Le texte est lu sur cet appareil pour remplir le nom, le producteur et le millésime.",
    "This bottle has already been opened.": "Cette bouteille a déjà été ouverte.",
    "This file is not an Encave backup or a CSV with a name column.":
        "Ce fichier n’est ni une sauvegarde Encave, ni un CSV avec une colonne « name ».",
    "This item no longer exists.": "Cet élément n’existe plus.",
    "This removes every bottle, rack and tasting from this device.":
        "Cela supprime toutes les bouteilles, tous les casiers et toutes les dégustations de cet appareil.",
    "Today": "Aujourd’hui",
    "Tonight": "Ce soir",
    "Tonight, open…": "Ce soir, ouvrez…",
    "Undo": "Annuler",
    "Unknown": "Inconnue",
    "Unknown wine": "Vin inconnu",
    "Until": "À",
    "Use This Label": "Utiliser cette étiquette",
    "Use this if the bottle was logged by mistake.": "À utiliser si la bouteille a été notée par erreur.",
    "Used by Tonight to suggest what to open. You can adjust it anytime.":
        "Sert à « Ce soir » pour suggérer quoi ouvrir. Modifiable à tout moment.",
    "Used for new purchases. Prices already entered keep their currency.":
        "Utilisée pour les nouveaux achats. Les prix déjà saisis gardent leur devise.",
    "Vegetarian": "Végétarien",
    "Version": "Version",
    "Vintage": "Millésime",
    "Where is it?": "Où est-elle ?",
    "White": "Blanc",
    "Wine": "Vin",
    "Wine fridge": "Cave à vin",
    "Wine fridge, 24": "Cave à vin, 24",
    "Wine not found": "Vin introuvable",
    "Wine or note": "Vin ou note",
    "Wine, producer, region, grape, vintage": "Vin, producteur, région, cépage, millésime",
    "With friends": "Entre amis",
    "Your cellar could not be loaded.": "Impossible de charger votre cave.",
    "Your cellar in figures": "Votre cave en chiffres",
    "Your cellar is empty": "Votre cave est vide",
    "Your cellar is stored only on this device.": "Votre cave est enregistrée uniquement sur cet appareil.",
    "Your data": "Vos données",
    "Zoom In": "Agrandir",
    "Zoom Out": "Réduire",
    "%@ and %lld more": "%1$@ et %2$lld autres",
    "%lld stars": "%lld étoiles",
    "%@, %@": "%1$@, %2$@",
    "%@, empty": "%@, vide",
    "%lld of %lld": "%1$lld sur %2$lld",
    "%lld of %lld chosen": "%1$lld sur %2$lld choisis",
    "%lld of %lld positions used": "%1$lld sur %2$lld emplacements occupés",
    "average rating": "note moyenne",
    "bottles": "bouteilles",
    "bottles in the cellar": "bouteilles en cave",
    "bottles opened": "bouteilles ouvertes",
    "classic style for this dish": "style classique pour ce plat",
    "different wines": "vins différents",
    "purchase value": "valeur d’achat",
    "ready to drink": "prêt à boire",
    "region known for it": "région réputée pour ça",
    "suited grape": "cépage adapté",
    "to drink soon": "à boire bientôt",
    "wines": "vins",
    # AI label recognition, photos and serving
    "AI label recognition": "Reconnaissance des étiquettes par IA",
    "API key": "Clé d’API",
    "Add a Photo": "Ajouter une photo",
    "Add your API key in Settings.": "Ajoutez votre clé d’API dans les réglages.",
    "Choose an AI provider in Settings.": "Choisissez un service d’IA dans les réglages.",
    "Complete with AI": "Compléter avec l’IA",
    "Decanting": "Carafage",
    "Label photo": "Photo de l’étiquette",
    "Model": "Modèle",
    "Not needed": "Inutile",
    "Off": "Désactivée",
    "Only the label photo is sent, directly to this service, when you scan a label or tap “Complete with AI”. Your cellar is never sent. The key is stored in the Keychain on this device.": "Seule la photo de l’étiquette est envoyée, directement à ce service, quand vous scannez une étiquette ou touchez « Compléter avec l’IA ». Votre cave n’est jamais envoyée. La clé est conservée dans le trousseau de cet appareil.",
    "OpenAI-compatible": "Compatible OpenAI",
    "Optional. Reads the label from a photo with the AI service you choose, using your own API key.": "Facultatif. Lit l’étiquette sur une photo avec le service d’IA de votre choix et votre propre clé d’API.",
    "Photo": "Photo",
    "Provider": "Fournisseur",
    "Reading the label…": "Lecture de l’étiquette…",
    "Reads the label on this device, then completes the details with the AI service set in Settings.": "Lit l’étiquette sur cet appareil, puis complète les informations avec le service d’IA choisi dans les réglages.",
    "Remove Photo": "Supprimer la photo",
    "Replace Photo": "Remplacer la photo",
    "Saved with the wine": "Enregistrée avec le vin",
    "Scan Again": "Scanner à nouveau",
    "Server URL": "Adresse du serveur",
    "Serving": "Service",
    "Temperature": "Température",
    "The AI service returned error %lld.": "Le service d’IA a renvoyé l’erreur %lld.",
    "The answer from the AI service could not be read.": "La réponse du service d’IA est illisible.",
    "The model declined to read this photo.": "Le modèle a refusé de lire cette photo.",
    "%lld min": "%lld min",
    # AI providers
    "AI": "IA",
    "API key (optional)": "Clé d’API (facultative)",
    "Check Key and Load Models": "Vérifier la clé et charger les modèles",
    "Choose a model in Settings.": "Choisissez un modèle dans les réglages.",
    "Choose from the list": "Choisir dans la liste",
    "Choose…": "Choisir…",
    "Enter a model name": "Saisir un nom de modèle",
    "Get an API key": "Obtenir une clé d’API",
    "Local server (Ollama, LM Studio…)": "Serveur local (Ollama, LM Studio…)",
    "Not valid": "Non valide",
    "On iPhone and iPad, use your computer’s address on the network instead of localhost. Choose a model that reads images for label recognition.": "Sur iPhone et iPad, utilisez l’adresse de votre ordinateur sur le réseau au lieu de localhost. Choisissez un modèle qui lit les images pour reconnaître les étiquettes.",
    "Optional. Reads labels from a photo with the AI service you choose, using your own API key.": "Facultatif. Lit les étiquettes sur une photo avec le service d’IA de votre choix et votre propre clé d’API.",
    "%lld models": "%lld modèles",
    # Racks
    "5 rows of 8": "5 rangées de 8",
    "6 rows of 8": "6 rangées de 8",
    "8 rows of 10": "8 rangées de 10",
    "Add a Bottle Here": "Ajouter une bouteille ici",
    "Add a Bottle to This Bin": "Ajouter une bouteille dans ce casier",
    "Bin %@": "Casier %@",
    "Bins or crates": "Casiers ou caisses",
    "Bins per row": "Casiers par rangée",
    "Bottles in the widest row": "Bouteilles dans la rangée la plus large",
    "Bottles per bin": "Bouteilles par casier",
    "Bottles per cell": "Bouteilles par case",
    "Bottles per row": "Bouteilles par rangée",
    "Bottles per shelf": "Bouteilles par clayette",
    "Cabinet, 10 shelves of 8, 2 deep": "Armoire, 10 clayettes de 8, 2 en profondeur",
    "Crates of 12, 2 × 4": "Caisses de 12, 2 × 4",
    "Crates of 6, 3 × 4": "Caisses de 6, 3 × 4",
    "Diamond bins or crates holding several bottles each": "Casiers en losange ou caisses de plusieurs bouteilles",
    "Diamond bins, 2 × 3 of 10": "Losanges, 2 × 3 de 10",
    "Double depth, 6 × 8": "Double profondeur, 6 × 8",
    "Empty": "Vide",
    "For a rack with a sloped top, a staircase or an irregular cellar wall.": "Pour un casier sous pente, sous un escalier ou contre un mur irrégulier.",
    "Fridge, 2 deep, 6 shelves of 7": "Cave à vin, 2 en profondeur, 6 clayettes de 7",
    "Fridge, 6 shelves of 6": "Cave à vin, 6 clayettes de 6",
    "Grid rack": "Casier classique",
    "Holds %lld bottles.": "Contient %lld bouteilles.",
    "Honeycomb rack": "Casier nid d’abeille",
    "Kind of storage": "Type de rangement",
    "Large, 10 × 12": "Grand, 10 × 12",
    "Offset rows, like metal or terracotta racks": "Rangées décalées, comme les casiers en métal ou en terre cuite",
    "One": "Une",
    "One bottle per cell, single or double depth": "Une bouteille par case, simple ou double profondeur",
    "Row %@": "Rangée %@",
    "Rows deep": "Rangs en profondeur",
    "Rows of bins": "Rangées de casiers",
    "Rows of different lengths": "Rangées de longueurs différentes",
    "Shelf %@, %lld": "Clayette %@, %lld",
    "Shelves": "Clayettes",
    "Shelves holding a row of bottles, one to three deep": "Clayettes d’une rangée de bouteilles, sur un à trois rangs",
    "Tap a place. Tapping another bottle swaps them; a bin takes the bottle if it has room.": "Touchez un emplacement. Toucher une autre bouteille les échange ; un casier prend la bouteille s’il a de la place.",
    "Tap where to put %@": "Touchez où mettre %@",
    "This place is full.": "Cet emplacement est plein.",
    "Two, one behind the other": "Deux, l’une derrière l’autre",
    "Wine fridge or cabinet": "Cave à vin ou armoire",
    "%lld of %lld bottles": "%lld sur %lld bouteilles",
    "%@, %lld of %lld bottles": "%@, %lld sur %lld bouteilles",
    "%@, empty": "%@, vide",
    "Diamond bins": "Casiers en losange",
    "front": "devant",
    "back": "derrière",
    "middle": "milieu",
    "Kind of rack": "Type de rangement",
    # Discoveries and shopping list
    "Add a tag": "Ajouter un tag",
    "Add to Cellar": "Ajouter à la cave",
    "Added to your shopping list": "Ajouté à votre liste d’achats",
    "At Julie and Marc's": "Chez Julie et Marc",
    "At friends'": "Chez des amis",
    "Bistro on the harbour, Lisbon": "Bistrot sur le port, Lisbonne",
    "Bought": "Acheté",
    "Delete": "Supprimer",
    "Delete Discovery": "Supprimer la découverte",
    "Delete this discovery?": "Supprimer cette découverte ?",
    "Discoveries": "Découvertes",
    "Discovery": "Découverte",
    "Discovery deleted": "Découverte supprimée",
    "Edit": "Modifier",
    "Elsewhere": "Ailleurs",
    "For summer": "Pour l’été",
    "Gift idea": "Idée cadeau",
    "Gift idea, for summer…": "Idée cadeau, pour l’été…",
    "Liked a bottle at friends’ or in a restaurant? Scan it to remember it, add notes and tags, and put it on your shopping list.": "Une bouteille vous a plu chez des amis ou au restaurant ? Scannez-la pour vous en souvenir, ajoutez des notes et des tags, et mettez-la sur votre liste d’achats.",
    "Mineral and salty, great with grilled fish.": "Minéral et salin, parfait avec le poisson grillé.",
    "No discoveries yet": "Aucune découverte",
    "Place": "Lieu",
    "Price seen": "Prix vu",
    "Remember a Wine": "Noter un vin",
    "Remember a Wine…": "Noter un vin…",
    "Remember a wine tasted elsewhere": "Noter un vin goûté ailleurs",
    "Remember a wine tasted or seen elsewhere": "Noter un vin goûté ou vu ailleurs",
    "Remove from List": "Retirer de la liste",
    "Restaurant, city, friends’ names…": "Restaurant, ville, prénoms des amis…",
    "Saved to your discoveries": "Enregistré dans vos découvertes",
    "Shop": "Caviste",
    "Shopping list": "Liste d’achats",
    "Show": "Afficher",
    "Tags": "Tags",
    "Tasting event": "Salon, dégustation",
    "To buy": "À acheter",
    "Travel": "Voyage",
    "Very fresh, perfect with the cheese platter.": "Très frais, parfait avec le plateau de fromages.",
    "What you liked, the dish, the moment…": "Ce qui vous a plu, le plat, le moment…",
    "Where": "Où",
    "Wine, place, note or tag": "Vin, lieu, note ou tag",
    "Wines to buy appear in the shopping list in Journal. Discoveries are not counted as bottles in your cellar.": "Les vins à acheter apparaissent dans la liste d’achats du Journal. Les découvertes ne comptent pas comme des bouteilles de votre cave.",
    "Your impression": "Votre impression",
}

# Keys with plural forms: English one/other and French one/other.
PLURALS = {
    "%lld bottles": (("%lld bottle", "%lld bottles"), ("%lld bouteille", "%lld bouteilles")),
    "%lld bottles in the cellar": (("%lld bottle in the cellar", "%lld bottles in the cellar"),
                                   ("%lld bouteille en cave", "%lld bouteilles en cave")),
    "%lld positions": (("%lld position", "%lld positions"), ("%lld emplacement", "%lld emplacements")),
}

INFO_PLIST = {
    "NSCameraUsageDescription": (
        "Encave uses the camera to read wine labels. Images stay on this device.",
        "Encave utilise l’appareil photo pour lire les étiquettes. Les images restent sur cet appareil.",
    ),
    "NSLocalNetworkUsageDescription": (
        "Encave connects to the AI server on your network that you set in Settings.",
        "Encave se connecte au serveur d’IA de votre réseau choisi dans les réglages.",
    ),
}

UI_CALL = re.compile(r'(?:Text|Label|Button|Section|LabeledContent|navigationTitle|Toggle|TextField|Picker|Stepper|'
                     r'DisclosureGroup|SecureField|ContentUnavailableView|Tab|ChoiceChip\(title:|RackPreset\(title:|caption:|ChartCard\(title:|DatePicker|'
                     r'String\(localized:|alert|confirmationDialog|Link|prompt: Text)\s*\(?\s*"((?:[^"\\]|\\.)*)"')
CASE_STRING = re.compile(r'case \.[a-zA-Z]+: "((?:[^"\\]|\\.)*)"')


def unit(value):
    return {"stringUnit": {"state": "translated", "value": value}}


def to_key(literal):
    """Swift interpolation → format key ("\\(count) bottles" → "%lld bottles")."""
    def repl(match):
        expr = match.group(1)
        if "count" in expr or any(op in expr for op in (" - ", " + ", " * ", "reduce")):
            return "%lld"
        stringy = any(t in expr for t in ("name", "Name", "label", "first", "formatted", "location", "displayName",
                                            "letters", "Letters", "place"))
        return "%@" if stringy else "%lld"
    return re.sub(r"\\\((.*?)\)(?=[^)]*(?:\\\(|$))", repl, literal) if "\\(" in literal else literal


def extract():
    keys = set()
    for path in glob.glob(str(ROOT / "Encave" / "**" / "*.swift"), recursive=True):
        source = Path(path).read_text()
        keys.update(UI_CALL.findall(source))
        if "LocalizedStringKey" in source:
            keys.update(CASE_STRING.findall(source))
        for block in re.findall(r"\[LocalizedStringResource\] = \[(.*?)\]", source, re.S):
            keys.update(re.findall(r'"([^"]+)"', block))
        keys.update(re.findall(r'\("((?:[^"\\]|\\.)*)", \d+, \d+, \.(?:grid|diamond)\)', source))
    # Skip SF Symbol names and other identifiers.
    return {k for k in keys if k.strip() and not re.fullmatch(r"[a-z0-9.]+", k)}


def write():
    strings = {key: {"localizations": {"fr": unit(value)}} for key, value in FR.items() if key.strip() == key}
    for key, ((en_one, en_other), (fr_one, fr_other)) in PLURALS.items():
        strings[key] = {"localizations": {
            "en": {"variations": {"plural": {"one": unit(en_one), "other": unit(en_other)}}},
            "fr": {"variations": {"plural": {"one": unit(fr_one), "other": unit(fr_other)}}},
        }}
    catalog = {"sourceLanguage": "en", "strings": dict(sorted(strings.items())), "version": "1.0"}
    (RES / "Localizable.xcstrings").write_text(json.dumps(catalog, indent=2, ensure_ascii=False) + "\n")
    info = {key: {"localizations": {"en": unit(en), "fr": unit(fr)}} for key, (en, fr) in INFO_PLIST.items()}
    (RES / "InfoPlist.xcstrings").write_text(
        json.dumps({"sourceLanguage": "en", "strings": info, "version": "1.0"}, indent=2, ensure_ascii=False) + "\n")
    print(f"Wrote {len(strings)} strings")


def check():
    known = set(FR) | set(PLURALS)
    missing = sorted(k for k in extract() if to_key(k) not in known and k not in known)
    for key in missing:
        print(f"missing French: {key!r} (key {to_key(key)!r})")
    return 1 if missing else 0


if __name__ == "__main__":
    if "--check" in sys.argv:
        sys.exit(check())
    write()
