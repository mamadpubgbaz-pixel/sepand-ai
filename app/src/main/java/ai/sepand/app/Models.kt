package ai.sepand.app

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

class ModelEntry(val id: String, val name: String, val path: String, val size: Long, val mmproj: String = "", val external: Boolean = false) {
    val vision: Boolean get() = mmproj.isNotEmpty()
    fun toJson(): JSONObject = U.jo("id" to id, "name" to name, "path" to path, "size" to size, "mmproj" to mmproj, "vision" to vision, "external" to external)
}

class CatItem(val id: String, val name: String, val repo: String, val quant: String, val mb: Int, val vision: Boolean, val note: String, val mmprojQuant: String = "")

object ModelStore {
    val CATALOG = listOf(
        CatItem("spark-4b", "Spark-X2.6 4B", "XHToken/Spark-X2.6-4B-GGUF", "Q4_K_M", 3500, false, "best"),
        CatItem("spark-1.7b", "Spark-X2.6 1.7B", "XHToken/Spark-X2.6-1.7B-GGUF", "Q4_K_M", 1800, false, "fast"),
        CatItem("qwen3vl-2b", "Qwen3-VL 2B", "Qwen/Qwen3-VL-2B-Instruct-GGUF", "Q4_K_M", 1500, true, "vision", "Q8_0")
    )

    fun catalogJson(): JSONArray = U.ja(CATALOG.map {
        U.jo("id" to it.id, "name" to it.name, "repo" to it.repo, "quant" to it.quant, "mb" to it.mb, "vision" to it.vision, "note" to it.note)
    })

    fun dir(ctx: Context): File = File(ctx.filesDir, "models").also { it.mkdirs() }
    private fun indexFile(ctx: Context) = File(dir(ctx), "index.json")

    private fun readIndex(ctx: Context): MutableList<ModelEntry> {
        val out = ArrayList<ModelEntry>()
        try {
            val a = JSONArray(indexFile(ctx).readText())
            for (o in a.objs()) out.add(ModelEntry(o.s("id"), o.s("name"), o.s("path"), o.optLong("size"), o.s("mmproj"), o.b("external")))
        } catch (e: Exception) { }
        return out
    }

    private fun writeIndex(ctx: Context, l: List<ModelEntry>) { indexFile(ctx).writeText(U.ja(l.map { it.toJson() }).toString()) }

    @Synchronized fun list(ctx: Context): List<ModelEntry> {
        val l = readIndex(ctx).filter { File(it.path).exists() }.toMutableList()
        // .gguf files that were copied in by hand
        dir(ctx).listFiles()?.filter { it.name.endsWith(".gguf") && !it.name.contains("mmproj", true) && l.none { e -> e.path == it.path } }?.forEach {
            l.add(ModelEntry(U.slug(it.name), it.name.removeSuffix(".gguf"), it.path, it.length()))
        }
        return l
    }

    @Synchronized fun add(ctx: Context, e: ModelEntry) {
        val l = readIndex(ctx).filter { it.id != e.id }.toMutableList()
        l.add(e)
        writeIndex(ctx, l)
    }

    @Synchronized fun remove(ctx: Context, id: String) {
        val l = readIndex(ctx)
        val e = l.firstOrNull { it.id == id } ?: list(ctx).firstOrNull { it.id == id } ?: return
        if (!e.external) {
            File(e.path).delete()
            if (e.mmproj.isNotEmpty() && !e.mmproj.contains("/storage/")) File(e.mmproj).delete()
        }
        writeIndex(ctx, l.filter { it.id != id })
    }

    /** Finds the real file names of a quantisation in a Hugging Face repo. */
    fun resolve(base: String, repo: String, quant: String, mmproj: Boolean): Pair<String, String> {
        var file: String? = null
        try {
            val c = URL("$base/api/models/$repo").openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.setRequestProperty("User-Agent", "SepandAI/0.1")
            if (c.responseCode == 200) {
                val names = JSONObject(c.inputStream.bufferedReader().readText()).optJSONArray("siblings")?.objs()?.map { it.s("rfilename") } ?: emptyList()
                val g = names.filter { it.endsWith(".gguf", true) }
                file = if (mmproj) g.firstOrNull { it.contains("mmproj", true) && it.contains(quant, true) } ?: g.firstOrNull { it.contains("mmproj", true) }
                else g.firstOrNull { !it.contains("mmproj", true) && it.contains(quant, true) }
            }
            c.disconnect()
        } catch (e: Exception) { }
        val name = file ?: (repo.substringAfter('/').removeSuffix("-GGUF") + "-" + quant + ".gguf")
        return "$base/$repo/resolve/main/$name" to name.substringAfterLast('/')
    }

    /** Downloads with resume; follows redirects by hand so the resume header survives. */
    fun download(urlStr: String, dest: File, onProgress: (Long, Long) -> Unit, cancel: AtomicBoolean) {
        val part = File(dest.path + ".part")
        var url = urlStr
        var hops = 0
        while (true) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 20000
            c.readTimeout = 30000
            c.setRequestProperty("User-Agent", "SepandAI/0.1")
            val have = if (part.exists()) part.length() else 0L
            if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IOException("Bad redirect")
                url = URL(URL(url), loc).toString()
                c.disconnect()
                if (++hops > 8) throw IOException("Too many redirects")
                continue
            }
            if (code == 416) { c.disconnect(); part.renameTo(dest); return }
            if (code != 200 && code != 206) { c.disconnect(); throw IOException("Download failed: HTTP $code") }
            val append = code == 206
            val startAt = if (append) have else 0L
            val len = c.contentLengthLong
            val total = if (len > 0) startAt + len else 0L
            c.inputStream.use { ins ->
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(256 * 1024)
                    var done = startAt
                    var last = 0L
                    while (true) {
                        if (cancel.get()) throw CancellationException()
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - last > 400) { onProgress(done, total); last = now }
                    }
                    onProgress(done, if (total > 0) total else done)
                }
            }
            c.disconnect()
            if (!part.renameTo(dest)) throw IOException("Could not save the file")
            return
        }
    }

    fun installCatalog(ctx: Context, item: CatItem, onProgress: (Long, Long) -> Unit, cancel: AtomicBoolean): ModelEntry {
        val base = Cfg.s("hfBase").trimEnd('/')
        val (url, name) = resolve(base, item.repo, item.quant, false)
        val dest = File(dir(ctx), name)
        if (!dest.exists()) download(url, dest, onProgress, cancel)
        var mm = ""
        if (item.vision) {
            val (u2, n2) = resolve(base, item.repo, item.mmprojQuant.ifEmpty { item.quant }, true)
            val d2 = File(dir(ctx), n2)
            if (!d2.exists()) download(u2, d2, { a, b -> onProgress(dest.length() + a, dest.length() + b) }, cancel)
            mm = d2.path
        }
        val e = ModelEntry(item.id, item.name, dest.path, dest.length(), mm)
        add(ctx, e)
        return e
    }

    fun fromUrl(ctx: Context, url: String, onProgress: (Long, Long) -> Unit, cancel: AtomicBoolean): ModelEntry {
        val name = url.substringBefore('?').substringAfterLast('/').ifEmpty { "model.gguf" }
        val dest = File(dir(ctx), name)
        if (!dest.exists()) download(url, dest, onProgress, cancel)
        val e = ModelEntry(U.slug(name), name.removeSuffix(".gguf"), dest.path, dest.length())
        add(ctx, e)
        return e
    }

    fun displayName(ctx: Context, uri: Uri): String {
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) return it.getString(0) }
        } catch (e: Exception) { }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    /** /storage/emulated/0/... path of a picked file (works when "All files access" is granted). */
    fun directPath(uri: Uri): String? {
        if (uri.authority != "com.android.externalstorage.documents") return null
        return try {
            val id = DocumentsContract.getDocumentId(uri)
            val p = id.split(":", limit = 2)
            if (p.size != 2) null else (if (p[0] == "primary") Environment.getExternalStorageDirectory().path else "/storage/" + p[0]) + "/" + p[1]
        } catch (e: Exception) { null }
    }

    /** Adds picked .gguf file(s). A file named *mmproj* is attached to the model as its vision part. */
    fun importUris(ctx: Context, uris: List<Uri>, onProgress: (Long, Long) -> Unit): List<ModelEntry> {
        val files = uris.map { u ->
            val name = displayName(ctx, u)
            val direct = directPath(u)?.let { File(it) }?.takeIf { it.canRead() }
            val path = if (direct != null) direct.path else {
                val dest = File(dir(ctx), name)
                if (!dest.exists() || dest.length() == 0L) {
                    val total = try { ctx.contentResolver.openAssetFileDescriptor(u, "r")?.use { it.length } ?: 0L } catch (e: Exception) { 0L }
                    ctx.contentResolver.openInputStream(u)!!.use { ins ->
                        FileOutputStream(dest).use { out ->
                            val buf = ByteArray(256 * 1024)
                            var done = 0L
                            var last = 0L
                            while (true) {
                                val n = ins.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                val now = System.currentTimeMillis()
                                if (now - last > 400) { onProgress(done, total); last = now }
                            }
                        }
                    }
                }
                dest.path
            }
            Triple(name, path, direct != null)
        }
        val mm = files.firstOrNull { it.first.contains("mmproj", true) }
        val out = ArrayList<ModelEntry>()
        for (f in files) {
            if (f.first.contains("mmproj", true)) continue
            val e = ModelEntry(U.slug(f.first), f.first.removeSuffix(".gguf"), f.second, File(f.second).length(), mm?.second ?: "", f.third)
            add(ctx, e)
            out.add(e)
        }
        return out
    }
}
