package com.example.batchsender

import android.accessibilityservice.AccessibilityService
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

class BatchAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile private var instance: BatchAccessibilityService? = null
        private const val LAUNCH_DELAY = 1400L
        private const val SHARE_DELAY = 1800L
        private const val GENERATION_TIMEOUT = 120_000L
        private const val DOWNLOAD_WAIT = 5000L
        private const val NEXT_DELAY = 2500L

        fun startQueue(context: android.content.Context, jobs: List<BatchController.Job>): Boolean {
            val service = instance ?: return false
            service.begin(jobs)
            Toast.makeText(context, "صف ${jobs.size} عملیات شروع شد.", Toast.LENGTH_SHORT).show()
            return true
        }
    }

    private enum class State {
        IDLE, LAUNCHING, SHARING, WAITING_FOR_COMPLETION, DOWNLOADING
    }

    private val handler = Handler(Looper.getMainLooper())
    private var jobs: List<BatchController.Job> = emptyList()
    private var index = 0
    private var state = State.IDLE
    private var sentAt = 0L
    private var sendClicked = false
    private var generationIndicatorSeen = false
    private var downloadClicked = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() = stopQueue()

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val job = jobs.getOrNull(index) ?: return
        val packageName = event?.packageName?.toString()
        if (packageName != null && packageName != job.destination.packageName) return

        val root = rootInActiveWindow ?: return
        when (state) {
            State.SHARING -> handleSharing(root, job)
            State.WAITING_FOR_COMPLETION -> handleGeneration(root)
            State.DOWNLOADING -> Unit
            else -> Unit
        }
    }

    private fun begin(newJobs: List<BatchController.Job>) {
        handler.removeCallbacksAndMessages(null)
        jobs = newJobs
        index = 0
        state = State.IDLE
        launchCurrent()
    }

    private fun launchCurrent() {
        val job = jobs.getOrNull(index) ?: run { stopQueue(); return }

        state = State.LAUNCHING
        sendClicked = false
        generationIndicatorSeen = false
        downloadClicked = false

        try {
            val launch = packageManager.getLaunchIntentForPackage(job.destination.packageName)
                ?: throw ActivityNotFoundException("No launcher activity")
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(launch)
        } catch (_: Exception) {
            Toast.makeText(this, "مقصد ${index + 1} قابل اجرا نیست؛ می‌روم سراغ بعدی.", Toast.LENGTH_LONG).show()
            scheduleNext()
            return
        }

        handler.postDelayed({ shareCurrent(job) }, LAUNCH_DELAY)
    }

    private fun shareCurrent(job: BatchController.Job) {
        if (jobs.getOrNull(index) !== job) return
        state = State.SHARING

        val share = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_STREAM, job.image)
            putExtra(Intent.EXTRA_TEXT, job.prompt)
            putExtra(Intent.EXTRA_TITLE, "ChatGPT Multi Batch")
            clipData = ClipData.newRawUri("image", job.image)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setPackage(job.destination.packageName)
        }

        try {
            startActivity(share)
        } catch (_: Exception) {
            // If the wrapper does not implement ACTION_SEND, leave it open and
            // let Accessibility try to fill the composer and press Send.
        }

        handler.postDelayed({
            if (jobs.getOrNull(index) === job && state == State.SHARING) {
                handleSharing(rootInActiveWindow, job)
            }
        }, SHARE_DELAY)
    }

    private fun handleSharing(root: AccessibilityNodeInfo?, job: BatchController.Job) {
        if (root == null || sendClicked) return

        // EXTRA_TEXT is not honored by every Web App wrapper, so explicitly fill
        // the first editable composer when it is empty or different from the job prompt.
        val editor = findEditable(root)
        if (editor != null && isLikelyEnabled(editor)) {
            val current = editor.text?.toString().orEmpty()
            if (current != job.prompt) {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, job.prompt)
                }
                editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
        }

        val stop = findAction(root, listOf("Stop generating", "Stop", "توقف", "در حال تولید"))
        if (stop != null) generationIndicatorSeen = true

        val send = findAction(root, listOf("Send", "ارسال", "Submit", "فرستادن"))
        if (send != null && isLikelyEnabled(send)) clickSend(send)
    }

    private fun clickSend(node: AccessibilityNodeInfo) {
        if (sendClicked) return
        sendClicked = true
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        state = State.WAITING_FOR_COMPLETION
        sentAt = System.currentTimeMillis()
        handler.postDelayed({ checkGenerationTimeout() }, GENERATION_TIMEOUT + 500L)
    }

    private fun handleGeneration(root: AccessibilityNodeInfo) {
        val stop = findAction(root, listOf("Stop generating", "Stop", "توقف", "در حال تولید"))
        if (stop != null) generationIndicatorSeen = true

        // As soon as generation is over, prefer the real Download action.
        if (generationIndicatorSeen && stop == null) {
            val download = findAction(root, listOf(
                "Download", "دانلود", "Download image", "دانلود تصویر",
                "Save", "ذخیره", "Save image", "ذخیره تصویر"
            ))
            if (download != null && isLikelyEnabled(download)) {
                clickDownload(download)
                return
            }
        }

        // Some wrappers do not expose a readable generation indicator. After a
        // short minimum wait, still look for Download before using the hard timeout.
        val elapsed = System.currentTimeMillis() - sentAt
        if (!generationIndicatorSeen && elapsed >= 30_000L) {
            val download = findAction(root, listOf(
                "Download", "دانلود", "Download image", "دانلود تصویر",
                "Save", "ذخیره", "Save image", "ذخیره تصویر"
            ))
            if (download != null && isLikelyEnabled(download)) {
                clickDownload(download)
            }
        }
    }

    private fun checkGenerationTimeout() {
        if (state != State.WAITING_FOR_COMPLETION) return
        val root = rootInActiveWindow
        val download = root?.let {
            findAction(it, listOf(
                "Download", "دانلود", "Download image", "دانلود تصویر",
                "Save", "ذخیره", "Save image", "ذخیره تصویر"
            ))
        }
        if (download != null && isLikelyEnabled(download)) {
            clickDownload(download)
        } else {
            Toast.makeText(this, "عملیات ${index + 1}: دکمه دانلود پیدا نشد؛ ادامه می‌دهم.", Toast.LENGTH_SHORT).show()
            finishCurrent()
        }
    }

    private fun clickDownload(node: AccessibilityNodeInfo) {
        if (downloadClicked || state != State.WAITING_FOR_COMPLETION) return
        downloadClicked = true
        state = State.DOWNLOADING
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        handler.postDelayed({
            finishCurrent()
        }, DOWNLOAD_WAIT)
    }

    private fun finishCurrent() {
        if (state == State.IDLE) return
        state = State.IDLE
        BatchController.advance()
        index++

        if (index >= jobs.size) {
            Toast.makeText(this, "همه ${jobs.size} عملیات تمام شد.", Toast.LENGTH_LONG).show()
            stopQueue()
        } else {
            handler.postDelayed({ launchCurrent() }, NEXT_DELAY)
        }
    }

    private fun scheduleNext() {
        index++
        BatchController.advance()
        if (index >= jobs.size) stopQueue() else handler.postDelayed({ launchCurrent() }, NEXT_DELAY)
    }

    private fun stopQueue() {
        handler.removeCallbacksAndMessages(null)
        jobs = emptyList()
        index = 0
        state = State.IDLE
        sendClicked = false
        generationIndicatorSeen = false
        downloadClicked = false
        BatchController.clear()
    }

    private fun findEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isEditable && node.isVisibleToUser && node.isEnabled) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        return null
    }

    private fun findAction(root: AccessibilityNodeInfo, labels: List<String>): AccessibilityNodeInfo? {
        for (label in labels) {
            root.findAccessibilityNodeInfosByText(label).firstOrNull()?.let { return it }
        }

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            val viewId = node.viewIdResourceName.orEmpty()
            if (labels.any {
                    it.equals(text, true) || it.equals(desc, true) ||
                    text.contains(it, true) || desc.contains(it, true) ||
                    viewId.contains(it, true)
                }) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        return null
    }

    private fun isLikelyEnabled(node: AccessibilityNodeInfo): Boolean =
        node.isEnabled && node.isVisibleToUser
}
