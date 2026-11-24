package ai.sepand.app

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The user's Obsidian vault: a normal folder of .md files that the user picks once.
 * Notes are searched with a small local index, and the AI can read and write notes in the memory folder.
 */
object Vault {
    class Hit(val path: String, val title: String, val snippet: String, val score: Double)
    private class Meta(val docId: String, val title: String, val mtime: Long, val size: Long)
    private class Child(val docId: String, val name: String, val mime: String, val mtime: Long, val size: Long) {
        val dir get() = mime == Document.MIME_TYPE_DIR
    }

    private val index = Bm25()
    private val metas = HashMap<String, Meta>()
    private val texts = HashMap<String, String>()
    @Volatile private var loaded = false
    @Volatile var indexing = false
    @Volatile var lastIndexed = 0L

    fun tree(): Uri? = Cfg.o("obs").s("uri").takeIf { it.isNotEmpty() }?.let { Uri.parse(it) }
    fun connected() = tree() != null
    fun folder(): String = Cfg.o("obs").s("folder").trim('/').ifEmpty { "SepandAI/Memory" }

    private fun children(ctx: Context, tree: Uri, parent: String): List<Child> {
        val out = ArrayList<Child>()
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
        try {
            val cols = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_SIZE)
            ctx.contentResolver.query(uri, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) out.add(Child(c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getLong(3), c.getLong(4)))
            }
        } catch (e: Exception) { }
        return out
    }

    private fun walk(ctx: Context, tree: Uri, parent: String, rel: String, out: MutableList<Pair<String, Child>>) {
        if (out.size > 20000) return
        for (c in children(ctx, tree, parent)) {
            if (c.name.startsWith(".")) continue
            val p = if (rel.isEmpty()) c.name else rel + "/" + c.name
            if (c.dir) walk(ctx, tree, c.docId, p, out) else if (c.name.endsWith(".md", true)) out.add(p to c)
        }
    }

    private fun readText(ctx: Context, tree: Uri, docId: String, max: Int): String? = try {
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        ctx.contentResolver.openInputStream(uri)?.use { ins ->
            val buf = CharArray(max)
            val n = ins.bufferedReader(Charsets.UTF_8).read(buf)
            if (n <= 0) "" else String(buf, 0, n)
        }
    } catch (e: Exception) { null }

    private fun write(ctx: Context, tree: Uri, docId: String, text: String) {
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        (ctx.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot write the note")).use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    private fun shownName(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (e: Exception) { null }

    private fun create(ctx: Context, tree: Uri, parent: String, name: String, mime: String): String {
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(tree, parent)
        var uri = DocumentsContract.createDocument(ctx.contentResolver, parentUri, mime, name) ?: throw IOException("Cannot create $name")
        val shown = shownName(ctx, uri)
        if (shown != null && shown != name) DocumentsContract.renameDocument(ctx.contentResolver, uri, name)?.let { uri = it }
        return DocumentsContract.getDocumentId(uri)
    }

    private fun ensureDir(ctx: Context, tree: Uri, parent: String, name: String): String =
        children(ctx, tree, parent).firstOrNull { it.dir && it.name == name }?.docId ?: create(ctx, tree, parent, name, Document.MIME_TYPE_DIR)

    private fun cacheFile(ctx: Context) = File(ctx.filesDir, "vault_index.json")

    @Synchronized private fun loadCache(ctx: Context) {
        if (loaded) return
        loaded = true
        try {
            val o = JSONObject(cacheFile(ctx).readText())
            if (o.s("tree") != tree()?.toString()) return
            for (d in o.getJSONArray("docs").objs()) {
                val p = d.s("p")
                metas[p] = Meta(d.s("d"), d.s("n"), d.optLong("m"), d.optLong("s"))
                texts[p] = d.s("t")
                index.put(p, d.s("n"), d.s("t"))
            }
            lastIndexed = o.optLong("at")
        } catch (e: Exception) { }
    }

    @Synchronized private fun saveCache(ctx: Context) {
        try {
            val a = org.json.JSONArray()
            for ((p, m) in metas) a.put(U.jo("p" to p, "d" to m.docId, "n" to m.title, "m" to m.mtime, "s" to m.size, "t" to (texts[p] ?: "")))
            cacheFile(ctx).writeText(U.jo("tree" to tree()?.toString(), "at" to lastIndexed, "docs" to a).toString())
        } catch (e: Exception) { }
    }

    /** Reads new and changed notes into the search index. */
    fun refresh(ctx: Context) {
        val t = tree() ?: return
        if (indexing) return
        indexing = true
        Bus.emit("obs", status())
        try {
            loadCache(ctx)
            val files = ArrayList<Pair<String, Child>>()
            walk(ctx, t, DocumentsContract.getTreeDocumentId(t), "", files)
            val present = HashSet<String>()
            for ((p, c) in files) {
                present.add(p)
                val m = metas[p]
                if (m != null && m.mtime == c.mtime && m.size == c.size) continue
                val text = readText(ctx, t, c.docId, 4000) ?: continue
                val title = p.substringAfterLast('/').removeSuffix(".md")
                synchronized(this) { metas[p] = Meta(c.docId, title, c.mtime, c.size); texts[p] = text; index.put(p, title, text) }
            }
            for (p in metas.keys.toList()) if (p !in present) synchronized(this) { metas.remove(p); texts.remove(p); index.remove(p) }
            lastIndexed = U.now()
            saveCache(ctx)
        } finally {
            indexing = false
            Bus.emit("obs", status())
        }
    }

    fun search(ctx: Context, q: String, k: Int, scope: String = Cfg.o("obs").s("scope")): List<Hit> {
        if (!connected()) return emptyList()
        loadCache(ctx)
        if (index.size == 0 && !indexing) refresh(ctx)
        else if (U.now() - lastIndexed > 90000 && !indexing) U.pool.execute { refresh(ctx) }
        val qt = Txt.tokens(q)
        val mem = folder() + "/"
        return index.search(q, k * 6).filter { scope != "memory" || it.first.id.startsWith(mem) }.take(k)
            .map { (d, s) -> Hit(d.id, d.title, Txt.snippet(d.text, qt), s) }
    }

    fun read(ctx: Context, ref: String): String? {
        val t = tree() ?: return null
        loadCache(ctx)
        val r = ref.trim()
        val key = metas.keys.firstOrNull { it.equals(r, true) || it.equals("$r.md", true) }
            ?: metas.entries.firstOrNull { it.value.title.equals(r.removeSuffix(".md"), true) }?.key
        val m = metas[key ?: return null] ?: return null
        return readText(ctx, t, m.docId, 12000)
    }

    /** Saves a memory note into the memory folder (appends if a note with that title already exists). */
    fun save(ctx: Context, title: String, content: String, tags: List<String> = emptyList()): String {
        val t = tree() ?: throw IllegalStateException("Obsidian is not connected")
        loadCache(ctx)
        var parent = DocumentsContract.getTreeDocumentId(t)
        for (part in folder().split('/').filter { it.isNotEmpty() }) parent = ensureDir(ctx, t, parent, part)
        val name = U.safeName(title) + ".md"
        val rel = folder() + "/" + name
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        val existing = children(ctx, t, parent).firstOrNull { it.name.equals(name, true) }
        val docId: String
        val full: String
        if (existing != null) {
            docId = existing.docId
            val old = readText(ctx, t, docId, 200000) ?: ""
            full = old.trimEnd() + "\n\n---\n*" + stamp + "*\n" + content.trim() + "\n"
        } else {
            docId = create(ctx, t, parent, name, "text/markdown")
            val tagLine = (listOf("sepand", "memory") + tags.map { U.safeName(it).replace(' ', '-') }).distinct().joinToString(", ")
            full = "---\ncreated: $stamp\nsource: sepand-ai\ntags: [$tagLine]\n---\n\n# ${title.trim()}\n\n${content.trim()}\n"
        }
        write(ctx, t, docId, full)
        synchronized(this) {
            val shortText = full.take(4000)
            metas[rel] = Meta(docId, name.removeSuffix(".md"), U.now(), full.length.toLong())
            texts[rel] = shortText
            index.put(rel, name.removeSuffix(".md"), shortText)
        }
        saveCache(ctx)
        Bus.emit("obs", status())
        return rel
    }

    fun status(): JSONObject {
        val t = tree()
        return U.jo(
            "connected" to (t != null),
            "name" to (try { t?.let { DocumentsContract.getTreeDocumentId(it).substringAfterLast('/').substringAfterLast(':') } } catch (e: Exception) { null }),
            "notes" to index.size, "indexing" to indexing, "folder" to folder(), "at" to lastIndexed
        )
    }

    fun reset() = synchronized(this) { metas.clear(); texts.clear(); index.clear(); loaded = false; lastIndexed = 0 }

    fun disconnect(ctx: Context) {
        val t = tree()
        if (t != null) try { ctx.contentResolver.releasePersistableUriPermission(t, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (e: Exception) { }
        reset()
        cacheFile(ctx).delete()
        val o = Cfg.o("obs")
        o.put("uri", "")
        Cfg.set("obs", o)
    }
}
