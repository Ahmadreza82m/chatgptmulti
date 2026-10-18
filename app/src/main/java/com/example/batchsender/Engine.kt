package com.example.batchsender

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.*
import java.io.IOException
import java.util.concurrent.TimeUnit

interface ImageProcessingProvider { suspend fun validateConnection(): ProviderCapabilities; suspend fun submitImage(image: Uri, prompt: String): ByteArray; suspend fun cancelTask(remoteTaskId: String) }
data class ProviderCapabilities(val maxConcurrency: Int, val supportsCancellation: Boolean = false)

class OpenAiImageProvider(private val context: Context, private val apiKey: String, private val model: String = "gpt-image-2") : ImageProcessingProvider {
    private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(5, TimeUnit.MINUTES).writeTimeout(5, TimeUnit.MINUTES).build()
    override suspend fun validateConnection(): ProviderCapabilities = withContext(Dispatchers.IO) { val req = Request.Builder().url("https://api.openai.com/v1/models/$model").header("Authorization", "Bearer $apiKey").get().build(); client.newCall(req).execute().use { r -> if (!r.isSuccessful) throw ProviderException("Provider/authentication error: HTTP ${r.code}"); ProviderCapabilities(1) } }
    override suspend fun submitImage(image: Uri, prompt: String): ByteArray = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(image)?.use { it.readBytes() } ?: throw ProviderException("Cannot read image")
        if (bytes.size > 25 * 1024 * 1024) throw ProviderException("Image is larger than 25 MB")
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("model", model).addFormDataPart("prompt", prompt).addFormDataPart("size", "auto").addFormDataPart("image[]", "input.png", bytes.toRequestBody("image/png".toMediaType())).build()
        val req = Request.Builder().url("https://api.openai.com/v1/images/edits").header("Authorization", "Bearer $apiKey").post(body).build()
        client.newCall(req).execute().use { r -> val text = r.body?.string().orEmpty(); if (!r.isSuccessful) throw ProviderException("Provider HTTP ${r.code}: ${text.take(500)}"); val b64 = Regex("\\\"b64_json\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(text)?.groupValues?.get(1) ?: throw ProviderException("Provider returned no image result"); try { java.util.Base64.getDecoder().decode(b64) } catch (_: Exception) { throw ProviderException("Invalid image response") } }
    }
    override suspend fun cancelTask(remoteTaskId: String) { }
}
class ProviderException(message: String) : IOException(message)

class BatchWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = coroutineScope {
        val db = AppDatabase.get(applicationContext); val batchId = inputData.getString("batchId") ?: return@coroutineScope Result.failure(); val connections = db.connections().enabled(); if (connections.isEmpty()) return@coroutineScope Result.failure()
        val started = System.currentTimeMillis(); db.batches().updateState(batchId, "RUNNING", started, null); val semaphores = connections.associate { it.id to Semaphore(it.maxConcurrency.coerceIn(1, 8)) }; val active = mutableListOf<kotlinx.coroutines.Job>()
        while (true) {
            currentCoroutineContext().ensureActive(); val pending = db.tasks().pending(batchId); active.removeAll { it.isCompleted }; if (pending.isEmpty() && active.isEmpty()) break
            var launchedAny = false
            for (task in pending) {
                val c = connections.firstOrNull { it.enabled && semaphores.getValue(it.id).availablePermits > 0 } ?: continue
                active += launch { processTask(db, task, c, semaphores.getValue(c.id)) }; launchedAny = true
            }
            active.removeAll { it.isCompleted }
            if (active.isNotEmpty() && (!launchedAny || pending.isEmpty())) active.first().join() else if (!launchedAny) delay(300)
        }
        db.batches().updateState(batchId, "COMPLETED", started, System.currentTimeMillis()); Result.success()
    }
    private suspend fun processTask(db: AppDatabase, task: TaskEntity, c: ConnectionEntity, semaphore: Semaphore) = semaphore.withPermit {
        val dao = db.tasks(); val now = System.currentTimeMillis(); val attempt = task.retryCount; dao.update(task.id, "UPLOADING", c.id, 10, attempt, null, null, now, null); val key = SecretStore(applicationContext).get(c.id) ?: run { dao.update(task.id, "FAILED", c.id, 0, attempt, "Missing credential", null, now, System.currentTimeMillis()); return@withPermit }
        try { dao.update(task.id, "PROCESSING", c.id, 30, attempt, null, null, now, null); val bytes = OpenAiImageProvider(applicationContext, key).submitImage(Uri.parse(task.imageUri), task.prompt); val dir = java.io.File(applicationContext.filesDir, "results").apply { mkdirs() }; val out = java.io.File(dir, "${task.id}.png"); out.writeBytes(bytes); dao.update(task.id, "COMPLETED", c.id, 100, attempt, null, out.toURI().toString(), now, System.currentTimeMillis()) }
        catch (t: Throwable) { if (t is CancellationException) { dao.update(task.id, "CANCELLED", c.id, 0, attempt, "Cancelled", null, now, System.currentTimeMillis()); throw t }; val next = attempt + 1; if (next <= 2) { dao.update(task.id, "RETRYING", c.id, 0, next, t.message ?: "Temporary error", null, now, null); delay((1000L shl attempt).coerceAtMost(8000)); dao.retry(task.id) } else dao.update(task.id, "FAILED", c.id, 0, attempt, t.message ?: "Provider error", null, now, System.currentTimeMillis()) }
    }
}
