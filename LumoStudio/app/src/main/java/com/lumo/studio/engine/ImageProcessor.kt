package com.lumo.studio.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream

/** تبدیل فرمت و فشرده‌سازی (قابلیت‌های ۱۱ و ۱۳). فایل اصلی هرگز تغییر نمی‌کند. */
object ImageProcessor {

    val FORMATS = listOf("JPG", "PNG", "WEBP")

    fun extFor(format: String) = when (format.uppercase()) {
        "PNG" -> "png"
        "WEBP" -> "webp"
        else -> "jpg"
    }

    fun mimeFor(format: String) = when (format.uppercase()) {
        "PNG" -> "image/png"
        "WEBP" -> "image/webp"
        else -> "image/jpeg"
    }

    fun decodeOriented(ctx: Context, uri: Uri, maxSide: Int = 0): Bitmap? {
        var sample = 1
        if (maxSide > 0) {
            val size = ImageAnalyzer.readSize(ctx.contentResolver, uri) ?: return null
            while (maxOf(size.first, size.second) / (sample * 2) >= maxSide) sample *= 2
        }
        var bmp: Bitmap? = null
        for (attempt in 0..2) {
            try {
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                break
            } catch (e: OutOfMemoryError) {
                sample *= 2
            } catch (e: Exception) {
                return null
            }
        }
        val src = bmp ?: return null
        val orientation = runCatching {
            ctx.contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return src
        }
        return try {
            Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true).also { if (it !== src) src.recycle() }
        } catch (e: OutOfMemoryError) {
            src
        }
    }

    fun encode(src: Bitmap, format: String, quality: Int): ByteArray {
        val q = quality.coerceIn(1, 100)
        var bmp = src
        if (format.uppercase() == "JPG" && src.hasAlpha()) {
            val flat = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            Canvas(flat).apply { drawColor(Color.WHITE); drawBitmap(src, 0f, 0f, null) }
            bmp = flat
        }
        val out = ByteArrayOutputStream()
        when (format.uppercase()) {
            "PNG" -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            "WEBP" -> {
                @Suppress("DEPRECATION")
                val f = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
                bmp.compress(f, q, out)
            }
            else -> bmp.compress(Bitmap.CompressFormat.JPEG, q, out)
        }
        if (bmp !== src) bmp.recycle()
        return out.toByteArray()
    }
}
