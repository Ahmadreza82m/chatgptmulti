package com.ahmadreza.photorevive

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
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
            contentResolver.openOutputStream(uri)?.use {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)
            } ?: false
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
private fun PhotoReviveScreen(onSave: (Bitmap) -> Boolean) {
    val selected = remember { mutableStateListOf<Uri>() }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("عکس‌ها را انتخاب کن؛ فایل اصلی تغییر نمی‌کند.") }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        selected.clear()
        selected.addAll(uris)
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
                                val bitmap = contentResolverFor(uri)
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
                            modifier = Modifier.weight(1f),
                            contentScale = ContentScale.Crop
                        )
                        Text(uri.lastPathSegment ?: "عکس انتخاب‌شده", modifier = Modifier.weight(2f))
                    }
                }
            }
        }
    }
}

private fun contentResolverFor(uri: Uri): Bitmap? {
    // Resolve through the URI's content provider; no broad storage permission is needed.
    return runCatching {
        val context = AppContextHolder.context ?: return null
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
    }.getOrNull()
}

/** Set by the activity before Compose content is created. */
private object AppContextHolder {
    var context: android.content.Context? = null
}
