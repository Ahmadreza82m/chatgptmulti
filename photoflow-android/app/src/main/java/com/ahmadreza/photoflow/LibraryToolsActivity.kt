package com.ahmadreza.photoflow

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject


private object LibraryAudit {
    private const val HISTORY = "library_operation_history"
    private const val ERRORS = "library_error_log"
    fun record(p: android.content.SharedPreferences, action: String, key: String, before: String, after: String) {
        val a = runCatching { JSONArray(p.getString(HISTORY, "[]") ?: "[]") }.getOrDefault(JSONArray())
        a.put(JSONObject().put("action", action).put("key", key).put("before", before).put("after", after))
        while (a.length() > 100) a.remove(0)
        p.edit().putString(HISTORY, a.toString()).apply()
    }
    fun logError(p: android.content.SharedPreferences, message: String) {
        val a = runCatching { JSONArray(p.getString(ERRORS, "[]") ?: "[]") }.getOrDefault(JSONArray())
        a.put(message.take(500))
        while (a.length() > 50) a.remove(0)
        p.edit().putString(ERRORS, a.toString()).apply()
    }
    fun history(p: android.content.SharedPreferences): List<String> {
        val a = runCatching { JSONArray(p.getString(HISTORY, "[]") ?: "[]") }.getOrDefault(JSONArray())
        return (0 until a.length()).mapNotNull { i -> runCatching {
            val x = a.getJSONObject(i)
            val label = when (x.optString("action")) {
                "favorite" -> "علاقه‌مندی"; "marked" -> "علامت فایل"
                "category" -> "دسته‌بندی"; "tags" -> "برچسب‌ها"; else -> "تغییر"
            }
            "$label • ${x.optString("key").substringAfterLast('/').take(28)}"
        }.getOrNull() }.reversed()
    }
    fun errors(p: android.content.SharedPreferences): List<String> {
        val a = runCatching { JSONArray(p.getString(ERRORS, "[]") ?: "[]") }.getOrDefault(JSONArray())
        return (0 until a.length()).mapNotNull { i -> runCatching { a.getString(i) }.getOrNull() }.reversed()
    }
    fun undo(p: android.content.SharedPreferences): Boolean {
        val a = runCatching { JSONArray(p.getString(HISTORY, "[]") ?: "[]") }.getOrDefault(JSONArray())
        if (a.length() == 0) return false
        val x = a.optJSONObject(a.length() - 1) ?: return false
        val key = x.optString("key"); val before = x.optString("before")
        when (x.optString("action")) {
            "favorite", "marked" -> {
                val pref = if (x.optString("action") == "favorite") "library_favorites" else "library_marked"
                val values = p.getStringSet(pref, emptySet()).orEmpty().toMutableSet()
                if (before == "true") values.add(key) else values.remove(key)
                p.edit().putStringSet(pref, values).apply()
            }
            "category" -> p.edit().putString("category:${encodeKey(key)}", before).apply()
            "tags" -> p.edit().putStringSet("tags:${encodeKey(key)}", before.split('\n').filter { it.isNotBlank() }.toSet()).apply()
            else -> return false
        }
        a.remove(a.length() - 1); p.edit().putString(HISTORY, a.toString()).apply()
        return true
    }
}

private data class LibraryRoot(val uri: Uri, val name: String)
private data class LibraryFile(val root: Uri, val path: String, val name: String, val mime: String, val size: Long) {
    val key: String get() = root.toString() + "|" + path
}
private enum class LibraryKind(val title: String) { ALL("همه"), IMAGES("تصاویر"), VIDEOS("ویدئو"), DOCS("سند"), OTHER("سایر") }

class LibraryToolsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LibraryToolsScreen(onBack = { finish() }) }
    }
}

private object LibraryOps {
    suspend fun scan(context: android.content.Context, roots: List<LibraryRoot>): List<LibraryFile> =
        withContext(Dispatchers.IO) {
            roots.flatMap { rootInfo ->
                val root = DocumentFile.fromTreeUri(context, rootInfo.uri) ?: return@flatMap emptyList()
                val found = mutableListOf<LibraryFile>()
                fun walk(folder: DocumentFile, prefix: String, depth: Int) {
                    if (depth > 30) return
                    folder.listFiles().forEach { child ->
                        val name = child.name ?: return@forEach
                        val path = if (prefix.isEmpty()) name else "$prefix/$name"
                        if (child.isDirectory) walk(child, path, depth + 1)
                        else if (child.isFile) found += LibraryFile(rootInfo.uri, path, name, child.type ?: "", child.length())
                    }
                }
                walk(root, "", 0)
                found
            }.distinctBy { it.key }.sortedBy { it.name.lowercase(Locale.ROOT) }
        }

    suspend fun processImages(
        context: android.content.Context,
        selected: List<LibraryFile>,
        destination: Uri,
        targetFormat: String?,
        quality: Int
    ): Int = withContext(Dispatchers.IO) {
        require(selected.isNotEmpty()) { "ابتدا چند تصویر انتخاب کن." }
        val target = DocumentFile.fromTreeUri(context, destination) ?: error("پوشه مقصد در دسترس نیست.")
        var completed = 0
        selected.forEach { file ->
            require(file.mime.startsWith("image/")) { "فایل «${file.name}» تصویر نیست." }
            val source = resolve(context, file.root, file.path) ?: error("فایل پیدا نشد: ${file.name}")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val boundsStream = context.contentResolver.openInputStream(source.uri)
                ?: error("خواندن تصویر ناموفق بود: ${file.name}")
            boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "تصویر «${file.name}» قابل رمزگشایی نیست." }
            val sample = calculateSample(bounds.outWidth, bounds.outHeight)
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = context.contentResolver.openInputStream(source.uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                ?: error("بازکردن تصویر ناموفق بود: ${file.name}")
            try {
                val format = when (targetFormat ?: file.name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
                    "png" -> Bitmap.CompressFormat.PNG
                    "webp" -> Bitmap.CompressFormat.WEBP
                    else -> Bitmap.CompressFormat.JPEG
                }
                val extension = when (format) {
                    Bitmap.CompressFormat.PNG -> "png"
                    Bitmap.CompressFormat.WEBP -> "webp"
                    else -> "jpg"
                }
                val base = file.name.substringBeforeLast('.', file.name)
                val outputName = uniqueName(target, "$base.$extension")
                val mime = when (format) {
                    Bitmap.CompressFormat.PNG -> "image/png"
                    Bitmap.CompressFormat.WEBP -> "image/webp"
                    else -> "image/jpeg"
                }
                val output = target.createFile(mime, outputName) ?: error("ساخت فایل خروجی ناموفق بود: $outputName")
                try {
                    val written = context.contentResolver.openOutputStream(output.uri)?.use { stream ->
                        bitmap.compress(format, if (format == Bitmap.CompressFormat.PNG) 100 else quality, stream)
                    } ?: false
                    check(written) { "ذخیره تصویر ناموفق بود: $outputName" }
                } catch (e: Exception) {
                    output.delete()
                    throw e
                }
                completed++
            } finally {
                bitmap.recycle()
            }
        }
        completed
    }

    private fun calculateSample(width: Int, height: Int): Int {
        var sample = 1
        while (width / sample > 2048 || height / sample > 2048) sample *= 2
        return sample
    }

    private fun uniqueName(parent: DocumentFile, original: String): String {
        val existing = parent.listFiles().mapNotNull { it.name }.toHashSet()
        if (original !in existing) return original
        val base = original.substringBeforeLast('.', original)
        val ext = original.substringAfterLast('.', "").takeIf { '.' in original }?.let { ".$it" }.orEmpty()
        var index = 2
        while ("$base ($index)$ext" in existing) index++
        return "$base ($index)$ext"
    }

    private fun resolve(context: android.content.Context, rootUri: Uri, path: String): DocumentFile? {
        var current = DocumentFile.fromTreeUri(context, rootUri) ?: return null
        path.split('/').filter { it.isNotEmpty() }.forEach { current = current.findFile(it) ?: return null }
        return current
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryToolsScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("photoflow-file-tools", 0) }
    val scope = rememberCoroutineScope()
    fun loadRoots() = prefs.getStringSet("roots", emptySet()).orEmpty().mapNotNull { raw ->
        runCatching { Uri.parse(raw) }.getOrNull()?.let { LibraryRoot(it, it.lastPathSegment?.substringAfterLast(':') ?: "پوشه") }
    }.sortedBy { it.name }
    var roots by remember { mutableStateOf(loadRoots()) }
    var files by remember { mutableStateOf<List<LibraryFile>>(emptyList()) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var favoriteKeys by remember { mutableStateOf(prefs.getStringSet("library_favorites", emptySet()).orEmpty()) }
    var markedKeys by remember { mutableStateOf(prefs.getStringSet("library_marked", emptySet()).orEmpty()) }
    var markedOnly by remember { mutableStateOf(false) }
    var showHistoryDialog by remember { mutableStateOf(false) }
    var showErrorsDialog by remember { mutableStateOf(false) }
    var showHealthDialog by remember { mutableStateOf(false) }
    var categories by remember { mutableStateOf(prefs.getStringSet("library_categories", setOf("بدون دسته")).orEmpty().toList().sorted()) }
    var search by remember { mutableStateOf("") }
    var tagSearch by remember { mutableStateOf("") }
    var largeFilesOnly by remember { mutableStateOf(false) }
    var kind by remember { mutableStateOf(LibraryKind.ALL) }
    var favoritesOnly by remember { mutableStateOf(false) }
    var categoryFilter by remember { mutableStateOf("همه دسته‌ها") }
    var destination by remember { mutableStateOf(roots.firstOrNull()?.uri) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("برای شروع، پوشه‌ها را از بخش مدیریت فایل اضافه کن و اسکن را بزن.") }
    var showCategoryDialog by remember { mutableStateOf(false) }
    var newCategory by remember { mutableStateOf("") }
    var tagTarget by remember { mutableStateOf<LibraryFile?>(null) }
    var tagText by remember { mutableStateOf("") }
    var showConvertDialog by remember { mutableStateOf(false) }
    var outputFormat by remember { mutableStateOf("jpg") }
    var quality by remember { mutableIntStateOf(82) }
    var destinationExpanded by remember { mutableStateOf(false) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            val label = uri.lastPathSegment?.substringAfterLast(':') ?: "پوشه"
            roots = (roots + LibraryRoot(uri, label)).distinctBy { it.uri }
            prefs.edit().putStringSet("roots", roots.map { it.uri.toString() }.toSet()).apply()
            if (destination == null) destination = uri
            status = "پوشه اضافه شد؛ اسکن را اجرا کن."
        }
    }

    fun scanNow() {
        scope.launch {
            busy = true
            try {
                files = LibraryOps.scan(context, roots)
                selected = selected intersect files.map { it.key }.toSet()
                status = "${files.size} فایل از ${roots.size} پوشه بارگذاری شد."
            } catch (e: Exception) { status = e.message ?: "اسکن انجام نشد."; LibraryAudit.logError(prefs, status) }
            busy = false
        }
    }

    val filtered = files.filter { file ->
        val typeMatches = when (kind) {
            LibraryKind.ALL -> true
            LibraryKind.IMAGES -> file.mime.startsWith("image/")
            LibraryKind.VIDEOS -> file.mime.startsWith("video/")
            LibraryKind.DOCS -> file.mime.contains("pdf") || file.mime.contains("document") || file.mime.contains("text")
            LibraryKind.OTHER -> !(file.mime.startsWith("image/") || file.mime.startsWith("video/") || file.mime.contains("pdf") || file.mime.contains("document") || file.mime.contains("text"))
        }
        val categoryMatches = categoryFilter == "همه دسته‌ها" || prefs.getString("category:${encodeKey(file.key)}", "بدون دسته") == categoryFilter
        val tags = prefs.getStringSet("tags:${encodeKey(file.key)}", emptySet()).orEmpty()
        val tagMatches = tagSearch.isBlank() || tags.any { it.contains(tagSearch, true) }
        typeMatches && categoryMatches && tagMatches && (!largeFilesOnly || file.size >= 10L * 1024L * 1024L) && (!favoritesOnly || file.key in favoriteKeys) && (!markedOnly || file.key in markedKeys) &&
            (search.isBlank() || file.name.contains(search, true) || file.path.contains(search, true))
    }
    val imageFiles = files.filter { it.mime.startsWith("image/") }
    val totalBytes = files.sumOf { it.size }
    val favoriteCount = files.count { it.key in favoriteKeys }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Color(0xFFC7F36B), secondary = Color(0xFF8DE0D0),
        background = Color(0xFF080C12), surface = Color(0xFF121A24),
        surfaceVariant = Color(0xFF1D2936), onBackground = Color(0xFFF4F2FF),
        onSurface = Color(0xFFF4F2FF)
    )) {
        Scaffold(containerColor = Color(0xFF080C12), topBar = {
            TopAppBar(
                title = { Column {
                    Text("کتابخانه هوشمند", fontWeight = FontWeight.Bold)
                    Text("فیلتر، برچسب و پردازش گروهی", style = MaterialTheme.typography.labelSmall, color = Color(0xFFAAA8BE))
                } },
                navigationIcon = { TextButton(onClick = onBack) { Text("بازگشت") } },
                actions = { TextButton(onClick = { scanNow() }, enabled = !busy) { Text(if (busy) "…" else "اسکن ↻") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0E151E))
            )
        }) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Card(shape = RoundedCornerShape(26.dp), colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                        modifier = Modifier.fillMaxWidth()) {
                        Box(Modifier.background(Brush.linearGradient(listOf(Color(0xFF263A4C), Color(0xFF173B3B), Color(0xFF171A2B)))).padding(18.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("فضای فایل‌های تو", color = Color(0xFFB9B4FF), style = MaterialTheme.typography.labelLarge)
                                Text("${files.size} فایل شناسایی‌شده", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                Text("${markedKeys.size} فایل علامت‌گذاری‌شده", color = Color(0xFFC7F36B), style = MaterialTheme.typography.labelMedium)
                                Text("ذخیره خودکار روشن است • داده‌ها روی همین دستگاه ذخیره می‌شوند", color = Color(0xFFAAA8BE), style = MaterialTheme.typography.labelSmall)
                                Text("بدون تغییر در فایل‌های اصلی؛ ابزارها روی نسخه خروجی کار می‌کنند.", color = Color(0xFFD0D1E0), style = MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    MetricTile("تصاویر", imageFiles.size.toString(), Modifier.weight(1f))
                                    MetricTile("علاقه‌مندی", favoriteCount.toString(), Modifier.weight(1f))
                                    MetricTile("حجم کل", shortSize(totalBytes), Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { showHistoryDialog = true }, modifier = Modifier.weight(1f)) { Text("تاریخچه و بازگشت") }
                        OutlinedButton(onClick = { showErrorsDialog = true }, modifier = Modifier.weight(1f)) { Text("گزارش خطاها") }
                    }
                    OutlinedButton(onClick = { showHealthDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("بررسی سلامت پوشه‌ها") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { folderPicker.launch(null) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Text("افزودن پوشه +") }
                        OutlinedButton(onClick = { scanNow() }, enabled = roots.isNotEmpty() && !busy, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Text("بازخوانی فایل‌ها") }
                    }
                    if (roots.isNotEmpty()) Text("پوشه‌های متصل: ${roots.joinToString("، ") { it.name }}", color = Color(0xFFAAA8BE), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
                }
                item {
                    OutlinedTextField(value = search, onValueChange = { search = it }, modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("جست‌وجوی نام یا مسیر فایل…") }, singleLine = true, shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFFC7F36B), unfocusedBorderColor = Color(0xFF34384B)))
                }
                item {
                    OutlinedTextField(value = tagSearch, onValueChange = { tagSearch = it }, modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("فیلتر بر اساس برچسب ذخیره‌شده…") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                }
                item {
                    Text("فیلترهای سریع", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        LibraryKind.values().forEach { item ->
                            FilterChip(selected = kind == item, onClick = { kind = item }, label = { Text(item.title) })
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        FilterChip(selected = favoritesOnly, onClick = { favoritesOnly = !favoritesOnly }, label = { Text("★ علاقه‌مندی‌ها") })
                        Spacer(Modifier.width(6.dp))
                        FilterChip(selected = markedOnly, onClick = { markedOnly = !markedOnly }, label = { Text("⚑ علامت‌گذاری‌شده‌ها") })
                        Spacer(Modifier.width(6.dp))
                        FilterChip(selected = largeFilesOnly, onClick = { largeFilesOnly = !largeFilesOnly }, label = { Text("بیش از ۱۰ مگابایت") })
                        Spacer(Modifier.width(8.dp))
                        var catExpanded by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton(onClick = { catExpanded = true }) { Text(categoryFilter, maxLines = 1) }
                            DropdownMenu(expanded = catExpanded, onDismissRequest = { catExpanded = false }) {
                                (listOf("همه دسته‌ها") + categories).forEach { cat ->
                                    DropdownMenuItem(text = { Text(cat) }, onClick = { categoryFilter = cat; catExpanded = false })                                }
                            }                        }
                    }
                    Text("${filtered.size} نتیجه از ${files.size} فایل", color = Color(0xFFAAA8BE), style = MaterialTheme.typography.labelSmall)
                }
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF121A24)), shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Text("ابزارهای گروهی", fontWeight = FontWeight.Bold)
                            Text("${selected.size} فایل انتخاب شده", color = Color(0xFFAAA8BE), style = MaterialTheme.typography.labelSmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { selected = filtered.map { it.key }.toSet() }, modifier = Modifier.weight(1f)) { Text("انتخاب نتایج") }
                                TextButton(onClick = { selected = emptySet() }) { Text("لغو انتخاب") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { showConvertDialog = true }, enabled = selected.isNotEmpty() && selected.all { key -> files.firstOrNull { it.key == key }?.mime?.startsWith("image/") == true } && destination != null && !busy,
                                    modifier = Modifier.weight(1f)) { Text("تبدیل فرمت") }
                                OutlinedButton(onClick = { outputFormat = "keep"; showConvertDialog = true }, enabled = selected.isNotEmpty() && selected.all { key -> files.firstOrNull { it.key == key }?.mime?.startsWith("image/") == true } && destination != null && !busy,
                                    modifier = Modifier.weight(1f)) { Text("فشرده‌سازی") }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("محل ذخیره نسخه‌های جدید", modifier = Modifier.weight(1f), color = Color(0xFFAAA8BE), style = MaterialTheme.typography.bodySmall)
                                Box {
                                    OutlinedButton(onClick = { destinationExpanded = true }, enabled = roots.isNotEmpty()) {
                                        Text(roots.firstOrNull { it.uri == destination }?.name ?: "انتخاب مقصد", maxLines = 1)
                                    }
                                    DropdownMenu(expanded = destinationExpanded, onDismissRequest = { destinationExpanded = false }) {
                                        roots.forEach { root -> DropdownMenuItem(text = { Text(root.name) }, onClick = { destination = root.uri; destinationExpanded = false }) }
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("فایل‌ها", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { showCategoryDialog = true }) { Text("＋ دسته جدید") }
                    }
                }
                if (filtered.isEmpty()) item {
                    Surface(color = Color(0xFF121A24), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                        Text(if (files.isEmpty()) "هنوز فایلی بارگذاری نشده؛ یک پوشه اضافه کن." else "با این فیلتر چیزی پیدا نشد.",
                            modifier = Modifier.padding(20.dp), color = Color(0xFFAAA8BE))
                    }
                }
                items(filtered, key = { it.key }) { file ->
                    LibraryFileCard(
                        file = file,
                        selected = file.key in selected,
                        favorite = file.key in favoriteKeys,
                        marked = file.key in markedKeys,
                        category = prefs.getString("category:${encodeKey(file.key)}", "بدون دسته") ?: "بدون دسته",
                        categories = categories,
                        tags = prefs.getStringSet("tags:${encodeKey(file.key)}", emptySet()).orEmpty(),
                        onSelect = { checked -> selected = if (checked) selected + file.key else selected - file.key },
                        onFavorite = {
                            val before = file.key in favoriteKeys
                            favoriteKeys = if (before) favoriteKeys - file.key else favoriteKeys + file.key
                            prefs.edit().putStringSet("library_favorites", favoriteKeys).apply()
                            LibraryAudit.record(prefs, "favorite", file.key, before.toString(), (!before).toString())
                        },
                        onMarked = {
                            val before = file.key in markedKeys
                            markedKeys = if (before) markedKeys - file.key else markedKeys + file.key
                            prefs.edit().putStringSet("library_marked", markedKeys).apply()
                            LibraryAudit.record(prefs, "marked", file.key, before.toString(), (!before).toString())
                        },
                        onCategory = { cat ->
                            val before = prefs.getString("category:${encodeKey(file.key)}", "بدون دسته") ?: "بدون دسته"
                            prefs.edit().putString("category:${encodeKey(file.key)}", cat).apply()
                            LibraryAudit.record(prefs, "category", file.key, before, cat)
                            status = "دسته فایل به «$cat» تغییر کرد."
                        },
                        onTags = {
                            tagTarget = file
                            tagText = prefs.getStringSet("tags:${encodeKey(file.key)}", emptySet()).orEmpty().joinToString(", ")
                        }
                    )
                }
                item {
                    Text(status, color = Color(0xFFAAA8BE), style = MaterialTheme.typography.bodySmall)
                    Text("آمار: ${imageFiles.size} تصویر • ${files.count { it.mime.startsWith("video/") }} ویدئو • ${favoriteCount} علاقه‌مندی",
                        color = Color(0xFF777D95), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }

    if (showCategoryDialog) AlertDialog(
        onDismissRequest = { showCategoryDialog = false },
        title = { Text("ساخت دسته جدید") },
        text = { OutlinedTextField(value = newCategory, onValueChange = { newCategory = it }, label = { Text("نام دسته") }, singleLine = true) },
        confirmButton = { TextButton(onClick = {
            val value = newCategory.trim()
            if (value.isNotEmpty()) {
                categories = (categories + value).distinct().sorted()
                prefs.edit().putStringSet("library_categories", categories.toSet()).apply()
                categoryFilter = value
            }
            newCategory = ""; showCategoryDialog = false
        }) { Text("ساخت") } },
        dismissButton = { TextButton(onClick = { showCategoryDialog = false }) { Text("انصراف") } }
    )

    tagTarget?.let { file ->
        AlertDialog(
            onDismissRequest = { tagTarget = null },
            title = { Text("برچسب‌های فایل") },
            text = { Column {
                Text(file.name, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = tagText, onValueChange = { tagText = it }, label = { Text("برچسب‌ها با ویرگول جدا شوند") })
            } },
            confirmButton = { TextButton(onClick = {
                val tags = tagText.split(',', '،').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                val oldTags = prefs.getStringSet("tags:${encodeKey(file.key)}", emptySet()).orEmpty().sorted().joinToString("\n")
                val newTags = tags.sorted().joinToString("\n")
                prefs.edit().putStringSet("tags:${encodeKey(file.key)}", tags).apply()
                if (oldTags != newTags) LibraryAudit.record(prefs, "tags", file.key, oldTags, newTags)
                tagTarget = null
            }) { Text("ذخیره برچسب‌ها") } },
            dismissButton = { TextButton(onClick = { tagTarget = null }) { Text("انصراف") } }
        )
    }


    if (showHistoryDialog) {
        val entries = LibraryAudit.history(prefs)
        AlertDialog(onDismissRequest = { showHistoryDialog = false }, title = { Text("تاریخچه عملیات") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("بازگردانی فعلاً برای علاقه‌مندی، علامت فایل، دسته و برچسب فعال است. عملیات جابه‌جایی یا حذف فایل در این نسخه قابل بازگردانی نیست.")
                if (entries.isEmpty()) Text("هنوز تغییری ثبت نشده است.")
                else entries.take(10).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                Text("حداکثر ۱۰۰ تغییر اخیر نگهداری می‌شود.", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
            } },
            confirmButton = { TextButton(onClick = {
                if (LibraryAudit.undo(prefs)) {
                    favoriteKeys = prefs.getStringSet("library_favorites", emptySet()).orEmpty()
                    markedKeys = prefs.getStringSet("library_marked", emptySet()).orEmpty()
                    status = "آخرین تغییر قابل‌بازگشت برگردانده شد."
                } else status = "تغییری برای بازگردانی وجود ندارد."
                showHistoryDialog = false
            }) { Text("بازگردانی آخرین تغییر") } },
            dismissButton = { TextButton(onClick = { showHistoryDialog = false }) { Text("بستن") } })
    }
    if (showErrorsDialog) {
        val errors = LibraryAudit.errors(prefs)
        AlertDialog(onDismissRequest = { showErrorsDialog = false }, title = { Text("گزارش خطاها") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (errors.isEmpty()) Text("هنوز خطایی ثبت نشده است.")
                else errors.take(12).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                Text("حداکثر ۵۰ خطای اخیر ذخیره می‌شود.", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
            } },
            confirmButton = { TextButton(onClick = { showErrorsDialog = false }) { Text("بستن") } })
    }
    if (showHealthDialog) {
        val readable = roots.count { root -> runCatching {
            DocumentFile.fromTreeUri(context, root.uri)?.canRead() == true
        }.getOrDefault(false) }
        AlertDialog(onDismissRequest = { showHealthDialog = false }, title = { Text("سلامت کتابخانه") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("پوشه‌های ثبت‌شده: ${roots.size}")
                Text("پوشه‌های قابل‌خواندن: $readable")
                Text("فایل‌های اسکن‌شده: ${files.size}")
                Text("حجم فایل‌های اسکن‌شده: ${shortSize(totalBytes)}")
                Text(if (readable == roots.size) "وضعیت دسترسی‌ها مناسب است." else "یک یا چند پوشه در دسترس نیستند؛ مجوز آن‌ها را دوباره بررسی کن.")
            } },
            confirmButton = { TextButton(onClick = { showHealthDialog = false }) { Text("بستن") } })
    }

    if (showConvertDialog) {
        val compressionOnly = outputFormat == "keep"
        AlertDialog(
            onDismissRequest = { showConvertDialog = false },
            title = { Text(if (compressionOnly) "فشرده‌سازی گروهی" else "تبدیل فرمت گروهی") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!compressionOnly) {
                    Text("فرمت خروجی")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("jpg", "png", "webp").forEach { format ->
                            FilterChip(selected = outputFormat == format, onClick = { outputFormat = format }, label = { Text(format.uppercase()) })
                        }                    }
                } else Text("فرمت اصلی حفظ می‌شود؛ کیفیت JPEG و WebP قابل تنظیم است.")
                Text("کیفیت خروجی: $quality٪")
                Slider(value = quality.toFloat(), onValueChange = { quality = it.toInt() }, valueRange = 45f..100f)
                Text("فایل‌های اصلی حذف یا بازنویسی نمی‌شوند. نسخه جدید در مقصد ذخیره می‌شود.", style = MaterialTheme.typography.bodySmall)
            } },
            confirmButton = { TextButton(onClick = {
                showConvertDialog = false
                scope.launch {
                    busy = true
                    try {
                        val chosen = files.filter { it.key in selected }
                        val count = LibraryOps.processImages(context, chosen, destination!!, if (compressionOnly) null else outputFormat, quality)
                        status = "${count} تصویر پردازش و به‌صورت نسخه جدید ذخیره شد."
                        files = LibraryOps.scan(context, roots)
                        selected = emptySet()
                    } catch (e: Exception) { status = e.message ?: "پردازش تصاویر ناموفق بود."; LibraryAudit.logError(prefs, status) }
                    busy = false
                }
            }) { Text("شروع پردازش") } },
            dismissButton = { TextButton(onClick = { showConvertDialog = false }) { Text("لغو") } }
        )
    }
}

@Composable
private fun MetricTile(title: String, value: String, modifier: Modifier = Modifier) {
    Surface(color = Color(0x33202038), shape = RoundedCornerShape(14.dp), modifier = modifier) {
        Column(Modifier.padding(10.dp)) {
            Text(value, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text(title, color = Color(0xFFCBC8E4), style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

@Composable
private fun LibraryFileCard(
    file: LibraryFile, selected: Boolean, favorite: Boolean, marked: Boolean, category: String, categories: List<String>, tags: Set<String>,
    onSelect: (Boolean) -> Unit, onFavorite: () -> Unit, onMarked: () -> Unit, onCategory: (String) -> Unit, onTags: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF121A24))) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = selected, onCheckedChange = onSelect)
                Column(Modifier.weight(1f)) {
                    Text(file.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${file.mime.ifBlank { "نوع نامشخص" }} • ${shortSize(file.size)}", color = Color(0xFFAAA8BE), style = MaterialTheme.typography.labelSmall)
                }
                IconButton(onClick = onFavorite) { Text(if (favorite) "★" else "☆", color = if (favorite) Color(0xFFFFCB70) else Color(0xFFAAA8BE)) }
                IconButton(onClick = onMarked) { Text(if (marked) "⚑" else "⚐", color = if (marked) Color(0xFFC7F36B) else Color(0xFFAAA8BE)) }
            }
            Text(file.path, color = Color(0xFF777D95), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Box {
                    AssistChip(onClick = { menu = true }, label = { Text(category) })
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("بدون دسته") }, onClick = { onCategory("بدون دسته"); menu = false })
                        categories.filter { it != "بدون دسته" }.forEach { cat ->
                            DropdownMenuItem(text = { Text(cat) }, onClick = { onCategory(cat); menu = false })
                        }
                    }
                }
                OutlinedButton(onClick = onTags, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 3.dp)) { Text("برچسب‌ها") }
                if (tags.isNotEmpty()) Text(tags.joinToString("، "), color = Color(0xFFC7F36B), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun encodeKey(value: String): String = Base64.encodeToString(value.toByteArray(), Base64.NO_WRAP or Base64.URL_SAFE)
private fun shortSize(size: Long): String = when {
    size < 1024 -> "$size B"
    size < 1024L * 1024 -> "%.1f MB".format(Locale.US, size / (1024.0 * 1024.0))
    else -> "%.2f GB".format(Locale.US, size / (1024.0 * 1024.0 * 1024.0))
}