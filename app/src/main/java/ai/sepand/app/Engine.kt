package ai.sepand.app

import android.content.Context
import java.io.File
import java.net.ServerSocket

/** Starts and watches the bundled llama-server process (CPU or Vulkan GPU build). */
object LlamaServer {
    @Volatile var state = "stopped"      // stopped | starting | ready | error
    @Volatile var message = ""
    @Volatile var modelName = ""
    @Volatile var vision = false
    @Volatile var port = 0
    @Volatile var usedGpu = false
    @Volatile var supportedEfforts: List<String> = emptyList()
    @Volatile var lastTps = 0.0
    @Volatile private var proc: Process? = null
    @Volatile private var stopping = false
    private val logs = ArrayDeque<String>()
    private val lock = Any()

    fun client() = LlamaClient("http://127.0.0.1:$port")
    fun logLines(): List<String> = synchronized(logs) { logs.toList() }
    private fun log(s: String) = synchronized(logs) { logs.addLast(s); while (logs.size > 300) logs.removeFirst() }

    fun binary(ctx: Context, gpu: Boolean): File? =
        File(ctx.applicationInfo.nativeLibraryDir, if (gpu) "libllama_gpu.so" else "libllama_cpu.so").takeIf { it.exists() }

    fun gpuAvailable(ctx: Context) = binary(ctx, true) != null

    private fun set(st: String, msg: String = "") {
        state = st
        message = msg
        Bus.emit("engine", status())
    }

    fun status() = U.jo("state" to state, "message" to message, "model" to modelName, "vision" to vision, "gpu" to usedGpu)

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /** Blocks until the model is loaded (or fails). Returns "" on success, otherwise an error text. */
    fun start(ctx: Context, m: ModelEntry): String {
        synchronized(lock) {
            if (state == "starting") return "The engine is already starting."
            stopQuiet(ctx)
            stopping = false
            set("starting", "Loading " + m.name)
        }
        val wantGpu = Cfg.s("compute") == "gpu"
        var bin = binary(ctx, wantGpu)
        var note = ""
        if (wantGpu && bin == null) { bin = binary(ctx, false); note = "GPU engine is not in this build, using CPU." }
        if (bin == null) { set("error", "The AI engine file is missing from this app build."); return message }
        usedGpu = wantGpu && bin.name == "libllama_gpu.so"
        modelName = m.name
        vision = false
        port = ServerSocket(0).use { it.localPort }

        val ctxSize = Cfg.i("ctx").coerceIn(512, 32768)
        val threads = Cfg.i("threads").coerceIn(1, 8)
        val batch = Cfg.i("batch").coerceIn(32, 2048)
        val kv = Cfg.s("kv")
        val args = arrayListOf(
            "-m", m.path, "--host", "127.0.0.1", "--port", port.toString(), "-c", ctxSize.toString(),
            "-t", threads.toString(), "-tb", threads.toString(), "-b", batch.toString(), "-ub", minOf(batch, 256).toString(),
            "-np", "1", "--jinja", "--no-warmup", "--timeout", "3600", "-cram", "0", "-ctxcp", "4"
        )
        if (kv != "f16") { args += listOf("-ctk", kv, "-ctv", kv, "-fa", "on") } else args += listOf("-fa", "auto")
        if (usedGpu) args += listOf("-ngl", Cfg.i("gpuLayers").coerceIn(0, 999).toString())
        if (m.mmproj.isNotEmpty() && File(m.mmproj).exists()) args += listOf("--mmproj", m.mmproj)

        val pidFile = File(ctx.cacheDir, "engine.pid")
        killOld(pidFile)
        val cmd = "echo \$\$ > " + q(pidFile.path) + "; exec " + q(bin.path) + " " + args.joinToString(" ") { q(it) } + " 2>&1"
        val pb = ProcessBuilder("/system/bin/sh", "-c", cmd)
        pb.environment()["TMPDIR"] = ctx.cacheDir.path
        pb.directory(ctx.filesDir)
        pb.redirectErrorStream(true)
        log("\$ " + bin.name + " " + args.joinToString(" "))
        val p = try { pb.start() } catch (e: Exception) { set("error", "Could not start the engine: " + e.message); return message }
        proc = p
        EngineService.start(ctx)

        Thread {
            try { p.inputStream.bufferedReader().forEachLine { log(it) } } catch (e: Exception) { }
        }.also { it.isDaemon = true }.start()
        Thread {
            val code = try { p.waitFor() } catch (e: InterruptedException) { -1 }
            if (!stopping && proc === p) {
                val hint = if (code == 137 || code == 9) " The phone ran out of memory: use a smaller model or a smaller context." else ""
                set("error", "The engine stopped (code $code)." + hint)
                EngineService.stop(ctx)
            }
        }.also { it.isDaemon = true }.start()

        val client = client()
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < 420000) {
            if (stopping) return "Stopped."
            if (!p.isAlive) return message.ifEmpty { "The engine stopped while loading." }
            if (client.health()) {
                val props = client.props()
                vision = props?.optJSONObject("modalities")?.b("vision") == true
                supportedEfforts = props?.optJSONObject("chat_template_caps")?.optJSONArray("reasoning_effort_levels")?.strs() ?: emptyList()
                set("ready", note)
                return ""
            }
            try { Thread.sleep(700) } catch (e: InterruptedException) { break }
        }
        set("error", "Loading took too long.")
        stopQuiet(ctx)
        return message
    }

    private fun killOld(pidFile: File) {
        try {
            val pid = pidFile.readText().trim().toInt()
            if (File("/proc/$pid/cmdline").readText().contains("libllama_")) android.os.Process.killProcess(pid)
        } catch (e: Exception) { }
    }

    private fun stopQuiet(ctx: Context) {
        stopping = true
        try { proc?.destroy() } catch (e: Exception) { }
        val p = proc
        if (p != null) {
            Thread.sleep(300)
            try { if (p.isAlive) p.destroyForcibly() } catch (e: Exception) { }
        }
        killOld(File(ctx.cacheDir, "engine.pid"))
        proc = null
    }

    fun stop(ctx: Context) {
        synchronized(lock) {
            stopQuiet(ctx)
            set("stopped")
        }
        EngineService.stop(ctx)
    }

    /** Makes sure a model is loaded (starting the selected one if needed). Returns null when ready. */
    fun ensure(ctx: Context): String? {
        if (state == "ready" && proc?.isAlive == true) return null
        val id = Cfg.s("modelId")
        val m = ModelStore.list(ctx).firstOrNull { it.id == id } ?: return "No model selected. Open Settings > Models and pick or download one."
        val err = start(ctx, m)
        return if (err.isEmpty()) null else err
    }
}
