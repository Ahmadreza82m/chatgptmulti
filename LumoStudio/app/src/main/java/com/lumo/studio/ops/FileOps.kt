package com.lumo.studio.ops

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.webkit.MimeTypeMap
import com.lumo.studio.model.ErrorKind
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.Locale

/** لایه‌ی نازک روی Storage Access Framework. هیچ‌چیز بدون اجازه بازنویسی نمی‌شود. */
object FileOps {

    class Child(
        val uri: Uri,
        val name: String,
        val mime: String,
        val size: Long,
        val modified: Long,
    ) {
        val isDir: Boolean get() = mime == Document.MIME_TYPE_DIR
    }

    class Found(val child: Child, val parent: Uri)

    private val COLS = arrayOf(
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE,
        Document.COLUMN_SIZE,
        Document.COLUMN_LAST_MODIFIED,
    )

    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")

    /** هر رشته‌ی Uri (درخت یا سند) را به Uri سند تبدیل می‌کند. */
    fun asDoc(s: String): Uri {
        val u = Uri.parse(s)
        return if (u.pathSegments.contains("document")) u
        else DocumentsContract.buildDocumentUriUsingTree(u, DocumentsContract.getTreeDocumentId(u))
    }

    fun mimeFor(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    fun isImage(c: Child): Boolean =
        c.mime.startsWith("image/") || c.name.substringAfterLast('.', "").lowercase(Locale.US) in IMAGE_EXT

    fun children(ctx: Context, parentDoc: Uri): List<Child> {
        val out = ArrayList<Child>()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            parentDoc, DocumentsContract.getDocumentId(parentDoc)
        )
        ctx.contentResolver.query(childrenUri, COLS, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                out += Child(
                    uri = DocumentsContract.buildDocumentUriUsingTree(parentDoc, id),
                    name = c.getString(1) ?: id,
                    mime = c.getString(2) ?: "",
                    size = if (c.isNull(3)) 0L else c.getLong(3),
                    modified = if (c.isNull(4)) 0L else c.getLong(4),
                )
            }
        }
        return out
    }

    fun stat(ctx: Context, doc: Uri): Child? = try {
        ctx.contentResolver.query(doc, COLS, null, null, null)?.use { c ->
            if (!c.moveToFirst()) null
            else Child(
                uri = doc,
                name = c.getString(1) ?: "",
                mime = c.getString(2) ?: "",
                size = if (c.isNull(3)) 0L else c.getLong(3),
                modified = if (c.isNull(4)) 0L else c.getLong(4),
            )
        }
    } catch (e: Exception) {
        null
    }

    /** پیمایش بازگشتی؛ پوشه‌های سطل و خروجی‌های Lumo نادیده گرفته می‌شوند. */
    fun walk(ctx: Context, rootDoc: Uri, maxDepth: Int = 8, imagesOnly: Boolean = true): List<Found> {
        val out = ArrayList<Found>()
        val stack = ArrayDeque<Pair<Uri, Int>>()
        stack.addLast(rootDoc to 0)
        while (stack.isNotEmpty()) {
            val (dir, depth) = stack.removeLast()
            for (c in children(ctx, dir)) {
                if (c.isDir) {
                    if (depth < maxDepth && !c.name.startsWith("_Lumo") && !c.name.startsWith("Lumo_")) {
                        stack.addLast(c.uri to depth + 1)
                    }
                } else if (!imagesOnly || isImage(c)) {
                    out += Found(c, dir)
                }
            }
        }
        return out
    }

    /** همه‌ی فایل‌های یک پوشه (بدون فیلتر Lumo_) برای ساخت دوباره‌ی ZIP. */
    fun walkAll(ctx: Context, rootDoc: Uri, prefix: String = ""): List<Pair<String, Child>> {
        val out = ArrayList<Pair<String, Child>>()
        for (c in children(ctx, rootDoc)) {
            if (c.isDir) out += walkAll(ctx, c.uri, prefix + c.name + "/")
            else out += (prefix + c.name) to c
        }
        return out
    }

    fun createDir(ctx: Context, parentDoc: Uri, name: String): Uri? =
        DocumentsContract.createDocument(ctx.contentResolver, parentDoc, Document.MIME_TYPE_DIR, name)

    fun createFile(ctx: Context, parentDoc: Uri, name: String, mime: String = mimeFor(name)): Uri? =
        DocumentsContract.createDocument(ctx.contentResolver, parentDoc, mime, name)

    fun rename(ctx: Context, doc: Uri, newName: String): Uri? =
        DocumentsContract.renameDocument(ctx.contentResolver, doc, newName)

    fun move(ctx: Context, doc: Uri, fromParent: Uri, toParent: Uri): Uri? =
        DocumentsContract.moveDocument(ctx.contentResolver, doc, fromParent, toParent)

    fun delete(ctx: Context, doc: Uri): Boolean = try {
        DocumentsContract.deleteDocument(ctx.contentResolver, doc)
    } catch (e: Exception) {
        false
    }

    fun copyStream(ctx: Context, src: Uri, dst: Uri) {
        val r = ctx.contentResolver
        val input = r.openInputStream(src) ?: throw FileNotFoundException(src.toString())
        input.use { i ->
            val output = r.openOutputStream(dst, "wt") ?: throw FileNotFoundException(dst.toString())
            output.use { o -> i.copyTo(o, 64 * 1024) }
        }
    }

    fun sha256(ctx: Context, uri: Uri): String? = try {
        val md = MessageDigest.getInstance("SHA-256")
        val ins = ctx.contentResolver.openInputStream(uri)
        if (ins == null) {
            null
        } else {
            ins.use {
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = it.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }
    } catch (e: Exception) {
        null
    }

    fun classify(e: Throwable): ErrorKind {
        val m = (e.message ?: "").lowercase(Locale.US)
        return when {
            "enospc" in m || "no space" in m -> ErrorKind.NO_SPACE
            e is SecurityException || "permission" in m -> ErrorKind.NO_ACCESS
            e is FileNotFoundException -> ErrorKind.UNREADABLE
            e is UnsupportedOperationException -> ErrorKind.UNSUPPORTED
            else -> ErrorKind.OTHER
        }
    }

    fun sanitize(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().trimEnd('.').ifEmpty { "file" }
}

/** فهرست نام‌های هر پوشه، فقط یک بار خوانده می‌شود تا بررسی تداخل ارزان باشد. */
class DirIndex(private val ctx: Context) {
    private val map = HashMap<String, MutableMap<String, FileOps.Child>>()

    fun entries(parent: Uri): MutableMap<String, FileOps.Child> =
        map.getOrPut(parent.toString()) {
            runCatching { FileOps.children(ctx, parent) }.getOrDefault(emptyList())
                .associateBy { it.name }.toMutableMap()
        }

    fun find(parent: Uri, name: String): FileOps.Child? = entries(parent)[name]

    fun add(parent: Uri, child: FileOps.Child) { entries(parent)[child.name] = child }

    fun remove(parent: Uri, name: String) { entries(parent).remove(name) }

    fun unique(parent: Uri, name: String): String {
        if (find(parent, name) == null) return name
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var n = 2
        while (find(parent, "${base}_$n$ext") != null) n++
        return "${base}_$n$ext"
    }

    /** پوشه‌ی فرزند را می‌یابد یا می‌سازد. */
    fun ensureDir(parent: Uri, name: String): Uri? {
        find(parent, name)?.let { if (it.isDir) return it.uri }
        val u = runCatching { FileOps.createDir(ctx, parent, name) }.getOrNull() ?: return null
        add(parent, FileOps.Child(u, name, DocumentsContract.Document.MIME_TYPE_DIR, 0L, 0L))
        return u
    }
}
