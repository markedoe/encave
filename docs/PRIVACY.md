> This page has moved to the Encave website: <https://marked-org.github.io/encave-site/privacy/>.
> Cette page a déménagé sur le site d’Encave : <https://marked-org.github.io/encave-site/privacy/>.

# Privacy policy · Politique de confidentialité

[English](#english) · [Français](#français)

## English

Effective date: November 1, 2026

Encave is a wine cellar app for iPhone, iPad, Mac and Android. This policy
covers those apps. In short: **the developer of Encave collects nothing.**
There is no account, no analytics, no advertising, no tracking and no
third-party SDK, and the developer runs no server that the apps talk to.

### What stays on your device

Your cellar (wines, bottles, storage, the Journal, prices, places, notes), its
photos and documents, and your settings are kept in Encave's own storage on
your device. Encave does not add encryption of its own to these files; they
are protected by the device:

- **iPhone and iPad**: iOS encrypts them (data protection, available after
  the first unlock following a restart). They are part of the device backups
  you make (iCloud Backup or a computer), as for any app.
- **Mac**: they are in the app's sandboxed container, encrypted when FileVault
  is on, and part of your Time Machine backups if you use it.
- **Android**: Android encrypts the app's private storage. Encave leaves them
  out of Google's cloud backups; they move to a new phone only with a direct
  device-to-device transfer.

Keys and passwords you enter (AI and web search keys, the WebDAV login, the
sync keys) are kept in the Keychain (Apple) or encrypted with an Android
Keystore key, on that device only.

The aroma game's guesses and scores ("Guess the aromas", "My palate") stay on
the device that played: they are never synced, not even with people sharing
your cellar, and are left out of Encave's backups and exports and of Android's
backups and device transfer.

Reminders, notifications of changes from your other devices, widgets, Siri,
Shortcuts and Spotlight, and the on-device features of Apple Intelligence
(asking your cellar, suggesting details, estimating a price) are worked out on
the device.

Prepare an Evening Request (Shortcuts) and Share an Evening Request (Android,
in Tonight) only hand a text to you: a request for an evening's selection with
your bottles and their positions, without prices, notes or places of purchase.
Encave sends nothing; if you pass it to an assistant app such as Claude, it
leaves the device through that app, under its privacy policy, by your choice.

The iPhone and iPad widgets read a short summary the app keeps on the device,
in a folder shared only with its widgets: counts of bottles, and the names,
vintages, producers and positions of tonight's bottle and of the first wines to
drink soon. They never read the cellar, keys or notes, and use no network. The
lock screen widgets show what you choose to place there, the wine's name
included; Encave marks names and positions as private, so iOS hides them while
the device is locked when your settings ask for it.

The device also keeps earlier versions of your cellar (one for each of the
last 7 days it changed, then one a week for 4 weeks), in the same storage.

**Backups are not encrypted.** The weekly backup is **on by default**: once a
week, Encave makes a ZIP file of your whole cellar, its photos and documents,
by itself, and keeps the last two on the device. It runs when you open Encave,
and in the background: on iPhone and iPad only while sync away from home is on
(iOS decides when), on Android once a day. You can turn it off in Settings.

- **iPhone, iPad and Mac**: the weekly backups are in Encave's own Documents
  folder (on iPhone and iPad, the Files app shows it under On My iPhone or On
  My iPad › Encave). They are left out of the device's backups and Time
  Machine. On iPhone and iPad, removing the app deletes them.
- **Android**: the weekly backups are in the shared **Download/Encave**
  folder. The Files app, apps you allow to access all files, and a computer
  connected by USB can read them. They stay there when you remove the app.

A backup you make yourself (**Back Up Now**, or an export) is a standard file,
not encrypted either: it goes where you choose to save or share it.

### What can leave your device, and only if you turn it on

1. **Sync between your devices.** Paired devices exchange your cellar, its
   photos and documents, encrypted from end to end. On the same network they
   connect directly. Away from home, if you turn it on, they go through a
   storage you choose: iCloud (on Apple devices, when offered), a WebDAV
   folder, or a relay (run by you or someone you trust). It stores only
   encrypted files that it cannot read; it sees when your devices sync, how
   much they send and, for a WebDAV server or a relay, their IP addresses. The
   device names and the cellar stay encrypted. A WebDAV folder on your local
   network may use plain http: the files stay encrypted, but the WebDAV
   username and password then travel unencrypted on that network, and Encave
   warns about it.
2. **AI** (nothing is sent until you set up a model). If you set up AI models
   with your own keys (or a server of your own), Encave sends them
   **information about a wine**, directly from your device, whenever it helps
   fill in or check that wine: the label photo when you scan a label or ask to
   complete a wine, and the wine's details (such as its name as you typed it,
   producer, appellation, region, country, classification, vintage, colour,
   grapes, alcohol and bottle size),
   with the current year (and, for a price estimate, your cellar's currency),
   for what to expect, its drinking window, a price estimate or a completion;
   and, when you tap Suggest Details on a device without Apple Intelligence,
   the search terms and the results of your search. It goes to the first model of your list, and to
   the next one only if the previous one fails or when you ask for another
   reading; never to two at once. What the AI estimates is marked as
   estimated. If a connection allows **web search** (on by default for
   Claude, ChatGPT, Grok and OpenRouter; you can turn it off), the model may
   search the web with these details through its provider's own search
   (through OpenRouter, possibly its search partner, such as Exa); the pages
   it used are listed. Through OpenRouter, by default Encave asks it to use
   only providers that do not keep or train on the data, unless you turn that
   off. Each service's own privacy policy applies to what it receives. Your
   keys stay on your device: they are never synced, exported or backed up.
3. **Web search** (nothing is sent until you choose a service). If you choose
   a search service, Encave sends it search terms made from a wine's details
   (producer, name, appellation, vintage, and for a price the bottle size)
   when you search online, look for a price or a picture; you can change the
   terms and search again. The results are read on the device. When you look
   for a price, Encave then opens the first five result pages from your
   device and reads the bottle's price on them, on the device. Those sites
   see your IP address, the page asked for and the app's name and version (on
   Apple devices, also the system version and your preferred languages, which
   the system sends); no cookies, stored passwords or referring page are
   sent. Each search opens its own connections, closed after it. Encave
   refuses pages whose name leads to your device or your local network. When
   you search pictures, the thumbnails and the picture you pick are
   downloaded from the sites that host them, which see the request (pictures
   from your device or local network are refused too); the picture becomes
   the wine's cover and never replaces your label photo.

**Never sent to an AI, search or web service:** your cellar as a whole or as
a list, who owns it, your storage places and the positions of your bottles,
the prices you paid, where and when you bought, your notes, tastings, ratings
and aroma game, occasions and reservations, names and contacts, accounts,
device and sync identifiers, and your location. Photos lose their metadata
(such as location or camera) before they are sent. Requests carry no cookies
and no identifier of you or your device; like any connection, the service
sees your IP address.

Plain HTTP is used only for a server on your local network, including a name
on your home network (such as a `.local` name): Encave then connects only to a
local address of that name, never to an address outside your network, and
refuses a name that has none. Such a connection never goes through a proxy set
up on the device. Everything else uses HTTPS. Links (the website, this
policy, a search result) open in your browser.

### What the stores may provide

Apple and Google may give developers crash reports and usage statistics from
users who allowed it in the system settings (for example "Share with App
Developers"). Those are collected by Apple or Google under their policies, not
by Encave, and hold no cellar data.

### Children

Encave is about wine and is not meant for children.

### Your rights

The developer holds no data about you: there is nothing to access, correct or
delete on the developer's side. You can export, delete and restore your data at
any time in the app. **Erase all data** empties the cellar (on your other
devices too, if they sync with this one) and, unless you turn off **Also erase
previous versions and backups**, the earlier versions and the weekly backups
Encave made on the device (on Android, the files it wrote itself in
Download/Encave); files you exported or saved elsewhere are never touched. On iPhone, iPad and
Android, removing the app deletes what Encave keeps in its own storage, but not
the weekly backups in Android's Download/Encave folder: delete them in the
Files app. On a Mac, the app's container stays until you delete it. Backups you
saved elsewhere stay where you put them. For the storage, AI or search service you choose, their
own policies apply.

### Changes and contact

Changes to this policy are published here, with the date above. Questions: open an issue on
<https://github.com/marked-org/encave-site/issues>.

## Français

Date d'effet : 29 octobre 2026

Encave est une app de cave à vin pour iPhone, iPad, Mac et Android. Cette
politique couvre ces apps. En bref : **le développeur d'Encave ne collecte
rien.** Pas de compte, pas de mesure d'audience, pas de publicité, pas de
pistage ni de SDK tiers, et le développeur n'exploite aucun serveur avec
lequel les apps communiquent.

### Ce qui reste sur votre appareil

Votre cave (vins, bouteilles, rangements, le Journal, prix, lieux, notes), ses
photos et ses documents, ainsi que vos réglages, sont gardés dans le stockage
propre à Encave sur votre appareil. Encave n'ajoute pas de chiffrement à ces
fichiers ; ils sont protégés par l'appareil :

- **iPhone et iPad** : iOS les chiffre (protection des données, accessibles
  après le premier déverrouillage suivant un redémarrage). Ils font partie des
  sauvegardes de l'appareil que vous faites (sauvegarde iCloud ou ordinateur),
  comme pour toute app.
- **Mac** : ils sont dans le conteneur isolé de l'app, chiffrés quand FileVault
  est activé, et font partie de vos sauvegardes Time Machine si vous l'utilisez.
- **Android** : Android chiffre le stockage privé de l'app. Encave les exclut
  des sauvegardes Google dans le cloud ; ils ne passent sur un nouveau
  téléphone que par un transfert direct d'appareil à appareil.

Les clés et mots de passe que vous saisissez (clés d'IA et de recherche web,
identifiants WebDAV, clés de synchronisation) sont gardés dans le trousseau
(Apple) ou chiffrés avec une clé du Keystore Android, sur cet appareil
seulement.

Les réponses et les scores du jeu des arômes (« Devinez les arômes », « Mon
palais ») restent sur l’appareil qui a joué : ils ne sont jamais synchronisés,
même avec les personnes qui partagent votre cave, et sont exclus des
sauvegardes et des exports d’Encave ainsi que des sauvegardes et du transfert
d’appareil d’Android.

Les rappels, les notifications de modifications venant de vos autres
appareils, les widgets, Siri, Raccourcis et Spotlight, ainsi que les fonctions
d'Apple Intelligence sur l'appareil (interroger votre cave, suggérer des
détails, estimer un prix) sont calculés sur l'appareil.

Préparer une demande de soirée (Raccourcis) et Partager une demande de soirée
(Android, dans Ce soir) ne font que vous remettre un texte : une demande de
sélection pour une soirée avec vos bouteilles et leur position, sans prix,
notes ni lieux d'achat. Encave n'envoie rien ; si vous le transmettez à une app
d'assistant comme Claude, il quitte l'appareil par cette app, selon sa
politique de confidentialité, par votre choix.

Les widgets de l'iPhone et de l'iPad lisent un court résumé que l'app garde sur
l'appareil, dans un dossier partagé seulement avec ses widgets : le nombre de
bouteilles, et les noms, millésimes, producteurs et positions de la bouteille
de ce soir et des premiers vins à boire bientôt. Ils ne lisent jamais la cave,
les clés ni les notes, et n'utilisent pas le réseau. Les widgets de l'écran
verrouillé montrent ce que vous choisissez d'y placer, nom du vin compris ;
Encave indique les noms et les positions comme privés, pour qu'iOS les masque
tant que l'appareil est verrouillé si vos réglages le demandent.

L'appareil garde aussi des versions précédentes de votre cave (une pour
chacun des 7 derniers jours où elle a changé, puis une par semaine pendant
4 semaines), dans le même stockage.

**Les sauvegardes ne sont pas chiffrées.** La sauvegarde hebdomadaire est
**activée par défaut** : une fois par semaine, Encave fait de lui-même un
fichier ZIP de toute votre cave, de ses photos et de ses documents, et garde les
deux derniers sur l'appareil. Elle a lieu quand vous ouvrez Encave, et en
arrière-plan : sur iPhone et iPad seulement tant que la synchronisation loin
de la maison est activée (iOS décide quand), sur Android une fois par jour.
Vous pouvez la désactiver dans les réglages.

- **iPhone, iPad et Mac** : les sauvegardes hebdomadaires sont dans le dossier
  Documents propre à Encave (sur iPhone et iPad, l'app Fichiers l'affiche sous
  Sur mon iPhone ou Sur mon iPad › Encave). Elles sont exclues des sauvegardes
  de l'appareil et de Time Machine. Sur iPhone et iPad, supprimer l'app les
  supprime.
- **Android** : les sauvegardes hebdomadaires sont dans le dossier partagé
  **Download/Encave**. L'app Fichiers, les apps que vous autorisez à accéder à
  tous les fichiers et un ordinateur branché en USB peuvent les lire. Elles y
  restent quand vous supprimez l'app.

Une sauvegarde que vous faites vous-même (**Sauvegarder maintenant** ou un
export) est un fichier standard, non chiffré lui non plus : il va là où vous
choisissez de l'enregistrer ou de le partager.

### Ce qui peut quitter votre appareil, et seulement si vous l'activez

1. **La synchronisation entre vos appareils.** Les appareils appairés
   s'échangent votre cave, ses photos et ses documents, chiffrés de bout en
   bout. Sur le même réseau, ils se connectent directement. À distance, si vous
   l'activez, ils passent par un stockage que vous choisissez : iCloud (sur les
   appareils Apple, quand il est proposé), un dossier WebDAV ou un relais
   (exploité par vous ou par quelqu'un de confiance). Il ne stocke que des
   fichiers chiffrés qu'il ne peut pas lire ; il voit quand vos appareils se
   synchronisent, combien ils envoient et, pour un serveur WebDAV ou un relais,
   leurs adresses IP. Les noms des appareils et la cave restent chiffrés. Un
   dossier WebDAV de votre réseau local peut utiliser http simple : les
   fichiers restent chiffrés, mais le nom d'utilisateur et le mot de passe
   WebDAV circulent alors en clair sur ce réseau, et Encave le signale.
2. **L’IA** (rien n’est envoyé tant que vous n’avez pas configuré de modèle).
   Si vous configurez des modèles d’IA avec vos propres clés (ou un serveur à
   vous), Encave leur envoie des **informations sur un vin**, directement
   depuis votre appareil, chaque fois que cela aide à renseigner ou à
   vérifier ce vin : la photo de l’étiquette quand vous scannez une étiquette
   ou demandez de compléter un vin, et les détails du vin (comme son nom tel
   que vous l’avez saisi, le producteur, l’appellation, la région, le
   pays, la classification, le millésime, la couleur, les cépages, l’alcool
   et le format de la bouteille), avec l’année en cours (et, pour une
   estimation de prix, la devise de votre cave), pour ce à quoi s’attendre, sa
   fenêtre de dégustation, une estimation de prix ou la complétion de la
   fiche ; et, quand vous touchez Suggérer des détails sur un appareil sans
   Apple Intelligence, les termes de recherche et les résultats de votre
   recherche. Ils vont au premier modèle de votre liste, et au suivant
   seulement si le précédent échoue ou si vous demandez une autre lecture ;
   jamais à deux à la fois. Ce que l’IA
   estime est signalé comme estimé. Si une connexion permet la **recherche
   web** (activée par défaut pour Claude, ChatGPT, Grok et OpenRouter ; vous
   pouvez la désactiver), le modèle peut chercher sur le web avec ces détails
   grâce à la recherche propre à son fournisseur (par OpenRouter,
   éventuellement son partenaire de recherche, comme Exa) ; les pages
   utilisées sont listées. Par OpenRouter, par défaut, Encave demande de
   n’utiliser que des fournisseurs qui ne gardent pas les données et ne s’en
   servent pas pour l’entraînement, sauf si vous le désactivez. La politique
   de confidentialité de chaque service s’applique à ce qu’il reçoit. Vos clés
   restent sur votre appareil : elles ne sont jamais synchronisées, exportées
   ni sauvegardées.
3. **La recherche web** (rien n’est envoyé tant que vous n’avez pas choisi de
   service). Si vous choisissez un service de recherche, Encave lui envoie des
   termes de recherche faits des détails d’un vin (producteur, nom,
   appellation, millésime, et pour un prix la contenance) quand vous cherchez
   en ligne, un prix ou une image ; vous pouvez modifier les termes et
   chercher de nouveau. Les résultats sont lus sur l’appareil. Quand vous
   cherchez un prix, Encave ouvre ensuite les cinq premières pages de
   résultats depuis votre appareil et y lit le prix de la bouteille, sur
   l’appareil. Ces sites voient votre adresse IP, la page demandée et le nom
   et la version de l’app (sur les appareils Apple, aussi la version du
   système et vos langues préférées, que le système envoie) ; aucun cookie,
   mot de passe enregistré ni page de provenance n’est envoyé. Chaque
   recherche ouvre ses propres connexions, fermées ensuite. Encave refuse les
   pages dont le nom mène à votre appareil ou à votre réseau local. Quand vous
   cherchez des images, les miniatures et l’image choisie sont téléchargées
   depuis les sites qui les hébergent, qui voient la demande (les images de
   votre appareil ou de votre réseau local sont refusées aussi) ; l’image
   devient la couverture du vin et ne remplace jamais votre photo d’étiquette.

**Jamais envoyé à un service d’IA, de recherche ou du web :** votre cave dans
son ensemble ou sous forme de liste, à qui elle appartient, vos lieux de
rangement et la position de vos bouteilles, les prix que vous avez payés, où
et quand vous avez acheté, vos notes, dégustations, évaluations et le jeu des
arômes, les occasions et les réservations, les noms et les contacts, les
comptes, les identifiants d’appareil et de synchronisation, et votre position.
Les photos perdent leurs métadonnées (comme la position ou l’appareil photo)
avant l’envoi. Les requêtes ne portent ni cookie ni identifiant de vous ou de
votre appareil ; comme toute connexion, le service voit votre adresse IP.

Le HTTP non chiffré n'est utilisé que pour un serveur de votre réseau local, y
compris un nom de votre réseau domestique (comme un nom en `.local`) : Encave ne
se connecte alors qu'à une adresse locale de ce nom, jamais à une adresse hors
de votre réseau, et refuse un nom qui n'en a aucune. Une telle connexion ne
passe jamais par un proxy configuré sur l'appareil. Tout le reste passe en
HTTPS. Les liens (le site, cette politique, un
résultat de recherche) s'ouvrent dans votre navigateur.

### Ce que les stores peuvent fournir

Apple et Google peuvent transmettre aux développeurs des rapports de plantage
et des statistiques d'utilisation des utilisateurs qui l'ont autorisé dans les
réglages du système (par exemple « Partager avec les développeurs d'apps »).
Ils sont collectés par Apple ou Google selon leurs politiques, pas par Encave,
et ne contiennent aucune donnée de la cave.

### Enfants

Encave parle de vin et ne s'adresse pas aux enfants.

### Vos droits

Le développeur ne détient aucune donnée sur vous : il n'y a rien à consulter,
corriger ou supprimer de son côté. Vous pouvez à tout moment exporter, supprimer
et restaurer vos données dans l'app. **Effacer toutes les données** vide la
cave (sur vos autres appareils aussi, s’ils se synchronisent avec celui-ci) et,
sauf si vous désactivez **Effacer aussi les versions précédentes et les
sauvegardes**, les versions précédentes et les sauvegardes hebdomadaires
qu’Encave a faites sur l’appareil (sur Android, les fichiers qu’il a écrits
lui-même dans Download/Encave) ; les fichiers que vous avez exportés ou
enregistrés ailleurs ne sont jamais touchés. Sur iPhone, iPad et Android,
supprimer l’app efface ce qu’Encave garde dans son propre stockage, mais pas
les sauvegardes hebdomadaires du dossier Download/Encave d’Android :
supprimez-les dans l’app Fichiers. Sur Mac, le conteneur de l'app reste jusqu'à ce que vous le supprimiez. Les sauvegardes enregistrées
ailleurs restent là où vous les avez mises. Pour le stockage, le service d'IA ou
de recherche que vous choisissez, leurs propres politiques s'appliquent.

### Modifications et contact

Les modifications de cette politique sont publiées ici, avec la date
ci-dessus. Questions : ouvrez un ticket sur
<https://github.com/marked-org/encave-site/issues>.
