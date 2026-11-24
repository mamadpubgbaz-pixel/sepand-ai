package ai.sepand.app

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject

/** Phone actions shared by the Agent and by chat tools. */
object Phone {
    const val NEEDS_A11Y = "Accessibility is off. Open the Agent tab > Permissions and turn it on for Sepand AI."

    private fun svc() = AgentAccessibilityService.instance

    private fun start(ctx: Context, i: Intent): String {
        return try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val c: Context = svc() ?: ctx
            c.startActivity(i)
            "ok"
        } catch (e: Exception) { "Could not open it: " + (e.message ?: e.javaClass.simpleName) }
    }

    fun openApp(ctx: Context, name: String): String {
        val pm = ctx.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(main, 0).map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        val q = Txt.norm(name).trim()
        val hit = apps.firstOrNull { Txt.norm(it.first) == q || it.second.equals(name, true) }
            ?: apps.firstOrNull { Txt.norm(it.first).contains(q) }
            ?: apps.firstOrNull { q.length > 3 && q.contains(Txt.norm(it.first)) && it.first.length > 2 }
            ?: apps.firstOrNull { it.second.contains(q, true) }
            ?: return "No app named \"$name\". Some installed apps: " + apps.take(12).joinToString { it.first }
        val li = pm.getLaunchIntentForPackage(hit.second) ?: return "Cannot open ${hit.first}."
        val r = start(ctx, li)
        return if (r == "ok") "opened ${hit.first}" else r
    }

    fun openUrl(ctx: Context, url: String): String = start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(if (url.startsWith("http")) url else "https://$url")))

    fun webSearch(ctx: Context, q: String): String {
        val r = start(ctx, Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q))
        return if (r == "ok") r else start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))))
    }

    /** One agent action. Returns a short result for the AI. */
    fun exec(ctx: Context, act: String, a: JSONObject): String {
        val s = svc()
        val id = if (a.has("id") && !a.isNull("id")) a.optInt("id", -1) else -1
        val target = a.s("target").ifEmpty { a.s("text") }
        return when (act) {
            "open_app" -> openApp(ctx, a.s("target").ifEmpty { a.s("app") })
            "open_url" -> openUrl(ctx, a.s("target").ifEmpty { a.s("url") })
            "web_search" -> webSearch(ctx, a.s("target").ifEmpty { a.s("query") })
            "wait" -> { Thread.sleep(2000); "waited" }
            "read_notifications" -> NotifService.describe()
            "reply" -> NotifService.reply(ctx, a.i("id", 1), a.s("text"))
            else -> if (s == null) NEEDS_A11Y else when (act) {
                "tap" -> s.tap(id)
                "long_press" -> s.tap(id, true)
                "type" -> s.typeText(if (id >= 0) id else null, a.s("text"))
                "scroll" -> s.swipe(a.s("dir").ifEmpty { "down" }, true)
                "swipe" -> s.swipe(a.s("dir").ifEmpty { "up" }, false)
                "back", "home", "recents", "notifications", "quick_settings" -> s.global(act)
                "read_screen" -> s.snapshot()
                else -> "Unknown action \"$act\"."
            }
        }
    }

    fun tools(ctx: Context): List<Tool> = listOf(
        Tool("read_screen", "{}", "read the phone screen as numbered elements") { exec(ctx, "read_screen", it) },
        Tool("open_app", "{app}", "open an app by name") { exec(ctx, "open_app", it) },
        Tool("open_url", "{url}", "open a web address in the browser") { exec(ctx, "open_url", it) },
        Tool("tap", "{id}", "tap element [id] from read_screen", true) { exec(ctx, "tap", it) },
        Tool("type_text", "{id,text}", "type text into input element [id]") { exec(ctx, "type", it) },
        Tool("scroll", "{dir}", "scroll down|up|left|right") { exec(ctx, "scroll", it) },
        Tool("press", "{key}", "press back|home|recents") { exec(ctx, it.s("key"), it) },
        Tool("read_notifications", "{}", "list recent message notifications") { exec(ctx, "read_notifications", it) },
        Tool("reply_notification", "{id,text}", "send a reply to message notification number [id]", true) { exec(ctx, "reply", it) }
    )
}
