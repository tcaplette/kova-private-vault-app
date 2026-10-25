package com.app.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What P2-23 must not become, checked against the source rather than argued about.
 *
 * The light scheme was cheap because one rule had held perfectly for the whole
 * project: **no colour literal exists outside the theme package**. Every one of the
 * 188 `VaultColors.X` call sites started following the scheme without being edited,
 * and that only worked because none of them had a colour of its own. The rule is now
 * load-bearing rather than tidy, so it is enforced here.
 *
 * JVM scope: it reads source, so no emulator is involved.
 */
class ThemeScanTest {

    private fun repoRoot(): File {
        var dir: File? = File(".").canonicalFile
        while (dir != null) {
            if (File(dir, "app/src/main/AndroidManifest.xml").isFile) return dir
            dir = dir.parentFile
        }
        throw IllegalStateException("repo root not found")
    }

    private fun sourceFiles(): List<File> =
        File(repoRoot(), "app/src/main/java/com/app/vault")
            .walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /**
     * Source with strings and comments removed, in that order.
     *
     * **Strings first, and that ordering is load-bearing.** `MainActivity` passes the
     * SAF any-type wildcard to `openDocuments.launch` — a five-character string whose
     * middle three are a block-comment opener. A stripper that takes comments out
     * first treats it as one and swallows the next thousand lines. Every check in
     * this file scans for something's *absence*, so being handed two-thirds of a file
     * would make them pass by seeing nothing. `ScreenChromeTest`'s scanner removes
     * strings first for the same reason; this one is not clever, it is merely in the
     * right order.
     */
    private fun code(f: File): String = f.readText()
        .replace(Regex("\"\"\".*?\"\"\"", RegexOption.DOT_MATCHES_ALL), "\"\"")
        .replace(Regex("\"(\\\\.|[^\"\\\\])*\""), "\"\"")
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\n]*"), "")

    /**
     * The rule the whole task rests on. A screen holding its own `Color(0xFF…)`
     * keeps that colour when the scheme changes — the exact failure P2-23's coverage
     * test hunts for on a device, caught here for free before it gets there.
     */
    @Test
    fun noColourLiteralExistsOutsideTheThemePackage() {
        val offenders = sourceFiles()
            .filter { "ui/theme" !in it.path.replace(File.separatorChar, '/') }
            .filter { Regex("""Color\(0x""").containsMatchIn(code(it)) }
            .map { it.name }
        assertEquals(
            "A colour outside the theme package does not follow the scheme. Found in: $offenders",
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * **The colour vocabulary did not change with P2-23**, and this is what says so.
     *
     * Every name `VaultColors` exposed before the light scheme still exists, so no
     * call site was quietly dropped or renamed while 188 of them were going
     * unexamined. The two viewer roles are additions and are listed separately.
     */
    @Test
    fun vaultColorsStillExposesEveryNameItDidBeforeTheLightScheme() {
        val before = listOf(
            "Surface", "SurfaceContainerLow", "SurfaceContainer", "SurfaceContainerHigh",
            "ViewerGround", "OnSurface", "OnSurfaceVariant", "Primary", "OnPrimary",
            "Secondary", "OnSecondary", "Error", "OnError", "Outline", "OutlineVariant", "Scrim",
        )
        val added = listOf("OnViewerGround", "OnViewerGroundVariant")

        val source = File(
            repoRoot(), "app/src/main/java/com/app/vault/ui/theme/Color.kt",
        ).readText()

        (before + added).forEach {
            assertTrue("VaultColors.$it is missing", Regex("""val $it:""").containsMatchIn(source))
        }
    }

    /**
     * Every property is a theme read. A plain `val` here would be a colour captured
     * once at class-init and frozen across a scheme change — the one bug this file's
     * shape exists to make impossible, arriving by omission.
     */
    @Test
    fun everyVaultColorsPropertyReadsTheActivePalette() {
        val source = code(File(
            repoRoot(), "app/src/main/java/com/app/vault/ui/theme/Color.kt",
        ))
        val properties = Regex("""val (\w+): Color""").findAll(source).count()
        val reads = Regex("""LocalVaultPalette\.current\.""").findAll(source).count()
        assertEquals("a VaultColors property does not read the active palette", properties, reads)
        assertTrue("VaultColors declares no properties", properties >= 18)
    }

    /**
     * The viewer's ground does not follow the scheme, so only what draws **on that
     * ground** may use the pinned foregrounds. A screen reaching for one is drawing
     * over something it should not be, and would be pinning itself to dark by
     * accident.
     *
     * **Four files, and the list is the whole list.** `VaultViewer` is the ground;
     * `EditorScreen` puts the same photograph on the same black, because an editor
     * that changed the surface under an image would change how every adjustment reads
     * (`viewer-chrome-and-editor`); `ViewerMenu` is the trigger that sits on the
     * viewer's rows — its *menu* is a Material surface and correctly uses the
     * scheme's own foreground, which is why it is a file of its own rather than part
     * of the viewer.
     *
     * `CameraScreen` joined at `camera-conventions`, and it is the clearest case of
     * the four: a viewfinder is whatever the lens is pointed at, which follows no
     * scheme at all. Chrome over it that used `onSurface` would be dark grey on a
     * night shot in the light palette — the same failure this rule was written
     * against, arriving through a surface nobody had built yet when it was written.
     *
     * `SlideshowScreen` joined at `destination-settings-and-slideshow`: the viewer draws
     * it over itself, on the same black, with the same photographs under its controls.
     */
    @Test
    fun onlyTheViewerUsesThePinnedViewerForegrounds() {
        val onTheViewersGround =
            setOf("VaultViewer.kt", "EditorScreen.kt", "ViewerMenu.kt", "CameraScreen.kt", "SlideshowScreen.kt")
        val offenders = sourceFiles()
            .filter { it.name !in onTheViewersGround && it.name != "Color.kt" }
            .filter { code(it).contains("VaultColors.OnViewerGround") }
            .map { it.name }
        assertEquals(
            "The pinned viewer foregrounds belong to the viewer. Found in: $offenders",
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * And the viewer uses them rather than the scheme's, over its black ground.
     *
     * `EditorScreen` is held to the same rule for the same reason, and both are read
     * as source: the menu that *does* use the scheme's foregrounds is `ViewerMenu`,
     * where they are correct because it draws on a Material container of its own.
     */
    @Test
    fun theViewerDrawsNoSchemeForegroundOverItsBlackGround() {
        val editor = code(File(
            repoRoot(), "app/src/main/java/com/app/vault/ui/EditorScreen.kt",
        ))
        assertTrue(
            "EditorScreen draws VaultColors.OnSurface over the viewer's ground",
            !editor.contains("VaultColors.OnSurface"),
        )
        assertTrue(
            "EditorScreen must use the pinned viewer foregrounds",
            editor.contains("VaultColors.OnViewerGround"),
        )

        val viewer = code(File(
            repoRoot(), "app/src/main/java/com/app/vault/ui/VaultViewer.kt",
        ))
        assertTrue(
            "VaultViewer draws VaultColors.OnSurface over the viewer's ground; in the light " +
                "scheme that is near-black on black",
            !viewer.contains("VaultColors.OnSurface"),
        )
        assertTrue(
            "VaultViewer must use the pinned viewer foregrounds",
            viewer.contains("VaultColors.OnViewerGround"),
        )
    }

    @Test
    fun nothingLogsTheAppearanceChoice() {
        sourceFiles().forEach { file ->
            Regex("""\bLog\.[dviwe]\([^)]*""").findAll(code(file)).forEach { call ->
                assertTrue(
                    "${file.name} logs the appearance: ${call.value.take(80)}",
                    !call.value.contains("ppearance") && !call.value.contains("ThemeChoice"),
                )
            }
        }
    }

    /**
     * The window theme is chosen before `super.onCreate`, because `windowBackground`
     * is resolved before app code runs. After `super` it is too late and the cold
     * start flashes the other scheme's ground — the exact defect `themes.xml` exists
     * to prevent, reintroduced by ordering.
     */
    @Test
    fun theWindowThemeIsAppliedBeforeSuperOnCreate() {
        // Raw source, not stripped: both forms below carry parentheses and an
        // argument, and the KDoc references to them do not — so there is nothing
        // here for a comment stripper to disambiguate, and one less thing to trust.
        val source = File(
            repoRoot(), "app/src/main/java/com/app/vault/MainActivity.kt",
        ).readText()
        val apply = source.indexOf("applyWindowTheme()")
        val superCall = source.indexOf("super.onCreate(savedInstanceState)")
        assertTrue("MainActivity never applies a window theme", apply >= 0)
        assertTrue("MainActivity never calls super.onCreate", superCall >= 0)
        assertTrue(
            "applyWindowTheme() must run before super.onCreate() or the window has already been " +
                "created with the wrong ground",
            apply < superCall,
        )
    }

    /**
     * `androidx.appcompat` is the usual way to switch window themes and is not on
     * this project's classpath. A dependency plus an I1 clearance run, to set a
     * window background, is not a trade P2-23 makes.
     */
    @Test
    fun noAppCompatDependencyWasAdded() {
        val gradle = File(repoRoot(), "app/build.gradle.kts").readText()
        assertTrue(
            "androidx.appcompat was added; P2-23 picks the window theme with setTheme instead",
            !gradle.contains("appcompat"),
        )
        sourceFiles().forEach {
            assertTrue(
                "${it.name} imports AppCompat",
                !code(it).contains("androidx.appcompat"),
            )
        }
    }

    /**
     * ***The appearance chooser screen is gone, and no route to one survives.***
     *
     * The setting is three segments in the drawer now. A leftover state flag, `when`
     * branch or navigation callback would be a screen nobody can reach and nobody
     * would notice was dead — the failure mode a deletion has when it stops one
     * reference short.
     */
    @Test
    fun noAppearanceChooserScreenSurvives() {
        sourceFiles().forEach { file ->
            val text = code(file)
            listOf("AppearanceScreen", "appearanceScreen").forEach {
                assertTrue(
                    "${file.name} still names $it — the chooser screen was deleted",
                    !text.contains(it),
                )
            }
        }
        assertTrue(
            "AppearanceScreen.kt still exists",
            !File(repoRoot(), "app/src/main/java/com/app/vault/ui/AppearanceScreen.kt").exists(),
        )
        // And the control that replaced it is really there, so this test cannot pass
        // by the whole feature having been removed.
        assertTrue(
            "AppearanceControl.kt is missing",
            File(repoRoot(), "app/src/main/java/com/app/vault/ui/AppearanceControl.kt").isFile,
        )
    }
}
