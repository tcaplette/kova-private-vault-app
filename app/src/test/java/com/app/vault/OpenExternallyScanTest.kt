package com.app.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Nothing hands a **readable** item to another application — P2-18, amended by P4-2.
 *
 * ***The amendment, and why it is not this rule being dropped.*** P2-18 wrote this
 * as a blanket ban: no `ACTION_SEND`, no `FileProvider`, no `<provider>`, anywhere.
 * Its stated reasons are both about *plaintext* — I3's "an app receiving a file
 * needs a copy it can read, which is decrypted content at a path outside the
 * vault", and I9's "plaintext leaves through the export the user authorised with
 * their passphrase, and by no other door". At P2-18 those were the same rule,
 * because the only thing that could have left the app was plaintext.
 *
 * P4-2 creates the category that rule never contemplated: a **sealed container**
 * (`FORMAT.md` §12) under a key the receiving app does not have. A messenger
 * carrying one carries opaque bytes. I3 is untouched — the seal streams and no
 * decrypted copy is written — and I9 is untouched, because the container is
 * written inside `filesDir` and served from there.
 *
 * So the ban is narrowed to what its reasons actually protect, and the narrowing
 * is asserted rather than assumed: **plaintext still leaves by one door**, no
 * component receives anything, and the provider that exists serves exactly one
 * directory. A second provider, a second exposed path, or `ACTION_SEND` reaching
 * any file outside `shares/` fails these tests.
 *
 * **A scan and not a runtime assertion, deliberately.** An intent that is never
 * fired cannot be observed at runtime: a test that launches the app and sees no
 * chooser proves only that nobody tapped the control that would have opened one.
 * The absence has to be checked where absence is visible, which is the source and
 * the manifest.
 *
 * The rule is two invariants at once, and either alone would be enough. **I3**: an
 * app receiving a file needs a copy it can read, which is decrypted content at a
 * path outside the vault. **I9**: plaintext leaves through the export the user
 * authorised with their passphrase, and by no other door.
 *
 * This is the same shape as `ExportPathScanTest`, extended
 * to the thing P2-18 makes tempting: a document the app cannot render is exactly
 * the item someone will want to "just open in a PDF viewer".
 *
 * JVM scope: it reads source and the manifest, so no emulator is involved.
 */
class OpenExternallyScanTest {

    private fun repoRoot(): File {
        // `canonicalFile`, not `absoluteFile`: the latter keeps the trailing "."
        // so `parentFile` lands one directory too high, and the manifest is then
        // looked for at app/app/. Found by this test failing on its own first run.
        var dir: File? = File(".").canonicalFile
        while (dir != null) {
            if (File(dir, "app/src/main/java/com/app/vault").isDirectory) return dir
            if (File(dir, "src/main/java/com/app/vault").isDirectory) return dir.parentFile!!
            dir = dir.parentFile
        }
        throw AssertionError("could not locate the repository from ${File(".").absolutePath}")
    }

    private fun sources(): List<File> =
        File(repoRoot(), "app/src/main/java/com/app/vault")
            .walkTopDown().filter { it.extension == "kt" }.toList()

    private fun stripped(text: String): String = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//.*"""), "")

    private fun manifest(): String =
        File(repoRoot(), "app/src/main/AndroidManifest.xml").readText()

    /**
     * The intent actions that hand content to somebody else. `ACTION_SEND` is
     * already on `OUTLINE.md`'s "do not build" list as an *inbound* filter; this
     * checks the outbound direction, which that row does not cover.
     */
    /**
     * The share path, and the only files permitted to hand anything out — P4-2.
     *
     * `MainActivity` is on this list for its send function alone; everything else
     * in it stays under the ban. Keeping the list short is what makes it a rule
     * rather than an exemption: a third file appearing here is a decision, and it
     * has to be argued in this comment before the test will pass.
     */
    private val sharePath = setOf("ShareSend.kt", "MainActivity.kt")

    @Test
    fun nothingLaunchesAnItemIntoAnotherApp() {
        val forbidden = listOf(
            "ACTION_VIEW",
            "ACTION_SEND",
            "ACTION_SEND_MULTIPLE",
            "createChooser",
            "FileProvider",
            "getUriForFile",
        )
        val offenders = sources()
            .filter { it.name !in sharePath }
            .flatMap { file ->
                val text = stripped(file.readText())
                forbidden.filter { text.contains(it) }.map { "${file.name}: $it" }
            }
        assertTrue("an item can be handed to another app: $offenders", offenders.isEmpty())
    }

    /**
     * What leaves through the share door is a sealed container and nothing else.
     *
     * The send function may reach `FileProvider`, and the URI it builds must come
     * from a file the share path produced. A `getUriForFile` over an arbitrary
     * `File` would turn one narrow exemption into a general one.
     */
    @Test
    fun theShareDoorCarriesOnlySealedContainers() {
        val activity = stripped(
            File(repoRoot(), "app/src/main/java/com/app/vault/MainActivity.kt").readText()
        )

        // One hand-off, not a family of them.
        assertEquals(
            "the app builds exactly one content URI",
            1,
            Regex("""getUriForFile""").findAll(activity).count(),
        )
        assertEquals(
            "the app starts exactly one outbound share",
            1,
            Regex("""ACTION_SEND""").findAll(activity).count(),
        )
        assertTrue(
            "the sent file must be the sealed container the send flow produced",
            activity.contains("val file = shareFile ?: return"),
        )
        // Plaintext still leaves by one door only: the export path, which this
        // test's P2-18 half continues to protect for every other source file.
        assertTrue(
            "the share intent must not carry a decrypted item",
            !activity.contains("EXTRA_STREAM, exportUri") && !activity.contains("Unvaulter("),
        )
    }

    /**
     * A provider is a door that needs no intent from us at all, so the one that
     * exists is held to what it serves.
     *
     * **One provider, not exported, exposing only `shares/`** — P4-2. The inbound
     * half of P2-18's rule is unchanged and is the part that still says "no":
     * nothing in this app answers a `SEND` or a `VIEW` from anybody else.
     */
    @Test
    fun theManifestPublishesOneUnexportedProviderAndNoInboundFilter() {
        val text = manifest()

        assertEquals(
            "exactly one provider is declared",
            1,
            Regex("""<provider""").findAll(text).count(),
        )
        val provider = Regex("""<provider(.*?)</provider>""", RegexOption.DOT_MATCHES_ALL)
            .find(text)!!.groupValues[1]
        assertTrue(
            "the share provider must not be exported",
            provider.contains("""android:exported="false""""),
        )
        assertTrue(
            "the share provider must grant URI permissions per call",
            provider.contains("""android:grantUriPermissions="true""""),
        )

        // Receiving stays refused. P4-3 amends this line and nothing before it.
        assertTrue("an inbound SEND filter is declared", !text.contains("android.intent.action.SEND"))
        assertTrue("a VIEW filter is declared", !text.contains("android.intent.action.VIEW"))

        // The provider's reach is the whole of what makes it safe: one directory,
        // inside filesDir, holding only containers.
        val paths = File(repoRoot(), "app/src/main/res/xml/share_paths.xml").readText()
        assertEquals(
            "the provider exposes exactly one path",
            1,
            Regex("""<files-path""").findAll(paths).count(),
        )
        assertTrue("the exposed path is the share directory", paths.contains("""path="shares/""""))
        listOf("<external-path", "<cache-path", "<root-path", "<external-files-path")
            .forEach { assertTrue("the provider exposes $it", !paths.contains(it)) }
    }

    /**
     * Every exported component is a launcher alias, and nothing else is a way in.
     *
     * **Amended by P2-22, which is the reconsideration this test's previous
     * comment anticipated.** The old rule was a count — at most one
     * `android:exported="true"` — and P2-22 makes that count three. The rule it
     * becomes is stricter about the thing that actually matters: the export
     * *surface* is one activity behind `MAIN`/`LAUNCHER`, reached through aliases
     * that carry a name and an icon and nothing else. A count would pass a fourth
     * alias with a `SEND` filter on it; this does not.
     *
     * `MainActivity` itself is `exported="false"` and carries no filter, which is
     * what makes "exactly one enabled" a rule that can be asserted at all — see
     * `LauncherIdentity`. That half is a device assertion, not a source one: the
     * manifest cannot see runtime component state.
     */
    @Test
    fun everyExportedComponentIsALauncherAlias() {
        val text = manifest()

        val exported = Regex("""android:exported="true"""").findAll(text).count()
        val aliases = Regex("""<activity-alias""").findAll(text).count()
        assertEquals("an exported component that is not an alias", aliases, exported)
        assertTrue("no launcher alias is declared", aliases > 0)

        // The activity is not itself an entry point.
        // Self-closing, so it can carry no filter; its other attributes (`configChanges`) are
        // its own business, in any order.
        val activity = Regex(
            """<activity\s+android:name="\.MainActivity"([^>]*?)/>""",
        ).find(text)
        assertTrue("MainActivity is not declared exported=\"false\" and filterless", activity != null)
        val exportedAttr = Regex("""android:exported="(\w+)"""").find(activity!!.groupValues[1])
        assertEquals("MainActivity is exported", "false", exportedAttr?.groupValues?.get(1))

        // Every alias is a launcher entry on MainActivity and carries no other
        // filter. One MAIN and one LAUNCHER per alias, and no other action at all.
        Regex("""<activity-alias(.*?)</activity-alias>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(text)
            .forEach { alias ->
                val body = alias.groupValues[1]
                assertTrue(
                    "an alias does not target MainActivity",
                    body.contains("""android:targetActivity=".MainActivity""""),
                )
                assertTrue(
                    "an alias is not exported",
                    body.contains("""android:exported="true""""),
                )
                assertEquals(
                    "an alias declares more than one intent filter",
                    1,
                    Regex("""<intent-filter>""").findAll(body).count(),
                )
                assertEquals(
                    "an alias declares an action other than MAIN",
                    1,
                    Regex("""<action android:name="android.intent.action.MAIN" />""")
                        .findAll(body).count(),
                )
                assertEquals(
                    "an alias declares an action beyond MAIN",
                    1,
                    Regex("""<action """).findAll(body).count(),
                )
                assertTrue(
                    "an alias declares a category other than LAUNCHER",
                    Regex("""<category """).findAll(body).count() == 1 &&
                        body.contains("android.intent.category.LAUNCHER"),
                )
            }
    }
}
