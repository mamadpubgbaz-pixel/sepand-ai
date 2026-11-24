package ai.sepand.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import org.json.JSONObject

class MainActivity : Activity() {
    lateinit var web: WebView
    private lateinit var bridge: Bridge
    private var cbFiles = -1
    private var cbModels = -1
    private var cbVault = -1

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        applyTheme(Cfg.s("theme"))
        web = WebView(this)
        setContentView(web)
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = false
            setSupportZoom(false)
            builtInZoomControls = false
            textZoom = 100
        }
        web.overScrollMode = View.OVER_SCROLL_NEVER
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) WebView.setWebContentsDebuggingEnabled(true)
        bridge = Bridge(this)
        web.addJavascriptInterface(bridge, "Android")
        // The page never navigates anywhere: links are opened through the bridge.
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView?, r: WebResourceRequest?): Boolean = true
        }
        Bus.sink = { t, j -> js("window.__ev&&window.__ev(${JSONObject.quote(t)},${JSONObject.quote(j)})") }
        web.loadUrl("file:///android_asset/web/index.html")
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        Bus.emit("perm", Bridge.perms(this))
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(code, p, r)
        Bus.emit("perm", Bridge.perms(this))
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        web.evaluateJavascript("window.__back&&window.__back()") { r -> if (r != "true") runOnUiThread { moveTaskToBack(true) } }
    }

    override fun onDestroy() {
        Bus.sink = null
        super.onDestroy()
    }

    fun js(code: String) { runOnUiThread { if (::web.isInitialized) web.evaluateJavascript(code, null) } }

    fun applyTheme(theme: String) {
        val light = theme.startsWith("white")
        val col = when (theme) {
            "dark-green" -> 0xFF050C09
            "white-red" -> 0xFFFFF5F5
            "white-green" -> 0xFFF2FBF6
            else -> 0xFF0B0507
        }.toInt()
        runOnUiThread {
            window.statusBarColor = col
            window.navigationBarColor = col
            @Suppress("DEPRECATION")
            var f = window.decorView.systemUiVisibility
            f = if (light) f or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            else f and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = f
            if (::web.isInitialized) web.setBackgroundColor(col)
        }
    }

    fun openUrl(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: Exception) { }
    }

    fun copy(text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("sepand", text))
        runOnUiThread { Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show() }
    }

    fun openPerm(which: String) {
        val pkg = Uri.parse("package:$packageName")
        try {
            when (which) {
                "a11y" -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                "notif" -> startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                "appinfo" -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg))
                "battery" -> startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg))
                "files" -> startActivity(Intent(if (Build.VERSION.SDK_INT >= 30) Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION else Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg))
                "post" -> if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
                "autostart" -> try {
                    startActivity(Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")))
                } catch (e: Exception) { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)) }
            }
        } catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)) } catch (e2: Exception) { }
        }
    }

    private fun pick(code: Int) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        startActivityForResult(i, code)
    }

    fun pickFiles(cb: Int) { cbFiles = cb; runOnUiThread { pick(11) } }
    fun pickModels(cb: Int) { cbModels = cb; runOnUiThread { pick(12) } }

    fun pickVault(cb: Int) {
        cbVault = cb
        runOnUiThread {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
            startActivityForResult(i, 13)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val uris = ArrayList<Uri>()
        if (res == RESULT_OK && data != null) {
            data.clipData?.let { cd -> for (i in 0 until cd.itemCount) uris.add(cd.getItemAt(i).uri) }
            if (uris.isEmpty()) data.data?.let { uris.add(it) }
        }
        val ctx = applicationContext
        when (req) {
            11 -> {
                val cb = cbFiles
                cbFiles = -1
                if (cb >= 0) U.pool.execute {
                    try { bridge.reply(cb, true, U.ja(uris.map { Attachments.load(ctx, it).meta() })) } catch (e: Throwable) { bridge.reply(cb, false, e.message) }
                }
            }
            12 -> {
                val cb = cbModels
                cbModels = -1
                if (cb >= 0) U.pool.execute {
                    try {
                        val added = ModelStore.importUris(ctx, uris) { d, t -> Bus.emit("dl", U.jo("id" to "import", "state" to "run", "done" to d, "total" to t)) }
                        if (Cfg.s("modelId").isEmpty() && added.isNotEmpty()) Cfg.set("modelId", added[0].id)
                        Bus.emit("dl", U.jo("id" to "import", "state" to (if (added.isEmpty()) "cancel" else "done")))
                        bridge.reply(cb, true, bridge.modelsJson())
                    } catch (e: Throwable) {
                        Bus.emit("dl", U.jo("id" to "import", "state" to "error", "message" to e.message))
                        bridge.reply(cb, false, e.message)
                    }
                }
            }
            13 -> {
                val cb = cbVault
                cbVault = -1
                val u = data?.data
                if (res == RESULT_OK && u != null) {
                    try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (e: Exception) { }
                    Vault.reset()
                    val o = Cfg.o("obs")
                    o.put("uri", u.toString())
                    Cfg.set("obs", o)
                    U.pool.execute { Vault.refresh(ctx) }
                }
                if (cb >= 0) bridge.reply(cb, true, Vault.status())
            }
        }
    }
}
