package com.example.batchsender

import android.content.Context
import android.net.Uri

object BatchController {
    enum class JobStatus { QUEUED, STARTING, GENERATING, DOWNLOADING, COMPLETED, FAILED, SKIPPED }

    data class Destination(val packageName: String, val label: String)
    data class Job(
        val image: Uri,
        val prompt: String,
        val destination: Destination,
        val index: Int
    )

    private data class RuntimeJob(val job: Job, var status: JobStatus = JobStatus.QUEUED)

    private var jobs: List<RuntimeJob> = emptyList()

    @Synchronized
    fun start(
        context: Context,
        images: List<Uri>,
        prompt: String,
        destinations: List<Destination>
    ): Boolean {
        if (images.isEmpty() || prompt.isBlank() || destinations.size < images.size) return false
        jobs = images.mapIndexed { index, uri ->
            RuntimeJob(Job(uri, prompt, destinations[index], index))
        }
        return BatchAccessibilityService.startQueue(
            context.applicationContext,
            jobs.map { it.job }
        )
    }

    @Synchronized
    fun setStatus(index: Int, status: JobStatus) {
        jobs.getOrNull(index)?.status = status
    }

    @Synchronized
    fun status(index: Int): JobStatus? = jobs.getOrNull(index)?.status

    @Synchronized
    fun statuses(): List<JobStatus> = jobs.map { it.status }

    @Synchronized
    fun next(): Job? = jobs.firstOrNull { it.status == JobStatus.QUEUED }?.job

    @Synchronized
    fun advance() {
        val job = jobs.firstOrNull { it.status == JobStatus.GENERATING }
        if (job != null) job.status = JobStatus.COMPLETED
    }

    @Synchronized
    fun clear() {
        jobs = emptyList()
    }

    @Synchronized
    fun size(): Int = jobs.size
}
