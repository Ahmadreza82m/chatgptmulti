package com.lumo.studio.viewmodel

import android.net.Uri
import android.os.Environment
import android.os.StatFs
import androidx.lifecycle.viewModelScope
import com.lumo.studio.export.*
import com.lumo.studio.model.*
import com.lumo.studio.ops.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun freeBytes(): Long = runCatching {
    StatFs(Environment.getExternalStorageDirectory().path).availableBytes
}.getOrDefault(0L)

/** قابلیت ۲۹: پیش‌نمایش دقیق ساختار خروجی بدون ساخت هیچ فایلی. */
fun StudioViewModel.previewExport(o: ExportOptions) {
    val project = current.project
    viewModelScope.launch(Dispatchers.Default) {
        val plan = ExportPlanner.plan(project, o)
        val pv = ExportPreview(
            lines = ExportPlanner.treeLines(plan, "PhotoFlow-Export"),
            files = plan.items.size, bytes = plan.bytes,
            renamed = plan.renamedForUniqueness, missing = plan.skippedMissing, freeBytes = freeBytes(),
        )
        mutate { it.copy(exportPreview = pv) }
    }
}

private fun csv(vararg cols: Any): String =
    cols.joinToString(",", postfix = "\n") { "\"" + it.toString().replace("\"", "\"\"") + "\"" }

fun StudioViewModel.runExport(destUri: String, o: ExportOptions) {
    val project = current.project
    val plan = ExportPlanner.plan(project, o)
    if (plan.items.isEmpty()) { toast("چیزی برای خروجی گرفتن نیست."); return }
    startJob("ساخت خروجی", plan.items.size) {
        val idx = DirIndex(ctx)
        val root = FileOps.asDoc(destUri)
        val outName = "Lumo_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outDoc = idx.ensureDir(root, outName) ?: throw IllegalStateException("ساخت پوشه‌ی خروجی ممکن نشد")
        val dirs = HashMap<String, Uri>()
        val groupName = project.groups().associate { it.id to (it.original?.name ?: "") }
        val rows = JSONArray()
        val tasks = plan.items.map { it ->
            val parent = if (it.folder.isEmpty()) outDoc else dirs.getOrPut(it.folder) { idx.ensureDir(outDoc, it.folder) ?: outDoc }
            val gid = project.assignments[it.photo.id]?.groupId
            rows.put(JSONArray().put(it.relPath).put(it.photo.name).put(it.role.name)
                .put(gid?.let { g -> groupName[g] } ?: "").put(project.assignments[it.photo.id]?.score ?: 0f)
                .put(it.photo.rating).put(it.photo.best))
            Task(TaskKind.COPY, it.photo.uri, it.photo.parentUri.orEmpty(), parent.toString(), it.fileName, it.photo.id)
        }
        val meta = JSONObject().put("outUri", outDoc.toString()).put("outName", outName)
            .put("zipMode", o.zipMode.name).put("zipCount", o.zipCount).put("zipMaxMb", o.zipMaxMb)
            .put("summary", "${o.scope.fa}${if (o.byGroup) " · پوشه برای هر گروه" else ""}")
            .put("rows", rows).toString()
        executeTasks("EXPORT", "خروجی ${tasks.size} فایل", tasks, meta, this)
    }
}

internal suspend fun StudioViewModel.finishExport(
    title: String, tasks: List<Task>, results: List<TaskResult?>, meta: String,
    completed: Boolean, scope: StudioViewModel.JobScope,
): String {
    val m = JSONObject(meta)
    val outUri = m.getString("outUri")
    val outName = m.getString("outName")
    val rows = m.getJSONArray("rows")
    val manifest = StringBuilder("\uFEFF")
    manifest.append(csv("file", "source", "role", "group", "score", "rating", "best"))
    val entries = ArrayList<ZipBuilder.Entry>()
    var files = 0
    var bytes = 0L
    tasks.forEachIndexed { i, _ ->
        val r = results[i] ?: return@forEachIndexed
        if (!r.ok) return@forEachIndexed
        val row = rows.getJSONArray(i)
        files++; bytes += r.size
        manifest.append(csv(row.get(0), row.get(1), row.get(2), row.get(3),
            String.format(Locale.US, "%.3f", row.getDouble(4)), row.get(5), row.get(6)))
        entries += ZipBuilder.Entry(row.getString(0), Uri.parse(r.uri), r.size)
    }
    val outDoc = FileOps.asDoc(outUri)
    val idx = DirIndex(ctx)
    val manifestBytes = manifest.toString().toByteArray(Charsets.UTF_8)
    val existing = idx.find(outDoc, "manifest.csv")
    val mUri = existing?.uri ?: FileOps.createFile(ctx, outDoc, "manifest.csv", "text/csv")
    if (mUri != null) ctx.contentResolver.openOutputStream(mUri, "wt")?.use { it.write(manifestBytes) }

    val version = (current.project.exports.maxOfOrNull { it.version } ?: 0) + 1
    var record = ExportRecord(
        version = version, folderName = outName, folderUri = outUri, files = files, bytes = bytes,
        summary = m.optString("summary"),
    )
    updateProject { it.copy(exports = listOf(record) + it.exports) }
    val bad = tasks.indices.count { results[it]?.ok == false }
    val byId = current.project.photos.associateBy { it.id }
    reportErrors(tasks.indices.mapNotNull { i ->
        val r = results[i] ?: return@mapNotNull null
        if (r.ok) null else makeError(r.kind ?: ErrorKind.OTHER, byId[tasks[i].photoId]?.name ?: tasks[i].dstName, r.error)
    })
    log(OpEntry(
        type = OpType.EXPORT, title = "$title (نسخه $version)",
        details = listOf("کپی‌شده: $files · ناموفق: $bad${if (!completed) " · ناتمام (لغو شد)" else ""}"),
        steps = listOf(UndoStep("DELETE", a = outUri)), undoable = true,
    ))

    val mode = runCatching { ZipMode.valueOf(m.optString("zipMode")) }.getOrDefault(ZipMode.NONE)
    var zipInfo = ""
    if (completed && mode != ZipMode.NONE && entries.isNotEmpty()) {
        val zips = ZipBuilder.build(
            ctx, outDoc, outName, entries, manifestBytes, mode, m.optInt("zipCount", 200), m.optInt("zipMaxMb", 500), idx,
        ) { scope.checkpoint() }
        record = record.copy(zips = zips)
        updateProject { p -> p.copy(exports = p.exports.map { if (it.id == record.id) record else it }) }
        zipInfo = " · ${zips.size} بسته‌ی ZIP"
    }
    return "خروجی ساخته شد: $files فایل، $bad ناموفق$zipInfo — پوشه $outName"
}

/** قابلیت ۳۱: ساخت دوباره‌ی ZIP از یک خروجی قبلی. */
fun StudioViewModel.reZip(recordId: String, mode: ZipMode, count: Int, maxMb: Int) {
    val rec = current.project.exports.firstOrNull { it.id == recordId } ?: return
    startJob("ساخت دوباره‌ی ZIP (نسخه ${rec.version})", 0) {
        val outDoc = FileOps.asDoc(rec.folderUri)
        val all = FileOps.walkAll(ctx, outDoc).filter { !it.first.endsWith(".zip", true) }
        total(all.size)
        val entries = all.map { ZipBuilder.Entry(it.first, it.second.uri, it.second.size) }
        rec.zips.forEach { FileOps.delete(ctx, Uri.parse(it.uri)) }
        val zips = ZipBuilder.build(ctx, outDoc, rec.folderName, entries, null, mode, count, maxMb, DirIndex(ctx)) { checkpoint() }
        updateProject { p -> p.copy(exports = p.exports.map { if (it.id == rec.id) it.copy(zips = zips) else it }) }
        log(OpEntry(type = OpType.ZIP, title = "ZIP دوباره‌ی نسخه ${rec.version}", details = listOf("${zips.size} بسته")))
        "${zips.size} بسته‌ی ZIP ساخته شد."
    }
}

fun StudioViewModel.deleteExport(recordId: String) {
    val rec = current.project.exports.firstOrNull { it.id == recordId } ?: return
    guard(Int.MAX_VALUE, "حذف خروجی", listOf("پوشه‌ی «${rec.folderName}» با ${rec.files} فایل حذف می‌شود.", "این کار برگشت‌پذیر نیست؛ فایل‌های اصلی دست‌نخورده می‌مانند.")) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = FileOps.delete(ctx, FileOps.asDoc(rec.folderUri)) || FileOps.stat(ctx, FileOps.asDoc(rec.folderUri)) == null
            if (ok) updateProject { p -> p.copy(exports = p.exports.filter { it.id != recordId }) }
            toast(if (ok) "خروجی حذف شد." else "حذف انجام نشد؛ دسترسی را بررسی کن.")
        }
    }
}

fun StudioViewModel.shareExport(recordId: String, zipOnly: Boolean) {
    val rec = current.project.exports.firstOrNull { it.id == recordId } ?: return
    viewModelScope.launch(Dispatchers.IO) {
        val uris: List<Uri>
        val mime: String
        if (zipOnly) {
            uris = rec.zips.map { Uri.parse(it.uri) }
            mime = "application/zip"
        } else {
            uris = FileOps.walkAll(ctx, FileOps.asDoc(rec.folderUri))
                .filter { !it.first.endsWith(".zip", true) && !it.first.endsWith(".csv", true) }
                .take(100).map { it.second.uri }
            mime = "image/*"
        }
        if (uris.isEmpty()) { toast(if (zipOnly) "این خروجی ZIP ندارد؛ ابتدا «ساخت دوباره‌ی ZIP» را بزن." else "فایلی برای اشتراک نیست."); return@launch }
        if (!Sharer.share(ctx, uris, mime)) toast("اشتراک‌گذاری ممکن نشد.")
    }
}
