package com.ahmadreza.photorevive

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PhotoReviveScreen(
                        appContext = this,
                        onSave = { bitmap -> saveBitmap(bitmap) }
                    )
                }
            }
        }
    }

    private fun saveBitmap(bitmap: Bitmap): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "PhotoRevive_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PhotoRevive")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return false
        return try {
            val written = contentResolver.openOutputStream(uri)?.use {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)
            } ?: false
            if (!written) {
                contentResolver.delete(uri, null, null)
                return false
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
            }
            true
        } catch (_: Exception) {
            contentResolver.delete(uri, null, null)
            false
        }
    }
}

@Composable
private fun PhotoReviveScreen(appContext: android.content.Context, onSave: (Bitmap) -> Boolean) {
    val selected = remember { mutableStateListOf<Uri>() }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("عکس‌ها را انتخاب کن؛ فایل اصلی تغییر نمی‌کند.") }
    var comparison by remember { mutableStateOf<Pair<Bitmap, Bitmap>?>(null) }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        selected.clear()
        selected.addAll(uris)
        comparison = null
        message = if (uris.isEmpty()) "هنوز عکسی انتخاب نشده است." else "${uris.size} عکس انتخاب شد."
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("PhotoRevive", style = MaterialTheme.typography.headlineMedium)
        Text(
            "بهبود آفلاین عکس‌ها",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            "این نسخه یک پایهٔ آزمایشی است: وضوح واقعیِ ازدست‌رفته را با هوش مصنوعی بازسازی یا عکس سیاه‌وسفید را رنگی نمی‌کند."
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { picker.launch("image/*") }, enabled = !busy) {
                Text("انتخاب عکس‌ها")
            }
            OutlinedButton(
                onClick = {
                    selected.clear()
                    comparison = null
                    message = "انتخاب عکس‌ها پاک شد."
                },
                enabled = selected.isNotEmpty() && !busy
            ) { Text("پاک کردن") }
        }
        Text(message, style = MaterialTheme.typography.bodyMedium)

        Button(
            onClick = {
                if (selected.isEmpty() || busy) return@Button
                busy = true
                scope.launch {
                    var saved = 0
                    var failed = 0
                    val total = selected.size
                    selected.toList().forEachIndexed { index, uri ->
                        message = "در حال پردازش ${index + 1} از $total ..."
                        val result = withContext(Dispatchers.IO) {
                            try {
                                val bitmap = contentResolverFor(appContext, uri)
                                if (bitmap == null) false
                                else ImageEnhancer.enhance(bitmap).let(onSave)
                            } catch (_: Exception) {
                                false
                            }
                        }
                        if (result) saved++ else failed++
                    }
                    busy = false
                    message = "تمام شد؛ ذخیره موفق: $saved، ناموفق: $failed. عکس‌های اصلی دست‌نخورده ماندند."
                }
            },
            enabled = selected.isNotEmpty() && !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (busy) "در حال پردازش..." else "بهبود و ذخیره نسخه‌ها")
        }

        OutlinedButton(
            onClick = {
                val uri = selected.firstOrNull() ?: return@OutlinedButton
                if (busy) return@OutlinedButton
                busy = true
                comparison = null
                message = "در حال آماده‌سازی مقایسه..."
                scope.launch {
                    val pair = withContext(Dispatchers.IO) {
                        runCatching {
                            val original = contentResolverFor(appContext, uri)
                                ?: error("خواندن عکس ناموفق بود")
                            original to ImageEnhancer.enhance(original)
                        }.getOrNull()
                    }
                    comparison = pair
                    busy = false
                    message = if (pair == null) "ساخت پیش‌نمایش ناموفق بود؛ عکس دیگری انتخاب کن."
                    else "مرز وسط عکس را با انگشت به چپ و راست بکش و دو نسخه را مقایسه کن."
                }
            },
            enabled = selected.isNotEmpty() && !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (busy) "لطفاً صبر کن..." else "مقایسه قبل و بعد")
        }

        comparison?.let { (before, after) ->
            Text("مقایسهٔ زنده — انگشتت را روی خط وسط بکش", style = MaterialTheme.typography.titleSmall)
            BeforeAfterComparison(before = before, after = after)
            Text("سمت راست: نسخهٔ بهبودیافته  |  سمت چپ: عکس اصلی", style = MaterialTheme.typography.bodySmall)
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(selected, key = { it.toString() }) { uri ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        AsyncImage(
                            model = uri,
                            contentDescription = "پیش‌نمایش عکس انتخاب‌شده",
                            modifier = Modifier.size(88.dp),
                            contentScale = ContentScale.Crop
                        )
                        Text(uri.lastPathSegment ?: "عکس انتخاب‌شده", modifier = Modifier.weight(2f))
                    }
                }
            }
        }
    }
}

@Composable
private fun BeforeAfterComparison(before: Bitmap, after: Bitmap) {
    var split by remember(before, after) { mutableStateOf(0.5f) }
    val beforeImage = remember(before) { before.asImageBitmap() }
    val afterImage = remember(after) { after.asImageBitmap() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(4f / 3f)
            .clip(RoundedCornerShape(14.dp))
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(before, after) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        split = (change.position.x / size.width.toFloat()).coerceIn(0.02f, 0.98f)
                    }
                }
        ) {
            val destination = IntSize(size.width.toInt(), size.height.toInt())
            drawImage(beforeImage, dstSize = destination)
            clipRect(left = size.width * split, top = 0f, right = size.width, bottom = size.height) {
                drawImage(afterImage, dstSize = destination)
            }
            val dividerX = size.width * split
            drawLine(
                color = Color.White,
                start = androidx.compose.ui.geometry.Offset(dividerX, 0f),
                end = androidx.compose.ui.geometry.Offset(dividerX, size.height),
                strokeWidth = 4.dp.toPx()
            )
            drawCircle(
                color = Color.White,
                radius = 13.dp.toPx(),
                center = androidx.compose.ui.geometry.Offset(dividerX, size.height / 2f)
            )
            drawCircle(
                color = Color.DarkGray,
                radius = 9.dp.toPx(),
                center = androidx.compose.ui.geometry.Offset(dividerX, size.height / 2f)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("اصل", color = Color.White, style = MaterialTheme.typography.labelLarge)
            Text("بهبودیافته", color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
    }
}

private fun contentResolverFor(context: android.content.Context, uri: Uri): Bitmap? {
    return runCatching {
        val decoded = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream)
        } ?: return null

        val orientation = context.contentResolver.openInputStream(uri)?.use { stream ->
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } ?: ExifInterface.ORIENTATION_NORMAL

        val transform = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> transform.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> transform.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> transform.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                transform.setRotate(90f)
                transform.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> transform.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                transform.setRotate(-90f)
                transform.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> transform.setRotate(270f)
        }

        if (orientation == ExifInterface.ORIENTATION_NORMAL ||
            orientation == ExifInterface.ORIENTATION_UNDEFINED
        ) {
            decoded
        } else {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, transform, true)
                .also { if (it !== decoded && !decoded.isRecycled) decoded.recycle() }
        }
    }.getOrNull()
}
