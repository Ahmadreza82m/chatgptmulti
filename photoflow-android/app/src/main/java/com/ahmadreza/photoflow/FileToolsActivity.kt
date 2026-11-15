package com.ahmadreza.photoflow

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest

private data class ManagedRoot(val uri: Uri, val label: String)
private data class ManagedFile(
    val rootUri: Uri,
    val relativePath: String,
    val name: String,
    val mime: String,
    val size: Long
) {
    val key: String get() = rootUri.toString() + "|" + relativePath
    val parentPath: String get() = relativePath.substringBeforeLast('/', "")
}
private data class FolderComparison(
    val onlyLeft: List<String>,
    val onlyRight: List<String>,
    val sizeDifferent: List<String>,
    val contentDifferent: List<String>
)

class FileToolsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FileToolsScreen(onBack = { finish() }) }
    }
}

private object FileOperations {
    suspend fun scan(context: android.content.Context, roots: List<ManagedRoot>): List<ManagedFile> =
        withContext(Dispatchers.IO) {
            roots.flatMap { rootInfo ->
                val root = DocumentFile.fromTreeUri(context, rootInfo.uri) ?: return@flatMap emptyList()
                val found = mutableListOf<ManagedFile>()
                fun walk(folder: DocumentFile, prefix: String, depth: Int) {
                    if (depth > 30) return
                    folder.listFiles().forEach { child ->
                        val name = child.name ?: return@forEach
                        val path = if (prefix.isEmpty()) name else "$prefix/$name"
                        if (child.isDirectory) walk(child, path, depth + 1)
                        else if (child.isFile) found += ManagedFile(
                            rootInfo.uri, path, name,
                            child.type ?: "application/octet-stream", child.length()
                        )
                    }
                }
                walk(root, "", 0)
                found
            }.distinctBy { it.key }.sortedWith(compareBy({ it.name.lowercase() }, { it.relativePath.lowercase() }))
        }

    suspend fun compare(context: android.content.Context, left: List<ManagedFile>, right: List<ManagedFile>): FolderComparison =
        withContext(Dispatchers.IO) {
            val a = left.associateBy { it.relativePath }
            val b = right.associateBy { it.relativePath }
            val shared = a.keys intersect b.keys
            val sizeDiff = shared.filter { a[it]?.size != b[it]?.size }.sorted()
            val contentDiff = shared.filter { path ->
                val first = a[path]!!
                val second = b[path]!!
                first.size == second.size && sha256(context, first) != sha256(context, second)
            }.sorted()
            FolderComparison(
                onlyLeft = (a.keys - b.keys).sorted(),
                onlyRight = (b.keys - a.keys).sorted(),
                sizeDifferent = sizeDiff,
                contentDifferent = contentDiff
            )
        }

    suspend fun findDuplicates(context: android.content.Context, files: List<ManagedFile>): List<List<ManagedFile>> =
        withContext(Dispatchers.IO) {
            files.groupBy { it.size }.values.filter { it.size > 1 }.flatMap { sameSize ->
                sameSize.groupBy { sha256(context, it) }.values.filter { it.size > 1 }
            }
        }

    private fun sha256(context: android.content.Context, file: ManagedFile): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val doc = resolve(context, file.rootUri, file.relativePath) ?: error("فایل پیدا نشد: ${file.name}")
        context.contentResolver.openInputStream(doc.uri)?.use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        } ?: error("خواندن فایل ناموفق بود: ${file.name}")
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun renameBatch(
        context: android.content.Context,
        selected: List<ManagedFile>,
        pattern: String
    ): Int = withContext(Dispatchers.IO) {
        require(selected.isNotEmpty()) { "ابتدا فایل‌ها را انتخاب کن." }
        require(pattern.isNotBlank()) { "الگوی نام‌گذاری خالی است." }
        val all = selected.mapIndexed { index, file ->
            val ext = file.name.substringAfterLast('.', "").takeIf { '.' in file.name }.orEmpty()
            val base = file.name.substringBeforeLast('.', file.name)
            var newName = pattern.replace("{name}", base)
                .replace("{index}", (index + 1).toString().padStart(2, '0'))
                .replace("{ext}", ext)
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            if (ext.isNotEmpty() && !pattern.contains("{ext}") && !newName.endsWith(".$ext", true)) newName += ".$ext"
            file to newName
        }
        require(all.map { it.second.lowercase() }.distinct().size == all.size) {
            "الگوی نام‌گذاری برای چند فایل نام یکسان ساخته است."
        }
        val selectedKeys = selected.map { it.key }.toSet()
        val targetKeys = all.map { (file, name) ->
            (file.rootUri.toString() + "|" + file.parentPath + "|" + name).lowercase()
        }
        require(targetKeys.distinct().size == targetKeys.size) {
            "دو فایل در یک پوشه نمی‌توانند نام یکسان بگیرند."
        }
        all.forEach { (file, targetName) ->
            val parent = resolveParent(context, file.rootUri, file.parentPath) ?: error("پوشه فایل پیدا نشد.")
            val collision = parent.listFiles().any { child ->
                val childPath = (file.parentPath + "/" + (child.name ?: "")).trim('/')
                val childKey = file.rootUri.toString() + "|" + childPath
                child.name.equals(targetName, true) && childKey !in selectedKeys
            }
            require(!collision) { "فایلی با نام «$targetName» از قبل در پوشه وجود دارد." }
        }
        val staged = mutableListOf<Triple<ManagedFile, DocumentFile, String>>()
        all.forEachIndexed { index, (file, targetName) ->
            val doc = resolve(context, file.rootUri, file.relativePath) ?: error("فایل پیدا نشد: ${file.name}")
            val temp = ".__photoflow_tmp_${System.nanoTime()}_$index"
            check(doc.renameTo(temp)) { "تغییر نام موقت فایل ناموفق بود: ${file.name}" }
            staged += Triple(file, resolveParent(context, file.rootUri, file.parentPath)
                ?.findFile(temp) ?: error("فایل موقت پیدا نشد."), targetName)
        }
        var completed = 0
        try {
            staged.forEach { (_, doc, targetName) ->
                check(doc.renameTo(targetName)) { "تغییر نام به «$targetName» ناموفق بود." }
                completed++
            }
        } catch (error: Exception) {
            staged.drop(completed).forEachIndexed { index, (old, doc, _) ->
                runCatching { doc.renameTo(old.name) }
            }
            throw error
        }
        staged.size
    }

    suspend fun copyOrMove(
        context: android.content.Context,
        selected: List<ManagedFile>,
        destination: Uri,
        move: Boolean
    ): Int = withContext(Dispatchers.IO) {
        require(selected.isNotEmpty()) { "ابتدا فایل‌ها را انتخاب کن." }
        val target = DocumentFile.fromTreeUri(context, destination) ?: error("پوشه مقصد در دسترس نیست.")
        var count = 0
        selected.forEach { file ->
            val source = resolve(context, file.rootUri, file.relativePath) ?: error("فایل پیدا نشد: ${file.name}")
            val name = uniqueName(target, file.name)
            val output = target.createFile(file.mime, name) ?: error("ساخت فایل مقصد ناموفق بود: $name")
            try {
                context.contentResolver.openInputStream(source.uri)?.use { input ->
                    context.contentResolver.openOutputStream(output.uri)?.use { out -> input.copyTo(out) }
                        ?: error("نوشتن فایل مقصد ناموفق بود: $name")
                } ?: error("خواندن فایل منبع ناموفق بود: ${file.name}")
                if (move) check(source.delete()) { "فایل کپی شد اما حذف نسخه مبدأ ناموفق بود: ${file.name}" }
                count++
            } catch (error: Exception) {
                output.delete()
                throw error
            }
        }
        count
    }

    private fun uniqueName(parent: DocumentFile, original: String): String {
        val names = parent.listFiles().mapNotNull { it.name }.toSet()
        if (original !in names) return original
        val base = original.substringBeforeLast('.', original)
        val ext = original.substringAfterLast('.', "").takeIf { '.' in original }?.let { ".$it" }.orEmpty()
        var index = 2
        while ("$base ($index)$ext" in names) index++
        return "$base ($index)$ext"
    }

    private fun resolve(context: android.content.Context, rootUri: Uri, relativePath: String): DocumentFile? {
        var current = DocumentFile.fromTreeUri(context, rootUri) ?: return null
        relativePath.split('/').filter { it.isNotEmpty() }.forEach { segment ->
            current = current.findFile(segment) ?: return null
        }
        return current
    }

    private fun resolveParent(context: android.content.Context, rootUri: Uri, parentPath: String): DocumentFile? {
        if (parentPath.isBlank()) return DocumentFile.fromTreeUri(context, rootUri)
        var current = DocumentFile.fromTreeUri(context, rootUri) ?: return null
        parentPath.split('/').filter { it.isNotEmpty() }.forEach { segment ->
            current = current.findFile(segment) ?: return null
        }
        return current
    }
}

@Composable
private fun FileToolsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("photoflow-file-tools", 0) }
    val scope = rememberCoroutineScope()
    fun readRoots(): List<ManagedRoot> = prefs.getStringSet("roots", mutableSetOf<String>()).orEmpty().mapNotNull { raw ->
        runCatching { Uri.parse(raw) }.getOrNull()?.let { ManagedRoot(it, it.lastPathSegment?.substringAfterLast(':') ?: "پوشه") }
    }.sortedBy { it.label }
    var roots by remember { mutableStateOf(readRoots()) }
    var files by remember { mutableStateOf<List<ManagedFile>>(emptyList()) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var destination by remember { mutableStateOf<Uri?>(null) }
    var leftRoot by remember { mutableStateOf<Uri?>(null) }
    var rightRoot by remember { mutableStateOf<Uri?>(null) }
    var pattern by remember { mutableStateOf("{name}_{index}.{ext}") }
    var status by remember { mutableStateOf("چند پوشه اضافه کن تا ابزارهای مدیریت فایل فعال شوند.") }
    var comparison by remember { mutableStateOf<FolderComparison?>(null) }
    var duplicates by remember { mutableStateOf<List<List<ManagedFile>>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            if (roots.none { it.uri == uri }) {
                roots = (roots + ManagedRoot(uri, uri.lastPathSegment?.substringAfterLast(':') ?: "پوشه"))
                prefs.edit().putStringSet("roots", roots.map { it.uri.toString() }.toSet()).apply()
                if (destination == null) destination = uri
                if (leftRoot == null) leftRoot = uri else if (rightRoot == null) rightRoot = uri
            }
            status = "پوشه اضافه شد. برای بارگذاری فایل‌ها، «اسکن همه پوشه‌ها» را بزن."
        }
    }

    fun refresh() {
        scope.launch {
            busy = true
            try {
                files = FileOperations.scan(context, roots)
                selected = selected intersect files.map { it.key }.toSet()
                status = "${files.size} فایل از ${roots.size} پوشه پیدا شد."
            } catch (e: Exception) { status = e.message ?: "اسکن پوشه‌ها ناموفق بود." }
            busy = false
        }
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("بازگشت") }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("مدیریت پیشرفته فایل‌ها", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("قابلیت‌های ۱ تا ۶", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { folderPicker.launch(null) }, enabled = !busy) { Text("افزودن پوشه +") }
                OutlinedButton(onClick = { refresh() }, enabled = roots.isNotEmpty() && !busy) {
                    Text(if (busy) "در حال اسکن…" else "اسکن همه پوشه‌ها")
                }
            }
            Text("پوشه‌های ثبت‌شده: ${roots.size}", fontWeight = FontWeight.SemiBold)
            roots.forEach { root ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(root.label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = {
                        roots = roots.filterNot { it.uri == root.uri }
                        prefs.edit().putStringSet("roots", roots.map { it.uri.toString() }.toSet()).apply()
                        if (destination == root.uri) destination = roots.firstOrNull()?.uri
                        if (leftRoot == root.uri) leftRoot = roots.firstOrNull()?.uri
                        if (rightRoot == root.uri) rightRoot = roots.lastOrNull()?.uri
                        refresh()
                    }) { Text("حذف") }
                }
            }
            Divider()
            Text("۲. مقایسه دو پوشه", fontWeight = FontWeight.Bold)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                RootPicker("پوشه اول", roots, leftRoot) { leftRoot = it }
                RootPicker("پوشه دوم", roots, rightRoot) { rightRoot = it }
                Button(onClick = {
                    val a = files.filter { it.rootUri == leftRoot }
                    val b = files.filter { it.rootUri == rightRoot }
                    scope.launch {
                        busy = true
                        try {
                            comparison = FileOperations.compare(context, a, b)
                            status = "مقایسه مسیرها، اندازه و محتوای فایل‌ها انجام شد."
                        } catch (e: Exception) { status = e.message ?: "مقایسه ناموفق بود." }
                        busy = false
                    }
                }, enabled = leftRoot != null && rightRoot != null && !busy) { Text("مقایسه") }
            }
            comparison?.let { result ->
                Text("فقط در اول: ${result.onlyLeft.size}  •  فقط در دوم: ${result.onlyRight.size}  •  اختلاف اندازه: ${result.sizeDifferent.size}  •  محتوای متفاوت: ${result.contentDifferent.size}",
                    style = MaterialTheme.typography.bodySmall)
                (result.onlyLeft + result.onlyRight + result.sizeDifferent + result.contentDifferent).take(5).forEach {
                    Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Divider()
            Text("۳. تغییر نام گروهی", fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = pattern, onValueChange = { pattern = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("الگو: {name} نام قبلی، {index} شماره، {ext} پسوند") }, singleLine = true
            )
            Text("انتخاب‌شده: ${selected.size}", style = MaterialTheme.typography.bodySmall)
            files.filter { it.key in selected }.take(4).forEachIndexed { index, file ->
                Text("پیش‌نمایش: ${file.name}  ←  ${previewRename(file.name, pattern, index)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch {
                        busy = true
                        try {
                            val chosen = files.filter { it.key in selected }
                            val count = FileOperations.renameBatch(context, chosen, pattern)
                            status = "نام ${count} فایل تغییر کرد."
                            refresh()
                        } catch (e: Exception) { status = e.message ?: "تغییر نام ناموفق بود." }
                        busy = false
                    }
                }, enabled = selected.isNotEmpty() && !busy) { Text("اجرای تغییر نام") }
                TextButton(onClick = { selected = files.map { it.key }.toSet() }) { Text("انتخاب همه") }
                TextButton(onClick = { selected = emptySet() }) { Text("پاک‌کردن انتخاب") }
            }
            Divider()
            Text("۴ و ۵. کپی یا انتقال گروهی", fontWeight = FontWeight.Bold)
            RootPicker("پوشه مقصد", roots, destination) { destination = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { runFileTransfer(context, scope, files, selected, destination, false, { busy = it }, { status = it }, ::refresh) },
                    enabled = selected.isNotEmpty() && destination != null && !busy) { Text("کپی فایل‌ها") }
                Button(onClick = { runFileTransfer(context, scope, files, selected, destination, true, { busy = it }, { status = it }, ::refresh) },
                    enabled = selected.isNotEmpty() && destination != null && !busy) { Text("انتقال فایل‌ها") }
            }
            Divider()
            Text("۶. شناسایی فایل‌های کاملاً یکسان", fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = {
                scope.launch {
                    busy = true
                    try {
                        duplicates = FileOperations.findDuplicates(context, files)
                        status = "${duplicates.size} گروه فایل کاملاً یکسان پیدا شد."
                    } catch (e: Exception) { status = e.message ?: "بررسی تکراری‌ها ناموفق بود." }
                    busy = false
                }
            }, enabled = files.size > 1 && !busy) { Text("بررسی تکراری‌ها با SHA-256") }
            duplicates.forEachIndexed { index, group ->
                Text("گروه ${index + 1}: ${group.size} نسخه — ${group.first().name}", style = MaterialTheme.typography.bodySmall)
            }
            Text(status, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
            Divider()
            Text("۱. فایل‌های همه پوشه‌ها — ${files.size}", fontWeight = FontWeight.Bold)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                files.forEach { file ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Checkbox(checked = file.key in selected, onCheckedChange = { checked ->
                            selected = if (checked) selected + file.key else selected - file.key
                        })
                        Column(Modifier.weight(1f)) {
                            Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${file.relativePath}  •  ${formatSize(file.size)}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RootPicker(
    label: String,
    roots: List<ManagedRoot>,
    selected: Uri?,
    onSelected: (Uri) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = roots.isNotEmpty()) {
            Text(roots.firstOrNull { it.uri == selected }?.label ?: label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            roots.forEach { root ->
                DropdownMenuItem(text = { Text(root.label) }, onClick = { onSelected(root.uri); expanded = false })
            }
        }
    }
}

private fun runFileTransfer(
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    files: List<ManagedFile>,
    selected: Set<String>,
    destination: Uri?,
    move: Boolean,
    setBusy: (Boolean) -> Unit,
    setStatus: (String) -> Unit,
    refresh: () -> Unit
) {
    scope.launch {
        setBusy(true)
        try {
            val count = FileOperations.copyOrMove(context, files.filter { it.key in selected }, destination!!, move)
            setStatus("${count} فایل با موفقیت ${if (move) "منتقل" else "کپی"} شد.")
            refresh()
        } catch (e: Exception) { setStatus(e.message ?: "عملیات فایل ناموفق بود.") }
        setBusy(false)
    }
}

private fun formatSize(size: Long): String = when {
    size < 1024 -> "$size B"
    size < 1024 * 1024 -> "%.1f KB".format(size / 1024.0)
    size < 1024L * 1024L * 1024L -> "%.1f MB".format(size / (1024.0 * 1024.0))
    else -> "%.2f GB".format(size / (1024.0 * 1024.0 * 1024.0))
}

private fun previewRename(name: String, pattern: String, index: Int): String {
    val ext = name.substringAfterLast('.', "").takeIf { '.' in name }.orEmpty()
    val base = name.substringBeforeLast('.', name)
    var result = pattern.replace("{name}", base)
        .replace("{index}", (index + 1).toString().padStart(2, '0'))
        .replace("{ext}", ext)
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
    if (ext.isNotEmpty() && !pattern.contains("{ext}") && !result.endsWith(".$ext", true)) result += ".$ext"
    return result
}
