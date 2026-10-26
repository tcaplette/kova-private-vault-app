package com.app.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What pairing must not become — P4-4, in `ShareOpenTest`'s shape.
 *
 * The rules this reads for are shape rules, and shape is exactly what nothing
 * fails on: a second pairing route, a key rendered on a screen, a frame written to
 * disk and a permission added for the camera that was already declared would each
 * work perfectly while being the defect. JVM, because it reads source.
 */
class PairingScanTest {

    private fun repoRoot(): File {
        var dir: File? = File(".").canonicalFile
        while (dir != null) {
            if (File(dir, "app/src/main/java/com/app/vault").isDirectory) return dir
            if (File(dir, "src/main/java/com/app/vault").isDirectory) return dir.parentFile!!
            dir = dir.parentFile
        }
        throw AssertionError("could not locate the repository from ${File(".").absolutePath}")
    }

    private fun source(name: String): String =
        File(repoRoot(), "app/src/main/java/com/app/vault/$name").readText()

    private fun stripped(text: String): String = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//.*"""), "")

    private fun mainSources(): List<File> =
        File(repoRoot(), "app/src/main/java/com/app/vault")
            .walkTopDown().filter { it.extension == "kt" }.toList()

    @Test
    fun thePairingPathExists() {
        listOf(
            "Identity.kt",
            "PairingQr.kt",
            "ui/PairingScreen.kt",
            "ui/ContactsScreen.kt",
        ).forEach {
            assertTrue("$it not found", File(repoRoot(), "app/src/main/java/com/app/vault/$it").isFile)
        }
    }

    // ── I1: the camera was already declared, and nothing else is ──────────────

    @Test
    fun pairingAddsNoPermission() {
        val manifest = File(repoRoot(), "app/src/main/AndroidManifest.xml").readText()
        val declared = Regex("""<uses-permission[^>]*android:name="([^"]+)"""", RegexOption.DOT_MATCHES_ALL)
            .findAll(manifest).map { it.groupValues[1] }.toSet()
        // The set as it stood before P4-4. A scanner reuses P2-17's CAMERA; zxing
        // is a plain JAR and declares nothing. Any addition fails here first and
        // `:app:check<Variant>Permissions` second.
        assertEquals(
            setOf(
                "android.permission.RECEIVE_BOOT_COMPLETED",
                "android.permission.ACCESS_NETWORK_STATE",
                "android.permission.INTERNET",
                "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
                "android.permission.CAMERA",
                "android.permission.RECORD_AUDIO",
                "android.permission.READ_MEDIA_AUDIO",
                "android.permission.READ_EXTERNAL_STORAGE",
                "android.permission.USE_FINGERPRINT",
                // `destination-settings-and-slideshow` D6: a memo that records with the screen off,
                // and its Bluetooth microphone. Not pairing's.
                "android.permission.FOREGROUND_SERVICE",
                "android.permission.FOREGROUND_SERVICE_MICROPHONE",
                "android.permission.POST_NOTIFICATIONS",
                "android.permission.MODIFY_AUDIO_SETTINGS",
            ),
            declared,
        )
    }

    @Test
    fun pairingRegistersNoComponent() {
        val manifest = File(repoRoot(), "app/src/main/AndroidManifest.xml").readText()
        // "Identity" is deliberately absent from this list: P2-22's launcher
        // aliases carry that word already, and a scan that fails on the wrong
        // thing teaches the next reader to loosen it.
        listOf("Pairing", "Contacts", "zxing", "QrCode", "Scanner").forEach {
            assertTrue(
                "the manifest must not name $it — pairing is two composables, not a component",
                !manifest.contains(it),
            )
        }
    }

    // ── two ways in: the scanner, and the authenticated remote exchange ───────

    @Test
    fun theOnlyRoutesToAContactAreAScanOrAnAuthenticatedExchange() {
        // Whoever calls `Identity.add` is a pairing route. There are two callers
        // (`contact-pairing`): the scan, whose argument comes from a decoded frame,
        // and remote pairing, whose argument is what an AEAD under the CPace key
        // opened to.
        val callers = mainSources()
            .filter { it.name != "Identity.kt" }
            .filter { stripped(it.readText()).contains("Identity.add(") }
            .map { it.name }
            .sorted()
        assertEquals("exactly two files may add a contact", listOf("MainActivity.kt", "RemotePairing.kt"), callers)

        val remote = stripped(source("RemotePairing.kt"))
        assertEquals(
            "remote pairing adds only a key the session's AEAD opened",
            2,
            Regex("""Identity\.add\(context, peerKey, session\.name\)""").findAll(remote).count(),
        )
        assertEquals(
            "and both peer keys come from an authenticated decryption",
            2,
            Regex("""val peerKey = open\(""").findAll(remote).count(),
        )

        val activity = stripped(source("MainActivity.kt"))
        assertTrue(
            "the stored key must be the one a frame decoded",
            activity.contains("Identity.add(this, key, pairingName)") &&
                activity.contains("val key = pairingScanned"),
        )
        assertTrue(
            "pairingScanned may only be set from a parsed payload",
            activity.contains("pairingScanned = parsed.publicKey"),
        )
        assertEquals(
            "there must be exactly one place a scanned key is accepted",
            1,
            Regex("""pairingScanned = parsed\.publicKey""").findAll(activity).count(),
        )
    }

    @Test
    fun aPayloadIsParsedInOnePlace() {
        val parsers = mainSources()
            .filter { stripped(it.readText()).contains("PairingCode.parse(") }
            .map { it.name }
        assertEquals(listOf("MainActivity.kt"), parsers)
    }

    // ── nothing about pairing reaches disk ────────────────────────────────────

    @Test
    fun onlyTheIdentityFileIsWritten() {
        listOf(
            "PairingQr.kt", "ui/PairingScreen.kt", "ui/ContactsScreen.kt",
            "Cpace.kt", "RemotePairing.kt", "ui/RemotePairingScreen.kt",
        ).forEach { name ->
            val text = stripped(source(name))
            listOf(
                "FileOutputStream", "cacheDir", "getExternalFilesDir", "createTempFile",
                "MediaStore", "compress(",
            ).forEach {
                assertTrue("$name must not reach $it", !text.contains(it))
            }
        }
        val identity = stripped(source("Identity.kt"))
        listOf("cacheDir", "getExternalFilesDir", "createTempFile", "MediaStore")
            .forEach { assertTrue("Identity must not reach $it", !identity.contains(it)) }
        // Four names: the identity file and the waiting-session file (`remote-pairing`
        // D4), each with the temp file it is committed from.
        assertTrue(
            "the identity path writes identity.bin, pairing.bin and their temp files, and nothing else",
            identity.contains("""FILE = "identity.bin"""") &&
                identity.contains("""TEMP = "identity.bin.tmp"""") &&
                identity.contains("""PAIRING_FILE = "pairing.bin"""") &&
                identity.contains("""PAIRING_TEMP = "pairing.bin.tmp""""),
        )
        assertTrue(
            "the write must be temp → sync → rename",
            identity.contains("out.fd.sync()") && identity.contains("tempFile.renameTo("),
        )
        assertEquals(
            "both files go through the one commit",
            1,
            Regex("""FileOutputStream\(""").findAll(identity).count(),
        )
    }

    @Test
    fun theCameraFrameIsDecodedAndDropped() {
        val screen = stripped(source("ui/PairingScreen.kt"))
        assertTrue(
            "a frame goes to the decoder and nowhere else",
            screen.contains("PairingQr.decode("),
        )
        listOf("Bitmap", "ByteArrayOutputStream", "File(").forEach {
            assertTrue("the scan state must not hold a frame in $it", !screen.contains(it))
        }
    }

    @Test
    fun thePayloadTravelsAsBytesNeverAsDecodedText() {
        // `share-format-v2`: the payload is a raw 1,216-byte key. zxing guesses a
        // character set for byte-mode text, and a guess turns arbitrary bytes into
        // something else — so the decoder reads byte segments, and the encoder
        // writes ISO-8859-1, which maps each byte to one character.
        val qr = stripped(source("PairingQr.kt"))
        assertTrue("decoded from byte segments", qr.contains("ResultMetadataType.BYTE_SEGMENTS"))
        assertTrue("never from Result.text", !Regex("""\.text\b""").containsMatchIn(qr))
        assertTrue("encoded as ISO-8859-1", qr.contains("\"ISO-8859-1\""))
        assertTrue("at level M", qr.contains("ErrorCorrectionLevel.M"))
        val screen = stripped(source("ui/PairingScreen.kt"))
        assertTrue("the screen hands bytes on", screen.contains("onDecoded: (ByteArray) -> Unit"))
    }

    // ── the contact list shows names, and never keys ──────────────────────────

    @Test
    fun theContactListRendersNoKeyMaterial() {
        val contacts = stripped(source("ui/ContactsScreen.kt"))
        listOf("PairingCode", "Base64", "toHexString", "joinToString", "%02x").forEach {
            assertTrue("the contact list must not render key material via $it", !contacts.contains(it))
        }
        // The screen never handles a key at all: it passes the whole contact up,
        // and the key is read one layer above, where the removal happens.
        assertEquals(
            "the contact list must not touch key material",
            0,
            Regex("""publicKey""").findAll(contacts).count(),
        )
    }

    @Test
    fun onlyAnAnsweredConfirmationDiscardsTheIdentity() {
        // The screen's control opens a question; nothing on the way to it deletes.
        // `Identity.startNewIdentity` is the only call that throws the file away,
        // and it must hang off the confirmation's answer and nothing else.
        val callers = mainSources()
            .filter { it.name != "Identity.kt" }
            .filter { stripped(it.readText()).contains("Identity.startNewIdentity(") }
            .map { it.name }
        assertEquals(listOf("MainActivity.kt"), callers)

        val activity = stripped(source("MainActivity.kt"))
        assertTrue(
            "the row's control may only raise the question",
            activity.contains("onAskNewIdentity = { confirmingNewIdentity = true }"),
        )
        assertTrue(
            "and the deletion may only hang off the answer",
            activity.contains("onConfirmNewIdentity = ::startNewIdentity"),
        )
        assertTrue(
            "dismissing it must delete nothing",
            activity.contains("onDismissNewIdentity = { confirmingNewIdentity = false }"),
        )
    }

    @Test
    fun theIdentityIsNeverPrintedExportedOrMirrored() {
        // The kit, the archive and the mirror each enumerate what they carry. None
        // of them may learn this file's name — device loss means re-pairing, and
        // that claim is only true while nothing copies it off the device.
        listOf(
            "RecoveryKitPrintAdapter.kt", "VaultArchive.kt", "CloudMirror.kt",
            "MirrorWorker.kt", "ExportWorker.kt", "ExportManifest.kt",
        ).forEach { name ->
            val text = stripped(source(name))
            listOf("identity.bin", "Identity.").forEach {
                assertTrue("$name must not touch $it", !text.contains(it))
            }
        }
    }

    @Test
    fun theWrappingKeyIsItsOwnAlias() {
        val identity = stripped(source("Identity.kt"))
        assertTrue(identity.contains("""ALIAS = "vault.identity""""))
        // Distinct from P2-5's anchor and P1-9's biometric wrap: three keys, three
        // lifetimes, and deleting one must not disturb another.
        assertTrue(!identity.contains("vault.anchor"))
        assertTrue(
            "the identity key must not require authentication — see FORMAT.md §13",
            !identity.contains("setUserAuthenticationRequired(true)"),
        )
    }

    @Test
    fun theStoreIsNotSealedUnderAVaultKey() {
        val identity = stripped(source("Identity.kt"))
        listOf("VaultKeys", "vmk", "VMK", "SlotArray", "BlobStore").forEach {
            assertTrue(
                "identity is install-scoped: it must not reach $it",
                !identity.contains(it),
            )
        }
    }
}
