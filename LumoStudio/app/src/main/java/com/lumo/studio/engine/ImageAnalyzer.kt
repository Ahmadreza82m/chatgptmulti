package com.lumo.studio.engine

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

/** Compact, color-independent description of an image. Never leaves the device. */
class Fingerprint(
    val dHash: Long,
    val pHash: Long,
    val edges: FloatArray,
)

object ImageAnalyzer {

    private val dct: Array<FloatArray> = Array(8) { u ->
        FloatArray(32) { x -> cos((2 * x + 1) * u * PI / 64.0).toFloat() }
    }

    fun readSize(resolver: ContentResolver, uri: Uri): Pair<Int, Int>? = try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        if (opts.outWidth > 0 && opts.outHeight > 0) opts.outWidth to opts.outHeight else null
    } catch (e: Exception) {
        null
    }

    fun decodeSampled(resolver: ContentResolver, uri: Uri, maxSide: Int): Bitmap? {
        val size = readSize(resolver, uri) ?: return null
        var sample = 1
        while (max(size.first, size.second) / (sample * 2) >= maxSide) sample *= 2
        return try {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    fun fingerprint(src: Bitmap): Fingerprint {
        val g32 = gray(src, 32, 32)
        val g9 = gray(src, 9, 8)

        // dHash: horizontal gradient signs on a 9x8 thumbnail.
        var dh = 0L
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                dh = dh shl 1
                if (g9[y * 9 + x] > g9[y * 9 + x + 1]) dh = dh or 1L
            }
        }

        // pHash: low-frequency DCT coefficients (8x8) compared to their median.
        val coef = FloatArray(64)
        for (v in 0 until 8) {
            for (u in 0 until 8) {
                var s = 0f
                for (y in 0 until 32) {
                    var row = 0f
                    for (x in 0 until 32) row += g32[y * 32 + x] * dct[u][x]
                    s += row * dct[v][y]
                }
                coef[v * 8 + u] = s
            }
        }
        val sorted = coef.sortedArray()
        val median = (sorted[31] + sorted[32]) / 2f
        var ph = 0L
        for (c in coef) {
            ph = ph shl 1
            if (c > median) ph = ph or 1L
        }

        // Edge structure: Sobel magnitude pooled to 16x16, zero-mean and unit-normalised.
        val mag = FloatArray(32 * 32)
        for (y in 1 until 31) {
            for (x in 1 until 31) {
                val i = y * 32 + x
                val gx = -g32[i - 33] + g32[i - 31] - 2f * g32[i - 1] + 2f * g32[i + 1] - g32[i + 31] + g32[i + 33]
                val gy = -g32[i - 33] - 2f * g32[i - 32] - g32[i - 31] + g32[i + 31] + 2f * g32[i + 32] + g32[i + 33]
                mag[i] = sqrt(gx * gx + gy * gy)
            }
        }
        val edges = FloatArray(16 * 16)
        for (y in 0 until 16) {
            for (x in 0 until 16) {
                val i = (2 * y) * 32 + 2 * x
                edges[y * 16 + x] = (mag[i] + mag[i + 1] + mag[i + 32] + mag[i + 33]) / 4f
            }
        }
        val mean = edges.average().toFloat()
        var norm = 0.0
        for (i in edges.indices) {
            edges[i] -= mean
            norm += (edges[i] * edges[i]).toDouble()
        }
        val n = sqrt(norm).toFloat()
        if (n > 1e-6f) {
            for (i in edges.indices) edges[i] /= n
        } else {
            edges.fill(0f)
        }
        return Fingerprint(dh, ph, edges)
    }

    private fun gray(src: Bitmap, w: Int, h: Int): FloatArray {
        val scaled = Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h)
        scaled.getPixels(px, 0, w, 0, 0, w, h)
        if (scaled !== src) scaled.recycle()
        return FloatArray(w * h) { i ->
            val c = px[i]
            0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
        }
    }
}
