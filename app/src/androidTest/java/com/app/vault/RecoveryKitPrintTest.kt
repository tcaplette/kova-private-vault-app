package com.app.vault

import android.content.Context
import android.content.res.Configuration
import android.graphics.pdf.PdfRenderer
import android.os.LocaleList
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.SecureRandom
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * The printed kit — `recovery-practice` D6: one page on A4 and on US Letter in all nine
 * languages, nothing past the printable width, the owner and today's date on it, and neither
 * the app's name nor a cover identity's anywhere in its text.
 *
 * The layout is measured where it is decided ([RecoveryKitPrintAdapter.layout]); the page count
 * is read back from the PDF bytes [RecoveryKitPrintAdapter.render] produces.
 */
@RunWith(AndroidJUnit4::class)
class RecoveryKitPrintTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private val languages = listOf("en", "de", "es", "fr", "ja", "ko", "pt-BR", "zh-CN", "zh-TW")

    /** Points at 72 dpi — what `onLayout` derives from the media size. */
    private val papers = mapOf("A4" to (595 to 842), "Letter" to (612 to 792))

    private val words: List<String> = Mnemonic.fromResources(ctx)
        .encode(ByteArray(Mnemonic.ENTROPY_BYTES).also { SecureRandom().nextBytes(it) })

    private fun localized(tag: String): Context = ctx.createConfigurationContext(
        Configuration(ctx.resources.configuration).apply { setLocales(LocaleList(Locale.forLanguageTag(tag))) },
    )

    private fun pages(pdf: ByteArray): Int {
        val file = File(ctx.cacheDir, "kit-${System.nanoTime()}.pdf").apply { writeBytes(pdf) }
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { return it.pageCount }
            }
        } finally {
            file.delete()
        }
    }

    /** The names a page must never carry: the app's, and every cover identity's. */
    private fun forbidden(context: Context): List<String> =
        listOf(context.getString(R.string.app_name)) +
            LauncherIdentity.entries.filter { it.cover != null }.map { context.getString(it.label) }

    private fun check(tag: String, owner: KitOwner) {
        val context = localized(tag)
        val now = System.currentTimeMillis()
        papers.forEach { (paper, size) ->
            val (w, h) = size
            val where = "$tag on $paper for $owner"
            val page = RecoveryKitPrintAdapter.layout(context, words, owner, now, w, h)

            assertTrue("$where: runs to ${page.bottom} of ${page.printableBottom}", page.bottom <= page.printableBottom)
            assertTrue("$where: the body never goes under 9 pt (${page.bodySize})", page.bodySize >= 9f)
            page.lines.forEach { (line, w) -> assertTrue("$where: \"$line\" is $w pt in ${page.printableWidth}", w <= page.printableWidth) }
            assertEquals("$where: one page", 1, pages(RecoveryKitPrintAdapter.render(context, words, owner, now, w, h)))

            val text = page.text
            val date = DateFormat.getDateInstance(DateFormat.LONG, Locale.forLanguageTag(tag)).format(Date(now))
            assertTrue("$where: today's date ($date)", text.contains(date))
            words.forEach { assertTrue("$where: the word $it", text.contains(it)) }
            when (owner) {
                is KitOwner.List -> {
                    assertTrue(where, text.contains(context.getString(R.string.kit_print_for_list)))
                    assertTrue(where, text.contains(context.getString(R.string.unlock_forgot)))
                    assertTrue(where, text.contains(context.getString(R.string.kit_print_backups)))
                }
                is KitOwner.Vault -> {
                    assertTrue(where, text.contains(context.getString(R.string.kit_print_for_vault, owner.name)))
                    assertTrue(where, text.contains(context.getString(R.string.drawer_recover_vault)))
                    assertFalse(where, text.contains(context.getString(R.string.kit_print_backups)))
                }
            }
            forbidden(context).forEach { name ->
                assertFalse(
                    "$where names \"$name\"",
                    Regex("""(?<!\p{L})${Regex.escape(name)}(?!\p{L})""", RegexOption.IGNORE_CASE).containsMatchIn(text),
                )
            }
            Log.i("RecoveryKitPrint", "$where: bottom ${page.bottom}/${page.printableBottom}, body ${page.bodySize} pt")
        }
    }

    @Test fun theListKitFitsOnePageInEveryLanguage() {
        languages.forEach { check(it, KitOwner.List()) }
    }

    @Test fun aVaultsKitFitsOnePageInEveryLanguage() {
        languages.forEach { check(it, KitOwner.Vault("Travel")) }
    }

    @Test fun theDecoysKitIsNamedForTheDecoy() {
        val page = RecoveryKitPrintAdapter.layout(localized("en"), words, KitOwner.List("Holidays"), 0L, 595, 842)
        assertTrue(page.text.contains("For: Holidays"))
        assertTrue("its steps are a list's", page.text.contains(localized("en").getString(R.string.unlock_forgot)))
    }
}
