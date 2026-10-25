package com.app.vault

import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput

/**
 * What a test does now to get where an unlock used to leave it — `vault-keyring`.
 *
 * **A passphrase opens a keyring and stops there.** Before this change, unlocking put
 * a vault's key in `VaultSession` and every suite below read it straight afterwards.
 * Opening a vault is a second act, from the list, and these two functions are that
 * act — written once here rather than four times across the suites that need it.
 *
 * Deliberately **not** a convenience on `VaultLifecycle`. A production function that
 * exists because tests are shaped a certain way is a production function nobody can
 * safely delete, and this app's rule about the unlock path is that it does one thing.
 */

/**
 * Unlocks and opens the vault at [position], running the per-vault work
 * `VaultLifecycle.open` does — the chaff churn and the tamper-log entry.
 *
 * Returns false when the passphrase opened no keyring, or the keyring had no vault
 * there, so a caller can assert on it exactly as it asserted on `unlock().opened`.
 */
fun VaultLifecycle.unlockAndOpen(
    passphrase: ByteArray,
    position: Int = 0,
): Boolean {
    if (!unlock(passphrase).opened) return false
    val opened = VaultKeyringSession.openVault(position) ?: return false
    open(opened)
    return true
}

/**
 * Opens the vault at [position] on a keyring that is already open.
 *
 * For a test that unlocked for its own reasons — to assert on the outcome's counts,
 * say — and then wants the vault as well.
 */
fun VaultLifecycle.openVaultAt(position: Int = 0): Boolean {
    val opened = VaultKeyringSession.openVault(position) ?: return false
    open(opened)
    return true
}

/**
 * Taps through the vault list to the grid — `vault-keyring`.
 *
 * **A passphrase opens a keyring, not a vault.** Every UI suite's `unlock()` used to
 * end by waiting for the grid, because unlocking landed there; it now lands on the
 * list of vaults, and the grid is behind whichever one the user taps. This is that
 * tap, written once rather than ten times.
 *
 * [name] is what `VaultLifecycle.create` calls the vault it makes alongside a new
 * keyring, which is what every fixture in these suites creates.
 */
fun androidx.compose.ui.test.junit4.ComposeTestRule.openFirstVaultFromTheList(
    name: String = VaultKeyringSession.MIGRATED_NAME,
) {
    waitUntil(timeoutMillis = 45_000) {
        onAllNodes(androidx.compose.ui.test.hasText(name)).fetchSemanticsNodes().isNotEmpty()
    }
    onNode(androidx.compose.ui.test.hasText(name)).performClick()
    waitForIdle()
}

/**
 * From the vault list to the Intruder capture screen — `intruder-captures-at-home`: the home bar's
 * drawer control, then the drawer's Intruder capture row. Waits for the screen's settings control.
 */
fun androidx.compose.ui.test.junit4.ComposeTestRule.openIntruderCaptureFromHome() {
    waitUntil(timeoutMillis = 45_000) {
        onAllNodes(androidx.compose.ui.test.hasContentDescription("Menu")).fetchSemanticsNodes().isNotEmpty()
    }
    onNode(androidx.compose.ui.test.hasContentDescription("Menu")).performClick()
    waitUntil(timeoutMillis = 15_000) {
        onAllNodes(androidx.compose.ui.test.hasText("Intruder capture")).fetchSemanticsNodes().isNotEmpty()
    }
    onAllNodes(androidx.compose.ui.test.hasText("Intruder capture"))[0].performClick()
    waitUntil(timeoutMillis = 15_000) {
        onAllNodes(androidx.compose.ui.test.hasContentDescription("Capture settings")).fetchSemanticsNodes().isNotEmpty()
    }
    waitForIdle()
}

/** Seals a capture of [mode] to the open keyring's store, as a failed unlock leaves one pending. */
fun stageKeyringCapture(
    context: android.content.Context,
    store: BlobStore,
    mode: Capture.Mode,
    media: ByteArray,
    stage: CaptureRecord.Stage = CaptureRecord.Stage(CaptureRecord.STAGE_PASSPHRASE),
    takenAt: Long = System.currentTimeMillis(),
    enrol: Boolean = true,
) {
    if (enrol) {
        val key = VaultKeyringSession.captureStoreKey()!!
        val kp = VaultKeys.captureKeypair(key)
        Capture.setDestination(context, kp.publicKey)
        Capture.setEnabled(context, mode, true)
        kp.zero()
        key.fill(0)
    }
    val sealed = Capture.seal(Capture.destinationKey(context)!!, CaptureRecord.prepend(stage, takenAt, media))
    val blobId = Capture.newBlobId()
    store.writeSealed(blobId, sealed)
    Capture.add(context, mode, blobId, sealed.size, store)
}

/** A real, decodable photograph — so the capture gets a thumbnail and its tile has something to draw. */
fun capturePhotoBytes(colour: Int = android.graphics.Color.rgb(30, 120, 160)): ByteArray {
    val bmp = android.graphics.Bitmap.createBitmap(240, 180, android.graphics.Bitmap.Config.ARGB_8888)
    android.graphics.Canvas(bmp).drawColor(colour)
    val out = java.io.ByteArrayOutputStream()
    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
    bmp.recycle()
    return out.toByteArray()
}

/** Both levels of lock, because a keyring left open leaks into the next test. */
fun lockKeyringAndVault() {
    VaultLifecycle.lockNow()
    VaultKeyringSession.lock()
}

// --- backups — `backup-file-recover-and-import` -----------------------------------------

/**
 * The open vault's names as a backup carries them: its blobs, its index, the keyring — and its own
 * capture store, `per-vault-intruder-capture`.
 */
fun openVaultBackupNames(store: BlobStore): Set<String> {
    val vmk = VaultSession.vmk()!!
    val id = VaultSession.metadataBlobId()!!
    return CloudMirror.vaultNames(
        MetadataDb.load(store, id, vmk), id, VaultKeyringSession.keyringBlobId(), CloudMirror.captureStoreOf(store, vmk),
    )
}

/** The open vault's `backup.bin`, written as `MirrorWorker` writes it. */
fun openVaultBackupFile(store: BlobStore, blobsDir: java.io.File, scratch: java.io.File): ByteArray {
    val names = openVaultBackupNames(store)
    val files = BackupFile.snapshot(blobsDir, names, names, scratch)
    return java.io.ByteArrayOutputStream().also { BackupFile.write(it, files, VaultKeyringSession.backupKey()!!) }
        .toByteArray().also { BackupFile.dropSnapshot(scratch) }
}

/** [payloads] as items of the open vault; returns what was written, in order. */
fun putItems(store: BlobStore, payloads: List<ByteArray>): List<WrittenItem> {
    val vmk = VaultSession.vmk()!!
    val id = VaultSession.metadataBlobId()!!
    val written = payloads.map { store.writeItem(it.inputStream(), vmk) }
    MetadataDb.commit(store, id, vmk) { db ->
        written.forEachIndexed { i, item ->
            db.items += VaultItem(
                blobIds = item.blobIds, plaintextLength = item.plaintextLength, thumb = null,
                filename = "item-$i.bin", album = null, createdAt = null, importedAt = 0L, tags = emptyList(),
            )
        }
    }
    return written
}

/** Whether the open vault reads [payloads] back as [written]. */
fun openVaultHolds(store: BlobStore, written: List<WrittenItem>, payloads: List<ByteArray>): Boolean {
    val vmk = VaultSession.vmk() ?: return false
    return payloads.indices.all { i ->
        runCatching {
            store.reader(written[i].blobIds, vmk, written[i].plaintextLength).readRange(0, payloads[i].size)
                .contentEquals(payloads[i])
        }.getOrDefault(false)
    }
}

/** Every file in a directory, name to content digest. */
fun digests(dir: java.io.File): Map<String, String> =
    dir.listFiles().orEmpty().filter { it.isFile }.associate { f ->
        f.name to java.security.MessageDigest.getInstance("SHA-256").digest(f.readBytes())
            .joinToString("") { "%02x".format(it) }
    }

/**
 * A folder on disk, behaving as a destination does — `CloudMirror.Target`'s second
 * implementation. A SAF tree can only be granted by a person in the picker, so tests read
 * and write a directory instead. [overwrite] truncates, as mode `"wt"` does.
 */
class DirectoryTarget(private val dir: java.io.File) : CloudMirror.Target {
    init { dir.mkdirs() }

    private fun fileOf(uri: android.net.Uri) = java.io.File(uri.path!!)

    override fun names(): Map<String, RemoteEntry> =
        dir.listFiles().orEmpty().filter { it.isFile }.associate {
            it.name to RemoteEntry(android.net.Uri.fromFile(it), it.length(), it.lastModified())
        }

    override fun create(name: String, mime: String, dateTaken: Long?): ExportEntry {
        val f = java.io.File(dir, name)
        f.createNewFile()
        return ExportEntry(android.net.Uri.fromFile(f), name)
    }

    override fun openOutput(entry: ExportEntry): java.io.OutputStream = fileOf(entry.uri).outputStream()

    override fun openInput(entry: ExportEntry): java.io.InputStream = fileOf(entry.uri).inputStream()

    override fun overwrite(uri: android.net.Uri): java.io.OutputStream = fileOf(uri).outputStream()

    override fun discard(entry: ExportEntry) {
        fileOf(entry.uri).delete()
    }
}

// --- a vault's own capture — `per-vault-intruder-capture` ----------------------------------

/** From the vault list into the vault [name] that has its own [passphrase], through its sheet. */
fun androidx.compose.ui.test.junit4.ComposeTestRule.openProtectedVaultFromTheList(name: String, passphrase: String) {
    enterVaultPassphrase(name, passphrase)
    waitUntil(timeoutMillis = 45_000) {
        onAllNodes(androidx.compose.ui.test.hasContentDescription("All vaults")).fetchSemanticsNodes().isNotEmpty()
    }
    waitForIdle()
}

/** Taps the vault [name] on the list and enters [passphrase] on its sheet, right or wrong. */
fun androidx.compose.ui.test.junit4.ComposeTestRule.enterVaultPassphrase(name: String, passphrase: String) {
    val row = androidx.compose.ui.test.hasText(name) and androidx.compose.ui.test.hasClickAction()
    waitUntil(timeoutMillis = 45_000) { onAllNodes(row).fetchSemanticsNodes().isNotEmpty() }
    onNode(row).performClick()
    submitVaultPassphrase(passphrase)
}

/** On an open vault passphrase sheet: focus, type, and press Open vault. */
fun androidx.compose.ui.test.junit4.ComposeTestRule.submitVaultPassphrase(passphrase: String) {
    val field = androidx.compose.ui.test.hasSetTextAction()
    waitUntil(timeoutMillis = 15_000) { onAllNodes(field).fetchSemanticsNodes().isNotEmpty() }
    onNode(field).performClick()
    waitForIdle()
    onNode(field).performTextInput(passphrase)
    onNode(androidx.compose.ui.test.hasText("Open vault") and androidx.compose.ui.test.hasClickAction())
        .performScrollTo().performClick()
    waitForIdle()
}

/** From the open vault's grid to its Intruder capture screen, through its drawer's row. */
fun androidx.compose.ui.test.junit4.ComposeTestRule.openVaultIntruderCapture() {
    onNode(androidx.compose.ui.test.hasContentDescription("Trash and settings")).performClick()
    val row = androidx.compose.ui.test.hasText("Intruder capture") and androidx.compose.ui.test.hasClickAction()
    waitUntil(timeoutMillis = 15_000) { onAllNodes(row).fetchSemanticsNodes().isNotEmpty() }
    onNode(row).performScrollTo().performClick()
    waitUntil(timeoutMillis = 15_000) {
        onAllNodes(androidx.compose.ui.test.hasContentDescription("Capture settings")).fetchSemanticsNodes().isNotEmpty()
    }
    waitForIdle()
}

/** From the vault list to Edit vault for [name], through its row's menu — `vault-passphrase-manage`. */
fun androidx.compose.ui.test.junit4.ComposeTestRule.openEditVault(name: String) {
    val options = androidx.compose.ui.test.hasContentDescription("Options for $name")
    waitUntil(timeoutMillis = 45_000) { onAllNodes(options).fetchSemanticsNodes().isNotEmpty() }
    onNode(options).performClick()
    val edit = androidx.compose.ui.test.hasText("Edit vault") and androidx.compose.ui.test.hasClickAction()
    waitUntil(timeoutMillis = 15_000) { onAllNodes(edit).fetchSemanticsNodes().isNotEmpty() }
    onNode(edit).performClick()
    waitUntil(timeoutMillis = 15_000) {
        onAllNodes(androidx.compose.ui.test.hasText("Passphrase")).fetchSemanticsNodes().isNotEmpty()
    }
    waitForIdle()
}

/** The open keyring's capture store's index, or an empty one where it has none. */
fun keyringCaptureStore(store: BlobStore): MetadataDb {
    val key = VaultKeyringSession.captureStoreKey()!!
    return try {
        val id = VaultKeys.metadataBlobId(key)
        if (store.fileOf(id).exists()) MetadataDb.load(store, id, key) else MetadataDb.empty()
    } finally {
        key.fill(0)
    }
}

/** [block] over the open keyring's capture store, as the activity holds it. */
fun <T> withKeyringCaptureStore(store: BlobStore, block: (OpenVault) -> T): T {
    val key = VaultKeyringSession.captureStoreKey()!!
    return try {
        block(CaptureStore.open(store, key))
    } finally {
        key.fill(0)
    }
}

/**
 * Enrols the vault at [position] in its own capture with [modes], as its sheet does — its capture
 * store's public key, into the keyring's capture store. Returns the vault's index ID.
 */
fun enrolVaultCapture(store: BlobStore, position: Int, kek: ByteArray?, modes: Set<Capture.Mode>): ByteArray =
    VaultKeyringSession.withVaultKey(position, kek) { vmk, id ->
        val key = VaultKeys.captureStoreKey(vmk)
        val kp = VaultKeys.captureKeypair(key)
        try {
            withKeyringCaptureStore(store) { CaptureStore.enrol(store, it, id, kp.publicKey, modes) }
            id
        } finally {
            kp.zero()
            key.fill(0)
        }
    }!!

/** Seals [media] to the enrolled vault [indexId] and queues it, as a wrong passphrase on its sheet does. */
fun queueVaultCapture(
    store: BlobStore,
    indexId: ByteArray,
    mode: Capture.Mode,
    media: ByteArray,
    takenAt: Long = System.currentTimeMillis(),
    stage: CaptureRecord.Stage = CaptureRecord.Stage(CaptureRecord.STAGE_VAULT_PASSPHRASE),
): ByteArray {
    val publicKey = CaptureStore.enrolment(keyringCaptureStore(store), indexId)!!.publicKey
    val sealed = Capture.seal(publicKey, CaptureRecord.prepend(stage, takenAt, media))
    val blobId = Capture.newBlobId()
    store.writeSealed(blobId, sealed)
    withKeyringCaptureStore(store) { CaptureStore.queue(store, it, indexId, mode, blobId, sealed.size) }
    return blobId
}

/** The queued captures for the vault [indexId], oldest first. */
fun vaultQueue(store: BlobStore, indexId: ByteArray): List<VaultPending> =
    keyringCaptureStore(store).vaultPending.filter { it.indexBlobId.contentEquals(indexId) }

/** The vault [vault]'s own capture store's index and log, or null where it has none. */
fun vaultCaptureStore(store: BlobStore, vault: OpenVault): Pair<MetadataDb, List<LogEntry>>? {
    val key = VaultKeys.captureStoreKey(vault.vmk)
    return try {
        val id = VaultKeys.metadataBlobId(key)
        if (!store.fileOf(id).exists()) return null
        val db = MetadataDb.load(store, id, key)
        db to db.logBlobId?.let { TamperLog.scan(store, it, key).entries }.orEmpty()
    } finally {
        key.fill(0)
    }
}
