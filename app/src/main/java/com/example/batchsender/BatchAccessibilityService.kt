package com.example.batchsender

import android.accessibilityservice.AccessibilityService
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

class BatchAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile private var instance: BatchAccessibilityService? = null
        private const val LAUNCH_DELAY = 1400L
        private const val SHARE_DELAY = 1800L
        private const val SEND_TIMEOUT = 120_000L
        private const val NEXT_DELAY = 2500L

        fun startQueue(context: android.content.Context, jobs: List<BatchController.Job>): Boolean {
            val service = instance ?: return false
            service.begin(jobs)
            Toast.makeText(context, "صف ${jobs.size} عملیات شروع شد.", Toast.LENGTH_SHORT).show()
            return true
        }
    }

    private enum class State { IDLE, LAUNCHING, SHARING, WAITING_FOR_COMPLETION }

    private val handler = Handler(Looper.getMainLooper())
    private var jobs: List<BatchController.Job> = emptyList()
    private var index = 0
    private var state = State.IDLE
    private var sentAt = 0L
    private var sendClicked = false
    private var generationIndicatorSeen = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {
        stopQueue()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val job = jobs.getOrNull(index) ?: return
        if (state != State.SHARING && state != State.WAITING_FOR_COMPLETION) return
        if (event?.packageName?.toString() != job.destination.packageName) return

        val root = rootInActiveWindow ?: return
        when (state) {
            State.SHARING -> {
                val stop = findAction(root, listOf("Stop generating", "Stop", "توقف"))
                if (stop != null) generationIndicatorSeen = true

                if (!sendClicked) {
                    val send = findAction(root, listOf("Send", "ارسال", "Submit"))
                    if (send != null && isLikelyEnabled(send)) {
                        handler.postDelayed({ click(send) }, 350)
                    }
                }
            }
            State.WAITING_FOR_COMPLETION -> {
                val stop = findAction(root, listOf("Stop generating", "Stop", "توقف"))
                if (stop != null) generationIndicatorSeen = true

                val elapsed = System.currentTimeMillis() - sentAt
                if (generationIndicatorSeen && stop == null && elapsed > 2500L) {
                    finishCurrent()
                } else if (!generationIndicatorSeen && elapsed > SEND_TIMEOUT) {
                    finishCurrent()
                }
            }
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
        val job = jobs.getOrNull(index) ?: run {
            stopQueue()
            return
        }

        state = State.LAUNCHING
        sendClicked = false
        generationIndicatorSeen = false

        try {
            val launch = packageManager.getLaunchIntentForPackage(job.destination.packageName)
                ?: throw ActivityNotFoundException("No launcher activity")
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(launch)
        } catch (_: Exception) {
            Toast.makeText(this, "مقصد ${index + 1} قابل اجرا نیست.", Toast.LENGTH_LONG).show()
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
            putExtra(Intent.EXTRA_TITLE, "Batch Sender")
            clipData = ClipData.newRawUri("image", job.image)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setPackage(job.destination.packageName)
        }

        try {
            startActivity(share)
        } catch (_: Exception) {
            // Some web apps do not expose ACTION_SEND. Keep the destination open so
            // the accessibility layer can still interact with its UI.
            handler.postDelayed({ tryAccessibilityFallback(job) }, SHARE_DELAY)
            return
        }

        handler.postDelayed({ tryAccessibilityFallback(job) }, SHARE_DELAY)
    }

    private fun tryAccessibilityFallback(job: BatchController.Job) {
        if (jobs.getOrNull(index) != job || state != State.SHARING) return
        val root = rootInActiveWindow ?: return
        val send = findAction(root, listOf("Send", "ارسال", "Submit"))
        if (send != null && isLikelyEnabled(send) && !sendClicked) {
            click(send)
        }
    }

    private fun click(node: AccessibilityNodeInfo) {
        if (sendClicked) return
        sendClicked = true
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        state = State.WAITING_FOR_COMPLETION
        sentAt = System.currentTimeMillis()
        handler.postDelayed({
            if (state == State.WAITING_FOR_COMPLETION && System.currentTimeMillis() - sentAt >= SEND_TIMEOUT) {
                finishCurrent()
            }
        }, SEND_TIMEOUT + 3000L)
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
        BatchController.clear()
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
            if (labels.any { it.equals(text, true) || it.equals(desc, true) || text.contains(it, true) || desc.contains(it, true) }) {
                return node
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        return null
    }

    private fun isLikelyEnabled(node: AccessibilityNodeInfo): Boolean = node.isEnabled && node.isVisibleToUser
}
