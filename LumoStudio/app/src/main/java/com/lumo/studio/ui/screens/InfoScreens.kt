package com.lumo.studio.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumo.studio.engine.Stats
import com.lumo.studio.model.*
import com.lumo.studio.ui.components.*
import com.lumo.studio.ui.theme.LocalLumo
import com.lumo.studio.viewmodel.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun fmtTime(t: Long) = SimpleDateFormat("MM/dd HH:mm", Locale.US).format(Date(t))

// ====================== مرکز ابزارها ======================

@Composable
fun HubScreen(state: UiState, vm: StudioViewModel) {
    val items = listOf(
        Triple("کتابخانه‌ی فایل‌ها", "فیلتر، علامت، دسته‌بندی، تغییر نام، انتقال، کپی", Screen.LIBRARY),
        Triple("پوشه‌ها و علاقه‌مندی‌ها", "چندین پوشه، اسکن هوشمند", Screen.FOLDERS),
        Triple("مقایسه‌ی دو پوشه", "مشترک‌ها و تفاوت‌ها", Screen.FOLDER_COMPARE),
        Triple("فایل‌های تکراری", "تشخیص بر اساس محتوا", Screen.DUPLICATES),
        Triple("تبدیل فرمت و فشرده‌سازی", "JPG / PNG / WebP", Screen.PROCESS),
        Triple("داشبورد آماری", "وضعیت کلی پروژه", Screen.DASHBOARD),
        Triple("بررسی سلامت پروژه", "پیش از خروجی نهایی", Screen.HEALTH),
        Triple("گزارش خطاها", "علت و راه‌حل هر مشکل", Screen.ERRORS),
        Triple("تاریخچه و بازگشت", "ثبت و برگرداندن عملیات", Screen.HISTORY),
        Triple("خروجی‌های قبلی", "ZIP مجدد، اشتراک، حذف", Screen.EXPORTS),
        Triple("پروژه‌ها", "چند پروژه‌ی مستقل", Screen.PROJECTS),
        Triple("پشتیبان‌گیری و انتقال", "پشتیبان و بازیابی روی گوشی دیگر", Screen.BACKUP),
        Triple("مدیریت فضای ذخیره‌سازی", "مصرف حافظه و فایل‌های حجیم", Screen.STORAGE),
    )
    Page("ابزارها", { vm.back() }) {
        items.forEach { (t, d, s) ->
            GlassCard(Modifier.fillMaxWidth().clickable { vm.navigate(s) }) {
                Text(t, color = LocalLumo.current.fg, style = MaterialTheme.typography.titleSmall)
                Dim(d)
            }
        }
    }
}

// ====================== داشبورد (۱۷) ======================

@Composable
fun DashboardScreen(state: UiState, vm: StudioViewModel) {
    val c = LocalLumo.current
    val s = remember(state.project) { Stats.dash(state.project) }
    Page("گزارش پروژه: ${state.project.name}", { vm.back() }) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("عکس اصلی", s.originals.toString(), Modifier.weight(1f))
            StatCard("خروجی", s.outputs.toString(), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("گروه تکمیل‌شده", s.groupsDone.toString(), Modifier.weight(1f))
            StatCard("خروجی بدون گروه", s.unsorted.toString(), Modifier.weight(1f))
        }
        SectionCard("وضعیت تکمیل پروژه") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.size(110.dp), contentAlignment = Alignment.Center) {
                    val g1 = c.g1; val g2 = c.g2; val track = c.line2
                    Canvas(Modifier.fillMaxSize()) {
                        val stroke = Stroke(width = 16f, cap = StrokeCap.Round)
                        drawArc(track, 0f, 360f, false, topLeft = Offset(12f, 12f), size = Size(size.width - 24f, size.height - 24f), style = stroke)
                        drawArc(Brush.sweepGradient(listOf(g1, g2)), -90f, 360f * s.completion / 100f, false,
                            topLeft = Offset(12f, 12f), size = Size(size.width - 24f, size.height - 24f), style = stroke)
                    }
                    Text("${s.completion}٪", color = c.fg, style = MaterialTheme.typography.titleLarge)
                }
                Column {
                    Dim("گروه کامل = حداقل یک نسخه و بدون مورد بررسی‌نشده")
                    Dim("${s.groupsDone} از ${s.groups} گروه")
                }
            }
        }
        SectionCard("جزئیات") {
            InfoLine("کل فایل‌ها", s.total.toString())
            InfoLine("فضای اشغال‌شده", Stats.fmtBytes(s.bytes))
            InfoLine("فایل‌های پردازش‌شده", s.processed.toString())
            InfoLine("نیازمند بررسی", s.pending.toString())
            InfoLine("فایل‌های ناپدیدشده", s.missing.toString())
            InfoLine("خطاهای ثبت‌شده", state.project.errors.size.toString())
        }
        SectionCard("نمودار گروه‌ها") {
            val tot = s.groups.coerceAtLeast(1)
            Bar("تکمیل‌شده", s.groupsDone, tot, c.ok)
            Bar("بدون نسخه", state.project.groups().count { it.versions.isEmpty() }, tot, c.warn)
            Bar("نیازمند بررسی", state.project.groups().count { it.pending > 0 }, tot, c.bad)
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier) {
    val c = LocalLumo.current
    GlassCard(modifier) {
        Text(value, color = c.fg, style = MaterialTheme.typography.headlineMedium)
        Dim(label)
    }
}

@Composable
private fun Bar(label: String, v: Int, total: Int, color: Color) {
    val c = LocalLumo.current
    Column {
        Row { Text(label, color = c.fg2, modifier = Modifier.weight(1f), fontSize = 12.sp); Text(v.toString(), color = c.fg, fontSize = 12.sp) }
        Box(Modifier.fillMaxWidth().height(10.dp).padding(top = 2.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                drawRoundRect(c.line, size = size, cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f))
                drawRoundRect(color, size = Size(size.width * v / total, size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f))
            }
        }
    }
}

// ====================== گزارش خطاها (۱۸) ======================

@Composable
fun ErrorsScreen(state: UiState, vm: StudioViewModel) {
    val c = LocalLumo.current
    val errs = state.project.errors
    Page("گزارش خطاها", { vm.back() }, actions = {
        if (errs.isNotEmpty()) TextButton(onClick = { vm.clearErrors() }) { Text("پاک کردن همه") }
    }) {
        if (errs.isEmpty()) Dim("خطایی ثبت نشده است ✓")
        errs.forEach { e ->
            GlassCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(e.kind.fa, color = c.bad, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Dim(fmtTime(e.time))
                }
                Text(e.subject, color = c.fg, fontSize = 13.sp, maxLines = 2)
                Text("چه شد: ${e.message}", color = c.fg2, fontSize = 12.sp)
                Text("چه کنم: ${e.advice}", color = c.ok, fontSize = 12.sp)
                TextButton(onClick = { vm.dismissError(e.id) }) { Text("رفع شد") }
            }
        }
    }
}

// ====================== سلامت پروژه (۱۹) ======================

@Composable
fun HealthScreen(state: UiState, vm: StudioViewModel) {
    val c = LocalLumo.current
    LaunchedEffect(Unit) { vm.runHealth() }
    val items = state.health
    Page("بررسی سلامت پروژه", { vm.back() }, actions = { TextButton(onClick = { vm.runHealth() }) { Text("بررسی دوباره") } }) {
        if (items.isEmpty()) Dim("در حال بررسی…")
        items.forEach { it ->
            val col = when (it.level) { 0 -> c.ok; 1 -> c.warn; else -> c.bad }
            GlassCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(when (it.level) { 0 -> "✔"; 1 -> "▲"; else -> "✖" }, color = col, modifier = Modifier.padding(end = 8.dp))
                    Text(it.title, color = c.fg, style = MaterialTheme.typography.titleSmall)
                }
                Dim(it.detail)
            }
        }
        if (items.isNotEmpty()) {
            val bad = items.count { it.level == 2 }
            val warn = items.count { it.level == 1 }
            Text(
                when {
                    bad > 0 -> "$bad مورد مهم باید قبل از خروجی نهایی حل شود."
                    warn > 0 -> "$warn هشدار دارد؛ می‌توانی ادامه بدهی ولی بهتر است نگاهی بیندازی."
                    else -> "همه‌چیز آماده‌ی خروجی نهایی است."
                },
                color = if (bad > 0) c.bad else if (warn > 0) c.warn else c.ok,
            )
            Button(onClick = { vm.navigate(Screen.EXPORT) }, enabled = bad == 0, modifier = Modifier.fillMaxWidth()) { Text("ادامه به خروجی") }
        }
    }
}

// ====================== تاریخچه و بازگشت (۲۱، ۲۲) ======================

@Composable
fun HistoryScreen(state: UiState, vm: StudioViewModel) {
    val c = LocalLumo.current
    val hist = state.project.history
    val latestUndoable = hist.firstOrNull { it.undoable && !it.undone }?.id
    var open by remember { mutableStateOf<String?>(null) }
    Page("تاریخچه‌ی عملیات", { vm.back() }, actions = {
        TextButton(onClick = { vm.undoLast() }, enabled = latestUndoable != null) { Text("بازگشت آخرین") }
    }) {
        if (hist.isEmpty()) Dim("هنوز عملیاتی ثبت نشده است.")
        hist.forEach { e ->
            GlassCard(Modifier.fillMaxWidth().clickable { open = if (open == e.id) null else e.id }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${e.type.fa} · ${e.title}", color = if (e.undone) c.fg3 else c.fg, style = MaterialTheme.typography.titleSmall)
                        Dim(fmtTime(e.time) + if (e.undone) " · برگردانده شد" else "")
                    }
                    if (e.id == latestUndoable) OutlinedButton(onClick = { vm.undoEntry(e.id) }) { Text("بازگشت") }
                }
                if (open == e.id) e.details.forEach { Dim(it) }
            }
        }
        Dim("فقط آخرین عملیات برگشت‌پذیر قابل بازگشت است تا وضعیت فایل‌ها با پروژه هماهنگ بماند.")
    }
}

// ====================== مدیریت فضا (۳۹) ======================

@Composable
fun StorageScreen(state: UiState, vm: StudioViewModel) {
    val c = LocalLumo.current
    var info by remember { mutableStateOf<StorageInfo?>(null) }
    LaunchedEffect(state.project.photos, state.project.exports) { info = vm.storageInfo() }
    var checked by remember { mutableStateOf(setOf<String>()) }
    var view by remember { mutableStateOf<Photo?>(null) }
    val big = remember(state.project) { state.project.livePhotos().filter { it.size > 0 }.sortedByDescending { it.size }.take(30) }
    Page("مدیریت فضای ذخیره‌سازی", { vm.back() }) {
        info?.let { i ->
            SectionCard("مصرف حافظه") {
                InfoLine("عکس‌های اصلی", Stats.fmtBytes(i.originals))
                InfoLine("خروجی‌ها و نسخه‌ها", Stats.fmtBytes(i.outputs))
                InfoLine("بسته‌های ZIP", Stats.fmtBytes(i.zips))
                InfoLine("اطلاعات پروژه", Stats.fmtBytes(i.projectData))
                InfoLine("فضای آزاد دستگاه", "${Stats.fmtBytes(i.free)} از ${Stats.fmtBytes(i.totalSpace)}")
            }
        }
        SectionCard("فایل‌های حجیم") {
            Dim("روی نام بزن تا پیش از حذف بررسی کنی؛ تیک بزن تا به سطل Lumo (قابل بازگشت) برود.")
            big.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = p.id in checked, onCheckedChange = { checked = if (it) checked + p.id else checked - p.id })
                    Text(p.name, color = c.fg, maxLines = 1, modifier = Modifier.weight(1f).clickable { view = p }, fontSize = 13.sp)
                    Text(Stats.fmtBytes(p.size), color = c.fg2, fontSize = 12.sp)
                }
            }
            Button(onClick = { vm.trashPhotos(checked); checked = emptySet() }, enabled = checked.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                Text("انتقال ${checked.size} فایل به سطل")
            }
        }
    }
    view?.let { p ->
        AlertDialog(
            onDismissRequest = { view = null },
            title = { Text(p.name, maxLines = 1) },
            text = { PhotoThumb(p.uri, 1024, Modifier.fillMaxWidth().height(300.dp), androidx.compose.ui.layout.ContentScale.Fit) },
            confirmButton = { TextButton(onClick = { view = null }) { Text("بستن") } },
        )
    }
}
