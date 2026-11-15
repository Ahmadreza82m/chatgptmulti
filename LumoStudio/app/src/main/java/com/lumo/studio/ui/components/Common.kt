package com.lumo.studio.ui.components

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import android.graphics.Bitmap
import android.util.LruCache
import com.lumo.studio.engine.ImageAnalyzer
import com.lumo.studio.ui.theme.LocalLumo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun AuroraBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val c = LocalLumo.current
    Box(
        modifier
            .fillMaxSize()
            .background(c.bg)
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        listOf(c.blob1, Color.Transparent),
                        center = Offset(size.width * 0.8f, size.height * 0.1f),
                        radius = size.width * 0.9f,
                    )
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(c.blob2, Color.Transparent),
                        center = Offset(size.width * 0.1f, size.height * 0.9f),
                        radius = size.width * 0.8f,
                    )
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(c.blob3, Color.Transparent),
                        center = Offset(size.width * 0.5f, size.height * 0.5f),
                        radius = size.width * 0.6f,
                    )
                )
            },
        content = content,
    )
}

@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalLumo.current
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier
            .clip(shape)
            .background(c.surface)
            .border(1.dp, c.line, shape)
            .padding(16.dp),
        content = content,
    )
}

@Composable
fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalLumo.current
    GlassCard(Modifier.fillMaxWidth()) {
        Text(title, color = c.fg, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
fun LumoChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = LocalLumo.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = c.fg, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

object BitmapCache {
    private val cache = object : LruCache<String, ImageBitmap>(24 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int =
            value.width * value.height * 4 / 1024
    }

    fun get(key: String): ImageBitmap? = cache.get(key)

    fun put(key: String, value: ImageBitmap) {
        cache.put(key, value)
    }
}

private fun loadBitmap(ctx: Context, uri: String, maxSide: Int, key: String): ImageBitmap? {
    BitmapCache.get(key)?.let { return it }
    val bmp: Bitmap = ImageAnalyzer.decodeSampled(ctx.contentResolver, Uri.parse(uri), maxSide) ?: return null
    val img = bmp.asImageBitmap()
    BitmapCache.put(key, img)
    return img
}

@Composable
fun rememberPhotoBitmap(uri: String, maxSide: Int): State<ImageBitmap?> {
    val ctx = LocalContext.current
    val key = "$uri|$maxSide"
    return produceState(initialValue = BitmapCache.get(key), uri, maxSide) {
        if (value == null) {
            value = withContext(Dispatchers.IO) { loadBitmap(ctx, uri, maxSide, key) }
        }
    }
}

@Composable
fun PhotoThumb(
    uri: String,
    maxSide: Int,
    modifier: Modifier = Modifier,
    scale: androidx.compose.ui.layout.ContentScale = androidx.compose.ui.layout.ContentScale.Crop,
) {
    val img by rememberPhotoBitmap(uri, maxSide)
    val c = LocalLumo.current
    Box(
        modifier.clip(RoundedCornerShape(16.dp)).background(c.surface2),
        contentAlignment = Alignment.Center,
    ) {
        img?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = scale,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun fitRect(iw: Int, ih: Int, bw: Float, bh: Float): Rect {
    val s = min(bw / iw, bh / ih)
    val w = iw * s
    val h = ih * s
    return Rect((bw - w) / 2f, (bh - h) / 2f, (bw + w) / 2f, (bh + h) / 2f)
}

private fun DrawScope.drawInRect(img: ImageBitmap, rect: Rect) {
    drawImage(
        img,
        dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
        dstSize = IntSize(rect.width.roundToInt(), rect.height.roundToInt()),
    )
}

/** Draggable before/after slider. Left side shows [otherUri], right side shows [baseUri]. */
@Composable
fun CompareSlider(baseUri: String, otherUri: String, modifier: Modifier = Modifier) {
    val base by rememberPhotoBitmap(baseUri, 1280)
    val other by rememberPhotoBitmap(otherUri, 1280)
    var split by remember(otherUri) { mutableFloatStateOf(0.5f) }
    var boxWidth by remember { mutableIntStateOf(1) }
    val c = LocalLumo.current

    Box(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(c.surface2)
            .onSizeChanged { boxWidth = it.width.coerceAtLeast(1) }
            .pointerInput(otherUri) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    split = (change.position.x / boxWidth).coerceIn(0f, 1f)
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val ref = base ?: other ?: return@Canvas
            val rect = fitRect(ref.width, ref.height, size.width, size.height)
            base?.let { drawInRect(it, rect) }
            other?.let { img ->
                clipRect(0f, 0f, size.width * split, size.height) { drawInRect(img, rect) }
            }
            val x = size.width * split
            drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), strokeWidth = 4f)
            drawCircle(Color.White, radius = 22f, center = Offset(x, size.height / 2f))
        }
    }
}
