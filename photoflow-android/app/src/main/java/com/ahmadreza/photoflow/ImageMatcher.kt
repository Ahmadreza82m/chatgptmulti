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

private data class ImageSignature(val pHash: Long)

object ImageMatcher {
    private val signatureCache = ConcurrentHashMap<String, ImageSignature>()

    suspend fun compare(resolver: ContentResolver, source: Uri, candidate: Uri): MatchScore {
        val a = signatureFor(resolver, source) ?: return noMatch()
        val b = signatureFor(resolver, candidate) ?: return noMatch()
        val distance = java.lang.Long.bitCount(a.pHash xor b.pHash)
        return score(distance)
    }

    suspend fun matchAll(
        resolver: ContentResolver,
        originals: List<PhotoFile>,
        outputs: List<PhotoFile>
    ): List<AutomaticMatch> {
        val all = (originals + outputs).distinctBy { it.uri.toString() }
        all.forEach { photo ->
            signatureFor(resolver, photo.uri, photo.size.toString())
        }

        return withContext(Dispatchers.Default) {
            outputs.map { output ->
                val outputSignature =
                    signatureCache[cacheKey(output.uri, output.size.toString())]
                if (outputSignature == null || originals.isEmpty()) {
                    AutomaticMatch(output, null, null)
                } else {
                    val best = originals.mapNotNull { original ->
                        val signature =
                            signatureCache[cacheKey(original.uri, original.size.toString())]
                                ?: return@mapNotNull null
                        val distance = java.lang.Long.bitCount(
                            signature.pHash xor outputSignature.pHash
                        )
                        original to distance
                    }.minByOrNull { it.second }

                    if (best == null) {
                        AutomaticMatch(output, null, null)
                    } else {
                        val result = score(best.second)
                        // Keep the original matching behavior: assign the closest original
                        // whenever it falls inside the established review threshold.
                        AutomaticMatch(
                            output,
                            best.first.takeIf { result.level != MatchLevel.UNMATCHED },
                            result
                        )
                    }
                }
            }
        }
    }

    private fun score(distance: Int): MatchScore {
        val confidence = (1f - distance / 64f).coerceIn(0f, 1f)
        val level = when {
            distance <= 9 -> MatchLevel.HIGH
            distance <= 17 -> MatchLevel.REVIEW
            else -> MatchLevel.UNMATCHED
        }
        return MatchScore(
            distance = distance,
            pHashDistance = distance,
            dHashDistance = 0,
            confidence = confidence,
            level = level
        )
    }

    private fun cacheKey(uri: Uri, token: String) = "${uri}#${token}"

    private suspend fun signatureFor(
        resolver: ContentResolver,
        uri: Uri,
        cacheToken: String = ""
    ): ImageSignature? {
        val key = cacheKey(uri, cacheToken)
        signatureCache[key]?.let { return it }

        // Decode the full image before reducing it to 32x32, matching the original
        // implementation's input path and avoiding the newer sampled-image regression.
        val bitmap = withContext(Dispatchers.IO) {
            try {
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            } catch (_: Exception) {
                null
            }
        } ?: return null

        val computed = try {
            withContext(Dispatchers.Default) { signature(bitmap) }
        } finally {
            bitmap.recycle()
        }
        return signatureCache.putIfAbsent(key, computed) ?: computed
    }

    // Original 64-bit perceptual hash: grayscale + 32x32 DCT + low-frequency 8x8 coefficients.
    // Color is intentionally discarded so grayscale originals can match color-enhanced outputs.
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
        for (i in coeffs.indices) {
            if (coeffs[i] > median) hash = hash or (1L shl i)
        }
        return ImageSignature(hash)
    }

    private fun noMatch() = MatchScore(
        distance = 64,
        pHashDistance = 64,
        dHashDistance = 0,
        confidence = 0f,
        level = MatchLevel.UNMATCHED
    )
}

data class AutomaticMatch(
    val output: PhotoFile,
    val original: PhotoFile?,
    val score: MatchScore?
)
