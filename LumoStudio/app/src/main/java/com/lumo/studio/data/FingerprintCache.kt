package com.lumo.studio.data

import com.lumo.studio.engine.Fingerprint
import com.lumo.studio.model.Photo
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * ذخیره‌ی اثر انگشت تصاویر روی دیسک تا اسکن مجدد فقط فایل‌های جدید/تغییرکرده را بررسی کند
 * (قابلیت‌های ۲۰ و ۳۶).
 */
object FingerprintCache {

    class Entry(val size: Long, val modified: Long, val fp: Fingerprint)

    fun load(file: File): Map<String, Entry> = runCatching {
        val out = HashMap<String, Entry>()
        if (!file.exists()) return@runCatching out
        DataInputStream(BufferedInputStream(file.inputStream())).use { ins ->
            if (ins.readInt() != 1) return@runCatching out
            val n = ins.readInt()
            repeat(n) {
                val id = ins.readUTF()
                val size = ins.readLong()
                val mod = ins.readLong()
                val dh = ins.readLong()
                val ph = ins.readLong()
                val edges = FloatArray(256) { ins.readFloat() }
                out[id] = Entry(size, mod, Fingerprint(dh, ph, edges))
            }
        }
        out
    }.getOrDefault(emptyMap())

    fun save(file: File, photos: List<Photo>, prints: Map<String, Fingerprint>) {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            val rows = photos.filter { prints.containsKey(it.id) }
            DataOutputStream(BufferedOutputStream(tmp.outputStream())).use { out ->
                out.writeInt(1)
                out.writeInt(rows.size)
                for (p in rows) {
                    val fp = prints.getValue(p.id)
                    out.writeUTF(p.id)
                    out.writeLong(p.size)
                    out.writeLong(p.modified)
                    out.writeLong(fp.dHash)
                    out.writeLong(fp.pHash)
                    for (f in fp.edges) out.writeFloat(f)
                }
            }
            if (!tmp.renameTo(file)) {
                file.writeBytes(tmp.readBytes())
                tmp.delete()
            }
        }
    }
}
