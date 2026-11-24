package ai.sepand.app

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Tool(val name: String, val sig: String, val desc: String, val risky: Boolean = false, val run: (JSONObject) -> String)

/** The tools the AI can call while chatting: Obsidian memory, web, attachments, MCP servers and (optionally) the phone. */
object ToolBox {
    fun now(): String = SimpleDateFormat("EEEE, yyyy-MM-dd HH:mm", Locale.US).format(Date())

    private fun sig(t: McpTool): String {
        val props = t.schema.optJSONObject("properties") ?: return "{}"
        val req = t.schema.optJSONArray("required")?.strs() ?: emptyList()
        return "{" + props.keys().asSequence().joinToString(",") { k -> k + (if (k in req) "" else "?") + ":" + (props.optJSONObject(k)?.s("type") ?: "any") } + "}"
    }

    fun forChat(ctx: Context): List<Tool> {
        val cfg = Cfg.o("tools")
        val obs = Cfg.o("obs")
        val l = ArrayList<Tool>()
        l += Tool("datetime", "{}", "the current date and time") { now() }
        if (cfg.b("memory", true) && Vault.connected()) {
            l += Tool("memory_search", "{query}", "search the user's Obsidian notes") { a ->
                val h = Vault.search(ctx, a.s("query"), 5)
                if (h.isEmpty()) "No matching notes." else h.joinToString("\n") { "[[" + it.path.removeSuffix(".md") + "]] " + it.snippet }
            }
            l += Tool("memory_read", "{path}", "read a whole note by path or title") { a -> Vault.read(ctx, a.s("path")) ?: "Note not found." }
            if (obs.b("autoSave", true)) {
                l += Tool("memory_write", "{title,content,tags?}", "save a lasting fact as a note in the user's Obsidian memory folder (appends if the title exists)") { a ->
                    "Saved to " + Vault.save(ctx, a.s("title").ifEmpty { "Note" }, a.s("content"), a.optJSONArray("tags")?.strs() ?: emptyList())
                }
            }
        }
        if (cfg.b("web", true)) {
            l += Tool("web_search", "{query}", "search the web") { a -> Web.search(a.s("query")) }
            l += Tool("fetch_url", "{url}", "read a web page as text") { a -> Web.fetch(a.s("url")) }
        }
        if (Attachments.any()) {
            l += Tool("attachment_read", "{name,offset?}", "read more of an attached file (6000 characters per call)") { a ->
                val at = Attachments.byName(a.s("name"))
                if (at == null) "No such attachment. Ask the user to attach it again." else {
                    val off = a.i("offset").coerceAtLeast(0)
                    if (off >= at.text.length) "End of file." else at.text.substring(off, minOf(at.text.length, off + 6000)) +
                        (if (off + 6000 < at.text.length) "\n[more: offset ${off + 6000}]" else "")
                }
            }
        }
        if (cfg.b("phone")) l += Phone.tools(ctx)
        try {
            for (e in McpHub.entries()) l += Tool(e.fullName, sig(e.tool), U.clip(e.tool.description.replace('\n', ' '), 140)) { a -> McpHub.call(e.fullName, a) }
        } catch (e: Exception) { }
        return l
    }

    fun prompt(tools: List<Tool>): String {
        val sb = StringBuilder("You can use tools. To use one, reply with ONLY one JSON object and nothing else:\n")
        sb.append("{\"tool\":\"NAME\",\"args\":{...}}\n")
        sb.append("You then receive the result and may call another tool, or answer the user in plain text (no JSON). Only call a tool when it really helps.\nTools:\n")
        for (t in tools) sb.append("- ").append(t.name).append(' ').append(t.sig).append(": ").append(t.desc).append('\n')
        if (tools.any { it.name == "memory_search" }) {
            sb.append("\nMemory: the user's notes live in Obsidian. Before answering about the user's life, projects, people or preferences, check them with memory_search if no note was given below. ")
            if (tools.any { it.name == "memory_write" }) sb.append("When the user tells you something lasting (a preference, plan, fact about a person or project) save it with memory_write without asking, one short note per topic.")
        }
        return sb.toString()
    }
}
