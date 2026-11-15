package com.ahmadreza.photoflow

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Page { PROJECT, GROUPS, EXPORT }
private enum class PickTarget { ORIGINALS, OUTPUTS, DESTINATION }
private data class SuggestedMatch(val photo: PhotoFile, val originalUri: String?, val score: MatchScore?)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PhotoFlowApp() }
    }
}

@Composable
private fun PhotoFlowApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("photoflow-project", 0) }
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf(Page.PROJECT) }
    var pickTarget by remember { mutableStateOf(PickTarget.ORIGINALS) }
    var originalsTree by remember { mutableStateOf(prefs.getString("originals_tree", null)?.let(Uri::parse)) }
    var outputsTree by remember { mutableStateOf(prefs.getString("outputs_tree", null)?.let(Uri::parse)) }
    var destinationTree by remember { mutableStateOf(prefs.getString("destination_tree", null)?.let(Uri::parse)) }
    var originals by remember { mutableStateOf<List<PhotoFile>>(emptyList()) }
    var outputs by remember { mutableStateOf<List<PhotoFile>>(emptyList()) }
    var matches by remember { mutableStateOf<List<SuggestedMatch>>(emptyList()) }
    var separateFolders by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("برای شروع، پوشه عکس‌های اصلی و پوشه خروجی Gemini را انتخاب کن.") }
    var createdFiles by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var lastOutputFolder by remember { mutableStateOf<DocumentFile?>(null) }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (_: SecurityException) { }
            when (pickTarget) {
                PickTarget.ORIGINALS -> {
                    originalsTree = uri
                    prefs.edit().putString("originals_tree", uri.toString()).apply()
                    scope.launch { originals = PhotoFiles.scan(context, uri); status = "پوشه عکس‌های اصلی بارگذاری شد." }
                }
                PickTarget.OUTPUTS -> {
                    outputsTree = uri
                    prefs.edit().putString("outputs_tree", uri.toString()).apply()
                    scope.launch { outputs = PhotoFiles.scan(context, uri); matches = outputs.map { SuggestedMatch(it, null, null) }; status = "پوشه خروجی‌های Gemini بارگذاری شد." }
                }
                PickTarget.DESTINATION -> {
                    destinationTree = uri
                    prefs.edit().putString("destination_tree", uri.toString()).apply()
                    status = "پوشه مقصد انتخاب شد."
                }
            }
        }
    }

    LaunchedEffect(originalsTree, outputsTree) {
        originalsTree?.let { originals = PhotoFiles.scan(context, it) }
        outputsTree?.let { val scanned = PhotoFiles.scan(context, it); outputs = scanned; if (matches.isEmpty()) matches = scanned.map { photo -> SuggestedMatch(photo, null, null) } }
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Color(0xFFB8A1FF), secondary = Color(0xFF8ED8CB),
        background = Color(0xFF101116), surface = Color(0xFF1A1C24),
        surfaceVariant = Color(0xFF252833), onBackground = Color(0xFFF4F2FA),
        onSurface = Color(0xFFF4F2FA)
    )) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                Column(Modifier.fillMaxWidth().background(Color(0xFF171820)).padding(horizontal = 20.dp, vertical = 16.dp)) {
                    Text("PHOTOFLOW", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text("مدیریت هوشمند عکس‌ها", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("تطبیق، گروه‌بندی و آماده‌سازی خروجی", style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
                }
            },
            bottomBar = {
                NavigationBar(containerColor = Color(0xFF171820)) {
                    NavigationBarItem(selected = page == Page.PROJECT, onClick = { page = Page.PROJECT }, icon = { Text("▦") }, label = { Text("پروژه") })
                    NavigationBarItem(selected = page == Page.GROUPS, onClick = { page = Page.GROUPS }, icon = { Text("◎") }, label = { Text("گروه‌بندی") })
                    NavigationBarItem(selected = page == Page.EXPORT, onClick = { page = Page.EXPORT }, icon = { Text("⇧") }, label = { Text("خروجی") })
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                Spacer(Modifier.height(12.dp))
                when (page) {
                    Page.PROJECT -> ProjectPage(originalsTree, outputsTree, destinationTree, originals.size, outputs.size, status, busy,
                        onPick = { target -> pickTarget = target; folderPicker.launch(null) },
                        onScan = {
                            scope.launch {
                                busy = true
                                try {
                                    originals = originalsTree?.let { PhotoFiles.scan(context, it) }.orEmpty()
                                    outputs = outputsTree?.let { PhotoFiles.scan(context, it) }.orEmpty()
                                    status = "اسکن تمام شد."
                                } catch (e: Exception) { status = e.message ?: "خطا در اسکن پوشه‌ها" }
                                busy = false
                            }
                        })
                    Page.GROUPS -> GroupsPage(originals, outputs, matches, busy, status,
                        onRunAuto = {
                            scope.launch {
                                if (originals.isEmpty() || outputs.isEmpty()) {
                                    status = "ابتدا پوشه عکس‌های اصلی و خروجی Gemini را انتخاب کن."
                                } else {
                                    busy = true
                                    status = "در حال مقایسه تصاویر…"
                                    val result = ImageMatcher.matchAll(context.contentResolver, originals, outputs).map { auto ->
                                        SuggestedMatch(auto.output, auto.original?.uri?.toString(), auto.score)
                                    }
                                    matches = result
                                    status = "بررسی تمام شد؛ موارد مشکوک را در همین صفحه اصلاح کن."
                                    busy = false
                                }
                            }
                        },
                        onAssign = { photo, originalId ->
                            matches = matches.map { if (it.photo.uri == photo.uri) it.copy(originalUri = originalId) else it }
                        })
                    Page.EXPORT -> ExportPage(originals, outputs, matches, destinationTree, separateFolders, busy,
                        createdFiles.size, status,
                        onPickDestination = { pickTarget = PickTarget.DESTINATION; folderPicker.launch(null) },
                        onMode = { separateFolders = it },
                        onBuild = {
                            scope.launch {
                                if (originals.isEmpty()) { status = "پوشه عکس‌های اصلی خالی است."; return@launch }
                                if (destinationTree == null) { status = "ابتدا پوشه مقصد را انتخاب کن."; return@launch }
                                if (outputs.isNotEmpty() && matches.isEmpty()) { status = "ابتدا تشخیص خودکار را اجرا کن و گروه‌ها را بررسی کن."; return@launch }
                                busy = true
                                try {
                                    val mapping = matches.filter { it.originalUri != null }.groupBy({ it.originalUri!! }, { it.photo })
                                    val bundle = PhotoFiles.createOutput(context, destinationTree!!, originals, mapping, separateFolders)
                                    createdFiles = bundle.files
                                    lastOutputFolder = bundle.folder
                                    status = "خروجی ساخته شد؛ فایل‌های منبع تغییر نکردند."
                                } catch (e: Exception) { status = e.message ?: "ساخت خروجی ناموفق بود" }
                                busy = false
                            }
                        },
                        onZip = {
                            scope.launch {
                                val folder = lastOutputFolder
                                if (folder == null || destinationTree == null) { status = "ابتدا خروجی را بساز."; return@launch }
                                busy = true
                                try {
                                    val uri = PhotoFiles.createZip(context, folder, destinationTree!!)
                                    createdFiles = listOf(uri)
                                    status = "فایل ZIP ساخته شد."
                                } catch (e: Exception) { status = e.message ?: "ساخت ZIP ناموفق بود" }
                                busy = false
                            }
                        },
                        onShare = {
                            if (createdFiles.isEmpty()) status = "هنوز خروجی‌ای ساخته نشده است."
                            else shareUris(context, createdFiles)
                        })
                }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun ProjectPage(originalsTree: Uri?, outputsTree: Uri?, destinationTree: Uri?, originalCount: Int, outputCount: Int,
    status: String, busy: Boolean, onPick: (PickTarget) -> Unit, onScan: () -> Unit) {
    val context = LocalContext.current

    Text("فضای کاری", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Button(
        onClick = { context.startActivity(Intent(context, FileToolsActivity::class.java)) },
        modifier = Modifier.fillMaxWidth()
    ) { Text("مدیریت پیشرفته فایل‌ها · قابلیت‌های ۱ تا ۶") }

    Spacer(Modifier.height(10.dp))
    FolderCard("عکس‌های اصلی", originalsTree, originalCount, "پوشه‌ای که عکس‌های اولیه داخل آن هستند") { onPick(PickTarget.ORIGINALS) }
    FolderCard("خروجی‌های Gemini", outputsTree, outputCount, "پوشه‌ای که نسخه‌های باکیفیت در آن ذخیره شده‌اند") { onPick(PickTarget.OUTPUTS) }
    FolderCard("مقصد فایل‌های مرتب‌شده", destinationTree, null, "محل ساخت کپی‌های مرتب‌شده و ZIP") { onPick(PickTarget.DESTINATION) }
    Spacer(Modifier.height(12.dp))
    OutlinedButton(onClick = onScan, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "در حال بررسی…" else "بازخوانی پوشه‌ها") }
    StatusPanel(status)
    Text("فقط تصاویر داخل پوشه‌های انتخاب‌شده بررسی می‌شوند. فایل‌های اصلی جابه‌جا یا حذف نمی‌شوند.", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun FolderCard(title: String, uri: Uri?, count: Int?, subtitle: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 5.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(if (uri == null) subtitle else uri.lastPathSegment?.substringAfterLast(':') ?: "پوشه انتخاب شده",
                    color = Color.LightGray, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (count != null) Text("$count تصویر شناسایی شد", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelMedium)
            }
            Text(if (uri == null) "انتخاب +" else "تغییر", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun GroupsPage(originals: List<PhotoFile>, outputs: List<PhotoFile>, matches: List<SuggestedMatch>,
    busy: Boolean, status: String, onRunAuto: () -> Unit, onAssign: (PhotoFile, String?) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("تطبیق و گروه‌بندی", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("هر خروجی فقط به یک عکس اصلی وصل می‌شود.", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = onRunAuto, enabled = !busy && originals.isNotEmpty() && outputs.isNotEmpty()) { Text(if (busy) "…" else "تشخیص خودکار") }
    }
    Spacer(Modifier.height(8.dp))
    if (matches.isNotEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            StatPill("مطمئن ${matches.count { it.score?.level == MatchLevel.HIGH }}", Color(0xFF75D4A6))
            StatPill("بررسی ${matches.count { it.score?.level == MatchLevel.REVIEW }}", Color(0xFFFFC56D))
            StatPill("نامشخص ${matches.count { it.originalUri == null }}", Color(0xFFFF8585))
        }
    }
    if (originals.isEmpty() || outputs.isEmpty()) EmptyPanel("برای شروع، پوشه‌های عکس اصلی و خروجی Gemini را در صفحه پروژه انتخاب کن.")
    else if (matches.isEmpty()) EmptyPanel("هنوز تطبیقی انجام نشده است. دکمه تشخیص خودکار را بزن.")
    else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
        items(matches, key = { it.photo.uri.toString() }) { match -> MatchCard(match, originals, onAssign) }
    }
    StatusPanel(status)
}

@Composable
private fun MatchCard(match: SuggestedMatch, originals: List<PhotoFile>, onAssign: (PhotoFile, String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val chosen = originals.firstOrNull { it.uri.toString() == match.originalUri }
    val level = match.score?.level
    val tint = when (level) {
        MatchLevel.HIGH -> Color(0xFF75D4A6)
        MatchLevel.REVIEW -> Color(0xFFFFC56D)
        else -> Color(0xFFFF8585)
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PhotoThumb(match.photo.uri, Modifier.size(76.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(match.photo.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(when (level) {
                    MatchLevel.HIGH -> "شباهت زیاد · فاصله ${match.score?.distance}"
                    MatchLevel.REVIEW -> "نیازمند بررسی · فاصله ${match.score?.distance}"
                    else -> "تطبیق خودکار پیدا نشد"
                }, color = tint, style = MaterialTheme.typography.labelSmall)
                Box {
                    OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(chosen?.name ?: "انتخاب عکس اصلی برای این خروجی", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        DropdownMenuItem(text = { Text("بدون گروه") }, onClick = { onAssign(match.photo, null); expanded = false })
                        originals.forEach { original ->
                            DropdownMenuItem(text = { Text(original.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                onClick = { onAssign(match.photo, original.uri.toString()); expanded = false })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PhotoThumb(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = uri) {
        value = withContext(Dispatchers.IO) {
            try { context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } }
            catch (_: Exception) { null }
        }
    }
    if (bitmap != null) {
        Image(bitmap!!.asImageBitmap(), contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
    } else {
        Box(modifier.background(Color(0xFF30333D)), contentAlignment = Alignment.Center) { Text("عکس", color = Color.LightGray) }
    }
}

@Composable
private fun ExportPage(originals: List<PhotoFile>, outputs: List<PhotoFile>, matches: List<SuggestedMatch>, destination: Uri?,
    separateFolders: Boolean, busy: Boolean, fileCount: Int, status: String, onPickDestination: () -> Unit,
    onMode: (Boolean) -> Unit, onBuild: () -> Unit, onZip: () -> Unit, onShare: () -> Unit) {
    Text("ساخت و ارسال خروجی", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(15.dp)) {
            Text("ساختار پوشه خروجی", fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = !separateFolders, onClick = { onMode(false) })
                Column(Modifier.weight(1f)) {
                    Text("همه در یک پوشه")
                    Text("عکس اصلی و نسخه‌هایش پشت سر هم", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = separateFolders, onClick = { onMode(true) })
                Column(Modifier.weight(1f)) {
                    Text("پوشه جدا برای هر عکس")
                    Text("هر عکس اصلی همراه تمام نسخه‌هایش", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = onPickDestination, modifier = Modifier.fillMaxWidth()) {
        Text(if (destination == null) "انتخاب پوشه مقصد" else "تغییر پوشه مقصد")
    }
    Text("عکس اصلی: ${originals.size}  •  خروجی واردشده: ${outputs.size}  •  فایل آماده: $fileCount",
        color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(8.dp))
    Button(onClick = onBuild, enabled = !busy && originals.isNotEmpty() && destination != null, modifier = Modifier.fillMaxWidth()) {
        Text(if (busy) "در حال ساخت…" else "ساخت کپی‌های مرتب‌شده")
    }
    OutlinedButton(onClick = onZip, enabled = !busy && fileCount > 0, modifier = Modifier.fillMaxWidth()) { Text("ساخت فایل ZIP از خروجی") }
    OutlinedButton(onClick = onShare, enabled = fileCount > 0, modifier = Modifier.fillMaxWidth()) { Text("اشتراک‌گذاری فایل‌ها برای تلگرام") }
    StatusPanel(status)
    Text("تلگرام پس از باز شدن صفحه اشتراک‌گذاری، انتخاب چت و تأیید ارسال را از خودت می‌خواهد. برای مجموعه‌های بزرگ ZIP ساده‌تر است.",
        color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun StatPill(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.14f), shape = RoundedCornerShape(50)) {
        Text(text, color = color, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
    }
}
@Composable
private fun EmptyPanel(text: String) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Text(text, modifier = Modifier.padding(20.dp), color = Color.LightGray)
    }
}
@Composable
private fun StatusPanel(text: String) {
    Surface(color = Color(0xFF242630), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(text, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
    }
}

private fun shareUris(context: android.content.Context, uris: List<Uri>) {
    val intent = if (uris.size == 1) Intent(Intent.ACTION_SEND).apply {
        type = context.contentResolver.getType(uris.first()) ?: "*/*"
        putExtra(Intent.EXTRA_STREAM, uris.first())
    } else Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "*/*"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Parcelable>(uris))
    }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    val clip = ClipData.newUri(context.contentResolver, "PhotoFlow files", uris.first())
    uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
    intent.clipData = clip
    context.startActivity(Intent.createChooser(intent, "ارسال فایل‌ها"))
}
