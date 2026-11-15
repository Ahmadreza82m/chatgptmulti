package com.lumo.studio.viewmodel

import android.net.Uri
import android.os.StatFs
import androidx.lifecycle.viewModelScope
import com.lumo.studio.engine.FileSafe
import com.lumo.studio.engine.ImageAnalyzer
import com.lumo.studio.export.toOptions
import com.lumo.studio.export.ExportPlanner
import com.lumo.studio.model.*
import com.lumo.studio.ops.DirIndex
import com.lumo.studio.ops.FileOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

// ====================== چندپروژه‌ای (۳۲) ======================

fun StudioViewModel.createProject(name: String) {
    store.save(current.project)
    val p = store.create(name.trim().ifEmpty { "پروژه جدید" })
    reloadProject(p)
    navigate(Screen.STUDIO)
}

fun StudioViewModel.switchProject(id: String) {
    if (id == current.project.id) return
    store.save(current.project)
    store.setActive(id)
    reloadProject(store.load(id))
    navigate(Screen.STUDIO)
}

fun StudioViewModel.renameProject(id: String, name: String) {
    val n = name.trim().ifEmpty { return }
    if (id == current.project.id) updateProject { it.copy(name = n) }
    else store.save(store.load(id).copy(name = n))
    mutate { it.copy(projects = store.listProjects()) }
}

fun StudioViewModel.deleteProject(id: String) {
    guard(Int.MAX_VALUE, "حذف پروژه", listOf("همه‌ی گروه‌بندی‌ها و تنظیمات این پروژه حذف می‌شود.", "عکس‌های اصلی روی گوشی دست‌نخورده می‌مانند.")) {
        val wasCurrent = id == current.project.id
        store.delete(id)
        if (wasCurrent) {
            val next = store.listProjects().firstOrNull()
            val p = if (next != null) store.load(next.id).also { store.setActive(it.id) } else store.create("پروژه جدید")
            reloadProject(p)
        } else {
            mutate { it.copy(projects = store.listProjects()) }
        }
    }
}

// ====================== پشتیبان‌گیری و انتقال (۳۳، ۳۴) ======================

fun StudioViewModel.createBackup(destUri: String, includePhotos: Boolean) {
    val project = current.project
    val photos = if (includePhotos) project.photos.filter { !it.trashed && !it.missing } else emptyList()
    startJob("پشتیبان‌گیری از «${project.name}»", photos.size + 1) {
        val idx = DirIndex(ctx)
        val root = FileOps.asDoc(destUri)
        val name = idx.unique(
            root,
            "LumoBackup_${FileSafe.clean(project.name)}_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".zip",
        )
        val zipUri = FileOps.createFile(ctx, root, name, "application/zip") ?: throw IllegalStateException("ساخت فایل پشتیبان ممکن نشد")
        val entryOf = HashMap<String, String>()
        photos.forEachIndexed { i, p -> entryOf[p.id] = "photos/${i}_${FileSafe.clean(p.name)}" }
        val os = ctx.contentResolver.openOutputStream(zipUri, "wt") ?: throw IllegalStateException("نوشتن فایل ممکن نشد")
        var ok = 0
        ZipOutputStream(BufferedOutputStream(os)).use { zos ->
            val manifest = JSONObject().put("format", 1).put("app", "LumoStudio").put("project", project.name)
                .put("includesPhotos", includePhotos).put("created", System.currentTimeMillis())
                .put("photos", JSONObject().apply { entryOf.forEach { (k, v) -> put(k, v) } })
            zos.putNextEntry(ZipEntry("manifest.json")); zos.write(manifest.toString().toByteArray()); zos.closeEntry()
            zos.putNextEntry(ZipEntry("project.json")); zos.write(store.toJson(project).toByteArray()); zos.closeEntry()
            val patterns = JSONArray().apply {
                current.settings.patterns.forEach { put(JSONObject().put("name", it.name).put("template", it.template)) }
            }
            zos.putNextEntry(ZipEntry("patterns.json")); zos.write(patterns.toString().toByteArray()); zos.closeEntry()
            report(1, 0)
            for (p in photos) {
                checkpoint()
                zos.putNextEntry(ZipEntry(entryOf.getValue(p.id)))
                ctx.contentResolver.openInputStream(Uri.parse(p.uri))?.use { it.copyTo(zos, 64 * 1024) }
                zos.closeEntry()
                ok++
                report(ok + 1, 0)
            }
        }
        val st = FileOps.stat(ctx, zipUri)
        log(OpEntry(type = OpType.BACKUP, title = "پشتیبان‌گیری", details = listOf(name, "حجم: ${com.lumo.studio.engine.Stats.fmtBytes(st?.size ?: 0)}"),
            steps = listOf(UndoStep("DELETE", a = zipUri.toString())), undoable = false))
        "پشتیبان ساخته شد: $name"
    }
}

/** پروژه‌ی پشتیبان را به‌صورت پروژه‌ی جدید باز می‌کند. عکس‌ها یا از داخل پشتیبان بیرون می‌آیند یا با «اسکن» دوباره وصل می‌شوند. */
fun StudioViewModel.restoreBackup(zipUri: Uri, destUri: String?) {
    startJob("بازیابی پشتیبان", 0, canPause = false) {
        val resolver = ctx.contentResolver
        var manifest: JSONObject? = null
        var projectJson: String? = null
        val entryToPhoto = HashMap<String, String>()
        var restored: Project? = null
        val extracted = HashMap<String, Triple<String, String, Long>>() // id -> uri, parent, size
        val idx = DirIndex(ctx)
        var outDir: Uri? = null
        var count = 0
        val input = resolver.openInputStream(zipUri) ?: throw IllegalStateException("فایل پشتیبان باز نشد")
        ZipInputStream(BufferedInputStream(input)).use { zis ->
            while (true) {
                checkpoint()
                val e = zis.nextEntry ?: break
                when {
                    e.name == "manifest.json" -> {
                        manifest = JSONObject(String(zis.readBytes()))
                        val ph = manifest!!.optJSONObject("photos")
                        if (ph != null) {
                            val keys = ph.keys()
                            while (keys.hasNext()) {
                                val pid = keys.next()
                                entryToPhoto[ph.getString(pid)] = pid
                            }
                        }
                        total(entryToPhoto.size)
                    }
                    e.name == "project.json" -> projectJson = String(zis.readBytes())
                    e.name.startsWith("photos/") && destUri != null && !e.isDirectory -> {
                        val id = entryToPhoto[e.name] ?: continue
                        if (outDir == null) {
                            val root = FileOps.asDoc(destUri)
                            outDir = idx.ensureDir(root, "Lumo_Restore_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()))
                        }
                        val dir = outDir ?: continue
                        val fname = idx.unique(dir, e.name.substringAfter('_'))
                        val u = FileOps.createFile(ctx, dir, fname) ?: continue
                        var size = 0L
                        resolver.openOutputStream(u, "wt")?.use { os -> size = zis.copyTo(os, 64 * 1024) }
                        idx.add(dir, FileOps.Child(u, fname, "", size, 0))
                        extracted[id] = Triple(u.toString(), dir.toString(), size)
                        count++
                        report(count, 0)
                    }
                }
            }
        }
        val pj = projectJson ?: throw IllegalStateException("فایل پشتیبان معتبر نیست (project.json پیدا نشد)")
        val base = store.fromJson(pj)
        val photos = base.photos.map { p ->
            val x = extracted[p.id]
            if (x != null) p.copy(uri = x.first, parentUri = x.second, folderId = null, missing = false, size = x.third, modified = 0L)
            else p.copy(uri = "lumo-missing://${p.id}", parentUri = null, folderId = null, missing = !p.trashed, trashed = false)
        }
        restored = base.copy(
            id = UUID.randomUUID().toString(), name = base.name + " (بازیابی)", photos = photos,
            folders = emptyList(), exports = emptyList(), copyLog = emptyMap(), errors = emptyList(),
        )
        store.save(restored!!)
        store.setActive(restored!!.id)
        withContext(Dispatchers.Main) {
            reloadProject(restored!!)
            navigate(if (extracted.size < photos.size) Screen.FOLDERS else Screen.STUDIO)
        }
        if (extracted.size < photos.size) "پروژه بازیابی شد. حالا پوشه‌های عکس را در بخش «پوشه‌ها» معرفی کن تا فایل‌ها خودکار وصل شوند."
        else "پروژه و عکس‌ها بازیابی شدند."
    }
}

// ====================== سلامت پروژه (۱۹) ======================

fun StudioViewModel.runHealth() {
    val p = current.project
    viewModelScope.launch(Dispatchers.IO) {
        val items = ArrayList<HealthItem>()
        val g = p.groups()
        val noOut = g.count { it.versions.isEmpty() }
        items += HealthItem(if (noOut > 0) 1 else 0, "عکس اصلی بدون خروجی",
            if (noOut > 0) "$noOut عکس اصلی هنوز نسخه‌ای ندارد." else "همه‌ی عکس‌های اصلی خروجی دارند.")
        val un = p.unsorted().size
        items += HealthItem(if (un > 0) 1 else 0, "خروجی بدون گروه", if (un > 0) "$un عکس به گروهی وصل نیست." else "همه گروه‌بندی شده‌اند.")
        val pend = p.reviewQueue().size
        items += HealthItem(if (pend > 0) 1 else 0, "تطبیق‌های بررسی‌نشده", if (pend > 0) "$pend مورد در صف بازبینی است." else "صف بازبینی خالی است.")
        val missing = p.photos.count { it.missing && !it.trashed }
        items += HealthItem(if (missing > 0) 2 else 0, "فایل‌های ناپدیدشده",
            if (missing > 0) "$missing فایل پیدا نمی‌شود؛ اسکن مجدد بزن یا پوشه را دوباره معرفی کن." else "همه‌ی فایل‌ها در دسترس‌اند.")
        // دسترسی پوشه‌ها
        val perms = ctx.contentResolver.persistedUriPermissions
        val broken = p.folders.filter { f ->
            val hasPerm = perms.any { it.uri.toString() == f.uri && it.isReadPermission }
            !hasPerm || FileOps.stat(ctx, FileOps.asDoc(f.uri)) == null
        }
        items += HealthItem(if (broken.isNotEmpty()) 2 else 0, "مسیر پوشه‌ها",
            if (broken.isNotEmpty()) "دسترسی قطع شده: " + broken.joinToString("، ") { it.label } else "${p.folders.size} پوشه در دسترس است.")
        // تکراری‌ها
        val live = p.photos.filter { !it.trashed && !it.missing }
        val collide = live.groupBy { it.size }.filter { it.key > 0 && it.value.size > 1 }.values.flatten()
        val unhashed = collide.count { it.hash.isEmpty() }
        val knownDup = collide.filter { it.hash.isNotEmpty() }.groupBy { it.hash }.count { it.value.size > 1 }
        items += HealthItem(if (knownDup > 0) 1 else if (unhashed > 0) 1 else 0, "فایل تکراری",
            when {
                knownDup > 0 -> "$knownDup دسته فایل یکسان پیدا شده؛ از «فایل‌های تکراری» پاکسازی کن."
                unhashed > 0 -> "$unhashed فایل هم‌حجم هستند؛ برای بررسی دقیق «فایل‌های تکراری» را اجرا کن."
                else -> "تکراری شناخته‌شده‌ای نیست."
            })
        // فضا و نام‌ها
        val plan = ExportPlanner.plan(p, p.settings.toOptions())
        val free = freeBytes()
        val low = free > 0 && free < plan.bytes + plan.bytes / 10
        items += HealthItem(if (low) 2 else 0, "فضای مقصد",
            "خروجی ≈ ${com.lumo.studio.engine.Stats.fmtBytes(plan.bytes)} · فضای آزاد حافظه‌ی داخلی ≈ ${com.lumo.studio.engine.Stats.fmtBytes(free)}" +
                if (low) " — فضا کم است." else "")
        items += HealthItem(if (plan.renamedForUniqueness > 0) 1 else 0, "تداخل نام‌گذاری خروجی",
            if (plan.renamedForUniqueness > 0) "${plan.renamedForUniqueness} نام با الگوی فعلی تکراری می‌شود (پسوند _2 می‌گیرد)." else "نام‌ها با هم تداخل ندارند.")
        if (p.errors.isNotEmpty()) items += HealthItem(1, "خطاهای ثبت‌شده", "${p.errors.size} خطا در «گزارش خطاها» هست.")
        mutate { it.copy(health = items) }
    }
}

// ====================== مدیریت فضا (۳۹) ======================

suspend fun StudioViewModel.storageInfo(): StorageInfo = withContext(Dispatchers.IO) {
    val p = current.project
    val live = p.photos.filter { !it.trashed }
    val orig = live.filter { p.roleOf(it.id) == Role.ORIGINAL }.sumOf { it.size }
    val others = live.sumOf { it.size } - orig
    val zips = p.exports.sumOf { e -> e.zips.sumOf { it.bytes } }
    val st = runCatching { StatFs(ctx.filesDir.path) }.getOrNull()
    StorageInfo(orig, others, zips, store.dirSize(p.id), st?.availableBytes ?: 0L, st?.totalBytes ?: 0L)
}
