package com.emux

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.emux.terminal.TerminalService

class EmuXApplication : Application() {

    companion object { const val BOOTSTRAP_WORK = "emux-bootstrap" }

    // v0.1.11 CRASH FIX ("bootstrap failed: NoClassDefFoundError", verified by
    // tracing the shipped dex): this file statically referenced androidx.work
    // (imports + CoroutineWorker class literal). On devices where the work
    // runtime fails late class resolution, merely *touching* scheduleBootstrap()
    // threw NoClassDefFoundError inside Application.onCreate — and because the
    // error text came from the wrapper exception only, every previous release
    // showed the same bare "NoClassDefFoundError". WorkManager is now reached
    // ONLY reflectively; if linkage fails we fall back to BootstrapRunner, an
    // in-process path with zero androidx.work dependency.

    // v0.1.6 CRASH FIX (verified by static analysis of the shipped dex):
    // EVERYTHING in Application.onCreate is now individually guarded. Any
    // uncaught Throwable here kills the process before a single activity exists
    // → the system shows "EmuX keeps stopping" and, because Application runs on
    // every launch (including WorkManager/scheduled wakeups), the app looked
    // permanently broken. Nothing in app-start may be able to crash it.
    override fun onCreate() {
        super.onCreate()
        // v0.2.62 CRASH VISIBILITY: installed FIRST, before anything else can
        // throw. The guards below only cover statements inside this method — the
        // crashes that reach the user as the system "EmuX keeps stopping" dialog
        // are the ones that escape them entirely (an androidx.startup provider, a
        // Runnable posted after onCreate returned, an uncaught Throwable in a
        // coroutine, a View callback like onDraw, or class initialization of
        // anything touched below). A process-wide handler is the only net that
        // covers all of those, and it writes the report the next launch displays.
        try { com.emux.CrashReport.install(this) } catch (_: Throwable) { /* reporter is best-effort */ }
        // v0.1.91: persist the PTY-wrapper probe so terminal cold starts skip
        // re-exec'ing every wrapper candidate (part of "terminal slow to start").
        // v0.2.62: this was the one statement in Application.onCreate NOT inside a
        // guard. Assigning to TtyWrap.cacheFile initializes the TtyWrap object,
        // whose <clinit> reads TerminalSession.CANDIDATES — so a failure there was
        // an ExceptionInInitializerError before any try block, i.e. an unopenable
        // app with no on-screen explanation. The probe result is a cache; losing
        // it only costs a re-probe on the next terminal cold start.
        try {
            com.emux.terminal.TtyWrap.cacheFile = java.io.File(filesDir, "ttywrap.probe")
        } catch (e: Throwable) {
            android.util.Log.e("EmuX", "ttywrap cache init failed", e)
        }
        try {
            com.emux.EmuXLog.init(this)
            com.emux.EmuXLog.i("EmuXApplication onCreate v${com.emux.EmuXLog.appVersion(this)}")
        } catch (e: Throwable) { android.util.Log.e("EmuX", "log init failed", e) }

        try { ensureNotificationChannel() }
        catch (e: Throwable) { com.emux.EmuXLog.e("channel setup failed", e) }

        // v0.1.11: WorkManager is touched ONLY through the reflective bridge in
        // BootstrapScheduling; if androidx.work can't link on this ROM we run the
        // bootstrap in-process instead of showing "NoClassDefFoundError".
        try {
            if (!com.emux.core.FsLayout.isBootstrapped(this)) {
                // v0.1.27 PERSISTENCE FIX #3b: don't schedule a full re-download
                // when only the completion marker was lost but the rootfs is
                // intact — re-stamp it instead.
                val restamped = runCatching {
                    com.emux.core.FsLayout.ensureMarkerForExistingRootfs(this)
                }.getOrDefault(false)
                // v0.1.47 MULTI-DISTRO: never auto-download a SWITCH. When any
                // distro is already installed but the selected one is not, the
                // user changed the Settings choice — MainActivity shows the
                // explicit Install pane instead of a silent 40-60 MB pull at
                // app start. Only a truly empty environment auto-bootstraps.
                if (!restamped && !runCatching {
                        com.emux.core.FsLayout.anyDistroInstalled(this)
                    }.getOrDefault(false)) {
                    com.emux.core.BootstrapScheduling.scheduleAuto(this)
                }
            }
        } catch (e: Throwable) { com.emux.EmuXLog.e("startup bootstrap scheduling failed", e) }
    }

    private fun ensureNotificationChannel() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(
                    TerminalService.CHANNEL_ID,
                    getString(R.string.notif_channel_sessions),
                    NotificationManager.IMPORTANCE_LOW   // never sounds / never peeks
                ).apply { description = getString(R.string.notif_channel_sessions_desc) }
            )
        } catch (e: Throwable) {
            // A failure here previously killed the process before MainActivity
            // ever existed → "app keeps stopping" with no UI at all. Non-fatal.
            com.emux.EmuXLog.e("notification channel creation failed", e)
        }
    }

    // v0.1.11: scheduleBootstrap removed — all WorkManager access now goes
    // through com.emux.core.BootstrapScheduling (reflective, crash-proof).
}
