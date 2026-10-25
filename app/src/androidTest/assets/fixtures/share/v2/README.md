# Share container golden vectors — version 2

`FORMAT.md` §12 version-2 containers, written by `share-format-v2` on `emulator-5554` on
2026-10-06, and opened by `ShareContainerTest`'s vector tests against these exact bytes.

- `code.bin` — code mode. The four words, the expiry policy, the prelude and the plaintext
  are fixed in `ShareVectorFixture`; the asset holds ciphertext only. 100,115 bytes: a
  16-byte salt, a 16-byte prefix, two chunks.
- `contact.bin` — contact mode, sealed to the X-Wing public key derived from
  `ShareVectorFixture.SEED` (a test-only identity). 101,219 bytes: a 1,120-byte X-Wing
  ciphertext, a 16-byte prefix, two chunks.

Neither file carries a readable header; `file` reports both as `data`.

**Add-only.** Editing or regenerating a committed vector turns the compatibility test
back into a round trip without changing a line of it — P2-25's rule. A new container
version adds `share/v3/` beside this directory; `v2` stays. Version 1 was removed rather
than kept because no release ever published it.

Regeneration for a *new* version: `DeviceFixtures.generateShareVectors`.
