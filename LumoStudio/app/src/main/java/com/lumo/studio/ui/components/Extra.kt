package com.lumo.studio.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumo.studio.ui.theme.LocalLumo
import com.lumo.studio.viewmodel.JobStatus
import com.lumo.studio.viewmodel.StudioViewModel
import com.lumo.studio.viewmodel.UiState
import kotlin.math.min
import kotlin.math.roundToInt

/** قالب یکسان صفحه‌های ابزار: عنوان، دکمه‌ی بازگشت و محتوای اسکرولی. */
@Composable
fun Page(
    title: String,
    onBack: () -> Unit,
    scroll: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalLumo.current
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = c.fg, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            actions()
            IconButton(onClick = onBack) { Icon(Icons.Filled.Close, contentDescription = "بستن", tint = c.fg) }
        }
        Spacer(Modifier.height(8.dp))
        val m = if (scroll) Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
        else Modifier.weight(1f).fillMaxWidth()
        Column(m, verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
fun Dim(text: String) {
    Text(text, color = LocalLumo.current.fg2, fontSize = 13.sp)
}

@Composable
fun InfoLine(label: String, value: String) {
    val c = LocalLumo.current
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = c.fg2, modifier = Modifier.weight(1f))
        Text(value, color = c.fg)
    }
}

@Composable
fun NumField(label: String, value: String, modifier: Modifier = Modifier, onValue: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValue(it.filter { ch -> ch.isDigit() || ch == '.' }) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
fun TextField1(label: String, value: String, modifier: Modifier = Modifier, onValue: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onValue, label = { Text(label) }, singleLine = true, modifier = modifier)
}

/** ردیف چیپ‌های افقی (بدون FlowRow آزمایشی). */
@Composable
fun ChipRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

fun labelFor(state: UiState, uri: String): String =
    state.project.folders.firstOrNull { it.uri == uri }?.label
        ?: state.settings.favorites.firstOrNull { it.uri == uri }?.label
        ?: android.net.Uri.decode(uri.substringAfterLast('/')).substringAfterLast(':').ifEmpty { "پوشه" }

/** انتخاب مقصد: پوشه‌های پروژه، علاقه‌مندی‌ها یا پوشه‌ی جدید. */
@Composable
fun DestinationDialog(
    state: UiState,
    vm: StudioViewModel,
    title: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { vm.grantTree(uri); onPick(uri.toString()) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                if (state.project.folders.isNotEmpty()) Dim("پوشه‌های پروژه")
                state.project.folders.forEach { f -> TextButton(onClick = { onPick(f.uri) }) { Text(f.label) } }
                if (state.settings.favorites.isNotEmpty()) Dim("علاقه‌مندی‌ها")
                state.settings.favorites.forEach { f -> TextButton(onClick = { onPick(f.uri) }) { Text("★ ${f.label}") } }
                TextButton(onClick = { launcher.launch(null) }) { Text("انتخاب پوشه‌ی دیگر…") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("بستن") } },
    )
}

@Composable
fun ConfirmDialog(state: UiState, vm: StudioViewModel) {
    val info = state.confirm ?: return
    AlertDialog(
        onDismissRequest = { vm.dismissConfirm() },
        title = { Text(info.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                info.lines.forEach { Text("• $it") }
                Text("نیازمند تأیید کاربر", color = LocalLumo.current.warn, fontSize = 12.sp)
            }
        },
        confirmButton = { Button(onClick = { vm.confirmed() }) { Text("تأیید و اجرا") } },
        dismissButton = { TextButton(onClick = { vm.dismissConfirm() }) { Text("انصراف") } },
    )
}

/** نوار عملیات پس‌زمینه با توقف، ادامه و لغو (قابلیت‌های ۲۳ و ۲۴). */
@Composable
fun JobBar(state: UiState, vm: StudioViewModel, modifier: Modifier = Modifier) {
    val job = state.job ?: return
    val c = LocalLumo.current
    GlassCard(modifier.fillMaxWidth()) {
        Text(job.title, color = c.fg, style = MaterialTheme.typography.titleSmall, maxLines = 1)
        Spacer(Modifier.height(6.dp))
        if (!job.finished) {
            if (job.total > 0) LinearProgressIndicator(progress = { (job.done + job.failed).toFloat() / job.total }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                "انجام‌شده ${job.done} · ناموفق ${job.failed} · باقی‌مانده ${job.remaining}" +
                    if (job.status == JobStatus.PAUSED) " · متوقف" else "",
                color = c.fg2, fontSize = 12.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (job.canPause) OutlinedButton(onClick = { vm.togglePause() }) {
                    Text(if (job.status == JobStatus.PAUSED) "ادامه" else "توقف موقت")
                }
                OutlinedButton(onClick = { vm.cancelJob() }) { Text("لغو") }
            }
        } else {
            Text(job.note, color = c.fg2, fontSize = 13.sp)
            TextButton(onClick = { vm.dismissJob() }) { Text("بستن") }
        }
    }
}

// ----- مقایسه‌ی دو تصویر درون‌حافظه‌ای (قبل/بعد از فشرده‌سازی) -----

private fun fitRect2(iw: Int, ih: Int, bw: Float, bh: Float): Rect {
    val s = min(bw / iw, bh / ih)
    val w = iw * s
    val h = ih * s
    return Rect((bw - w) / 2f, (bh - h) / 2f, (bw + w) / 2f, (bh + h) / 2f)
}

private fun DrawScope.drawIn(img: ImageBitmap, r: Rect) {
    drawImage(img, dstOffset = IntOffset(r.left.roundToInt(), r.top.roundToInt()),
        dstSize = IntSize(r.width.roundToInt(), r.height.roundToInt()))
}

@Composable
fun CompareImages(base: ImageBitmap?, other: ImageBitmap?, modifier: Modifier = Modifier) {
    var split by remember { mutableFloatStateOf(0.5f) }
    var w by remember { mutableIntStateOf(1) }
    val c = LocalLumo.current
    Box(
        modifier.clip(RoundedCornerShape(20.dp)).background(c.surface2)
            .onSizeChanged { w = it.width.coerceAtLeast(1) }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    split = (change.position.x / w).coerceIn(0f, 1f)
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val ref = base ?: other ?: return@Canvas
            val r = fitRect2(ref.width, ref.height, size.width, size.height)
            base?.let { drawIn(it, r) }
            other?.let { img -> clipRect(0f, 0f, size.width * split, size.height) { drawIn(img, r) } }
            val x = size.width * split
            drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), strokeWidth = 4f)
            drawCircle(Color.White, radius = 22f, center = Offset(x, size.height / 2f))
        }
    }
}
