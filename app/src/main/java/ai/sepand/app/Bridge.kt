package ai.sepand.app

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** The only door between the web UI and the phone. The UI calls Android.call(method, json, id). */
class Bridge(private val act: MainActivity) {
    companion object {
        val PENDING = Any()

        fun perms(ctx: Context): JSONObject {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            val post = if (Build.VERSION.SDK_INT >= 33) ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED else true
            return U.jo(
                "a11y" to (AgentAccessibilityService.instance != null),
                "a11yOn" to AgentAccessibilityService.enabled(ctx),
                "notif" to (NotifService.instance != null || NotifService.enabled(ctx)),
                "post" to post,
                "battery" to pm.isIgnoringBatteryOptimizations(ctx.packageName),
                "files" to (Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager())
            )
        }
    }

    private val dls = ConcurrentHashMap<String, AtomicBoolean>()

    @JavascriptInterface
    fun call(method: String, args: String, cb: Int) {
        U.pool.execute {
            try {
                val a = try { JSONObject(args) } catch (e: Exception) { JSONObject() }
                val r = handle(method, a, cb)
                if (r !== PENDING) reply(cb, true, r)
            } catch (e: Throwable) {
                reply(cb, false, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun reply(cb: Int, ok: Boolean, v: Any?) {
        act.js("window.__cb($cb,$ok,${JSONObject.quote(v?.toString() ?: "null")})")
    }

    fun modelsJson(): JSONArray {
        val sel = Cfg.s("modelId")
        return U.ja(ModelStore.list(act.applicationContext).map { it.toJson().put("active", it.id == sel) })
    }

    private fun init(): JSONObject {
        val ctx = act.applicationContext
        val mi = ActivityManager.MemoryInfo()
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        return U.jo(
            "cfg" to Cfg.all(), "models" to modelsJson(), "catalog" to ModelStore.catalogJson(), "engine" to LlamaServer.status(),
            "gpu" to LlamaServer.gpuAvailable(ctx), "cpuEngine" to (LlamaServer.binary(ctx, false) != null),
            "ramMB" to mi.totalMem / 1048576, "perms" to perms(ctx), "vault" to Vault.status(), "version" to "0.1.0",
            "device" to Build.MODEL, "sdk" to Build.VERSION.SDK_INT, "locale" to Locale.getDefault().language, "tps" to LlamaServer.lastTps,
            "defPrompts" to Prompts.builtinPresets(), "defAgent" to Prompts.AGENT
        )
    }

    private fun download(a: JSONObject) {
        val ctx = act.applicationContext
        val catId = a.s("catalogId")
        val url = a.s("url")
        val id = if (catId.isNotEmpty()) catId else url
        if (id.isEmpty() || dls.containsKey(id)) return
        val cancel = AtomicBoolean(false)
        dls[id] = cancel
        U.pool.execute {
            try {
                Bus.emit("dl", U.jo("id" to id, "state" to "run", "done" to 0, "total" to 0))
                val prog: (Long, Long) -> Unit = { d, t -> Bus.emit("dl", U.jo("id" to id, "state" to "run", "done" to d, "total" to t)) }
                val e = if (catId.isNotEmpty()) ModelStore.installCatalog(ctx, ModelStore.CATALOG.first { it.id == catId }, prog, cancel)
                else ModelStore.fromUrl(ctx, url, prog, cancel)
                if (Cfg.s("modelId").isEmpty()) Cfg.set("modelId", e.id)
                Bus.emit("dl", U.jo("id" to id, "state" to "done", "model" to e.id))
            } catch (e: CancellationException) {
                Bus.emit("dl", U.jo("id" to id, "state" to "cancel"))
            } catch (e: Throwable) {
                Bus.emit("dl", U.jo("id" to id, "state" to "error", "message" to (e.message ?: e.javaClass.simpleName)))
            } finally { dls.remove(id) }
        }
    }

    private fun startEngine(a: JSONObject) {
        val ctx = act.applicationContext
        val id = a.s("modelId").ifEmpty { Cfg.s("modelId") }
        if (id.isNotEmpty()) Cfg.set("modelId", id)
        U.pool.execute {
            val m = ModelStore.list(ctx).firstOrNull { it.id == id }
            if (m == null) Bus.emit("engine", U.jo("state" to "error", "message" to "Pick or download a model first."))
            else LlamaServer.start(ctx, m)
        }
    }

    private fun handle(m: String, a: JSONObject, cb: Int): Any? {
        val ctx = act.applicationContext
        return when (m) {
            "init" -> init()
            "cfg.set" -> {
                Cfg.set(a.s("key"), a.opt("value"))
                when (a.s("key")) { "mcp" -> McpHub.forget(); "theme" -> act.applyTheme(Cfg.s("theme")) }
                JSONObject()
            }
            "models.list" -> modelsJson()
            "models.download" -> { download(a); JSONObject() }
            "models.cancel" -> { dls[a.s("id")]?.set(true); JSONObject() }
            "models.pick" -> { act.pickModels(cb); PENDING }
            "models.remove" -> {
                ModelStore.remove(ctx, a.s("id"))
                if (Cfg.s("modelId") == a.s("id")) Cfg.set("modelId", "")
                modelsJson()
            }
            "models.select" -> { Cfg.set("modelId", a.s("id")); JSONObject() }
            "engine.start" -> { startEngine(a); JSONObject() }
            "engine.stop" -> { LlamaServer.stop(ctx); JSONObject() }
            "engine.status" -> LlamaServer.status()
            "engine.logs" -> U.jo("lines" to U.ja(LlamaServer.logLines().takeLast(150)))
            "chat.list" -> ChatStore.list(ctx)
            "chat.load" -> ChatStore.load(ctx, a.s("id")) ?: JSONObject()
            "chat.send" -> { ChatEngine.send(ctx, a.s("chatId"), a.s("text"), a.optJSONArray("atts")?.strs() ?: emptyList()); JSONObject() }
            "chat.stop" -> { ChatEngine.stop(); JSONObject() }
            "chat.del" -> { ChatStore.delete(ctx, a.s("id")); JSONObject() }
            "chat.rename" -> { ChatStore.load(ctx, a.s("id"))?.let { it.put("title", a.s("title")); ChatStore.save(ctx, it) }; JSONObject() }
            "chat.note" -> U.jo("path" to Vault.save(ctx, a.s("title").ifEmpty { "Note" }, a.s("content"), emptyList()))
            "att.pick" -> { act.pickFiles(cb); PENDING }
            "att.drop" -> { Attachments.drop(a.s("id")); JSONObject() }
            "agent.run" -> { AgentRunner.start(ctx, a.s("goal")); JSONObject() }
            "agent.stop" -> { AgentRunner.stop(); JSONObject() }
            "confirm" -> { Confirm.answer(a.s("id"), a.b("ok")); JSONObject() }
            "msg.log" -> MessageAuto.events()
            "perm.status" -> perms(ctx)
            "perm.open" -> { act.openPerm(a.s("which")); JSONObject() }
            "obs.pick" -> { act.pickVault(cb); PENDING }
            "obs.status" -> Vault.status()
            "obs.search" -> U.ja(Vault.search(ctx, a.s("q"), 8, "vault").map { U.jo("path" to it.path, "title" to it.title, "snippet" to it.snippet) })
            "obs.read" -> U.jo("text" to (Vault.read(ctx, a.s("path")) ?: ""))
            "obs.reindex" -> { U.pool.execute { Vault.refresh(ctx) }; JSONObject() }
            "obs.off" -> { Vault.disconnect(ctx); Vault.status() }
            "mcp.test" -> {
                val tools = McpHub.test(a)
                U.jo("ok" to true, "tools" to U.ja(tools.map { U.jo("name" to it.name, "description" to U.clip(it.description, 160)) }))
            }
            "ui.theme" -> { act.applyTheme(a.s("theme")); JSONObject() }
            "ui.open" -> { act.openUrl(a.s("url")); JSONObject() }
            "ui.copy" -> { act.copy(a.s("text")); JSONObject() }
            else -> throw IllegalArgumentException("Unknown method: $m")
        }
    }
}
