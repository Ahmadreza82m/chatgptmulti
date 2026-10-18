package com.example.batchsender

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class AppStore(private val context: Context) {
    data class Connection(val id: String, val name: String, val apiKey: String, val model: String = "gpt-image-2", val enabled: Boolean = true)
    data class Task(val id: String, val batchId: String, val name: String, val inputUri: String, val prompt: String, val connectionId: String, val status: String = "PENDING", val outputUri: String? = null, val error: String = "", val attempts: Int = 0)
    private val prefs = context.getSharedPreferences("batch_studio", Context.MODE_PRIVATE)
    private val lock = Any()

    fun loadConnections(): List<Connection> = synchronized(lock) {
        val a = runCatching { JSONArray(prefs.getString("connections", "[]")) }.getOrDefault(JSONArray())
        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { o -> Connection(o.optString("id"), o.optString("name"), o.optString("key"), o.optString("model", "gpt-image-2"), o.optBoolean("enabled", true)) } }
    }
    fun addConnection(name: String, key: String) { saveConnections(loadConnections() + Connection(UUID.randomUUID().toString(), name, key.trim())) }
    fun setConnectionEnabled(id: String, enabled: Boolean) { saveConnections(loadConnections().map { if (it.id == id) it.copy(enabled = enabled) else it }) }
    private fun saveConnections(list: List<Connection>) { val a = JSONArray(); list.forEach { c -> a.put(JSONObject().apply { put("id", c.id); put("name", c.name); put("key", c.apiKey); put("model", c.model); put("enabled", c.enabled) }) }; prefs.edit().putString("connections", a.toString()).apply() }

    fun loadTasks(): List<Task> = synchronized(lock) {
        val a = runCatching { JSONArray(prefs.getString("tasks", "[]")) }.getOrDefault(JSONArray())
        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { o -> Task(o.optString("id"), o.optString("batchId"), o.optString("name"), o.optString("inputUri"), o.optString("prompt"), o.optString("connectionId"), o.optString("status", "PENDING"), o.optString("outputUri").ifBlank { null }, o.optString("error"), o.optInt("attempts")) } }
    }
    private fun saveTasks(list: List<Task>) { val a = JSONArray(); list.forEach { t -> a.put(JSONObject().apply { put("id", t.id); put("batchId", t.batchId); put("name", t.name); put("inputUri", t.inputUri); put("prompt", t.prompt); put("connectionId", t.connectionId); put("status", t.status); put("outputUri", t.outputUri ?: ""); put("error", t.error); put("attempts", t.attempts) }) }; prefs.edit().putString("tasks", a.toString()).apply() }
    fun updateTask(id: String, update: (Task) -> Task) = synchronized(lock) { saveTasks(loadTasks().map { if (it.id == id) update(it) else it }) }

    fun loadPrompt() = prefs.getString("last_prompt", "") ?: ""
    fun saveSelected(uris: List<Uri>) = prefs.edit().putString("selected", JSONArray(uris.map(Uri::toString)).toString()).apply()
    fun loadSelected(): List<Uri> = runCatching { JSONArray(prefs.getString("selected", "[]")).let { a -> (0 until a.length()).map { Uri.parse(a.getString(it)) } } }.getOrDefault(emptyList())

    fun createBatch(context: Context, name: String, images: List<Uri>, prompt: String, connections: List<Connection>) {
        val batchId = UUID.randomUUID().toString()
        val tasks = images.mapIndexed { index, uri -> Task(UUID.randomUUID().toString(), batchId, "Image ${index + 1}", uri.toString(), prompt, connections[index % connections.size].id) }
        synchronized(lock) { saveTasks(loadTasks() + tasks); prefs.edit().putString("last_prompt", prompt).putBoolean("running", true).putBoolean("paused", false).apply() }
        BatchService.start(context)
    }
    fun isRunning() = prefs.getBoolean("running", false)
    fun setRunning(value: Boolean) = prefs.edit().putBoolean("running", value).apply()
    fun isPaused() = prefs.getBoolean("paused", false)
    fun setPaused(value: Boolean) = prefs.edit().putBoolean("paused", value).apply()
}
