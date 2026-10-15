package com.example.batchsender

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.*

class MainActivity : Activity() {
    private val images = mutableListOf<Uri>()
    private lateinit var prompt: EditText
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIncoming(intent)
        buildUi()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    private fun handleIncoming(intent: Intent?) {
        if (intent == null) return
        images.clear()
        if (intent.action == Intent.ACTION_SEND && intent.type?.startsWith("image/") == true) {
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { images.add(it) }
        } else if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { images.addAll(it) }
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        root.addView(TextView(this).apply {
            text = "Batch Sender — تست ۲ مقصدی"
            textSize = 24f
        })

        status = TextView(this).apply {
            text = "عکس‌های دریافت‌شده: ${images.size}"
            textSize = 16f
            setPadding(0, 24, 0, 16)
        }
        root.addView(status)

        prompt = EditText(this).apply {
            hint = "پرامپت مشترک را فقط یک بار وارد کنید"
            minLines = 4
            gravity = Gravity.TOP
        }
        root.addView(prompt)

        root.addView(Button(this).apply {
            text = "فعال‌کردن دسترسی اتوماسیون"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        })

        root.addView(Button(this).apply {
            text = "شروع تست"
            setOnClickListener {
                if (images.size < 2) {
                    Toast.makeText(this@MainActivity, "حداقل ۲ عکس انتخاب کنید.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                BatchController.start(this@MainActivity, images.take(2), prompt.text.toString())
            }
        })

        setContentView(root)
    }
}
