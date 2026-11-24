package ai.sepand.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The phone's "hands and eyes": reads the screen as text and taps, types, scrolls and swipes. */
class AgentAccessibilityService : AccessibilityService() {
    class Node(val id: Int, val info: AccessibilityNodeInfo, val bounds: Rect, val label: String, val kind: String)

    companion object {
        @Volatile var instance: AgentAccessibilityService? = null

        fun enabled(ctx: Context): Boolean {
            val s = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return s.split(':').any { it.startsWith(ctx.packageName + "/") }
        }
    }

    @Volatile var nodes: Map<Int, Node> = emptyMap()
    @Volatile var lastChange = 0L
    @Volatile var lastPkg = ""
    private val main = Handler(Looper.getMainLooper())
    private var pill: LinearLayout? = null
    private var pillText: TextView? = null
    private var confirmView: LinearLayout? = null

    override fun onServiceConnected() { instance = this; Bus.emit("perm", Bridge.perms(this)) }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; hideStatus(); hideConfirm(); return super.onUnbind(intent) }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null) return
        lastChange = System.currentTimeMillis()
        e.packageName?.let { lastPkg = it.toString() }
    }

    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- reading the screen

    /** Waits until the screen stops changing (or the time is up). */
    fun settle(maxMs: Long, quietMs: Long) {
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < maxMs) {
            if (System.currentTimeMillis() - lastChange >= quietMs) return
            Thread.sleep(100)
        }
    }

    fun snapshot(maxNodes: Int = 70): String {
        val root = rootInActiveWindow ?: return "(the screen cannot be read right now)"
        val list = ArrayList<Node>()
        collect(root, list, false, 0, maxNodes)
        val map = LinkedHashMap<Int, Node>()
        for (n in list) map[n.id] = n
        nodes = map
        val dm = resources.displayMetrics
        val sb = StringBuilder("App: ").append(root.packageName).append(" | screen ").append(dm.widthPixels).append('x').append(dm.heightPixels).append('\n')
        if (list.isEmpty()) sb.append("(no readable elements; try wait, back or scroll)\n")
        for (n in list) sb.append('[').append(n.id).append("] ").append(n.kind).append(" \"").append(n.label).append("\"\n")
        return sb.toString()
    }

    private fun textOf(n: AccessibilityNodeInfo): String =
        (n.text ?: n.contentDescription ?: n.hintText)?.toString()?.replace('\n', ' ')?.trim().orEmpty()

    private fun descLabel(n: AccessibilityNodeInfo): String {
        val parts = ArrayList<String>()
        fun rec(x: AccessibilityNodeInfo, d: Int) {
            if (parts.size >= 3 || d > 4) return
            for (i in 0 until x.childCount) {
                val c = x.getChild(i) ?: continue
                val t = (c.text ?: c.contentDescription)?.toString()?.trim().orEmpty()
                if (t.isNotEmpty() && c.isVisibleToUser) parts.add(t) else rec(c, d + 1)
                if (parts.size >= 3) return
            }
        }
        rec(n, 0)
        return parts.joinToString(" | ")
    }

    private fun collect(n: AccessibilityNodeInfo, out: MutableList<Node>, covered: Boolean, depth: Int, max: Int) {
        if (out.size >= max || depth > 30 || !n.isVisibleToUser) return
        val text = textOf(n)
        val clickable = n.isClickable || n.isLongClickable
        var label = text
        var childCovered = covered
        if (clickable && label.isEmpty()) {
            label = descLabel(n)
            if (label.isNotEmpty()) childCovered = true
        }
        val kind = when {
            n.isEditable -> "input"
            n.isCheckable -> if (n.isChecked) "toggle(on)" else "toggle(off)"
            clickable -> "button"
            n.isScrollable -> "scroll"
            else -> "text"
        }
        if (n.isEditable || clickable || n.isScrollable || (text.isNotEmpty() && !covered)) {
            val r = Rect()
            n.getBoundsInScreen(r)
            if (r.width() > 0 && r.height() > 0) {
                var lab = label
                if (lab.isEmpty()) lab = n.viewIdResourceName?.substringAfter('/') ?: ""
                if (lab.isEmpty() && kind == "scroll") lab = "list"
                if (lab.isNotEmpty() || kind == "input") out.add(Node(out.size + 1, n, r, U.clip(lab, 70), kind))
                if (clickable && text.isNotEmpty()) childCovered = true
            }
        }
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            collect(c, out, childCovered, depth + 1, max)
        }
    }

    // ---------------------------------------------------------------- doing things

    private fun gesture(p: Path, ms: Long): Boolean {
        val done = CountDownLatch(1)
        var ok = false
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, ms)).build()
        main.post {
            val started = dispatchGesture(g, object : GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) { ok = true; done.countDown() }
                override fun onCancelled(d: GestureDescription?) { done.countDown() }
            }, null)
            if (!started) done.countDown()
        }
        done.await(ms + 3000, TimeUnit.MILLISECONDS)
        return ok
    }

    fun tapAt(x: Float, y: Float, hold: Long = 60): Boolean {
        val p = Path()
        p.moveTo(x, y)
        return gesture(p, hold)
    }

    fun labelOf(id: Int): String = nodes[id]?.label ?: ""

    fun tap(id: Int, long: Boolean = false): String {
        val n = nodes[id] ?: return "There is no element [$id] on the screen."
        var cur: AccessibilityNodeInfo? = n.info
        var hops = 0
        while (cur != null && !(if (long) cur.isLongClickable else cur.isClickable) && hops < 4) { cur = cur.parent; hops++ }
        if (cur != null && cur.performAction(if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK)) return "ok"
        return if (tapAt(n.bounds.exactCenterX(), n.bounds.exactCenterY(), if (long) 700 else 60)) "ok (gesture)" else "The tap did not work."
    }

    fun typeText(id: Int?, text: String): String {
        val n = (if (id != null) nodes[id]?.info else null) ?: rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return "There is no input field to type in. Tap one first."
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        if (n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return "ok"
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("sepand", text))
        return if (n.performAction(AccessibilityNodeInfo.ACTION_PASTE)) "ok (pasted)" else "Typing did not work in this field."
    }

    /** content = true: dir is where you want to see more content (scroll down = swipe up). */
    fun swipe(dir: String, content: Boolean): String {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        val finger = if (!content) dir else when (dir) { "down" -> "up"; "up" -> "down"; "left" -> "right"; "right" -> "left"; else -> dir }
        val p = Path()
        when (finger) {
            "up" -> { p.moveTo(w / 2, h * 0.72f); p.lineTo(w / 2, h * 0.28f) }
            "down" -> { p.moveTo(w / 2, h * 0.28f); p.lineTo(w / 2, h * 0.72f) }
            "left" -> { p.moveTo(w * 0.8f, h / 2); p.lineTo(w * 0.2f, h / 2) }
            "right" -> { p.moveTo(w * 0.2f, h / 2); p.lineTo(w * 0.8f, h / 2) }
            else -> return "Unknown direction \"$dir\"."
        }
        return if (gesture(p, 380)) "ok" else "The swipe did not work."
    }

    fun global(name: String): String {
        val a = when (name) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            else -> return "Unknown key \"$name\"."
        }
        return if (performGlobalAction(a)) "ok" else "failed"
    }

    // ---------------------------------------------------------------- floating status and question

    private fun wm() = getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private fun params() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT
    )

    private fun glass(radius: Int, a: Int = 228) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat()
        setColor(Color.argb(a, 22, 12, 16))
        setStroke(dp(1), Color.argb(80, 255, 255, 255))
    }

    private fun button(label: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        textSize = 13f
        minHeight = 0
        minimumHeight = dp(34)
        setPadding(dp(14), 0, dp(14), 0)
        background = GradientDrawable().apply { cornerRadius = dp(17).toFloat(); setColor(color) }
        setOnClickListener { onClick() }
    }

    fun showStatus(text: String) {
        main.post {
            if (pill == null) {
                val box = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(8), dp(8), dp(8))
                    background = glass(24)
                }
                val tv = TextView(this).apply { setTextColor(Color.WHITE); textSize = 13f; maxLines = 2; maxWidth = dp(230) }
                box.addView(tv)
                box.addView(button("Stop", Color.rgb(200, 40, 60)) { AgentRunner.stop() }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(34)).apply { marginStart = dp(10) })
                val p = params()
                p.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                p.y = dp(52)
                try { wm().addView(box, p); pill = box; pillText = tv } catch (e: Exception) { }
            }
            pillText?.text = text
        }
    }

    fun hideStatus() {
        main.post {
            pill?.let { try { wm().removeView(it) } catch (e: Exception) { } }
            pill = null
            pillText = null
        }
    }

    fun showConfirm(id: String, text: String) {
        main.post {
            hideConfirmNow()
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(16), dp(18), dp(14))
                background = glass(22, 242)
            }
            box.addView(TextView(this).apply { setTextColor(Color.WHITE); textSize = 15f; this.text = U.clip(text, 400); maxWidth = dp(300) })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
            row.addView(button("Deny", Color.rgb(120, 40, 50)) { Confirm.answer(id, false) })
            row.addView(button("Allow", Color.rgb(36, 140, 90)) { Confirm.answer(id, true) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(34)).apply { marginStart = dp(10) })
            box.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
            val p = params()
            p.gravity = Gravity.CENTER
            try { wm().addView(box, p); confirmView = box } catch (e: Exception) { }
        }
    }

    private fun hideConfirmNow() {
        confirmView?.let { try { wm().removeView(it) } catch (e: Exception) { } }
        confirmView = null
    }

    fun hideConfirm() { main.post { hideConfirmNow() } }
}
