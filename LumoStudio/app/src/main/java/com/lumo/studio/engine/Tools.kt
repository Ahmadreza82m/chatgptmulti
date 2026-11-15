package com.lumo.studio.engine

import com.lumo.studio.model.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** قابلیت ۷: فیلترهای ترکیبی. */
object FilterEngine {
    private fun normExt(e: String) = if (e == "jpeg") "jpg" else e

    fun apply(project: Project, f: FilterSpec, now: Long = System.currentTimeMillis()): List<Photo> {
        val minDate = when (f.date) {
            DateRange.ALL -> 0L
            DateRange.TODAY -> now - 86_400_000L
            DateRange.WEEK -> now - 7 * 86_400_000L
            DateRange.MONTH -> now - 30 * 86_400_000L
        }
        val exts = f.exts.map(::normExt).toSet()
        return project.photos.filter { p ->
            if (p.trashed) return@filter false
            if (f.query.isNotBlank() && !p.name.contains(f.query.trim(), ignoreCase = true)) return@filter false
            if (exts.isNotEmpty() && normExt(p.ext) !in exts) return@filter false
            if (f.minSizeMb > 0f && p.size < (f.minSizeMb * 1024 * 1024).toLong()) return@filter false
            if (f.minSidePx > 0 && maxOf(p.width, p.height) < f.minSidePx) return@filter false
            if (f.date != DateRange.ALL && (p.modified <= 0L || p.modified < minDate)) return@filter false
            if (f.onlyUngrouped && project.roleOf(p.id) != Role.UNSORTED) return@filter false
            if (f.onlyUnreviewed && p.reviewed) return@filter false
            if (f.marks.isNotEmpty() && p.marks.none { it in f.marks }) return@filter false
            if (f.category != null && f.category !in p.categories) return@filter false
            if (f.folderId != null && p.folderId != f.folderId) return@filter false
            true
        }.sortedBy { it.name.lowercase(Locale.ROOT) }
    }
}

/** قابلیت ۳: قوانین تغییر نام گروهی. */
enum class NumberMode(val fa: String) { NONE("بدون شماره"), REPLACE("جایگزین نام"), APPEND("اضافه به انتهای نام") }

data class RenameRules(
    val removeText: String = "",
    val find: String = "",
    val replace: String = "",
    val prefix: String = "",
    val suffix: String = "",
    val numberMode: NumberMode = NumberMode.NONE,
    val start: Int = 1,
    val digits: Int = 3,
    val numberPrefix: String = "",
    val numberSep: String = "_",
)

enum class RenameStatus { OK, UNCHANGED, CONFLICT, NO_WRITE }

data class RenameItem(
    val photoId: String,
    val oldName: String,
    val newName: String,
    val parent: String,
    val status: RenameStatus,
    val srcUri: String,
)

object Renamer {
    fun transform(base: String, r: RenameRules, n: Int): String {
        var s = base
        if (r.removeText.isNotEmpty()) s = s.replace(r.removeText, "")
        if (r.find.isNotEmpty()) s = s.replace(r.find, r.replace)
        val num = r.numberPrefix + n.toString().padStart(r.digits.coerceIn(1, 8), '0')
        s = when (r.numberMode) {
            NumberMode.NONE -> s
            NumberMode.REPLACE -> num
            NumberMode.APPEND -> if (s.isEmpty()) num else s + r.numberSep + num
        }
        return r.prefix + s + r.suffix
    }

    /** [taken] نام‌های موجود هر پوشه را برمی‌گرداند. */
    fun plan(photos: List<Photo>, rules: RenameRules, taken: (String) -> Set<String>): List<RenameItem> {
        val sorted = photos.sortedBy { it.name.lowercase(Locale.ROOT) }
        val renaming = sorted.groupBy { it.parentUri.orEmpty() }.mapValues { e -> e.value.map { it.name }.toSet() }
        val assigned = HashMap<String, MutableSet<String>>()
        return sorted.mapIndexed { i, p ->
            val parent = p.parentUri.orEmpty()
            if (parent.isEmpty()) {
                return@mapIndexed RenameItem(p.id, p.name, p.name, parent, RenameStatus.NO_WRITE, p.uri)
            }
            val base = transform(p.baseName, rules, rules.start + i)
            val ext = p.name.substringAfterLast('.', "")
            val newName = FileSafe.clean(if (ext.isEmpty()) base else "$base.$ext")
            val busy = (taken(parent) - (renaming[parent] ?: emptySet())) + (assigned[parent] ?: emptySet())
            val status = when {
                newName == p.name -> RenameStatus.UNCHANGED
                base.isBlank() || newName in busy -> RenameStatus.CONFLICT
                else -> RenameStatus.OK
            }
            if (status == RenameStatus.OK) assigned.getOrPut(parent) { HashSet() }.add(newName)
            RenameItem(p.id, p.name, newName, parent, status, p.uri)
        }
    }
}

object FileSafe {
    fun clean(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().trimEnd('.').ifEmpty { "file" }
}

/** قابلیت‌های ۲۸ و ۲۹: الگوهای نام‌گذاری خروجی. */
object NameTemplate {
    class Ctx(
        val gi: Int,
        val group: String,
        val role: Role,
        val name: String,
        val n: Int,
        val k: Int,
        val persianRoles: Boolean,
    )

    private fun role(r: Role, fa: Boolean) = when (r) {
        Role.ORIGINAL -> if (fa) "اصلی" else "original"
        Role.VERSION -> if (fa) "نسخه" else "version"
        Role.UNSORTED -> if (fa) "بدون‌گروه" else "unsorted"
    }

    private fun roleLong(r: Role) = when (r) {
        Role.ORIGINAL -> "عکس اصلی"
        Role.VERSION -> "نسخه"
        Role.UNSORTED -> "بدون گروه"
    }

    fun render(template: String, c: Ctx): String {
        val date = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        var s = template.replace(".{ext}", "").replace("{ext}", "")
        s = s.replace("{gi}", c.gi.toString().padStart(3, '0'))
            .replace("{group}", c.group)
            .replace("{name}", c.name)
            .replace("{role_fa}", roleLong(c.role))
            .replace("{role}", role(c.role, c.persianRoles))
            .replace("{n}", if (c.n > 0) c.n.toString().padStart(2, '0') else "")
            .replace("{k}", c.k.toString().padStart(2, '0'))
            .replace("{date}", date)
        s = s.replace(Regex("\\s+"), " ").trim().trimEnd('_', '-', ' ')
        return FileSafe.clean(s)
    }
}

data class DashStats(
    val originals: Int,
    val outputs: Int,
    val groups: Int,
    val groupsDone: Int,
    val unsorted: Int,
    val pending: Int,
    val completion: Int,
    val bytes: Long,
    val processed: Int,
    val missing: Int,
    val total: Int,
)

/** قابلیت ۱۷: آمار داشبورد. */
object Stats {
    fun dash(p: Project): DashStats {
        val g = p.groups()
        val live = p.livePhotos()
        val done = g.count { it.versions.isNotEmpty() && it.pending == 0 }
        return DashStats(
            originals = g.size,
            outputs = g.sumOf { it.versions.size },
            groups = g.size,
            groupsDone = done,
            unsorted = p.unsorted().size,
            pending = p.reviewQueue().size,
            completion = if (g.isEmpty()) 0 else done * 100 / g.size,
            bytes = live.sumOf { it.size },
            processed = live.count { it.width > 0 },
            missing = live.count { it.missing },
            total = live.size,
        )
    }

    fun fmtBytes(b: Long): String = when {
        b >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", b / (1024.0 * 1024 * 1024))
        b >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", b / (1024.0 * 1024))
        b >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", b / 1024.0)
        else -> "$b B"
    }
}
