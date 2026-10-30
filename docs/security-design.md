# Kova security design

This page explains how Kova protects what you keep in it, in enough detail to check against the
source. The byte-level format, including every structure and field, is in [FORMAT.md](../FORMAT.md).
To report a vulnerability, see [SECURITY.md](../SECURITY.md).

## What Kova protects against

- **Someone who has your phone while the vault is locked**, including someone who copies the app's
  files off it. Everything in a vault is encrypted with keys that exist only while a vault is open.
- **Someone who makes you open a vault.** A decoy vault, separate vault passphrases and a panic
  credential let you open something without revealing what else is there. The file layout is built
  so that nobody can count your vaults or tell one vault's files from another's.
- **Someone looking at the screen.** The app blocks screenshots and screen recording, and the recent-apps
  view never shows what is inside.

## What it does not protect against

- **A compromised phone.** Malware with root, a modified operating system or a debugger attached
  while a vault is open can read what you can read. The lock screen warns when it detects root, an
  attached debugger or an accessibility service that can read the screen, but it cannot stop them.
- **A weak passphrase.** The key derivation makes each guess expensive, not impossible.
- **The fact that Kova is installed.** The app is visibly a vault, and its files can be recognised as
  Kova's. The protection lies in what an attacker cannot open or count, not in hiding the app.

## Primitives

| Purpose | Algorithm | Library |
|---|---|---|
| Passphrase to key | Argon2id (`crypto_pwhash`, `ALG_ARGON2ID13`), m = 64 MiB, t = 6, p = 1 | libsodium (lazysodium-android 5.2.0) |
| Encryption | XChaCha20-Poly1305 IETF, 24-byte nonces | libsodium |
| Subkeys | BLAKE2b (`crypto_kdf_derive_from_key`), 8-byte contexts | libsodium |
| Tamper-log chain | HMAC-SHA-512-256 (`crypto_auth`) | libsodium |
| Biometric wrap | AES-256-GCM, Android Keystore | Android |
| Sharing with a contact | X-Wing (ML-KEM-768 + X25519), a post-quantum hybrid | BouncyCastle 1.79, ML-KEM, SHA-3 and X25519 only |
| Remote pairing | CPace (`CPACE-RISTR255-SHA512`, draft-irtf-cfrg-cpace-21) | libsodium |

libsodium is the only source of Argon2id and ChaCha20-Poly1305 in the app. The build strips
BouncyCastle down to the classes X-Wing needs and fails if any of its Argon2 or ChaCha20 classes
reach the APK.

The Argon2id cost is set so one derivation takes about half a second on a mid-range phone:
453 ms on a Samsung Galaxy A53. The parameters are recorded on disk when the app is first set
up, and that install always derives its keys with them. A later version can raise the costs for
new installs without locking anyone out of an existing one.

## Keys

```
passphrase ─Argon2id(install salt)─► KEK
                                      │ opens one of 8 slots
                                      ▼
                               keyring key (random, 32 B) ◄── 24-word recovery kit
                                      │
               ┌──────────────────────┼─────────────────────────┐
               ▼                      ▼                         ▼
        keyring blob           entry key per vault        backup key, capture
   (vault names, 16 places)   (BLAKE2b subkey)            store key (subkeys)
                                      │ unseals
                                      ▼
                              vault key, VMK (random, 32 B)
                                      │ BLAKE2b subkeys
                                      ▼
            per-file keys · thumbnail keys · tamper-log keys · capture keypair
```

- **Slots.** `vault.slots` holds a 16-byte install salt, a 10-byte header with the Argon2id
  parameters, and 8 slots of 256 bytes each. Every slot starts as random bytes. A used slot holds the
  keyring key, encrypted under the KEK, with the slot number as associated data, and then more random
  bytes. An unlock derives the KEK once and tries all 8 slots with no early exit, so the time taken
  does not depend on which slot matched, or whether any did. A wrong passphrase and an empty slot
  fail the same way.
- **Keyring.** The keyring key opens one blob with the names of up to 16 vaults. It is always the
  same size, whatever it holds, so it does not reveal how many vaults there are. Opening it reveals
  the names but no vault keys. Each vault key is sealed again, separately, and is unsealed only when
  that vault is opened.
- **A vault with its own passphrase.** Its vault key is sealed under Argon2id of its own passphrase
  instead of under the entry key, so the main passphrase cannot open it.
- **Per-file keys** are derived from the vault key and the file's random 16-byte ID. They are never
  stored.

## Files on disk

Everything a vault holds is stored as blobs, each named after its random ID.

- A blob is encrypted in 64 KiB chunks. Each chunk's nonce is a random 16-byte prefix plus the
  chunk number, and the blob ID is the associated data, so chunks cannot be reordered or moved
  between blobs without failing authentication.
- **Every blob is padded to a fixed size**: 64 KiB, 128 KiB and so on, doubling up to 16 MiB. Larger
  items are split into several 16 MiB blobs that do not reference each other; only the vault's
  encrypted index knows the order.
- **Chaff.** Random blobs of the same sizes sit among the real ones, so an undecryptable blob could
  be chaff or could belong to another vault, and the blob count never equals the item count.
- No file records a plaintext length, a vault count or which slot is in use.

## Biometric unlock

Biometric unlock is off until you turn it on, and your passphrase always works. When it is on,
`vault.bio` holds the same contents as your slot, plus the slot number, encrypted with an AES-256-GCM
key in the Android Keystore. That key is created with:

- `setUserAuthenticationRequired(true)` and `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)`:
  every use needs a fresh Class 3 biometric, and a device PIN or pattern cannot unlock it.
- `setInvalidatedByBiometricEnrollment(true)`: adding or removing a fingerprint or face destroys
  the key, so a biometric you did not have when you turned it on can never open the vault.

The key is not exportable and is protected by the phone's trusted execution environment. Kova does
not ask for StrongBox, which many phones lack. `vault.bio` contains no passphrase and nothing from
which the KEK can be derived.

## Recovery kit

The 24 words are a BIP39 encoding of the keyring key itself: 256 bits plus an 8-bit checksum. No
key derivation is applied, so the words recover your vaults on a new phone with nothing else.
Anyone holding the words can open the vault list without your passphrase, so keep them as safely
as you would the vaults. A vault with its own passphrase has its own 24 words, which encode that
vault's key.

## Leaving the phone

- **Backups** copy blobs that are already encrypted. A backup to a cloud app is one file whose table
  of contents is sealed with XChaCha20-Poly1305 under a subkey of the keyring key, and whose blob
  bytes are covered by an HMAC-SHA256. The 24 words restore either kind of backup.
- **Moving to a new phone** uses a `.vault` file sealed under Argon2id of the vault's passphrase with
  a fresh salt.
- **Sharing with a paired contact** seals the file to the contact's X-Wing public key, so recovering
  it would require breaking both ML-KEM-768 and X25519. **Sharing with a code** derives the key with
  Argon2id from four BIP39 words (44 bits). That is enough for a short-lived file sent to one person,
  not for long-term storage.
- **No byte of a share file names the app.** It is named with random hex and sent as
  `application/octet-stream`.

## No network

Kova does not have Android's `INTERNET` permission. The build removes it from every library that
declares it and fails if it reappears in the merged manifest. Purchases on the Play version are
handled by the Google Play Store app, which runs in its own process with its own permissions.

## Known limits

- **One salt for all 8 slots.** An unlock attempt costs one Argon2id derivation, not eight, for you
  and for an attacker alike. This keeps unlock time constant.
- **Size and count are bounded, not hidden.** The total size of the store and its number of blobs
  put upper bounds on how much you keep, across all vaults together.
- **Repeated snapshots reveal activity.** Someone who copies the phone's storage more than once can
  see which blob changes most often and guess that it is a vault's index.
- **The typed passphrase lingers in memory.** Android's text fields return a string that cannot be
  wiped. Kova copies it into a byte array straight away and wipes that, but the original string stays
  in memory until garbage collection. Vault keys and file keys are wiped after use.
- **`vault.bio` shows that biometric unlock is on** for some vault, though not which one.
- **Panic destroys the way in, not the files.** The panic phrase overwrites every other slot with
  random bytes, deletes `vault.bio` and deletes the backups. The vaults' blobs stay on the phone, and
  each vault's 24-word recovery kit still opens it. Flash storage can also keep old copies of a
  block, so a lab that reads the memory chip directly may be able to find an overwritten slot.

[FORMAT.md section 10](../FORMAT.md#10-known-leaks) lists every known leak in detail.
