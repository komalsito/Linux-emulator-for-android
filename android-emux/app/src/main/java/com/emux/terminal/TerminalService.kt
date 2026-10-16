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
            ctx.startForegroundService(i)
            return -1 // real ids delivered through the sessions() flow
        }

        const val ACTION_NEW_SESSION = "com.emux.NEW_SESSION"
        const val ACTION_STOP_SESSION = "com.emux.STOP_SESSION"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_COMMAND = "command"
    }

    private val root by lazy { RootManager(this) }
    private val sessions = mutableListOf<TerminalSession>()
    private var nextId = 1

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification("EmuX ready"))
        lifecycleScope.launch {
            root.detect() // async, non-blocking; chroot mode upgrades later sessions
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
        return android.app.Service.START_STICKY // like Termux: survive task-swipe, restart only if killed
    }

    private fun newSession(command: String) {
        val s = TerminalSession(nextId++, this, root, command)
        sessions += s
        s.start()
        refresh()
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
        (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
            .notify(NOTIF_ID, buildNotification(text))
    }

    override fun onDestroy() {
        lifecycleScope.launch { sessions.toList().forEach { it.stop() } }
        super.onDestroy()
    }
}
