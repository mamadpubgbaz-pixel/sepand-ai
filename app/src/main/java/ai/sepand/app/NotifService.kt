package ai.sepand.app

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Sees message notifications (WhatsApp, Telegram, SMS...) and can answer them with the notification's own reply button. */
class NotifService : NotificationListenerService() {
    class Item(
        val key: String, val pkg: String, val app: String, val title: String, val text: String, val time: Long,
        val group: Boolean, val action: Notification.Action?, val history: List<String>
    )

    companion object {
        @Volatile var instance: NotifService? = null
        val recent = ArrayList<Item>()

        fun enabled(ctx: Context): Boolean =
            (Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: "").split(':').any { it.startsWith(ctx.packageName + "/") }

        fun describe(): String = synchronized(recent) {
            if (recent.isEmpty()) "There are no message notifications right now."
            else recent.take(12).mapIndexed { i, it -> "${i + 1}. [${it.app}] ${it.title}: ${U.clip(it.text, 200)}" + (if (it.action == null) " (cannot reply)" else "") }.joinToString("\n")
        }

        fun send(ctx: Context, item: Item, text: String): Boolean {
            val a = item.action ?: return false
            val ris = a.remoteInputs ?: return false
            return try {
                val intent = Intent()
                val b = Bundle()
                for (ri in ris) b.putCharSequence(ri.resultKey, text)
                RemoteInput.addResultsToIntent(ris, intent, b)
                a.actionIntent.send(ctx, 0, intent)
                true
            } catch (e: PendingIntent.CanceledException) { false }
        }

        fun reply(ctx: Context, index: Int, text: String): String {
            val item = synchronized(recent) { recent.getOrNull(index - 1) } ?: return "No notification number $index."
            if (text.isBlank()) return "The reply text is empty."
            return if (send(ctx, item, text)) "reply sent to ${item.title}" else "This notification cannot be replied to."
        }
    }

    override fun onListenerConnected() { instance = this; Bus.emit("perm", Bridge.perms(this)) }
    override fun onListenerDisconnected() { instance = null }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        synchronized(recent) { recent.removeAll { it.key == sbn.key } }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName == packageName) return
        try { handle(sbn) } catch (e: Exception) { }
    }

    private fun handle(sbn: StatusBarNotification) {
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0 || n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        var text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
        val history = ArrayList<String>()
        @Suppress("DEPRECATION")
        val arr = ex.getParcelableArray(Notification.EXTRA_MESSAGES)
        arr?.forEach { p ->
            if (p is Bundle) {
                val t = p.getCharSequence("text")?.toString() ?: ""
                val who = p.getCharSequence("sender")?.toString() ?: p.getBundle("sender_person")?.getCharSequence("name")?.toString() ?: ""
                if (t.isNotEmpty()) history.add(if (who.isNotEmpty()) "$who: $t" else t)
            }
        }
        if (history.isNotEmpty()) text = history.last().substringAfter(": ", history.last())
        val action = n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }
        if (text.isEmpty() || (action == null && n.category != Notification.CATEGORY_MESSAGE)) return
        val group = ex.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false) || ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) != null
        val app = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() } catch (e: Exception) { sbn.packageName }
        val item = Item(sbn.key, sbn.packageName, app, title, text, sbn.postTime, group, action, history)
        synchronized(recent) {
            recent.removeAll { it.key == item.key }
            recent.add(0, item)
            while (recent.size > 40) recent.removeAt(recent.size - 1)
        }
        MessageAuto.onMessage(applicationContext, item)
    }
}
