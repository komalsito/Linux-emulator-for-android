package com.emux

import android.app.Activity
import android.content.Context
import android.os.Bundle

/**
 * Shows a captured crash report. Runs in its own process (`:crash`, declared in
 * the manifest) for one reason: the process that crashed is dying, and an Activity
 * started inside it dies with it. Launching here means system_server starts a
 * second, healthy process that outlives the failure and can actually render the
 * stack trace.
 *
 * Everything is built in code and uses a FRAMEWORK theme, not @style/EmuX. If
 * resource inflation or the app theme is what broke, this screen still has to work
 * — it is the last thing standing between the user and a total dead end.
 */
class CrashReportActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val report = runCatching {
            intent?.getStringExtra(CrashReport.EXTRA_REPORT)
                ?: CrashReport.consumeIfFresh(this)
                ?: "(no report was captured)"
        }.getOrDefault("(could not read the report)")

        val pad = 40
        val col = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, 72, pad, pad)
            setBackgroundColor(0xFF101216.toInt())
        }
        col.addView(android.widget.TextView(this).apply {
            text = "EmuX crashed"
            textSize = 22f
            setTextColor(0xFFFF8A65.toInt())
        })
        col.addView(android.widget.TextView(this).apply {
            text = "The app died before it could draw anything, so this screen runs in a " +
                "separate process to show you why.\n\n" +
                "Copy the report below, or photograph this screen — either is enough to " +
                "diagnose it. Nothing was hidden or swallowed to get here."
            textSize = 13f
            setTextColor(0xFFB0BEC5.toInt())
            setPadding(0, 18, 0, 26)
        })
        col.addView(android.widget.TextView(this).apply {
            text = report
            setTextIsSelectable(true)
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(0xFFE8E8E8.toInt())
            setPadding(0, 0, 0, 20)
        })
        col.addView(button("Copy report") { v ->
            runCatching {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("EmuX crash report", report))
                (v as? android.widget.Button)?.text = "Copied ✓"
            }
        })
        col.addView(button("Try starting EmuX anyway") {
            runCatching { CrashReport.clear(this) }
            runCatching {
                startActivity(packageManager.getLaunchIntentForPackage(packageName)
                    ?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            finish()
        })
        col.addView(button("Close") { finish() })
        col.addView(android.widget.TextView(this).apply {
            text = "Also written to (readable with a file manager, no adb needed):\n" +
                runCatching { CrashReport.locations(this@CrashReportActivity) }.getOrDefault("(unavailable)")
            textSize = 10f
            setTextColor(0xFF78909C.toInt())
            setPadding(0, 26, 0, 0)
        })
        setContentView(android.widget.ScrollView(this).apply { addView(col) })
    }

    private fun button(label: String, onClick: (android.view.View) -> Unit) =
        android.widget.Button(this).apply {
            text = label
            setOnClickListener { runCatching { onClick(it) } }
        }
}
