package com.lumo.studio.export

import android.content.Context
import android.net.Uri
import com.lumo.studio.model.ZipInfo
import com.lumo.studio.model.ZipMode
import com.lumo.studio.ops.DirIndex
import com.lumo.studio.ops.FileOps
import java.io.BufferedOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** قابلیت ۲۶: ساخت یک یا چند ZIP با محدودیت تعداد یا حجم. */
object ZipBuilder {

    class Entry(val path: String, val uri: Uri, val size: Long)

    fun partition(entries: List<Entry>, mode: ZipMode, count: Int, maxMb: Int): List<List<Entry>> {
        if (entries.isEmpty()) return emptyList()
        return when (mode) {
            ZipMode.NONE -> emptyList()
            ZipMode.SINGLE -> listOf(entries)
            ZipMode.BY_COUNT -> entries.chunked(count.coerceAtLeast(1))
            ZipMode.BY_SIZE -> {
                val limit = maxMb.coerceAtLeast(1) * 1024L * 1024L
                val parts = ArrayList<MutableList<Entry>>()
                var cur = ArrayList<Entry>()
                var sum = 0L
                for (e in entries) {
                    if (cur.isNotEmpty() && sum + e.size > limit) {
                        parts += cur; cur = ArrayList(); sum = 0L
                    }
                    cur += e; sum += e.size
                }
                if (cur.isNotEmpty()) parts += cur
                parts
            }
        }
    }

    suspend fun build(
        ctx: Context,
        outDoc: Uri,
        baseName: String,
        entries: List<Entry>,
        manifest: ByteArray?,
        mode: ZipMode,
        count: Int,
        maxMb: Int,
        idx: DirIndex,
        tick: suspend () -> Unit,
    ): List<ZipInfo> {
        val parts = partition(entries, mode, count, maxMb)
        val result = ArrayList<ZipInfo>()
        parts.forEachIndexed { i, part ->
            val name = idx.unique(
                outDoc,
                if (parts.size == 1) "$baseName.zip" else "${baseName}_part${(i + 1).toString().padStart(2, '0')}.zip",
            )
            val zipUri = FileOps.createFile(ctx, outDoc, name, "application/zip") ?: return@forEachIndexed
            val os = ctx.contentResolver.openOutputStream(zipUri, "wt") ?: return@forEachIndexed
            ZipOutputStream(BufferedOutputStream(os)).use { zos ->
                zos.setLevel(1)
                if (i == 0 && manifest != null) {
                    zos.putNextEntry(ZipEntry("manifest.csv"))
                    zos.write(manifest)
                    zos.closeEntry()
                }
                for (e in part) {
                    tick()
                    zos.putNextEntry(ZipEntry(e.path))
                    ctx.contentResolver.openInputStream(e.uri)?.use { it.copyTo(zos, 64 * 1024) }
                    zos.closeEntry()
                }
            }
            val st = FileOps.stat(ctx, zipUri)
            idx.add(outDoc, FileOps.Child(zipUri, name, "application/zip", st?.size ?: 0L, st?.modified ?: 0L))
            result += ZipInfo(zipUri.toString(), name, st?.size ?: 0L)
        }
        return result
    }
}
