# Encryption and export compliance

Encave uses encryption to keep the user's cellar private between their
devices. This page lists what is encrypted and with what, and how the apps
declare it to the stores. It is not legal advice: review it before each public
release, and whenever the cryptography changes.

## What is encrypted

- **Sync on the same network**: each session between two devices is
  authenticated and encrypted (handshake and frames in `docs/SYNC.md`).
- **Sync away from home**: the files left on the relay, the WebDAV folder or
  iCloud are encrypted with the cellar's key before they leave the device; the
  storage only ever sees ciphertext (`docs/REMOTE_SYNC.md`).
- **Keys on the device**: kept in the Keychain (Apple) or encrypted with a key
  of the Android Keystore.
- **Connections** to the relay, WebDAV, iCloud, the AI service and the web
  search provider use the system's HTTPS (TLS).

The encryption protects the user's own data. It is not limited to
authentication, and it cannot be used as a general encryption tool for other
files.

## Algorithms

Only standard, published algorithms; nothing proprietary:

| Use | Algorithm | Standard |
|-----|-----------|----------|
| Key agreement | X25519 | RFC 7748 |
| Key derivation | HKDF-SHA256 | RFC 5869 |
| Authentication of the handshake | HMAC-SHA256 | RFC 2104 |
| Encryption of frames and files | ChaCha20-Poly1305 | RFC 8439 |
| Content IDs of photos | SHA-256 | FIPS 180-4 |
| Stored secrets (Android) | AES-GCM with an Android Keystore key | NIST SP 800-38D |

## Libraries

- **iPhone, iPad, Mac**: CryptoKit, through swift-crypto, which on Apple
  platforms is CryptoKit itself (BoringSSL is used only on Linux, for the
  tests). Keys in the Keychain; HTTPS through `URLSession`. All of the app's
  encryption is the one built into Apple's operating systems.
- **Android** (Android 15 and later): only the platform's encryption, through
  the standard Java names: `XDH` (X25519) and `ChaCha20/Poly1305/NoPadding`,
  served by Conscrypt, the system's provider; `javax.crypto` for HMAC-SHA256,
  SHA-256 and AES-GCM with the Android Keystore; the platform's TLS for HTTPS.
  No crypto library is shipped in the app. The unit tests run the same code on
  the JDK's provider and on Conscrypt (`SystemCryptoTest`, a test-only
  dependency).

## Apple: App Store Connect

Both Apple apps declare `ITSAppUsesNonExemptEncryption = NO` in
`apple/project.yml`. That is the value Apple prescribes for an app that "only
uses forms of encryption that are exempt from export compliance documentation
requirements", and Apple's table of requirements lists "encryption limited to
that within the Apple operating system" as needing "no documentation required
in App Store Connect", including for distribution in France. So the key does
not say that the app has no encryption; it says that its encryption is the
operating system's, with nothing to upload.

Apple notes that apps using such encryption may still need a year-end
self-classification report to the U.S. government (Bureau of Industry and
Security). Check whether it applies before the public release.

The value must become `YES`, with the documents Apple asks for, if the Apple
apps ever add encryption that is not CryptoKit or the system's (for example a
third-party crypto library, or a proprietary algorithm).

Sources: Apple, "Complying with Encryption Export Regulations" and
"Export compliance documentation for encryption" (App Store Connect Help).

## Android: Google Play

Google Play does not ask export questions for each build. Like the Apple apps,
the Android app uses only the encryption of the operating system, with
standard algorithms: the case for which Apple's table asks no French
declaration on its store. The same reasoning should hold for Android, but
confirm it against ANSSI's rules before distributing the Android app in France.
Keep it that way: a crypto library shipped in the Android app (BouncyCastle,
Tink, libsodium…) would be "an industry standard algorithm, not provided within
the operating system", the case that needs a declaration to ANSSI.
