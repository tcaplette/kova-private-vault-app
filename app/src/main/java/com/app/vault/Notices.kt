package com.app.vault

import android.content.Context
import org.json.JSONObject

/**
 * The third-party notices — `third-party-notices`.
 *
 * Read from `res/raw/aboutlibraries.json`, which `generate<Variant>Notices` writes at build time
 * from the variant's resolved runtime classpath plus the manual entries in `aboutlibraries/`. The
 * build fails on a library without an accepted licence, so every entry read here has one, with its
 * text. Parsed with `org.json`, which ships in the platform: the notices add no library to the APK.
 */
object Notices {

    class Licence(val name: String, val url: String?, val text: String)

    class Component(
        /** `group:artifact`, or the manual entry's id. Stable, so it keys the list and the open detail. */
        val id: String,
        val name: String,
        val version: String,
        /** `group:artifact:version` for a resolved library; null for a component with no POM. */
        val coordinate: String?,
        val website: String?,
        val licences: List<Licence>,
    )

    /** Alphabetical by display name, as the screen lists them. */
    fun read(context: Context): List<Component> {
        val json = context.resources.openRawResource(R.raw.aboutlibraries).use { String(it.readBytes()) }
        return parse(json)
    }

    fun parse(json: String): List<Component> {
        val root = JSONObject(json)
        val texts = root.getJSONObject("licenses")
        val libraries = root.getJSONArray("libraries")
        return (0 until libraries.length()).map { i ->
            val library = libraries.getJSONObject(i)
            val keys = library.getJSONArray("licenses")
            Component(
                id = library.getString("uniqueId"),
                name = library.getString("name"),
                version = library.optString("artifactVersion"),
                coordinate = library.optString("artifactId").ifEmpty { null },
                website = library.optString("website").ifEmpty { null }
                    ?: library.optJSONObject("scm")?.optString("url")?.ifEmpty { null },
                licences = (0 until keys.length()).map { k ->
                    val licence = texts.getJSONObject(keys.getString(k))
                    Licence(
                        name = licence.getString("name"),
                        url = licence.optString("url").ifEmpty { null },
                        text = licence.getString("content"),
                    )
                },
            )
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }
}
