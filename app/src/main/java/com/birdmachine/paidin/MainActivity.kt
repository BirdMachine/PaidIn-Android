package com.birdmachine.paidin

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels

class MainActivity : ComponentActivity() {
    private val viewModel: PaidInViewModel by viewModels()
    private var showingCrashRecovery = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("paidin", MODE_PRIVATE)
        val lastCrash = prefs.getString("last_crash", null)
        if (!lastCrash.isNullOrBlank()) {
            showingCrashRecovery = true
            showCrashRecovery(lastCrash)
            return
        }

        enableEdgeToEdge()
        consumeShare(intent)
        setContent { PaidInTheme { PaidInApp(viewModel) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!showingCrashRecovery) consumeShare(intent)
    }

    private fun consumeShare(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let(viewModel::importSharedText)
        }
    }

    private fun showCrashRecovery(report: String) {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
            setBackgroundColor(0xFF001D4C.toInt())
        }

        container.addView(TextView(this).apply {
            text = "PaidIn hit a startup crash"
            textSize = 24f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(typeface, Typeface.BOLD)
        })

        container.addView(TextView(this).apply {
            text = "I saved the exception instead of crash-looping again. Copy it for debugging, then tap Try PaidIn again after installing a fix."
            textSize = 16f
            setTextColor(0xFFD5F7FF.toInt())
            setPadding(0, dp(10), 0, dp(16))
        })

        container.addView(TextView(this).apply {
            text = report
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(0x66000000)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        container.addView(Button(this).apply {
            text = "Copy crash report"
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("PaidIn crash report", report))
                text = "Copied ✓"
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(16)
        })

        container.addView(Button(this).apply {
            text = "Try PaidIn again"
            setOnClickListener {
                getSharedPreferences("paidin", MODE_PRIVATE).edit()
                    .remove("last_crash")
                    .remove("last_crash_at")
                    .apply()
                recreate()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(container, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(scroll)
    }
}
