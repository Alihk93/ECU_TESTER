package com.alayed.ecutester

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.alayed.ecutester.ui.IntroView

/**
 * Cinematic intro splash (native port of the Claude Design handoff). Plays once
 * per cold start, over MainActivity, then ENTER hands off to the dashboard.
 * SETTING opens an on-device change-password modal (SharedPreferences, default
 * "00000000") — the native equivalent of the web modal in ecu-intro.jsx.
 */
class IntroActivity : AppCompatActivity() {

    private lateinit var intro: IntroView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_intro)
        intro = findViewById(R.id.intro)
        intro.onEnter = { enterDashboard() }
        intro.onSettings = { showPasswordDialog() }
        goImmersive()
    }

    private fun enterDashboard() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
    }

    private fun goImmersive() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    /* ---------------- change-password modal ---------------- */
    private fun prefs() = getSharedPreferences("ecu", MODE_PRIVATE)
    private fun storedPassword() = prefs().getString("ecu_tester_password", "00000000") ?: "00000000"

    private fun showPasswordDialog() {
        val dp = resources.displayMetrics.density
        fun d(v: Int) = (v * dp).toInt()

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(28), d(26), d(28), d(26))
            background = GradientDrawable().apply {
                cornerRadius = d(14).toFloat()
                setColor(Color.parseColor("#F00C1622"))
                setStroke(d(1), Color.parseColor("#665AB4FF"))
            }
        }
        fun title(t: String) = TextView(this).apply {
            text = t; setTextColor(Color.parseColor("#e8f4ff")); textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val msg = TextView(this).apply { textSize = 15f; visibility = View.GONE }
        val fields = HashMap<String, EditText>()
        fun field(label: String, key: String) {
            panel.addView(TextView(this).apply {
                text = label; setTextColor(Color.parseColor("#9fc3dd")); textSize = 15f
                setPadding(0, d(12), 0, d(6))
            })
            val e = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setTextColor(Color.parseColor("#e8f4ff")); textSize = 17f
                setPadding(d(14), d(12), d(14), d(12))
                background = GradientDrawable().apply {
                    cornerRadius = d(8).toFloat()
                    setColor(Color.parseColor("#D90A1420")); setStroke(d(1), Color.parseColor("#595AB4FF"))
                }
            }
            fields[key] = e
            panel.addView(e, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        panel.addView(title("Change Password"))
        field("Old password", "old"); field("New password", "new"); field("Confirm new password", "conf")
        msg.setPadding(0, d(12), 0, 0); panel.addView(msg)

        val dialog = Dialog(this)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, d(18), 0, 0) }
        fun mkBtn(label: String, primary: Boolean, fn: () -> Unit) = Button(this).apply {
            text = label; isAllCaps = false; textSize = 16f
            setTextColor(if (primary) Color.parseColor("#04240f") else Color.parseColor("#bcd8ec"))
            background = GradientDrawable().apply {
                cornerRadius = d(10).toFloat()
                setColor(if (primary) Color.parseColor("#12B872") else Color.parseColor("#E5142230"))
                setStroke(d(1), if (primary) Color.parseColor("#9978FFBE") else Color.parseColor("#4C5AB4FF"))
            }
            setOnClickListener { fn() }
        }
        row.addView(mkBtn("Cancel", false) { dialog.dismiss() },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = d(9) })
        row.addView(mkBtn("Save", true) {
            val old = fields["old"]!!.text.toString()
            val new = fields["new"]!!.text.toString()
            val conf = fields["conf"]!!.text.toString()
            fun bad(t: String) { msg.text = t; msg.setTextColor(Color.parseColor("#ff7a7a")); msg.visibility = View.VISIBLE }
            when {
                old != storedPassword() -> bad("Old password is incorrect.")
                // >= 8 chars: this password is destined for the WPA2-PSK AP key
                // (punchlist #4), and WPA2-PSK rejects anything shorter.
                new.length < 8 -> bad("New password must be at least 8 characters.")
                new != conf -> bad("Confirmation does not match.")
                else -> {
                    prefs().edit().putString("ecu_tester_password", new).apply()
                    msg.text = "Password updated."; msg.setTextColor(Color.parseColor("#3ce89a")); msg.visibility = View.VISIBLE
                    panel.postDelayed({ dialog.dismiss() }, 950)
                }
            }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = d(9) })
        panel.addView(row)
        panel.addView(TextView(this).apply {
            text = "Default password: 00000000"; setTextColor(Color.parseColor("#5f7d95")); textSize = 13f
            setPadding(0, d(14), 0, 0)
        })

        val wrap = LinearLayout(this).apply {
            gravity = Gravity.CENTER; setPadding(d(24), d(24), d(24), d(24))
            addView(panel, LinearLayout.LayoutParams(d(360), LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        dialog.setContentView(wrap)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        dialog.show()
    }
}
