package com.lumo.studio.ops

import android.content.Context
import android.net.Uri
import com.lumo.studio.model.ErrorKind
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class TaskKind { COPY, MOVE, RENAME }

/**
 * یک کار کوچک فایلی. همه‌ی کارها «تکرارپذیر» هستند: اگر نتیجه از قبل وجود داشته باشد،
 * بدون کپی دوباره موفق گزارش می‌شوند؛ پایه‌ی سیستم بازیابی اضطراری (قابلیت ۴۰).
 */
data class Task(
    val kind: TaskKind,
    val srcUri: String,
    val srcParent: String,
    val dstParent: String,
    val dstName: String,
    val photoId: String = "",
    val key: String = "",
)

data class TaskResult(
    val ok: Boolean,
    val uri: String = "",
    val parent: String = "",
    val name: String = "",
    val skipped: Boolean = false,
    val kind: ErrorKind? = null,
    val error: String = "",
    val size: Long = 0L,
)

class TaskExecutor(private val ctx: Context) {

    val idx = DirIndex(ctx)

    private fun fail(kind: ErrorKind, msg: String) = TaskResult(false, kind = kind, error = msg)

    fun run(t: Task): TaskResult = try {
        when (t.kind) {
            TaskKind.COPY -> copy(t)
            TaskKind.MOVE -> move(t)
            TaskKind.RENAME -> rename(t)
        }
    } catch (e: Exception) {
        fail(FileOps.classify(e), e.message ?: "خطای نامشخص")
    }

    private fun copy(t: Task): TaskResult {
        val src = Uri.parse(t.srcUri)
        val dstParent = FileOps.asDoc(t.dstParent)
        val st = FileOps.stat(ctx, src) ?: return fail(ErrorKind.UNREADABLE, "فایل مبدأ پیدا نشد یا دسترسی ندارد")
        val ex = idx.find(dstParent, t.dstName)
        if (ex != null) {
            return if (!ex.isDir && st.size > 0 && ex.size == st.size) {
                TaskResult(true, ex.uri.toString(), t.dstParent, t.dstName, skipped = true, size = st.size)
            } else {
                fail(ErrorKind.CONFLICT, "فایلی با همین نام و محتوای متفاوت در مقصد هست")
            }
        }
        val dst = FileOps.createFile(ctx, dstParent, t.dstName)
            ?: return fail(ErrorKind.NO_ACCESS, "ساخت فایل در مقصد ممکن نشد")
        try {
            FileOps.copyStream(ctx, src, dst)
        } catch (e: Exception) {
            FileOps.delete(ctx, dst)
            return fail(FileOps.classify(e), e.message ?: "خطا در نوشتن فایل")
        }
        val made = FileOps.stat(ctx, dst)
        idx.add(dstParent, FileOps.Child(dst, t.dstName, made?.mime ?: "", made?.size ?: st.size, made?.modified ?: 0L))
        return TaskResult(true, dst.toString(), t.dstParent, t.dstName, size = st.size)
    }

    private fun move(t: Task): TaskResult {
        val src = Uri.parse(t.srcUri)
        val dstParent = FileOps.asDoc(t.dstParent)
        val srcParent = FileOps.asDoc(t.srcParent)
        val st = FileOps.stat(ctx, src)
        if (st == null) {
            // احتمالاً قبلاً منتقل شده (ادامه‌ی عملیات پس از وقفه)
            val done = idx.find(dstParent, t.dstName)
            return if (done != null) TaskResult(true, done.uri.toString(), t.dstParent, t.dstName, skipped = true)
            else fail(ErrorKind.UNREADABLE, "فایل مبدأ پیدا نشد")
        }
        if (t.srcParent == t.dstParent) {
            if (st.name == t.dstName) return TaskResult(true, t.srcUri, t.dstParent, st.name, skipped = true)
            val r = FileOps.rename(ctx, src, t.dstName) ?: return fail(ErrorKind.OTHER, "تغییر نام ممکن نشد")
            return TaskResult(true, r.toString(), t.dstParent, t.dstName)
        }
        val finalName = idx.unique(dstParent, t.dstName)
        var moved: Uri? = runCatching { FileOps.move(ctx, src, srcParent, dstParent) }.getOrNull()
        if (moved == null) {
            // فال‌بک: کپی + حذف مبدأ
            val dst = FileOps.createFile(ctx, dstParent, finalName)
                ?: return fail(ErrorKind.NO_ACCESS, "ساخت فایل در مقصد ممکن نشد")
            try {
                FileOps.copyStream(ctx, src, dst)
            } catch (e: Exception) {
                FileOps.delete(ctx, dst)
                return fail(FileOps.classify(e), e.message ?: "خطا در کپی")
            }
            if (!FileOps.delete(ctx, src)) {
                FileOps.delete(ctx, dst)
                return fail(ErrorKind.NO_ACCESS, "حذف فایل مبدأ مجاز نبود؛ انتقال لغو شد")
            }
            idx.remove(srcParent, st.name)
            idx.add(dstParent, FileOps.Child(dst, finalName, st.mime, st.size, st.modified))
            return TaskResult(true, dst.toString(), t.dstParent, finalName, size = st.size)
        }
        var curName = FileOps.stat(ctx, moved)?.name ?: st.name
        if (curName != finalName) {
            val r = runCatching { FileOps.rename(ctx, moved!!, finalName) }.getOrNull()
            if (r != null) { moved = r; curName = finalName }
        }
        idx.remove(srcParent, st.name)
        idx.add(dstParent, FileOps.Child(moved!!, curName, st.mime, st.size, st.modified))
        return TaskResult(true, moved.toString(), t.dstParent, curName, size = st.size)
    }

    private fun rename(t: Task): TaskResult {
        val src = Uri.parse(t.srcUri)
        val parent = FileOps.asDoc(t.dstParent)
        val st = FileOps.stat(ctx, src)
        if (st == null) {
            val done = idx.find(parent, t.dstName)
            return if (done != null) TaskResult(true, done.uri.toString(), t.dstParent, t.dstName, skipped = true)
            else fail(ErrorKind.UNREADABLE, "فایل پیدا نشد")
        }
        if (st.name == t.dstName) return TaskResult(true, t.srcUri, t.dstParent, st.name, skipped = true)
        if (idx.find(parent, t.dstName) != null) return fail(ErrorKind.CONFLICT, "نام «${t.dstName}» از قبل وجود دارد")
        val r = FileOps.rename(ctx, src, t.dstName) ?: return fail(ErrorKind.OTHER, "تغییر نام ممکن نشد")
        idx.remove(parent, st.name)
        idx.add(parent, FileOps.Child(r, t.dstName, st.mime, st.size, st.modified))
        return TaskResult(true, r.toString(), t.dstParent, t.dstName, size = st.size)
    }
}

/** دفترچه‌ی عملیات روی دیسک؛ اگر برنامه وسط کار بسته شود، از اینجا ادامه پیدا می‌کند. */
class Journal(private val file: File) {
    var type: String = ""
    var title: String = ""
    var meta: String = ""
    var tasks: List<Task> = emptyList()
    val done = HashSet<Int>()

    fun begin(type: String, title: String, tasks: List<Task>, meta: String = "") {
        this.type = type; this.title = title; this.tasks = tasks; this.meta = meta
        done.clear()
        save()
    }

    @Synchronized
    fun save() {
        val o = JSONObject()
        o.put("type", type); o.put("title", title); o.put("meta", meta)
        o.put("done", JSONArray(done.toList()))
        o.put("tasks", JSONArray().apply {
            tasks.forEach {
                put(JSONObject().put("k", it.kind.name).put("s", it.srcUri).put("sp", it.srcParent)
                    .put("dp", it.dstParent).put("dn", it.dstName).put("p", it.photoId).put("key", it.key))
            }
        })
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
    }

    fun clear() { file.delete() }

    companion object {
        fun load(file: File): Journal? = runCatching {
            if (!file.exists()) return@runCatching null
            val o = JSONObject(file.readText())
            val j = Journal(file)
            j.type = o.getString("type"); j.title = o.optString("title"); j.meta = o.optString("meta")
            val d = o.optJSONArray("done") ?: JSONArray()
            for (i in 0 until d.length()) j.done += d.getInt(i)
            val a = o.getJSONArray("tasks")
            j.tasks = (0 until a.length()).map {
                val t = a.getJSONObject(it)
                Task(TaskKind.valueOf(t.getString("k")), t.getString("s"), t.getString("sp"), t.getString("dp"),
                    t.getString("dn"), t.optString("p"), t.optString("key"))
            }
            j
        }.getOrNull()
    }
}
