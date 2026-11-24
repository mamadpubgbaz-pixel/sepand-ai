package ai.sepand.app

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ctx = applicationContext
        val p = getSharedPreferences("sepand", Context.MODE_PRIVATE)
        Cfg.load(p.getString("cfg", "{}"))
        Cfg.persist = { p.edit().putString("cfg", it).apply() }
        Notif.channels(this)
    }

    companion object { lateinit var ctx: Context }
}

/** Events from Kotlin to the web UI. */
object Bus {
    @Volatile var sink: ((String, String) -> Unit)? = null
    fun emit(type: String, o: JSONObject = JSONObject()) { sink?.invoke(type, o.toString()) }
}

object Notif {
    const val CH_ENGINE = "engine"
    const val CH_ALERT = "alert"

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_ENGINE, "AI engine", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Questions and replies", NotificationManager.IMPORTANCE_HIGH))
    }

    fun builder(ctx: Context, ch: String): Notification.Builder = Notification.Builder(ctx, ch).setSmallIcon(R.drawable.ic_stat)

    private fun action(ctx: Context, title: String, id: String, ok: Boolean): Notification.Action {
        val i = Intent(ctx, ActionReceiver::class.java).setAction("confirm").putExtra("id", id).putExtra("ok", ok)
        val pi = PendingIntent.getBroadcast(ctx, id.hashCode() + (if (ok) 1 else 2), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_stat), title, pi).build()
    }

    fun confirm(ctx: Context, id: String, text: String) {
        val n = builder(ctx, CH_ALERT).setContentTitle("Sepand AI").setContentText(U.clip(text, 90))
            .setStyle(Notification.BigTextStyle().bigText(text))
            .addAction(action(ctx, "Allow", id, true)).addAction(action(ctx, "Deny", id, false)).build()
        ctx.getSystemService(NotificationManager::class.java).notify(id.hashCode(), n)
    }

    fun info(ctx: Context, title: String, text: String) {
        val n = builder(ctx, CH_ALERT).setContentTitle(title).setContentText(U.clip(text, 90))
            .setStyle(Notification.BigTextStyle().bigText(text)).setAutoCancel(true).build()
        ctx.getSystemService(NotificationManager::class.java).notify((title + text).hashCode(), n)
    }

    fun cancel(ctx: Context, id: String) = ctx.getSystemService(NotificationManager::class.java).cancel(id.hashCode())
}

/** Asks the user "may I do this?" through the app, a notification and a floating button. */
object Confirm {
    private val waits = ConcurrentHashMap<String, LinkedBlockingQueue<Boolean>>()

    fun ask(ctx: Context, text: String, timeoutSec: Long = 90): Boolean {
        val id = U.id()
        val q = LinkedBlockingQueue<Boolean>()
        waits[id] = q
        Bus.emit("confirm", U.jo("id" to id, "text" to text))
        AgentAccessibilityService.instance?.showConfirm(id, text)
        Notif.confirm(ctx, id, text)
        val r = q.poll(timeoutSec, TimeUnit.SECONDS)
        waits.remove(id)
        Notif.cancel(ctx, id)
        AgentAccessibilityService.instance?.hideConfirm()
        Bus.emit("confirm.done", U.jo("id" to id))
        return r == true
    }

    fun answer(id: String, ok: Boolean) { waits[id]?.offer(ok) }
}

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        if (i.action == "confirm") Confirm.answer(i.getStringExtra("id") ?: "", i.getBooleanExtra("ok", false))
    }
}

/** Keeps the AI engine alive in the background. */
class EngineService : Service() {
    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        if (i?.action == "stop") {
            LlamaServer.stop(this)
            stopSelf()
            return START_NOT_STICKY
        }
        val stop = PendingIntent.getService(this, 1, Intent(this, EngineService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notif.builder(this, Notif.CH_ENGINE).setContentTitle(getString(R.string.engine_running))
            .setContentText(LlamaServer.modelName).setOngoing(true).setContentIntent(open)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat), getString(R.string.engine_stop), stop).build()).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(1, n)
        return START_NOT_STICKY
    }

    companion object {
        fun start(ctx: Context) { try { ctx.startForegroundService(Intent(ctx, EngineService::class.java)) } catch (e: Exception) { } }
        fun stop(ctx: Context) { try { ctx.stopService(Intent(ctx, EngineService::class.java)) } catch (e: Exception) { } }
    }
}
