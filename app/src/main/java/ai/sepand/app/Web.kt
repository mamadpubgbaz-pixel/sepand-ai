package ai.sepand.app

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/** Simple web access for the AI: search (DuckDuckGo) and reading a page as text. */
object Web {
    private fun get(url: String, maxBytes: Int = 700000): String {
        var u = url
        var hops = 0
        while (true) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 12000
            c.readTimeout = 15000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13; POCO X3 GT) SepandAI/0.1")
            c.setRequestProperty("Accept-Language", "en,fa;q=0.8")
            val code = c.responseCode
            if (code in 300..399 && hops++ < 6) {
                u = URL(URL(u), c.getHeaderField("Location") ?: return "").toString()
                c.disconnect()
                continue
            }
            val cs = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(c.contentType ?: "")?.groupValues?.get(1) ?: "UTF-8"
            val ins = (if (code >= 400) c.errorStream else c.inputStream) ?: return ""
            val out = java.io.ByteArrayOutputStream()
            ins.use {
                val buf = ByteArray(16384)
                while (out.size() < maxBytes) { val n = it.read(buf); if (n < 0) break; out.write(buf, 0, n) }
            }
            c.disconnect()
            return String(out.toByteArray(), try { charset(cs) } catch (e: Exception) { Charsets.UTF_8 })
        }
    }

    fun text(html: String): String = html.replace(Regex("(?is)<(script|style|noscript|svg|nav|footer|header).*?</\\1>"), " ")
        .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</h\\d>|</li>|</tr>"), "\n").replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        .replace(Regex("[ \\t]+"), " ").replace(Regex("\\s*\n\\s*"), "\n").trim()

    fun search(q: String): String {
        val html = get("https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(q, "UTF-8"))
        val re = Regex("<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>(?:.*?<a[^>]*class=\"result__snippet\"[^>]*>(.*?)</a>)?", RegexOption.DOT_MATCHES_ALL)
        val out = ArrayList<String>()
        for (m in re.findAll(html)) {
            var link = m.groupValues[1]
            if (link.contains("uddg=")) link = try { URLDecoder.decode(link.substringAfter("uddg=").substringBefore("&"), "UTF-8") } catch (e: Exception) { link }
            if (link.startsWith("//")) link = "https:$link"
            out.add("${out.size + 1}. " + text(m.groupValues[2]) + "\n   " + link + "\n   " + U.clip(text(m.groupValues[3]), 220))
            if (out.size >= 6) break
        }
        return if (out.isEmpty()) "No results (the search site may be blocking this phone for now)." else out.joinToString("\n")
    }

    fun fetch(url: String): String {
        val u = if (url.startsWith("http")) url else "https://$url"
        return U.clip(text(get(u)), 6000).ifEmpty { "(empty page)" }
    }
}
