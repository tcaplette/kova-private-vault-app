package com.app.vault

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Every persisted single-file grant is a backup file — `backup-to-cloud-apps` D8.
 *
 * Panic reaches backups without a key by walking the grants the install holds, and it
 * deletes a single-document grant's file whole. That is safe only while the backup file
 * is the one place the app persists such a grant. So the check is where
 * `takePersistableUriPermission` is called: the export folder (a tree), the mirror folder
 * (a tree), the backup file saved, and the backup file restored from — and nowhere else.
 */
class BackupGrantScanTest {

    private val allowed = setOf("pickExportTree", "mirrorTreePicked", "createBackupFile", "openBackupFile")

    private fun sources(): List<File> {
        var dir: File? = File(".").canonicalFile
        val rel = "src/main/java/com/app/vault"
        while (dir != null) {
            listOf(File(dir, rel), File(dir, "app/$rel")).firstOrNull { it.isDirectory }
                ?.let { root -> return root.walkTopDown().filter { it.extension == "kt" }.toList() }
            dir = dir.parentFile
        }
        throw AssertionError("could not locate the main sources from ${File(".").absolutePath}")
    }

    /**
     * The member each call sits in: the nearest `private val x` or `private fun x` above
     * it. Private, so a nested `override fun createIntent` inside a launcher's contract is
     * not mistaken for the launcher.
     */
    private fun callers(code: String): List<String> {
        val decl = Regex("""private\s+(?:val|fun)\s+(\w+)""")
        return Regex("""takePersistableUriPermission\(""").findAll(code).map { call ->
            decl.findAll(code.substring(0, call.range.first)).lastOrNull()?.groupValues?.get(1) ?: "?"
        }.toList()
    }

    @Test
    fun onlyTheBackupFileTakesASingleFileGrant() {
        val found = sources().flatMap { callers(it.readText()) }
        assertEquals(
            "a persisted grant outside these three would be swept by panic as a backup file",
            allowed, found.toSet(),
        )
        assertEquals("each place takes one grant", allowed.size, found.size)
    }

    @Test
    fun theScanWouldCatchAFourth() {
        val code = """
            private val pickExportTree = x { takePersistableUriPermission(a) }
            private fun somethingElse() { takePersistableUriPermission(b) }
        """
        assertEquals(listOf("pickExportTree", "somethingElse"), callers(code))
    }
}
