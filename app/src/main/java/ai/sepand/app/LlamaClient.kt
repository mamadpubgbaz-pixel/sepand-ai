package ai.sepand.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/** Talks to the local llama-server (OpenAI compatible API) over 127.0.0.1. */
class LlamaClient(private val base: String) {
    class Opts {
        var temp = 0.7
        var topP = 0.9
        var maxTokens = 1024
        var effort: String? = null      // "none" turns thinking off; otherwise the effort name for the template
        var budget = -1                 // max thinking tokens (-1 = unlimited)
        var schema: JSONObject? = null  // force JSON that matches this schema
        var stop: List<String> = emptyList()
    }

    class Result {
        var content = ""
        var reasoning = ""
        var finish = ""
        var error: String? = null
        var tps = 0.0
        var ptps = 0.0
        var ptok = 0
        var ctok = 0
    }

    private fun open(path: String, method: String = "GET", body: String? = null, readTimeout: Int = 10000): HttpURLConnection {
        val c = URL(base + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 5000
        c.readTimeout = readTimeout
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        return c
    }

    fun health(): Boolean = try {
        val c = open("/health")
        val ok = c.responseCode == 200
        c.disconnect()
        ok
    } catch (e: Exception) { false }

    fun props(): JSONObject? = try {
        val c = open("/props")
        val r = if (c.responseCode == 200) JSONObject(c.inputStream.bufferedReader().readText()) else null
        c.disconnect()
        r
    } catch (e: Exception) { null }

    fun control(id: String) {
        try {
            val c = open("/v1/chat/completions/control", "POST", JSONObject().put("id", id).put("action", "reasoning_end").toString(), 5000)
            c.responseCode
            c.disconnect()
        } catch (e: Exception) { }
    }

    fun chat(messages: JSONArray, o: Opts, cancel: AtomicBoolean, onDelta: (String, String) -> Unit): Result {
        val r = Result()
        val body = JSONObject()
            .put("model", "local").put("messages", messages).put("stream", true)
            .put("temperature", o.temp).put("top_p", o.topP).put("max_tokens", o.maxTokens)
            .put("cache_prompt", true).put("reasoning_format", "deepseek")
            .put("stream_options", JSONObject().put("include_usage", true))
        if (o.effort != null) body.put("reasoning_effort", o.effort)
        val thinking = o.effort != null && o.effort != "none"
        if (thinking && o.budget >= 0) body.put("reasoning_control", true)
        if (o.schema != null) body.put("response_format", JSONObject().put("type", "json_object").put("schema", o.schema))
        if (o.stop.isNotEmpty()) body.put("stop", JSONArray(o.stop))

        var conn: HttpURLConnection? = null
        var finished = false
        try {
            conn = open("/v1/chat/completions", "POST", body.toString(), 0)
            val watcher = conn
            Thread {
                while (!finished) {
                    if (cancel.get()) { try { watcher.disconnect() } catch (e: Exception) { }; break }
                    try { Thread.sleep(150) } catch (e: InterruptedException) { break }
                }
            }.also { it.isDaemon = true }.start()

            if (conn.responseCode != 200) {
                r.error = "HTTP ${conn.responseCode}: " + U.clip(conn.errorStream?.bufferedReader()?.readText().orEmpty(), 300)
                return r
            }
            val rd = conn.inputStream.bufferedReader(Charsets.UTF_8)
            var id = ""
            var rt = 0
            var ended = false
            while (true) {
                val line = rd.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val d = line.substring(5).trim()
                if (d == "[DONE]") break
                val j = try { JSONObject(d) } catch (e: Exception) { continue }
                if (id.isEmpty()) id = j.s("id")
                j.optJSONObject("error")?.let { r.error = it.s("message", "error") }
                if (r.error != null) break
                val ch = j.optJSONArray("choices")?.optJSONObject(0)
                val delta = ch?.optJSONObject("delta")
                if (delta != null) {
                    val rc = delta.s("reasoning_content")
                    if (rc.isNotEmpty()) { r.reasoning += rc; rt++; onDelta("reasoning", rc) }
                    val c = delta.s("content")
                    if (c.isNotEmpty()) { r.content += c; onDelta("content", c) }
                }
                val fin = ch?.s("finish_reason").orEmpty()
                if (fin.isNotEmpty()) r.finish = fin
                j.optJSONObject("timings")?.let {
                    r.tps = it.d("predicted_per_second"); r.ptps = it.d("prompt_per_second")
                    r.ptok = it.i("prompt_n"); r.ctok = it.i("predicted_n")
                }
                j.optJSONObject("usage")?.let {
                    if (r.ptok == 0) r.ptok = it.i("prompt_tokens")
                    if (r.ctok == 0) r.ctok = it.i("completion_tokens")
                }
                // Per-level thinking budget: when it is used up, tell the server to stop thinking and answer.
                if (thinking && o.budget >= 0 && !ended && rt >= o.budget && r.content.isEmpty() && id.isNotEmpty()) {
                    ended = true
                    val cid = id
                    U.pool.execute { control(cid) }
                }
            }
            if (cancel.get()) r.finish = "cancelled"
        } catch (e: Exception) {
            if (cancel.get()) r.finish = "cancelled" else r.error = e.message ?: e.javaClass.simpleName
        } finally {
            finished = true
            try { conn?.disconnect() } catch (e: Exception) { }
        }
        return r
    }
}
