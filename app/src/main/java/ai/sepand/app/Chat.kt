package ai.sepand.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

object ChatStore {
    private fun dir(ctx: Context) = File(ctx.filesDir, "chats").also { it.mkdirs() }

    fun list(ctx: Context): JSONArray {
        val items = dir(ctx).listFiles()?.filter { it.name.endsWith(".json") }?.mapNotNull { f ->
            try { JSONObject(f.readText()).let { U.jo("id" to it.s("id"), "title" to it.s("title"), "updated" to it.optLong("updated")) } } catch (e: Exception) { null }
        } ?: emptyList()
        return U.ja(items.sortedByDescending { it.optLong("updated") })
    }

    fun load(ctx: Context, id: String): JSONObject? = try { JSONObject(File(dir(ctx), "$id.json").readText()) } catch (e: Exception) { null }

    fun save(ctx: Context, c: JSONObject) {
        c.put("updated", U.now())
        File(dir(ctx), c.s("id") + ".json").writeText(c.toString())
    }

    fun delete(ctx: Context, id: String) { File(dir(ctx), "$id.json").delete() }
}

object ChatEngine {
    private val cancel = AtomicBoolean(false)
    @Volatile var busy = false

    fun stop() { cancel.set(true) }

    fun send(ctx: Context, chatId: String, text: String, attIds: List<String>) {
        if (busy) return
        busy = true
        cancel.set(false)
        U.pool.execute {
            try { run(ctx, chatId, text, attIds) } catch (e: Throwable) {
                Bus.emit("chat.error", U.jo("message" to (e.message ?: e.javaClass.simpleName)))
            } finally { busy = false }
        }
    }

    private fun opts(): LlamaClient.Opts {
        val o = LlamaClient.Opts()
        o.temp = Cfg.d("temp")
        o.topP = Cfg.d("topP")
        o.maxTokens = Cfg.i("maxTokens")
        if (Cfg.b("thinkOn")) {
            val lv = Cfg.i("thinkLevel")
            val t = Prompts.think(lv)
            o.effort = Prompts.effortFor(lv, LlamaServer.supportedEfforts)
            o.budget = t.budget
            o.maxTokens = (if (t.budget < 0) 3000 else t.budget) + Cfg.i("maxTokens")
        } else o.effort = "none"
        o.maxTokens = o.maxTokens.coerceIn(64, maxOf(64, Cfg.i("ctx") - 256))
        return o
    }

    private fun apiText(m: JSONObject): String {
        val sb = StringBuilder(m.s("content"))
        for (a in m.optJSONArray("atts")?.objs() ?: emptyList()) {
            when (a.s("kind")) {
                "text" -> if (a.s("text").isNotEmpty()) sb.append("\n\n[Attachment: ").append(a.s("name")).append("]\n").append(U.clip(a.s("text"), 1200))
                "binary" -> sb.append("\n\n[Attachment: ${a.s("name")}, not readable]")
                "image" -> sb.append("\n\n[Picture: ${a.s("name")}]")
            }
        }
        return sb.toString()
    }

    private fun run(ctx: Context, chatId: String, text: String, attIds: List<String>) {
        LlamaServer.ensure(ctx)?.let { Bus.emit("chat.error", U.jo("message" to it)); return }
        val chat = ChatStore.load(ctx, chatId) ?: JSONObject().put("id", chatId).put("title", "").put("created", U.now()).put("messages", JSONArray())
        val msgs = chat.getJSONArray("messages")
        val atts = attIds.mapNotNull { Attachments.get(it) }
        val userMsg = JSONObject().put("id", U.id()).put("role", "user").put("content", text)
            .put("atts", U.ja(atts.map { a -> a.meta().put("text", U.clip(a.text, 6000)) }))
        msgs.put(userMsg)
        val asstId = U.id()
        Bus.emit("chat.start", U.jo("chatId" to chatId, "userId" to userMsg.s("id"), "msgId" to asstId))

        // Obsidian memory: look up notes that match this message.
        var recall = emptyList<Vault.Hit>()
        val obs = Cfg.o("obs")
        if (obs.b("autoRecall", true) && Vault.connected() && text.isNotBlank()) {
            recall = try { Vault.search(ctx, text, obs.i("topK", 4).coerceIn(1, 8)) } catch (e: Exception) { emptyList() }
            if (recall.isNotEmpty()) Bus.emit("chat.recall", U.jo("msgId" to asstId, "notes" to U.ja(recall.map { U.jo("title" to it.title, "path" to it.path) })))
        }

        val tools = ToolBox.forChat(ctx)
        val sys = StringBuilder(Prompts.chatPrompt()).append("\n\nCurrent date and time: ").append(ToolBox.now())
        if (tools.size > 1) sys.append("\n\n").append(ToolBox.prompt(tools))
        if (recall.isNotEmpty()) {
            sys.append("\n\nNotes from the user's Obsidian vault (use them only if relevant, and name the note when you use it):")
            for (h in recall) sys.append("\n[[").append(h.path.removeSuffix(".md")).append("]] ").append(h.snippet)
        }

        val o = opts()
        val budgetChars = ((Cfg.i("ctx") - o.maxTokens - 250) * 2.4).toInt().coerceAtLeast(1500)
        // current user message (text + attachments)
        val textAtts = atts.count { it.kind == "text" }
        val share = ((budgetChars - sys.length - text.length - 300) / maxOf(1, textAtts)).coerceIn(800, 120000)
        val sb = StringBuilder(text)
        val parts = JSONArray()
        for (a in atts) when (a.kind) {
            "text" -> {
                sb.append("\n\n[Attachment: ${a.name}]\n").append(a.text.take(share))
                if (a.text.length > share) sb.append("\n[... cut; read the rest with the attachment_read tool]")
            }
            "image" -> if (LlamaServer.vision && a.b64.isNotEmpty())
                parts.put(U.jo("type" to "image_url", "image_url" to U.jo("url" to "data:image/jpeg;base64," + a.b64)))
            else sb.append("\n\n[Picture attached: ${a.name}. The loaded model cannot see pictures. Tell the user to load a vision model (Qwen3-VL) in Settings > Models.]")
            else -> sb.append("\n\n[Attachment: ${a.name} (${a.mime}, ${a.size} bytes). ${a.note}]")
        }
        val userContent: Any = if (parts.length() > 0) {
            val arr = JSONArray().put(U.jo("type" to "text", "text" to sb.toString()))
            for (i in 0 until parts.length()) arr.put(parts.get(i))
            arr
        } else sb.toString()

        // earlier messages, newest first until the context budget is used
        val hist = ArrayList<JSONObject>()
        var used = sb.length + sys.length
        for (i in msgs.length() - 2 downTo 0) {
            val m = msgs.getJSONObject(i)
            val c = apiText(m)
            if (used + c.length > budgetChars) break
            used += c.length
            hist.add(0, U.jo("role" to m.s("role"), "content" to c))
        }
        val messages = JSONArray().put(U.jo("role" to "system", "content" to sys.toString()))
        var lastRole = "system"
        for (h in hist) {
            if (h.s("role") == lastRole && messages.length() > 1) {
                val prev = messages.getJSONObject(messages.length() - 1)
                prev.put("content", prev.s("content") + "\n\n" + h.s("content"))
            } else messages.put(h)
            lastRole = h.s("role")
        }
        messages.put(JSONObject().put("role", "user").put("content", userContent))

        val client = LlamaServer.client()
        val toolLog = JSONArray()
        var reasoningAll = ""
        var final = ""
        var round = 0
        var last = LlamaClient.Result()
        while (true) {
            var decided = false
            var maybeTool = false
            val held = StringBuilder()
            val res = client.chat(messages, o, cancel) { kind, t ->
                if (kind == "reasoning") {
                    reasoningAll += t
                    Bus.emit("chat.delta", U.jo("msgId" to asstId, "kind" to "reasoning", "text" to t))
                } else if (!decided) {
                    held.append(t)
                    val tr = held.toString().trimStart()
                    if (tr.isNotEmpty()) {
                        decided = true
                        maybeTool = tools.size > 1 && (tr.startsWith("{") || tr.startsWith("```"))
                        if (!maybeTool) { Bus.emit("chat.delta", U.jo("msgId" to asstId, "kind" to "content", "text" to held.toString())); held.setLength(0) }
                    }
                } else if (!maybeTool) Bus.emit("chat.delta", U.jo("msgId" to asstId, "kind" to "content", "text" to t))
            }
            last = res
            if (res.error != null && res.content.isEmpty()) {
                ChatStore.save(ctx, chat)
                Bus.emit("chat.error", U.jo("message" to res.error))
                return
            }
            val content = Parse.stripThink(res.content)
            val call = if (tools.size > 1 && !cancel.get()) Parse.toolCall(content) else null
            if (call != null && round < 5) {
                round++
                val tool = tools.firstOrNull { it.name == call.name }
                Bus.emit("chat.tool", U.jo("msgId" to asstId, "name" to call.name, "args" to call.args, "status" to "running"))
                val result = if (tool == null) "ERROR: unknown tool ${call.name}" else try {
                    if (tool.risky && !Confirm.ask(ctx, "Let the AI use ${tool.name}?\n" + call.args)) "The user said no." else tool.run(call.args)
                } catch (e: Throwable) { "ERROR: " + (e.message ?: e.javaClass.simpleName) }
                toolLog.put(U.jo("name" to call.name, "args" to call.args, "result" to U.clip(result, 1500)))
                Bus.emit("chat.tool", U.jo("msgId" to asstId, "name" to call.name, "args" to call.args, "status" to "done", "result" to U.clip(result, 600)))
                messages.put(U.jo("role" to "assistant", "content" to content))
                messages.put(U.jo("role" to "user", "content" to "[Tool result: ${call.name}]\n" + U.clip(result, 5000) + "\n\nNow continue: call another tool or answer the user in plain text."))
                continue
            }
            final = content
            if (maybeTool) Bus.emit("chat.delta", U.jo("msgId" to asstId, "kind" to "content", "text" to content))
            break
        }

        val asst = JSONObject().put("id", asstId).put("role", "assistant").put("content", final).put("reasoning", reasoningAll)
            .put("tools", toolLog).put("recall", U.ja(recall.map { U.jo("title" to it.title, "path" to it.path) }))
            .put("stats", U.jo("tps" to last.tps, "ptps" to last.ptps, "tokens" to last.ctok)).put("stopped", last.finish == "cancelled")
        msgs.put(asst)
        if (chat.s("title").isEmpty()) {
            chat.put("title", U.clip((text.ifBlank { atts.firstOrNull()?.name ?: "Chat" }).replace('\n', ' '), 40))
            Bus.emit("chat.title", U.jo("chatId" to chatId, "title" to chat.s("title")))
        }
        ChatStore.save(ctx, chat)
        if (last.tps > 0) LlamaServer.lastTps = last.tps
        Bus.emit("chat.done", U.jo("msgId" to asstId, "tps" to last.tps, "ptps" to last.ptps, "tokens" to last.ctok, "stopped" to (last.finish == "cancelled")))
    }
}
