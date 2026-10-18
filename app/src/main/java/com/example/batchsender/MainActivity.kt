package com.example.batchsender

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BatchSenderApp() }
    }
}

enum class Screen { Home, NewBatch, Results, Connections }

@Composable
private fun BatchSenderApp() {
    val context = LocalContext.current
    val store = remember { AppStore(context) }
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf(Screen.Home) }
    var selected by remember { mutableStateOf(store.loadSelected()) }
    var prompt by remember { mutableStateOf(store.loadPrompt()) }
    var connections by remember { mutableStateOf(store.loadConnections()) }
    var tasks by remember { mutableStateOf(store.loadTasks()) }
    var running by remember { mutableStateOf(store.isRunning()) }

    LaunchedEffect(Unit) {
        while (true) { tasks = store.loadTasks(); connections = store.loadConnections(); running = store.isRunning(); delay(700) }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { runCatching { context.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
            selected = (selected + uris).distinct(); store.saveSelected(selected)
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF8B7CFF), secondary = Color(0xFF35D4C8))) {
        Scaffold(bottomBar = {
            NavigationBar {
                NavItem(Screen.Home, Icons.Default.Home, "Home", screen) { screen = it }
                NavItem(Screen.NewBatch, Icons.Default.AddPhotoAlternate, "New Batch", screen) { screen = it }
                NavItem(Screen.Results, Icons.Default.GridView, "Results", screen) { screen = it }
                NavItem(Screen.Connections, Icons.Default.Key, "Connections", screen) { screen = it }
            }
        }) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (screen) {
                    Screen.Home -> HomeScreen(tasks, connections, running, store) { screen = Screen.NewBatch }
                    Screen.NewBatch -> NewBatchScreen(selected, prompt, store, picker, scope) { selected = emptyList(); prompt = ""; store.saveSelected(emptyList()); screen = Screen.Home }
                    Screen.Results -> ResultsScreen(tasks)
                    Screen.Connections -> ConnectionsScreen(connections, store) { connections = store.loadConnections() }
                }
            }
        }
    }
}

@Composable private fun NavItem(s: Screen, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, current: Screen, go: (Screen) -> Unit) {
    NavigationBarItem(selected = current == s, onClick = { go(s) }, icon = { Icon(icon, null) }, label = { Text(label) })
}

@Composable private fun HomeScreen(tasks: List<AppStore.Task>, connections: List<AppStore.Connection>, running: Boolean, store: AppStore, newBatch: () -> Unit) {
    val completed = tasks.count { it.status == "COMPLETED" }; val failed = tasks.count { it.status == "FAILED" }; val processing = tasks.count { it.status == "PROCESSING" || it.status == "UPLOADING" }
    LazyColumn(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("Batch Image Studio", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text("Parallel AI image processing workspace", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Button(onClick = newBatch, Modifier.fillMaxWidth().height(56.dp)) { Icon(Icons.Default.AddPhotoAlternate, null); Spacer(Modifier.width(8.dp)); Text("New Batch") } }
        item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text(if (running) "Batch is running" else "Ready", style = MaterialTheme.typography.titleLarge); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Stat("Processing", processing.toString()); Stat("Completed", completed.toString()); Stat("Failed", failed.toString()) }; Text("Connections: ${connections.count { it.enabled }} active / ${connections.size}", color = MaterialTheme.colorScheme.onSurfaceVariant); if (running) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton({ store.setPaused(!store.isPaused()) }) { Text(if (store.isPaused()) "Resume" else "Pause") }; OutlinedButton({ store.setRunning(false) }) { Text("Stop scheduling") } } } } }
        item { Text("Live Tasks", style = MaterialTheme.typography.titleLarge) }
        items(tasks.take(30)) { TaskRow(it) }
    }
}

@Composable private fun Stat(label: String, value: String) { Column { Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant) } }

@Composable private fun TaskRow(t: AppStore.Task) { Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { AsyncImage(model = t.inputUri, contentDescription = null, modifier = Modifier.size(58.dp), contentScale = ContentScale.Crop); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(t.name, fontWeight = FontWeight.SemiBold); Text(t.status, color = statusColor(t.status)); if (t.error.isNotBlank()) Text(t.error, maxLines = 1, color = MaterialTheme.colorScheme.error) } } } }
private fun statusColor(status: String) = when (status) { "COMPLETED" -> Color(0xFF48D597); "FAILED" -> Color(0xFFFF6B6B); "PROCESSING", "UPLOADING" -> Color(0xFF8B7CFF); else -> Color(0xFF9AA0AA) }

@Composable private fun NewBatchScreen(selected: List<Uri>, prompt0: String, store: AppStore, picker: ActivityResultLauncher<Array<String>>, scope: kotlinx.coroutines.CoroutineScope, done: () -> Unit) {
    var prompt by remember(prompt0) { mutableStateOf(prompt0) }; var name by remember { mutableStateOf("Batch ${System.currentTimeMillis().toString().takeLast(5)}") }; val connections = store.loadConnections().filter { it.enabled }; val context = LocalContext.current; var busy by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("New Batch", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        item { OutlinedTextField(name, { name = it }, label = { Text("Batch name") }, Modifier.fillMaxWidth(), singleLine = true) }
        item { Card(Modifier.fillMaxWidth().clickable { picker.launch(arrayOf("image/*")) }) { Column(Modifier.padding(18.dp)) { Text("Images", style = MaterialTheme.typography.titleLarge); Text("${selected.size} selected", color = MaterialTheme.colorScheme.onSurfaceVariant); Text("Tap to add images") } } }
        if (selected.isNotEmpty()) item { LazyVerticalGrid(GridCells.Fixed(4), Modifier.height(180.dp), userScrollEnabled = false) { items(selected) { AsyncImage(it, null, Modifier.padding(3.dp).fillMaxWidth().aspectRatio(1f), contentScale = ContentScale.Crop) } } }
        item { OutlinedTextField(prompt, { prompt = it }, label = { Text("Fixed Prompt") }, Modifier.fillMaxWidth().height(170.dp)) }
        item { Text("${connections.size} active connections", style = MaterialTheme.typography.titleMedium) }
        items(connections) { c -> Text("• ${c.name} — ${c.model}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Button(enabled = selected.isNotEmpty() && prompt.isNotBlank() && connections.isNotEmpty() && !busy, onClick = { busy = true; scope.launch { store.createBatch(context, name, selected, prompt, connections); busy = false; done() } }, Modifier.fillMaxWidth().height(56.dp)) { if (busy) CircularProgressIndicator(Modifier.size(22.dp)) else { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("Start Parallel Processing") } } }
    }
}

@Composable private fun ConnectionsScreen(conns: List<AppStore.Connection>, store: AppStore, changed: () -> Unit) {
    var show by remember { mutableStateOf(false) }; var name by remember { mutableStateOf("") }; var key by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(20.dp)) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("Connections", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); IconButton({ show = true }) { Icon(Icons.Default.Add, "Add") } }; LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) { items(conns) { c -> Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(c.name, fontWeight = FontWeight.Bold); Text(if (c.enabled) "Available" else "Disabled", color = if (c.enabled) Color(0xFF48D597) else Color.Gray); Text(c.model, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Switch(c.enabled, { store.setConnectionEnabled(c.id, it); changed() }) } } } } }
    if (show) AlertDialog(onDismissRequest = { show = false }, title = { Text("Add API Connection") }, text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { OutlinedTextField(name, { name = it }, label = { Text("Name") }); OutlinedTextField(key, { key = it }, label = { Text("OpenAI API key") }) } }, confirmButton = { Button(onClick = { if (name.isNotBlank() && key.isNotBlank()) { store.addConnection(name, key); name = ""; key = ""; show = false; changed() } }) { Text("Save") } }, dismissButton = { TextButton({ show = false }) { Text("Cancel") } })
}

@Composable private fun ResultsScreen(tasks: List<AppStore.Task>) { val done = tasks.filter { it.status == "COMPLETED" && it.outputUri != null }; LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.fillMaxSize().padding(12.dp)) { items(done) { t -> Card(Modifier.padding(6.dp)) { Column { AsyncImage(t.outputUri, null, Modifier.fillMaxWidth().aspectRatio(1f), contentScale = ContentScale.Crop); Text(t.name, Modifier.padding(8.dp), maxLines = 1) } } } } }
EOF