package com.lumo.studio.viewmodel

import android.net.Uri
import com.lumo.studio.engine.*
import com.lumo.studio.model.*
import com.lumo.studio.ops.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.withContext
import org.json.JSONObject

// ====================== زیرساخت اجرای کارها + بازیابی ======================

/** اجرای فهرست کار با دفترچه‌ی بازیابی؛ نتیجه‌ی جزئی حتی در صورت لغو در پروژه ثبت می‌شود. */
internal suspend fun StudioViewModel.executeTasks(
    type: String,
    title: String,
    tasks: List<Task>,
    meta: String,
    scope: StudioViewModel.JobScope,
): String {
    val journal = Journal(store.journalFile(current.project.id))
    journal.begin(type, title, tasks, meta)
    val exec = TaskExecutor(ctx)
    val results = arrayOfNulls<TaskResult>(tasks.size)
    var ok = 0
    var bad = 0
    try {
        tasks.forEachIndexed { i, t ->
            scope.checkpoint()
            val r = exec.run(t)
            results[i] = r
            if (r.ok) ok++ else bad++
            journal.done += i
            if (i % 10 == 9) journal.save()
            scope.report(ok, bad)
        }
    } catch (e: CancellationException) {
        withContext(NonCancellable) { finishOp(type, title, tasks, results.toList(), meta, false, scope) }
        journal.clear()
        throw e
    }
    val summary = finishOp(type, title, tasks, results.toList(), meta, true, scope)
    journal.clear()
    return summary
}

internal suspend fun StudioViewModel.finishOp(
    type: String, title: String, tasks: List<Task>, results: List<TaskResult?>,
    meta: String, completed: Boolean, scope: StudioViewModel.JobScope,
): String = when (type) {
    "RENAME" -> finishRename(title, tasks, results)
    "MOVE", "TRASH" -> finishMove(type, title, tasks, results, meta)
    "COPY" -> finishCopy(title, tasks, results, meta)
    "EXPORT" -> finishExport(title, tasks, results, meta, completed, scope)
    else -> ""
}

private fun StudioViewModel.failErrors(tasks: List<Task>, results: List<TaskResult?>): Int {
    val byId = current.project.photos.associateBy { it.id }
    val errs = ArrayList<AppError>()
    tasks.forEachIndexed { i, t ->
        val r = results[i] ?: return@forEachIndexed
        if (!r.ok) errs += makeError(r.kind ?: ErrorKind.OTHER, byId[t.photoId]?.name ?: t.dstName, r.error)
    }
    reportErrors(errs)
    return errs.size
}

/** قابلیت ۴۰: اگر دفترچه‌ی ناتمامی باشد، فقط باقی‌مانده انجام می‌شود (کارهای انجام‌شده تکرار نمی‌شوند). */
fun StudioViewModel.resumeRecovery() {
    val j = Journal.load(store.journalFile(current.project.id))
    clearRecovery()
    if (j == null) return
    startJob("ادامه‌ی عملیات: ${j.title}", j.tasks.size) {
        executeTasks(j.type, j.title, j.tasks, j.meta, this)
    }
}

fun StudioViewModel.discardRecovery() {
    store.journalFile(current.project.id).delete()
    clearRecovery()
}

// ====================== تغییر نام گروهی (۳) ======================

fun StudioViewModel.previewRename(rules: RenameRules) {
    val photos = selectedPhotos()
    viewModelScope.launch(Dispatchers.IO) {
        val idx = DirIndex(ctx)
        val items = Renamer.plan(photos, rules) { parent ->
            idx.entries(FileOps.asDoc(parent)).keys
        }
        mutate { it.copy(renamePreview = items) }
    }
}

internal fun StudioViewModel.selectedPhotos(): List<Photo> {
    val s = current
    val sel = s.selection
    return s.project.photos.filter { !it.trashed && it.id in sel }
}

fun StudioViewModel.runRename(rules: RenameRules) {
    val items = current.renamePreview.filter { it.status == RenameStatus.OK }
    if (items.isEmpty()) { toast("چیزی برای تغییر نام نیست."); return }
    val all = current.renamePreview
    val conflicts = all.count { it.status == RenameStatus.CONFLICT }
    guard(
        items.size, "تأیید عملیات حساس",
        listOf(
            "قرار است ${items.size} فایل تغییر نام داده شوند.",
            "نام‌های تکراری احتمالی (رد می‌شوند): $conflicts مورد",
            "فایل‌های بدون تغییر: ${all.count { it.status == RenameStatus.UNCHANGED }} مورد",
            "فایل‌های بدون دسترسی نوشتن: ${all.count { it.status == RenameStatus.NO_WRITE }} مورد",
        ),
    ) {
        val tasks = items.map { Task(TaskKind.RENAME, it.srcUri, it.parent, it.parent, it.newName, it.photoId) }
        startJob("تغییر نام ${tasks.size} فایل", tasks.size) {
            executeTasks("RENAME", "تغییر نام ${tasks.size} فایل", tasks, "", this)
        }
        mutate { it.copy(renamePreview = emptyList()) }
    }
}

private fun StudioViewModel.finishRename(title: String, tasks: List<Task>, results: List<TaskResult?>): String {
    val byId = current.project.photos.associateBy { it.id }
    val upd = HashMap<String, TaskResult>()
    val steps = ArrayList<UndoStep>()
    tasks.forEachIndexed { i, t ->
        val r = results[i] ?: return@forEachIndexed
        val ph = byId[t.photoId] ?: return@forEachIndexed
        if (r.ok && (r.uri != ph.uri || r.name != ph.name)) {
            upd[t.photoId] = r
            steps += UndoStep("RENAME", t.photoId, a = r.uri, b = ph.name)
        }
    }
    updateProject { p ->
        p.copy(photos = p.photos.map { ph -> upd[ph.id]?.let { ph.copy(uri = it.uri, name = it.name) } ?: ph })
    }
    val bad = failErrors(tasks, results)
    log(OpEntry(type = OpType.RENAME, title = title, details = listOf("موفق: ${upd.size} · ناموفق: $bad"), steps = steps, undoable = steps.isNotEmpty()))
    return "تغییر نام: ${upd.size} موفق، $bad ناموفق."
}

// ====================== انتقال گروهی هوشمند (۴) ======================

fun StudioViewModel.previewMove(rule: MoveRule) {
    val p = current.project
    val items = selectedPhotos().map { ph ->
        val folder = moveFolderFor(p, ph, rule)
        MoveItem(
            ph.id, ph.name, folder ?: "",
            when {
                !ph.canWrite -> MoveStatus.NO_WRITE
                folder == null -> MoveStatus.SKIP
                else -> MoveStatus.OK
            },
        )
    }
    mutate { it.copy(movePreview = items) }
}

private fun moveFolderFor(p: Project, ph: Photo, rule: MoveRule): String? = when (rule.mode) {
    MoveMode.GROUP -> {
        val gid = p.assignments[ph.id]?.groupId
        val orig = gid?.let { id -> p.photos.firstOrNull { it.id == id } }
        orig?.let { FileSafe.clean(it.baseName) }
    }
    MoveMode.EXTENSION -> ph.ext.uppercase().ifEmpty { "OTHER" }
    MoveMode.MARK -> if (rule.mark in ph.marks) FileSafe.clean(rule.mark.fa) else null
    MoveMode.CATEGORY -> if (rule.category.isNotEmpty() && rule.category in ph.categories) FileSafe.clean(rule.category) else null
    MoveMode.SINGLE -> FileSafe.clean(rule.fixedName)
}

fun StudioViewModel.runMove(destUri: String) {
    val items = current.movePreview.filter { it.status == MoveStatus.OK }
    if (items.isEmpty()) { toast("فایلی برای انتقال نیست."); return }
    val byId = current.project.photos.associateBy { it.id }
    guard(
        items.size, "تأیید انتقال",
        listOf(
            "قرار است ${items.size} فایل منتقل شوند.",
            "مقصد: ${items.map { it.folder }.distinct().size} پوشه",
            "نمونه: ${items.first().folder}/${items.first().name}",
            "فایل‌های موجود هرگز بازنویسی نمی‌شوند؛ نام تکراری پسوند _2 می‌گیرد.",
        ),
    ) {
        startJob("انتقال ${items.size} فایل", items.size) {
            val idx = DirIndex(ctx)
            val root = FileOps.asDoc(destUri)
            val tasks = ArrayList<Task>()
            for (it in items) {
                val ph = byId[it.photoId] ?: continue
                val dir = idx.ensureDir(root, it.folder) ?: continue
                tasks += Task(TaskKind.MOVE, ph.uri, ph.parentUri.orEmpty(), dir.toString(), ph.name, ph.id)
            }
            val meta = JSONObject().put("destRoot", destUri).toString()
            executeTasks("MOVE", "انتقال ${tasks.size} فایل", tasks, meta, this)
        }
        mutate { it.copy(movePreview = emptyList()) }
    }
}

private fun StudioViewModel.finishMove(type: String, title: String, tasks: List<Task>, results: List<TaskResult?>, meta: String): String {
    val destRoot = runCatching { JSONObject(meta).optString("destRoot") }.getOrDefault("")
    val keep = runCatching {
        JSONObject(meta).optJSONObject("keep")?.let { o -> o.keys().asSequence().associateWith { o.getString(it) } }
    }.getOrNull() ?: emptyMap()
    val project = current.project
    val destFolderId = project.folders.firstOrNull { it.uri == destRoot }?.id
    val byId = project.photos.associateBy { it.id }
    val upd = HashMap<String, TaskResult>()
    val steps = ArrayList<UndoStep>()
    tasks.forEachIndexed { i, t ->
        val r = results[i] ?: return@forEachIndexed
        val ph = byId[t.photoId] ?: return@forEachIndexed
        if (r.ok && (r.uri != ph.uri || r.name != ph.name)) {
            upd[t.photoId] = r
            steps += UndoStep(type, t.photoId, a = r.uri, b = r.parent, c = t.srcParent, d = ph.name)
        }
    }
    var snapshot: String? = null
    var assignments = project.assignments
    if (type == "TRASH" && keep.isNotEmpty()) {
        val remap = keep.filterKeys { it in upd }
        if (remap.isNotEmpty()) {
            snapshot = store.assignmentsToJson(project.assignments)
            val m = assignments.toMutableMap()
            for ((removed, kept) in remap) {
                val hadVersions = m.values.any { it.groupId == removed && it.photoId != removed }
                if (hadVersions) {
                    m[kept] = Assignment(kept, kept, Role.ORIGINAL, 1f, 1f, false, true)
                    for ((id, a) in m.toList()) {
                        if (id != removed && a.groupId == removed) m[id] = a.copy(groupId = kept, manual = true, needsReview = false)
                    }
                }
                m.remove(removed)
            }
            assignments = m
        }
    }
    updateProject { p ->
        p.copy(
            assignments = if (snapshot != null) assignments else p.assignments,
            photos = p.photos.map { ph ->
                upd[ph.id]?.let {
                    ph.copy(
                        uri = it.uri, parentUri = it.parent, name = it.name,
                        folderId = if (type == "TRASH") ph.folderId else destFolderId,
                        trashed = type == "TRASH",
                    )
                } ?: ph
            },
        )
    }
    val bad = failErrors(tasks, results)
    log(OpEntry(
        type = if (type == "TRASH") OpType.DEDUPE else OpType.MOVE, title = title,
        details = listOf("موفق: ${upd.size} · ناموفق: $bad"), steps = steps, snapshot = snapshot,
        undoable = steps.isNotEmpty(),
    ))
    if (type == "TRASH") analyze()
    return "${if (type == "TRASH") "حذف تکراری" else "انتقال"}: ${upd.size} موفق، $bad ناموفق."
}

// ====================== کپی هوشمند (۵) ======================

internal fun copyKey(p: Photo) = "${p.uri}|${p.size}|${p.modified}"

fun StudioViewModel.previewCopy(destUri: String, policy: CopyPolicy) {
    val photos = selectedPhotos()
    val log = current.project.copyLog[destUri].orEmpty()
    viewModelScope.launch(Dispatchers.IO) {
        val idx = DirIndex(ctx)
        val dir = FileOps.asDoc(destUri)
        var already = 0; var same = 0; var conflict = 0; var fresh = 0; var noAccess = 0
        for (p in photos) {
            val ex = idx.find(dir, p.name)
            when {
                copyKey(p) in log -> already++
                ex != null && p.size > 0 && ex.size == p.size -> same++
                ex != null -> conflict++
                else -> fresh++
            }
            if (p.missing) noAccess++
        }
        mutate { it.copy(copyPreview = CopyPreview(photos.size, already, same, conflict, fresh, noAccess)) }
    }
}

fun StudioViewModel.runCopy(destUri: String, policy: CopyPolicy, ids: Set<String>? = null) {
    val photos = if (ids != null) current.project.photos.filter { it.id in ids && !it.trashed } else selectedPhotos()
    if (photos.isEmpty()) { toast("فایلی برای کپی انتخاب نشده است."); return }
    startJob("کپی هوشمند ${photos.size} فایل", photos.size) {
        val idx = DirIndex(ctx)
        val dir = FileOps.asDoc(destUri)
        val log = current.project.copyLog[destUri].orEmpty()
        val tasks = ArrayList<Task>()
        var skipped = 0
        for (p in photos) {
            val key = copyKey(p)
            val ex = idx.find(dir, p.name)
            when {
                p.missing -> skipped++
                key in log -> skipped++
                ex != null && p.size > 0 && ex.size == p.size ->
                    tasks += Task(TaskKind.COPY, p.uri, p.parentUri.orEmpty(), destUri, p.name, p.id, key)
                ex != null && policy == CopyPolicy.SKIP -> skipped++
                ex != null -> {
                    val n = idx.unique(dir, p.name)
                    idx.add(dir, FileOps.Child(Uri.EMPTY, n, "", 0, 0))
                    tasks += Task(TaskKind.COPY, p.uri, p.parentUri.orEmpty(), destUri, n, p.id, key)
                }
                else -> tasks += Task(TaskKind.COPY, p.uri, p.parentUri.orEmpty(), destUri, p.name, p.id, key)
            }
        }
        total(tasks.size)
        val meta = JSONObject().put("dest", destUri).put("skipped", skipped).toString()
        executeTasks("COPY", "کپی ${tasks.size} فایل جدید", tasks, meta, this)
    }
}

private fun StudioViewModel.finishCopy(title: String, tasks: List<Task>, results: List<TaskResult?>, meta: String): String {
    val dest = runCatching { JSONObject(meta).optString("dest") }.getOrDefault("")
    val skipped = runCatching { JSONObject(meta).optInt("skipped") }.getOrDefault(0)
    val keys = HashSet<String>()
    val steps = ArrayList<UndoStep>()
    var ok = 0
    tasks.forEachIndexed { i, t ->
        val r = results[i] ?: return@forEachIndexed
        if (r.ok) {
            ok++
            if (t.key.isNotEmpty()) keys += t.key
            if (!r.skipped) steps += UndoStep("DELETE", a = r.uri)
        }
    }
    updateProject { p -> p.copy(copyLog = p.copyLog + (dest to (p.copyLog[dest].orEmpty() + keys))) }
    val bad = failErrors(tasks, results)
    log(OpEntry(
        type = OpType.COPY, title = title,
        details = listOf("کپی‌شده: ${steps.size} · از قبل موجود/رد شده: ${skipped + ok - steps.size} · ناموفق: $bad"),
        steps = steps, undoable = steps.isNotEmpty(),
    ))
    return "کپی: ${steps.size} فایل جدید، ${skipped + ok - steps.size} رد شد، $bad ناموفق."
}

// ====================== مقایسه‌ی دو پوشه (۲) ======================

/** هش‌های لازم را (فقط برای فایل‌هایی که هم‌حجم دارند) می‌سازد و در پروژه ذخیره می‌کند. */
internal suspend fun StudioViewModel.ensureHashes(
    ids: Collection<String>, scope: StudioViewModel.JobScope, base: Int = 0,
): Map<String, String> {
    val byId = current.project.photos.associateBy { it.id }
    val out = HashMap<String, String>()
    val fresh = HashMap<String, String>()
    var n = base
    for (id in ids) {
        scope.checkpoint()
        val p = byId[id] ?: continue
        if (p.hash.isNotEmpty()) { out[id] = p.hash } else {
            val h = FileOps.sha256(ctx, Uri.parse(p.uri))
            if (h != null) { out[id] = h; fresh[id] = h }
        }
        n++
        scope.report(n, 0)
    }
    if (fresh.isNotEmpty()) updateProject { p -> p.copy(photos = p.photos.map { ph -> fresh[ph.id]?.let { ph.copy(hash = it) } ?: ph }) }
    return out
}

fun StudioViewModel.compareFolders(aId: String, bId: String) {
    val p = current.project
    val fa = p.folders.firstOrNull { it.id == aId } ?: return
    val fb = p.folders.firstOrNull { it.id == bId } ?: return
    val a = p.photos.filter { it.folderId == aId && !it.trashed && !it.missing }
    val b = p.photos.filter { it.folderId == bId && !it.trashed && !it.missing }
    val sizesB = b.map { it.size }.toSet()
    val sizesA = a.map { it.size }.toSet()
    val need = a.filter { it.size in sizesB }.map { it.id } + b.filter { it.size in sizesA }.map { it.id }
    startJob("مقایسه‌ی «${fa.label}» و «${fb.label}»", need.size) {
        val h = ensureHashes(need, this)
        val byHash = b.filter { h.containsKey(it.id) }.groupBy { h[it.id] }
        val used = HashSet<String>()
        val same = ArrayList<String>(); val renamed = ArrayList<NamePair>()
        val sameNameDiff = ArrayList<String>(); val onlyA = ArrayList<String>(); val onlyAIds = ArrayList<String>()
        val bByName = b.associateBy { it.name }
        for (x in a) {
            val hx = h[x.id]
            val twin = if (hx != null) byHash[hx]?.firstOrNull { it.id !in used } else null
            when {
                twin != null && twin.name == x.name -> { used += twin.id; same += x.name }
                twin != null -> { used += twin.id; renamed += NamePair(x.name, twin.name) }
                bByName[x.name] != null -> { used += bByName.getValue(x.name).id; sameNameDiff += x.name }
                else -> { onlyA += x.name; onlyAIds += x.id }
            }
        }
        val onlyB = b.filter { it.id !in used }.map { it.name }
        mutate {
            it.copy(folderCompare = FolderCompareResult(fa.label, fb.label, same, renamed, sameNameDiff, onlyA, onlyB, onlyAIds))
        }
        log(OpEntry(type = OpType.SCAN, title = "مقایسه‌ی ${fa.label} و ${fb.label}",
            details = listOf("مشترک: ${same.size} · فقط اول: ${onlyA.size} · فقط دوم: ${onlyB.size} · هم‌محتوا با نام متفاوت: ${renamed.size}")))
        "مقایسه تمام شد."
    }
}

// ====================== فایل‌های تکراری (۶) ======================

fun StudioViewModel.findDuplicates() {
    val live = current.project.photos.filter { !it.trashed && !it.missing }
    val bySize = live.groupBy { it.size }.filter { it.key > 0 && it.value.size > 1 }
    val ids = bySize.values.flatten().map { it.id }
    startJob("جست‌وجوی فایل‌های تکراری", ids.size) {
        val h = ensureHashes(ids, this)
        val p = current.project
        val clusters = ids.groupBy { h[it] }.filter { it.key != null && it.value.size > 1 }.map { (hash, list) ->
            val photos = list.mapNotNull { id -> p.photos.firstOrNull { it.id == id } }
            val keep = photos.sortedWith(
                compareByDescending<Photo> { p.roleOf(it.id) == Role.ORIGINAL }
                    .thenBy { if (it.modified > 0) it.modified else Long.MAX_VALUE }.thenBy { it.name },
            ).first().id
            DupCluster(hash!!, photos.map { it.id }, keep)
        }
        mutate { it.copy(dupes = clusters, dupesScanned = true) }
        "${clusters.size} دسته‌ی فایل کاملاً یکسان پیدا شد. (عکس‌های مشابه ولی متفاوت تکراری حساب نمی‌شوند.)"
    }
}

fun StudioViewModel.setKeep(hash: String, id: String) =
    mutate { s -> s.copy(dupes = s.dupes.map { if (it.hash == hash) it.copy(keep = id) else it }) }

/** نسخه‌های اضافی هرگز حذف قطعی نمی‌شوند: به «_LumoTrash» کنار فایل (یا پوشه‌ی دلخواه) می‌روند و قابل بازگشت‌اند. */
fun StudioViewModel.removeDuplicates(destUri: String? = null) {
    val clusters = current.dupes
    val byId = current.project.photos.associateBy { it.id }
    val removals = clusters.flatMap { c -> c.ids.filter { it != c.keep }.map { it to c.keep } }
        .filter { byId[it.first]?.canWrite == true }
    if (removals.isEmpty()) { toast("نسخه‌ی اضافه‌ای برای حذف نیست."); return }
    guard(
        removals.size, "تأیید حذف تکراری‌ها",
        listOf(
            "${removals.size} فایل تکراری ${if (destUri == null) "به سطل Lumo (_LumoTrash)" else "به پوشه‌ی انتخابی"} منتقل می‌شود.",
            "حذف قطعی انجام نمی‌شود و عملیات از «تاریخچه» قابل بازگشت است.",
        ),
    ) {
        startJob("حذف ${removals.size} فایل تکراری", removals.size) {
            val idx = DirIndex(ctx)
            val tasks = ArrayList<Task>()
            val keep = JSONObject()
            for ((rid, kid) in removals) {
                val ph = byId[rid] ?: continue
                val parent = FileOps.asDoc(ph.parentUri.orEmpty())
                val dir = if (destUri == null) idx.ensureDir(parent, "_LumoTrash") else FileOps.asDoc(destUri)
                if (dir == null) continue
                tasks += Task(TaskKind.MOVE, ph.uri, ph.parentUri.orEmpty(), dir.toString(), ph.name, ph.id)
                keep.put(rid, kid)
            }
            val meta = JSONObject().put("keep", keep).toString()
            val r = executeTasks("TRASH", "حذف ${tasks.size} فایل تکراری", tasks, meta, this)
            mutate { it.copy(dupes = emptyList(), dupesScanned = false) }
            r
        }
    }
}

/** انتقال فایل‌های انتخاب‌شده (مثلاً حجیم‌ها) به سطل Lumo. */
fun StudioViewModel.trashPhotos(ids: Set<String>) {
    val byId = current.project.photos.associateBy { it.id }
    val list = ids.mapNotNull { byId[it] }.filter { it.canWrite }
    if (list.isEmpty()) { toast("این فایل‌ها دسترسی نوشتن ندارند."); return }
    guard(list.size, "تأیید انتقال به سطل", listOf("${list.size} فایل به _LumoTrash منتقل می‌شود (قابل بازگشت).")) {
        startJob("انتقال ${list.size} فایل به سطل", list.size) {
            val idx = DirIndex(ctx)
            val tasks = list.mapNotNull { ph ->
                val dir = idx.ensureDir(FileOps.asDoc(ph.parentUri.orEmpty()), "_LumoTrash") ?: return@mapNotNull null
                Task(TaskKind.MOVE, ph.uri, ph.parentUri.orEmpty(), dir.toString(), ph.name, ph.id)
            }
            executeTasks("TRASH", "انتقال ${tasks.size} فایل به سطل", tasks, "{}", this)
        }
    }
}

// ====================== بازگشت عملیات (۲۲) و تاریخچه (۲۱) ======================

fun StudioViewModel.undoLast() {
    val entry = current.project.history.firstOrNull { it.undoable && !it.undone }
    if (entry == null) { toast("عملیات قابل بازگشتی وجود ندارد."); return }
    undoEntry(entry.id)
}

fun StudioViewModel.undoEntry(id: String) {
    val entry = current.project.history.firstOrNull { it.id == id } ?: return
    val latest = current.project.history.firstOrNull { it.undoable && !it.undone }
    if (latest?.id != id) { toast("فقط آخرین عملیات قابل بازگشت است."); return }
    guard(entry.steps.size, "تأیید بازگشت", listOf("«${entry.title}» برگردانده می‌شود (${entry.steps.size} فایل).")) {
        startJob("بازگشت: ${entry.title}", entry.steps.size) {
            val exec = TaskExecutor(ctx)
            val byId = current.project.photos.associateBy { it.id }
            val restored = HashMap<String, Photo>()
            var ok = 0; var bad = 0
            try {
                for (s in entry.steps.reversed()) {
                    checkpoint()
                    val ph = byId[s.photoId]
                    var good = false
                    try {
                        when (s.kind) {
                            "RENAME" -> {
                                val r = exec.run(Task(TaskKind.RENAME, s.a, ph?.parentUri.orEmpty(), ph?.parentUri.orEmpty(), s.b, s.photoId))
                                if (r.ok && ph != null) { restored[ph.id] = ph.copy(uri = r.uri, name = r.name); good = true }
                            }
                            "MOVE", "TRASH" -> {
                                val r = exec.run(Task(TaskKind.MOVE, s.a, s.b, s.c, s.d, s.photoId))
                                if (r.ok && ph != null) {
                                    restored[ph.id] = ph.copy(uri = r.uri, parentUri = r.parent, name = r.name, trashed = false)
                                    good = true
                                }
                            }
                            "DELETE" -> good = FileOps.delete(ctx, FileOps.asDoc(s.a))
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) { good = false }
                    if (good) ok++ else bad++
                    report(ok, bad)
                }
            } finally {
                withContext(NonCancellable) {
                    val snap = entry.snapshot
                    updateProject { p ->
                        p.copy(
                            photos = p.photos.map { restored[it.id] ?: it },
                            assignments = if (snap != null && bad == 0) store.assignmentsFromJson(snap) else p.assignments,
                            history = p.history.map { if (it.id == id) it.copy(undone = bad == 0, undoable = bad != 0) else it },
                        )
                    }
                    log(OpEntry(type = OpType.UNDO, title = "بازگشت: ${entry.title}", details = listOf("موفق: $ok · ناموفق: $bad")))
                }
            }
            if (entry.snapshot != null || entry.type == OpType.DEDUPE) analyze()
            "بازگشت تمام شد: $ok موفق، $bad ناموفق."
        }
    }
}
