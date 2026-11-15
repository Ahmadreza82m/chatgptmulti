package com.ahmadreza.photorevive

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.roundToInt

/**
 * Lightweight offline enhancement baseline.
 * This is conventional pixel processing, not an AI restoration or colorization model.
 */
object ImageEnhancer {
    fun enhance(source: Bitmap): Bitmap {
        val input = source.copy(Bitmap.Config.ARGB_8888, false)
        val width = input.width
        val height = input.height
        val pixels = IntArray(width * height)
        input.getPixels(pixels, 0, width, 0, 0, width, height)

        val contrast = 1.18f
        val saturation = 1.12f
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = Color.red(c).toFloat()
            val g = Color.green(c).toFloat()
            val b = Color.blue(c).toFloat()

            val luminance = 0.213f * r + 0.715f * g + 0.072f * b
            val nr = luminance + (r - luminance) * saturation
            val ng = luminance + (g - luminance) * saturation
            val nb = luminance + (b - luminance) * saturation

            fun adjust(v: Float): Int =
                (((v - 127.5f) * contrast) + 127.5f + 2f).roundToInt().coerceIn(0, 255)

            pixels[i] = Color.argb(
                Color.alpha(c),
                adjust(nr),
                adjust(ng),
                adjust(nb)
            )
        }

        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }
}
