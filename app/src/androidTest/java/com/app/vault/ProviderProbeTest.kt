package com.app.vault

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.security.SecureRandom

/**
 * Does this provider actually do what SAF says it does? — `cloud-mirror` task 1.
 *
 * **This test cannot be run unattended, and that is not a defect.** A SAF tree URI
 * is obtainable only by a human tapping through the system document picker; there
 * is no API, no shell command and no test hook that grants one. So the probe reads
 * the destination the app already stored and skips itself when there is none.
 *
 * **To run it against a provider:**
 *  1. Sign in to that provider's app on the device.
 *  2. Open the vault, go to Settings → Backup, and choose a folder in it.
 *  3. `./gradlew :app:connectedPlayDebugAndroidTest \
 *        -Pandroid.testInstrumentationRunnerArguments.class=com.app.vault.ProviderProbeTest`
 *  4. Record the result in `OUTLINE.md` under `cloud-mirror`, then repeat for the
 *     next provider.
 *
 * **What it is looking for.** Three operations, and one of them is the whole
 * reason this exists. `createDocument` and `deleteDocument` are the obvious ones.
 * The third is **mode `"wt"` truncating**: the index blob keeps its name while its
 * ciphertext changes and shrinks, so a provider that opens `"wt"` without
 * truncating leaves the tail of the previous index behind the new one. That file
 * still authenticates for its real length, so nothing downstream notices and the
 * mirror rots silently. A provider that fails this one is not shipped with a
 * warning; it is not shipped.
 */
@RunWith(AndroidJUnit4::class)
class ProviderProbeTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val rng = SecureRandom()

    private companion object {
        const val TAG = "VaultProviderProbe"
        const val BIG = 1 shl 20      // 1 MiB
        const val SMALL = 1 shl 19    // 512 kB
    }

    @Test
    fun theChosenProviderCreatesTruncatesAndDeletes() {
        // The folder a human picked through the system picker, as the app recorded it
        // — `settings-separation` moved a *vault's* destination into its encrypted
        // index, which this probe has no key for, so it reads the persisted grant
        // instead. Same folder, and the only one an unlocked device can name.
        val tree = ctx.contentResolver.persistedUriPermissions
            .firstOrNull { it.isWritePermission }?.uri
        // Skipped, not failed: an unattended run has no folder and this is not a
        // regression when it does not execute. See the KDoc for how to run it.
        assumeNotNull(tree)

        val target = SafDestination(ctx, tree!!)
        val name = "probe-" + ByteArray(8).also(rng::nextBytes)
            .joinToString("") { "%02x".format(it) }

        Log.i(TAG, "probing $tree")

        // 1. create
        val entry = target.create(name, "application/octet-stream", null)
        assertNotNull("createDocument returned nothing", entry)

        try {
            // 2. write 1 MiB
            val big = ByteArray(BIG).also(rng::nextBytes)
            target.openOutput(entry).use { it.write(big) }
            assertEquals(
                "the provider did not store what was written",
                BIG.toLong(), target.names()[entry.name]?.size
            )

            // 3. the listing has to report a name, a size and a time, or the diff
            //    has nothing to compare and rewrites the whole store every run.
            val listed = target.names()[entry.name]
            assertNotNull("the file is not in the child listing", listed)
            assertTrue("no size reported — every run would rewrite everything", listed!!.size >= 0)
            assertTrue("no modification time reported", listed.modified >= 0)

            // 4. **the one that matters**: overwrite shorter, through mode "wt"
            val small = ByteArray(SMALL).also(rng::nextBytes)
            target.overwrite(listed.uri).use { it.write(small) }

            val after = target.names()[entry.name]
            assertNotNull("the file vanished on overwrite", after)
            assertEquals(
                "mode \"wt\" did not truncate: this provider leaves the tail of the " +
                    "previous index behind a shorter one, and the mirror rots silently",
                SMALL.toLong(), after!!.size
            )

            // 5. and the bytes are the new ones, not the old ones
            val read = target.openInput(ExportEntry(after.uri, entry.name)).use { it.readBytes() }
            assertEquals("read back the wrong length", SMALL, read.size)
            assertTrue("read back the wrong bytes", read.contentEquals(small))

            Log.i(TAG, "PASS: create, list with size and time, wt truncation, read back")
        } finally {
            // 6. delete
            target.discard(entry)
            assertTrue(
                "deleteDocument left the file in place — deletions would not propagate",
                entry.name !in target.names()
            )
            Log.i(TAG, "PASS: delete")
        }
    }
}
