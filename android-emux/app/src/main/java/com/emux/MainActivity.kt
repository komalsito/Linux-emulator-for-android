package com.emux

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.emux.core.BootstrapReporter
import com.emux.core.FsLayout
import com.emux.core.RootManager
import com.emux.databinding.ActivityMainBinding
import com.emux.terminal.ExtraKeysView
import com.emux.terminal.TerminalService
import com.emux.terminal.VtTerminalView
import com.emux.x11.DesktopService
import com.emux.x11.DesktopView
import kotlinx.coroutines.launch

/**
 * Single-Activity UI: terminal ⇄ desktop tabs, bootstrap progress, permission
 * onboarding (all optional), and lifecycle hooks that drive the power model.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val root by lazy { RootManager(this) }
    private val desktop by lazy { DesktopService(this, root) }

    private var termView: VtTerminalView? = null

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* fine if denied */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        // v0.1.3 CRASH FIX: wrap the ENTIRE startup path. Any exception thrown
        // here (missing resource, OEM view-inflation quirk, corrupt prefs…) used
        // to propagate straight out of onCreate → Android shows "EmuX keeps
        // stopping" and, because it happens on every launch, the app became
        // permanently unopenable. Now we degrade to a plain error screen that
        // still tells the user what happened and lets them retry/clear data.
        try {
            doOnCreate(savedInstanceState)
        } catch (e: Throwable) {
            com.emux.EmuXLog.init(this)
            com.emux.EmuXLog.e("MainActivity.onCreate failed", e)
            try {
                setContentView(android.widget.ScrollView(this).apply {
                    addView(android.widget.TextView(this@MainActivity).apply {
                        text = "EmuX failed to start:\n\n${e.javaClass.name}: ${e.message}\n\n" +
                            "You can try: Settings (in launcher) → EmuX → Storage → Clear Data,\n" +
                            "or reinstall the APK. Log tail:\n\n" + com.emux.EmuXLog.tail(40)
                        setTextIsSelectable(true)
                        setPadding(48, 96, 48, 48)
                        setTextColor(0xFFE8E8E8.toInt())
                        setBackgroundColor(0xFF101216.toInt())
                    })
                })
            } catch (_: Throwable) { /* nothing more we can do */ }
        }
    }

    private fun doOnCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        wireTabs()
        wireExtraKeys()
        requestNotificationIfNeeded()
        observeSessions()
        // v0.1.3: session attach + root detection are deferred until AFTER the
        // first frame is drawn. Two reasons:
        //  a) TerminalService.startSession() from inside onCreate hit Android 12+
        //     background-FGS restrictions (see TerminalService fix);
        //  b) hasWindowFocus() is false during onCreate, so deferring guarantees
        //     the "app is visible" exemption applies when we do start services.
        postSafely(Runnable {
            if (!com.emux.core.FsLayout.isBootstrapped(this)) {
                showBootstrapUiOnly()
            } else {
                ensureSessionAttached()
            }
        })
        maybeAskBatteryExemption()

        // v0.1.9 CRASH FIX: click listeners run on the main looper OUTSIDE every
        // enclosing try/catch — any Throwable escaping startBootstrapNow (OEM ROM
        // quirks in WorkManager, missing provider, ...) was an instant force-close
        // exactly when Install was tapped. Belt-and-suspenders with the worker fix.
        b.btnInstall.setOnClickListener {
            try {
                startBootstrapNow(showUi = true)
            } catch (e: Throwable) {
                com.emux.EmuXLog.e("install click failed", e)
                runCatching {
                    b.bootstrapBar.visibility = android.view.View.GONE
                    b.bootstrapLabel.visibility = android.view.View.VISIBLE
                    b.bootstrapLabel.text = getString(
                        R.string.bootstrap_failed_reason,
                        "${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }

        lifecycleScope.launch {
            // v0.1.8: root.detect() must NEVER escape this coroutine — an
            // uncaught Throwable in a lifecycleScope launch goes straight to the
            // thread's UncaughtExceptionHandler and kills the process ("app keeps
            // stopping" right after opening the app / tapping Install).
            try { root.detect() } catch (e: Throwable) {
                com.emux.EmuXLog.e("root detect failed", e)
            }
            runCatching {
                b.statusRoot.text = when (root.mode) {
                    com.emux.core.RootMode.NONE -> getString(R.string.root_none)
                    else -> getString(R.string.root_available, root.mode.name)
                }
            }
        }
    }

    /**
     * v0.1.8 CRASH FIX: View.post bodies run on the main looper OUTSIDE every
     * enclosing try/catch (MainActivity.onCreate's doOnCreate guard had already
     * returned by then). Any throw inside it — corrupt prefs file, missing view,
     * OEM ROM quirk — was an instant uncaught crash. Now wrapped, logged, and
     * degraded gracefully.
     */
    private fun postSafely(r: Runnable) {
        b.root.post {
            try { r.run() } catch (e: Throwable) {
                com.emux.EmuXLog.e("post-frame task failed", e)
                runCatching {
                    b.bootstrapLabel.visibility = android.view.View.VISIBLE
                    b.bootstrapLabel.text = "Startup error: ${e.javaClass.simpleName}: ${e.message}"
                }
            }
        }
    }

    /** Bootstrap banner without auto-starting anything (fresh install path). */
    private fun showBootstrapUiOnly() {
        b.bootstrapPane.visibility = android.view.View.VISIBLE
        b.btnInstall.visibility = android.view.View.VISIBLE
    }

    private fun wireTabs() {
        b.tabTerm.setOnClickListener { showTerminal() }
        b.tabX11.setOnClickListener { showDesktop() }
        b.tabSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        showTerminal()
    }

    private fun showTerminal() {
        b.paneDesktop.visibility = android.view.View.GONE
        b.termContainer.visibility = android.view.View.VISIBLE
        b.tabTerm.isSelected = true; b.tabX11.isSelected = false
        ensureSessionAttached()
    }

    private fun showDesktop() {
        b.termContainer.visibility = android.view.View.GONE
        b.paneDesktop.visibility = android.view.View.VISIBLE
        b.tabTerm.isSelected = false; b.tabX11.isSelected = true
        if (desktop.state.value == DesktopService.State.IDLE) {
            b.desktopStatus.text = getString(R.string.x_starting)
            desktop.startDesktop(wmCommand = prefsWm())
            desktop.beginFrameLoop { runOnUiThread { b.desktopView.onNewFrame(desktop.frameId) } }
        }
        b.desktopView.desktop = desktop
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                desktop.state.collect { s ->
                    b.desktopStatus.text = when (s) {
                        DesktopService.State.IDLE -> ""
                        DesktopService.State.STARTING_X -> getString(R.string.x_starting)
                        DesktopService.State.CONNECTED ->
                            getString(R.string.x_connected, desktop.x11?.screenWidth ?: 0, desktop.x11?.screenHeight ?: 0)
                        DesktopService.State.ERROR ->
                            getString(R.string.x_error, desktop.errorText.value ?: "?")
                    }
                }
            }
        }
    }

    private fun prefsWm(): String =
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
            .getString("wm", "openbox") ?: "openbox"

    /** Attach a live TerminalService session to the view, creating one if needed. */
    private fun ensureSessionAttached() {
        val view = termView ?: VtTerminalView(this).also {
            b.termContainer.addView(it, android.widget.FrameLayout.LayoutParams(-1, -1))
            termView = it
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                TerminalService.sessions().collect { list ->
                    val s = list.firstOrNull { it.id < 9000 }
                        ?: run {
                            TerminalService.startSession(this@MainActivity, "bash -l")
                            return@collect
                        }
                    if (view.session !== s) {
                        view.session = s
                        pumpOutput(view, s)
                    }
                }
            }
        }
    }

    private fun pumpOutput(view: VtTerminalView, s: com.emux.terminal.TerminalSession) {
        // NOTE: previously this collected `scrollback` — a Channel that *closes* when
        // the session dies. On a dead/closed channel `for (chunk in ch)` just falls
        // through immediately, so nothing was ever fed to the terminal and the screen
        // stayed blank ("nothing happens"). Now we keep pumping until the session
        // transitions to DEAD, and print an explicit notice on death/exit.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    for (chunk in s.scrollback) view.feed(chunk)
                }
                launch {
                    if (s.state.value == com.emux.terminal.TerminalSession.State.DEAD) {
                        view.feed("[emux] session #${s.id} already exited — tap Install to re-bootstrap.\n".toByteArray())
                    } else {
                        s.state.collect { st ->
                            if (st == com.emux.terminal.TerminalSession.State.DEAD) {
                                view.feed("\n[emux] process exited — press ENTER for a new shell.\n".toByteArray())
                            }
                        }
                    }
                }
            }
        }
    }

    private fun wireExtraKeys() {
        b.extraKeys.currentSessionSink = { bytes -> termView?.session?.write(bytes) }
    }

    private fun observeSessions() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                TerminalService.sessions().collect { list ->
                    b.statusSessions.text = resources.getQuantityString(
                        R.plurals.sessions_count, list.size, list.size
                    )
                }
            }
        }
    }

    /** Kick the WorkManager job immediately (user pressed Install). */
    private fun startBootstrapNow(showUi: Boolean) {
        // v0.1.3 CRASH FIX: WorkManager was never explicitly initialised and this
        // app does NOT use androidx.startup, so on some devices/OEM ROMs
        // WorkManager.getInstance() throws IllegalStateException ("no default
        // Configuration") right here — and because onCreate auto-called this on a
        // fresh install, EVERY launch crashed → "EmuX keeps stopping".
        ensureWorkManager()
        runCatching {
            androidx.work.WorkManager.getInstance(this).cancelUniqueWork(EmuXApplication.BOOTSTRAP_WORK)
        }
        // v0.1.8 CRASH FIX (the Install-button one): watchBootstrapProgress() used
        // to run the FULL download+extract inside lifecycleScope.launch on Main,
        // merely *collecting* progress emitted from an IO child scope. Two fatal
        // consequences: (a) every BootstrapProgress is an Exception carrying a
        // fresh stack trace — collecting dozens per second allocated hard enough
        // to trip the low-memory killer; (b) more importantly, WorkManager had
        // already been cancelled above, so if this in-process attempt died with
        // the activity (rotation/task-swipe/OEM kill), nothing ever completed or
        // reported bootstrap again. Now the UI ONLY observes the WorkManager job's
        // progress; the work itself survives process death and can never crash
        // the app because no bootstrap code runs on the main thread at all.
        // v0.1.10: also add backoffCriteria so a permanently-failing mirror can't
        // hammer retries, and NEVER fall through silently — if enqueue throws
        // (e.g. NoClassDefFoundError linking androidx.work on a broken ROM), run
        // the bootstrap IN-PROCESS right here instead of leaving the user stuck.
        val enqueued = runCatching {
            val req = androidx.work.OneTimeWorkRequestBuilder<com.emux.core.BootstrapWorker>()
                .setConstraints(
                    androidx.work.Constraints.Builder()
                        .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.EXPONENTIAL, 30L, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            androidx.work.WorkManager.getInstance(this)
                .enqueueUniqueWork("emux-bootstrap-now", androidx.work.ExistingWorkPolicy.REPLACE, req)
        }.also { r -> r.exceptionOrNull()?.let { com.emux.EmuXLog.e("worker enqueue failed", it) } }
            .isSuccess
        if (!enqueued) startInProcessFallback()
        if (showUi) watchBootstrapProgress()
    }

    /** Last-resort path when WorkManager itself can't load/run on this device. */
    private fun startInProcessFallback() {
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val mgr = com.emux.core.BootstrapManager(applicationContext)
            val r = runCatching { mgr.bootstrap() }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                b.bootstrapBar.isIndeterminate = false
                if (r.getOrNull()?.isSuccess == true) {
                    ensureSessionAttached()
                    b.bootstrapBar.visibility = android.view.View.GONE
                    b.bootstrapLabel.visibility = android.view.View.GONE
                    b.btnInstall.visibility = android.view.View.GONE
                } else {
                    b.bootstrapLabel.text = getString(
                        R.string.bootstrap_failed_reason,
                        com.emux.core.BootstrapDiagnostics.describe(
                            r.exceptionOrNull() ?: RuntimeException("in-process fallback failed")))
                    b.bootstrapBar.visibility = android.view.View.GONE
                    b.btnInstall.visibility = android.view.View.VISIBLE
                }
            }
        }
    }

    /** Observe the BootstrapWorker's progress; render bar + real errors. */
    private fun watchBootstrapProgress() {
        b.bootstrapBar.visibility = android.view.View.VISIBLE
        b.bootstrapBar.isIndeterminate = true
        b.btnInstall.visibility = android.view.View.GONE
        b.bootstrapLabel.visibility = android.view.View.VISIBLE
        b.bootstrapLabel.text = getString(R.string.bootstrap_starting)
        lifecycleScope.launch {
            try {
                // (a) step/pct/error mirror: BootstrapReporter publishes into a
                // static LiveData fed by the worker (survives activity recreation
                // because the worker re-publishes on restart).
                BootstrapReporter.live.observe(this@MainActivity) { data ->
                    val step = (data[BootstrapReporter.KEY_STEP] as? String) ?: ""
                    val pct = (data[BootstrapReporter.KEY_PCT] as? Int) ?: -1
                    val err = data[BootstrapReporter.KEY_ERROR] as? String
                    if (err != null) {
                        b.bootstrapLabel.text =
                            getString(R.string.bootstrap_failed_reason, err)
                        b.bootstrapLabel.visibility = android.view.View.VISIBLE
                        b.bootstrapBar.visibility = android.view.View.GONE
                        b.btnInstall.visibility = android.view.View.VISIBLE
                    } else if (step.isNotEmpty()) {
                        b.bootstrapLabel.text = step
                        b.bootstrapLabel.visibility = android.view.View.VISIBLE
                        b.bootstrapBar.isIndeterminate = pct < 0
                        if (pct >= 0) b.bootstrapBar.progress = pct
                    }
                }
                // (b) terminal state comes from WorkInfo itself.
                androidx.work.WorkManager.getInstance(this@MainActivity)
                    .getWorkInfosForUniqueWorkLiveData("emux-bootstrap-now")
                    .observe(this@MainActivity) { infos ->
                        val info = infos?.firstOrNull() ?: return@observe
                        when (info.state) {
                            androidx.work.WorkInfo.State.SUCCEEDED -> {
                                b.bootstrapBar.visibility = android.view.View.GONE
                                b.bootstrapLabel.visibility = android.view.View.GONE
                                b.btnInstall.visibility = android.view.View.GONE
                                ensureSessionAttached()
                            }
                            androidx.work.WorkInfo.State.FAILED,
                            androidx.work.WorkInfo.State.CANCELLED,
                            androidx.work.WorkInfo.State.BLOCKED -> {
                                b.bootstrapBar.visibility = android.view.View.GONE
                                if (b.bootstrapLabel.text.isNullOrEmpty() ||
                                    b.bootstrapLabel.text == getString(R.string.bootstrap_starting)) {
                                    b.bootstrapLabel.text =
                                        getString(R.string.bootstrap_failed_reason, "worker ${info.state}")
                                }
                                b.bootstrapLabel.visibility = android.view.View.VISIBLE
                                b.btnInstall.visibility = android.view.View.VISIBLE
                            }
                            else -> { /* ENQUEUED/RUNNING — keep showing the bar */ }
                        }
                    }
            } catch (e: Throwable) {
                com.emux.EmuXLog.e("progress observer failed", e)
                b.bootstrapBar.visibility = android.view.View.GONE
                b.btnInstall.visibility = android.view.View.VISIBLE
            }
        }
    }

    private fun ensureWorkManager() {
        // v0.1.10: route through EmuXBootstrapApp so the availability flag is set;
        // MainActivity uses this to decide whether WorkManager observation is even
        // possible on this device (some OEM ROMs fail androidx.work linkage).
        com.emux.core.EmuXBootstrapApp.workManagerAvailable(application)
    }

    // ------------------------------------------------------- permissions glue
    private fun requestNotificationIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** One-tap battery optimization exemption — the single biggest Termux-parity win. */
    private fun maybeAskBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                @Suppress("BatteryLife")
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:$packageName"))
                )
            } catch (_: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (_: Exception) { /* OEM without the screen: harmless */ }
            }
        }
    }

    // -------------------------------------------------------- power lifecycle
    override fun onResume() {
        super.onResume()
        desktop.foreground = true
        b.termContainer.findViewWithTag<VtTerminalView>("term")?.requestFocus()
    }

    override fun onPause() {
        super.onPause()
        desktop.foreground = false   // drops frame rate to idle instantly
    }

    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            desktop.screenOn = i?.action != Intent.ACTION_SCREEN_OFF
        }
    }

    override fun onStart() {
        super.onStart()
        // v0.1.3 CRASH FIX: on Android 14+ (targetSdk 34) registering a receiver
        // for SCREEN_ON/SCREEN_OFF requires the RECEIVER_NOT_EXPORTED flag —
        // without it, registerReceiver throws SecurityException here and this ran
        // on EVERY launch → instant "app keeps stopping" crash loop.
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(
                    screenReceiver,
                    android.content.IntentFilter().apply {
                        addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
                    },
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                registerReceiver(
                    screenReceiver,
                    android.content.IntentFilter().apply {
                        addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
                    }
                )
            }
        } catch (e: Throwable) {
            com.emux.EmuXLog.e("screen receiver registration failed (non-fatal)", e)
        }
    }

    override fun onStop() {
        runCatching { unregisterReceiver(screenReceiver) }
        super.onStop()
    }
}
