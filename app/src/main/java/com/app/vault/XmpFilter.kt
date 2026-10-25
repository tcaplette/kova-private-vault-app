package com.app.vault

import com.app.vault.ExifPolicy.Claim
import com.app.vault.ExifPolicy.In
import com.app.vault.ExifPolicy.Kept
import com.app.vault.ExifPolicy.Unclaimed
import org.w3c.dom.Attr
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The XMP packet, filtered field by field — `exif-strip-choice`, design D4.
 *
 * ***A second container that holds all four categories at once***, and the only one that holds
 * edit history — so dropping it whole, as the first round did, made "keep edit history" keep
 * nothing, and cost a motion photo the directory that says where its video is. Each property of
 * each `rdf:Description` is kept or dropped by [claim]; a namespace the app does not know is
 * dropped.
 *
 * **Parsed with the platform's DOM parser** (`javax.xml.parsers`), which is on the device and on
 * the JVM alike, so the same code is what the unit tests run. A packet carrying a `DOCTYPE` or an
 * entity declaration is refused before parsing: XMP has no use for either, and they are how an
 * XML parser is made to read something it should not.
 *
 * **Length.** Before the multi-picture index the rewritten packet is written at its own length.
 * After it — or inside an embedded image — it is padded to its original length with the
 * whitespace the XMP wrapper provides for exactly this, an in-place edit.
 */
internal object XmpFilter {

    /** One entry of Google's container directory: what follows the primary image, in order. */
    class ContainerItem(val mime: String, val semantic: String, val length: Long, val padding: Long)

    class Result(
        /** The packet to write, or null to remove it. */
        val packet: ByteArray?,
        val changed: Boolean,
        val container: List<ContainerItem>?,
        /** A legacy micro video's length from the end of the file, or null. */
        val microVideoOffset: Long?,
    )

    const val RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    private const val X = "adobe:ns:meta/"
    private const val XMLNS = "http://www.w3.org/2000/xmlns/"
    private const val XML = "http://www.w3.org/XML/1998/namespace"

    const val EXIF = "http://ns.adobe.com/exif/1.0/"
    const val EXIF_EX = "http://cipa.jp/exif/1.0/"
    const val TIFF = "http://ns.adobe.com/tiff/1.0/"
    const val AUX = "http://ns.adobe.com/exif/1.0/aux/"
    const val XMP = "http://ns.adobe.com/xap/1.0/"
    const val XMP_MM = "http://ns.adobe.com/xap/1.0/mm/"
    const val PHOTOSHOP = "http://ns.adobe.com/photoshop/1.0/"
    const val CRS = "http://ns.adobe.com/camera-raw-settings/1.0/"
    const val IPTC_CORE = "http://iptc.org/std/Iptc4xmpCore/1.0/xmlns/"
    const val IPTC_EXT = "http://iptc.org/std/Iptc4xmpExt/2008-02-29/"
    const val HDRGM = "http://ns.adobe.com/hdr-gain-map/1.0/"
    const val CONTAINER = "http://ns.google.com/photos/1.0/container/"
    const val ITEM = "http://ns.google.com/photos/1.0/container/item/"
    const val GCAMERA = "http://ns.google.com/photos/1.0/camera/"

    private val location = In(PhotoDetail.LOCATION)
    private val camera = In(PhotoDetail.CAMERA)
    private val date = In(PhotoDetail.DATE)
    private val software = In(PhotoDetail.SOFTWARE)

    private val EXIF_KEPT = setOf(
        "ExifVersion", "FlashpixVersion", "ColorSpace", "ComponentsConfiguration",
        "PixelXDimension", "PixelYDimension",
    )
    private val EXIF_CAMERA = setOf(
        "ExposureTime", "FNumber", "ExposureProgram", "SpectralSensitivity", "ISOSpeedRatings",
        "PhotographicSensitivity", "SensitivityType", "ISOSpeed", "OECF", "ShutterSpeedValue",
        "ApertureValue", "BrightnessValue", "ExposureBiasValue", "MaxApertureValue",
        "SubjectDistance", "MeteringMode", "LightSource", "Flash", "FocalLength", "SubjectArea",
        "FlashEnergy", "SpatialFrequencyResponse", "FocalPlaneXResolution", "FocalPlaneYResolution",
        "FocalPlaneResolutionUnit", "SubjectLocation", "ExposureIndex", "SensingMethod",
        "FileSource", "SceneType", "CFAPattern", "CustomRendered", "ExposureMode", "WhiteBalance",
        "DigitalZoomRatio", "FocalLengthIn35mmFilm", "SceneCaptureType", "GainControl",
        "Contrast", "Saturation", "Sharpness", "DeviceSettingDescription", "SubjectDistanceRange",
        "ImageUniqueID",
    )
    private val TIFF_KEPT = setOf(
        "ImageWidth", "ImageLength", "BitsPerSample", "Compression", "PhotometricInterpretation",
        "Orientation", "SamplesPerPixel", "PlanarConfiguration", "YCbCrSubSampling",
        "YCbCrPositioning", "XResolution", "YResolution", "ResolutionUnit", "TransferFunction",
        "WhitePoint", "PrimaryChromaticities", "YCbCrCoefficients", "ReferenceBlackWhite",
    )

    /** Design D1's XMP column. Anything not named here is removed by every strip. */
    fun claim(ns: String, local: String): Claim = when (ns) {
        EXIF -> when {
            local.startsWith("GPS") -> location
            local == "DateTimeOriginal" || local == "DateTimeDigitized" -> date
            local in EXIF_KEPT -> Kept
            local in EXIF_CAMERA -> camera
            else -> Unclaimed
        }
        EXIF_EX -> if (local == "Gamma") Kept else camera
        TIFF -> when (local) {
            "Make", "Model" -> camera
            "Software" -> software
            "DateTime" -> date
            in TIFF_KEPT -> Kept
            else -> Unclaimed
        }
        AUX -> camera
        XMP -> when (local) {
            "CreateDate", "ModifyDate", "MetadataDate" -> date
            "CreatorTool" -> software
            else -> Unclaimed
        }
        XMP_MM, CRS -> software
        PHOTOSHOP -> when (local) {
            "City", "State", "Country" -> location
            "DateCreated" -> date
            "History" -> software
            else -> Unclaimed
        }
        IPTC_CORE -> if (local == "Location" || local == "CountryCode") location else Unclaimed
        IPTC_EXT -> if (local == "LocationCreated" || local == "LocationShown") location else Unclaimed
        HDRGM, CONTAINER, ITEM -> Kept
        GCAMERA -> if (local.startsWith("MotionPhoto") || local.startsWith("MicroVideo")) Kept else Unclaimed
        else -> Unclaimed
    }

    private val REMOVE = Result(null, true, null, null)

    fun filter(packet: ByteArray, removed: Set<PhotoDetail>, targetLength: Int?): Result {
        var end = packet.size
        while (end > 0 && packet[end - 1] == 0.toByte()) end--
        val text = String(packet, 0, end, Charsets.UTF_8)
        if (text.contains("<!DOCTYPE", ignoreCase = true) || text.contains("<!ENTITY", ignoreCase = true)) {
            return REMOVE
        }
        val doc = try {
            DocumentBuilderFactory.newInstance()
                .apply { isNamespaceAware = true }
                .newDocumentBuilder()
                .parse(ByteArrayInputStream(packet, 0, end))
        } catch (_: Exception) {
            return REMOVE
        }
        val rdf = doc.getElementsByTagNameNS(RDF, "RDF").item(0) as? Element ?: return REMOVE

        val keptAttrs = ArrayList<Attr>()
        val keptKids = ArrayList<Element>()
        var dropped = false
        var container: List<ContainerItem>? = null
        var microVideo = false
        var microOffset: Long? = null

        for (node in children(rdf)) {
            if (node.namespaceURI != RDF || node.localName != "Description") {
                dropped = true
                continue
            }
            val attrs = node.attributes
            for (i in 0 until attrs.length) {
                val a = attrs.item(i) as Attr
                val ns = a.namespaceURI ?: ""
                if (ns == XMLNS || ns == RDF) continue
                val local = a.localName ?: a.name
                if (ns == GCAMERA && local == "MicroVideo") microVideo = a.value.trim() == "1"
                if (ns == GCAMERA && local == "MicroVideoOffset") microOffset = a.value.trim().toLongOrNull()
                if (ExifPolicy.survives(claim(ns, local), removed)) keptAttrs += a else dropped = true
            }
            for (kid in children(node)) {
                val ns = kid.namespaceURI ?: ""
                val local = kid.localName ?: kid.nodeName
                if (ns == CONTAINER && local == "Directory") container = directory(kid)
                if (ns == GCAMERA && local == "MicroVideo") microVideo = kid.textContent.trim() == "1"
                if (ns == GCAMERA && local == "MicroVideoOffset") microOffset = kid.textContent.trim().toLongOrNull()
                if (ExifPolicy.survives(claim(ns, local), removed)) keptKids += kid else dropped = true
            }
        }

        val micro = if (microVideo) microOffset else null
        if (!dropped) return Result(packet, false, container, micro)
        if (keptAttrs.isEmpty() && keptKids.isEmpty()) return Result(null, true, container, micro)

        val out = serialise(keptAttrs, keptKids)
        if (targetLength == null) return Result(out, true, container, micro)
        if (out.size > targetLength) return Result(null, true, container, micro)
        return Result(padded(out, targetLength), true, container, micro)
    }

    private fun children(parent: Node): List<Element> {
        val nodes = parent.childNodes
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }

    private fun directory(dir: Element): List<ContainerItem> {
        val items = dir.getElementsByTagNameNS(CONTAINER, "Item")
        return (0 until items.length).map { i ->
            val item = items.item(i) as Element
            fun field(name: String): String? =
                item.getAttributeNodeNS(ITEM, name)?.value?.trim()
                    ?: children(item).firstOrNull { it.namespaceURI == ITEM && it.localName == name }
                        ?.textContent?.trim()
            ContainerItem(
                mime = field("Mime").orEmpty(),
                semantic = field("Semantic").orEmpty(),
                length = field("Length")?.toLongOrNull() ?: 0,
                padding = field("Padding")?.toLongOrNull() ?: 0,
            )
        }
    }

    // ── Writing it back ───────────────────────────────────────────────────────

    private const val END = "<?xpacket end=\"w\"?>"

    private class Prefixes {
        val byUri = LinkedHashMap<String, String>().apply {
            put(RDF, "rdf")
            put(X, "x")
        }

        fun of(uri: String, wanted: String?): String {
            if (uri == XML) return "xml"
            byUri[uri]?.let { return it }
            var p = wanted?.takeIf { it.isNotEmpty() && it != "xml" && it != "xmlns" } ?: "ns"
            if (p in byUri.values) {
                var n = 1
                while ("ns$n" in byUri.values) n++
                p = "ns$n"
            }
            byUri[uri] = p
            return p
        }
    }

    private fun serialise(attrs: List<Attr>, kids: List<Element>): ByteArray {
        val prefixes = Prefixes()
        fun register(e: Element) {
            e.namespaceURI?.let { prefixes.of(it, e.prefix) }
            val a = e.attributes
            for (i in 0 until a.length) {
                val at = a.item(i) as Attr
                at.namespaceURI?.takeIf { it != XMLNS }?.let { prefixes.of(it, at.prefix) }
            }
            children(e).forEach(::register)
        }
        attrs.forEach { a -> a.namespaceURI?.let { prefixes.of(it, a.prefix) } }
        kids.forEach(::register)

        fun qname(n: Node): String {
            val uri = n.namespaceURI ?: return n.localName ?: n.nodeName
            return prefixes.of(uri, n.prefix) + ":" + (n.localName ?: n.nodeName)
        }

        val sb = StringBuilder()
        fun element(e: Element) {
            sb.append('<').append(qname(e))
            val a = e.attributes
            for (i in 0 until a.length) {
                val at = a.item(i) as Attr
                if (at.namespaceURI == XMLNS) continue
                sb.append(' ').append(qname(at)).append("=\"").append(escape(at.value, true)).append('"')
            }
            val nodes = e.childNodes
            if (nodes.length == 0) {
                sb.append("/>")
                return
            }
            sb.append('>')
            for (i in 0 until nodes.length) {
                when (val n = nodes.item(i)) {
                    is Element -> element(n)
                    else -> if (n.nodeType == Node.TEXT_NODE || n.nodeType == Node.CDATA_SECTION_NODE) {
                        sb.append(escape(n.nodeValue.orEmpty(), false))
                    }
                }
            }
            sb.append("</").append(qname(e)).append('>')
        }

        sb.append("<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n")
        sb.append("<x:xmpmeta xmlns:x=\"").append(X).append("\">")
        sb.append("<rdf:RDF xmlns:rdf=\"").append(RDF).append("\">")
        sb.append("<rdf:Description rdf:about=\"\"")
        for ((uri, p) in prefixes.byUri) {
            if (uri != RDF && uri != X) sb.append(" xmlns:").append(p).append("=\"").append(escape(uri, true)).append('"')
        }
        for (a in attrs) sb.append(' ').append(qname(a)).append("=\"").append(escape(a.value, true)).append('"')
        if (kids.isEmpty()) {
            sb.append("/>")
        } else {
            sb.append('>')
            kids.forEach(::element)
            sb.append("</rdf:Description>")
        }
        sb.append("</rdf:RDF></x:xmpmeta>\n")
        sb.append(END)
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun escape(s: String, attribute: Boolean): String = buildString(s.length) {
        for (c in s) when {
            c == '&' -> append("&amp;")
            c == '<' -> append("&lt;")
            c == '>' -> append("&gt;")
            attribute && c == '"' -> append("&quot;")
            attribute && (c == '\n' || c == '\r' || c == '\t') -> append("&#").append(c.code).append(';')
            else -> append(c)
        }
    }

    /** [out] grown to [length] with the wrapper's own whitespace padding, ahead of its end marker. */
    private fun padded(out: ByteArray, length: Int): ByteArray {
        val end = END.toByteArray(Charsets.US_ASCII)
        val body = out.copyOf(out.size - end.size)
        val pad = ByteArray(length - out.size) { i -> if (i % 100 == 99) '\n'.code.toByte() else ' '.code.toByte() }
        return body + pad + end
    }
}
