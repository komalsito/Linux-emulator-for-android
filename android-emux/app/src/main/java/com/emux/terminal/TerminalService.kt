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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** v0.2.64: shorthand for the health-gate result (nested enum, so it cannot be
 *  imported as a value any other way without repeating the qualifier). */
private typealias GuestHealth = com.emux.core.BootstrapManager.GuestHealth

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
                // v0.1.5 CRASH FIX (verified by decompiling the shipped dex):
                // this expression evaluated `A && !isFinishing && B.hasWindowFocus()
                // || C is LifecycleOwner && …` — Kotlin precedence made it TRUE for
                // every Service/Application context (they are LifecycleOwners and
                // their state is >= RESUMED once started). So TerminalService.onCreate
                // called startForeground() while the app had NO visible window
                // (bootstrap worker path, relaunch after task-swipe) →
                // ForegroundServiceDidNotStartInTimeException / RemoteServiceException
                // killed the process → "EmuX keeps stopping".
                // v0.1.8 CRASH FIX (verified against shipped dex + ROM behavior):
                // the old "only a RESUMED activity may use startService()" rule was
                // wrong on OEM ROMs (Samsung OneUI, MIUI) where lifecycleState can
                // lag behind window focus — those calls threw
                // IllegalStateException ("Not allowed to start service Intent … app
                // is not in foreground") and BOTH branches of the old code then used
                // startForegroundService(), which on API >= 31 throws
                // ForegroundServiceStartNotAllowedException when the ROM doesn't
                // grant the visible-window exemption. Every exception escaped the
                // runCatching-less call sites → uncaught on the main thread →
                // "EmuX keeps stopping" exactly when tapping Install (which attaches
                // a terminal session). Now: try the legal paths in order, each one
                // individually guarded; if everything is refused we simply log and
                // the UI shows an error instead of dying.
                var started = false
                if (android.os.Build.VERSION.SDK_INT < 26) {
                    started = runCatching { ctx.startService(i) }.isSuccess
                }
                if (!started) {
                    // Plain startService is legal whenever the app has a visible
                    // activity; failures here are expected-but-caught on O+.
                    started = runCatching { ctx.startService(i) }.isSuccess
                }
                if (!started && android.os.Build.VERSION.SDK_INT >= 26) {
                    started = runCatching { ctx.startForegroundService(i) }.isSuccess
                }
                if (!started) {
                    com.emux.EmuXLog.e(
                        "TerminalService start refused by ROM (all paths); sessions unavailable until next resume")
                }
            } catch (e: Throwable) {
                // Last-resort: never let session startup kill the process.
                com.emux.EmuXLog.e("startSession failed", e)
                runCatching { ctx.startService(i) }
            }
            return -1 // real ids delivered through the sessions() flow
        }

        const val ACTION_NEW_SESSION = "com.emux.NEW_SESSION"
        const val ACTION_STOP_SESSION = "com.emux.STOP_SESSION"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_COMMAND = "command"

        /** v0.1.18: cap on automatic respawns of abnormally-exited sessions. */
        const val MAX_AUTO_RESTARTS = 2

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

    /**
     * v0.2.64: used ONLY for the pre-session guest health gate. A single
     * instance is shared by every session so the apt lock and the repair state
     * stay coherent across respawns.
     */
    private val bootstrap by lazy { com.emux.core.BootstrapManager(this) }

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
                ACTION_NEW_SESSION -> {
                    // v0.2.126 DOUBLE-SPAWN GUARD: the service death watcher,
                    // UI session collectors and bootstrap callbacks can all
                    // request a replacement shell within the same second one
                    // dies. Identical commands arriving back-to-back collapse
                    // to one spawn; deliberate new sessions are seconds apart
                    // and never hit this window.
                    val cmd = intent.getStringExtra(EXTRA_COMMAND) ?: "bash -l"
                    val now = System.currentTimeMillis()
                    if (cmd == lastSpawnCmd && now - lastSpawnAtMs < 700) {
                        com.emux.EmuXLog.i("new-session request collapsed (duplicate within 700 ms)")
                    } else {
                        lastSpawnCmd = cmd; lastSpawnAtMs = now
                        newSession(cmd)
                    }
                }
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

    /** How long the gate will wait for a concurrent apt/dpkg transaction before
     *  it gives up and starts the shell anyway. Matches the budget the desktop
     *  cascade uses for the same lock ("up to 90 s"). */
    private val APT_LOCK_WAIT_MS = 90_000L

    /**
     * v0.2.64: push host-side text onto the terminal screen. The gate below can
     * hold a session for up to ~90 s while dpkg repairs; without this the user
     * stares at a blank prompt and concludes the app broke again. Plain text, no
     * ANSI — VtTerminalView renders it and we never risk a half-parsed escape
     * sequence landing mid-prompt. Retried briefly because `scrollback` is
     * BUFFERED and may not have a consumer attached yet.
     */
    private fun banner(s: TerminalSession, text: String) {
        com.emux.EmuXLog.i("terminal-banner: $text")
        val bytes = "\r\n$text\r\n".toByteArray()
        repeat(3) {
            if (s.scrollback.trySend(bytes).isSuccess) return
            runCatching { Thread.sleep(200) }
        }
    }

    /**
     * v0.2.64 TERMINAL HEALTH GATE — the fix for `proot warning: signal 11` on
     * every keystroke. See BootstrapManager.ensureGuestHealthy for the full
     * post-mortem; in short: the desktop launch path has pre-repaired the rootfs
     * since v0.1.72, the terminal path never did, so a half-installed libc-bin
     * SIGSEGV'd every guest execve and then exhausted MAX_AUTO_RESTARTS.
     *
     * Runs on Dispatchers.IO. Cheap (a few file reads) when the guest is
     * healthy, so the common path is unchanged; when the rootfs IS damaged this
     * blocks the shell for the length of one dpkg repair — which is the point,
     * because a shell spawned into a half-swapped library set cannot work.
     */
    private fun gateGuestHealth(s: TerminalSession, priorSegv: Int) {
        var h = GuestHealth.HEALTHY
        var waitedMs = 0L
        var announcedLock = false
        while (true) {
            h = runCatching {
                bootstrap.ensureGuestHealthy("terminal") { m -> banner(s, m) }
            }.getOrElse { GuestHealth.HEALTHY }
            if (h != GuestHealth.LOCK_BUSY || waitedMs >= APT_LOCK_WAIT_MS) break
            if (!announcedLock) {
                announcedLock = true
                banner(s, "terminal: an apt/dpkg transaction is running - waiting for it, because a shell started now would crash into a half-swapped library set")
            }
            runCatching { Thread.sleep(3_000) }
            waitedMs += 3_000
        }
        // Always fail OPEN: a damaged shell still beats no shell at all.
        when (h) {
            GuestHealth.REPAIR_FAILED ->
                banner(s, "terminal: the rootfs could not be repaired automatically - the log tail (Settings > About) has the reason")
            GuestHealth.NOT_READY ->
                banner(s, "terminal: the Linux rootfs is not installed yet - starting in degraded mode until bootstrap finishes")
            GuestHealth.LOCK_BUSY ->
                banner(s, "terminal: still waiting on apt after ${APT_LOCK_WAIT_MS / 1000}s - starting anyway; if every command prints 'signal 11', close the Desktop tab and retry")
            else -> {}
        }
        // dpkg looks clean yet the guest still segfaulted: the remaining
        // documented causes are an ABI/loader mismatch or a kill-sweep hitting
        // proot's own tracer. Say so rather than silently respawning into the
        // same wall — the user has no adb and this screen is their only window.
        if (priorSegv > 0 && h == GuestHealth.HEALTHY) {
            banner(s, "terminal: the previous shell died with SIGSEGV $priorSegv time(s) but dpkg reports clean - if it repeats, the guest binaries do not match this device's ABI/loader; see the log tail (Settings > About)")
        }
    }

    private fun newSession(command: String, priorSegv: Int = 0) {
        try {
            val s = TerminalSession(nextId++, this, root, command)
            sessions += s
            refresh()
            // v0.2.64: gate BEFORE start(). The session object exists first so
            // the UI has something to attach to and the gate's banners have a
            // screen to land on; the child process is only spawned once the
            // rootfs is known-healthy (or the wait has been exhausted).
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { gateGuestHealth(s, priorSegv) }
                withContext(Dispatchers.Main) {
                    s.start()
                    refresh()
                }
            }
            // v0.1.18 BUGFIX #4: when a session's child dies (exit code != 0 or a
            // dead-on-arrival launcher), the registry kept the corpse forever and
            // MainActivity re-attached to it — the UI looked "bootstrapped but
            // totally inert". Watch the state flow; on death with a nonzero exit
            // code, respawn ONE replacement automatically (bounded by
            // MAX_AUTO_RESTARTS so a permanently broken environment can't spawn a
            // busy-loop of processes). Clean exits (code 0, e.g. `exit`) are left
            // alone — the user closed the shell intentionally.
            lifecycleScope.launch {
                s.state.collect { st ->
                    // v0.2.55 FIELD FIX: the restart budget used to be a
                    // GLOBAL counter that never reset - the old SIGSEGV
                    // waves burned through MAX_AUTO_RESTARTS in the first
                    // days, and every terminal death since was permanent
                    // until an app restart ("opening the desktop kills the
                    // terminal, I have to restart everything"). Now the
                    // budget resets after 5 quiet minutes: bursts stay
                    // bounded, ordinary life-cycle deaths always recover.
                    if (st == TerminalSession.State.DEAD && !s.exitedCleanly &&
                        s.id < 9000) {
                        val now = System.currentTimeMillis()
                        if (now - lastAutoRestartMs > 300_000L) autoRestarts = 0
                    }
                    if (st == TerminalSession.State.DEAD && !s.exitedCleanly &&
                        s.id < 9000 && autoRestarts < MAX_AUTO_RESTARTS) {
                        autoRestarts++
                        lastAutoRestartMs = System.currentTimeMillis()
                        sessions.remove(s)
                        refresh()
                        com.emux.EmuXLog.e("session ${s.id} died abnormally — respawning" +
                            if (s.segvDetected) " after ${s.segvCount} guest SIGSEGV(s)" else "")
                        // v0.2.64: carry the crash evidence into the respawn so
                        // the gate can heal the rootfs first (and, if dpkg turns
                        // out clean, tell the user what else to look at) instead
                        // of respawning straight back into the same SIGSEGV.
                        newSession(command, priorSegv = if (s.segvDetected) s.segvCount else 0)
                    } else if (st == TerminalSession.State.DEAD) {
                        sessions -= s
                        refresh()
                    }
                }
            }
        } catch (e: Throwable) {
            com.emux.EmuXLog.e("newSession('$command') failed", e)
        }
    }

    private var autoRestarts = 0
    private var lastAutoRestartMs = 0L   // v0.2.55: windowed restart budget
    // v0.2.126: back-to-back duplicate spawn collapse (see onStartCommand)
    private var lastSpawnCmd = ""
    private var lastSpawnAtMs = 0L

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
