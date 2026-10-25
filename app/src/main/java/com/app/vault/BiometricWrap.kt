package com.app.vault

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import com.app.vault.ui.BiometricLayout
import com.app.vault.ui.BiometricState
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The biometric wrap — `FORMAT.md` §4.1, P1-9.
 *
 * A second wrapping of exactly what a slot holds, under a key the user
 * *authenticates* to rather than derives. It is not a copy of the passphrase and
 * not a route to the KEK: an attacker who extracts this file gets 256 bytes that
 * only a live strong biometric on this device can open.
 *
 * **Deliberately holds no `BiometricPrompt` and no Activity.** It takes a
 * `Cipher` that someone else has already had authenticated and returns bytes, so
 * an instrumented test can drive every branch — write, read, invalidate, disable
 * — without a UI and without a fingerprint. The prompt lives in `MainActivity`,
 * which is the only place that can show one.
 *
 * I7 runs the other way from here: this file references the Keystore freely, and
 * `VaultLifecycle`, `VaultUnlock`, `VaultKeys`, `SlotArray` and `VaultSession`
 * reference it nowhere. `PassphrasePathTest` enforces that, and it is the reason
 * the class exposes [OpenVault] rather than reaching into the session itself.
 */
class BiometricWrap(private val file: File) {

    constructor(context: Context) : this(File(context.filesDir, FILE_NAME))

    companion object {
        const val FILE_NAME = "vault.bio"
        const val ALIAS = "vault.bio"

        private const val KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        /** §4.1. Fixed, so the length says nothing about what is inside it. */
        const val FILE_BYTES = 256

        const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val TAG_BYTES = TAG_BITS / 8

        private const val SLOT_INDEX_BYTES = 1

        /** `VMK ‖ metadata blob ID ‖ slot index` — 49 B. */
        private const val PAYLOAD_BYTES =
            SlotArray.VMK_BYTES + SlotArray.BLOB_ID_BYTES + SLOT_INDEX_BYTES

        private const val CIPHERTEXT_BYTES = PAYLOAD_BYTES + TAG_BYTES  // 65
        private const val CIPHERTEXT_OFFSET = IV_BYTES                  // 12

        /**
         * Whether this device can authenticate a strong biometric *at all*.
         *
         * `BIOMETRIC_STRONG` only. A weak authenticator cannot produce a
         * `CryptoObject`, and the device credential is excluded here for the same
         * reason it is excluded from the key: a four-digit PIN shoulder-surfed in
         * a queue is not a vault passphrase, whatever the passphrase says.
         */
        fun isAvailable(context: Context): Boolean =
            BiometricManager.from(context)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
                BiometricManager.BIOMETRIC_SUCCESS

        // --- which state, and why ----------------------------------------------
        //
        // `unavailable-capability-states`. STRONG alone decides whether the feature is
        // *offered*; WEAK (which succeeds if *any* biometric is enrolled) only decides
        // which explanation is true. Which biometric the state belongs to is [layout]'s.

        /**
         * The state from two `canAuthenticate` results and whether the wrap is on.
         *
         * No `Context` and no Android type, so the JVM suite drives every row.
         * Top to bottom, first match wins. `NONE_ENROLLED` is NotSetUp whatever WEAK
         * says: a weak biometric beside it is the unavailable face or fingerprint row's
         * to explain, not the live row's.
         *
         * ponytail: the WEAK test sits **before** `HW_UNAVAILABLE` on purpose. The
         * `canAuthenticate(int)` javadoc promises a weak-only phone `NONE_ENROLLED` "or
         * `HW_UNAVAILABLE`", and which one is decided in `system_server`, which is not in
         * the SDK sources. This order is right whichever it returns. Its ceiling: a strong
         * sensor that is briefly not responding, beside an enrolled weak one, reads as
         * NotSecureEnough until the next `onStart`. Upgrade, if that ever matters: treat
         * a non-null `getStrings(STRONG).getSettingName()` as "a strong sensor exists".
         */
        fun state(strong: Int, weak: Int, enabled: Boolean): BiometricState = when {
            strong == BiometricManager.BIOMETRIC_SUCCESS ->
                if (enabled) BiometricState.On else BiometricState.Off
            strong == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricState.NotSetUp
            strong == BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> BiometricState.NeedsUpdate
            weak == BiometricManager.BIOMETRIC_SUCCESS ||
                weak == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricState.NotSecureEnough
            strong == BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> BiometricState.NotAvailableNow
            else -> BiometricState.NotSupported
        }

        /** [state], read from the device: two binder calls, so never from a composition. */
        fun state(context: Context, enabled: Boolean): BiometricState {
            val manager = BiometricManager.from(context)
            return state(
                manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG),
                manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK),
                enabled,
            )
        }

        // --- which biometric ---------------------------------------------------
        //
        // `biometric-unlock-screen` D1. Android answers *whether* per class and never
        // says which modality authenticated, so *which* comes from three public signals:
        // the two hardware features, and whether the STRONG and WEAK setting names are
        // equal. The `Strings.getSettingName()` javadoc has each name the modalities that
        // meet its class "regardless of whether the user has enrolled or selected either",
        // so equal names mean every biometric sensor on the device is strong. **Equality
        // only** — the strings are localised and OEM-written, and nothing here reads a
        // word in them.

        /**
         * Which rows the device gets — `biometric-unlock-screen`'s detection table, top to
         * bottom, first match wins. No `Context` and no Android type, so the JVM suite
         * drives every row.
         *
         * ponytail: an OEM that returns the same setting name for STRONG and WEAK while its
         * face is weak reads as Both, and no public signal tells it apart. The AOSP contract
         * is the javadoc's, and Pixel follows it.
         */
        fun layout(
            state: BiometricState,
            hasFingerprint: Boolean,
            hasFace: Boolean,
            allStrong: Boolean?,
        ): BiometricLayout = when {
            state == BiometricState.NotSecureEnough || state == BiometricState.NotSupported ->
                BiometricLayout.None
            hasFingerprint && hasFace ->
                if (allStrong == true) BiometricLayout.Both else BiometricLayout.Fingerprint
            hasFingerprint -> BiometricLayout.Fingerprint
            hasFace -> BiometricLayout.Face
            else -> BiometricLayout.Unnamed
        }

        /**
         * Whether every biometric sensor on the device is strong: true where the STRONG and
         * WEAK setting names are equal, false where both exist and differ, null on API 30 or
         * wherever either is null. Two binder calls, so never from a composition.
         */
        fun allStrong(context: Context): Boolean? {
            val strong = platformLabel(context, button = false) ?: return null
            val weak = platformLabel(context, button = false, weak = true) ?: return null
            return strong == weak
        }

        /** A fingerprint sensor exists, of whatever class. A sensor does not appear at runtime: read once. */
        fun hasFingerprint(context: Context): Boolean =
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)

        /** A face sensor exists, of whatever class. Read once, as [hasFingerprint]. */
        fun hasFace(context: Context): Boolean =
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_FACE)

        // --- the words ---------------------------------------------------------
        //
        // `biometric-unlock-screen` D3, D5, D6 and `unavailable-capability-states`. The
        // app's own sentences, verbatim from the specs, and all of them named here, so
        // `BiometricNamingScanTest` can hold the words "face" and "fingerprint" to the
        // strings this file names. "Device", not "phone": the owner's word, and true on a
        // tablet. `when`s rather than maps, so a new state or layout cannot be added without
        // its words. `app-languages`: each is a string resource, resolved where it is drawn.

        /** The screen's title, the drawer row, the first-run step's title, and the Unnamed row. */
        val NAME = R.string.bio_name

        /** The screen's and the first-run step's lead — one feature, so one sentence. */
        val LEAD = R.string.bio_lead

        val FINGERPRINT_ROW = R.string.bio_row_fingerprint
        val FACE_ROW = R.string.bio_row_face

        /** Pixel's own name for the same pair in its Settings. */
        val BOTH_ROW = R.string.bio_row_both

        /** The live row's name, or null in None, which has no live row. */
        fun liveName(layout: BiometricLayout): Int? = when (layout) {
            BiometricLayout.Fingerprint -> FINGERPRINT_ROW
            BiometricLayout.Face -> FACE_ROW
            BiometricLayout.Both -> BOTH_ROW
            BiometricLayout.Unnamed -> NAME
            BiometricLayout.None -> null
        }

        /** The live row's line — its state in words. Null in the states that have no live row. */
        fun liveLine(state: BiometricState): Int? = when (state) {
            BiometricState.On -> R.string.bio_on
            BiometricState.Off -> R.string.bio_off
            BiometricState.NotSetUp -> R.string.bio_line_not_set_up
            BiometricState.NotAvailableNow -> R.string.bio_line_not_available_now
            BiometricState.NeedsUpdate -> R.string.bio_line_needs_update
            BiometricState.NotSecureEnough, BiometricState.NotSupported -> null
        }

        /**
         * An unavailable row's line. The owner's sentence first; the reason after it only
         * where the device has that hardware — "isn't secure enough" about a sensor the
         * device does not have would be false.
         */
        fun unavailableLine(face: Boolean, hardwarePresent: Boolean): Int = when {
            !hardwarePresent -> R.string.bio_unsupported
            face -> R.string.bio_unsupported_face
            else -> R.string.bio_unsupported_fingerprint
        }

        /** The drawer row's state line. The screen it opens says the rest. */
        fun drawerLine(state: BiometricState): Int = when (state) {
            BiometricState.On -> R.string.bio_on
            BiometricState.Off -> R.string.bio_off
            BiometricState.NotSetUp -> R.string.bio_drawer_not_set_up
            BiometricState.NotAvailableNow -> R.string.bio_drawer_not_available_now
            BiometricState.NeedsUpdate -> R.string.bio_drawer_needs_update
            BiometricState.NotSecureEnough, BiometricState.NotSupported -> R.string.bio_drawer_not_supported
        }

        private val FINGERPRINT_ONLY_FACT = R.string.bio_fact_fingerprint_only to R.string.bio_fact_fingerprint_only_body

        private val PASSPHRASE_FACT = R.string.bio_fact_passphrase to R.string.bio_fact_passphrase_body

        /**
         * The facts under the rows, heading then body, in the order they are drawn. None
         * gets only the passphrase: how it works and what turns it off say nothing to a
         * device that cannot use it.
         */
        fun facts(layout: BiometricLayout): List<Pair<Int, Int>> {
            val (how, turnsOff) = when (layout) {
                BiometricLayout.Fingerprint ->
                    R.string.bio_fact_how_fingerprint to R.string.bio_fact_turns_off_fingerprint
                // True because the prompt keeps confirmation required (`biometric-unlock`).
                BiometricLayout.Face -> R.string.bio_fact_how_face to R.string.bio_fact_turns_off_face
                BiometricLayout.Both -> R.string.bio_fact_how_both to R.string.bio_fact_turns_off_both
                BiometricLayout.Unnamed -> R.string.bio_fact_how_unnamed to R.string.bio_fact_turns_off_unnamed
                BiometricLayout.None -> return listOf(PASSPHRASE_FACT)
            }
            return listOfNotNull(
                R.string.bio_fact_how to how,
                FINGERPRINT_ONLY_FACT.takeIf { layout == BiometricLayout.Both },
                R.string.bio_fact_turns_off to turnsOff,
                PASSPHRASE_FACT,
            )
        }

        // --- the calculator's method choice ---------------------------------------
        //
        // The words the calculator's "How do you want to open it?" step draws, its three rows in
        // order (`calculator-dots-methods` D6). The music player and the voice recorder have one
        // method and no choice (`cover-hold-dots`). Each name states the order the owner performs
        // the method in; each line says what the owner does.

        /** The plain method's name: what the calculator's own gesture is called. */
        val PLAIN_METHOD = R.string.method_sequence

        val PLAIN_METHOD_LINE = R.string.method_sequence_line

        val SEQUENCE_PATTERN_METHOD = R.string.method_sequence_pattern

        val SEQUENCE_PATTERN_METHOD_LINE = R.string.method_sequence_pattern_line

        val HOLD_PATTERN_METHOD = R.string.method_hold_pattern

        val HOLD_PATTERN_METHOD_LINE = R.string.method_hold_pattern_line

        /** The choice step's lead. */
        val CHOOSE_LEAD = R.string.method_choose_lead

        // --- the Intruder log's stages — `cover-fingerprint-dots` D6a, `cover-hold-dots` D7 ---

        /**
         * Where a capture's attempt failed, as its line in the Intruder log says it — from the
         * sealed record's [stage], [flags] and [cover] bytes, which the `0x41` entry carries as its
         * payload. [layout] is the unlock screen's, at the time the log is written. An entry with
         * no stage is the passphrase, the only stage that fired before stages were recorded.
         */
        fun captureWhere(words: Words, stage: Int, flags: Int, cover: Int, layout: BiometricLayout): String {
            val name = words.get(
                when (cover) {
                    CaptureRecord.COVER_MUSIC -> R.string.capture_where_music
                    CaptureRecord.COVER_RECORDER -> R.string.capture_where_recorder
                    CaptureRecord.COVER_CALCULATOR -> R.string.capture_where_calculator
                    else -> R.string.capture_where_cover
                }
            )
            val limited = flags and CaptureRecord.FLAG_LIMITED != 0
            return when (stage) {
                CaptureRecord.STAGE_COVER_PATTERN -> words.get(
                    when {
                        flags and CaptureRecord.FLAG_DOTS_SHOWN == 0 ->
                            if (limited) R.string.capture_where_pattern_lockout else R.string.capture_where_pattern
                        limited -> R.string.capture_where_pattern_lockout_dots
                        else -> R.string.capture_where_pattern_dots
                    },
                    name,
                )
                CaptureRecord.STAGE_UNLOCK_BIOMETRIC -> words.get(
                    when (layout) {
                        BiometricLayout.Fingerprint -> R.string.capture_where_wrong_fingerprint
                        BiometricLayout.Face -> R.string.capture_where_wrong_face
                        BiometricLayout.Both -> R.string.capture_where_wrong_both
                        BiometricLayout.Unnamed, BiometricLayout.None -> R.string.capture_where_wrong_biometric
                    }
                )
                CaptureRecord.STAGE_VAULT_PASSPHRASE -> words.get(R.string.capture_where_vault_passphrase)
                else -> words.get(R.string.capture_where_wrong_passphrase)
            }
        }

        // --- the unlock screen's button --------------------------------------
        //
        // `biometric-naming`. The one label the app takes from the platform:
        // `getButtonLabel()`, which the platform narrows to the sensors present, their
        // classes and the user's preferred modality — what is about to be used at the
        // prompt, which the app cannot know. Every other name is the app's, above.
        //
        // **The framework class, not `androidx.biometric`.** 1.1.0 is the pinned
        // stable release and ships no `Strings` wrapper — the wrapper landed in
        // 1.4.0-alpha07, and an alpha does not go on this app's unlock path for the
        // sake of avoiding six lines. `getStrings` arrives at API 31 and `minSdk`
        // is 30, so the call is guarded and falls back; every accessor is
        // `@Nullable` on every version, so a null falls back too. `USE_BIOMETRIC`,
        // which they require, is already merged in from `androidx.biometric`'s
        // manifest.

        /** Shown where the platform supplies nothing. Names the act, never a modality. */
        val BUTTON_FALLBACK = R.string.bio_button_fallback

        /**
         * The enable prompt's title, on every device.
         *
         * Not built from a platform string: measured on a Pixel 8 AVD at API 36 with a
         * Class 3 fingerprint enrolled, `getSettingName()` returns **"Use fingerprint"**,
         * so a framed title reads "Turn on Use fingerprint". And not named per row: the
         * key accepts every enrolled strong biometric, so a title naming one would be
         * false on a device where both are strong.
         */
        val ENABLE_LABEL = R.string.bio_enable_title

        /**
         * What a **control about to raise the prompt** calls this — `getButtonLabel()`.
         *
         * The platform resolves it against the sensors present, their classes and the
         * user's preferred modality, so it names what is actually about to happen.
         */
        fun buttonLabel(context: Context): String =
            platformLabel(context, button = true)
                // `app-languages`: the platform words in the device's language, so they are
                // used only when the app is in that language too — never one English
                // button in a German screen.
                ?.takeIf {
                    context.resources.configuration.locales[0].language ==
                        AppLanguage.systemLocales(context)[0].language
                }
                ?: context.getString(BUTTON_FALLBACK)

        /**
         * One guarded read, or null — API 30, a missing service, or a null accessor.
         *
         * One function rather than two so the guard exists once, and it returns a
         * `String?` rather than a `Strings` so no API-31 type reaches a signature
         * that is called on API 30. [weak] asks about the WEAK class instead, for
         * [allStrong]'s comparison.
         */
        private fun platformLabel(context: Context, button: Boolean, weak: Boolean = false): String? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
            val strings = context
                .getSystemService(android.hardware.biometrics.BiometricManager::class.java)
                ?.getStrings(
                    if (weak) android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK
                    else android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
                ) ?: return null
            return (if (button) strings.buttonLabel else strings.settingName)
                ?.toString()
                ?.takeIf { it.isNotBlank() }
        }
    }

    private val rng = SecureRandom()

    private fun keystore(): KeyStore =
        KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun key(): SecretKey? = keystore().getKey(ALIAS, null) as SecretKey?

    /**
     * Whether a wrap record is on disk, whatever the Keystore says.
     *
     * Read *before* [isEnabled], which cleans up a half-state without saying so.
     * A record that was there and is not enabled is an enrolment change the user
     * has to be told about — measured on device: clearing the screen lock makes
     * the platform delete the auth-bound key outright, so the app never sees
     * `KeyPermanentlyInvalidatedException` and would otherwise stay silent.
     */
    fun hasRecord(): Boolean = file.exists()

    /**
     * Enabled means the file **and** the key are both present.
     *
     * Either one alone is a disabled state, and it disables the other rather than
     * leaving the pair to disagree: a file with no key can never be opened, and a
     * key with no file protects nothing.
     */
    fun isEnabled(): Boolean {
        val hasFile = file.exists()
        val hasKey = runCatching { keystore().containsAlias(ALIAS) }.getOrDefault(false)
        if (hasFile != hasKey) {
            disable()
            return false
        }
        return hasFile
    }

    /**
     * Deletes the file and the key, together, always.
     *
     * Idempotent and total: every failure path in P1-9 ends here, including ones
     * where one half is already gone. There is no repair path, because the wrap
     * is a convenience derived from a secret the user still holds — re-enabling
     * it is one action, and a retry path would be complexity guarding nothing.
     */
    fun disable() {
        file.delete()
        runCatching { keystore().deleteEntry(ALIAS) }
    }

    // --- keys ----------------------------------------------------------------

    /**
     * Creates the key, replacing any existing one — `FORMAT.md` §4.1.
     *
     * A zero validity duration is per-use authentication: the `Cipher` binds to
     * one `CryptoObject` and one operation, so no window exists in which the key
     * is warm. `AUTH_BIOMETRIC_STRONG` excludes the device credential at the key
     * level, so the refusal survives a future caller asking a prompt for
     * `DEVICE_CREDENTIAL` by mistake.
     *
     * StrongBox is not requested. `setIsStrongBoxBacked(true)` throws on hardware
     * without it, so it buys a fallback path and a second failure mode in return
     * for nothing this threat model needs — the key is already non-exportable and
     * auth-bound in the TEE.
     */
    fun createKey() {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                .setInvalidatedByBiometricEnrollment(true)
                .build()
        )
        generator.generateKey()
    }

    /**
     * A `Cipher` ready for a `CryptoObject`, for writing a new wrap.
     *
     * Creates the key first, so enabling twice replaces the wrap rather than
     * writing a second one under a key that already authenticated somebody.
     */
    fun encryptCipher(): Cipher {
        createKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key() ?: error("key absent immediately after creation"))
        return cipher
    }

    /**
     * A `Cipher` ready for a `CryptoObject`, for reading the existing wrap.
     *
     * Throws rather than returning null on a missing key or file: the caller must
     * handle `KeyPermanentlyInvalidatedException` here anyway — it is the whole
     * enrolment-changed branch — and a null would let it be forgotten. Every one
     * of those throws ends at [disable].
     */
    fun decryptCipher(): Cipher {
        val key = key() ?: error("biometric key absent")
        val iv = ByteArray(IV_BYTES)
        file.inputStream().use { input ->
            check(input.read(iv) == IV_BYTES) { "biometric wrap is truncated" }
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return cipher
    }

    // --- the record ----------------------------------------------------------

    /**
     * Seals [vault] into [FILE_BYTES] bytes — §4.1 layout.
     *
     * [cipher] must be one an authentication has already unlocked; that is what
     * makes the finger's presence at this moment part of the record rather than a
     * claim about it.
     */
    fun write(cipher: Cipher, vault: OpenVault) {
        require(vault.vmk.size == SlotArray.VMK_BYTES) { "VMK must be ${SlotArray.VMK_BYTES} bytes" }
        require(vault.metadataBlobId.size == SlotArray.BLOB_ID_BYTES) {
            "blob ID must be ${SlotArray.BLOB_ID_BYTES} bytes"
        }
        require(vault.slotIndex in 0 until SlotArray.SLOT_COUNT) { "slot index out of range" }

        val payload = ByteArray(PAYLOAD_BYTES)
        // CSPRNG first, so the padding is fresh on every write and the tail is
        // never a region that stays constant across rewrites (§4 makes the same
        // argument for a slot).
        val record = ByteArray(FILE_BYTES).also { rng.nextBytes(it) }
        try {
            vault.vmk.copyInto(payload, 0)
            vault.metadataBlobId.copyInto(payload, SlotArray.VMK_BYTES)
            payload[PAYLOAD_BYTES - 1] = vault.slotIndex.toByte()

            val iv = cipher.iv
            check(iv.size == IV_BYTES) { "expected a $IV_BYTES byte GCM IV, got ${iv.size}" }
            val ciphertext = cipher.doFinal(payload)
            check(ciphertext.size == CIPHERTEXT_BYTES) {
                "expected $CIPHERTEXT_BYTES ciphertext bytes, got ${ciphertext.size}"
            }

            iv.copyInto(record, 0)
            ciphertext.copyInto(record, CIPHERTEXT_OFFSET)
            ciphertext.fill(0)

            val tmp = File(file.parentFile, "${file.name}.tmp")
            try {
                tmp.outputStream().use { out ->
                    out.write(record)
                    out.fd.sync()
                }
                check(tmp.renameTo(file)) { "could not move the biometric wrap into place" }
            } finally {
                tmp.delete()
            }
        } finally {
            payload.fill(0)
            record.fill(0)
        }
    }

    /**
     * Opens the wrap with an authenticated [cipher], or returns null.
     *
     * Null covers a truncated file, a bit-flipped one, and a record written under
     * a key that is no longer the key — all the same outcome to the caller, which
     * is [disable] and the passphrase.
     */
    fun read(cipher: Cipher): OpenVault? {
        val record = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (record.size != FILE_BYTES) return null

        val ciphertext = record.copyOfRange(CIPHERTEXT_OFFSET, CIPHERTEXT_OFFSET + CIPHERTEXT_BYTES)
        val payload = runCatching { cipher.doFinal(ciphertext) }.getOrNull() ?: return null

        try {
            if (payload.size != PAYLOAD_BYTES) return null
            val slotIndex = payload[PAYLOAD_BYTES - 1].toInt()
            if (slotIndex !in 0 until SlotArray.SLOT_COUNT) return null

            return OpenVault(
                payload.copyOfRange(0, SlotArray.VMK_BYTES),
                payload.copyOfRange(SlotArray.VMK_BYTES, SlotArray.VMK_BYTES + SlotArray.BLOB_ID_BYTES),
                slotIndex,
            )
        } finally {
            payload.fill(0)
        }
    }
}
