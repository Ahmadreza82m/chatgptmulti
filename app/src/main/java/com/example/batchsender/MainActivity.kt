package com.example.batchsender

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.*

/**
 * Safe launcher for MultiSend Control Center.
 *
 * The old Accessibility foreground-switching automation is intentionally not
 * started from this activity. MultiSend now runs its automation in the
 * Linux/Termux browser backend, keeping Android apps out of the control loop.
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(28, 48, 28, 48)
        }

        root.addView(TextView(this).apply {
            text = "MultiSend AI"
            textSize = 30f
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "Control Center"
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 22)
        })

        root.addView(TextView(this).apply {
            text = "اتوماسیون قدیمی Accessibility غیرفعال شده است.\n\nنسخه جدید از Sessionهای مستقل مرورگر در Linux/Termux استفاده می‌کند و دیگر گوشی را بین چند اپ جابه‌جا نمی‌کند."
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 28)
        })

        root.addView(Button(this).apply {
            text = "باز کردن Control Center"
            setOnClickListener { openControlCenter() }
        })

        root.addView(Button(this).apply {
            text = "راهنمای راه‌اندازی Linux + VNC"
            setOnClickListener { openSetupGuide() }
        })

        root.addView(TextView(this).apply {
            text = "توقف کامل از داخل Control Center در دسترس است. در این نسخه هیچ کنترل خودکاری روی اپ‌های اندروید انجام نمی‌شود."
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, 28, 0, 0)
        })

        setContentView(root)
    }

    private fun openControlCenter() {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:8787")))
    }

    private fun openSetupGuide() {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Ahmadreza82m/chatgptmulti/tree/main/multisend-linux")))
    }
}
