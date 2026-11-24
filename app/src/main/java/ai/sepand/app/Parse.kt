package ai.sepand.app

import org.json.JSONObject

/** Reading JSON out of model replies (small models often add text around it). */
object Parse {
    fun firstJson(s: String): JSONObject? {
        var from = 0
        while (true) {
            val start = s.indexOf('{', from)
            if (start < 0) return null
            var depth = 0
            var inStr = false
            var esc = false
            var i = start
            while (i < s.length) {
                val c = s[i]
                if (inStr) {
                    if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false
                } else if (c == '"') {
                    inStr = true
                } else if (c == '{') {
                    depth++
                } else if (c == '}') {
                    depth--
                    if (depth == 0) {
                        try { return JSONObject(s.substring(start, i + 1)) } catch (e: Exception) { break }
                    }
                }
                i++
            }
            from = start + 1
        }
    }

    fun stripThink(s: String): String {
        val i = s.lastIndexOf("</think>")
        return (if (i >= 0) s.substring(i + 8) else s).trim()
    }

    fun unfence(s: String): String {
        var t = s.trim()
        if (t.startsWith("```")) {
            t = t.removePrefix("```")
            val nl = t.indexOf('\n')
            if (nl >= 0 && t.substring(0, nl).trim().all { it.isLetter() }) t = t.substring(nl + 1)
            val e = t.lastIndexOf("```")
            if (e >= 0) t = t.substring(0, e)
        }
        return t.trim()
    }

    class Call(val name: String, val args: JSONObject)

    /** A tool call is a reply that is only a JSON object like {"tool":"name","args":{...}}. */
    fun toolCall(content: String): Call? {
        val t = unfence(stripThink(content))
        if (!t.startsWith("{")) return null
        val j = firstJson(t) ?: return null
        val name = if (j.has("tool")) j.s("tool") else j.s("name")
        if (name.isEmpty()) return null
        val args = j.optJSONObject("args") ?: j.optJSONObject("arguments") ?: JSONObject().also { o ->
            for (k in j.keys()) if (k != "tool" && k != "name") o.put(k, j.get(k))
        }
        return Call(name, args)
    }
}
