package com.emux

import android.content.Context
import android.content.Intent
import java.io.File

/**
 * Crash visibility for a field device with no adb and no root.
 *
 * WHY THIS EXISTS. A device crash-looped on every launch with the system "EmuX
 * keeps stopping" dialog and produced nothing on screen. This app is otherwise
 * heavily guarded — MainActivity.onCreate catches Throwable around its whole
 * startup path (v0.1.3), EmuXApplication.onCreate guards each statement (v0.1.6),
 * postSafely guards View.post bodies (v0.1.8) — so the throws that reach the
 * SYSTEM dialog are exactly the ones outside all of those.
 *
 * THE ORDERING TRAP (this is what v0.2.62 got wrong). Android's startup order is:
 *
 *   1. Application constructed
 *   2. Application.attachBaseContext()
 *   3. EVERY ContentProvider instantiated + onCreate()   <-- providers run HERE
 *   4. Application.onCreate()
 *   5. Activities
 *
 * v0.2.62 installed this handler in step 4. The app's only ContentProvider is
 * androidx.startup's InitializationProvider, which runs EmojiCompatInitializer,
 * ProcessLifecycleInitializer and ProfileInstallerInitializer in step 3 — so a
 * throw there killed the process BEFORE the handler existed. No file, no report,
 * no on-screen anything: precisely the "still nothing from the app, instant crash"
 * that was reported back. [install] must therefore be called from
 * attachBaseContext, and is.
 *
 * Nothing here swallows a crash. The report is written, surfaced, and then the
 * original handler still runs and the process still dies.
 */
object CrashReport {

    private const val NAME = "last_crash.txt"

    /** Intent extra carrying the report, so display does not depend on file I/O. */
    const val EXTRA_REPORT = "com.emux.extra.CRASH_REPORT"

    /** Freshness window: older than this is a stale report, not the current bug. */
    private const val MAX_AGE_MS = 30L * 60 * 1000

    @Volatile private var installed = false

    /** Both locations: only the external one is reachable without adb. */
    private fun targets(ctx: Context): List<File> = listOfNotNull(
        runCatching { File(ctx.filesDir, NAME) }.getOrNull(),
        runCatching { ctx.getExternalFilesDir(null)?.let { File(it, NAME) } }.getOrNull()
    )

    /**
     * Install the process-wide reporter. Idempotent and itself fully guarded —
     * this is the first app code to run, so a failure here must not be the reason
     * the app dies. Call from Application.attachBaseContext, NOT onCreate.
     */
    fun install(ctx: Context) {
        if (installed) return
        installed = true
        val app = runCatching { ctx.applicationContext ?: ctx }.getOrDefault(ctx)
        val previous = runCatching { Thread.getDefaultUncaughtExceptionHandler() }.getOrNull()
        runCatching {
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                val report = runCatching { render(app, thread, error) }.getOrNull()
                if (report != null) {
                    runCatching { persist(app, report) }
                    // Shown from a SEPARATE process (:crash), because this one is
                    // about to die and would take an in-process Activity with it.
                    runCatching { showInOwnProcess(app, report) }
                }
                // Never swallow: hand back to the platform so the process still
                // dies and the system still reports it.
                if (previous != null) previous.uncaughtException(thread, error)
            }
        }
    }

    /**
     * Record a failure that was CAUGHT. Used by SafeStartupProvider, which absorbs
     * an androidx.startup initializer failure so the app can still start — the
     * reason must not disappear just because it stopped being fatal.
     */
    fun recordNonFatal(ctx: Context, tag: String, error: Throwable) {
        runCatching {
            val text = render(ctx, Thread.currentThread(), error, tag) ?: return
            persist(ctx, text)
            runCatching { EmuXLog.e(tag, error) }
        }
    }

    private fun render(
        ctx: Context,
        thread: Thread,
        error: Throwable,
        tag: String? = null
    ): String? {
        val sb = StringBuilder(4096)
        sb.append(if (tag == null) "EmuX crash report" else "EmuX non-fatal: $tag").append('\n')
        sb.append("app version : ")
          .append(runCatching { EmuXLog.appVersion(ctx) }.getOrDefault("?")).append('\n')
        sb.append("env version : ")
          .append(runCatching { com.emux.core.FsLayout.ENV_VERSION.toString() }.getOrDefault("?")).append('\n')
        sb.append("android     : API ").append(android.os.Build.VERSION.SDK_INT)
          .append(" / ").append(android.os.Build.MANUFACTURER).append(' ')
          .append(android.os.Build.MODEL).append('\n')
        sb.append("abi         : ")
          .append(runCatching { android.os.Build.SUPPORTED_ABIS.joinToString(",") }.getOrDefault("?")).append('\n')
        sb.append("thread      : ").append(thread.name).append('\n')
        sb.append("when        : ").append(java.util.Date()).append('\n')
        sb.append('\n')
        val sw = java.io.StringWriter()
        runCatching { error.printStackTrace(java.io.PrintWriter(sw)) }
        sb.append(sw.toString().ifBlank { "${error.javaClass.name}: ${error.message}" })
        sb.append("\n--- EmuXLog tail (what happened just before) ---\n")
        sb.append(runCatching { EmuXLog.tail(80) }.getOrDefault("(log unavailable)"))
        return sb.toString()
    }

    private fun persist(ctx: Context, text: String) {
        for (f in targets(ctx)) runCatching { f.parentFile?.mkdirs(); f.writeText(text) }
    }

    private fun showInOwnProcess(ctx: Context, report: String) {
        val i = Intent(ctx, CrashReportActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra(EXTRA_REPORT, report)
        ctx.startActivity(i)
    }

    /** The report from the previous process, if recent enough to still be relevant. */
    fun consumeIfFresh(ctx: Context): String? {
        val f = runCatching { File(ctx.filesDir, NAME) }.getOrNull() ?: return null
        return runCatching {
            if (!f.isFile) return null
            if (System.currentTimeMillis() - f.lastModified() > MAX_AGE_MS) { clear(ctx); return null }
            val t = f.readText()
            if (t.isBlank()) null else t
        }.getOrNull()
    }

    fun clear(ctx: Context) {
        for (f in targets(ctx)) runCatching { if (f.exists()) f.delete() }
    }

    /** Absolute paths, so a report can say where to look without adb. */
    fun locations(ctx: Context): String = targets(ctx).joinToString("\n") { it.absolutePath }
}
