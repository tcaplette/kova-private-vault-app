package com.app.vault

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** `language-picker-redesign` D3. The Suggested group: the device's languages among the nine. */
class AppLanguageSuggestedTest {

    private fun suggested(vararg tags: String) =
        AppLanguage.suggested(tags.map(Locale::forLanguageTag)).map { it.ownName }

    @Test
    fun anUnofferedLanguageIsSkipped() = assertEquals(listOf("Deutsch"), suggested("it-IT", "de-DE"))

    @Test
    fun theDevicesOrderIsKept() = assertEquals(listOf("繁體中文", "English"), suggested("zh-Hant-TW", "en-GB"))

    @Test
    fun nothingOfferedIsEmpty() = assertEquals(emptyList<String>(), suggested("it-IT"))

    @Test
    fun twoRegionsOfOneLanguageAppearOnce() = assertEquals(listOf("Deutsch"), suggested("de-AT", "de-DE"))
}
