package com.example.batchsender

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(private val repo: AppRepository) : ViewModel() {
    private val db = AppDatabase.get(App.instance)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    init { viewModelScope.launch { observeLatest() } }
    private suspend fun observeLatest() {
        db.batches().observe().collectLatest { batches ->
            val batch = batches.firstOrNull() ?: return@collectLatest
            db.tasks().observe(batch.id).collect { tasks -> _state.update { it.copy(tasks = tasks, total = tasks.size, processing = tasks.count { t -> t.state in setOf("UPLOADING", "PROCESSING", "DOWNLOADING", "RETRYING") }, completed = tasks.count { t -> t.state == "COMPLETED" }, failed = tasks.count { t -> t.state == "FAILED" }, running = batch.state == "RUNNING") } }
        }
    }
    fun addImages(uris: List<Uri>) { _state.update { it.copy(images = (it.images + uris).distinct()) } }
    fun setPrompt(v: String) { _state.update { it.copy(prompt = v) } }
    fun setName(v: String) { _state.update { it.copy(name = v) } }
    fun startBatch() { val s = _state.value; viewModelScope.launch { val id = repo.createBatch(s.name, s.prompt, s.images); val request = OneTimeWorkRequestBuilder<BatchWorker>().setInputData(workDataOf("batchId" to id)).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build(); WorkManager.getInstance(App.instance).enqueue(request) } }
}

data class UiState(val images: List<Uri> = emptyList(), val prompt: String = "", val name: String = "", val tasks: List<TaskEntity> = emptyList(), val total: Int = 0, val processing: Int = 0, val completed: Int = 0, val failed: Int = 0, val running: Boolean = false)
class App : android.app.Application() { companion object { lateinit var instance: App }; override fun onCreate() { super.onCreate(); instance = this } }
