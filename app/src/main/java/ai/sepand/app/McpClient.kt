package ai.sepand.app

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class McpTool(val name: String, val description: String, val schema: JSONObject)

/** Minimal MCP client: Streamable HTTP (and the older SSE transport as a fallback). */
class McpClient(val url: String, private val headers: Map<String, String>) {
    private class LegacyNeeded : Exception()

    @Volatile private var session: String? = null
    @Volatile private var inited = false
    @Volatile private var legacy = false
    @Volatile private var postUrl: String? = null
    @Volatile private var sse: HttpURLConnection? = null
    private var proto = "2025-06-18"
    private val ids = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, LinkedBlockingQueue<JSONObject>>()
    var serverName = ""

    fun connect(): String {
        val params = JSONObject().put("protocolVersion", proto).put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", "sepand-ai").put("version", "0.1"))
        val res = try { rpc("initialize", params) } catch (e: LegacyNeeded) { startLegacy(); rpc("initialize", params) }
        proto = res.s("protocolVersion", proto)
        serverName = res.optJSONObject("serverInfo")?.s("name") ?: ""
        inited = true
        try { post(if (legacy) postUrl!! else url, JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized"), null, 15000, "notify") } catch (e: Exception) { }
        return serverName
    }

    fun listTools(): List<McpTool> {
        val out = ArrayList<McpTool>()
        var cursor: String? = null
        do {
            val r = rpc("tools/list", if (cursor != null) JSONObject().put("cursor", cursor) else JSONObject())
            r.optJSONArray("tools")?.objs()?.forEach {
                out.add(McpTool(it.s("name"), it.s("description"), it.optJSONObject("inputSchema") ?: JSONObject()))
            }
            cursor = r.s("nextCursor").ifEmpty { null }
        } while (cursor != null)
        return out
    }

    fun callTool(name: String, args: JSONObject): String {
        val r = rpc("tools/call", JSONObject().put("name", name).put("arguments", args), 120000)
        val sb = StringBuilder()
        r.optJSONArray("content")?.objs()?.forEach { c ->
            when (c.s("type")) {
                "text" -> sb.append(c.s("text")).append('\n')
                "image" -> sb.append("[image]\n")
                "resource" -> sb.append(c.optJSONObject("resource")?.let { it.s("text", it.s("uri")) } ?: "").append('\n')
                else -> sb.append(c.toString()).append('\n')
            }
        }
        if (sb.isEmpty() && r.has("structuredContent")) sb.append(r.get("structuredContent").toString())
        val text = sb.toString().trim()
        return if (r.b("isError")) "ERROR: $text" else text
    }

    fun close() { try { sse?.disconnect() } catch (e: Exception) { } }

    private fun rpc(method: String, params: JSONObject?, timeout: Int = 60000): JSONObject {
        val id = ids.getAndIncrement()
        val body = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method)
        if (params != null) body.put("params", params)
        val resp: JSONObject? = if (legacy) {
            val q = LinkedBlockingQueue<JSONObject>()
            pending[id] = q
            try {
                post(postUrl!!, body, null, timeout, method)
                q.poll(timeout.toLong(), TimeUnit.MILLISECONDS)
            } finally { pending.remove(id) }
        } else post(url, body, id, timeout, method)
        if (resp == null) throw IOException("No answer to $method")
        resp.optJSONObject("error")?.let { throw IOException(it.s("message", "MCP error")) }
        return resp.optJSONObject("result") ?: JSONObject()
    }

    private fun post(target: String, body: JSONObject, expectId: Long?, timeout: Int, method: String): JSONObject? {
        val c = URL(target).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 10000
            c.readTimeout = timeout
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Accept", "application/json, text/event-stream")
            session?.let { c.setRequestProperty("Mcp-Session-Id", it) }
            if (inited) c.setRequestProperty("MCP-Protocol-Version", proto)
            for ((k, v) in headers) c.setRequestProperty(k, v)
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            c.getHeaderField("Mcp-Session-Id")?.let { session = it }
            if (code == 202 || code == 204) return null
            if (code >= 400) {
                val err = c.errorStream?.bufferedReader()?.readText().orEmpty()
                if (method == "initialize" && !legacy && (code == 404 || code == 405 || code == 400)) throw LegacyNeeded()
                throw IOException("HTTP $code " + U.clip(err, 200))
            }
            if (expectId == null) return null
            val ct = c.contentType ?: ""
            if (ct.contains("text/event-stream")) return readSse(c, expectId)
            return JSONObject(c.inputStream.bufferedReader().readText())
        } finally {
            c.disconnect()
        }
    }

    private fun readSse(c: HttpURLConnection, expectId: Long): JSONObject? {
        val rd = c.inputStream.bufferedReader(Charsets.UTF_8)
        val data = StringBuilder()
        fun take(): JSONObject? {
            if (data.isEmpty()) return null
            val j = try { JSONObject(data.toString()) } catch (e: Exception) { null }
            data.setLength(0)
            return if (j != null && (j.has("result") || j.has("error")) && j.optLong("id", -1) == expectId) j else null
        }
        while (true) {
            val line = rd.readLine() ?: break
            if (line.isEmpty()) take()?.let { return it }
            else if (line.startsWith("data:")) data.append(line.substring(5).trim())
        }
        return take()
    }

    private fun startLegacy() {
        legacy = true
        val ready = CountDownLatch(1)
        val t = Thread {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                sse = c
                c.setRequestProperty("Accept", "text/event-stream")
                for ((k, v) in headers) c.setRequestProperty(k, v)
                c.connectTimeout = 10000
                c.readTimeout = 0
                val rd = c.inputStream.bufferedReader(Charsets.UTF_8)
                var ev = ""
                val data = StringBuilder()
                while (true) {
                    val line = rd.readLine() ?: break
                    if (line.isEmpty()) {
                        val d = data.toString()
                        data.setLength(0)
                        if (ev == "endpoint") {
                            postUrl = URL(URL(url), d.trim()).toString()
                            ready.countDown()
                        } else if (d.isNotEmpty()) {
                            try {
                                val j = JSONObject(d)
                                if (j.has("id")) pending[j.optLong("id", -1)]?.offer(j)
                            } catch (e: Exception) { }
                        }
                        ev = ""
                    } else if (line.startsWith("event:")) ev = line.substring(6).trim()
                    else if (line.startsWith("data:")) data.append(line.substring(5).trim())
                }
            } catch (e: Exception) { }
        }
        t.isDaemon = true
        t.start()
        if (!ready.await(10, TimeUnit.SECONDS)) {
            legacy = false
            throw IOException("The MCP server did not answer")
        }
    }
}

/** Keeps the connected MCP servers from settings and exposes their tools to the AI. */
object McpHub {
    class Entry(val serverId: String, val serverName: String, val fullName: String, val tool: McpTool)

    private val clients = HashMap<String, McpClient>()
    private val cache = HashMap<String, List<McpTool>>()
    private val failed = HashMap<String, Long>()
    private val index = HashMap<String, Pair<JSONObject, String>>()

    private fun key(s: JSONObject) = s.s("id") + "|" + s.s("url") + "|" + (s.optJSONObject("headers")?.toString() ?: "")

    @Synchronized private fun client(s: JSONObject): McpClient = clients.getOrPut(key(s)) {
        val h = HashMap<String, String>()
        s.optJSONObject("headers")?.let { ho -> for (k in ho.keys()) h[k] = ho.optString(k) }
        McpClient(s.s("url"), h)
    }

    /** Connects fresh and lists tools (used by the Test button and on first use). */
    fun test(s: JSONObject): List<McpTool> {
        synchronized(this) { clients.remove(key(s))?.close() }
        val c = client(s)
        c.connect()
        val tools = c.listTools()
        synchronized(this) { cache[key(s)] = tools; failed.remove(key(s)) }
        return tools
    }

    fun entries(): List<Entry> {
        val out = ArrayList<Entry>()
        for (s in Cfg.a("mcp").objs()) {
            if (!s.b("enabled", true)) continue
            val k = key(s)
            var tools = synchronized(this) { cache[k] }
            if (tools == null) {
                val f = synchronized(this) { failed[k] }
                if (f != null && U.now() - f < 60000) continue
                tools = try { test(s) } catch (e: Exception) { synchronized(this) { failed[k] = U.now() }; continue }
            }
            val off = s.optJSONArray("off")?.strs() ?: emptyList()
            val sname = s.s("name").ifEmpty { "server" }
            for (t in tools) {
                if (t.name in off) continue
                val full = "mcp_" + U.slug(sname) + "_" + U.slug(t.name)
                synchronized(this) { index[full] = s to t.name }
                out.add(Entry(s.s("id"), sname, full, t))
            }
        }
        return out
    }

    fun call(fullName: String, args: JSONObject): String {
        val (s, tool) = synchronized(this) { index[fullName] } ?: return "ERROR: unknown MCP tool $fullName"
        return try {
            client(s).callTool(tool, args)
        } catch (e: Exception) {
            try {
                test(s)
                client(s).callTool(tool, args)
            } catch (e2: Exception) { "ERROR: " + (e2.message ?: e2.javaClass.simpleName) }
        }
    }

    fun forget() = synchronized(this) { clients.values.forEach { it.close() }; clients.clear(); cache.clear(); failed.clear(); index.clear() }
}
