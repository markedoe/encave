# Privacy policy · Politique de confidentialité

> **Draft for the owner.** Review it, fill in the contact and date, then publish
> it where the stores can link to it (the apps link to this file:
> `ProjectLinks` in `apple/Encave/App/ProjectLinks.swift` and
> `android/app/src/main/java/app/encave/ui/ProjectLinks.kt`). Keep it true for
> the code: change it with any change to what the apps send.

[English](#english) · [Français](#français)

## English

Effective date: *[date of publication]*

Encave is a wine cellar app for iPhone, iPad, Mac and Android. This policy
covers those apps. In short: **the developer of Encave collects nothing.**
There is no account, no analytics, no advertising, no tracking and no
third-party SDK, and the developer runs no server that the apps talk to.

### What stays on your device

Your cellar (wines, bottles, racks, the Journal, prices, places, notes), its
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

Reminders, notifications of changes from your other devices, widgets, Siri,
Shortcuts and Spotlight, and the on-device features of Apple Intelligence
(asking your cellar, suggesting details, estimating a price) are worked out on
the device.

A backup you make (**Back Up Now**, the weekly backup, or an export) is a
standard file that is **not encrypted**: it goes where you choose to save or
share it.

### What can leave your device, and only if you turn it on

1. **Sync between your devices.** Paired devices exchange your cellar, its
   photos and documents, encrypted from end to end. On the same network they
   connect directly. Away from home, if you turn it on, they go through a
   storage you choose: iCloud (on Apple devices, when offered), a WebDAV
   folder, or a relay (run by you or someone you trust). It stores only
   encrypted files that it cannot read; it sees when your devices sync, how
   much they send and, for a WebDAV server or a relay, their IP addresses. The
   device names and the cellar stay encrypted.
2. **AI label recognition** (off by default). If you choose an AI service and
   enter your own key (or a server of your own), Encave sends **only the label
   photo**, directly from your device to that service, when you scan a label
   or ask it to complete a wine. Your cellar and anything else are never sent.
   The service's own privacy policy applies to the photo.
3. **Web search** (off by default). If you choose a search service, Encave
   sends **only the search terms**: the producer, name, appellation and
   vintage of a wine, and for a price the bottle size. They are shown, and can
   be changed, before anything is sent. Your cellar, notes, prices and places
   are never sent. The results are read on the device. When you search
   pictures, the thumbnails and the picture you pick are downloaded from the
   sites that host them, which see the request; the picture becomes the wine's
   cover and never replaces your label photo.

Plain HTTP is used only for a server on your local network; everything else
uses HTTPS. Links (the source code, this policy, a search result) open in your
browser.

### What the stores may provide

Apple and Google may give developers crash reports and usage statistics from
users who allowed it in the system settings (for example "Share with App
Developers"). Those are collected by Apple or Google under their policies, not
by Encave, and hold no cellar data.

### Children

Encave is about wine and is not meant for children.

### Your rights

The developer holds no data about you: there is nothing to access, correct or
delete on the developer's side. You can export, delete (**Erase all data**, or
removing the app) and restore your data at any time in the app. For the
storage, AI or search service you choose, their own policies apply.

### Changes and contact

Changes to this policy are published here, with the date above. Encave is open
source: anyone can check what the apps do at
<https://github.com/markedoe/encave>. Questions: *[contact to fill in]*, or an
issue on the project page.

## Français

Date d'effet : *[date de publication]*

Encave est une app de cave à vin pour iPhone, iPad, Mac et Android. Cette
politique couvre ces apps. En bref : **le développeur d'Encave ne collecte
rien.** Pas de compte, pas de mesure d'audience, pas de publicité, pas de
pistage ni de SDK tiers, et le développeur n'exploite aucun serveur avec
lequel les apps communiquent.

### Ce qui reste sur votre appareil

Votre cave (vins, bouteilles, casiers, le Journal, prix, lieux, notes), ses
photos et ses documents, ainsi que vos réglages, sont gardés dans le stockage
propre à Encave sur votre appareil. Encave n'ajoute pas de chiffrement à ces
fichiers ; ils sont protégés par l'appareil :

- **iPhone et iPad** : iOS les chiffre (protection des données, accessibles
  après le premier déverrouillage suivant un redémarrage). Ils font partie des
  sauvegardes de l'appareil que vous faites (sauvegarde iCloud ou ordinateur),
  comme pour toute app.
- **Mac** : ils sont dans le conteneur isolé de l'app, chiffrés quand FileVault
  est activé, et font partie de vos sauvegardes Time Machine si vous l'utilisez.
- **Android** : Android chiffre le stockage privé de l'app. Encave les exclut
  des sauvegardes Google dans le cloud ; ils ne passent sur un nouveau
  téléphone que par un transfert direct d'appareil à appareil.

Les clés et mots de passe que vous saisissez (clés d'IA et de recherche web,
identifiants WebDAV, clés de synchronisation) sont gardés dans le trousseau
(Apple) ou chiffrés avec une clé du Keystore Android, sur cet appareil
seulement.

Les rappels, les notifications de modifications venant de vos autres
appareils, les widgets, Siri, Raccourcis et Spotlight, ainsi que les fonctions
d'Apple Intelligence sur l'appareil (interroger votre cave, suggérer des
détails, estimer un prix) sont calculés sur l'appareil.

Une sauvegarde que vous faites (**Sauvegarder maintenant**, la sauvegarde
hebdomadaire ou un export) est un fichier standard **non chiffré** : il va là
où vous choisissez de l'enregistrer ou de le partager.

### Ce qui peut quitter votre appareil, et seulement si vous l'activez

1. **La synchronisation entre vos appareils.** Les appareils appairés
   s'échangent votre cave, ses photos et ses documents, chiffrés de bout en
   bout. Sur le même réseau, ils se connectent directement. À distance, si vous
   l'activez, ils passent par un stockage que vous choisissez : iCloud (sur les
   appareils Apple, quand il est proposé), un dossier WebDAV ou un relais
   (exploité par vous ou par quelqu'un de confiance). Il ne stocke que des
   fichiers chiffrés qu'il ne peut pas lire ; il voit quand vos appareils se
   synchronisent, combien ils envoient et, pour un serveur WebDAV ou un relais,
   leurs adresses IP. Les noms des appareils et la cave restent chiffrés.
2. **La reconnaissance des étiquettes par IA** (désactivée par défaut). Si
   vous choisissez un service d'IA et saisissez votre propre clé (ou un serveur
   à vous), Encave envoie **seulement la photo de l'étiquette**, directement de
   votre appareil à ce service, quand vous scannez une étiquette ou lui
   demandez de compléter un vin. Votre cave et tout le reste ne sont jamais
   envoyés. La politique de confidentialité du service s'applique à la photo.
3. **La recherche web** (désactivée par défaut). Si vous choisissez un service
   de recherche, Encave envoie **seulement les termes de recherche** : le
   producteur, le nom, l'appellation et le millésime d'un vin, et pour un prix
   la contenance. Ils sont affichés, et modifiables, avant tout envoi. Votre
   cave, vos notes, vos prix et vos lieux ne sont jamais envoyés. Les résultats
   sont lus sur l'appareil. Quand vous cherchez des images, les miniatures et
   l'image choisie sont téléchargées depuis les sites qui les hébergent, qui
   voient la demande ; l'image devient la couverture du vin et ne remplace
   jamais votre photo d'étiquette.

Le HTTP non chiffré n'est utilisé que pour un serveur de votre réseau local ;
tout le reste passe en HTTPS. Les liens (le code source, cette politique, un
résultat de recherche) s'ouvrent dans votre navigateur.

### Ce que les stores peuvent fournir

Apple et Google peuvent transmettre aux développeurs des rapports de plantage
et des statistiques d'utilisation des utilisateurs qui l'ont autorisé dans les
réglages du système (par exemple « Partager avec les développeurs d'apps »).
Ils sont collectés par Apple ou Google selon leurs politiques, pas par Encave,
et ne contiennent aucune donnée de la cave.

### Enfants

Encave parle de vin et ne s'adresse pas aux enfants.

### Vos droits

Le développeur ne détient aucune donnée sur vous : il n'y a rien à consulter,
corriger ou supprimer de son côté. Vous pouvez à tout moment exporter,
supprimer (**Effacer toutes les données**, ou supprimer l'app) et restaurer vos
données dans l'app. Pour le stockage, le service d'IA ou de recherche que vous
choisissez, leurs propres politiques s'appliquent.

### Modifications et contact

Les modifications de cette politique sont publiées ici, avec la date
ci-dessus. Encave est open source : chacun peut vérifier ce que font les apps
sur <https://github.com/markedoe/encave>. Questions : *[contact à compléter]*,
ou un ticket sur la page du projet.
