package com.app.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What P2-22 must not become, checked against the source rather than argued about.
 *
 * Three claims live here. **The identity never reaches a log** — the chosen identity
 * in logcat is the one fact the feature exists to withhold, and it is the easiest
 * thing in the change to add "just for debugging". **The enum and the manifest agree
 * on the words** — the launcher reads `@string/identity_*` and everything in-app
 * reads `LauncherIdentity.label`, so two sources exist and this is what stops them
 * drifting. **The application label is unchanged** — P2-22 deliberately does not
 * touch it, and a later hand reaching for it would be silently converting a
 * documented boundary into a build-time lie told to every install.
 *
 * JVM scope: it reads source, so no emulator is involved.
 */
class LauncherIdentityScanTest {

    private fun repoRoot(): File {
        var dir: File? = File(".").canonicalFile
        while (dir != null) {
            if (File(dir, "app/src/main/AndroidManifest.xml").isFile) return dir
            dir = dir.parentFile
        }
        throw IllegalStateException("repo root not found")
    }

    private fun manifest() = File(repoRoot(), "app/src/main/AndroidManifest.xml").readText()

    private fun strings() = File(repoRoot(), "app/src/main/res/values/strings.xml").readText()

    private fun identitySource() =
        File(repoRoot(), "app/src/main/java/com/app/vault/LauncherIdentity.kt").readText()

    private fun sourceFiles(): List<File> =
        File(repoRoot(), "app/src/main/java/com/app/vault")
            .walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun nothingLogsTheChosenIdentity() {
        val source = identitySource()
        assertTrue("LauncherIdentity imports android.util.Log", !source.contains("android.util.Log"))
        assertTrue("LauncherIdentity logs", !Regex("Log[.][dviwe]\\(").containsMatchIn(source))

        // And no other file logs one either. A `Log` line anywhere that takes an
        // identity puts the answer in logcat just as well as one in this file does.
        sourceFiles().forEach { file ->
            Regex("Log[.][dviwe]\\([^)]*").findAll(file.readText()).forEach { call ->
                assertTrue(
                    "${file.name} logs an identity: ${call.value.take(80)}",
                    !call.value.contains("dentity"),
                )
            }
        }
    }

    /**
     * The launcher reads the manifest's `@string/identity_*`; the drawer, the
     * chooser and the recents description read `LauncherIdentity.label`. One resource
     * each, translated once (`app-languages` D5), so this binds them to it.
     */
    @Test
    fun theManifestLabelsAreTheEnumLabels() {
        val keys = LauncherIdentity.entries.map { "identity_" + it.name.lowercase() }
        val values = Regex("<string name=\"(identity_\\w+)\">([^<]+)</string>")
            .findAll(strings())
            .associate { it.groupValues[1] to it.groupValues[2] }
            .filterKeys { it in keys }
        assertEquals(
            "a launcher identity has no string resource",
            LauncherIdentity.entries.size,
            values.size,
        )

        LauncherIdentity.entries.forEach { identity ->
            val key = "identity_" + identity.name.lowercase()
            assertEquals("$key is not the enum's label", R.string::class.java.getField(key).getInt(null), identity.label)
            assertTrue(
                "the ${identity.name} alias does not use @string/$key",
                manifest().contains("android:label=\"@string/$key\""),
            )
        }
    }

    @Test
    fun everyIdentityHasAnAliasInTheManifest() {
        LauncherIdentity.entries.forEach {
            assertTrue(
                "no alias declared for ${it.name}",
                manifest().contains("android:name=\"${it.aliasSuffix}\""),
            )
        }
        assertEquals(
            "an alias is declared that no identity names",
            LauncherIdentity.entries.size,
            Regex("<activity-alias").findAll(manifest()).count(),
        )
    }

    /**
     * **The application label is not the identity, and must not become one.**
     *
     * It is read by notifications, Settings, and every runtime permission dialog, and
     * no alias label reaches it — so it is the one string that speaks for the app in
     * places a cover cannot dress. It must therefore be neither "Vault" nor any
     * identity's name: "Vault" unmasked the covers outright, and a label matching one
     * identity would contradict the other three. It is "Kova", the owner's name for the
     * app, which no identity wears.
     *
     * Asserted as a shape rather than as a literal. Pinning the exact word would make
     * this test the place a label change is argued with, which is not what a scan test
     * is for; what must not drift is that the label is owned by no identity on screen.
     */
    @Test
    fun theApplicationLabelNamesNoIdentity() {
        assertTrue(
            "the application label is no longer @string/app_name",
            manifest().contains("android:label=\"@string/app_name\""),
        )
        val label = Regex("<string name=\"app_name\">([^<]+)</string>")
            .find(strings())?.groupValues?.get(1)
        assertNotNull("app_name is not declared", label)
        assertTrue("app_name is empty", !label.isNullOrBlank())
        LauncherIdentity.entries.forEach { identity ->
            assertNotEquals(
                "the application label is ${TestWords.text(identity.label)}, which is an identity's " +
                    "own name — it would contradict every other identity, and reach " +
                    "Settings and the permission dialogs where no alias label goes",
                TestWords.text(identity.label).lowercase(),
                label!!.lowercase(),
            )
        }
        assertNotEquals(
            "the application label is the product's name, which is what put " +
                "\"Allow Vault to record audio?\" over a voice recorder",
            "vault",
            label!!.lowercase(),
        )
    }

    /**
     * **The application icon is not an identity's either** — `cover-fingerprint-pattern`,
     * the icon twin of the label above. Android draws it where no alias icon reaches: the
     * biometric prompt's header on Android 15 and later, and Settings → Apps. Left as the
     * vault's icon, it put the vault's glyph over a music player the moment the
     * fingerprint sheet appeared.
     */
    @Test
    fun theApplicationIconIsNoIdentitysIcon() {
        val application = Regex("<application(.*?)>", RegexOption.DOT_MATCHES_ALL)
            .find(manifest())?.groupValues?.get(1)
        assertNotNull("the manifest has no <application>", application)
        assertTrue(
            "the application icon is no longer @mipmap/ic_launcher_toolbox",
            application!!.contains("android:icon=\"@mipmap/ic_launcher_toolbox\""),
        )
        val aliasIcons = Regex("<activity-alias(.*?)</activity-alias>", RegexOption.DOT_MATCHES_ALL)
            .findAll(manifest())
            .mapNotNull { Regex("android:icon=\"@mipmap/(\\w+)\"").find(it.groupValues[1])?.groupValues?.get(1) }
            .toList()
        assertEquals("an alias lost its icon", LauncherIdentity.entries.size, aliasIcons.size)
        assertTrue("an alias uses the application icon", "ic_launcher_toolbox" !in aliasIcons)
        LauncherIdentity.entries.forEach {
            assertNotEquals(
                "the application icon is ${it.name}'s — it would put that identity's glyph " +
                    "on every surface no alias reaches",
                R.mipmap.ic_launcher_toolbox,
                it.iconRes,
            )
        }
    }

    /**
     * No alias may grow a second way in. The aliases exist to carry a name and an
     * icon; a filter on one is the entry surface `Do not build` rejects, reached
     * through the door P2-22 opened.
     */
    @Test
    fun noAliasDeclaresAnythingButTheLauncherFilter() {
        Regex("<activity-alias(.*?)</activity-alias>", RegexOption.DOT_MATCHES_ALL)
            .findAll(manifest())
            .forEach { alias ->
                val body = alias.groupValues[1]
                listOf("SEND", "VIEW", "GET_CONTENT", "PICK", "mimeType", "data android:").forEach {
                    assertTrue("an alias declares $it", !body.contains(it))
                }
            }
    }

    /**
     * The switch order is the requirement, so it is checked rather than trusted:
     * one place writes component state, and it passes `DONT_KILL_APP`.
     *
     * Counted against the **code**, with comments stripped. The file argues for the
     * flag in prose as well as passing it, and a test that counted both would pass a
     * version that only argued.
     */
    @Test
    fun everyComponentWriteAsksNotToBeKilled() {
        val code = identitySource()
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("//[^\n]*"), "")
        val writes = Regex("setComponentEnabledSetting\\(").findAll(code).count()
        assertEquals("more than one place writes component state", 1, writes)
        assertEquals(
            "a component write does not pass DONT_KILL_APP",
            writes,
            Regex("DONT_KILL_APP").findAll(code).count(),
        )
    }
    // --- identity-switch-done-step -----------------------------------------------

    /**
     * Source with its comment lines dropped. By line and not by block-comment span: a
     * MIME-type string literal ending in a slash and a star would open a span that eats
     * real code.
     */
    private fun code(source: String) = source.lines()
        .filterNot { line -> line.trimStart().let { it.startsWith("//") || it.startsWith("/*") || it.startsWith("*") } }
        .joinToString("\n")

    private fun mainSource() =
        File(repoRoot(), "app/src/main/java/com/app/vault/MainActivity.kt").readText()

    private fun applicationSource() =
        File(repoRoot(), "app/src/main/java/com/app/vault/VaultApplication.kt").readText()

    /**
     * What is wrong with a `MainActivity` source's start path: it must call `admit`,
     * and nothing in it may call `reconcile`. A start came through one of the aliases,
     * and disabling it finishes the activity the start just created.
     */
    private fun startPathFaults(source: String): List<String> {
        val code = code(source)
        return listOfNotNull(
            "MainActivity does not call LauncherIdentity.admit(this)"
                .takeIf { "LauncherIdentity.admit(this)" !in code },
            "MainActivity calls LauncherIdentity.reconcile"
                .takeIf { "LauncherIdentity.reconcile(" in code },
        )
    }

    private val departureGuards =
        listOf("started == 0", "!awaitingResult", "!activity.isChangingConfigurations")

    /**
     * What is wrong with a `VaultApplication` source's departure: `reconcile` is called
     * from `onActivityStopped`, and every call sits directly inside an `if` holding all
     * three guards — an activity still visible, a picker outstanding, a recreate.
     */
    private fun departureFaults(source: String): List<String> {
        val code = code(source)
        val calls = Regex("LauncherIdentity\\.reconcile\\(").findAll(code).toList()
        if (calls.isEmpty()) return listOf("VaultApplication never reconciles on departure")
        // The one with a body — `FlagSecure`'s `= Unit` override comes first in the file.
        val stopped = Regex("fun onActivityStopped\\([^)]*\\)\\s*\\{").find(code)?.range?.first ?: -1
        val stoppedEnd = code.indexOf("override fun", stopped + 1).let { if (it < 0) code.length else it }
        val guarded = Regex("if \\(([^{}]*)\\)\\s*\\{\\s*LauncherIdentity\\.reconcile\\(").findAll(code).toList()
        return buildList {
            if (calls.any { stopped < 0 || it.range.first !in stopped..stoppedEnd }) {
                add("a reconcile is called outside onActivityStopped")
            }
            if (guarded.size != calls.size) add("a reconcile is called outside a guard")
            guarded.forEach { m ->
                departureGuards.filter { it !in m.groupValues[1] }.forEach { add("the departure reconcile lacks `$it`") }
            }
        }
    }

    @Test
    fun theStartPathAdmitsAndNeverReconciles() {
        assertEquals(emptyList<String>(), startPathFaults(mainSource()))
    }

    @Test
    fun theDepartureReconcilesOnlyUnderAllThreeGuards() {
        assertEquals(emptyList<String>(), departureFaults(applicationSource()))
    }

    /** The two scans bite: each fault they exist to catch is caught. */
    @Test
    fun theSwitchScansBite() {
        assertTrue(
            startPathFaults("LauncherIdentity.admit(this)\nLauncherIdentity.reconcile(this)").isNotEmpty(),
        )
        assertTrue(startPathFaults("// LauncherIdentity.admit(this)").isNotEmpty())
        val stopped = { condition: String ->
            """
            override fun onActivityStopped(activity: Activity) {
                onBackground()
                if ($condition) {
                    LauncherIdentity.reconcile(activity.applicationContext)
                }
            }
            override fun onActivityDestroyed(activity: Activity) = Unit
            """
        }
        assertEquals(
            emptyList<String>(),
            departureFaults(stopped("started == 0 && !awaitingResult && !activity.isChangingConfigurations")),
        )
        departureGuards.forEach { guard ->
            val without = departureGuards.filter { it != guard }.joinToString(" && ")
            assertTrue("a missing `$guard` passed", departureFaults(stopped(without)).isNotEmpty())
        }
        assertTrue(
            departureFaults(
                "override fun onActivityStopped(activity: Activity) {\n" +
                    "LauncherIdentity.reconcile(activity.applicationContext)\n}",
            ).isNotEmpty(),
        )
        assertTrue(departureFaults("fun onBackground() = Unit").isNotEmpty())
    }
}
