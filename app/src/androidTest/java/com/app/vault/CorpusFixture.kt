package com.app.vault

import java.util.Random

/**
 * What P2-25's compatibility corpus contains, shared by the generator that writes
 * it ([DeviceFixtures.generateCompatibilityCorpus]) and the test that opens it
 * ([CompatibilityCorpusTest]).
 *
 * **Everything here is a constant, and that is the point.** The corpus on disk is
 * ciphertext only; the plaintext a later build must recover is *recomputed* from
 * these values rather than stored beside them. So the corpus leaks nothing (I3 is
 * untouched — no plaintext user content is committed) and it cannot drift out of
 * agreement with what it claims, because there is one definition of the content.
 *
 * **Never change these values.** They describe bytes that were written once, by a
 * released build, and are frozen from that moment. A new release adds a new
 * [VERSION] directory beside the old one; it does not edit an old one. Editing one
 * silently turns a compatibility test into a round-trip test, which is the exact
 * failure this whole mechanism exists to prevent.
 */
object CorpusFixture {

    /**
     * The corpus this build generates. One directory per released build, added to
     * and never edited.
     */
    const val VERSION = "v1"

    /**
     * A throwaway value with no other use. It is committed in the clear because the
     * corpus is a test fixture, not a vault — the ciphertext beside it protects
     * nothing anyone owns.
     */
    const val PASSPHRASE = "corpus-fixture-passphrase"

    const val CREATED_AT = 1_600_000_000_000L
    const val IMPORTED_AT = 1_700_000_000_000L

    /**
     * A real, decodable image, so the corpus carries a **thumbnail pack** — §8 is
     * format and a corpus of undecodable random bytes would silently omit it.
     *
     * Its plaintext is deliberately **not** asserted byte-identical the way
     * [ITEMS] are: PNG encoding is the platform's, not this app's, and pinning it
     * would make the corpus fail on an Android version change that has nothing to
     * do with the vault format. What it exists to prove is that the pack and the
     * `ThumbRef` into it still resolve.
     */
    const val IMAGE_NAME = "photo.png"
    const val IMAGE_SIDE = 64

    const val NOTE_TITLE = "Corpus note"
    const val NOTE_BODY = "A note carries its content in the index, not in a blob — 🔐 秘密"

    /**
     * The smallest set of items that fails if the §5 envelope changes. Sizes are
     * plaintext bytes.
     *
     * The chunk **boundary** is what a compatibility corpus has to pin — one byte,
     * exactly one chunk, one byte past a chunk, and a multi-chunk blob — because
     * the envelope's offsets, its per-chunk nonce and its final short chunk all
     * turn on it. Larger buckets were dropped deliberately: a 2 MiB blob exercises
     * the same envelope as a 128 KiB one and differs only in how much padding is
     * committed to git, and this corpus gains a copy per release forever.
     */
    val ITEMS: List<Pair<String, Int>> = listOf(
        "one-byte.bin" to 1,
        "under-a-chunk.bin" to 40_000,
        "exactly-one-chunk.bin" to 65_536,
        "one-past-a-chunk.bin" to 65_537,
        "several-chunks.bin" to 200_000,
    )

    /**
     * The item's plaintext, derived from its name so it is identical on every
     * machine and in every year.
     *
     * `java.util.Random` and not `SecureRandom`: what is wanted here is a
     * *reproducible* stream, which is the one thing a CSPRNG must not give. It
     * seeds nothing cryptographic — these bytes are fixture content, and the vault
     * they are written into is sealed by the ordinary path under [PASSPHRASE].
     */
    fun content(name: String, size: Int): ByteArray =
        ByteArray(size).also { Random(name.hashCode().toLong()).nextBytes(it) }
}
