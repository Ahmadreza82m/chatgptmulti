package com.example.batchsender

import android.accessibilityservice.AccessibilityService
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * Concurrent batch dispatcher.
 *
 * Android AccessibilityService can only actively inspect/control the current
 * foreground window. Therefore this engine does not pretend that ten foreground
 * apps can be clicked literally at the same instant. Instead it starts each
 * destination quickly, lets generation continue independently in each app,
 * and then rotates through the running destinations to detect completion and
 * download results. One job failing never blocks the others.
 */
class BatchAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile private var instance: BatchAccessibilityService? = null

        private const val START_GAP = 450L
        private const val SHARE_DELAY = 1200L
        private const val POLL_INTERVAL = 1800L
        private const val GENERATION_TIMEOUT = 120_000L
        private const val DOWNLOAD_WAIT = 1800L

        fun startQueue(context: android.content.Context, jobs: List<BatchController.Job>): Boolean {
            val service = instance ?: return false
            service.begin(jobs)
            Toast.makeText(context, "${jobs.size} عملیات به‌صورت همزمان شروع می‌شوند.", Toast.LENGTH_SHORT).show()
            return true
        }
    }

    private enum class State { IDLE, STARTING, SHARING, GENERATING, DOWNLOADING }

    private data class RuntimeJob(
        val job: BatchController.Job,
        var state: State = State.IDLE,
        var sentAt: Long = 0L,
        var generationIndicatorSeen: Boolean = false,
        var sendClicked: Boolean = false,
        var downloadClicked: Boolean = false,
        var finished: Boolean = false
    )

    private val handler = Handler(Looper.getMainLooper())
    private var runtimeJobs: List<RuntimeJob> = emptyList()
    private var focusedIndex = -1
    private var stopped = false

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
        val index = focusedIndex
        val runtime = runtimeJobs.getOrNull(index) ?: return
        if (runtime.finished) return

        val packageName = event?.packageName?.toString()
        if (packageName != null && packageName != runtime.job.destination.packageName) return

        val root = rootInActiveWindow ?: return
        when (runtime.state) {
            State.SHARING -> handleSharing(root, runtime)
            State.GENERATING -> handleGeneration(root, runtime)
            else -> Unit
        }
    }

    private fun begin(newJobs: List<BatchController.Job>) {
        handler.removeCallbacksAndMessages(null)
        stopped = false
        runtimeJobs = newJobs.map { RuntimeJob(it) }
        focusedIndex = -1

        runtimeJobs.forEachIndexed { index, _ ->
            handler.postDelayed({ startJob(index) }, START_GAP * index)
        }

        val totalStart = START_GAP * runtimeJobs.size + 1500L
        handler.postDelayed({ pollRunningJobs() }, totalStart)
    }

    /** Start one destination, send its prompt/image, then immediately move on. */
    private fun startJob(index: Int) {
        if (stopped) return
        val runtime = runtimeJobs.getOrNull(index) ?: return
        if (runtime.finished) return

        focusedIndex = index
        runtime.state = State.STARTING
        BatchController.setStatus(index, BatchController.JobStatus.STARTING)

        try {
            val launch = packageManager.getLaunchIntentForPackage(runtime.job.destination.packageName)
                ?: throw ActivityNotFoundException("No launcher activity")
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(launch)
        } catch (_: Exception) {
            failJob(index, "مقصد قابل اجرا نیست")
            return
        }

        handler.postDelayed({
            if (!stopped && !runtime.finished) shareJob(index)
        }, SHARE_DELAY)
    }

    private fun shareJob(index: Int) {
        val runtime = runtimeJobs.getOrNull(index) ?: return
        if (runtime.finished) return

        focusedIndex = index
        runtime.state = State.SHARING
        BatchController.setStatus(index, BatchController.JobStatus.STARTING)

        val share = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_STREAM, runtime.job.image)
            putExtra(Intent.EXTRA_TEXT, runtime.job.prompt)
            putExtra(Intent.EXTRA_TITLE, "MultiSend AI")
            clipData = ClipData.newRawUri("image", runtime.job.image)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setPackage(runtime.job.destination.packageName)
        }

        try {
            startActivity(share)
        } catch (_: Exception) {
            // Some wrappers ignore ACTION_SEND. Accessibility fallback below.
        }

        handler.postDelayed({
            if (!stopped && !runtime.finished) {
                handleSharing(rootInActiveWindow, runtime)
                // Do not wait for this destination before starting the next one.
                if (runtime.state == State.SHARING && !runtime.sendClicked) {
                    failJob(index, "دکمه ارسال پیدا نشد")
                }
            }
        }, SHARE_DELAY)
    }

    private fun handleSharing(root: AccessibilityNodeInfo?, runtime: RuntimeJob) {
        if (root == null || runtime.sendClicked) return

        val editor = findEditable(root)
        if (editor != null && editor.isEnabled && editor.isVisibleToUser) {
            val current = editor.text?.toString().orEmpty()
            if (current != runtime.job.prompt) {
                val args = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        runtime.job.prompt
                    )
                }
                editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
        }

        val send = findAction(root, listOf("Send", "ارسال", "Submit", "فرستادن"))
        if (send != null && send.isEnabled && send.isVisibleToUser) {
            runtime.sendClicked = true
            send.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            runtime.state = State.GENERATING
            runtime.sentAt = System.currentTimeMillis()
            BatchController.setStatus(runtime.job.index, BatchController.JobStatus.GENERATING)
        }
    }

    private fun handleGeneration(root: AccessibilityNodeInfo, runtime: RuntimeJob) {
        val stop = findAction(root, listOf("Stop generating", "Stop", "توقف", "در حال تولید"))
        if (stop != null) runtime.generationIndicatorSeen = true

        if (runtime.generationIndicatorSeen && stop == null) {
            findAndDownload(root, runtime)
            return
        }

        if (!runtime.generationIndicatorSeen && System.currentTimeMillis() - runtime.sentAt >= 15_000L) {
            findAndDownload(root, runtime)
        }
    }

    private fun findAndDownload(root: AccessibilityNodeInfo, runtime: RuntimeJob) {
        val download = findAction(
            root,
            listOf("Download", "دانلود", "Download image", "دانلود تصویر", "Save", "ذخیره", "Save image", "ذخیره تصویر")
        )
        if (download != null && download.isEnabled && download.isVisibleToUser) {
            clickDownload(runtime, download)
        }
    }

    private fun clickDownload(runtime: RuntimeJob, node: AccessibilityNodeInfo) {
        if (runtime.downloadClicked || runtime.finished) return
        runtime.downloadClicked = true
        runtime.state = State.DOWNLOADING
        BatchController.setStatus(runtime.job.index, BatchController.JobStatus.DOWNLOADING)
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        handler.postDelayed({ finishJob(runtime.job.index, true) }, DOWNLOAD_WAIT)
    }

    /**
     * Rotate through jobs which are already generating. This is the key change:
     * generation is no longer held behind a single global index.
     */
    private fun pollRunningJobs() {
        if (stopped) return

        val unfinished = runtimeJobs.filter { !it.finished }
        if (unfinished.isEmpty()) {
            completeBatch()
            return
        }

        val candidates = unfinished.filter { it.state == State.GENERATING }
        if (candidates.isEmpty()) {
            if (runtimeJobs.all { it.finished }) completeBatch()
            else handler.postDelayed({ pollRunningJobs() }, POLL_INTERVAL)
            return
        }

        checkNextRunning(candidates, 0)
    }

    private fun checkNextRunning(candidates: List<RuntimeJob>, position: Int) {
        if (stopped) return
        if (position >= candidates.size) {
            handler.postDelayed({ pollRunningJobs() }, POLL_INTERVAL)
            return
        }

        val runtime = candidates[position]
        if (System.currentTimeMillis() - runtime.sentAt > GENERATION_TIMEOUT) {
            failJob(runtime.job.index, "زمان تولید تمام شد")
            checkNextRunning(candidates, position + 1)
            return
        }

        focusedIndex = runtime.job.index
        try {
            val launch = packageManager.getLaunchIntentForPackage(runtime.job.destination.packageName)
            if (launch == null) {
                failJob(runtime.job.index, "مقصد دیگر در دسترس نیست")
                checkNextRunning(candidates, position + 1)
                return
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launch)
        } catch (_: Exception) {
            failJob(runtime.job.index, "بازگشت به مقصد ناموفق بود")
            checkNextRunning(candidates, position + 1)
            return
        }

        handler.postDelayed({
            val root = rootInActiveWindow
            if (root != null && !runtime.finished) handleGeneration(root, runtime)
            checkNextRunning(candidates, position + 1)
        }, 700L)
    }

    private fun finishJob(index: Int, success: Boolean) {
        val runtime = runtimeJobs.getOrNull(index) ?: return
        if (runtime.finished) return
        runtime.finished = true
        runtime.state = if (success) State.IDLE else State.IDLE
        BatchController.setStatus(
            index,
            if (success) BatchController.JobStatus.COMPLETED else BatchController.JobStatus.FAILED
        )
        focusedIndex = -1

        if (runtimeJobs.all { it.finished }) completeBatch()
        else handler.postDelayed({ pollRunningJobs() }, 250L)
    }

    private fun failJob(index: Int, reason: String) {
        val runtime = runtimeJobs.getOrNull(index) ?: return
        if (runtime.finished) return
        runtime.finished = true
        runtime.state = State.IDLE
        BatchController.setStatus(index, BatchController.JobStatus.FAILED)
        Toast.makeText(this, "عملیات ${index + 1}: $reason", Toast.LENGTH_SHORT).show()
        if (runtimeJobs.all { it.finished }) completeBatch()
    }

    private fun completeBatch() {
        if (stopped) return
        stopped = true
        handler.removeCallbacksAndMessages(null)
        val completed = runtimeJobs.count { BatchController.status(it.job.index) == BatchController.JobStatus.COMPLETED }
        val failed = runtimeJobs.count { BatchController.status(it.job.index) == BatchController.JobStatus.FAILED }
        Toast.makeText(this, "تمام شد: $completed موفق، $failed ناموفق.", Toast.LENGTH_LONG).show()
    }

    private fun stopQueue() {
        handler.removeCallbacksAndMessages(null)
        stopped = true
        runtimeJobs = emptyList()
        focusedIndex = -1
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
}
