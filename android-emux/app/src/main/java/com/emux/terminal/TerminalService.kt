package com.emux.terminal

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.emux.MainActivity
import com.emux.R
import com.emux.core.BootstrapProgress
import com.emux.core.RootManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Hosts all TerminalSessions in one foreground process — exactly Termux's model:
 *  - single service, single wake-lock budget, sessions share the process heap;
 *  - notification is LOW_MIN so it doesn't light up the shade or hold a full wakelock;
 *  - when every session dies we stopSelf() → no lingering CPU residency.
 */
class TerminalService : LifecycleService() {

    companion object {
        const val CHANNEL_ID = "emux_session"
        const val NOTIF_ID = 1001

        private val registry = MutableStateFlow<List<TerminalSession>>(emptyList())
        fun sessions() = registry

        /** Entry point used by UI + API service. Idempotent per id. */
        fun startSession(ctx: Context, command: String = "bash -l"): Int {
            val i = Intent(ctx, TerminalService::class.java).apply {
                action = ACTION_NEW_SESSION
                putExtra(EXTRA_COMMAND, command)
            }
            // v0.1.2 CRASH FIX: we called startForegroundService() from inside
            // Activity.onCreate(). On Android 12+ (S), starting a foreground
            // service from the background is forbidden unless the app already
            // has a visible-window exemption — which it does NOT yet have during
            // onCreate on many OEM ROMs. The system then throws
            // ForegroundServiceStartNotAllowedException → instant crash loop
            // ("app keeps stopping", every launch dies identically).
            // While the activity is in the foreground, plain startService() is
            // legal and our onCreate() still calls startForeground() immediately,
            // so sessions keep working exactly as before. We only use
            // startForegroundService() when the caller is not an active
            // foreground activity (e.g. the termux-api style EmuXExecuteService).
            try {
                val fgCaller = ctx is android.app.Activity &&
                    (ctx as android.app.Activity).isFinishing.not() &&
                    ctx.hasWindowFocus() || ctx is androidx.lifecycle.LifecycleOwner &&
                    (ctx as? androidx.lifecycle.LifecycleOwner)?.lifecycle
                        ?.currentState?.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) == true
                if (fgCaller || android.os.Build.VERSION.SDK_INT < 31) {
                    ctx.startService(i)
                } else {
                    ctx.startForegroundService(i)
                }
            } catch (_: Exception) {
                // Last-resort: never let session startup kill the process.
                runCatching { ctx.startService(i) }
            }
            return -1 // real ids delivered through the sessions() flow
        }

        const val ACTION_NEW_SESSION = "com.emux.NEW_SESSION"
        const val ACTION_STOP_SESSION = "com.emux.STOP_SESSION"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_COMMAND = "command"

        /**
         * v0.1.2 CRASH FIX #2: on Android 12+ a startService() call that lands
         * while the app is in the *background* (e.g. user swipes the crashed app
         * away, WorkManager wakes us, or the launch happens between onCreate and
         * the window actually gaining focus) throws
         * ForegroundServiceStartNotAllowedException inside onStartCommand — an
         * uncaught exception there kills the process → "app keeps stopping".
         * Every path that promotes this service to foreground now goes through
         * this helper, which downgrades gracefully instead of throwing.
         *
         * v0.1.3 hardening: several ROMs also throw when we pass an FGS *type*
         * the framework doesn't know (specialUse requires API 34; dataSync was
         * only added in API 29). We therefore try in strict capability order and
         * fall back one level at a time, finally running as a plain background
         * service if everything is refused. Sessions keep working either way —
         * the notification/FGS layer is purely for lifecycle robustness.
         */
        fun safeStartForeground(svc: android.app.Service, notifId: Int, n: android.app.Notification) {
            val types = mutableListOf<Int>()
            if (android.os.Build.VERSION.SDK_INT >= 34)
                types += android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            if (android.os.Build.VERSION.SDK_INT >= 29)
                types += android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            types += 0 // no type at all — legal below API 29, and harmless above
            for (t in types) {
                try {
                    androidx.core.app.ServiceCompat.startForeground(svc, notifId, n, t)
                    com.emux.EmuXLog.i("startForeground OK with type=$t")
                    return
                } catch (e: Exception) {
                    com.emux.EmuXLog.e("startForeground refused type=$t (${e.javaClass.simpleName}: ${e.message})", e)
                }
            }
            // Last resort: never crash. Run as ordinary background service; if the
            // system later stops us, START_STICKY + session auto-restart recover.
            com.emux.EmuXLog.e("all FGS types refused; continuing WITHOUT foreground state")
        }
    }

    private val root by lazy { RootManager(this) }
    private val sessions = mutableListOf<TerminalSession>()
    private var nextId = 1

    override fun onCreate() {
        super.onCreate()
        com.emux.EmuXLog.init(this)
        com.emux.EmuXLog.i("TerminalService onCreate")
        safeStartForeground(this, NOTIF_ID, buildNotification("EmuX ready"))
        lifecycleScope.launch {
            root.detect() // async, non-blocking; chroot mode upgrades later sessions
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // NEVER let a system-delivered intent crash the host process.
        try {
            super.onStartCommand(intent, flags, startId)
            when (intent?.action) {
                ACTION_NEW_SESSION -> newSession(intent.getStringExtra(EXTRA_COMMAND) ?: "bash -l")
                ACTION_STOP_SESSION -> {
                    val id = intent.getIntExtra(EXTRA_SESSION_ID, -1)
                    sessions.find { it.id == id }?.let { s ->
                        lifecycleScope.launch { s.stop(); sessions.remove(s); refresh() }
                    }
                }
            }
        } catch (e: Throwable) {
            com.emux.EmuXLog.e("onStartCommand failed", e)
        }
        return android.app.Service.START_STICKY // like Termux: survive task-swipe, restart only if killed
    }

    private fun newSession(command: String) {
        try {
            val s = TerminalSession(nextId++, this, root, command)
            sessions += s
            s.start()
            refresh()
        } catch (e: Throwable) {
            com.emux.EmuXLog.e("newSession('$command') failed", e)
        }
    }

    private fun refresh() {
        registry.value = sessions.toList()
        if (sessions.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            updateNotification("EmuX: ${sessions.size} session(s)")
        }
    }

    private fun buildNotification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_emux_notif)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(text)
        .setOngoing(true)
        .setSilent(true)
        .setPriority(NotificationCompat.PRIORITY_MIN)   // ← battery-friendly: heads-up never fires
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        .build()

    private fun updateNotification(text: String) {
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                .notify(NOTIF_ID, buildNotification(text))
        } catch (_: Exception) { /* notification channel gone (user disabled): harmless */ }
    }

    override fun onDestroy() {
        lifecycleScope.launch { sessions.toList().forEach { it.stop() } }
        super.onDestroy()
    }
}
