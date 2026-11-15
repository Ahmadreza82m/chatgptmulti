package com.lumo.studio.viewmodel

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.lumo.studio.model.*
import com.lumo.studio.ops.FileOps
import java.util.UUID

internal class ScanDiff(
    val project: Project,
    val added: Int,
    val changed: Int,
    val missing: Int,
    val relinked: Int,
    val changedIds: Set<String>,
)

/**
 * قابلیت‌های ۱، ۳۶ و ۳۴: ادغام نتیجه‌ی پیمایش یک پوشه با پروژه.
 * فقط فایل‌های جدید/تغییرکرده/ناپدیدشده مشخص می‌شوند؛ فایل‌های ناپدیدشده‌ی هم‌نام با فایل جدید دوباره وصل می‌شوند.
 */
internal fun mergeScan(p: Project, folder: ProjectFolder, found: List<FileOps.Found>): ScanDiff {
    val byUri = p.photos.associateBy { it.uri }
    val out = LinkedHashMap<String, Photo>()
    p.photos.forEach { out[it.id] = it }
    val pool = p.photos.filter { it.missing && !it.trashed }.groupBy { it.name }
        .mapValues { it.value.toMutableList() }.toMutableMap()
    val folderIds = p.folders.map { it.id }.toSet()
    val seen = HashSet<String>()
    val changedIds = HashSet<String>()
    var added = 0; var changed = 0; var relinked = 0

    for (f in found) {
        val c = f.child
        val uri = c.uri.toString()
        val parent = f.parent.toString()
        seen += uri
        val known = byUri[uri]
        if (known != null) {
            val sizeChanged = known.size > 0 && c.size > 0 && known.size != c.size
            val timeChanged = known.modified > 0 && c.modified > 0 && known.modified != c.modified
            val fid = if (known.folderId != null && known.folderId in folderIds) known.folderId else folder.id
            if (sizeChanged || timeChanged) {
                out[known.id] = known.copy(
                    size = c.size, modified = c.modified, hash = "", width = 0, height = 0,
                    missing = false, folderId = fid, parentUri = parent,
                )
                changedIds += known.id
                changed++
            } else {
                out[known.id] = known.copy(
                    missing = false, folderId = fid, parentUri = parent,
                    size = if (known.size == 0L) c.size else known.size,
                    modified = if (known.modified == 0L) c.modified else known.modified,
                )
            }
            continue
        }
        val cand = pool[c.name]?.firstOrNull { it.size == 0L || c.size == 0L || it.size == c.size }
        if (cand != null) {
            pool[c.name]?.remove(cand)
            out[cand.id] = cand.copy(
                uri = uri, parentUri = parent, folderId = folder.id, size = c.size, modified = c.modified, missing = false,
            )
            relinked++
        } else {
            val id = UUID.nameUUIDFromBytes(uri.toByteArray()).toString()
            if (out.containsKey(id)) continue
            out[id] = Photo(
                uri = uri, name = c.name, id = id, folderId = folder.id, parentUri = parent,
                size = c.size, modified = c.modified,
            )
            added++
        }
    }

    var missing = 0
    for ((id, ph) in out.toList()) {
        if (ph.folderId == folder.id && !ph.trashed && ph.uri !in seen && !ph.missing) {
            out[id] = ph.copy(missing = true)
            missing++
        }
    }
    val summary = "جدید: $added · تغییرکرده: $changed · ناپدیدشده: $missing · وصل‌شده‌ی مجدد: $relinked"
    val folders = p.folders.map {
        if (it.id == folder.id) it.copy(lastScan = System.currentTimeMillis(), lastSummary = summary) else it
    }
    return ScanDiff(p.copy(photos = out.values.toList(), folders = folders), added, changed, missing, relinked, changedIds)
}

fun StudioViewModel.addFolder(treeUri: Uri, kind: FolderKind) {
    grantTree(treeUri)
    val s = treeUri.toString()
    if (current.project.folders.any { it.uri == s }) { toast("این پوشه قبلاً اضافه شده است."); return }
    val label = DocumentFile.fromTreeUri(ctx, treeUri)?.name ?: "پوشه"
    val f = ProjectFolder(uri = s, label = label, kind = kind)
    updateProject { it.copy(folders = it.folders + f) }
    scanFolders(listOf(f.id))
}

fun StudioViewModel.rescanAll() = scanFolders(current.project.folders.map { it.id })

fun StudioViewModel.scanFolders(ids: List<String>) {
    if (ids.isEmpty()) { toast("هنوز پوشه‌ای اضافه نشده است."); return }
    startJob("اسکن هوشمند پوشه‌ها", ids.size, canPause = false) {
        var added = 0; var changed = 0; var missing = 0; var relinked = 0
        val errs = ArrayList<AppError>()
        ids.forEachIndexed { i, fid ->
            checkpoint()
            val folder = current.project.folders.firstOrNull { it.id == fid }
            if (folder != null) {
                val found = try {
                    FileOps.walk(ctx, FileOps.asDoc(folder.uri))
                } catch (e: Exception) {
                    errs += makeError(ErrorKind.NO_ACCESS, folder.label, "پوشه خوانده نشد: ${e.message ?: ""}")
                    null
                }
                if (found != null) {
                    val diff = mergeScan(current.project, folder, found)
                    diff.changedIds.forEach { prints.remove(it) }
                    // به‌جای جایگزینی کل پروژه، فقط فیلدهای اسکن را در آخرین وضعیت ادغام می‌کنیم.
                    updateProject { cur -> mergeScan(cur, folder, found).project }
                    added += diff.added; changed += diff.changed; missing += diff.missing; relinked += diff.relinked
                }
            }
            report(i + 1, 0)
        }
        if (errs.isNotEmpty()) reportErrors(errs)
        val text = "جدید: $added · تغییرکرده: $changed · ناپدیدشده: $missing · وصل‌شده‌ی مجدد: $relinked"
        log(OpEntry(type = OpType.SCAN, title = "اسکن ${ids.size} پوشه", details = listOf(text)))
        analyze()
        "اسکن تمام شد. $text"
    }
}

fun StudioViewModel.removeFolder(id: String) {
    val ids = current.project.photos.filter { it.folderId == id }.map { it.id }.toSet()
    ids.forEach { prints.remove(it) }
    updateProject { p ->
        p.copy(
            folders = p.folders.filter { it.id != id },
            photos = p.photos.filter { it.id !in ids },
            assignments = p.assignments.filterKeys { it !in ids }.filterValues { it.groupId == null || it.groupId !in ids },
        )
    }
    analyze()
}
