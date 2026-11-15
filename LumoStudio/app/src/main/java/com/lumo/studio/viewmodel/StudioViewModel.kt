package com.lumo.studio.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lumo.studio.data.FingerprintCache
import com.lumo.studio.data.ProjectStore
import com.lumo.studio.data.Session
import com.lumo.studio.engine.Fingerprint
import com.lumo.studio.engine.ImageAnalyzer
import com.lumo.studio.engine.Matcher
import com.lumo.studio.model.*
import com.lumo.studio.ops.FileOps
import com.lumo.studio.ops.JobService
import com.lumo.studio.ops.Journal
import com.lumo.studio.ops.Notifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class StudioViewModel(app: Application) : AndroidViewModel(app) {

    internal val store = ProjectStore(app)
    internal val prints = ConcurrentHashMap<String, Fingerprint>()
    internal var analysisJob: Job? = null
    internal val ctx: Context get() = getApplication<Application>()

    private val paused = MutableStateFlow(false)
    private var jobHandle: Job? = null
    private var pendingAction: (() -> Unit)? = null
    private val saveSignal = Channel<Unit>(Channel.CONFLATED)
    private var lastNotify = 0L

    private val _state = MutableStateFlow(
        UiState(project = store.load(store.activeId()), settings = store.loadSettings(), projects = store.listProjects())
    )
    val state: StateFlow<UiState> = _state.asStateFlow()
    internal val current: UiState get() = _state.value

    init {
        // ذخیره‌ی مداوم: هر تغییر، آخرین وضعیت را روی دیسک می‌نویسد (قابلیت ۲۰)
        viewModelScope.launch(Dispatchers.IO) {
            for (u in saveSignal) {
                runCatching { store.save(_state.value.project) }
            }
        }
        store.loadSession()?.let { s ->
            mutate {
                it.copy(
                    screen = runCatching { Screen.valueOf(s.screen) }.getOrDefault(Screen.STUDIO),
                    filter = runCatching { Filter.valueOf(s.filter) }.getOrDefault(Filter.ALL),
                    reviewIndex = s.reviewIndex,
                    compareGroup = s.compareGroup,
                )
            }
        }
        loadPrints(_state.value.project)
        checkRecovery()
        if (_state.value.project.photos.isNotEmpty()) analyze()
    }

    // ---------- plumbing ----------

    internal fun mutate(block: (UiState) -> UiState) { _state.update(block) }

    internal fun persist() { saveSignal.trySend(Unit) }

    internal fun updateProject(block: (Project) -> Project) {
        mutate { it.copy(project = block(it.project)) }
        persist()
    }

    internal fun toast(msg: String) = mutate { it.copy(message = msg) }

    internal fun log(entry: OpEntry) = updateProject { p ->
        var snaps = 0
        val list = (listOf(entry) + p.history).take(200).map { e ->
            if (e.snapshot != null) {
                snaps++
                if (snaps > 8) e.copy(snapshot = null, undoable = e.undoable && e.steps.isNotEmpty()) else e
            } else e
        }
        p.copy(history = list)
    }

    internal fun updatePhotos(ids: Set<String>, f: (Photo) -> Photo) = updateProject { p ->
        p.copy(photos = p.photos.map { if (it.id in ids) f(it) else it })
    }

    fun flush() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { store.save(_state.value.project) }
            saveSession()
        }
    }

    private fun saveSession() {
        val s = _state.value
        store.saveSession(Session(s.screen.name, s.filter.name, s.reviewIndex, s.compareGroup))
    }

    override fun onCleared() {
        runCatching { store.save(_state.value.project) }
        super.onCleared()
    }

    // ---------- fingerprints ----------

    internal fun loadPrints(project: Project) {
        prints.clear()
        val cache = FingerprintCache.load(store.printsFile(project.id))
        for (p in project.photos) {
            val e = cache[p.id] ?: continue
            if (p.modified > 0 && e.modified == p.modified && e.size == p.size) prints[p.id] = e.fp
        }
    }

    // ---------- analysis ----------

    fun analyze() {
        analysisJob?.cancel()
        analysisJob = viewModelScope.launch(Dispatchers.IO) {
            val resolver = ctx.contentResolver
            val start = _state.value.project
            mutate { it.copy(analyzing = true, progress = 0f) }

            val todo = start.photos.filter { !it.trashed && !it.missing && !prints.containsKey(it.id) }
            val dims = HashMap<String, Pair<Int, Int>>()
            val errs = ArrayList<AppError>()
            todo.forEachIndexed { i, p ->
                ensureActive()
                val uri = Uri.parse(p.uri)
                val size = ImageAnalyzer.readSize(resolver, uri)
                val bmp = ImageAnalyzer.decodeSampled(resolver, uri, 256)
                if (bmp != null) {
                    prints[p.id] = ImageAnalyzer.fingerprint(bmp)
                    bmp.recycle()
                } else {
                    errs += decodeError(p)
                }
                if (size != null) dims[p.id] = size
                mutate { it.copy(progress = (i + 1f) / todo.size) }
            }

            ensureActive()
            val latest = _state.value.project
            val finalPhotos = latest.photos.map { p ->
                dims[p.id]?.let { d -> p.copy(width = d.first, height = d.second) } ?: p
            }
            val liveIds = finalPhotos.filter { !it.trashed }.map { it.id }.toSet()
            val manual = latest.assignments.filterValues {
                it.manual && it.photoId in liveIds && (it.groupId == null || it.groupId in liveIds)
            }
            val assignments = Matcher.run(finalPhotos.filter { !it.trashed }, prints, latest.sensitivity, manual)
            mutate { s ->
                // فقط ابعاد و گروه‌بندی را به‌روز می‌کنیم؛ تغییرات هم‌زمان کاربر (امتیاز، علامت…) حفظ می‌شود.
                val cur = s.project
                val photos = cur.photos.map { p -> dims[p.id]?.let { d -> p.copy(width = d.first, height = d.second) } ?: p }
                s.copy(project = cur.copy(photos = photos, assignments = assignments), analyzing = false, progress = 1f)
            }
            if (errs.isNotEmpty()) reportErrors(errs)
            val saved = _state.value.project
            store.save(saved)
            FingerprintCache.save(store.printsFile(saved.id), saved.photos, prints)
        }
    }

    private fun decodeError(p: Photo): AppError {
        val exists = FileOps.stat(ctx, Uri.parse(p.uri)) != null
        val kind = when {
            !exists -> ErrorKind.UNREADABLE
            p.ext in setOf("heic", "heif") && android.os.Build.VERSION.SDK_INT < 28 -> ErrorKind.UNSUPPORTED
            p.ext !in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif") -> ErrorKind.UNSUPPORTED
            else -> ErrorKind.CORRUPT
        }
        return makeError(kind, p.name, if (exists) "تصویر قابل خواندن نیست" else "فایل پیدا نشد")
    }

    internal fun makeError(kind: ErrorKind, subject: String, msg: String) = AppError(
        kind = kind, subject = subject, message = msg, advice = advice(kind),
    )

    internal fun advice(kind: ErrorKind): String = when (kind) {
        ErrorKind.UNREADABLE -> "بررسی کن فایل هنوز وجود دارد و دسترسی پوشه فعال است؛ سپس در «پوشه‌ها» اسکن مجدد بزن."
        ErrorKind.CORRUPT -> "فایل ناقص یا خراب است؛ نسخه‌ی سالم را دوباره کپی کن یا آن را از پروژه حذف کن."
        ErrorKind.UNSUPPORTED -> "این فرمت روی این گوشی پشتیبانی نمی‌شود؛ آن را با ابزار دیگری به JPG یا PNG تبدیل کن."
        ErrorKind.NO_SPACE -> "فضای مقصد پر است؛ از «مدیریت فضا» فایل‌های حجیم را بررسی کن یا مقصد دیگری انتخاب کن."
        ErrorKind.NO_ACCESS -> "دسترسی برنامه به پوشه قطع شده؛ پوشه را دوباره از «پوشه‌ها» اضافه کن."
        ErrorKind.CONFLICT -> "نام تکراری است؛ قانون نام‌گذاری را عوض کن یا گزینه‌ی «نگه‌داشتن هر دو» را بزن."
        ErrorKind.OTHER -> "عملیات را دوباره امتحان کن؛ اگر تکرار شد از «سلامت پروژه» گزارش بگیر."
    }

    /** قابلیت ۱۸: همه‌ی خطاها در یک صفحه جمع می‌شوند. */
    internal fun reportErrors(list: List<AppError>) {
        if (list.isEmpty()) return
        updateProject { p ->
            p.copy(errors = (list + p.errors).distinctBy { it.kind to it.subject }.take(200))
        }
    }

    fun clearErrors() = updateProject { it.copy(errors = emptyList()) }

    fun dismissError(id: String) = updateProject { p -> p.copy(errors = p.errors.filter { it.id != id }) }

    // ---------- import / edit ----------

    fun grantTree(uri: Uri) {
        val r = ctx.contentResolver
        runCatching {
            r.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }.onFailure {
            runCatching { r.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }

    private data class Meta(val name: String, val size: Long, val modified: Long)

    private fun queryMeta(uri: Uri): Meta {
        var name: String? = null
        var size = 0L
        var mod = 0L
        runCatching {
            ctx.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE, "last_modified"), null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                    if (c.columnCount > 2 && !c.isNull(2)) mod = c.getLong(2)
                }
            }
        }
        return Meta(name ?: uri.lastPathSegment ?: "photo", size, mod)
    }

    fun importUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val resolver = ctx.contentResolver
        val existing = _state.value.project.photos.map { it.uri }.toHashSet()
        val added = uris.filter { it.toString() !in existing }.map { uri ->
            runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val m = queryMeta(uri)
            Photo(uri = uri.toString(), name = m.name, size = m.size, modified = m.modified)
        }
        if (added.isEmpty()) { toast("این عکس‌ها قبلاً اضافه شده‌اند."); return }
        mutate { s -> s.copy(project = s.project.copy(photos = s.project.photos + added), screen = Screen.STUDIO) }
        persist()
        analyze()
    }

    fun removePhoto(photoId: String) {
        prints.remove(photoId)
        mutate { s ->
            val p = s.project
            s.copy(project = p.copy(
                photos = p.photos.filter { it.id != photoId },
                assignments = p.assignments.filterKeys { it != photoId }.filterValues { it.groupId != photoId },
            ))
        }
        persist()
        analyze()
    }

    private fun putAssignment(a: Assignment) {
        mutate { s -> s.copy(project = s.project.copy(assignments = s.project.assignments + (a.photoId to a))) }
        persist()
    }

    fun confirmVersion(photoId: String, groupId: String) {
        if (photoId == groupId) { makeOriginal(photoId); return }
        val prev = _state.value.project.assignments[photoId]
        putAssignment(Assignment(photoId, groupId, Role.VERSION, prev?.score ?: 1f, prev?.gap ?: 1f, false, true))
        updatePhotos(setOf(photoId)) { it.copy(reviewed = true) }
    }

    fun makeOriginal(photoId: String) {
        putAssignment(Assignment(photoId, photoId, Role.ORIGINAL, 1f, 1f, false, true))
        updatePhotos(setOf(photoId)) { it.copy(reviewed = true) }
    }

    /** Removes a photo from its group. If it was an original, its versions are unsorted too. */
    fun unsort(photoId: String) {
        mutate { s ->
            val map = s.project.assignments.toMutableMap()
            map[photoId] = Assignment(photoId, null, Role.UNSORTED, 0f, 1f, false, true)
            for ((id, a) in map.toList()) {
                if (id != photoId && a.groupId == photoId) {
                    map[id] = Assignment(id, null, Role.UNSORTED, 0f, 1f, false, true)
                }
            }
            s.copy(project = s.project.copy(assignments = map))
        }
        updatePhotos(setOf(photoId)) { it.copy(reviewed = true) }
    }

    fun setRating(photoId: String, rating: Int) = updatePhotos(setOf(photoId)) { it.copy(rating = rating, reviewed = true) }

    fun toggleBest(photoId: String) = updatePhotos(setOf(photoId)) { it.copy(best = !it.best, reviewed = true) }

    fun skipReview() { mutate { it.copy(reviewIndex = it.reviewIndex + 1) }; saveSession() }

    // ---------- navigation & settings ----------

    fun navigate(screen: Screen) {
        mutate { it.copy(screen = screen, projects = if (screen == Screen.PROJECTS) store.listProjects() else it.projects) }
        saveSession()
    }

    fun back() = navigate(_state.value.screen.parent())

    fun openCompare(groupId: String) {
        val g = _state.value.project.groups().firstOrNull { it.id == groupId }
        if (g != null) updatePhotos((g.versions.map { it.id } + g.id).toSet()) { it.copy(reviewed = true) }
        mutate { it.copy(screen = Screen.COMPARE, compareGroup = groupId) }
        saveSession()
    }

    fun setFilter(filter: Filter) { mutate { it.copy(filter = filter) }; saveSession() }

    fun clearMessage() = mutate { it.copy(message = null) }

    fun setSensitivity(value: Float) = mutate { s -> s.copy(project = s.project.copy(sensitivity = value)) }

    fun commitSensitivity() { persist(); analyze() }

    fun setSettings(f: (Settings) -> Settings) {
        mutate { it.copy(settings = f(it.settings)) }
        store.saveSettings(_state.value.settings)
    }

    fun setTheme(mode: ThemeMode) = setSettings { it.copy(themeMode = mode) }

    fun setAccent(accent: Accent) = setSettings { it.copy(accent = accent) }

    /** قابلیت ۳۷: تنظیمات اختصاصی پروژه. */
    fun updateProjectSettings(f: (ProjectSettings) -> ProjectSettings) = updateProject { it.copy(settings = f(it.settings)) }

    fun resetProject() {
        analysisJob?.cancel()
        prints.clear()
        val old = _state.value.project
        mutate {
            it.copy(
                project = Project(id = old.id, name = old.name, settings = old.settings),
                screen = Screen.STUDIO, reviewIndex = 0, analyzing = false, selection = emptySet(),
            )
        }
        persist()
    }

    // ---------- selection ----------

    fun toggleSelect(id: String) = mutate { s ->
        s.copy(selection = if (id in s.selection) s.selection - id else s.selection + id)
    }

    fun selectAll(ids: Collection<String>) = mutate { it.copy(selection = ids.toSet()) }

    fun clearSelection() = mutate { it.copy(selection = emptySet()) }

    fun setLibFilter(f: FilterSpec) = mutate { it.copy(libFilter = f) }

    // ---------- marks & categories (قابلیت‌های ۸ و ۹) ----------

    fun setMark(ids: Set<String>, mark: Mark, on: Boolean) {
        if (ids.isEmpty()) return
        updatePhotos(ids) { it.copy(marks = if (on) it.marks + mark else it.marks - mark, reviewed = true) }
    }

    fun addCategory(name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        updateProject { p -> if (n in p.categories) p else p.copy(categories = p.categories + n) }
    }

    fun deleteCategory(name: String) = updateProject { p ->
        p.copy(
            categories = p.categories - name,
            photos = p.photos.map { if (name in it.categories) it.copy(categories = it.categories - name) else it },
        )
    }

    fun setCategory(ids: Set<String>, name: String, on: Boolean) {
        if (ids.isEmpty()) return
        updatePhotos(ids) { it.copy(categories = if (on) it.categories + name else it.categories - name) }
    }

    // ---------- favorites (قابلیت ۱۰) ----------

    fun toggleFavorite(uri: String, label: String) = setSettings { s ->
        if (s.favorites.any { it.uri == uri }) s.copy(favorites = s.favorites.filter { it.uri != uri })
        else s.copy(favorites = s.favorites + FavoriteFolder(uri, label))
    }

    fun addFavoritePath(uri: Uri) {
        grantTree(uri)
        val label = androidx.documentfile.provider.DocumentFile.fromTreeUri(ctx, uri)?.name ?: "پوشه"
        setSettings { s -> if (s.favorites.any { it.uri == uri.toString() }) s else s.copy(favorites = s.favorites + FavoriteFolder(uri.toString(), label)) }
    }

    // ---------- naming patterns (قابلیت ۲۸) ----------

    fun savePattern(name: String, template: String) {
        if (name.isBlank() || template.isBlank()) return
        setSettings { s -> s.copy(patterns = s.patterns + NamingPattern(UUID.randomUUID().toString(), name.trim(), template)) }
    }

    fun deletePattern(id: String) = setSettings { s -> s.copy(patterns = s.patterns.filter { it.id != id }) }

    // ---------- jobs (قابلیت‌های ۲۳، ۲۴، ۲۵) ----------

    inner class JobScope {
        suspend fun checkpoint() {
            while (paused.value) {
                delay(150)
                currentCoroutineContext().ensureActive()
            }
            currentCoroutineContext().ensureActive()
        }

        fun report(done: Int, failed: Int, note: String = "") {
            mutate { s -> s.copy(job = s.job?.copy(done = done, failed = failed, note = note)) }
            val now = System.currentTimeMillis()
            val j = _state.value.job ?: return
            if (now - lastNotify > 700) {
                lastNotify = now
                Notifier.updateProgress(ctx, j.title, done + failed, j.total, paused.value)
            }
        }

        fun total(n: Int) = mutate { s -> s.copy(job = s.job?.copy(total = n)) }
    }

    /** body باید خلاصه‌ی نتیجه را برگرداند. نتایج جزئی حتی در صورت لغو توسط خود body ثبت می‌شوند. */
    internal fun startJob(title: String, total: Int, canPause: Boolean = true, body: suspend JobScope.() -> String): Boolean {
        if (jobHandle?.isActive == true || _state.value.job?.finished == false) {
            toast("یک عملیات دیگر در حال اجراست؛ صبر کن یا لغوش کن.")
            return false
        }
        paused.value = false
        mutate { it.copy(job = JobState(UUID.randomUUID().toString(), title, total, canPause = canPause)) }
        runCatching {
            Notifier.ensureChannels(ctx)
            ContextCompat.startForegroundService(ctx, Intent(ctx, JobService::class.java).putExtra("title", title))
        }
        jobHandle = viewModelScope.launch(Dispatchers.IO) {
            var status = JobStatus.DONE
            var summary: String
            try {
                summary = JobScope().body()
            } catch (e: CancellationException) {
                status = JobStatus.CANCELLED
                summary = "عملیات لغو شد؛ کارهای انجام‌شده تا این لحظه حفظ و ثبت شد."
            } catch (e: Exception) {
                status = JobStatus.FAILED
                summary = "خطا: ${e.message ?: "نامشخص"}"
            }
            withContext(NonCancellable) {
                mutate { s -> s.copy(job = s.job?.copy(status = status, note = summary)) }
                runCatching { ctx.stopService(Intent(ctx, JobService::class.java)) }
                if (_state.value.settings.notifications) Notifier.done(ctx, title, summary)
            }
        }
        return true
    }

    fun togglePause() {
        val j = _state.value.job ?: return
        if (j.finished || !j.canPause) return
        paused.value = !paused.value
        mutate { s -> s.copy(job = s.job?.copy(status = if (paused.value) JobStatus.PAUSED else JobStatus.RUNNING)) }
        Notifier.updateProgress(ctx, j.title, j.done + j.failed, j.total, paused.value)
    }

    fun cancelJob() {
        paused.value = false
        jobHandle?.cancel()
    }

    fun dismissJob() = mutate { it.copy(job = null) }

    /** قابلیت ۳۵: اگر تعداد فایل‌ها از آستانه بیشتر باشد، ابتدا صفحه‌ی تأیید نشان داده می‌شود. */
    internal fun guard(count: Int, title: String, lines: List<String>, action: () -> Unit) {
        val s = _state.value.settings
        if (!s.lockEnabled || count < s.lockThreshold) { action(); return }
        pendingAction = action
        mutate { it.copy(confirm = ConfirmInfo(title, lines)) }
    }

    fun confirmed() {
        val a = pendingAction
        pendingAction = null
        mutate { it.copy(confirm = null) }
        a?.invoke()
    }

    fun dismissConfirm() {
        pendingAction = null
        mutate { it.copy(confirm = null) }
    }

    // ---------- recovery bootstrap (قابلیت ۴۰) ----------

    private fun checkRecovery() {
        val j = Journal.load(store.journalFile(_state.value.project.id)) ?: return
        mutate { it.copy(recovery = RecoveryInfo(j.type, j.title, j.done.size, j.tasks.size)) }
    }

    internal fun clearRecovery() = mutate { it.copy(recovery = null) }

    internal fun reloadProject(p: Project) {
        analysisJob?.cancel()
        mutate {
            it.copy(
                project = p, selection = emptySet(), libFilter = FilterSpec(), renamePreview = emptyList(),
                movePreview = emptyList(), copyPreview = null, folderCompare = null, dupes = emptyList(),
                dupesScanned = false, health = emptyList(), exportPreview = null, compressPreview = null,
                compareGroup = null, reviewIndex = 0, recovery = null, filter = Filter.ALL,
                projects = store.listProjects(),
            )
        }
        loadPrints(p)
        checkRecovery()
        if (p.photos.isNotEmpty()) analyze()
    }
}
