package com.ahmadreza.photoflow

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.sqrt

data class MatchScore(val distance: Int, val pHashDistance: Int, val dHashDistance: Int, val confidence: Float, val level: MatchLevel)
enum class MatchLevel { HIGH, REVIEW, UNMATCHED }
private data class ImageSignature(val pHash: Long, val dHash: Long)

object ImageMatcher {
    private val signatureCache = ConcurrentHashMap<String, ImageSignature>()

    suspend fun compare(resolver: ContentResolver, source: Uri, candidate: Uri): MatchScore {
        val a = signatureFor(resolver, source) ?: return MatchScore(64, 64, 64, 0f, MatchLevel.UNMATCHED)
        val b = signatureFor(resolver, candidate) ?: return MatchScore(64, 64, 64, 0f, MatchLevel.UNMATCHED)
        val p = java.lang.Long.bitCount(a.pHash xor b.pHash)
        val d = java.lang.Long.bitCount(a.dHash xor b.dHash)
        val distance = (p + d) / 2
        val level = when {
            distance <= 11 -> MatchLevel.HIGH
            distance <= 20 -> MatchLevel.REVIEW
            else -> MatchLevel.UNMATCHED
        }
        return MatchScore(distance, p, d, (1f - distance / 64f).coerceIn(0f, 1f), level)
    }

    suspend fun matchAll(resolver: ContentResolver, originals: List<PhotoFile>, outputs: List<PhotoFile>): List<AutomaticMatch> {
        val all = (originals + outputs).distinctBy { it.uri.toString() }
        for (photo in all) signatureFor(resolver, photo.uri)
        return withContext(Dispatchers.Default) {
            outputs.map { output ->
                val candidate = signatureCache[output.uri.toString()]
                if (candidate == null || originals.isEmpty()) AutomaticMatch(output, null, null)
                else {
                    val best = originals.mapNotNull { original ->
                        val source = signatureCache[original.uri.toString()] ?: return@mapNotNull null
                        val p = java.lang.Long.bitCount(source.pHash xor candidate.pHash)
                        val d = java.lang.Long.bitCount(source.dHash xor candidate.dHash)
                        Triple(original, p, d) to ((p + d) / 2)
                    }.minByOrNull { it.second }
                    if (best == null) AutomaticMatch(output, null, null)
                    else {
                        val (details, distance) = best
                        val level = when {
                            distance <= 11 -> MatchLevel.HIGH
                            distance <= 20 -> MatchLevel.REVIEW
                            else -> MatchLevel.UNMATCHED
                        }
                        val score = MatchScore(distance, details.second, details.third,
                            (1f - distance / 64f).coerceIn(0f, 1f), level)
                        AutomaticMatch(output, details.first.takeIf { level != MatchLevel.UNMATCHED }, score)
                    }
                }
            }
        }
    }

    private suspend fun signatureFor(resolver: ContentResolver, uri: Uri): ImageSignature? {
        val key = uri.toString()
        signatureCache[key]?.let { return it }
        val bitmap = withContext(Dispatchers.IO) {
            try { resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } }
            catch (_: Exception) { null }
        } ?: return null
        val computed = try { withContext(Dispatchers.Default) { signature(bitmap) } }
        finally { bitmap.recycle() }
        return signatureCache.putIfAbsent(key, computed) ?: computed
    }

    private fun signature(bitmap: Bitmap): ImageSignature {
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
                sum += gray[y * 32 + x] * cos((2 * x + 1) * u * Math.PI / 64.0) *
                    cos((2 * y + 1) * v * Math.PI / 64.0)
            }
            val cu = if (u == 0) 1.0 / sqrt(2.0) else 1.0
            val cv = if (v == 0) 1.0 / sqrt(2.0) else 1.0
            coeffs[k++] = 0.25 * cu * cv * sum
        }
        val median = coeffs.drop(1).sorted()[31]
        var pHash = 0L
        for (i in coeffs.indices) if (coeffs[i] > median) pHash = pHash or (1L shl i)

        val small = Bitmap.createScaledBitmap(bitmap, 9, 8, true)
        val row = IntArray(72)
        small.getPixels(row, 0, 9, 0, 0, 9, 8)
        small.recycle()
        fun luminance(c: Int): Int {
            val r = (c shr 16) and 255
            val g = (c shr 8) and 255
            val b = c and 255
            return (299 * r + 587 * g + 114 * b) / 1000
        }
        var dHash = 0L
        var bit = 0
        for (y in 0 until 8) for (x in 0 until 8) {
            if (luminance(row[y * 9 + x]) > luminance(row[y * 9 + x + 1])) dHash = dHash or (1L shl bit)
            bit++
        }
        return ImageSignature(pHash, dHash)
    }
}

data class AutomaticMatch(val output: PhotoFile, val original: PhotoFile?, val score: MatchScore?)
