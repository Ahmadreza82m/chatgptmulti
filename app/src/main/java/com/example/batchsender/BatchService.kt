package com.example.batchsender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import kotlin.math.abs

class BatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onCreate() { super.onCreate(); createChannel(); startForeground(77, notification("Parallel processing")) }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int { runBatch(); return START_STICKY }

    private fun runBatch() {
        if (scope.coroutineContext[Job]?.children?.any { it.isActive } == true) return
        scope.launch {
            val store = AppStore(this@BatchService)
            val connections = store.loadConnections().filter { it.enabled && it.apiKey.isNotBlank() }
            if (connections.isEmpty()) { store.setRunning(false); stopSelf(); return@launch }
            val pending = store.loadTasks().filter { it.status == "PENDING" || it.status == "FAILED" }
            if (pending.isEmpty()) { store.setRunning(false); stopSelf(); return@launch }
            val semaphore = Semaphore(minOf(8, connections.size * 2))
            var finished = 0
            coroutineScope {
                pending.map { task -> async {
                    while (store.isPaused() && store.isRunning()) delay(500)
                    if (!store.isRunning()) return@async
                    semaphore.withPermit {
                        val c = connections.firstOrNull { it.id == task.connectionId } ?: connections[abs(task.id.hashCode()) % connections.size]
                        process(store, task, c)
                        finished++
                        updateNotification("$finished/${pending.size} tasks")
                    }
                } }.awaitAll()
            }
            store.setRunning(false); store.setPaused(false); updateNotification("Batch finished"); delay(1200); stopSelf()
        }
    }

    private suspend fun process(store: AppStore, task: AppStore.Task, connection: AppStore.Connection) {
        store.updateTask(task.id) { it.copy(status = "UPLOADING", error = "", attempts = it.attempts + 1) }
        var last: Throwable? = null
        repeat(3) { attempt ->
            try {
                store.updateTask(task.id) { it.copy(status = "PROCESSING") }
                val result = OpenAIImageClient.edit(this, connection.apiKey, connection.model, Uri.parse(task.inputUri), task.prompt)
                val dir = File(filesDir, "results").apply { mkdirs() }
                val file = File(dir, "${task.id}.png"); file.writeBytes(result.bytes)
                store.updateTask(task.id) { it.copy(status = "COMPLETED", outputUri = Uri.fromFile(file).toString(), error = "") }
                return
            } catch (e: OpenAIImageClient.ApiException) {
                last = e
                if (e.code != 429 && e.code !in 500..599) return@repeat
                delay((1000L shl attempt).coerceAtMost(8000L))
            } catch (e: Throwable) { last = e; return@repeat }
        }
        store.updateTask(task.id) { it.copy(status = "FAILED", error = last?.message ?: "Unknown error") }
    }

    private fun createChannel() { getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("batch", "Batch Processing", NotificationManager.IMPORTANCE_LOW)) }
    private fun notification(text: String): Notification = NotificationCompat.Builder(this, "batch").setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("Batch Image Studio").setContentText(text).setOngoing(true).build()
    private fun updateNotification(text: String) = getSystemService(NotificationManager::class.java).notify(77, notification(text))
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object { fun start(context: Context) { ContextCompat.startForegroundService(context, Intent(context, BatchService::class.java)) } }
}
