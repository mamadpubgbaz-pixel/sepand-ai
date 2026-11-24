package ai.sepand.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream

class Att(val id: String, val name: String, val mime: String, val size: Long, val kind: String, val text: String, val b64: String, val thumb: String, val note: String) {
    fun meta(): JSONObject = U.jo("id" to id, "name" to name, "mime" to mime, "size" to size, "kind" to kind, "thumb" to thumb, "note" to note, "chars" to text.length)
}

/** Turns any picked file into something the AI can use: text, a picture, or a short description. */
object Attachments {
    private val store = ConcurrentHashMap<String, Att>()
    private var pdfReady = false
    private val TEXT_EXT = setOf("txt", "md", "markdown", "csv", "tsv", "json", "xml", "html", "htm", "css", "js", "ts", "kt", "java", "py", "c", "cpp", "h", "cs", "go", "rs", "rb", "php",
        "sh", "bat", "yml", "yaml", "toml", "ini", "cfg", "conf", "log", "sql", "tex", "srt", "vtt", "gradle", "properties", "svg")
    private val OFFICE = setOf("docx", "xlsx", "pptx", "odt", "ods", "odp", "epub")
    private const val MAX_CHARS = 300000

    fun get(id: String): Att? = store[id]
    fun drop(id: String) { store.remove(id) }
    fun any(): Boolean = store.isNotEmpty()
    fun byName(n: String): Att? = store.values.firstOrNull { it.name.equals(n, true) } ?: store.values.firstOrNull { it.name.contains(n, true) }

    fun load(ctx: Context, uri: Uri): Att {
        val cr = ctx.contentResolver
        val name = ModelStore.displayName(ctx, uri)
        val mime = cr.getType(uri) ?: "application/octet-stream"
        val ext = name.substringAfterLast('.', "").lowercase()
        val size = try { cr.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L } catch (e: Exception) { 0L }
        var kind = "binary"
        var text = ""
        var b64 = ""
        var thumb = ""
        var note = ""
        try {
            when {
                mime.startsWith("image/") -> {
                    kind = "image"
                    val (a, b) = image(ctx, uri)
                    b64 = a
                    thumb = b
                }
                ext == "pdf" || mime == "application/pdf" -> { kind = "text"; text = pdf(ctx, uri); if (text.isBlank()) { note = "No text found (scanned PDF?)" } }
                ext in OFFICE -> {
                    kind = "text"
                    text = office(ctx, uri, ext)
                    if (ext == "xlsx") note = "Only the text cells are included."
                }
                ext == "zip" -> { kind = "text"; text = zipList(ctx, uri) }
                ext in TEXT_EXT || mime.startsWith("text/") || mime.contains("json") || mime.contains("xml") || mime.contains("javascript") -> {
                    kind = "text"
                    text = readText(ctx, uri)
                }
                else -> {
                    val head = cr.openInputStream(uri)?.use { it.readNBytesCompat(4096) } ?: ByteArray(0)
                    val bad = head.count { val v = it.toInt() and 0xff; v < 9 || (v in 14..31) }
                    if (head.isNotEmpty() && bad * 20 < head.size) { kind = "text"; text = readText(ctx, uri) }
                    else note = "Binary file. The AI only knows its name, type and size."
                }
            }
        } catch (e: Throwable) {
            note = "Could not read this file: " + (e.message ?: e.javaClass.simpleName)
        }
        if (text.length > MAX_CHARS) { text = text.substring(0, MAX_CHARS); note = "Cut after $MAX_CHARS characters." }
        val a = Att(U.id(), name, mime, size, kind, text, b64, thumb, note)
        store[a.id] = a
        return a
    }


    private fun java.io.InputStream.readNBytesCompat(n: Int): ByteArray {
        val buf = ByteArray(n)
        var got = 0
        while (got < n) { val r = read(buf, got, n - got); if (r < 0) break; got += r }
        return buf.copyOf(got)
    }

    private fun readText(ctx: Context, uri: Uri): String =
        ctx.contentResolver.openInputStream(uri)?.use { String(it.readNBytesCompat(4_000_000), Charsets.UTF_8) } ?: ""

    private fun pdf(ctx: Context, uri: Uri): String {
        if (!pdfReady) { com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(ctx.applicationContext); pdfReady = true }
        ctx.contentResolver.openInputStream(uri)!!.use { ins ->
            val doc = com.tom_roush.pdfbox.pdmodel.PDDocument.load(ins)
            try {
                val s = com.tom_roush.pdfbox.text.PDFTextStripper()
                s.setStartPage(1)
                s.setEndPage(minOf(doc.getNumberOfPages(), 80))
                return s.getText(doc)
            } finally { doc.close() }
        }
    }

    private fun unescape(s: String): String = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
        .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: "" }.replace("&amp;", "&")

    private fun xmlText(xml: String, ext: String): String {
        var x = xml
        x = when (ext) {
            "docx" -> x.replace("</w:p>", "\n").replace(Regex("<w:tab\\s*/>"), "\t").replace(Regex("<w:br\\s*/>"), "\n")
            "pptx" -> x.replace("</a:p>", "\n")
            "xlsx" -> x.replace("</si>", "\n")
            "odt", "ods", "odp" -> x.replace("</text:p>", "\n").replace("</text:h>", "\n")
            else -> x.replace(Regex("(?is)<(script|style).*?</\\1>"), "").replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</h\\d>|</li>"), "\n")
        }
        return unescape(x.replace(Regex("<[^>]+>"), "")).replace(Regex("\n{3,}"), "\n\n")
    }

    private fun office(ctx: Context, uri: Uri, ext: String): String {
        val sb = StringBuilder()
        ZipInputStream(ctx.contentResolver.openInputStream(uri)!!.buffered()).use { z ->
            var e = z.nextEntry
            while (e != null && sb.length < MAX_CHARS) {
                val n = e.name
                val take = when (ext) {
                    "docx" -> n == "word/document.xml"
                    "xlsx" -> n == "xl/sharedStrings.xml"
                    "pptx" -> n.startsWith("ppt/slides/slide") && n.endsWith(".xml")
                    "odt", "ods", "odp" -> n == "content.xml"
                    else -> n.endsWith(".xhtml") || n.endsWith(".html") || n.endsWith(".htm")
                }
                if (take) sb.append(xmlText(z.readBytes().toString(Charsets.UTF_8), ext)).append('\n')
                e = z.nextEntry
            }
        }
        return sb.toString().trim()
    }

    private fun zipList(ctx: Context, uri: Uri): String {
        val sb = StringBuilder("Files inside the zip:\n")
        var n = 0
        ZipInputStream(ctx.contentResolver.openInputStream(uri)!!.buffered()).use { z ->
            var e = z.nextEntry
            while (e != null && n < 300) { sb.append("- ").append(e.name).append('\n'); n++; e = z.nextEntry }
        }
        return sb.toString()
    }

    private fun image(ctx: Context, uri: Uri): Pair<String, String> {
        val cr = ctx.contentResolver
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, o) }
        var s = 1
        while (o.outWidth / s > 2400 || o.outHeight / s > 2400) s *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = s }
        var bmp = cr.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, o2) } ?: throw IOException("Not a readable picture")
        try {
            val rot = cr.openInputStream(uri)!!.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            }
            if (rot != 0f) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot) }, true)
        } catch (e: Exception) { }
        val src = bmp
        fun enc(maxDim: Int, q: Int): String {
            val sc = minOf(1f, maxDim.toFloat() / maxOf(src.width, src.height))
            val b = if (sc < 1f) Bitmap.createScaledBitmap(src, maxOf(1, (src.width * sc).toInt()), maxOf(1, (src.height * sc).toInt()), true) else src
            val bo = ByteArrayOutputStream()
            b.compress(Bitmap.CompressFormat.JPEG, q, bo)
            return Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP)
        }
        return enc(1024, 82) to enc(160, 70)
    }
}
