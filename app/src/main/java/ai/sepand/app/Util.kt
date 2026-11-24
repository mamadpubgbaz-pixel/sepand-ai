package ai.sepand.app

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

object U {
    val pool = Executors.newCachedThreadPool { r -> Thread(r).also { it.isDaemon = true } }

    fun jo(vararg p: Pair<String, Any?>): JSONObject {
        val o = JSONObject()
        for ((k, v) in p) o.put(k, v ?: JSONObject.NULL)
        return o
    }

    fun ja(items: Iterable<Any?>): JSONArray {
        val a = JSONArray()
        for (i in items) a.put(i)
        return a
    }

    fun clip(s: String, n: Int): String = if (s.length <= n) s else s.substring(0, n) + "…"

    fun safeName(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t#^\\[\\]]"), " ").replace(Regex("\\s+"), " ").trim().take(80).ifEmpty { "note" }

    fun now(): Long = System.currentTimeMillis()

    fun id(): String = java.lang.Long.toString(System.nanoTime(), 36) + java.lang.Integer.toString((Math.random() * 46656).toInt(), 36)

    fun slug(s: String): String = s.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "x" }
}

fun JSONObject.s(k: String, d: String = ""): String = if (has(k) && !isNull(k)) optString(k, d) else d
fun JSONObject.i(k: String, d: Int = 0): Int = if (has(k) && !isNull(k)) optInt(k, d) else d
fun JSONObject.b(k: String, d: Boolean = false): Boolean = if (has(k) && !isNull(k)) optBoolean(k, d) else d
fun JSONObject.d(k: String, d: Double = 0.0): Double = if (has(k) && !isNull(k)) optDouble(k, d) else d
fun JSONArray.objs(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
fun JSONArray.strs(): List<String> = (0 until length()).mapNotNull { if (isNull(it)) null else optString(it) }
