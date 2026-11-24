package ai.sepand.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/** The phone agent: look at the screen, ask the AI for the next action, do it, repeat. */
object AgentRunner {
    @Volatile var running = false
    private val stopFlag = AtomicBoolean(false)
    private val RISKY = Regex("(send|post|publish|submit|pay|buy|purchase|order|delete|remove|confirm|ارسال|پرداخت|خرید|حذف|تایید|تأیید)", RegexOption.IGNORE_CASE)

    private val SCHEMA = JSONObject(
        """{"type":"object","properties":{
        "thought":{"type":"string","maxLength":160},
        "action":{"type":"string","enum":["tap","long_press","type","scroll","swipe","back","home","recents","open_app","open_url","web_search","wait","read_notifications","reply","recall","remember","done"]},
        "id":{"type":"integer"},
        "text":{"type":"string","maxLength":400},
        "dir":{"type":"string","enum":["up","down","left","right"]},
        "target":{"type":"string","maxLength":160}},
        "required":["thought","action"],"additionalProperties":false}"""
    )

    fun stop() { stopFlag.set(true) }

    fun start(ctx: Context, goal: String) {
        if (running) return
        running = true
        stopFlag.set(false)
        U.pool.execute {
            try { loop(ctx, goal) } catch (e: Throwable) {
                Bus.emit("agent.status", U.jo("state" to "error", "text" to (e.message ?: e.javaClass.simpleName)))
            } finally {
                running = false
                AgentAccessibilityService.instance?.hideStatus()
                Bus.emit("agent.status", U.jo("state" to "idle", "text" to ""))
            }
        }
    }

    private fun fail(msg: String) { Bus.emit("agent.status", U.jo("state" to "error", "text" to msg)) }

    private fun loop(ctx: Context, goal: String) {
        val svc = AgentAccessibilityService.instance ?: return fail(Phone.NEEDS_A11Y)
        LlamaServer.ensure(ctx)?.let { return fail(it) }
        val maxSteps = Cfg.o("agent").i("maxSteps", 20).coerceIn(1, 60)
        val confirmRisky = Cfg.o("agent").b("confirmRisky", true)
        var notes = ""
        if (Vault.connected() && Cfg.o("obs").b("autoRecall", true)) {
            notes = try { Vault.search(ctx, goal, 3).joinToString("\n") { "- ${it.title}: ${it.snippet}" } } catch (e: Exception) { "" }
        }
        Bus.emit("agent.status", U.jo("state" to "running", "text" to goal))
        val history = ArrayList<String>()
        val client = LlamaServer.client()
        val system = U.jo("role" to "system", "content" to Prompts.agentPrompt())
        var lastSig = ""
        var repeats = 0
        var finished = false

        for (step in 1..maxSteps) {
            if (stopFlag.get()) break
            svc.showStatus("Sepand · step $step")
            svc.settle(2500, 450)
            val screen = svc.snapshot()
            val user = StringBuilder("TASK: ").append(goal).append('\n')
            if (notes.isNotEmpty()) user.append("USER NOTES:\n").append(notes).append('\n')
            if (history.isNotEmpty()) user.append("STEPS SO FAR:\n").append(history.takeLast(8).joinToString("\n")).append('\n')
            if (repeats >= 2) user.append("WARNING: you are repeating yourself. Try a different action, or finish with done.\n")
            user.append("CURRENT SCREEN:\n").append(screen).append("Reply with the next action as JSON.")
            val messages = JSONArray().put(system).put(U.jo("role" to "user", "content" to user.toString()))
            val o = LlamaClient.Opts().also { it.temp = 0.2; it.maxTokens = 160; it.effort = "none"; it.schema = SCHEMA }
            val res = client.chat(messages, o, stopFlag) { _, _ -> }
            if (stopFlag.get()) break
            if (res.error != null) return fail(res.error!!)
            val j = Parse.firstJson(res.content)
            if (j == null) { history.add("$step. (the AI sent an invalid answer)"); continue }

            val act = j.s("action")
            val thought = j.s("thought")
            val id = if (j.has("id") && !j.isNull("id")) j.optInt("id", -1) else -1
            val arg = when {
                id >= 0 && j.s("text").isNotEmpty() -> "[$id] \"${U.clip(j.s("text"), 40)}\""
                id >= 0 -> "[$id]"
                j.s("target").isNotEmpty() -> "\"${U.clip(j.s("target"), 50)}\""
                j.s("text").isNotEmpty() -> "\"${U.clip(j.s("text"), 50)}\""
                j.s("dir").isNotEmpty() -> j.s("dir")
                else -> ""
            }
            val sig = "$act $arg ${screen.hashCode()}"
            repeats = if (sig == lastSig) repeats + 1 else 0
            lastSig = sig

            var result: String
            if (act == "done") {
                result = "done"
                Bus.emit("agent.step", U.jo("n" to step, "thought" to thought, "action" to act, "arg" to arg, "result" to ""))
                Bus.emit("agent.done", U.jo("text" to j.s("text").ifEmpty { thought }))
                finished = true
                break
            }
            // ask before anything that sends, buys or deletes
            val label = if (id >= 0) svc.labelOf(id) else ""
            val risky = act == "reply" || ((act == "tap" || act == "long_press") && RISKY.containsMatchIn(label))
            if (confirmRisky && risky) {
                val what = if (act == "reply") "Send this reply?\n\"${j.s("text")}\"" else "Tap \"$label\"?"
                if (!Confirm.ask(ctx, what)) { result = "The user said no."; history.add("$step. $act $arg -> $result"); Bus.emit("agent.step", U.jo("n" to step, "thought" to thought, "action" to act, "arg" to arg, "result" to result)); continue }
            }
            result = when (act) {
                "recall" -> try {
                    val h = Vault.search(ctx, j.s("target").ifEmpty { j.s("text") }, 3)
                    if (h.isEmpty()) "No matching notes." else h.joinToString("\n") { "${it.title}: ${it.snippet}" }
                } catch (e: Exception) { "Obsidian is not connected." }
                "remember" -> try { "saved to " + Vault.save(ctx, U.clip(j.s("text"), 40), j.s("text")) } catch (e: Exception) { "Obsidian is not connected." }
                else -> Phone.exec(ctx, act, j)
            }
            history.add("$step. $act $arg -> ${U.clip(result.replace('\n', ' '), 140)}")
            Bus.emit("agent.step", U.jo("n" to step, "thought" to thought, "action" to act, "arg" to arg, "result" to U.clip(result, 300)))
        }
        if (!finished) Bus.emit("agent.done", U.jo("text" to (if (stopFlag.get()) "Stopped." else "I stopped after $maxSteps steps.")))
    }
}
