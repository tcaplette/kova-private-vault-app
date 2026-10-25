package com.app.vault

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/**
 * P1-9 — the biometric wrap, `FORMAT.md` §4.1.
 *
 * **Why most of these use a key of their own.** The production key requires a
 * live strong biometric for every operation, which is the entire point of it and
 * also means no unattended test can call `doFinal` on it. So the record layout is
 * driven through a key that is identical except for the authentication binding,
 * and the binding itself is asserted separately from `KeyInfo` — the one thing a
 * test *can* read without a finger — plus the device run in P1-9's Verify.
 *
 * Stated plainly because the alternative is worse: a test seam inside
 * `BiometricWrap` that let production code create an unbound key would be a
 * bypass shipped in the APK to make a test easier.
 */
@RunWith(AndroidJUnit4::class)
class BiometricWrapTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var slotsFile: File
    private lateinit var store: BlobStore
    private lateinit var lifecycle: VaultLifecycle
    private lateinit var bioFile: File
    private lateinit var bio: BiometricWrap

    private companion object {
        const val TAG = "BiometricWrapTest"
        const val TEST_ALIAS = "vault.bio.test"
        const val GOOD = "correct horse battery staple"
    }

    private fun pass(s: String) = s.toByteArray(Charsets.UTF_8)

    @Before
    fun setUp() {
        VaultSession.lock()
        root = File(ctx.filesDir, "bio-${System.nanoTime()}")
        root.mkdirs()
        slotsFile = File(root, "vault.slots")
        store = BlobStore(File(root, "blobs"))
        lifecycle = VaultLifecycle(slotsFile, store)
        bioFile = File(root, BiometricWrap.FILE_NAME)
        bio = BiometricWrap(bioFile)
        clearAliases()
    }

    @After
    fun tearDown() {
        VaultSession.lock()
        ThumbnailCache.clear()
        clearAliases()
        root.deleteRecursively()
    }

    /**
     * `VaultLifecycleTest` asserts the app holds **no** Keystore alias at all, so
     * a stray one left here would fail I7's behavioural half in a completely
     * different file. Both aliases go, before and after.
     */
    private fun clearAliases() {
        val ks = keystore()
        // `InstallAnchor.ALIAS` joins the two this file owns because the assertion
        // below is "the app holds **no** alias", and `DeviceEvents` legitimately holds
        // one — a fresh-install marker whose whole mechanism is a Keystore entry. A
        // suite run where those tests came first left it behind and failed this one
        // for something it does not test. Clearing it is the isolation this function
        // already existed to provide; `DeviceEvents` creates its own when it needs it.
        listOf(BiometricWrap.ALIAS, TEST_ALIAS, InstallAnchor.ALIAS)
            .forEach { runCatching { ks.deleteEntry(it) } }
    }

    private fun keystore(): KeyStore =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    // --- a key with the same shape, minus the authentication binding ----------

    private fun testKey(alias: String = TEST_ALIAS): SecretKey {
        (keystore().getKey(alias, null) as SecretKey?)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun encryptCipher(): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, testKey()) }

    private fun decryptCipher(): Cipher {
        val iv = bioFile.readBytes().copyOfRange(0, BiometricWrap.IV_BYTES)
        return Cipher.getInstance("AES/GCM/NoPadding")
            .apply { init(Cipher.DECRYPT_MODE, testKey(), GCMParameterSpec(128, iv)) }
    }

    private fun openVault(): OpenVault {
        lifecycle.create(pass(GOOD))
        return VaultSession.openVault()!!
    }

    private fun blobFiles() = File(root, "blobs").listFiles().orEmpty().filter { it.isFile }

    private fun digest(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // --- the record -----------------------------------------------------------

    @Test
    fun theWrapReturnsTheSameKeysThePassphraseProduced() {
        val vault = openVault()

        bio.write(encryptCipher(), vault)
        val back = bio.read(decryptCipher())

        assertNotNull("the wrap must open", back)
        assertArrayEquals("VMK must round trip", vault.vmk, back!!.vmk)
        assertArrayEquals("blob ID must round trip", vault.metadataBlobId, back.metadataBlobId)
        assertEquals("slot index must round trip", vault.slotIndex, back.slotIndex)
    }

    @Test
    fun theItemsAreIntactAfterOpeningFromTheWrap() {
        val vault = openVault()
        val written = store.write(java.io.ByteArrayInputStream(ByteArray(3000) { 7 }), vault.vmk)
        val db = MetadataDb.load(store, vault.metadataBlobId, vault.vmk)
        db.items += VaultItem(
            listOf(written.blobId), written.plaintextLength, null, "still-here.bin", null, null, 0, emptyList()
        )
        db.save(store, vault.metadataBlobId, vault.vmk)

        bio.write(encryptCipher(), vault)
        val recovered = bio.read(decryptCipher())!!
        VaultSession.lock()
        VaultLifecycle(slotsFile, store).open(recovered)

        val back = MetadataDb.load(store, VaultSession.metadataBlobId()!!, VaultSession.vmk()!!)
        assertEquals("the index must load with its items", 1, back.items.size)
        assertEquals("still-here.bin", back.items[0].filename)
    }

    @Test
    fun enablingChangesNothingElseOnDisk() {
        val vault = openVault()
        val slotsBefore = slotsFile.readBytes()
        val blobsBefore = blobFiles().associate { it.name to digest(it) }

        bio.write(encryptCipher(), vault)

        assertArrayEquals("vault.slots must not be touched", slotsBefore, slotsFile.readBytes())
        assertEquals(
            "no blob may be written, removed or rewritten",
            blobsBefore, blobFiles().associate { it.name to digest(it) }
        )
    }

    @Test
    fun theRecordIsAFixedSizeWhateverItHolds() {
        val first = openVault()
        bio.write(encryptCipher(), first)
        val firstLength = bioFile.length()

        // A second vault, in its own storage, with different keys entirely.
        val otherRoot = File(ctx.filesDir, "bio-other-${System.nanoTime()}")
        otherRoot.mkdirs()
        try {
            VaultSession.lock()
            val otherStore = BlobStore(File(otherRoot, "blobs"))
            VaultLifecycle(File(otherRoot, "vault.slots"), otherStore).create(pass(GOOD))
            val otherFile = File(otherRoot, BiometricWrap.FILE_NAME)
            BiometricWrap(otherFile).write(encryptCipher(), VaultSession.openVault()!!)

            Log.i(TAG, "record sizes: $firstLength, ${otherFile.length()}")
            assertEquals(BiometricWrap.FILE_BYTES.toLong(), firstLength)
            assertEquals(BiometricWrap.FILE_BYTES.toLong(), otherFile.length())
        } finally {
            otherRoot.deleteRecursively()
        }
    }

    // --- failure paths --------------------------------------------------------

    @Test
    fun aWipedKeyDisablesTheWrapAndLeavesThePassphraseWorking() {
        val vault = openVault()
        bio.write(encryptCipher(), vault)
        // The alias, not the production key: this test is about the pair
        // disagreeing, and generating an auth-bound key needs an enrolled finger
        // the test runner does not have.
        testKey(BiometricWrap.ALIAS)
        assertTrue("both halves present means enabled", bio.isEnabled())

        // Exactly what P1-9's Verify does to the device: the entry goes, the file
        // stays. The pair now disagrees, and nothing may crash on it.
        keystore().deleteEntry(BiometricWrap.ALIAS)

        assertFalse("a file with no key is not enabled", bio.isEnabled())
        assertFalse("the orphaned file must be deleted", bioFile.exists())

        VaultSession.lock()
        assertTrue(
            "the passphrase must still open the vault",
            VaultLifecycle(slotsFile, store).unlockAndOpen(pass(GOOD))
        )
        assertEquals("items must survive", 0, MetadataDb.load(
            store, VaultSession.metadataBlobId()!!, VaultSession.vmk()!!
        ).items.size)
    }

    @Test
    fun aTruncatedOrAlteredRecordOpensNothing() {
        val vault = openVault()

        bio.write(encryptCipher(), vault)
        val whole = bioFile.readBytes()

        bioFile.writeBytes(whole.copyOfRange(0, whole.size - 1))
        assertNull("a truncated record must not open", bio.read(decryptCipher()))

        bioFile.writeBytes(whole.copyOf().also { it[BiometricWrap.IV_BYTES] = (it[BiometricWrap.IV_BYTES] + 1).toByte() })
        assertNull("a flipped ciphertext bit must not open", bio.read(decryptCipher()))

        bio.disable()
        assertFalse(bioFile.exists())

        VaultSession.lock()
        assertTrue(
            "the passphrase path is untouched by any of this",
            VaultLifecycle(slotsFile, store).unlockAndOpen(pass(GOOD))
        )
    }

    @Test
    fun aFreshVaultHasNoWrapAndNoAlias() {
        lifecycle.create(pass(GOOD))

        assertFalse("creation must write no wrap", bioFile.exists())
        assertFalse("creation must create no key", bio.isEnabled())
        val aliases = keystore().aliases().toList()
        Log.i(TAG, "AndroidKeyStore aliases after creation: $aliases")
        assertTrue("creation must touch no Keystore alias", aliases.isEmpty())
    }

    // --- opening from recovered keys (vault-lifecycle) -------------------------

    @Test
    fun openingFromTheWrapChurnsChaffExactlyAsAnUnlockDoes() {
        val vault = openVault()
        bio.write(encryptCipher(), vault)

        val id = vault.metadataBlobId.copyOf()
        val before = blobFiles().associate { it.name to digest(it) }
        val ownedBefore = MetadataDb.load(store, id, VaultSession.vmk()!!)
            .ownedChaff.map { it.joinToString("") { b -> "%02x".format(b) } }.toSet()

        val recovered = bio.read(decryptCipher())!!
        VaultSession.lock()
        VaultLifecycle(slotsFile, store).open(recovered)

        assertTrue("the session must hold the key", VaultSession.isUnlocked)

        val after = blobFiles().associate { it.name to digest(it) }
        val ownedAfter = MetadataDb.load(store, id, VaultSession.vmk()!!)
            .ownedChaff.map { it.joinToString("") { b -> "%02x".format(b) } }.toSet()
        val gone = before.keys - after.keys
        val fresh = after.keys - before.keys
        Log.i(TAG, "biometric open churn: -${gone.size} +${fresh.size}")

        assertEquals("exactly one blob must disappear", 1, gone.size)
        assertEquals("exactly one must appear", 1, fresh.size)
        assertTrue("it must have been owned chaff", gone.single() in ownedBefore)
        assertEquals("owned chaff count must not drift", ownedBefore.size, ownedAfter.size)

        // The index and the tamper log both change, exactly as they do on the
        // passphrase path — which is the point of this test: one `open`, one set
        // of side effects, so a biometric unlock is not a quieter unlock.
        val indexName = id.joinToString("") { "%02x".format(it) }
        val logName = MetadataDb.load(store, id, VaultSession.vmk()!!)
            .logBlobId!!.joinToString("") { "%02x".format(it) }
        val changed = before.keys.intersect(after.keys).filterNot { before[it] == after[it] }
        assertEquals(
            "only the index and the log may change content",
            setOf(indexName, logName), changed.toSet()
        )
    }

    // --- the binding itself ----------------------------------------------------

    /**
     * The half no unbound key can stand in for. Skipped where no biometric is
     * enrolled, because the platform refuses to generate the key at all then —
     * which is itself the behaviour P1-9 relies on.
     */
    @Test
    fun theProductionKeyIsBoundToAStrongBiometricAndOneUse() {
        assumeTrue(
            "no strong biometric enrolled on this device",
            BiometricWrap.isAvailable(ctx)
        )

        bio.createKey()
        assertBound(BiometricWrap.ALIAS)
    }

    private fun assertBound(alias: String) {
        val key = keystore().getKey(alias, null) as SecretKey
        val info = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
            .getKeySpec(key, KeyInfo::class.java) as KeyInfo

        Log.i(
            TAG,
            "$alias KeyInfo: authRequired=${info.isUserAuthenticationRequired} " +
                "type=${info.userAuthenticationType} " +
                "validity=${info.userAuthenticationValidityDurationSeconds} " +
                "invalidatedByEnrollment=${info.isInvalidatedByBiometricEnrollment}"
        )

        assertTrue("the key must require authentication", info.isUserAuthenticationRequired)
        assertEquals(
            "strong biometric only — never the device credential",
            KeyProperties.AUTH_BIOMETRIC_STRONG, info.userAuthenticationType
        )
        assertEquals(
            "per-use authentication, so no window exists in which the key is warm",
            0, info.userAuthenticationValidityDurationSeconds
        )
        assertTrue(
            "enrolling a biometric must destroy the wrap",
            info.isInvalidatedByBiometricEnrollment
        )
    }
}
