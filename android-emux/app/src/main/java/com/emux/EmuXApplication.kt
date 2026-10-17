package com.emux

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.emux.core.BootstrapWorker
import com.emux.terminal.TerminalService

class EmuXApplication : Application() {

    companion object { const val BOOTSTRAP_WORK = "emux-bootstrap" }

    // v0.1.6 CRASH FIX (verified by static analysis of the shipped dex):
    // EVERYTHING in Application.onCreate is now individually guarded. Any
    // uncaught Throwable here kills the process before a single activity exists
    // → the system shows "EmuX keeps stopping" and, because Application runs on
    // every launch (including WorkManager/scheduled wakeups), the app looked
    // permanently broken. Nothing in app-start may be able to crash it.
    override fun onCreate() {
        super.onCreate()
        try {
            com.emux.EmuXLog.init(this)
            com.emux.EmuXLog.i("EmuXApplication onCreate v${com.emux.EmuXLog.appVersion(this)}")
        } catch (e: Throwable) { android.util.Log.e("EmuX", "log init failed", e) }

        try { ensureNotificationChannel() }
        catch (e: Throwable) { com.emux.EmuXLog.e("channel setup failed", e) }

        // Explicitly initialise WorkManager BEFORE any getInstance() call —
        // without androidx.startup on the classpath it can throw otherwise.
        try {
            androidx.work.WorkManager.initialize(
                this,
                androidx.work.Configuration.Builder()
                    .setMinimumLoggingLevel(android.util.Log.INFO).build()
            )
        } catch (_: IllegalStateException) { /* already initialised */ }
        catch (e: Throwable) { com.emux.EmuXLog.e("WorkManager init failed", e) }

        // If the distro isn't installed yet, schedule it for a battery-friendly
        // window (charging OR idle + network) instead of hammering CPU now.
        try {
            if (!com.emux.core.FsLayout.isBootstrapped(this)) {
                runCatching { scheduleBootstrap(this) }
                    .onFailure { com.emux.EmuXLog.e("scheduleBootstrap failed", it) }
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

    private fun scheduleBootstrap(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<BootstrapWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setInputData(workDataOf("force" to false))
            .addTag("emux")
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            BOOTSTRAP_WORK, ExistingWorkPolicy.KEEP, req
        )
    }
}
