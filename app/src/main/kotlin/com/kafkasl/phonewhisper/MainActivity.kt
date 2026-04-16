package com.kafkasl.phonewhisper

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {

    private lateinit var audioStatus: TextView
    private lateinit var accStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(64), dp(24), dp(32))
        }
        scroll.addView(root)
        setContentView(scroll)

        // Title
        root.addView(TextView(this).apply {
            text = "PTT — Vosk on Optiplex"
            textSize = 28f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        })

        root.addView(TextView(this).apply {
            text = "Hold the floating mic button to talk. Audio streams to Optiplex, " +
                    "Vosk transcribes in real-time, words appear at the cursor."
            textSize = 15f
            setTextColor(0xFF888888.toInt())
            setPadding(0, 0, 0, dp(32))
        })

        // Launch button
        MaterialButton(this).apply {
            text = "Launch mic button"
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(32)) }
            setOnClickListener {
                val svc = WhisperAccessibilityService.instance
                if (svc == null) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Accessibility service not running")
                        .setMessage("Enable \"Phone Whisper\" in Accessibility Settings first.")
                        .setPositiveButton("Open Settings") { _, _ ->
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                } else {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Launch mic button?")
                        .setMessage("The floating PTT button will appear on screen.")
                        .setPositiveButton("Launch") { _, _ -> svc.show() }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
        }.also { root.addView(it) }

        // Server info
        sectionHeader("Server", root)
        root.addView(row("LAN", WhisperAccessibilityService.LAN_WS, root))
        root.addView(row("Tailscale", WhisperAccessibilityService.TS_WS, root))

        root.addView(View(this).apply {
            setBackgroundColor(0x22FFFFFF)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply { setMargins(0, dp(24), 0, dp(24)) }
        })

        // Audio permission
        sectionHeader("Permissions", root)
        audioStatus = statusRow("Microphone", root)

        val audioBtn = MaterialButton(this).apply {
            text = "Grant"
            textSize = 13f
            setPadding(dp(16), 0, dp(16), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(8), 0, dp(16)) }
            setOnClickListener {
                ActivityCompat.requestPermissions(
                    this@MainActivity,
                    arrayOf(Manifest.permission.RECORD_AUDIO), 1
                )
            }
        }
        root.addView(audioBtn)

        // Accessibility service
        accStatus = statusRow("Accessibility service", root)

        MaterialButton(this).apply {
            text = "Open Accessibility Settings"
            textSize = 13f
            setPadding(dp(16), 0, dp(16), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(8), 0, dp(16)) }
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }.also { root.addView(it) }

        root.addView(View(this).apply {
            setBackgroundColor(0x22FFFFFF)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply { setMargins(0, dp(8), 0, dp(24)) }
        })

        // Instructions
        sectionHeader("How to use", root)
        root.addView(TextView(this).apply {
            text = "1. Enable the Accessibility Service above (\"PTT Vosk\")\n" +
                   "2. Make sure vosk-ws-server.py is running on Optiplex (port 9877)\n" +
                   "3. The floating mic button will appear — drag it anywhere\n" +
                   "4. Hold the button while speaking, release to finalise\n" +
                   "5. Words appear at the cursor on Optiplex in real-time"
            textSize = 14f
            setTextColor(0xFFCCCCCC.toInt())
            setLineSpacing(0f, 1.5f)
        })

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatus()
    }

    private fun refreshStatus() {
        val hasAudio = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val hasAcc = WhisperAccessibilityService.instance != null

        audioStatus.text = if (hasAudio) "✓ Granted" else "✗ Not granted"
        audioStatus.setTextColor(if (hasAudio) 0xFF4CAF50.toInt() else 0xFFEF5350.toInt())

        accStatus.text = if (hasAcc) "✓ Running" else "✗ Not enabled"
        accStatus.setTextColor(if (hasAcc) 0xFF4CAF50.toInt() else 0xFFEF5350.toInt())
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun sectionHeader(text: String, parent: LinearLayout) {
        parent.addView(TextView(this).apply {
            this.text = text.uppercase()
            textSize = 11f
            letterSpacing = 0.12f
            setTextColor(0xFF888888.toInt())
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        })
    }

    private fun row(label: String, value: String, parent: LinearLayout): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 14f
                setTextColor(0xFFAAAAAA.toInt())
                layoutParams = LinearLayout.LayoutParams(dp(80), LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            addView(TextView(this@MainActivity).apply {
                text = value
                textSize = 13f
                setTextColor(0xFFFFFFFF.toInt())
                typeface = Typeface.MONOSPACE
            })
        }
    }

    private fun statusRow(label: String, parent: LinearLayout): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        row.addView(TextView(this).apply {
            text = label
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        })
        val status = TextView(this).apply {
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
        }
        row.addView(status)
        parent.addView(row)
        return status
    }
}
