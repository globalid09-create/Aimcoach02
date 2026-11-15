package com.aimcoach.app

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Small draggable floating panel. It is NOT focusable and semi-transparent so the game
 * keeps receiving input everywhere outside the panel. It only displays information and
 * records what the player tells it; it never sends input anywhere.
 */
class OverlayPanel(
    private val ctx: Context,
    private val engine: TuningEngine,
    private val cb: Callbacks
) {
    interface Callbacks {
        fun onLearn(on: Boolean)
        fun onExit()
    }

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = ctx.resources.displayMetrics.density
    private val main = Handler(Looper.getMainLooper())
    private val stepOptions = intArrayOf(1, 2, 3, 5)
    private var stepPos = 0
    private var learning = false
    private var added = false
    private var collapsed = false

    private lateinit var root: LinearLayout
    private lateinit var body: LinearLayout
    private lateinit var title: TextView
    private lateinit var hideBtn: Button
    private lateinit var statusTv: TextView
    private lateinit var statsTv: TextView
    private lateinit var adviceTv: TextView
    private lateinit var bestTv: TextView
    private lateinit var hintTv: TextView
    private lateinit var learnBtn: Button
    private lateinit var stepBtn: Button

    private val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 24
        y = 24
        alpha = 0.78f // Android caps overlay opacity for touch-passthrough at 0.8
    }

    private fun dp(v: Int) = (v * density).toInt()

    private fun round(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun text(size: Float, color: Int) = TextView(ctx).apply {
        textSize = size
        setTextColor(color)
    }

    private fun btn(label: String, color: Int, onClick: () -> Unit) = Button(ctx).apply {
        text = label
        isAllCaps = false
        textSize = 12f
        setTextColor(Color.WHITE)
        minHeight = 0
        minimumHeight = 0
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(6), dp(5), dp(6), dp(5))
        background = round(color, 8)
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        for (v in views) {
            val p = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            p.setMargins(dp(2), dp(2), dp(2), dp(2))
            addView(v, p)
        }
    }

    private fun build() {
        root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(8))
            minimumWidth = dp(250)
            background = round(0xE0101418.toInt(), 12)
        }

        title = text(13f, Color.WHITE).apply { text = "Aim Coach  (drag)" }
        hideBtn = btn("Hide", 0xFF455A64.toInt()) { toggleCollapsed() }
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(hideBtn)
        }
        root.addView(header)

        body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        statusTv = text(14f, Color.WHITE)
        statsTv = text(13f, 0xFFB0BEC5.toInt())
        adviceTv = text(13f, 0xFFFFD54F.toInt()).apply {
            maxWidth = dp(250)
            text = engine.lastAdvice()
        }
        bestTv = text(12f, 0xFF81C784.toInt())
        hintTv = text(11f, 0xFF90A4AE.toInt())
        body.addView(statusTv)
        body.addView(statsTv)
        body.addView(adviceTv)
        body.addView(bestTv)

        learnBtn = btn("LEARN", 0xFF2E7D32.toInt()) { onLearnClick() }
        val checkBtn = btn("CHECK", 0xFF1565C0.toInt()) { onCheckClick() }
        body.addView(row(learnBtn, checkBtn))

        body.addView(
            row(
                btn("G-", 0xFF6A1B9A.toInt()) { change(true, -1) },
                btn("G+", 0xFF6A1B9A.toInt()) { change(true, +1) },
                btn("D-", 0xFF00838F.toInt()) { change(false, -1) },
                btn("D+", 0xFF00838F.toInt()) { change(false, +1) }
            )
        )

        stepBtn = btn("", 0xFF455A64.toInt()) { cycleStep() }
        val exitBtn = btn("Exit", 0xFFB71C1C.toInt()) { cb.onExit() }
        body.addView(row(stepBtn, exitBtn))
        body.addView(hintTv)
        updateStepLabel()
        root.addView(body)

        // Drag by the title.
        title.setOnTouchListener(object : View.OnTouchListener {
            var startX = 0
            var startY = 0
            var downX = 0f
            var downY = 0f
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = lp.x; startY = lp.y
                        downX = e.rawX; downY = e.rawY
                    }
                    MotionEvent.ACTION_MOVE -> {
                        lp.x = startX + (e.rawX - downX).toInt()
                        lp.y = startY + (e.rawY - downY).toInt()
                        if (added) wm.updateViewLayout(root, lp)
                    }
                }
                return true
            }
        })
    }

    fun show() {
        if (added) return
        build()
        wm.addView(root, lp)
        added = true
        refresh()
    }

    fun remove() {
        if (!added) return
        try {
            wm.removeView(root)
        } catch (_: Exception) {
        }
        added = false
    }

    fun resetPosition() {
        main.post {
            lp.x = 24
            lp.y = 24
            if (added) wm.updateViewLayout(root, lp)
        }
    }

    fun refresh() {
        if (Looper.myLooper() == Looper.getMainLooper()) doRefresh() else main.post { doRefresh() }
    }

    private fun doRefresh() {
        if (!added) return
        statusTv.text = engine.statusLine()
        statsTv.text = engine.statsLine()
        bestTv.text = engine.bestLine()
        title.text = if (learning) "Aim Coach  * LEARNING" else "Aim Coach  (drag)"
        title.setTextColor(if (learning) 0xFFFF8A80.toInt() else Color.WHITE)
    }

    private fun toggleCollapsed() {
        collapsed = !collapsed
        body.visibility = if (collapsed) View.GONE else View.VISIBLE
        hideBtn.text = if (collapsed) "Show" else "Hide"
    }

    private fun onLearnClick() {
        learning = !learning
        learnBtn.text = if (learning) "STOP" else "LEARN"
        learnBtn.background = round(if (learning) 0xFFC62828.toInt() else 0xFF2E7D32.toInt(), 8)
        adviceTv.text = if (learning) {
            "Learning... fire at the dummy. Press STOP when done."
        } else {
            "Stopped. Press CHECK for the result."
        }
        cb.onLearn(learning)
        refresh()
    }

    private fun onCheckClick() {
        if (learning) {
            adviceTv.text = "Press STOP first, then CHECK."
            return
        }
        adviceTv.text = engine.check()
        refresh()
    }

    private fun change(general: Boolean, sign: Int) {
        val m = stepOptions[stepPos] * sign
        if (general) engine.changeGeneral(m) else engine.changeDpi(m)
        val c = engine.current()
        adviceTv.text = "Now General ${c.g}, DPI ${c.d}. Press LEARN."
        refresh()
    }

    private fun cycleStep() {
        stepPos = (stepPos + 1) % stepOptions.size
        updateStepLabel()
    }

    private fun updateStepLabel() {
        val m = stepOptions[stepPos]
        stepBtn.text = "Step x$m"
        hintTv.text = "x$m = General +/-$m, DPI +/-${m * TuningEngine.DPI_UNIT} per tap"
    }
}
