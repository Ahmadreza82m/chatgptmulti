package com.example.batchsender

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*

class MainActivity : Activity() {
    private val images = mutableListOf<Uri>()
    private val destinationSpinners = mutableListOf<Spinner>()
    private lateinit var prompt: EditText
    private lateinit var status: TextView
    private lateinit var destinationsContainer: LinearLayout
    private lateinit var apps: List<AppOption>

    private data class AppOption(val label: String, val packageName: String) {
        override fun toString(): String = label
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        apps = loadLaunchableApps()
        handleIncoming(intent)
        buildUi()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
        updateStatus()
    }

    private fun handleIncoming(intent: Intent?) {
        if (intent == null) return
        if (intent.action == Intent.ACTION_SEND && intent.type?.startsWith("image/") == true) {
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let {
                if (!images.contains(it)) images.add(it)
            }
        } else if (intent.action == Intent.ACTION_SEND_MULTIPLE && intent.type?.startsWith("image/") == true) {
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { list ->
                list.forEach { if (!images.contains(it)) images.add(it) }
            }
        }
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 40)
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "ChatGPT Multi Batch"
            textSize = 25f
        })
        root.addView(TextView(this).apply {
            text = "هر تصویر → یک مقصد مستقل → یک درخواست جداگانه"
            textSize = 15f
            setPadding(0, 8, 0, 20)
        })

        status = TextView(this).apply { textSize = 16f }
        root.addView(status)

        root.addView(Button(this).apply {
            text = "انتخاب چند تصویر"
            setOnClickListener { pickImages() }
        })

        prompt = EditText(this).apply {
            hint = "پرامپت مشترک را فقط یک بار وارد کنید"
            minLines = 5
            gravity = Gravity.TOP
        }
        root.addView(prompt)

        root.addView(TextView(this).apply {
            text = "مقصدها (ترتیب مقصدها با ترتیب تصاویر یکی است)"
            textSize = 18f
            setPadding(0, 20, 0, 8)
        })

        destinationsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(destinationsContainer)

        addDestinationRow()
        addDestinationRow()

        root.addView(Button(this).apply {
            text = "+ افزودن مقصد"
            setOnClickListener {
                if (destinationSpinners.size < 20) addDestinationRow()
                else Toast.makeText(this@MainActivity, "حداکثر ۲۰ مقصد در این نسخه.", Toast.LENGTH_SHORT).show()
            }
        })

        root.addView(Button(this).apply {
            text = "فعال‌کردن دسترسی اتوماسیون"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })

        root.addView(Button(this).apply {
            text = "شروع پردازش"
            setOnClickListener { startBatch() }
        })

        root.addView(TextView(this).apply {
            text = "نکته: مقصدها باید Web Appهای مستقل و نصب‌شده باشند. برای هر تصویر دقیقاً یک مقصد انتخاب کنید."
            textSize = 13f
            setPadding(0, 18, 0, 0)
        })

        updateStatus()
        setContentView(scroll)
    }

    private fun addDestinationRow() {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, apps)
        spinner.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        row.addView(spinner)

        val remove = Button(this).apply {
            text = "حذف"
            setOnClickListener {
                if (destinationSpinners.size > 1) {
                    destinationSpinners.remove(spinner)
                    destinationsContainer.removeView(row)
                }
            }
        }
        row.addView(remove)
        destinationSpinners.add(spinner)
        destinationsContainer.addView(row)
    }

    private fun pickImages() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(intent, REQUEST_IMAGES)
    }

    @Deprecated("Legacy activity result API kept for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_IMAGES || resultCode != RESULT_OK || data == null) return
        images.clear()
        data.data?.let { images.add(it) }
        data.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) images.add(clip.getItemAt(i).uri)
        }
        updateStatus()
    }

    private fun startBatch() {
        if (images.isEmpty()) {
            Toast.makeText(this, "اول تصویرها را انتخاب کنید.", Toast.LENGTH_LONG).show()
            return
        }
        if (prompt.text.toString().isBlank()) {
            Toast.makeText(this, "پرامپت را وارد کنید.", Toast.LENGTH_LONG).show()
            return
        }
        if (destinationSpinners.size < images.size) {
            Toast.makeText(this, "برای هر تصویر یک مقصد لازم است. مقصد اضافه کنید.", Toast.LENGTH_LONG).show()
            return
        }

        val destinations = destinationSpinners.take(images.size).map {
            val app = it.selectedItem as AppOption
            BatchController.Destination(app.packageName, app.label)
        }

        if (!BatchController.start(this, images.toList(), prompt.text.toString(), destinations)) {
            Toast.makeText(this, "سرویس Accessibility فعال نیست یا صف شروع نشد. ابتدا دسترسی را فعال کنید.", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "شروع شد: ${images.size} تصویر مستقل.", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateStatus() {
        if (::status.isInitialized) status.text = "تصاویر انتخاب‌شده: ${images.size} | مقصدهای آماده: ${destinationSpinners.size}"
    }

    private fun loadLaunchableApps(): List<AppOption> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .map { resolve ->
                AppOption(
                    resolve.loadLabel(packageManager).toString().ifBlank { resolve.activityInfo.packageName },
                    resolve.activityInfo.packageName
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    companion object { private const val REQUEST_IMAGES = 501 }
}
