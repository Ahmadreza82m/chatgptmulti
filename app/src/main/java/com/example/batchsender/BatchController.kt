package com.example.batchsender

import android.content.Context
import android.net.Uri

object BatchController {
    data class Job(val image: Uri, val prompt: String, val destinationIndex: Int)

    private var jobs: List<Job> = emptyList()
    private var cursor = 0

    fun start(context: Context, images: List<Uri>, prompt: String) {
        jobs = images.take(2).mapIndexed { i, uri -> Job(uri, prompt, i) }
        cursor = 0
        android.widget.Toast.makeText(
            context,
            "۲ عملیات مستقل در صف قرار گرفت.",
            android.widget.Toast.LENGTH_LONG
        ).show()
    }

    fun next(): Job? = jobs.getOrNull(cursor)

    fun advance() {
        if (cursor < jobs.size) cursor++
    }
}
