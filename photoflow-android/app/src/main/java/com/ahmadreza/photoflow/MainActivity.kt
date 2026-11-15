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
import androidx.compose.ui.graphics.Brush
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
        primary = Color(0xFFC7F36B), secondary = Color(0xFF8DE0D0),
        background = Color(0xFF080C12), surface = Color(0xFF121A24),
        surfaceVariant = Color(0xFF1D2936), onBackground = Color(0xFFF4F7F1),
        onSurface = Color(0xFFF4F7F1)
    )) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                Row(
                    Modifier.fillMaxWidth()
                        .background(Brush.linearGradient(listOf(Color(0xFF172536), Color(0xFF102D32), Color(0xFF171A2C))))
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(46.dp).background(Brush.linearGradient(listOf(Color(0xFFC7F36B), Color(0xFF77D9C6))), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                        Text("P", color = Color(0xFF101711), fontWeight = FontWeight.Black, style = MaterialTheme.typography.headlineMedium)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("PHOTO FLOW  /  STUDIO", style = MaterialTheme.typography.labelMedium, color = Color(0xFFC7F36B), fontWeight = FontWeight.ExtraBold)
                        Text("استودیوی هوشمند عکس", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = Color(0xFFF5F7F0))
                        Text("مدیریت، تطبیق و سازمان‌دهی حرفه‌ای", style = MaterialTheme.typography.labelSmall, color = Color(0xFFADBCC7))
                    }
                    Surface(color = Color(0xFF263B3A), shape = RoundedCornerShape(50)) {
                        Text("PRO", modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp), color = Color(0xFFC7F36B), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }
            },
            bottomBar = {
                NavigationBar(containerColor = Color(0xFF0E151E), contentColor = Color(0xFFC7F36B)) {
                    NavigationBarItem(selected = page == Page.PROJECT, onClick = { page = Page.PROJECT }, icon = { Text("⌂", style = MaterialTheme.typography.titleLarge) }, label = { Text("خانه") })
                    NavigationBarItem(selected = page == Page.GROUPS, onClick = { page = Page.GROUPS }, icon = { Text("◈", style = MaterialTheme.typography.titleLarge) }, label = { Text("تطبیق") })
                    NavigationBarItem(selected = page == Page.EXPORT, onClick = { page = Page.EXPORT }, icon = { Text("⇧", style = MaterialTheme.typography.titleLarge) }, label = { Text("خروجی") })
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
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent)) {
                Box(Modifier.background(Brush.linearGradient(listOf(Color(0xFF273A49), Color(0xFF183E40), Color(0xFF292344)))).padding(20.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(color = Color(0x33C7F36B), shape = RoundedCornerShape(50)) {
                                Text("YOUR CREATIVE WORKSPACE", modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                                    color = Color(0xFFC7F36B), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.weight(1f))
                            Text("✦", color = Color(0xFFC7F36B), style = MaterialTheme.typography.headlineMedium)
                        }
                        Text("تمام عکس‌هایت،\nدر یک جریان هوشمند.", style = MaterialTheme.typography.headlineMedium,
                            color = Color.White, fontWeight = FontWeight.ExtraBold)
                        Text("از پیدا کردن نسخه‌های مشابه تا ساخت آرشیو مرتب؛ همه‌چیز در یک فضای کاری امن و یکپارچه.",
                            color = Color(0xFFD1DFE1), style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { context.startActivity(Intent(context, FileToolsActivity::class.java)) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC7F36B), contentColor = Color(0xFF101711)),
                                shape = RoundedCornerShape(14.dp)) { Text("ورود به ابزارها  ↗", fontWeight = FontWeight.Bold) }
                            OutlinedButton(onClick = onScan, enabled = !busy,
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF6B8989)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                shape = RoundedCornerShape(14.dp)) { Text(if (busy) "در حال اسکن…" else "اسکن مجدد") }
                        }
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.fillMaxWidth()) {
                DashboardMetric("عکس‌های اصلی", originalCount.toString(), "منبع", Modifier.weight(1f))
                DashboardMetric("نسخه‌های خروجی", outputCount.toString(), "برای تطبیق", Modifier.weight(1f))
                DashboardMetric("پوشه‌های فعال", listOfNotNull(originalsTree, outputsTree, destinationTree).distinct().size.toString(), "فضای کاری", Modifier.weight(1f))
            }
        }
        item { SectionHeading("ابزارهای حرفه‌ای", "WORKSPACE TOOLS", "همه ابزارها در دسترس و آماده استفاده") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                ToolLaunchCard("مدیریت فایل", "مرتب‌سازی، تغییر نام، کپی و انتقال", "▤",
                    Brush.linearGradient(listOf(Color(0xFF263B4C), Color(0xFF1B2938))), Modifier.weight(1f)) {
                    context.startActivity(Intent(context, FileToolsActivity::class.java))
                }
                ToolLaunchCard("کتابخانه هوشمند", "جست‌وجو، برچسب، دسته و پردازش", "◫",
                    Brush.linearGradient(listOf(Color(0xFF293A37), Color(0xFF1D302F))), Modifier.weight(1f)) {
                    context.startActivity(Intent(context, LibraryToolsActivity::class.java))
                }
            }
        }
        item { SectionHeading("اتصال منابع", "YOUR LIBRARIES", "پوشه‌های منبع و مقصد پروژه را مدیریت کن") }
        item { FolderCard("عکس‌های اصلی", originalsTree, originalCount, "منبع تصاویر اولیه") { onPick(PickTarget.ORIGINALS) } }
        item { FolderCard("خروجی‌های Gemini", outputsTree, outputCount, "نسخه‌های بهبود‌یافته و خروجی‌ها") { onPick(PickTarget.OUTPUTS) } }
        item { FolderCard("مقصد فایل‌های مرتب‌شده", destinationTree, null, "محل ذخیره خروجی و فایل ZIP") { onPick(PickTarget.DESTINATION) } }
        item {
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF121B25))) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("جریان کاری پیشنهادی", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text("3 مرحله", color = Color(0xFFC7F36B), style = MaterialTheme.typography.labelSmall)
                    }
                    WorkflowStep("01", "اتصال پوشه‌ها", "منبع، خروجی و مقصد را مشخص کن", originalsTree != null && outputsTree != null)
                    WorkflowStep("02", "تطبیق و بازبینی", "نسخه‌های مشابه را گروه‌بندی و تأیید کن", false)
                    WorkflowStep("03", "ساخت آرشیو", "خروجی مرتب و قابل اشتراک بساز", false)
                }
            }
        }
        item {
            Surface(color = Color(0xFF19252C), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("◈", color = Color(0xFF8DE0D0), style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("حریم خصوصی در اولویت", color = Color(0xFF8DE0D0), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                        Text("فایل‌های اصلی بدون اجازه تو حذف یا بازنویسی نمی‌شوند؛ خروجی‌ها به‌صورت کپی ساخته می‌شوند.",
                            color = Color(0xFFB9C7CB), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item { StatusPanel(status) }
    }
}

@Composable
private fun DashboardMetric(title: String, value: String, hint: String, modifier: Modifier = Modifier) {
    Card(modifier, shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF141E29))) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value, color = Color(0xFFC7F36B), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
            Text(title, color = Color(0xFFF0F3EE), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(hint, color = Color(0xFF82939F), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun SectionHeading(title: String, eyebrow: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(eyebrow, color = Color(0xFF9BB0B7), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        Text(title, color = Color(0xFFF4F7F1), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
        Text(subtitle, color = Color(0xFF8E9DA8), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ToolLaunchCard(title: String, subtitle: String, glyph: String, brush: Brush, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(modifier.clickable(onClick = onClick), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color.Transparent)) {
        Column(Modifier.fillMaxWidth().background(brush).padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Box(Modifier.size(42.dp).background(Color(0x22FFFFFF), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                Text(glyph, color = Color(0xFFC7F36B), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, color = Color(0xFFC2D0D3), style = MaterialTheme.typography.bodySmall, minLines = 2)
            Text("باز کردن  ↗", color = Color(0xFFC7F36B), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun WorkflowStep(number: String, title: String, subtitle: String, complete: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        Surface(color = if (complete) Color(0xFFC7F36B) else Color(0xFF263543), shape = RoundedCornerShape(11.dp)) {
            Text(number, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                color = if (complete) Color(0xFF101711) else Color(0xFF9DB0B8), fontWeight = FontWeight.ExtraBold)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = Color(0xFFEAF0EB), fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Color(0xFF8799A4), style = MaterialTheme.typography.bodySmall)
        }
        Text(if (complete) "✓" else "○", color = if (complete) Color(0xFFC7F36B) else Color(0xFF667783))
    }
}

@Composable
private fun FolderCard(title: String, uri: Uri?, count: Int?, subtitle: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = Color(0xFF141E29)),
        shape = RoundedCornerShape(19.dp)) {
        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).background(Color(0xFF263A43), RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                Text("▱", color = Color(0xFF8DE0D0), style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, color = Color(0xFFF0F3EE), fontWeight = FontWeight.SemiBold)
                Text(if (uri == null) subtitle else uri.lastPathSegment?.substringAfterLast(':') ?: "پوشه متصل",
                    color = Color(0xFF8D9EAA), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (count != null) Text("$count تصویر شناسایی شد", color = Color(0xFFC7F36B), style = MaterialTheme.typography.labelSmall)
            }
            Text(if (uri == null) "اتصال +" else "تغییر", color = Color(0xFFC7F36B), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
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
                Text(when {
                    level == MatchLevel.HIGH -> "شباهت زیاد · فاصله ${match.score?.distance}"
                    level == MatchLevel.REVIEW -> "نیازمند بررسی دستی · فاصله ${match.score?.distance}"
                    match.score?.distance != null && match.score.distance <= 18 &&
                        (match.score.margin ?: 99) < 3 -> "چند عکس تقریباً مشابه‌اند؛ انتخاب دستی لازم است"
                    else -> "شباهت کافی پیدا نشد؛ انتخاب دستی لازم است"
                }, color = tint, style = MaterialTheme.typography.labelSmall)
                if (match.score?.runnerUpDistance != null) {
                    Text("فاصله گزینه بعدی: ${match.score.runnerUpDistance} · اختلاف: ${match.score.margin}",
                        color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
                }
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
