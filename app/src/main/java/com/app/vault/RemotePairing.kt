package com.app.vault

import android.content.Context
import com.app.vault.Identity.PairingSession
import java.security.SecureRandom

/**
 * Remote pairing: three files and four words, authenticated by [Cpace] —
 * `remote-pairing`, `FORMAT.md` §13a.
 *
 * ```
 * invite  (A → B)  sid (16) ‖ Ya (32)                                    48 B
 * reply   (B → A)  sid (16) ‖ Yb (32) ‖ AEAD(k_reply,   pk_B ‖ tag)   1,280 B
 * confirm (A → B)  sid (16) ‖          AEAD(k_confirm, pk_A ‖ tag)   1,248 B
 * ```
 *
 * **Each derived key seals exactly one message**, which is what makes the all-zero
 * nonce safe — the argument Noise makes for its handshake keys. A confirmation sent
 * twice is the same plaintext under the same key and nonce, so the same bytes.
 *
 * **One attempt per invite** (D3): a reply whose `sid` matches the waiting invite
 * and does not authenticate ends it. A file whose `sid` does not match is not an
 * attempt, and ends nothing.
 *
 * Contacts are saved through [Identity.add], so the own-key and already-known
 * outcomes are the in-person scan's own.
 */
object RemotePairing {

    /** CPace's channel identifier, which is also the protocol's version (D7). */
    private val CI = "vault-pair-1".toByteArray(Charsets.US_ASCII)

    const val SID_BYTES = 16
    private const val TAG_BYTES = 16
    private const val KEY_BYTES = 32
    const val INVITE_BYTES = SID_BYTES + Cpace.POINT_BYTES
    const val REPLY_BYTES = SID_BYTES + Cpace.POINT_BYTES + Identity.PUBLIC_KEY_BYTES + TAG_BYTES
    const val CONFIRM_BYTES = SID_BYTES + Identity.PUBLIC_KEY_BYTES + TAG_BYTES

    private val REPLY_LABEL = "reply".toByteArray(Charsets.US_ASCII)
    private val CONFIRM_LABEL = "confirm".toByteArray(Charsets.US_ASCII)
    private val ZERO_NONCE = ByteArray(24)

    private val sodium = Sodium.lazy
    private val random = SecureRandom()

    // --- outcomes ---------------------------------------------------------------

    sealed interface Invited {
        class Made(val session: PairingSession.Inviting, val file: ByteArray) : Invited
        data object BadName : Invited
        data object Unreadable : Invited
    }

    sealed interface Answered {
        class Made(val session: PairingSession.Answering) : Answered
        /** Not 48 bytes, or not a valid point — "This isn't an invite file." */
        data object NotAnInvite : Answered
        /** The `sid` is one this phone is waiting on as the inviter. */
        data object OwnInvite : Answered
        data object BadName : Answered
        data object Unreadable : Answered
    }

    /** What opening a reply or a confirmation can come to. */
    sealed interface Opened {
        /** Saved. [alreadyKnownAs] is set when the key was a contact already, under that name. */
        class Saved(val alreadyKnownAs: String?, val confirmation: ByteArray?) : Opened
        /** It matched this session and failed: the session is gone. */
        data object Ended : Opened
        /** Not this session's file. Nothing ended. */
        data object WrongFile : Opened
        /** The session outlived its 24 hours; it is gone. */
        data object Expired : Opened
        /** No session waits on this `sid` — already ended or finished. */
        data object Gone : Opened
        /** The key is this phone's own. The session is gone. */
        data object OwnKey : Opened
        data object Unreadable : Opened
    }

    // --- the two keys, and the one-shot AEAD --------------------------------------

    internal class Keys(val reply: ByteArray, val confirm: ByteArray)

    /** `k_reply` and `k_confirm`: BLAKE2b-256 keyed with the 64-byte ISK, one label each. */
    internal fun keys(isk: ByteArray): Keys = Keys(derive(isk, REPLY_LABEL), derive(isk, CONFIRM_LABEL))

    private fun derive(isk: ByteArray, label: ByteArray): ByteArray = ByteArray(KEY_BYTES).also {
        check(sodium.cryptoGenericHash(it, KEY_BYTES, label, label.size.toLong(), isk, isk.size)) { "BLAKE2b failed" }
    }

    private fun seal(key: ByteArray, message: ByteArray, sid: ByteArray): ByteArray =
        ByteArray(message.size + TAG_BYTES).also {
            check(
                sodium.cryptoAeadXChaCha20Poly1305IetfEncrypt(
                    it, longArrayOf(0), message, message.size.toLong(),
                    sid, sid.size.toLong(), null, ZERO_NONCE, key,
                )
            ) { "pairing seal failed" }
        }

    private fun open(key: ByteArray, sealed: ByteArray, sid: ByteArray): ByteArray? {
        val out = ByteArray(sealed.size - TAG_BYTES)
        val ok = sodium.cryptoAeadXChaCha20Poly1305IetfDecrypt(
            out, longArrayOf(0), null, sealed, sealed.size.toLong(),
            sid, sid.size.toLong(), ZERO_NONCE, key,
        )
        return if (ok) out else null
    }

    private fun generator(words: String, sid: ByteArray): ByteArray {
        val prs = ShareCode.canonicalBytes(words.split(" "))
        try {
            return Cpace.generator(prs, CI, sid)
        } finally {
            prs.fill(0)
        }
    }

    private fun validName(name: String): String? =
        name.trim().takeIf { it.toByteArray(Charsets.UTF_8).size in 1..Identity.MAX_NAME_BYTES }

    private fun ownPublicKey(context: Context): ByteArray? =
        (Identity.state(context) as? Identity.State.Ready)?.publicKey

    // --- the inviter ------------------------------------------------------------

    /**
     * A's step 1: a session ID, a scalar, and the invite file for [words]. Stores the
     * session, so the invite can be shown and sent again until it ends.
     */
    fun makeInvite(
        context: Context,
        name: String,
        words: List<String>,
        now: Long = System.currentTimeMillis(),
        scalar: ByteArray = Cpace.randomScalar(),
    ): Invited {
        val trimmed = validName(name) ?: return Invited.BadName
        // An identity is minted here if there is none, as the in-person code does:
        // the reply this invite waits for is only useful with a key to confirm with.
        if (ownPublicKey(context) == null) return Invited.Unreadable
        val sessions = Identity.loadPairing(context) ?: return Invited.Unreadable
        val sid = ByteArray(SID_BYTES).also { random.nextBytes(it) }
        val canonical = words.joinToString(" ").lowercase()
        val session = PairingSession.Inviting(sid, now, trimmed, scalar, canonical)
        Identity.savePairing(context, sessions + session)
        return Invited.Made(session, inviteFile(session))
    }

    /** The invite file for a waiting invite — the same bytes every time it is asked for. */
    fun inviteFile(session: PairingSession.Inviting): ByteArray =
        session.sid + Cpace.share(session.scalar, generator(session.words, session.sid))

    /**
     * A's step 2: opens [reply] against the waiting invite [sid]. On success B is a
     * contact and the session holds `k_confirm` until it is finished; on failure the
     * invite is gone (D3).
     */
    fun openReply(
        context: Context,
        sid: ByteArray,
        reply: ByteArray,
        now: Long = System.currentTimeMillis(),
    ): Opened {
        if (reply.size != REPLY_BYTES || !reply.copyOf(SID_BYTES).contentEquals(sid)) return Opened.WrongFile
        val sessions = Identity.loadPairing(context) ?: return Opened.Unreadable
        val session = sessions.firstOrNull { it.sid.contentEquals(sid) } ?: return Opened.Gone
        val rest = sessions - session
        if (now >= session.endsAt) {
            Identity.savePairing(context, rest)
            return Opened.Expired
        }
        if (session !is PairingSession.Inviting) return Opened.Gone
        val ownKey = ownPublicKey(context) ?: return Opened.Unreadable

        val g = generator(session.words, sid)
        val ya = Cpace.share(session.scalar, g)
        val yb = reply.copyOfRange(SID_BYTES, SID_BYTES + Cpace.POINT_BYTES)
        val k = Cpace.sharedPoint(session.scalar, yb)
        if (k == null) {
            Identity.savePairing(context, rest)
            return Opened.Ended
        }
        val isk = Cpace.isk(sid, k, ya, ByteArray(0), yb, ByteArray(0))
        k.fill(0)
        val keys = keys(isk)
        isk.fill(0)
        val peerKey = open(keys.reply, reply.copyOfRange(SID_BYTES + Cpace.POINT_BYTES, REPLY_BYTES), sid)
        keys.reply.fill(0)
        if (peerKey == null) {
            keys.confirm.fill(0)
            Identity.savePairing(context, rest)
            return Opened.Ended
        }
        return when (val added = Identity.add(context, peerKey, session.name)) {
            Identity.AddOutcome.Added, is Identity.AddOutcome.AlreadyKnown -> {
                val confirming = PairingSession.Confirming(sid, session.created, session.name, keys.confirm)
                Identity.savePairing(context, rest + confirming)
                Opened.Saved(
                    (added as? Identity.AddOutcome.AlreadyKnown)?.name,
                    sid + seal(keys.confirm, ownKey, sid),
                )
            }
            Identity.AddOutcome.OwnCode -> {
                keys.confirm.fill(0)
                Identity.savePairing(context, rest)
                Opened.OwnKey
            }
            // The name was checked when the invite was made, so BadName cannot
            // happen here; it and an unreadable store both leave the session as it was.
            Identity.AddOutcome.BadName, Identity.AddOutcome.Unreadable -> {
                keys.confirm.fill(0)
                Opened.Unreadable
            }
        }
    }

    /**
     * The confirmation for a session that has opened its reply — the same bytes every
     * time, because it is the one message `k_confirm` ever seals.
     */
    fun confirmationFile(context: Context, session: PairingSession.Confirming): ByteArray? {
        val ownKey = ownPublicKey(context) ?: return null
        return session.sid + seal(session.confirmKey, ownKey, session.sid)
    }

    // --- the responder ----------------------------------------------------------

    /**
     * B's step 1: answers [invite] with [words], naming the inviter [name]. Stores the
     * session with `k_confirm` and the reply, so the reply can be sent again and the
     * confirmation opened later.
     */
    fun answerInvite(
        context: Context,
        invite: ByteArray,
        words: List<String>,
        name: String,
        now: Long = System.currentTimeMillis(),
        scalar: ByteArray = Cpace.randomScalar(),
    ): Answered {
        if (!isInvite(invite)) return Answered.NotAnInvite
        val trimmed = validName(name) ?: return Answered.BadName
        val ownKey = ownPublicKey(context) ?: return Answered.Unreadable
        val sessions = Identity.loadPairing(context) ?: return Answered.Unreadable
        val sid = invite.copyOf(SID_BYTES)
        if (sessions.any { it.sid.contentEquals(sid) && it !is PairingSession.Answering }) {
            return Answered.OwnInvite
        }
        val ya = invite.copyOfRange(SID_BYTES, INVITE_BYTES)
        val canonical = words.joinToString(" ").lowercase()
        val yb = Cpace.share(scalar, generator(canonical, sid))
        val k = Cpace.sharedPoint(scalar, ya) ?: return Answered.NotAnInvite
        scalar.fill(0)
        val isk = Cpace.isk(sid, k, ya, ByteArray(0), yb, ByteArray(0))
        k.fill(0)
        val keys = keys(isk)
        isk.fill(0)
        val reply = sid + yb + seal(keys.reply, ownKey, sid)
        keys.reply.fill(0)
        val session = PairingSession.Answering(sid, now, trimmed, keys.confirm, reply)
        // Answering the same invite again replaces the earlier answer.
        Identity.savePairing(context, sessions.filterNot { it.sid.contentEquals(sid) } + session)
        return Answered.Made(session)
    }

    /** Whether picked bytes can be an invite at all: 48 bytes ending in a valid share. */
    fun isInvite(bytes: ByteArray): Boolean =
        bytes.size == INVITE_BYTES && Cpace.isValidShare(bytes.copyOfRange(SID_BYTES, INVITE_BYTES))

    /** B's step 2: opens [confirm] against the waiting answer [sid], saving A. The session ends either way it decides. */
    fun openConfirmation(
        context: Context,
        sid: ByteArray,
        confirm: ByteArray,
        now: Long = System.currentTimeMillis(),
    ): Opened {
        if (confirm.size != CONFIRM_BYTES || !confirm.copyOf(SID_BYTES).contentEquals(sid)) return Opened.WrongFile
        val sessions = Identity.loadPairing(context) ?: return Opened.Unreadable
        val session = sessions.firstOrNull { it.sid.contentEquals(sid) } ?: return Opened.Gone
        val rest = sessions - session
        if (now >= session.endsAt) {
            Identity.savePairing(context, rest)
            return Opened.Expired
        }
        if (session !is PairingSession.Answering) return Opened.Gone
        val peerKey = open(session.confirmKey, confirm.copyOfRange(SID_BYTES, CONFIRM_BYTES), sid)
        if (peerKey == null) {
            Identity.savePairing(context, rest)
            return Opened.Ended
        }
        return when (val added = Identity.add(context, peerKey, session.name)) {
            Identity.AddOutcome.Added -> Opened.Saved(null, null).also { Identity.savePairing(context, rest) }
            is Identity.AddOutcome.AlreadyKnown ->
                Opened.Saved(added.name, null).also { Identity.savePairing(context, rest) }
            Identity.AddOutcome.OwnCode -> Opened.OwnKey.also { Identity.savePairing(context, rest) }
            Identity.AddOutcome.BadName, Identity.AddOutcome.Unreadable -> Opened.Unreadable
        }
    }

    // --- either side ------------------------------------------------------------

    /** Cancel, Done, or a failure the screen already showed: the session and its secrets go. */
    fun end(context: Context, sid: ByteArray) {
        val sessions = Identity.loadPairing(context) ?: return
        Identity.savePairing(context, sessions.filterNot { it.sid.contentEquals(sid) })
    }
}
