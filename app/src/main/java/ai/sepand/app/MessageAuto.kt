package ai.sepand.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Automatic message replies: draft, ask first, or send. Works through notification reply buttons. */
object MessageAuto {
    private val exec = Executors.newSingleThreadExecutor()
    private val seen = LinkedHashSet<String>()
    private val perSender = HashMap<String, ArrayList<Long>>()
    private val log = ArrayList<JSONObject>()

    fun events(): JSONArray = synchronized(log) { U.ja(log.toList()) }

    private fun event(kind: String, who: String, text: String) {
        val o = U.jo("t" to U.now(), "kind" to kind, "who" to who, "text" to U.clip(text, 240))
        synchronized(log) { log.add(0, o); while (log.size > 50) log.removeAt(log.size - 1) }
        Bus.emit("msg.event", o)
    }

    private fun quiet(c: JSONObject): Boolean {
        val f = c.s("quietFrom")
        val t = c.s("quietTo")
        if (f.isEmpty() || t.isEmpty()) return false
        fun m(s: String) = (s.substringBefore(':').toIntOrNull() ?: 0) * 60 + (s.substringAfter(':', "0").toIntOrNull() ?: 0)
        val cal = Calendar.getInstance()
        val now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val a = m(f)
        val b = m(t)
        return if (a <= b) now in a until b else now >= a || now < b
    }

    fun onMessage(ctx: Context, it: NotifService.Item) {
        val c = Cfg.o("msg")
        val mode = c.s("mode")
        if (mode == "off" || it.action == null) return
        if (it.pkg !in (c.optJSONArray("apps")?.strs() ?: emptyList())) return
        if (it.group && !c.b("groups")) return
        val contacts = c.optJSONArray("contacts")?.strs()?.filter { s -> s.isNotBlank() } ?: emptyList()
        if (contacts.isNotEmpty() && contacts.none { s -> it.title.contains(s.trim(), true) }) return
        if (quiet(c)) return
        val sig = it.key + "|" + it.text.hashCode()
        synchronized(seen) {
            if (!seen.add(sig)) return
            if (seen.size > 300) seen.remove(seen.first())
        }
        val limit = c.i("maxPerHour", 6)
        synchronized(perSender) {
            val l = perSender.getOrPut(it.title) { ArrayList() }
            l.removeAll { t -> U.now() - t > 3600000 }
            if (l.size >= limit) { event("skipped", it.title, "reply limit per hour reached"); return }
            l.add(U.now())
        }
        exec.execute { try { process(ctx, it, c, mode) } catch (e: Throwable) { event("error", it.title, e.message ?: e.javaClass.simpleName) } }
    }

    private fun process(ctx: Context, it: NotifService.Item, c: JSONObject, mode: String) {
        event("incoming", it.title, it.text)
        if (!c.b("autoStart", true) && LlamaServer.state != "ready") { event("skipped", it.title, "the AI engine is not running"); return }
        LlamaServer.ensure(ctx)?.let { e -> event("error", it.title, e); return }
        val sys = StringBuilder(Prompts.chatPrompt(c.s("promptId").ifEmpty { "reply" })).append("\n\nCurrent date and time: ").append(ToolBox.now())
        if (Cfg.o("obs").b("autoRecall", true) && Vault.connected()) {
            val h = try { Vault.search(ctx, it.title + " " + it.text, 3) } catch (e: Exception) { emptyList() }
            if (h.isNotEmpty()) {
                sys.append("\n\nNotes about the owner and their contacts (use only if useful):")
                for (x in h) sys.append("\n- ").append(x.title).append(": ").append(x.snippet)
            }
        }
        val convo = (if (it.history.isNotEmpty()) it.history.takeLast(6) else listOf(it.title + ": " + it.text)).joinToString("\n")
        val messages = JSONArray().put(U.jo("role" to "system", "content" to sys.toString()))
            .put(U.jo("role" to "user", "content" to "App: ${it.app}\nConversation with ${it.title}:\n$convo\n\nWrite the owner's reply to the last message."))
        val o = LlamaClient.Opts().also { x -> x.temp = 0.6; x.maxTokens = 200; x.effort = "none" }
        val r = LlamaServer.client().chat(messages, o, AtomicBoolean(false)) { _, _ -> }
        val reply = Parse.stripThink(r.content).trim().trim('"', '“', '”')
        if (reply.isEmpty() || r.error != null) { event("error", it.title, r.error ?: "empty reply"); return }
        when (mode) {
            "draft" -> { Notif.info(ctx, "Draft reply to ${it.title}", reply); event("draft", it.title, reply) }
            "ask" -> if (Confirm.ask(ctx, "Reply to ${it.title} (${it.app})?\n\n\"$reply\"")) send(ctx, it, reply) else event("denied", it.title, reply)
            else -> send(ctx, it, reply)
        }
    }

    private fun send(ctx: Context, it: NotifService.Item, reply: String) {
        if (NotifService.send(ctx, it, reply)) event("sent", it.title, reply) else event("error", it.title, "could not send (the notification is gone)")
    }
}
