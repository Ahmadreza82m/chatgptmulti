package com.lumo.studio.viewmodel

import androidx.compose.ui.graphics.ImageBitmap
import com.lumo.studio.engine.RenameItem
import com.lumo.studio.model.*
import com.lumo.studio.data.ProjectInfo

enum class Screen {
    STUDIO, REVIEW, COMPARE, EXPORT, SETTINGS,
    HUB, LIBRARY, FOLDERS, RENAME, MOVE, COPY, FOLDER_COMPARE, DUPLICATES, PROCESS,
    DASHBOARD, ERRORS, HEALTH, HISTORY, EXPORTS, PROJECTS, BACKUP, STORAGE,
}

fun Screen.parent(): Screen = when (this) {
    Screen.STUDIO -> Screen.STUDIO
    Screen.HUB -> Screen.STUDIO
    Screen.RENAME, Screen.MOVE, Screen.COPY -> Screen.LIBRARY
    Screen.LIBRARY, Screen.FOLDERS, Screen.FOLDER_COMPARE, Screen.DUPLICATES, Screen.PROCESS,
    Screen.DASHBOARD, Screen.ERRORS, Screen.HEALTH, Screen.HISTORY, Screen.EXPORTS,
    Screen.PROJECTS, Screen.BACKUP, Screen.STORAGE -> Screen.HUB
    else -> Screen.STUDIO
}

enum class Filter { ALL, REVIEW, UNSORTED, BEST }

enum class JobStatus { RUNNING, PAUSED, DONE, CANCELLED, FAILED }

/** وضعیت عملیات پس‌زمینه (قابلیت‌های ۲۳ و ۲۴). */
data class JobState(
    val id: String,
    val title: String,
    val total: Int,
    val done: Int = 0,
    val failed: Int = 0,
    val status: JobStatus = JobStatus.RUNNING,
    val note: String = "",
    val canPause: Boolean = true,
) {
    val remaining: Int get() = (total - done - failed).coerceAtLeast(0)
    val finished: Boolean get() = status == JobStatus.DONE || status == JobStatus.CANCELLED || status == JobStatus.FAILED
}

/** صفحه‌ی تأیید عملیات حساس (قابلیت ۳۵). */
data class ConfirmInfo(val title: String, val lines: List<String>)

enum class MoveMode(val fa: String) {
    GROUP("به پوشه‌ی هر گروه"),
    EXTENSION("به پوشه‌ی هر فرمت"),
    MARK("بر اساس علامت"),
    CATEGORY("بر اساس دسته‌بندی"),
    SINGLE("همه به یک پوشه"),
}

data class MoveRule(
    val mode: MoveMode = MoveMode.GROUP,
    val mark: Mark = Mark.APPROVED,
    val category: String = "",
    val fixedName: String = "Moved",
)

enum class MoveStatus { OK, NO_WRITE, SKIP }

data class MoveItem(val photoId: String, val name: String, val folder: String, val status: MoveStatus)

enum class CopyPolicy(val fa: String) { SKIP("رد شدن از فایل هم‌نام"), KEEP_BOTH("نگه‌داشتن هر دو (نام جدید)") }

data class CopyPreview(
    val total: Int,
    val alreadyCopied: Int,
    val sameInDest: Int,
    val conflicts: Int,
    val fresh: Int,
    val noAccess: Int,
)

data class NamePair(val a: String, val b: String)

data class FolderCompareResult(
    val aLabel: String,
    val bLabel: String,
    val same: List<String>,
    val renamed: List<NamePair>,
    val sameNameDiff: List<String>,
    val onlyA: List<String>,
    val onlyB: List<String>,
    val onlyAIds: List<String>,
)

data class DupCluster(val hash: String, val ids: List<String>, val keep: String)

data class HealthItem(val level: Int, val title: String, val detail: String)

data class ProcessPreview(
    val photoName: String,
    val origBytes: Long,
    val newBytes: Long,
    val before: ImageBitmap?,
    val after: ImageBitmap?,
    val totalOrigBytes: Long,
)

data class ExportPreview(
    val lines: List<String>,
    val files: Int,
    val bytes: Long,
    val renamed: Int,
    val missing: Int,
    val freeBytes: Long,
)

data class RecoveryInfo(val type: String, val title: String, val done: Int, val total: Int)

data class StorageInfo(
    val originals: Long,
    val outputs: Long,
    val zips: Long,
    val projectData: Long,
    val free: Long,
    val totalSpace: Long,
)

data class UiState(
    val project: Project = Project(),
    val settings: Settings = Settings(),
    val projects: List<ProjectInfo> = emptyList(),
    val screen: Screen = Screen.STUDIO,
    val filter: Filter = Filter.ALL,
    val analyzing: Boolean = false,
    val progress: Float = 0f,
    val compareGroup: String? = null,
    val reviewIndex: Int = 0,
    val message: String? = null,
    val job: JobState? = null,
    val confirm: ConfirmInfo? = null,
    val selection: Set<String> = emptySet(),
    val libFilter: FilterSpec = FilterSpec(),
    val renamePreview: List<RenameItem> = emptyList(),
    val movePreview: List<MoveItem> = emptyList(),
    val copyPreview: CopyPreview? = null,
    val folderCompare: FolderCompareResult? = null,
    val dupes: List<DupCluster> = emptyList(),
    val dupesScanned: Boolean = false,
    val health: List<HealthItem> = emptyList(),
    val compressPreview: ProcessPreview? = null,
    val exportPreview: ExportPreview? = null,
    val recovery: RecoveryInfo? = null,
)
