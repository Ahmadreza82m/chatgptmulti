package com.ahmadreza.photoflow

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class MatchScore(
    val distance: Int,
    val pHashDistance: Int,
    val dHashDistance: Int,
    val confidence: Float,
    val level: MatchLevel,
    val runnerUpDistance: Int? = null,
    val margin: Int? = null
)
enum class MatchLevel { HIGH, REVIEW, UNMATCHED }
private data class ImageSignature(val pHash: Long, val dHash: Long, val colorHistogram: FloatArray)

object ImageMatcher {
    private const val MAX_DECODE_SIDE = 1024
    private val signatureCache = ConcurrentHashMap<String, ImageSignature>()

    suspend fun compare(resolver: ContentResolver, source: Uri, candidate: Uri): MatchScore {
        val a = signatureFor(resolver, source) ?: return noMatch()
        val b = signatureFor(resolver, candidate) ?: return noMatch()
        val p = java.lang.Long.bitCount(a.pHash xor b.pHash)
        val d = java.lang.Long.bitCount(a.dHash xor b.dHash)
        val color = colorDistance(a.colorHistogram, b.colorHistogram)
        val distance = combinedDistance(p, d, color)
        val level = when {
            distance <= 10 -> MatchLevel.HIGH
            distance <= 18 -> MatchLevel.REVIEW
            else -> MatchLevel.UNMATCHED
        }
        return MatchScore(distance, p, d, 1f - distance / 64f, level)
    }

    suspend fun matchAll(
        resolver: ContentResolver,
        originals: List<PhotoFile>,
        outputs: List<PhotoFile>
    ): List<AutomaticMatch> {
        // Include file size in the cache key so a replaced file at the same URI is not
        // accidentally compared using an old in-memory signature.
        val all = (originals + outputs).distinctBy { it.uri.toString() }
        all.forEach { photo ->
            signatureFor(resolver, photo.uri, photo.size.toString())
        }

        return withContext(Dispatchers.Default) {
            outputs.map { output ->
                val candidate = signatureCache[cacheKey(output.uri, output.size.toString())]
                if (candidate == null || originals.isEmpty()) {
                    AutomaticMatch(output, null, null)
                } else {
                    val ranked = originals.mapNotNull { original ->
                        val source = signatureCache[cacheKey(original.uri, original.size.toString())]
                            ?: return@mapNotNull null
                        val p = java.lang.Long.bitCount(source.pHash xor candidate.pHash)
                        val d = java.lang.Long.bitCount(source.dHash xor candidate.dHash)
                        val color = colorDistance(source.colorHistogram, candidate.colorHistogram)
                        val distance = combinedDistance(p, d, color)
                        RankedCandidate(original, p, d, distance)
                    }.sortedBy { it.distance }

                    val best = ranked.firstOrNull()
                    if (best == null) {
                        AutomaticMatch(output, null, null)
                    } else {
                        val runnerUp = ranked.getOrNull(1)?.distance
                        // A small gap means two different originals look almost equally
                        // plausible. In that case do not silently assign either one.
                        val margin = runnerUp?.minus(best.distance)
                        val level = when {
                            best.distance <= 10 && (margin == null || margin >= 5) -> MatchLevel.HIGH
                            best.distance <= 18 && (margin == null || margin >= 3) -> MatchLevel.REVIEW
                            else -> MatchLevel.UNMATCHED
                        }
                        val score = MatchScore(
                            distance = best.distance,
                            pHashDistance = best.p,
                            dHashDistance = best.d,
                            confidence = (1f - best.distance / 64f).coerceIn(0f, 1f),
                            level = level,
                            runnerUpDistance = runnerUp,
                            margin = margin
                        )
                        // Only confident / reviewable matches are pre-assigned. Ambiguous
                        // and weak matches stay ungrouped until the user chooses manually.
                        AutomaticMatch(output, best.original.takeIf { level != MatchLevel.UNMATCHED }, score)
                    }
                }
            }
        }
    }

    private data class RankedCandidate(
        val original: PhotoFile,
        val p: Int,
        val d: Int,
        val distance: Int
    )

    private fun combinedDistance(p: Int, d: Int, color: Int): Int =
        (p * 0.48 + d * 0.42 + color * 0.10).roundToInt().coerceIn(0, 64)

    private fun colorDistance(a: FloatArray, b: FloatArray): Int {
        // Histogram L1 distance is in [0, 2]; map it to a 0..64 scale.
        var total = 0f
        for (i in a.indices) total += kotlin.math.abs(a[i] - b[i])
        return (total * 32f).roundToInt().coerceIn(0, 64)
    }

    private fun cacheKey(uri: Uri, token: String) = "${uri}#${token}"

    private suspend fun signatureFor(
        resolver: ContentResolver,
        uri: Uri,
        cacheToken: String = ""
    ): ImageSignature? {
        val key = cacheKey(uri, cacheToken)
        signatureCache[key]?.let { return it }
        val bitmap = withContext(Dispatchers.IO) {
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
                var sample = 1
                while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > MAX_DECODE_SIDE) sample *= 2
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            } catch (_: Exception) { null }
        } ?: return null

        val computed = try {
            withContext(Dispatchers.Default) { signature(bitmap) }
        } finally {
            bitmap.recycle()
        }
        return signatureCache.putIfAbsent(key, computed) ?: computed
    }

    private fun signature(bitmap: Bitmap): ImageSignature {
        // Fit the whole image into a square canvas without stretching portrait or
        // landscape photos into a different aspect ratio.
        val scale = min(32f / bitmap.width, 32f / bitmap.height)
        val width = (bitmap.width * scale).roundToInt().coerceIn(1, 32)
        val height = (bitmap.height * scale).roundToInt().coerceIn(1, 32)
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        val pixels = IntArray(32 * 32) { 0xFF808080.toInt() }
        val offsetX = (32 - width) / 2
        val offsetY = (32 - height) / 2
        val content = IntArray(width * height)
        scaled.getPixels(content, 0, width, 0, 0, width, height)
        scaled.recycle()
        for (y in 0 until height) {
            System.arraycopy(content, y * width, pixels, (offsetY + y) * 32 + offsetX, width)
        }

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
        var pHash = 0L
        for (i in coeffs.indices) if (coeffs[i] > median) pHash = pHash or (1L shl i)

        var dHash = 0L
        var bit = 0
        for (y in 0 until 8) for (x in 0 until 8) {
            if (luminance(pixels[y * 32 + x]) > luminance(pixels[y * 32 + x + 1])) {
                dHash = dHash or (1L shl bit)
            }
            bit++
        }

        // A coarse RGB histogram helps distinguish images with similar brightness
        // layouts but clearly different dominant colors.
        val histogram = FloatArray(64)
        for (y in offsetY until offsetY + height) {
            for (x in offsetX until offsetX + width) {
                val c = pixels[y * 32 + x]
                val r = ((c shr 16) and 255) / 64
                val g = ((c shr 8) and 255) / 64
                val b = (c and 255) / 64
                histogram[r * 16 + g * 4 + b]++
            }
        }
        val count = (width * height).toFloat().coerceAtLeast(1f)
        for (i in histogram.indices) histogram[i] /= count
        return ImageSignature(pHash, dHash, histogram)
    }

    private fun luminance(c: Int): Int {
        val r = (c shr 16) and 255
        val g = (c shr 8) and 255
        val b = c and 255
        return (299 * r + 587 * g + 114 * b) / 1000
    }

    private fun noMatch() = MatchScore(64, 64, 64, 0f, MatchLevel.UNMATCHED)
}

data class AutomaticMatch(val output: PhotoFile, val original: PhotoFile?, val score: MatchScore?)
