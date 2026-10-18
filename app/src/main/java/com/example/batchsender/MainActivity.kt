package com.example.batchsender

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() { override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); val repo = AppRepository(this, AppDatabase.get(this)); setContent { MultiSendApp(repo) } } }

@Composable private fun MultiSendApp(repo: AppRepository) {
    val vm: MainViewModel = viewModel(factory = object : ViewModelProvider.Factory { override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(repo) as T })
    val state by vm.state.collectAsState(); val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(100)) { vm.addImages(it) }
    MaterialTheme(colorScheme = darkColorScheme()) { Scaffold(topBar = { TopAppBar(title = { Text("MultiSend AI") }) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("Parallel image workstation", style = MaterialTheme.typography.titleLarge) }
            item { Text("هر تصویر یک Task مستقل است؛ Scheduler ظرفیت Connectionها را هم‌زمان پر می‌کند و خطای یک Task بقیه را متوقف نمی‌کند.") }
            item { Button(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, Modifier.fillMaxWidth()) { Text("انتخاب تصاویر (${state.images.size})") } }
            item { OutlinedTextField(state.prompt, vm::setPrompt, Modifier.fillMaxWidth(), minLines = 4, label = { Text("Prompt ثابت") }) }
            item { OutlinedTextField(state.name, vm::setName, Modifier.fillMaxWidth(), label = { Text("نام Batch") }) }
            item { Button(vm::startBatch, enabled = state.images.isNotEmpty() && state.prompt.isNotBlank() && !state.running, modifier = Modifier.fillMaxWidth()) { Text(if (state.running) "در حال پردازش…" else "Start Batch") } }
            item { ConnectionSection(repo) }
            item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text("Live status", style = MaterialTheme.typography.titleMedium); Spacer(Modifier.height(8.dp)); Text("Total ${state.total}  •  Processing ${state.processing}  •  Completed ${state.completed}  •  Failed ${state.failed}") } } }
            items(state.tasks, key = { it.id }) { task -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) { Text("Task ${task.id.takeLast(8)} • ${task.state}"); task.connectionId?.let { Text("Connection: $it") }; task.error?.let { Text(it, color = MaterialTheme.colorScheme.error) } } } }
        }
    } }
}

@Composable private fun ConnectionSection(repo: AppRepository) {
    var name by remember { mutableStateOf("") }; var key by remember { mutableStateOf("") }; val scope = rememberCoroutineScope(); val connections by repo.connections.collectAsState(initial = emptyList())
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Connections", style = MaterialTheme.typography.titleMedium); connections.forEach { c -> Text("${c.displayName} • ${c.status} • ${c.running}/${c.maxConcurrency}") }
        OutlinedTextField(name, { name = it }, label = { Text("نام Connection") }, modifier = Modifier.fillMaxWidth()); OutlinedTextField(key, { key = it }, label = { Text("OpenAI API key") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { if (name.isNotBlank() && key.isNotBlank()) scope.launch { repo.addConnection(name, key); name = ""; key = "" } }, modifier = Modifier.fillMaxWidth()) { Text("Add Connection") }
        Text("کلید با Android Keystore رمزنگاری می‌شود و در Room ذخیره نمی‌شود.", style = MaterialTheme.typography.bodySmall)
    } }
}
