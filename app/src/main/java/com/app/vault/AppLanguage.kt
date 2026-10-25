package com.app.vault

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.annotation.StringRes
import java.util.Locale

/**
 * The app's language — `app-languages` D2. The one entry point: what is offered, what is
 * chosen, and making the choice take effect.
 *
 * **Android 13+** keeps the choice where the platform keeps it, `LocaleManager`, so system
 * Settings › Apps › Language reads and writes the same value and the platform applies it to
 * every context in the process.
 *
 * **Android 11–12** has no per-app language. The tag lives in [GridPreference] and [apply]
 * puts it on the resources by hand — called at process start, before the activity draws,
 * and after every configuration change, because each of those hands the framework's own
 * configuration back and would otherwise revert the app to the device's language.
 */
object AppLanguage {

    /** One offered language: its tag, its name in its own script, its name in the app's. */
    class Choice(val tag: String, val ownName: String, @StringRes val nameRes: Int) {
        val locale: Locale get() = Locale.forLanguageTag(tag)
    }

    /**
     * The nine, in the order the platform's own picker uses: Latin script alphabetically
     * by own name, then Japanese, Korean, Simplified and Traditional Chinese. Own names are
     * never translated — they are what someone who cannot read the screen looks for.
     */
    val supported = listOf(
        Choice("de", "Deutsch", R.string.language_german),
        Choice("en-US", "English", R.string.language_english),
        Choice("es", "Español", R.string.language_spanish),
        Choice("fr", "Français", R.string.language_french),
        Choice("pt-BR", "Português (Brasil)", R.string.language_portuguese_brazil),
        Choice("ja", "日本語", R.string.language_japanese),
        Choice("ko", "한국어", R.string.language_korean),
        Choice("zh-CN", "简体中文", R.string.language_chinese_simplified),
        Choice("zh-TW", "繁體中文", R.string.language_chinese_traditional),
    )

    /** The chosen language, or null when the app follows the device. */
    fun current(context: Context): Choice? {
        val locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java).applicationLocales
                .takeUnless { it.isEmpty }?.get(0)
        } else {
            GridPreference.language(context)?.let(Locale::forLanguageTag)
        } ?: return null
        return match(locale)
    }

    /** The device's languages, whatever the app is set to. */
    fun systemLocales(context: Context): LocaleList =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java).systemLocales
        } else {
            Resources.getSystem().configuration.locales
        }

    /**
     * The language the device's setting resolves to among the nine, by the same resource
     * resolution that draws the app — each `strings.xml` names its own tag — so System
     * default's line says what System default will actually show.
     */
    fun systemResolved(context: Context): Choice {
        val tag = localized(context, systemLocales(context))
            .getString(R.string.language_resource_tag)
        return supported.first { it.tag == tag }
    }

    /**
     * The Language sheet's Suggested group — `language-picker-redesign` D3: the nine that the
     * device's languages resolve to, in the device's order, each once. It describes the phone,
     * not the last choice, so rows do not move between openings.
     */
    fun suggested(context: Context): List<Choice> =
        systemLocales(context).let { list -> suggested((0 until list.size()).map(list::get)) }

    /** [suggested] for a given list of locales. Pure, so it is tested on the JVM. */
    fun suggested(locales: List<Locale>): List<Choice> = locales.mapNotNull(::match).distinct()

    /** [context]'s resources under [locales] — for text that must not be in the app's language. */
    fun localized(context: Context, locales: LocaleList): Resources =
        context.createConfigurationContext(
            Configuration(context.resources.configuration).apply { setLocales(locales) }
        ).resources

    /** Chooses [choice], or the device's language for null. Takes effect at once. */
    fun set(context: Context, choice: Choice?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // An empty list, never the resolved tag, so a later change of the device's
            // language is followed.
            context.getSystemService(LocaleManager::class.java).applicationLocales =
                choice?.let { LocaleList(it.locale) } ?: LocaleList.getEmptyLocaleList()
        } else {
            GridPreference.setLanguage(context, choice?.tag)
            apply(context)
        }
    }

    /**
     * Puts the chosen language on [context]'s resources and the application's, on 30–32.
     * On 33+ the platform does this itself, and here only the 12 → 13 hand-over runs: a
     * tag stored before an OS upgrade becomes the platform's, so the upgrade does not
     * forget the choice.
     */
    fun apply(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val stored = GridPreference.language(context) ?: return
            val manager = context.getSystemService(LocaleManager::class.java)
            if (manager.applicationLocales.isEmpty) {
                manager.applicationLocales = LocaleList.forLanguageTags(stored)
            }
            GridPreference.setLanguage(context, null)
            return
        }
        val locales = locales(context)
        Locale.setDefault(locales[0])
        listOf(context.resources, context.applicationContext.resources).distinct().forEach {
            @Suppress("DEPRECATION") // 30–32 only, where it is the mechanism there is.
            it.updateConfiguration(
                Configuration(it.configuration).apply { setLocales(locales) },
                it.displayMetrics,
            )
        }
    }

    /** The locales the app draws in on 30–32: the stored choice, or the device's. */
    fun locales(context: Context): LocaleList =
        GridPreference.language(context)?.let { LocaleList.forLanguageTags(it) }
            ?: Resources.getSystem().configuration.locales

    private fun match(locale: Locale): Choice? {
        supported.firstOrNull { it.locale == locale }?.let { return it }
        // Chinese splits by script, not by language: zh-Hant-TW and zh-HK are Traditional.
        if (locale.language == "zh") {
            val traditional = locale.script == "Hant" || locale.country in setOf("TW", "HK", "MO")
            return supported.first { it.tag == if (traditional) "zh-TW" else "zh-CN" }
        }
        return supported.firstOrNull { it.locale.language == locale.language }
    }
}
