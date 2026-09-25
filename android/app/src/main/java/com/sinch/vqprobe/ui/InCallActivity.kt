package com.sinch.vqprobe.ui

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.sinch.vqprobe.graph

/** Minimal local UI required of a default dialer, including controls for unrelated incoming calls. */
class InCallActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var summary: TextView
    private lateinit var answerButton: Button
    private lateinit var rejectButton: Button
    private val refresh = object : Runnable {
        override fun run() {
            val status = graph().telecom.status()
            summary.text = "SINCH MOBILE VQ PROBE\n\n${status.optString("ui_number", "No call")}\n${status.optString("ui_state", status.optString("state"))}"
            answerButton.isEnabled = status.optBoolean("incoming")
            rejectButton.isEnabled = status.optBoolean("incoming")
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val margin = (20 * resources.displayMetrics.density).toInt()
            setPadding(margin, margin, margin, margin)
        }
        summary = TextView(this).apply { textSize = 23f; gravity = Gravity.CENTER; setPadding(0, 0, 0, 32) }
        container.addView(summary)
        fun button(label: String, action: () -> Unit): Button = Button(this).apply {
            text = label
            setOnClickListener {
                try { action() } catch (_: Exception) { Toast.makeText(this@InCallActivity, "Call control unavailable", Toast.LENGTH_SHORT).show() }
            }
            container.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        answerButton = button("Answer") { graph().telecom.answer() }
        rejectButton = button("Decline") { graph().telecom.reject() }
        button("Hang up") { graph().telecom.hangupLocal() }
        for (digits in listOf("123", "456", "789", "*0#")) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (digit in digits) {
                row.addView(Button(this).apply {
                    text = digit.toString()
                    setOnClickListener { graph().telecom.sendDtmf(digit) }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            container.addView(row)
        }
        button("Return to probe") { finish() }
        val scroll = ScrollView(this).apply { addView(container) }
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        setContentView(scroll)
    }

    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
}
