package com.aimcoach.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Setup screen: enter current General + DPI, start / stop the coach. UI is built in code. */
class MainActivity : Activity() {

    private companion object {
        const val REQ_NOTIF = 1
        const val REQ_OVERLAY = 2
        const val REQ_CAPTURE = 3
    }

    private lateinit var engine: TuningEngine
    private lateinit var generalEt: EditText
    private lateinit var dpiEt: EditText
    private lateinit var statusTv: TextView
    private var notifAsked = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = TuningEngine.get(this)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(40), dp(20), dp(24))
        }

        col.addView(TextView(this).apply {
            text = "Aim Coach"
            textSize = 28f
            setTextColor(Color.BLACK)
        })
        col.addView(TextView(this).apply {
            text = "Observe-only helper for Free Fire TRAINING mode. It never sends taps, " +
                "never changes settings and never touches game files or memory. " +
                "You change General and DPI in the game yourself.\n\n" +
                "1. Enter your current General (0-100) and DPI.\n" +
                "2. Press Start coach, allow \"Display over other apps\" and screen capture " +
                "(choose entire screen).\n" +
                "3. Open Free Fire training. Use LEARN, STOP, CHECK on the floating panel."
            textSize = 14f
            setPadding(0, dp(8), 0, dp(16))
        })

        val c = engine.current()
        generalEt = EditText(this).apply {
            hint = "General sensitivity (0-100)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(c.g.toString())
        }
        dpiEt = EditText(this).apply {
            hint = "DPI"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(c.d.toString())
        }
        col.addView(generalEt)
        col.addView(dpiEt)

        col.addView(Button(this).apply {
            text = "Start coach"
            setOnClickListener { onStartClicked() }
        })
        col.addView(Button(this).apply {
            text = "Stop coach"
            setOnClickListener {
                stopService(Intent(this@MainActivity, CaptureService::class.java))
                updateStatus()
            }
        })
        col.addView(Button(this).apply {
            text = "Reset learned data"
            setOnClickListener {
                engine.reset()
                Toast.makeText(this@MainActivity, "Learned data cleared", Toast.LENGTH_SHORT).show()
                updateStatus()
            }
        })

        statusTv = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(16), 0, 0)
        }
        col.addView(statusTv)

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val running = if (CaptureService.running) "Coach is running." else "Coach is not running."
        statusTv.text = "$running\n${engine.statusLine()}\n${engine.statsLine()}\n${engine.bestLine()}"
    }

    private fun onStartClicked() {
        if (CaptureService.running) {
            Toast.makeText(this, "Already running. Press Stop coach first.", Toast.LENGTH_LONG).show()
            return
        }
        val g = generalEt.text.toString().trim().toIntOrNull()
        val d = dpiEt.text.toString().trim().toIntOrNull()
        if (g == null || g !in 0..100) {
            Toast.makeText(this, "General must be 0-100", Toast.LENGTH_LONG).show()
            return
        }
        if (d == null || d !in TuningEngine.DPI_MIN..TuningEngine.DPI_MAX) {
            Toast.makeText(
                this, "DPI must be ${TuningEngine.DPI_MIN}-${TuningEngine.DPI_MAX}", Toast.LENGTH_LONG
            ).show()
            return
        }
        engine.setCurrent(g, d)
        proceed()
    }

    /** Walks through the permissions one at a time, then asks for screen capture. */
    private fun proceed() {
        if (Build.VERSION.SDK_INT >= 33 && !notifAsked &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifAsked = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow \"Display over other apps\" for Aim Coach, then come back", Toast.LENGTH_LONG).show()
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                REQ_OVERLAY
            )
            return
        }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIF) proceed() // continue even if denied; the notification is just hidden
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_OVERLAY -> {
                if (Settings.canDrawOverlays(this)) {
                    proceed()
                } else {
                    Toast.makeText(this, "Overlay permission is required", Toast.LENGTH_LONG).show()
                }
            }
            REQ_CAPTURE -> {
                if (resultCode == RESULT_OK && data != null) {
                    val i = Intent(this, CaptureService::class.java)
                        .putExtra(CaptureService.EXTRA_CODE, resultCode)
                        .putExtra(CaptureService.EXTRA_DATA, data)
                    startForegroundService(i)
                    Toast.makeText(this, "Coach started. Open Free Fire training.", Toast.LENGTH_LONG).show()
                    moveTaskToBack(true)
                } else {
                    Toast.makeText(this, "Screen capture was not allowed", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
