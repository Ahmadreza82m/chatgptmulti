package com.example.batchsender

import android.content.Context
import android.net.Uri

object BatchController {
    data class Destination(val packageName: String, val label: String)
    data class Job(val image: Uri, val prompt: String, val destination: Destination, val index: Int)

    private var jobs: List<Job> = emptyList()
    private var cursor = 0

    @Synchronized
    fun start(context: Context, images: List<Uri>, prompt: String, destinations: List<Destination>): Boolean {
        if (images.isEmpty() || prompt.isBlank() || destinations.size < images.size) return false
        jobs = images.mapIndexed { index, uri ->
            Job(uri, prompt, destinations[index], index)
        }
        cursor = 0
        return BatchAccessibilityService.startQueue(context.applicationContext, jobs)
    }

    @Synchronized
    fun next(): Job? = jobs.getOrNull(cursor)

    @Synchronized
    fun advance() {
        if (cursor < jobs.size) cursor++
    }

    @Synchronized
    fun clear() {
        jobs = emptyList()
        cursor = 0
    }

    @Synchronized
    fun size(): Int = jobs.size
}
