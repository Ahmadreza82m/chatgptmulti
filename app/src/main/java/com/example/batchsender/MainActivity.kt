package com.example.batchsender

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class Screen { HOME, NEW, RESULTS, CONNECTIONS }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { BatchStudio() } }
}

@Composable private fun BatchStudio() {
    val context = LocalContext.current
    val store = remember { AppStore(context) }
    var screen by remember { mutableStateOf(Screen.HOME) }
    var selected by remember { mutableStateOf(store.loadSelected()) }
    var prompt by remember { mutableStateOf(store.loadPrompt()) }
    var tasks by remember { mutableStateOf(store.loadTasks()) }
    var connections by remember { mutableStateOf(store.loadConnections()) }
    var running by remember { mutableStateOf(store.isRunning()) }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) { uris.forEach { runCatching { context.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } }; selected = (selected + uris).distinct(); store.saveSelected(selected) }
    }
    LaunchedEffect(Unit) { while (true) { tasks = store.loadTasks(); connections = store.loadConnections(); running = store.isRunning(); delay(800) } }
    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF8B7CFF), secondary = Color(0xFF35D4C8))) {
        Scaffold(bottomBar = { NavigationBar { Nav(Screen.HOME, Icons.Default.Home, "Home", screen) { screen = it }; Nav(Screen.NEW, Icons.Default.AddPhotoAlternate, "New", screen) { screen = it }; Nav(Screen.RESULTS, Icons.Default.GridView, "Results", screen) { screen = it }; Nav(Screen.CONNECTIONS, Icons.Default.Key, "Connections", screen) { screen = it } } }) { pad ->
            Box(Modifier.fillMaxSize().padding(pad)) {
                when (screen) {
                    Screen.HOME -> Home(tasks, connections, running, store) { screen = Screen.NEW }
                    Screen.NEW -> NewBatch(selected, prompt, store, picker, scope) { selected = emptyList(); prompt = ""; store.saveSelected(emptyList()); screen = Screen.HOME }
                    Screen.RESULTS -> Results(tasks)
                    Screen.CONNECTIONS -> Connections(connections, store) { connections = store.loadConnections() }
                }
            }
        }
    }
}

@Composable private fun Nav(screen: Screen, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, current: Screen, go: (Screen) -> Unit) { NavigationBarItem(current == screen, { go(screen) }, icon = { Icon(icon, null) }, label = { Text(label) }) }

@Composable private fun Home(tasks: List<AppStore.Task>, connections: List<AppStore.Connection>, running: Boolean, store: AppStore, newBatch: () -> Unit) {
    val context = LocalContext.current
    val processing = tasks.count { it.status == "PROCESSING" || it.status == "UPLOADING" }; val completed = tasks.count { it.status == "COMPLETED" }; val failed = tasks.count { it.status == "FAILED" }
    LazyColumn(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Batch Image Studio", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text("Professional parallel AI image processing", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Button(newBatch, Modifier.fillMaxWidth().height(54.dp)) { Icon(Icons.Default.AddPhotoAlternate, null); Spacer(Modifier.width(8.dp)); Text("New Batch") } }
        item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Text(if (running) "Running" else "Ready", style = MaterialTheme.typography.titleLarge); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Stat("Processing", processing); Stat("Completed", completed); Stat("Failed", failed) }; Text("${connections.count { it.enabled }} active connections / ${connections.size} configured"); if (running) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton({ store.setPaused(!store.isPaused()) }) { Text(if (store.isPaused()) "Resume" else "Pause") }; OutlinedButton({ store.setRunning(false) }) { Text("Cancel pending") } }; if (failed > 0 && !running) OutlinedButton({ store.retryFailed(); BatchService.start(context) }) { Text("Retry failed ($failed)") } } } }
        item { Text("Tasks", style = MaterialTheme.typography.titleLarge) }
        items(tasks.take(50), key = { it.id }) { TaskCard(it) }
    }
}

@Composable private fun Stat(label: String, value: Int) { Column { Text(value.toString(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
@Composable private fun TaskCard(t: AppStore.Task) { Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) { AsyncImage(t.inputUri, null, Modifier.size(56.dp), contentScale = ContentScale.Crop); Spacer(Modifier.width(12.dp)); Column { Text(t.name, fontWeight = FontWeight.SemiBold); Text(t.status, color = when(t.status) { "COMPLETED" -> Color(0xFF48D597); "FAILED" -> Color(0xFFFF6B6B); else -> Color(0xFF8B7CFF) }); if (t.error.isNotBlank()) Text(t.error, color = MaterialTheme.colorScheme.error, maxLines = 1) } } } }

@Composable private fun NewBatch(selected: List<Uri>, initialPrompt: String, store: AppStore, picker: ActivityResultLauncher<Array<String>>, scope: kotlinx.coroutines.CoroutineScope, done: () -> Unit) {
    var prompt by remember(initialPrompt) { mutableStateOf(initialPrompt) }; var name by remember { mutableStateOf("Batch") }; var busy by remember { mutableStateOf(false) }; val active = store.loadConnections().filter { it.enabled }; val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("New Batch", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        item { OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Batch name") }, singleLine = true) }
        item { Card(Modifier.fillMaxWidth().clickable { picker.launch(arrayOf("image/*")) }) { Column(Modifier.padding(16.dp)) { Text("Images", style = MaterialTheme.typography.titleLarge); Text("${selected.size} selected — tap to add more") } } }
        if (selected.isNotEmpty()) item { LazyVerticalGrid(GridCells.Fixed(4), Modifier.height(170.dp), userScrollEnabled = false) { items(selected) { AsyncImage(it, null, Modifier.padding(2.dp).fillMaxWidth().aspectRatio(1f), contentScale = ContentScale.Crop) } } }
        item { OutlinedTextField(prompt, { prompt = it }, Modifier.fillMaxWidth().height(160.dp), label = { Text("Fixed Prompt") }) }
        item { Text("${active.size} active connections") }
        items(active) { c -> Text("• ${c.name} — ${c.model}") }
        item { Button(enabled = selected.isNotEmpty() && prompt.isNotBlank() && active.isNotEmpty() && !busy, onClick = { busy = true; scope.launch { store.createBatch(context, name, selected, prompt, active); busy = false; done() } }, Modifier.fillMaxWidth().height(54.dp)) { Text(if (busy) "Starting…" else "Start Parallel Processing") } }
    }
}

@Composable private fun Connections(conns: List<AppStore.Connection>, store: AppStore, changed: () -> Unit) {
    var dialog by remember { mutableStateOf(false) }; var name by remember { mutableStateOf("") }; var key by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(20.dp)) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("Connections", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); IconButton({ dialog = true }) { Icon(Icons.Default.Add, null) } }; LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) { items(conns) { c -> Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(c.name, fontWeight = FontWeight.Bold); Text(if (c.enabled) "Available" else "Disabled"); Text(c.model) }; Switch(c.enabled, { store.setConnectionEnabled(c.id, it); changed() }) } } } } }
    if (dialog) AlertDialog(onDismissRequest = { dialog = false }, title = { Text("Add API Connection") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(name, { name = it }, label = { Text("Name") }); OutlinedTextField(key, { key = it }, label = { Text("OpenAI API key") }) } }, confirmButton = { Button({ if (name.isNotBlank() && key.isNotBlank()) { store.addConnection(name, key); name = ""; key = ""; dialog = false; changed() } }) { Text("Save") } }, dismissButton = { TextButton({ dialog = false }) { Text("Cancel") } })
}

@Composable private fun Results(tasks: List<AppStore.Task>) { val done = tasks.filter { it.status == "COMPLETED" && it.outputUri != null }; LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.fillMaxSize().padding(10.dp)) { items(done) { t -> Card(Modifier.padding(5.dp)) { Column { AsyncImage(t.outputUri, null, Modifier.fillMaxWidth().aspectRatio(1f), contentScale = ContentScale.Crop); Text(t.name, Modifier.padding(8.dp)) } } } } }
