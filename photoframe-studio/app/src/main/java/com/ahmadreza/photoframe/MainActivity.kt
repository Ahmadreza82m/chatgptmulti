package com.ahmadreza.photoframe

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import kotlin.math.min

private val Ink = Color(0xFF0A1020)
private val Glass = Color(0xCC1B2940)
private val Mint = Color(0xFF72E6C1)
private val Lilac = Color(0xFFB6A5FF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(10,16,32)
        window.navigationBarColor = android.graphics.Color.rgb(10,16,32)
        setContent { PhotoFrameApp() }
    }
}

@Composable
private fun PhotoFrameApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var reference by remember { mutableStateOf<Uri?>(null) }
    var inputTree by remember { mutableStateOf<Uri?>(null) }
    var outputTree by remember { mutableStateOf<Uri?>(null) }
    var top by remember { mutableFloatStateOf(8f) }
    var bottom by remember { mutableFloatStateOf(8f) }
    var left by remember { mutableFloatStateOf(8f) }
    var right by remember { mutableFloatStateOf(8f) }
    var bleed by remember { mutableFloatStateOf(5f) }
    var progress by remember { mutableFloatStateOf(0f) }
    var status by remember { mutableStateOf("برای شروع، عکس مرجع و پوشه‌ها را انتخاب کن.") }
    var running by remember { mutableStateOf(false) }
    var total by remember { mutableIntStateOf(0) }
    var done by remember { mutableIntStateOf(0) }
    var errors by remember { mutableIntStateOf(0) }

    val refPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { reference = it }
    val inputPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { inputTree = it }
    val outputPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { outputTree = it }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Mint, secondary = Lilac, background = Ink, surface = Glass,
        onPrimary = Ink, onBackground = Color.White, onSurface = Color.White
    )) {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Ink, Color(0xFF182642), Color(0xFF10172A))))) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("PHOTOFRAME", color = Mint, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                        Text("استودیو آماده‌سازی چاپ", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                        Text("اصلاح گروهی کادر، حاشیه و پس‌زمینه", color = Color(0xFFB7C4D9), fontSize = 12.sp)
                    }
                    Box(Modifier.size(48.dp).background(Brush.linearGradient(listOf(Mint, Lilac)), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                        Text("PF", color = Ink, fontWeight = FontWeight.Black, fontSize = 18.sp)
                    }
                }
                GlassCard {
                    Text("۱. ورودی‌ها", color = Mint, fontWeight = FontWeight.Bold)
                    ActionButton("انتخاب عکس مرجع", reference?.lastPathSegment ?: "انتخاب تصویر نمونه") { refPicker.launch("image/*") }
                    ActionButton("انتخاب پوشه عکس‌ها", if (inputTree == null) "انتخاب پوشه ورودی" else "پوشه ورودی انتخاب شد") { inputPicker.launch(null) }
                    ActionButton("انتخاب پوشه خروجی", if (outputTree == null) "انتخاب پوشه مقصد" else "پوشه خروجی انتخاب شد") { outputPicker.launch(null) }
                }
                GlassCard {
                    Text("۲. تنظیم محدوده تصویر", color = Mint, fontWeight = FontWeight.Bold)
                    Text("درصد فضای خالی داخل بوم در هر ضلع؛ این مقادیر روی همه عکس‌ها اعمال می‌شوند.", color = Color(0xFFB7C4D9), fontSize = 12.sp)
                    SideSlider("بالا", top) { top = it }
                    SideSlider("پایین", bottom) { bottom = it }
                    SideSlider("چپ", left) { left = it }
                    SideSlider("راست", right) { right = it }
                    Divider(color = Color(0x334F6688))
                    Text("گسترش پس‌زمینه: ${bleed.toInt()}٪", color = Color.White, fontSize = 13.sp)
                    Slider(value = bleed, onValueChange = { bleed = it }, valueRange = 0f..20f)
                    Text("حاشیه با امتداد آینه‌ای لبه‌ها ساخته می‌شود؛ تصویر اصلی کشیده یا بریده نمی‌شود.", color = Color(0xFFB7C4D9), fontSize = 11.sp)
                }
                GlassCard {
                    Text("۳. پردازش گروهی", color = Mint, fontWeight = FontWeight.Bold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Stat("کل", total.toString())
                        Stat("موفق", done.toString())
                        Stat("خطا", errors.toString())
                    }
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = Mint)
                    Text(status, color = Color(0xFFCAD5E6), fontSize = 12.sp)
                    Button(
                        onClick = {
                            val src = inputTree; val dst = outputTree
                            if (reference == null || src == null || dst == null) {
                                status = "ابتدا عکس مرجع، پوشه ورودی و پوشه خروجی را انتخاب کن."
                            } else {
                                running = true; done = 0; errors = 0; total = 0; progress = 0f
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        processFolder(context, src, dst, left, right, top, bottom, bleed) { count, success, failed, message ->
                                            total = count; done = success; errors = failed
                                            progress = if (count == 0) 0f else (success + failed).toFloat() / count
                                            status = message
                                        }
                                    }
                                    total = result.first; done = result.second; errors = result.third
                                    progress = 1f; running = false
                                    status = "پایان پردازش: ${result.second} موفق، ${result.third} ناموفق."
                                }
                            }
                        },
                        enabled = !running,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink),
                        shape = RoundedCornerShape(16.dp)
                    ) { Text(if (running) "در حال پردازش…" else "شروع پردازش تصاویر", fontWeight = FontWeight.Bold) }
                }
                Text("نسخه اولیه • پردازش محلی روی دستگاه • فایل‌های اصلی بدون تغییر می‌مانند", color = Color(0xFF8292AA), fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun GlassCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(Glass, RoundedCornerShape(24.dp)).border(1.dp, Color(0x337DA5CC), RoundedCornerShape(24.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}
@Composable
private fun ActionButton(title: String, subtitle: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) {
        Column(Modifier.fillMaxWidth()) { Text(title, fontWeight = FontWeight.SemiBold); Text(subtitle, color = Color(0xFF9EADC2), fontSize = 11.sp) }
    }
}
@Composable
private fun SideSlider(label: String, value: Float, change: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Color.White, fontSize = 13.sp)
            Text("${value.toInt()}٪", color = Mint, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Slider(value = value, onValueChange = change, valueRange = 0f..30f)
    }
}
@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Mint, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Color(0xFF9EADC2), fontSize = 11.sp)
    }
}

private fun processFolder(
    context: android.content.Context, input: Uri, output: Uri,
    left: Float, right: Float, top: Float, bottom: Float, bleed: Float,
    update: (Int, Int, Int, String) -> Unit
): Triple<Int, Int, Int> {
    val srcRoot = DocumentFile.fromTreeUri(context, input) ?: return Triple(0,0,1)
    val dstRoot = DocumentFile.fromTreeUri(context, output) ?: return Triple(0,0,1)
    val files = mutableListOf<DocumentFile>()
    fun collect(dir: DocumentFile) {
        dir.listFiles().forEach { f -> if (f.isDirectory) collect(f) else if (f.name?.lowercase()?.let { it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") || it.endsWith(".webp") } == true) files.add(f) }
    }
    collect(srcRoot)
    var success = 0; var failed = 0
    files.forEachIndexed { index, file ->
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(file.uri).use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / sample > 2600 || bounds.outHeight / sample > 3600) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val bitmap = context.contentResolver.openInputStream(file.uri).use { BitmapFactory.decodeStream(it, null, opts) } ?: throw FileNotFoundException()
            val out = fitWithMargins(bitmap, left, right, top, bottom, bleed)
            val name = (file.name ?: "image_$index").substringBeforeLast(".") + "_print.png"
            val target = dstRoot.createFile("image/png", name) ?: throw IllegalStateException("Cannot create output")
            context.contentResolver.openOutputStream(target.uri, "w").use { stream ->
                if (stream == null || !out.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw IllegalStateException("Encode failed")
            }
            bitmap.recycle(); out.recycle(); success++
        } catch (_: Exception) { failed++ }
        update(index + 1, success, failed, "در حال پردازش ${index + 1} از ${files.size}: ${file.name}")
    }
    return Triple(files.size, success, failed)
}

private fun fitWithMargins(source: Bitmap, leftPct: Float, rightPct: Float, topPct: Float, bottomPct: Float, bleedPct: Float): Bitmap {
    val w = 1400; val h = 1960
    val canvasBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(canvasBitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    val bgScale = maxOf(w.toFloat()/source.width, h.toFloat()/source.height)
    val bgW = (source.width * bgScale).toInt(); val bgH = (source.height * bgScale).toInt()
    val bg = Bitmap.createScaledBitmap(source, bgW, bgH, true)
    val cropX = (bgW - w) / 2; val cropY = (bgH - h) / 2
    canvas.drawBitmap(bg, Rect(cropX, cropY, cropX+w, cropY+h), Rect(0,0,w,h), paint)
    bg.recycle()
    val safeLeft = (w * (leftPct + bleedPct) / 100f).coerceAtMost(w * .35f)
    val safeRight = (w * (rightPct + bleedPct) / 100f).coerceAtMost(w * .35f)
    val safeTop = (h * (topPct + bleedPct) / 100f).coerceAtMost(h * .35f)
    val safeBottom = (h * (bottomPct + bleedPct) / 100f).coerceAtMost(h * .35f)
    val area = RectF(safeLeft, safeTop, w-safeRight, h-safeBottom)
    val scale = min(area.width()/source.width, area.height()/source.height)
    val dw = source.width*scale; val dh = source.height*scale
    val dst = RectF(area.centerX()-dw/2, area.centerY()-dh/2, area.centerX()+dw/2, area.centerY()+dh/2)
    canvas.drawBitmap(source, null, dst, paint)
    return canvasBitmap
}
