package com.emux

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tiny rotating file logger. Because the app kept dying before logcat could be
 * captured on-device, every launch writes to files/log/emux.log AND installs an
 * UncaughtExceptionHandler that records the full stack of any crash (including
 * startup crashes) to the same file. Settings → "About" shows its tail.
 */
object EmuXLog {
    private const val TAG = "EmuX"
    private var file: File? = null

    fun init(ctx: Context) {
        try {
            val dir = File(ctx.filesDir, "log").apply { mkdirs() }
            file = File(dir, "emux.log")
            if ((file?.length() ?: 0) > 256 * 1024) file?.delete()   // rotate at 256 KiB
            append("---- session start ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())} " +
                "v${appVersion(ctx)} android=${android.os.Build.VERSION.SDK_INT} abi=${android.os.Build.SUPPORTED_ABIS.firstOrNull()} ----")
            val prev = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { t, e ->
                append("FATAL thread=${t.name}: ${Log.getStackTraceString(e)}")
                prev?.uncaughtException(t, e)
            }
        } catch (_: Exception) { /* logging must never cause a crash */ }
    }

    fun appVersion(ctx: Context): String = try {
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (_: Exception) { "?" }

    fun i(msg: String) { Log.i(TAG, msg); append("I $msg") }
    fun e(msg: String, t: Throwable? = null) {
        Log.e(TAG, msg, t); append("E $msg${t?.let { "\n" + Log.getStackTraceString(it) } ?: ""}")
    }

    private fun append(line: String) {
        try {
            file?.appendText("${SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())} $line\n")
        } catch (_: Exception) {}
    }

    fun tail(maxLines: Int = 80): String {
        return try {
            val f = file ?: return "(no log yet)"
            f.readLines().takeLast(maxLines).joinToString("\n")
        } catch (e: Exception) { "reading log failed: ${e.message}" }
    }
}
