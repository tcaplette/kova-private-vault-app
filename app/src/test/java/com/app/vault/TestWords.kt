package com.app.vault

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * [Words] for a JVM test — `app-languages`. There are no resources to resolve against off a
 * device, so the base `strings.xml` is read directly and the `R` field's name finds the entry.
 * English, as the app draws it with no language chosen on an English device.
 */
object TestWords : Words {

    private val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(File("src/main/res/values/strings.xml"))

    private val strings: Map<String, String> = doc.getElementsByTagName("string").let { nodes ->
        (0 until nodes.length).associate { i ->
            val e = nodes.item(i) as Element
            e.getAttribute("name") to unescape(e.textContent)
        }
    }

    private val plurals: Map<String, Map<String, String>> = doc.getElementsByTagName("plurals").let { nodes ->
        (0 until nodes.length).associate { i ->
            val e = nodes.item(i) as Element
            val items = e.getElementsByTagName("item")
            e.getAttribute("name") to (0 until items.length).associate { j ->
                val item = items.item(j) as Element
                item.getAttribute("quantity") to unescape(item.textContent)
            }
        }
    }

    private fun name(type: Class<*>, id: Int): String =
        type.fields.single { it.getInt(null) == id }.name

    /** The text of the string resource [id], unformatted. */
    fun text(id: Int): String = strings.getValue(name(R.string::class.java, id))

    override fun get(id: Int, vararg args: Any): String =
        if (args.isEmpty()) text(id) else String.format(text(id), *args)

    override fun plural(id: Int, count: Int, vararg args: Any): String {
        val forms = plurals.getValue(name(R.plurals::class.java, id))
        val form = forms[if (count == 1) "one" else "other"] ?: forms.getValue("other")
        return String.format(form, *args)
    }

    /** Every base string's text — for scans that hold a word to the strings it may appear in. */
    val all: Map<String, String> get() = strings

    /**
     * [source] with every string resource it names written back in as the English literal
     * it was before `app-languages` moved it to `strings.xml` — so a scan that asks "does this
     * screen say X" still reads the sentence where the screen draws it. A lookup with no
     * arguments becomes the bare literal; one with arguments keeps its call and loses only
     * the ID; a plural reads as its `other` form.
     */
    fun inlined(source: String): String {
        fun literal(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
        val bare = Regex("""\b(?:stringResource|getString|ctx\.getString|context\.getString|words\.get|words\(\)\.get)\(R\.string\.(\w+)\)""")
        val ids = Regex("""\bR\.string\.(\w+)\b""")
        val plurals = Regex("""\bR\.plurals\.(\w+)\b""")
        return source
            .replace(bare) { m -> strings[m.groupValues[1]]?.let(::literal) ?: m.value }
            .replace(ids) { m -> strings[m.groupValues[1]]?.let(::literal) ?: m.value }
            .replace(plurals) { m -> plurals_(m.groupValues[1])?.let(::literal) ?: m.value }
    }

    private fun plurals_(name: String): String? = plurals[name]?.let { it["other"] ?: it.values.first() }

    private fun unescape(raw: String): String {
        val s = raw.trim().removeSurrounding("\"")
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length && s[i + 1] == 'u') {
                out.append(s.substring(i + 2, i + 6).toInt(16).toChar())
                i += 6
            } else if (c == '\\' && i + 1 < s.length) {
                out.append(if (s[i + 1] == 'n') '\n' else s[i + 1])
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}

/** A main source file as the scans read it — see [TestWords.inlined]. */
fun File.readSource(): String = readText().let { if (name.endsWith(".kt")) TestWords.inlined(it) else it }
