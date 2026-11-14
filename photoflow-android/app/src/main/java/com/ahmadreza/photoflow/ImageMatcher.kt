package com.ahmadreza.photoflow

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.sqrt

data class MatchScore(val distance: Int, val confidence: Float, val level: MatchLevel)
enum class MatchLevel { HIGH, REVIEW, UNMATCHED }

object ImageMatcher {
    suspend fun compare(resolver: ContentResolver, source: Uri, candidate: Uri): MatchScore =
        withContext(Dispatchers.Default) {
            val a = decode(resolver, source) ?: return@withContext MatchScore(64, 0f, MatchLevel.UNMATCHED)
            val b = decode(resolver, candidate) ?: return@withContext MatchScore(64, 0f, MatchLevel.UNMATCHED)
            val distance = java.lang.Long.bitCount(perceptualHash(a) xor perceptualHash(b))
            a.recycle()
            b.recycle()
            val confidence = (1f - distance / 64f).coerceIn(0f, 1f)
            val level = when {
                distance <= 9 -> MatchLevel.HIGH
                distance <= 17 -> MatchLevel.REVIEW
                else -> MatchLevel.UNMATCHED
            }
            MatchScore(distance, confidence, level)
        }

    private fun decode(resolver: ContentResolver, uri: Uri): Bitmap? = try {
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
    } catch (_: Exception) { null }

    // 64-bit perceptual hash: grayscale + 32x32 DCT + low-frequency 8x8 coefficients.
    // Color is discarded intentionally, allowing grayscale originals to match color enhancements.
    private fun perceptualHash(bitmap: Bitmap): Long {
        val sample = Bitmap.createScaledBitmap(bitmap, 32, 32, true)
        val pixels = IntArray(32 * 32)
        sample.getPixels(pixels, 0, 32, 0, 0, 32, 32)
        sample.recycle()
        val gray = DoubleArray(32 * 32) { i ->
            val c = pixels[i]
            val r = (c shr 16) and 255
            val g = (c shr 8) and 255
            val b = c and 255
            0.299 * r + 0.587 * g + 0.114 * b
        }
        val coeffs = DoubleArray(64)
        var k = 0
        for (v in 0 until 8) for (u in 0 until 8) {
            var sum = 0.0
            for (y in 0 until 32) for (x in 0 until 32) {
                sum += gray[y * 32 + x] *
                    cos((2 * x + 1) * u * Math.PI / 64.0) *
                    cos((2 * y + 1) * v * Math.PI / 64.0)
            }
            val cu = if (u == 0) 1.0 / sqrt(2.0) else 1.0
            val cv = if (v == 0) 1.0 / sqrt(2.0) else 1.0
            coeffs[k++] = 0.25 * cu * cv * sum
        }
        val median = coeffs.drop(1).sorted()[31]
        var hash = 0L
        for (i in coeffs.indices) if (coeffs[i] > median) hash = hash or (1L shl i)
        return hash
    }
}
