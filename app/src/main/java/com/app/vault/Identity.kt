package com.app.vault

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * This install's pairing identity and the people it has paired with — P4-4,
 * `FORMAT.md` §13.
 *
 * `ShareContainer`'s contact mode seals to a recipient's X-Wing public key and opens
 * with the recipient's seed, and §12 says in as many words that where that identity
 * lives is this file's decision. It lives here: **one X-Wing keypair per install**,
 * not per vault, in one file, under one Keystore key.
 *
 * **Why the Keystore and not the VMK.** A contact must be reachable whichever vault
 * is open, and with none open — a per-vault identity would make the same person two
 * different identities to everyone who ever scanned them. The cost is that the wrap
 * is device-bound: a copy of `filesDir` lifted off the phone is inert, and so is the
 * file after a restore onto another handset. That is the trade `OUTLINE.md` names,
 * it is Signal's, and losing the phone means pairing again rather than losing a
 * vault.
 *
 * **I7 containment.** `KeystoreSurfaceTest` confines Keystore references to the
 * files that legitimately need them; this is the fourth. Identity is not on the
 * passphrase path — no unlock, no slot, no VMK touches this file — so
 * `PassphrasePathTest` is untouched and the five core files stay Keystore-free.
 *
 * **The seed is what is stored, and both halves are derived from it.** A stored
 * public half would be a second source of truth that can disagree with the first.
 * An X-Wing secret key *is* a 32-byte seed — the draft expands it with SHAKE256 into
 * the ML-KEM-768 and X25519 halves — so [XWing.keypairFromSeed] gives the 1,216-byte
 * public key from it deterministically. One field, no disagreement possible.
 *
 * **`pairing.bin`** (`remote-pairing` D4) holds remote pairings still in progress,
 * sealed under the same Keystore key so this stays the one file that names it. Its
 * codec is here with the identity's; the protocol that fills it is [RemotePairing].
 *
 * **File version 2** (`share-format-v2`). A version-1 file held an X25519 seed and
 * 32-byte contact keys; this build reads it as [State.Unreadable], and the one
 * recovery action replaces it. There is no installed base to migrate.
 */
object Identity {

    private const val KEYSTORE = "AndroidKeyStore"

    /** The wrapping key's alias. Distinct from `vault.anchor` and the biometric wrap. */
    const val ALIAS = "vault.identity"

    private const val FILE = "identity.bin"
    private const val TEMP = "identity.bin.tmp"

    /** The at-rest file's layout version — `FORMAT.md` §13. */
    const val FILE_VERSION = 2

    /** The QR payload's version, versioned separately: one is read by this build, the other by another install. */
    const val PAYLOAD_VERSION = 2

    /** A contact's key: an X-Wing public key. */
    const val PUBLIC_KEY_BYTES = XWing.PUBLIC_KEY_BYTES

    private const val SEED_BYTES = XWing.SEED_BYTES

    /** A name is at least one byte and at most this many, measured in UTF-8 — not characters. */
    const val MAX_NAME_BYTES = 64

    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    // --- what the screens see -------------------------------------------------

    /**
     * One paired person. A name this device's owner chose and a public key, and
     * deliberately nothing else — no timestamp, no history, no device identifier.
     */
    class Contact(val name: String, val publicKey: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Contact && name == other.name && publicKey.contentEquals(other.publicKey)

        override fun hashCode(): Int = 31 * name.hashCode() + publicKey.contentHashCode()

        override fun toString(): String = "Contact($name)"
    }

    /**
     * The identity's two halves: the 1,216-byte X-Wing public key and the 32-byte
     * seed that is its secret key. [secretKey] is the caller's to zero, the
     * convention every key in `VaultKeys` follows.
     */
    class Keys(val publicKey: ByteArray, val secretKey: ByteArray)

    /** What the contacts screen renders. */
    sealed interface State {
        /** The ordinary case: this install's code, and who it knows. */
        data class Ready(val publicKey: ByteArray, val contacts: List<Contact>) : State

        /**
         * The file is there and this install cannot open it — the Keystore key is
         * gone, which is what a restore onto another phone leaves behind. Nothing
         * is deleted until the user asks for it: see [startNewIdentity].
         */
        data object Unreadable : State
    }

    /** What a scan can turn into. Three of the four store nothing. */
    sealed interface AddOutcome {
        data object Added : AddOutcome
        data object OwnCode : AddOutcome
        data class AlreadyKnown(val name: String) : AddOutcome
        data object BadName : AddOutcome
        data object Unreadable : AddOutcome
    }

    // --- the pairing payload, which is pure and has no Android in it ----------

    /**
     * The ASCII bytes `VPAIR`, the version digit, and the 1,216-byte X-Wing public
     * key as **raw bytes** — and nothing else. `FORMAT.md` §13.
     *
     * Raw rather than Base64 because the key grew from 32 bytes to 1,216: in QR byte
     * mode at level M, raw is version 29 (133 modules a side) where Base64 is version
     * 34 (153). [PairingQr] carries the bytes through zxing's byte segments, never
     * through a decoded string.
     *
     * No name (`DESIGN.md` §7.21), no device identifier, no timestamp, no
     * signature: a signature would be checked with a key the scanner does not have
     * yet, which is what trust-on-first-use means.
     */
    object PairingCode {

        private val PREFIX = "VPAIR".toByteArray(Charsets.US_ASCII)

        /** What a decoded payload turned out to be. */
        sealed interface Parsed {
            data class Key(val publicKey: ByteArray) : Parsed

            /** The magic did not match, the version is 1 or unreadable, or the key is the wrong length. */
            data object NotAPairingCode : Parsed

            /** The magic matched and the version is above this build's — §13's second named refusal. */
            data object NewerApp : Parsed
        }

        fun encode(publicKey: ByteArray): ByteArray {
            require(publicKey.size == PUBLIC_KEY_BYTES) {
                "public key must be $PUBLIC_KEY_BYTES bytes"
            }
            return PREFIX + ('0' + PAYLOAD_VERSION).code.toByte() + publicKey
        }

        fun parse(payload: ByteArray): Parsed {
            if (payload.size < PREFIX.size + 1) return Parsed.NotAPairingCode
            if (!payload.copyOfRange(0, PREFIX.size).contentEquals(PREFIX)) return Parsed.NotAPairingCode
            val version = (payload[PREFIX.size].toInt() and 0xFF).toChar().digitToIntOrNull()
                ?: return Parsed.NotAPairingCode
            // Version before length: a newer build's payload may be any shape, and
            // the user is owed the message that names the reason.
            if (version > PAYLOAD_VERSION) return Parsed.NewerApp
            // Version 1 was text around a 32-byte X25519 key, which nothing here can
            // seal to any more. It is not a pairing code this build knows.
            if (version < PAYLOAD_VERSION) return Parsed.NotAPairingCode
            if (payload.size != PREFIX.size + 1 + PUBLIC_KEY_BYTES) return Parsed.NotAPairingCode
            return Parsed.Key(payload.copyOfRange(PREFIX.size + 1, payload.size))
        }
    }

    // --- the store's bytes, also pure ----------------------------------------

    /**
     * The decrypted contents. Serialised, sealed, and written whole every time:
     * the whole file is about 1.3 KB per contact, and a rewrite has no partial
     * state to reason about.
     */
    internal class Snapshot(val seed: ByteArray, val contacts: List<Contact>)

    internal fun encode(snapshot: Snapshot): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(snapshot.seed)
        val count = snapshot.contacts.size
        out.write(count and 0xFF)
        out.write((count ushr 8) and 0xFF)
        snapshot.contacts.forEach { contact ->
            val name = contact.name.toByteArray(Charsets.UTF_8)
            out.write(contact.publicKey)
            out.write(name.size)
            out.write(name)
        }
        return out.toByteArray()
    }

    /** Null for anything malformed — a decrypted file that does not parse is as unreadable as one that does not decrypt. */
    internal fun decode(plain: ByteArray): Snapshot? {
        val seedBytes = SEED_BYTES
        if (plain.size < seedBytes + 2) return null
        val seed = plain.copyOfRange(0, seedBytes)
        var at = seedBytes
        val count = (plain[at].toInt() and 0xFF) or ((plain[at + 1].toInt() and 0xFF) shl 8)
        at += 2
        val contacts = ArrayList<Contact>(count)
        repeat(count) {
            if (at + PUBLIC_KEY_BYTES + 1 > plain.size) return null
            val key = plain.copyOfRange(at, at + PUBLIC_KEY_BYTES)
            at += PUBLIC_KEY_BYTES
            val length = plain[at].toInt() and 0xFF
            at += 1
            if (length !in 1..MAX_NAME_BYTES || at + length > plain.size) return null
            contacts.add(Contact(String(plain, at, length, Charsets.UTF_8), key))
            at += length
        }
        if (at != plain.size) return null
        return Snapshot(seed, contacts)
    }

    /** Alphabetical, case-insensitively, which is the order a person looks for a name in. */
    internal fun sorted(contacts: List<Contact>): List<Contact> =
        contacts.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

    /**
     * The add rule, with the file taken out of it so a JVM test can run it.
     *
     * Returns the new list, or the refusal that stopped it. Refusals store nothing
     * and — on the screen — do not stop the scanner.
     */
    internal fun add(
        ownPublicKey: ByteArray,
        contacts: List<Contact>,
        publicKey: ByteArray,
        name: String,
    ): Pair<AddOutcome, List<Contact>> {
        if (publicKey.contentEquals(ownPublicKey)) return AddOutcome.OwnCode to contacts
        contacts.firstOrNull { it.publicKey.contentEquals(publicKey) }
            ?.let { return AddOutcome.AlreadyKnown(it.name) to contacts }
        val trimmed = name.trim()
        val bytes = trimmed.toByteArray(Charsets.UTF_8).size
        if (bytes !in 1..MAX_NAME_BYTES) return AddOutcome.BadName to contacts
        return AddOutcome.Added to sorted(contacts + Contact(trimmed, publicKey))
    }

    /**
     * Whether a scanned key can become a contact, before a name has been typed.
     *
     * The same two refusals as [add], decided one step earlier: the naming step
     * should never be reached for a code that cannot be stored.
     */
    internal fun scanned(
        ownPublicKey: ByteArray,
        contacts: List<Contact>,
        publicKey: ByteArray,
    ): AddOutcome = when {
        publicKey.contentEquals(ownPublicKey) -> AddOutcome.OwnCode
        else -> contacts.firstOrNull { it.publicKey.contentEquals(publicKey) }
            ?.let { AddOutcome.AlreadyKnown(it.name) } ?: AddOutcome.Added
    }

    // --- remote pairing's waiting sessions, also pure — `remote-pairing` D4 -----

    /** How long a session lives after it is made. Dropped on the first read after. */
    const val PAIRING_LIFETIME_MS = 24L * 60 * 60 * 1000

    /** At most this many are held; making one more drops the oldest. */
    const val MAX_PAIRING_SESSIONS = 16

    /** `pairing.bin`'s layout version — `FORMAT.md` §13a. */
    const val PAIRING_FILE_VERSION = 1

    /**
     * One remote pairing in progress. [sid] is the inviter's 16-byte session ID,
     * [name] the name this phone's owner gave the other person.
     *
     * Each role holds the secret its next step needs and nothing else, and every
     * secret is the caller's to zero once read.
     */
    sealed class PairingSession(val sid: ByteArray, val created: Long, val name: String) {
        val endsAt: Long get() = created + PAIRING_LIFETIME_MS

        /** The inviter before the reply: its CPace scalar and the canonical words, so the invite can be shown and sent again. */
        class Inviting(sid: ByteArray, created: Long, name: String, val scalar: ByteArray, val words: String) :
            PairingSession(sid, created, name)

        /** The inviter after the reply: the key the confirmation is sealed under, so it can be sent again. */
        class Confirming(sid: ByteArray, created: Long, name: String, val confirmKey: ByteArray) :
            PairingSession(sid, created, name)

        /**
         * The responder after the reply: the key that opens the confirmation, and the
         * reply file itself so it can be sent again. The reply is what already
         * travelled through another app — it carries nothing this store must hide.
         */
        class Answering(
            sid: ByteArray,
            created: Long,
            name: String,
            val confirmKey: ByteArray,
            val reply: ByteArray,
        ) : PairingSession(sid, created, name)
    }

    private const val ROLE_INVITING = 0
    private const val ROLE_CONFIRMING = 1
    private const val ROLE_ANSWERING = 2
    private const val SID_BYTES = 16
    private const val KEY_BYTES = 32

    internal fun encodeSessions(sessions: List<PairingSession>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(sessions.size)
        sessions.forEach { s ->
            out.write(s.sid)
            out.write(
                when (s) {
                    is PairingSession.Inviting -> ROLE_INVITING
                    is PairingSession.Confirming -> ROLE_CONFIRMING
                    is PairingSession.Answering -> ROLE_ANSWERING
                }
            )
            out.write(java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(s.created).array())
            val name = s.name.toByteArray(Charsets.UTF_8)
            out.write(name.size)
            out.write(name)
            when (s) {
                is PairingSession.Inviting -> {
                    val words = s.words.toByteArray(Charsets.UTF_8)
                    out.write(s.scalar)
                    out.write(words.size)
                    out.write(words)
                    words.fill(0)
                }
                is PairingSession.Confirming -> out.write(s.confirmKey)
                is PairingSession.Answering -> {
                    out.write(s.confirmKey)
                    out.write(s.reply)
                }
            }
        }
        return out.toByteArray()
    }

    /** Null for anything malformed, which is the store's unreadable condition. */
    internal fun decodeSessions(plain: ByteArray): List<PairingSession>? {
        var at = 0
        fun take(n: Int): ByteArray? =
            if (n < 0 || at + n > plain.size) null else plain.copyOfRange(at, at + n).also { at += n }
        fun byte(): Int? = take(1)?.let { it[0].toInt() and 0xFF }

        val count = byte() ?: return null
        val sessions = ArrayList<PairingSession>(count)
        repeat(count) {
            val sid = take(SID_BYTES) ?: return null
            val role = byte() ?: return null
            val created = take(8)?.let {
                java.nio.ByteBuffer.wrap(it).order(java.nio.ByteOrder.LITTLE_ENDIAN).long
            } ?: return null
            val nameLength = byte() ?: return null
            if (nameLength !in 1..MAX_NAME_BYTES) return null
            val name = String(take(nameLength) ?: return null, Charsets.UTF_8)
            sessions += when (role) {
                ROLE_INVITING -> {
                    val scalar = take(Cpace.SCALAR_BYTES) ?: return null
                    val words = take(byte() ?: return null) ?: return null
                    PairingSession.Inviting(sid, created, name, scalar, String(words, Charsets.UTF_8))
                        .also { words.fill(0) }
                }
                ROLE_CONFIRMING -> PairingSession.Confirming(sid, created, name, take(KEY_BYTES) ?: return null)
                ROLE_ANSWERING -> PairingSession.Answering(
                    sid, created, name,
                    take(KEY_BYTES) ?: return null,
                    take(RemotePairing.REPLY_BYTES) ?: return null,
                )
                else -> return null
            }
        }
        if (at != plain.size) return null
        return sessions
    }

    // --- the Keystore wrap ----------------------------------------------------

    private fun keystore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    /** The wrapping key, or null when it is absent — which is the restore case, not a first run. */
    private fun existingKey(): SecretKey? = runCatching {
        (keystore().getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }.getOrNull()

    /**
     * Creates the wrapping key.
     *
     * **No authentication requirement**, deliberately: requiring one would put a
     * biometric prompt in front of a contacts list and would leave an install with
     * nothing enrolled unable to pair at all. The key is non-exportable and
     * device-bound either way, which is the property this wrap is for.
     */
    private fun createKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    /** `identity.bin`'s associated data is its version byte alone, as it always was. */
    private val IDENTITY_AAD get() = byteArrayOf(FILE_VERSION.toByte())

    private fun seal(plain: ByteArray, key: SecretKey, version: Int, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(aad)
        val sealed = cipher.doFinal(plain)
        return byteArrayOf(version.toByte()) + cipher.iv + sealed
    }

    private fun open(bytes: ByteArray, version: Int, aad: ByteArray): ByteArray? {
        if (bytes.size < 1 + IV_BYTES + 16) return null
        if (bytes[0].toInt() != version) return null
        val key = existingKey() ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(TAG_BITS, bytes, 1, IV_BYTES),
            )
            cipher.updateAAD(aad)
            cipher.doFinal(bytes, 1 + IV_BYTES, bytes.size - 1 - IV_BYTES)
        }.getOrNull()
    }

    /** Temp file, flush, rename — so an interrupted write leaves the previous file standing. */
    private fun commit(context: Context, bytes: ByteArray, name: String, temp: String) {
        val tempFile = File(context.filesDir, temp)
        java.io.FileOutputStream(tempFile).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        check(tempFile.renameTo(File(context.filesDir, name))) { "$name write could not be committed" }
    }

    // --- the file -------------------------------------------------------------

    internal fun file(context: Context): File = File(context.filesDir, FILE)

    /**
     * Writes the snapshot: temp file, flush, rename.
     *
     * An interrupted write leaves the previous file standing, which matters more
     * here than in most places — the same bytes carry the identity *and* the
     * contacts, so a half-written file would lose both.
     */
    private fun write(context: Context, snapshot: Snapshot) {
        val key = existingKey() ?: createKey()
        val plain = encode(snapshot)
        val sealed = try {
            seal(plain, key, FILE_VERSION, IDENTITY_AAD)
        } finally {
            plain.fill(0)
        }
        commit(context, sealed, FILE, TEMP)
    }

    private fun load(context: Context): Snapshot? {
        val file = file(context)
        if (!file.isFile) return null
        return open(file.readBytes(), FILE_VERSION, IDENTITY_AAD)?.let { plain ->
            try {
                decode(plain)
            } finally {
                plain.fill(0)
            }
        }
    }

    private fun create(context: Context): Snapshot {
        val seed = ByteArray(SEED_BYTES).also { SecureRandom().nextBytes(it) }
        val snapshot = Snapshot(seed, emptyList())
        write(context, snapshot)
        return snapshot
    }

    private fun publicKeyOf(seed: ByteArray): ByteArray {
        val keypair = XWing.keypairFromSeed(seed)
        keypair.secretKey.fill(0)
        return keypair.publicKey
    }

    // --- what the app calls ---------------------------------------------------

    /**
     * The identity and the contacts, creating an identity on first use.
     *
     * A file that exists and will not open is [State.Unreadable] and **nothing is
     * touched** — the recovery is a decision the user makes, not one taken on their
     * behalf by the screen that noticed.
     */
    fun state(context: Context): State {
        // The waiting sessions live under the same key: one that will not open is
        // the same condition, met by the same one recovery action.
        if (loadPairing(context) == null) return State.Unreadable
        if (file(context).isFile) {
            val snapshot = load(context) ?: return State.Unreadable
            return try {
                State.Ready(publicKeyOf(snapshot.seed), sorted(snapshot.contacts))
            } finally {
                snapshot.seed.fill(0)
            }
        }
        val fresh = create(context)
        return try {
            State.Ready(publicKeyOf(fresh.seed), emptyList())
        } finally {
            fresh.seed.fill(0)
        }
    }

    /**
     * The contacts alone, **without creating an identity** — P4-5.
     *
     * The send flow needs the list to offer recipients; it does not need a keypair,
     * and [state] would mint one for a user who has never paired and may never
     * intend to. An absent file and an unreadable one both read as no contacts,
     * which is the honest answer on this surface: a store that cannot be read
     * cannot be sealed to either.
     */
    fun contacts(context: Context): List<Contact> {
        if (!file(context).isFile) return emptyList()
        val snapshot = load(context) ?: return emptyList()
        return try {
            sorted(snapshot.contacts)
        } finally {
            snapshot.seed.fill(0)
        }
    }

    /**
     * Both halves, derived from the stored seed — the public key the code shows and
     * the seed that opens what a contact sealed.
     *
     * The caller zeroes [Keys.secretKey], as with every key in `VaultKeys`.
     */
    fun keypair(context: Context): Keys? {
        val snapshot = load(context) ?: return null
        return try {
            val keypair = XWing.keypairFromSeed(snapshot.seed)
            Keys(keypair.publicKey, keypair.secretKey)
        } finally {
            snapshot.seed.fill(0)
        }
    }

    /**
     * The X-Wing seed alone — what the receive flow probes and opens with. Null when
     * there is no identity or this install cannot read it; **never creates one**, so
     * picking a file does not mint an identity for somebody who has never paired.
     *
     * The caller zeroes it.
     */
    fun seed(context: Context): ByteArray? = load(context)?.seed

    /**
     * Stores a paired key — scanned in person, or delivered by a remote pairing
     * ([RemotePairing]) — under the name this phone's owner chose.
     */
    fun add(context: Context, publicKey: ByteArray, name: String): AddOutcome {
        val snapshot = load(context) ?: return AddOutcome.Unreadable
        return try {
            val (outcome, contacts) = add(
                publicKeyOf(snapshot.seed), snapshot.contacts, publicKey, name,
            )
            if (outcome is AddOutcome.Added) write(context, Snapshot(snapshot.seed, contacts))
            outcome
        } finally {
            snapshot.seed.fill(0)
        }
    }

    /**
     * What a scan means before a name exists, so the naming step is never reached
     * for a code that cannot be stored.
     */
    fun inspect(context: Context, publicKey: ByteArray): AddOutcome {
        val snapshot = load(context) ?: return AddOutcome.Unreadable
        return try {
            scanned(publicKeyOf(snapshot.seed), snapshot.contacts, publicKey)
        } finally {
            snapshot.seed.fill(0)
        }
    }

    /**
     * Forgets one contact. It claims nothing about containers already sealed to
     * that key — see `DESIGN.md` §7.21's confirmation, which says so out loud.
     */
    fun remove(context: Context, publicKey: ByteArray) {
        val snapshot = load(context) ?: return
        try {
            write(
                context,
                Snapshot(
                    snapshot.seed,
                    snapshot.contacts.filterNot { it.publicKey.contentEquals(publicKey) },
                ),
            )
        } finally {
            snapshot.seed.fill(0)
        }
    }

    /**
     * The one action offered against [State.Unreadable]: throw away the file this
     * install cannot read and start again. Every contact goes with it, which is why
     * it is a tap and never automatic.
     */
    fun startNewIdentity(context: Context) {
        runCatching { keystore().deleteEntry(ALIAS) }
        file(context).delete()
        File(context.filesDir, TEMP).delete()
        pairingFile(context).delete()
        File(context.filesDir, PAIRING_TEMP).delete()
        create(context).seed.fill(0)
    }

    // --- the waiting-session file, `pairing.bin` --------------------------------

    private const val PAIRING_FILE = "pairing.bin"
    private const val PAIRING_TEMP = "pairing.bin.tmp"

    /**
     * Its associated data names the file as well as its version, so a `pairing.bin`
     * put where `identity.bin` belongs — both are sealed under the one key — fails
     * its tag rather than reaching a parser.
     */
    private val PAIRING_AAD get() = byteArrayOf(PAIRING_FILE_VERSION.toByte()) + PAIRING_FILE.toByteArray(Charsets.US_ASCII)

    internal fun pairingFile(context: Context): File = File(context.filesDir, PAIRING_FILE)

    /**
     * Every stored session, **expired ones included** — [RemotePairing] needs to tell
     * "this invite has ended" from "no such invite". Empty when there is no file;
     * null when there is one this install cannot open, the identity's unreadable
     * condition, and nothing is deleted.
     */
    internal fun loadPairing(context: Context): List<PairingSession>? {
        val file = pairingFile(context)
        if (!file.isFile) return emptyList()
        val plain = open(file.readBytes(), PAIRING_FILE_VERSION, PAIRING_AAD) ?: return null
        return try {
            decodeSessions(plain)
        } finally {
            plain.fill(0)
        }
    }

    /**
     * Writes [sessions] whole, keeping the newest [MAX_PAIRING_SESSIONS]. An empty
     * list deletes the file, so a finished session leaves no sealed bytes behind
     * either.
     */
    internal fun savePairing(context: Context, sessions: List<PairingSession>) {
        if (sessions.isEmpty()) {
            pairingFile(context).delete()
            return
        }
        val kept = sessions.sortedByDescending { it.created }.take(MAX_PAIRING_SESSIONS)
        val key = existingKey() ?: createKey()
        val plain = encodeSessions(kept)
        val sealed = try {
            seal(plain, key, PAIRING_FILE_VERSION, PAIRING_AAD)
        } finally {
            plain.fill(0)
        }
        commit(context, sealed, PAIRING_FILE, PAIRING_TEMP)
    }

    /**
     * The sessions still waiting, newest first, with every expired one dropped from
     * the file as it is read. Null is the unreadable condition.
     */
    fun pairingSessions(context: Context, now: Long = System.currentTimeMillis()): List<PairingSession>? {
        val all = loadPairing(context) ?: return null
        val live = all.filter { now < it.endsAt }
        if (live.size != all.size) savePairing(context, live)
        return live.sortedByDescending { it.created }
    }

    /**
     * Test-only: installs a **chosen** identity seed, replacing whatever is there.
     *
     * P4-5's two-device rig needs it. AGP uninstalls the app after every
     * instrumentation run, taking `identity.bin` with it, so a device that must
     * hold the *same* identity across two invocations — print its code in one,
     * open a container sealed to that code in the next — cannot rely on the
     * random one [state] mints. This lets the rig pin it.
     *
     * **No shipping code path calls this**, and `ContactShareTest` fails the build
     * if one ever does. It is the same shape as [deleteKeyForTest] and carries the
     * same warning: it destroys the identity it replaces.
     */
    fun installForTest(context: Context, seed: ByteArray) {
        require(seed.size == SEED_BYTES) { "seed must be $SEED_BYTES bytes" }
        write(context, Snapshot(seed.copyOf(), emptyList()))
    }

    /** Test-only: removes the wrapping key so a test can stage the restore case. */
    fun deleteKeyForTest() {
        runCatching { keystore().deleteEntry(ALIAS) }
    }
}
