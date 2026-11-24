package ai.sepand.app

import kotlin.math.ln

/** Text normalisation (Persian + English) and a small BM25 search used for Obsidian memory. */
object Txt {
    private val stop = setOf(
        "the", "a", "an", "and", "or", "of", "to", "in", "is", "are", "was", "for", "on", "with", "as", "at", "by", "it",
        "this", "that", "from", "be", "i", "you", "he", "she", "we", "they", "my", "your", "what", "how", "do", "does",
        "و", "در", "به", "از", "که", "این", "را", "با", "است", "برای", "آن", "یک", "هم", "بر", "تا", "یا", "من", "تو", "ما", "شما", "چه", "چی", "چطور"
    )

    fun norm(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            when {
                ch == '\u064A' -> sb.append('\u06CC')
                ch == '\u0643' -> sb.append('\u06A9')
                ch == '\u200C' || ch == '\u0640' -> {}
                ch in '\u064B'..'\u065F' -> {}
                ch in '\u0660'..'\u0669' -> sb.append('0' + (ch - '\u0660'))
                ch in '\u06F0'..'\u06F9' -> sb.append('0' + (ch - '\u06F0'))
                else -> sb.append(ch.lowercaseChar())
            }
        }
        return sb.toString()
    }

    fun tokens(s: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        fun flush() {
            if (cur.length >= 2 && cur.toString() !in stop) out.add(cur.toString())
            cur.setLength(0)
        }
        for (ch in norm(s)) if (Character.isLetterOrDigit(ch)) cur.append(ch) else flush()
        flush()
        return out
    }

    /** A short piece of text around the first matching word. */
    fun snippet(text: String, q: List<String>, max: Int = 360): String {
        val low = text.lowercase()
        var at = -1
        for (t in q) { val i = low.indexOf(t); if (i >= 0 && (at < 0 || i < at)) at = i }
        val from = if (at <= 0) 0 else maxOf(0, at - max / 4)
        val s = text.substring(from, minOf(text.length, from + max)).replace(Regex("\\s+"), " ").trim()
        return (if (from > 0) "…" else "") + s + (if (from + max < text.length) "…" else "")
    }
}

class Bm25 {
    class Doc(val id: String, val title: String, val text: String) {
        val tf = HashMap<String, Int>()
        var len = 0
        init {
            for (t in Txt.tokens(title)) { tf[t] = (tf[t] ?: 0) + 2; len += 2 }
            for (t in Txt.tokens(text)) { tf[t] = (tf[t] ?: 0) + 1; len++ }
        }
    }

    private val docs = LinkedHashMap<String, Doc>()
    val size: Int get() = docs.size

    @Synchronized fun put(id: String, title: String, text: String) { docs[id] = Doc(id, title, text) }
    @Synchronized fun remove(id: String) { docs.remove(id) }
    @Synchronized fun clear() { docs.clear() }
    @Synchronized fun get(id: String): Doc? = docs[id]
    @Synchronized fun ids(): Set<String> = HashSet(docs.keys)

    @Synchronized fun search(query: String, k: Int): List<Pair<Doc, Double>> {
        val q = Txt.tokens(query).distinct()
        if (q.isEmpty() || docs.isEmpty()) return emptyList()
        val n = docs.size.toDouble()
        val avg = docs.values.sumOf { it.len }.toDouble() / n
        val idf = HashMap<String, Double>()
        for (t in q) {
            val df = docs.values.count { it.tf.containsKey(t) }
            idf[t] = ln(1.0 + (n - df + 0.5) / (df + 0.5))
        }
        val scored = ArrayList<Pair<Doc, Double>>()
        for (d in docs.values) {
            var sc = 0.0
            for (t in q) {
                val f = d.tf[t] ?: continue
                sc += idf[t]!! * (f * 2.5) / (f + 1.5 * (0.25 + 0.75 * d.len / avg))
            }
            if (sc > 0) scored.add(d to sc)
        }
        scored.sortByDescending { it.second }
        return scored.take(k)
    }
}
