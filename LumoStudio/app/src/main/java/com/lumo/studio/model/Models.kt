package com.lumo.studio.model

import java.util.UUID

enum class Role { ORIGINAL, VERSION, UNSORTED }

enum class ThemeMode { SYSTEM, DARK, LIGHT }

enum class Accent { AURORA, CHAMPAGNE, ROSE }

/** برچسب نوع پوشه (قابلیت ۱: مدیریت چندپوشه‌ای). */
enum class FolderKind(val fa: String) {
    ORIGINALS("عکس‌های اصلی"),
    GEMINI("خروجی‌های Gemini"),
    DOWNLOADS("دانلودشده"),
    FLASH("فلش / حافظه خارجی"),
    OTHER("سایر"),
}

/** علامت‌های فایل (قابلیت ۹). */
enum class Mark(val fa: String, val glyph: String) {
    IMPORTANT("مهم", "❗"),
    REVIEW("بررسی شود", "👁"),
    APPROVED("تأییدشده", "✅"),
    PICKED("منتخب", "⭐"),
    PROBLEM("مشکل‌دار", "⚠"),
}

enum class DateRange(val fa: String) {
    ALL("همه"), TODAY("امروز"), WEEK("۷ روز اخیر"), MONTH("۳۰ روز اخیر")
}

enum class ZipMode(val fa: String) {
    NONE("بدون ZIP"), SINGLE("یک ZIP"), BY_COUNT("تقسیم بر اساس تعداد"), BY_SIZE("تقسیم بر اساس حجم")
}

enum class ExportScope(val fa: String) {
    BOTH("اصلی + خروجی‌ها"),
    ORIGINALS("فقط عکس‌های اصلی"),
    VERSIONS("فقط خروجی‌ها (Gemini)"),
    APPROVED("فقط گروه‌های تأییدشده"),
    BEST("فقط نسخه‌های منتخب"),
}

data class FavoriteFolder(val uri: String, val label: String)

/** الگوی نام‌گذاری ذخیره‌شده (قابلیت ۲۸). */
data class NamingPattern(val id: String, val name: String, val template: String)

fun defaultPatterns(): List<NamingPattern> = listOf(
    NamingPattern("p1", "شماره گروه - نقش", "{gi} - {role_fa} {n}"),
    NamingPattern("p2", "نام گروه - نقش", "{group} - {role_fa} {n}"),
    NamingPattern("p3", "گروه و تصویر", "گروه {gi} - تصویر {k}"),
)

/** تنظیمات سراسری برنامه (مستقل از پروژه). */
data class Settings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accent: Accent = Accent.AURORA,
    val favorites: List<FavoriteFolder> = emptyList(),
    val patterns: List<NamingPattern> = defaultPatterns(),
    val lockEnabled: Boolean = true,
    val lockThreshold: Int = 50,
    val notifications: Boolean = true,
)

/** تنظیمات اختصاصی هر پروژه (قابلیت ۳۷). */
data class ProjectSettings(
    val template: String = "{group}_{role}_{n}",
    val folderTemplate: String = "{gi} - {group}",
    val byGroup: Boolean = false,
    val includeUnsorted: Boolean = true,
    val scope: ExportScope = ExportScope.BOTH,
    val zipMode: ZipMode = ZipMode.NONE,
    val zipCount: Int = 200,
    val zipMaxMb: Int = 500,
    val persianRoles: Boolean = false,
    val convertFormat: String = "JPG",
    val imageQuality: Int = 90,
)

data class ProjectFolder(
    val id: String = UUID.randomUUID().toString(),
    val uri: String,
    val label: String,
    val kind: FolderKind = FolderKind.OTHER,
    val lastScan: Long = 0L,
    val lastSummary: String = "",
)

data class Photo(
    val uri: String,
    val name: String,
    val id: String = UUID.randomUUID().toString(),
    val width: Int = 0,
    val height: Int = 0,
    val rating: Int = 0,
    val best: Boolean = false,
    val folderId: String? = null,
    val parentUri: String? = null,
    val size: Long = 0L,
    val modified: Long = 0L,
    val hash: String = "",
    val marks: Set<Mark> = emptySet(),
    val categories: Set<String> = emptySet(),
    val reviewed: Boolean = false,
    val missing: Boolean = false,
    val trashed: Boolean = false,
) {
    val area: Long get() = width.toLong() * height
    val ext: String get() = name.substringAfterLast('.', "").lowercase()
    val baseName: String get() = name.substringBeforeLast('.')
    val canWrite: Boolean get() = !parentUri.isNullOrEmpty()
}

/**
 * One decision per photo. For ORIGINAL the groupId equals the photo's own id,
 * so a group id is stable across re-analysis (manual links never break).
 */
data class Assignment(
    val photoId: String,
    val groupId: String?,
    val role: Role,
    val score: Float = 0f,
    val gap: Float = 1f,
    val needsReview: Boolean = false,
    val manual: Boolean = false,
)

enum class OpType(val fa: String) {
    GROUP("گروه‌بندی"), RENAME("تغییر نام"), MOVE("انتقال"), COPY("کپی"), DEDUPE("حذف تکراری"),
    CONVERT("تبدیل فرمت"), COMPRESS("فشرده‌سازی"), EXPORT("خروجی"), ZIP("ZIP"), BACKUP("پشتیبان"),
    RESTORE("بازیابی"), SCAN("اسکن"), CATEGORY("دسته‌بندی"), MARK("علامت‌گذاری"), UNDO("بازگشت"), FOLDER("پوشه"),
}

/** یک گام قابل‌بازگشت. kind: RENAME | MOVE | TRASH | DELETE. */
data class UndoStep(
    val kind: String,
    val photoId: String = "",
    val a: String = "",
    val b: String = "",
    val c: String = "",
    val d: String = "",
)

data class OpEntry(
    val id: String = UUID.randomUUID().toString(),
    val time: Long = System.currentTimeMillis(),
    val type: OpType,
    val title: String,
    val details: List<String> = emptyList(),
    val steps: List<UndoStep> = emptyList(),
    val snapshot: String? = null,
    val undoable: Boolean = false,
    val undone: Boolean = false,
)

enum class ErrorKind(val fa: String) {
    UNREADABLE("فایل باز نمی‌شود"),
    CORRUPT("فایل ناقص یا خراب"),
    UNSUPPORTED("فرمت پشتیبانی نمی‌شود"),
    NO_SPACE("فضای کافی نیست"),
    NO_ACCESS("دسترسی قطع شده"),
    CONFLICT("تداخل نام"),
    OTHER("خطای دیگر"),
}

data class AppError(
    val id: String = UUID.randomUUID().toString(),
    val time: Long = System.currentTimeMillis(),
    val kind: ErrorKind,
    val subject: String,
    val message: String,
    val advice: String,
)

data class ZipInfo(val uri: String, val name: String, val bytes: Long)

/** سابقه‌ی خروجی‌ها (قابلیت ۳۱). */
data class ExportRecord(
    val id: String = UUID.randomUUID().toString(),
    val time: Long = System.currentTimeMillis(),
    val version: Int,
    val folderName: String,
    val folderUri: String,
    val files: Int,
    val bytes: Long,
    val zips: List<ZipInfo> = emptyList(),
    val summary: String = "",
)

data class Project(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "پروژه جدید",
    val photos: List<Photo> = emptyList(),
    val assignments: Map<String, Assignment> = emptyMap(),
    val sensitivity: Float = 0.5f,
    val folders: List<ProjectFolder> = emptyList(),
    val categories: List<String> = emptyList(),
    val settings: ProjectSettings = ProjectSettings(),
    val history: List<OpEntry> = emptyList(),
    val exports: List<ExportRecord> = emptyList(),
    val errors: List<AppError> = emptyList(),
    /** مقصد کپی → کلیدهای فایل‌هایی که قبلاً کپی شده‌اند (قابلیت ۵). */
    val copyLog: Map<String, Set<String>> = emptyMap(),
    val created: Long = System.currentTimeMillis(),
)

data class GroupView(
    val id: String,
    val original: Photo?,
    val versions: List<Photo>,
    val pending: Int,
    val bests: Int,
)

fun Project.livePhotos(): List<Photo> = photos.filter { !it.trashed }

fun Project.groups(): List<GroupView> {
    val byId = photos.filter { !it.trashed }.associateBy { it.id }
    return assignments.values
        .filter { it.groupId != null && it.role != Role.UNSORTED && byId.containsKey(it.photoId) }
        .groupBy { it.groupId!! }
        .map { (gid, members) ->
            val versions = members
                .filter { it.photoId != gid }
                .mapNotNull { byId[it.photoId] }
                .sortedBy { it.name }
            GroupView(
                id = gid,
                original = byId[gid],
                versions = versions,
                pending = members.count { it.needsReview && !it.manual },
                bests = members.count { byId[it.photoId]?.best == true },
            )
        }
        .sortedBy { it.original?.name ?: "" }
}

fun Project.unsorted(): List<Photo> = photos.filter { p ->
    if (p.trashed) return@filter false
    val a = assignments[p.id]
    a == null || a.groupId == null || a.role == Role.UNSORTED
}

/** Uncertain automatic matches, most ambiguous first. */
fun Project.reviewQueue(): List<Assignment> = assignments.values
    .filter { it.needsReview && !it.manual && it.groupId != null }
    .sortedBy { it.gap }

/** فیلتر ترکیبی (قابلیت ۷). */
data class FilterSpec(
    val query: String = "",
    val exts: Set<String> = emptySet(),
    val minSizeMb: Float = 0f,
    val minSidePx: Int = 0,
    val date: DateRange = DateRange.ALL,
    val onlyUngrouped: Boolean = false,
    val onlyUnreviewed: Boolean = false,
    val marks: Set<Mark> = emptySet(),
    val category: String? = null,
    val folderId: String? = null,
) {
    val active: Boolean get() = this != FilterSpec()
}

fun Project.roleOf(photoId: String): Role {
    val a = assignments[photoId]
    return when {
        a == null || a.groupId == null || a.role == Role.UNSORTED -> Role.UNSORTED
        a.groupId == photoId -> Role.ORIGINAL
        else -> Role.VERSION
    }
}
