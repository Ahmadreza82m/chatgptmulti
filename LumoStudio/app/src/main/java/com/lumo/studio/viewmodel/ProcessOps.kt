package com.lumo.studio.viewmodel

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.viewModelScope
import com.lumo.studio.engine.FileSafe
import com.lumo.studio.engine.ImageAnalyzer
import com.lumo.studio.engine.ImageProcessor
import com.lumo.studio.engine.Stats
import com.lumo.studio.model.*
import com.lumo.studio.ops.DirIndex
import com.lumo.studio.ops.FileOps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ProcessMode { CONVERT, COMPRESS }

/** قابلیت ۱۳: مقایسه‌ی قبل و بعد از فشرده‌سازی روی اولین عکس انتخاب‌شده. */
fun StudioViewModel.previewCompress(format: String, quality: Int) {
    val sel = selectedPhotos()
    val p = sel.firstOrNull() ?: run { toast("ابتدا چند عکس را در کتابخانه انتخاب کن."); return }
    val total = sel.sumOf { it.size }
    viewModelScope.launch(Dispatchers.IO) {
        val uri = Uri.parse(p.uri)
        val bmp = ImageProcessor.decodeOriented(ctx, uri)
        if (bmp == null) { toast("این عکس خوانده نشد."); return@launch }
        val bytes = ImageProcessor.encode(bmp, format, quality)
        bmp.recycle()
        val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
        val after = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        val before = ImageAnalyzer.decodeSampled(ctx.contentResolver, uri, 1280)
        mutate {
            it.copy(compressPreview = ProcessPreview(p.name, p.size, bytes.size.toLong(),
                before?.asImageBitmap(), after?.asImageBitmap(), total))
        }
    }
}

/** قابلیت‌های ۱۱ و ۱۳: خروجی همیشه کپی جدید در پوشه‌ی مقصد است؛ فایل اصلی دست‌نخورده می‌ماند. */
fun StudioViewModel.runProcess(mode: ProcessMode, format: String, quality: Int, destUri: String) {
    val photos = selectedPhotos().filter { !it.missing }
    if (photos.isEmpty()) { toast("عکسی انتخاب نشده است."); return }
    val label = if (mode == ProcessMode.CONVERT) "تبدیل به $format" else "فشرده‌سازی ($format ${quality}٪)"
    startJob("$label · ${photos.size} عکس", photos.size) {
        val idx = DirIndex(ctx)
        val root = FileOps.asDoc(destUri)
        val outName = (if (mode == ProcessMode.CONVERT) "Lumo_Convert_" else "Lumo_Compress_") +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val out = idx.ensureDir(root, outName) ?: throw IllegalStateException("ساخت پوشه‌ی خروجی ممکن نشد")
        var ok = 0; var bad = 0; var before = 0L; var after = 0L
        val errs = ArrayList<AppError>()
        try {
            for (p in photos) {
                checkpoint()
                try {
                    val bmp = ImageProcessor.decodeOriented(ctx, Uri.parse(p.uri))
                    if (bmp == null) {
                        bad++
                        errs += makeError(ErrorKind.UNSUPPORTED, p.name, "تصویر خوانده نشد")
                    } else {
                        val bytes = ImageProcessor.encode(bmp, format, quality)
                        bmp.recycle()
                        val name = idx.unique(out, FileSafe.clean(p.baseName) + "." + ImageProcessor.extFor(format))
                        val u = FileOps.createFile(ctx, out, name, ImageProcessor.mimeFor(format))
                        if (u == null) throw java.io.IOException("ساخت فایل ممکن نشد")
                        ctx.contentResolver.openOutputStream(u, "wt")?.use { it.write(bytes) }
                        idx.add(out, FileOps.Child(u, name, "", bytes.size.toLong(), 0))
                        ok++; before += p.size; after += bytes.size
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    bad++
                    errs += makeError(FileOps.classify(e), p.name, e.message ?: "خطا در پردازش")
                } catch (e: OutOfMemoryError) {
                    bad++
                    errs += makeError(ErrorKind.OTHER, p.name, "حافظه کافی نبود")
                }
                report(ok, bad)
            }
        } finally {
            withContext(NonCancellable) {
                reportErrors(errs)
                val saved = if (before > 0) "کاهش حجم: ${Stats.fmtBytes(before)} ← ${Stats.fmtBytes(after)}" else ""
                log(OpEntry(
                    type = if (mode == ProcessMode.CONVERT) OpType.CONVERT else OpType.COMPRESS,
                    title = "$label · $ok عکس", details = listOfNotNull("ناموفق: $bad", saved.ifEmpty { null }),
                    steps = listOf(UndoStep("DELETE", a = out.toString())), undoable = true,
                ))
            }
        }
        "$label تمام شد: $ok موفق، $bad ناموفق. پوشه $outName"
    }
}
