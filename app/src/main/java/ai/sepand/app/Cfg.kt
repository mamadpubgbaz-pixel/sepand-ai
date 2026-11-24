package ai.sepand.app

import org.json.JSONArray
import org.json.JSONObject

/** All user settings in one JSON document. Defaults are merged in on every read. */
object Cfg {
    private var o = JSONObject()
    @Volatile var persist: (String) -> Unit = {}

    private const val DEF = """{
 "lang":"auto","theme":"dark-red",
 "modelId":"","compute":"cpu","threads":4,"gpuLayers":99,"ctx":4096,"batch":512,"kv":"q8_0",
 "thinkOn":false,"thinkLevel":2,"temp":0.7,"topP":0.9,"maxTokens":1024,
 "promptId":"default","agentPrompt":"",
 "mcp":[],
 "obs":{"uri":"","folder":"SepandAI/Memory","autoRecall":true,"autoSave":true,"scope":"vault","topK":4},
 "agent":{"maxSteps":20,"confirmRisky":true},
 "msg":{"mode":"off","apps":["com.whatsapp","org.telegram.messenger","com.google.android.apps.messaging","com.instagram.android"],"contacts":[],"groups":false,"maxPerHour":6,"promptId":"reply","autoStart":true,"quietFrom":"","quietTo":""},
 "tools":{"web":true,"memory":true,"phone":false},
 "hfBase":"https://huggingface.co"
}"""

    val defaults: JSONObject get() = JSONObject(DEF).put("prompts", Prompts.builtinPresets())

    @Synchronized fun load(json: String?) {
        o = try { JSONObject(json ?: "{}") } catch (e: Exception) { JSONObject() }
    }

    @Synchronized fun all(): JSONObject = merge(defaults, o)

    @Synchronized fun set(k: String, v: Any?) {
        o.put(k, v ?: JSONObject.NULL)
        persist(o.toString())
    }

    fun s(k: String): String = all().s(k)
    fun i(k: String): Int = all().i(k)
    fun d(k: String): Double = all().d(k)
    fun b(k: String): Boolean = all().b(k)
    fun o(k: String): JSONObject = all().optJSONObject(k) ?: JSONObject()
    fun a(k: String): JSONArray = all().optJSONArray(k) ?: JSONArray()

    private fun merge(base: JSONObject, over: JSONObject): JSONObject {
        val r = JSONObject(base.toString())
        for (k in over.keys()) {
            val v = over.get(k)
            val bv = r.opt(k)
            if (v is JSONObject && bv is JSONObject) r.put(k, merge(bv, v)) else r.put(k, v)
        }
        return r
    }
}
