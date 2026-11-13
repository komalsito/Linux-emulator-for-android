package com.emux

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * v0.2.129 COLOR WHEEL: visual picker for the Terminal color settings
 * (Settings > Terminal colors). Tapping a color row opens a framework
 * AlertDialog - the dialog path the field ROM reliably renders (see
 * SettingsActivity.attachFrameworkDialogDispatch) - holding:
 *
 *   - a hue/saturation disc (SweepGradient hue ring + a white radial
 *     saturation overlay; drag anywhere on it),
 *   - a brightness slider (0-100%, re-tints the whole disc),
 *   - a live preview swatch + hex field (#RRGGBB - the old hex workflow
 *     keeps working inside the same dialog),
 *   - a "Scheme default" button that clears the override.
 *
 * OK persists through the SAME term_color_* keys the v0.2.127 hex fields
 * used, so nothing else in the theme pipeline changes.
 */
class ColorWheelView(context: Context) : View(context) {

    /** Fired on every drag with the color currently under the pointer. */
    var onColorPicked: ((Int) -> Unit)? = null

    var hue = 0f; private set      // degrees 0..360 (0 = red, at 3 o'clock)
    var sat = 1f; private set      // 0 (center) .. 1 (rim)
    var value = 1f; private set    // brightness 0..1 (the slider)

    private val huePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val satPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringOuter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 7f; color = 0xCC000000.toInt()
    }
    private val ringInner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.WHITE
    }
    private var cx = 0f; private var cy = 0f; private var radius = 0f

    /** Current selection as an ARGB int. */
    fun currentColor(): Int = Color.HSVToColor(floatArrayOf(hue, sat, value))

    /** Jump the pointer to a color (used to open on the current theme color). */
    fun setHsv(h: Float, s: Float, v: Float) {
        hue = ((h % 360f) + 360f) % 360f
        sat = s.coerceIn(0f, 1f)
        setValue(v)
    }

    /** Brightness change re-tints the whole disc (gradients rebuilt). */
    fun setValue(v: Float) {
        val nv = v.coerceIn(0f, 1f)
        if (nv != value || huePaint.shader == null) {
            value = nv
            rebuildShaders()
        }
        invalidate()
    }

    private fun rebuildShaders() {
        if (radius <= 0f) return
        val hues = IntArray(13) { i -> Color.HSVToColor(floatArrayOf(i * 30f, 1f, value)) }
        huePaint.shader = SweepGradient(cx, cy, hues, null)
        val white = Color.HSVToColor(floatArrayOf(0f, 0f, value))
        satPaint.shader = RadialGradient(cx, cy, radius,
            intArrayOf(white, white and 0x00FFFFFF), null, Shader.TileMode.CLAMP)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        cx = w / 2f; cy = h / 2f
        // small margin so the pointer ring never clips at the rim
        radius = min(w, h) / 2f * 0.90f
        rebuildShaders()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (radius <= 0f) return
        canvas.drawCircle(cx, cy, radius, huePaint)
        canvas.drawCircle(cx, cy, radius, satPaint)
        val rad = Math.toRadians(hue.toDouble())
        val px = cx + cos(rad).toFloat() * radius * sat
        val py = cy + sin(rad).toFloat() * radius * sat
        canvas.drawCircle(px, py, 16f, ringOuter)
        canvas.drawCircle(px, py, 16f, ringInner)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val dx = event.x - cx
                val dy = event.y - cy
                hue = ((Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) + 360.0) % 360.0).toFloat()
                sat = (hypot(dx.toDouble(), dy.toDouble()) / radius).toFloat().coerceIn(0f, 1f)
                onColorPicked?.invoke(currentColor())
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}

/**
 * v0.2.129: opens the color wheel dialog for one Terminal color preference.
 * Persists to the same term_color_* key the v0.2.127 hex fields used, keeps
 * the row summary in sync, and re-applies the live theme through [onApplied]
 * (the same Handler-posted VtTerminalView.applyTheme tap).
 */
internal fun showColorWheelDialog(
    ctx: Context,
    pref: androidx.preference.Preference,
    label: String,
    onApplied: () -> Unit,
) {
    val key = pref.key ?: return
    // framework dialogs need an Activity context for the device-default theme
    var actCtx: Context = ctx
    while (actCtx !is android.app.Activity && actCtx is ContextWrapper) actCtx = actCtx.baseContext
    val app = ctx.applicationContext
    runCatching {
        val sp = androidx.preference.PreferenceManager.getDefaultSharedPreferences(app)
        val saved = (sp.getString(key, "") ?: "").trim()
        val schemeColor = com.emux.terminal.VtTerminalView.schemeColorOf(app, key)
        val d = ctx.resources.displayMetrics.density
        val pad = (20 * d).toInt()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        val wheelSide = minOf(
            ctx.resources.displayMetrics.widthPixels * 3 / 4,
            (300 * d).toInt()).coerceAtLeast((200 * d).toInt())
        val wheel = ColorWheelView(ctx)
        root.addView(wheel, LinearLayout.LayoutParams(wheelSide, wheelSide).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })

        root.addView(TextView(ctx).apply {
            text = "Brightness"
            textSize = 14f
            setPadding(0, (14 * d).toInt(), 0, (4 * d).toInt())
        })
        val bar = SeekBar(ctx).apply { max = 100 }
        root.addView(bar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (12 * d).toInt(), 0, 0)
        }
        val swatch = View(ctx)
        row.addView(swatch, LinearLayout.LayoutParams((44 * d).toInt(), (44 * d).toInt()))
        val hexEdit = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            hint = "#RRGGBB (empty = scheme default)"
            maxLines = 1
        }
        row.addView(hexEdit, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = (12 * d).toInt()
        })
        root.addView(row)

        // keep wheel -> hex -> swatch -> slider in sync (the hex listener never
        // writes text back, so the listeners cannot loop)
        fun sync(c: Int, setHex: Boolean) {
            if (setHex) hexEdit.setText(String.format(java.util.Locale.US, "#%06X", 0xFFFFFF and c))
            swatch.setBackgroundColor(c)
            bar.progress = (wheel.value * 100f).toInt().coerceIn(0, 100)
        }
        wheel.onColorPicked = { c -> sync(c, true) }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    wheel.setValue(p / 100f)
                    sync(wheel.currentColor(), true)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        hexEdit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val c = runCatching { Color.parseColor(s?.toString()?.trim() ?: "") }.getOrNull()
                    ?: return
                val hsv = FloatArray(3)
                Color.colorToHSV(c, hsv)
                wheel.setHsv(hsv[0], hsv[1], hsv[2])
                swatch.setBackgroundColor(c)
                bar.progress = (hsv[2] * 100f).toInt().coerceIn(0, 100)
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        // open exactly on the color the terminal uses right now
        val start = if (saved.length == 7 && saved.startsWith("#"))
            runCatching { Color.parseColor(saved) }.getOrNull() else null
        val startColor = start ?: if (schemeColor != -1) schemeColor else 0xFF808080.toInt()
        val hsv = FloatArray(3)
        Color.colorToHSV(startColor, hsv)
        wheel.setHsv(hsv[0], hsv[1], hsv[2])
        hexEdit.setText(saved)
        swatch.setBackgroundColor(startColor)
        bar.progress = (hsv[2] * 100f).toInt().coerceIn(0, 100)

        val dlg = android.app.AlertDialog.Builder(actCtx,
            android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(label + " - pick a color")
            .setView(root)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton("Scheme default", null)
            .create()
        dlg.show()
        // validation replaces the auto-dismiss listener: an invalid hex keeps
        // the dialog open instead of silently dropping the edit
        dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val v = hexEdit.text.toString().trim()
            val ok = v.isEmpty() || (v.length == 7 && v.startsWith("#") &&
                runCatching { Color.parseColor(v) }.isSuccess)
            if (!ok) {
                android.widget.Toast.makeText(app,
                    "Use #RRGGBB (e.g. #5AA7FF) or leave empty",
                    android.widget.Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            sp.edit().putString(key, v).apply()
            pref.summary = if (v.isEmpty()) "Scheme default" else v
            onApplied()
            dlg.dismiss()
        }
        dlg.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            sp.edit().putString(key, "").apply()
            pref.summary = "Scheme default"
            onApplied()
            dlg.dismiss()
        }
    }.onFailure { t ->
        com.emux.EmuXLog.e("color wheel dialog failed", t)
        android.widget.Toast.makeText(app,
            "Could not open the color picker", android.widget.Toast.LENGTH_LONG).show()
    }
}
