package com.lumo.studio.data

import android.content.Context
import com.lumo.studio.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ProjectInfo(val id: String, val name: String, val count: Int, val updated: Long)

/** وضعیت آخرین صفحه‌ی باز (قابلیت ۲۰: ذخیره‌ی خودکار). */
data class Session(val screen: String, val filter: String, val reviewIndex: Int, val compareGroup: String?)

/** Local persistence only. Nothing is uploaded anywhere. */
class ProjectStore(private val context: Context) {

    private val root = File(context.filesDir, "projects").also { it.mkdirs() }
    private val indexFile = File(root, "index.json")
    private val prefs = context.getSharedPreferences("lumo_prefs", Context.MODE_PRIVATE)

    init { migrateLegacy() }

    // ---------- projects ----------

    fun dir(id: String): File = File(root, id).also { it.mkdirs() }

    fun journalFile(id: String) = File(dir(id), "journal.json")

    fun printsFile(id: String) = File(dir(id), "prints.bin")

    @Synchronized
    fun listProjects(): List<ProjectInfo> = runCatching {
        val arr = JSONArray(indexFile.readText())
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            ProjectInfo(o.getString("id"), o.optString("name"), o.optInt("count"), o.optLong("updated"))
        }
    }.getOrDefault(emptyList()).filter { fileIn(root, it.id, "project.json").exists() }

    private fun fileIn(parent: File, a: String, b: String) = java.io.File(java.io.File(parent, a), b)

    fun activeId(): String {
        val saved = prefs.getString("active", null)
        val list = listProjects()
        if (saved != null && list.any { it.id == saved }) return saved
        if (list.isNotEmpty()) return list.first().id.also { setActive(it) }
        return create("پروژه جدید").id
    }

    fun setActive(id: String) = prefs.edit().putString("active", id).apply()

    fun create(name: String): Project {
        val p = Project(name = name)
        save(p)
        setActive(p.id)
        return p
    }

    fun load(id: String): Project = runCatching {
        parse(JSONObject(fileIn(root, id, "project.json").readText()))
    }.getOrElse { Project(id = id) }

    @Synchronized
    fun save(p: Project) {
        val d = dir(p.id)
        val tmp = java.io.File(d, "project.tmp")
        val file = java.io.File(d, "project.json")
        tmp.writeText(serialize(p).toString())
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
        val list = listProjects().filter { it.id != p.id } +
            ProjectInfo(p.id, p.name, p.photos.count { !it.trashed }, System.currentTimeMillis())
        indexFile.writeText(JSONArray().apply {
            list.forEach {
                put(JSONObject().put("id", it.id).put("name", it.name).put("count", it.count).put("updated", it.updated))
            }
        }.toString())
    }

    @Synchronized
    fun delete(id: String) {
        java.io.File(root, id).deleteRecursively()
        val list = listProjects().filter { it.id != id }
        indexFile.writeText(JSONArray().apply {
            list.forEach {
                put(JSONObject().put("id", it.id).put("name", it.name).put("count", it.count).put("updated", it.updated))
            }
        }.toString())
    }

    fun dirSize(id: String): Long = java.io.File(root, id).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun migrateLegacy() {
        val legacy = java.io.File(context.filesDir, "project.json")
        if (!legacy.exists() || indexFile.exists()) return
        runCatching {
            val p = parse(JSONObject(legacy.readText()))
            save(p)
            setActive(p.id)
            legacy.delete()
        }
    }

    // ---------- global settings ----------

    fun loadSettings(): Settings {
        val favs = runCatching {
            val a = JSONArray(prefs.getString("favorites", "[]"))
            (0 until a.length()).map { FavoriteFolder(a.getJSONObject(it).getString("uri"), a.getJSONObject(it).getString("label")) }
        }.getOrDefault(emptyList())
        val pats = runCatching {
            val a = JSONArray(prefs.getString("patterns", null) ?: return@runCatching defaultPatterns())
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                NamingPattern(o.getString("id"), o.getString("name"), o.getString("template"))
            }
        }.getOrDefault(defaultPatterns())
        return Settings(
            themeMode = runCatching { ThemeMode.valueOf(prefs.getString("theme", "SYSTEM") ?: "SYSTEM") }
                .getOrDefault(ThemeMode.SYSTEM),
            accent = runCatching { Accent.valueOf(prefs.getString("accent", "AURORA") ?: "AURORA") }
                .getOrDefault(Accent.AURORA),
            favorites = favs,
            patterns = pats,
            lockEnabled = prefs.getBoolean("lock", true),
            lockThreshold = prefs.getInt("lockN", 50),
            notifications = prefs.getBoolean("notif", true),
        )
    }

    fun saveSettings(s: Settings) {
        prefs.edit()
            .putString("theme", s.themeMode.name)
            .putString("accent", s.accent.name)
            .putString("favorites", JSONArray().apply {
                s.favorites.forEach { put(JSONObject().put("uri", it.uri).put("label", it.label)) }
            }.toString())
            .putString("patterns", JSONArray().apply {
                s.patterns.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("template", it.template)) }
            }.toString())
            .putBoolean("lock", s.lockEnabled)
            .putInt("lockN", s.lockThreshold)
            .putBoolean("notif", s.notifications)
            .apply()
    }

    fun loadSession(): Session? {
        val screen = prefs.getString("s_screen", null) ?: return null
        return Session(
            screen,
            prefs.getString("s_filter", "ALL") ?: "ALL",
            prefs.getInt("s_review", 0),
            prefs.getString("s_compare", null),
        )
    }

    fun saveSession(s: Session) {
        prefs.edit().putString("s_screen", s.screen).putString("s_filter", s.filter)
            .putInt("s_review", s.reviewIndex).putString("s_compare", s.compareGroup).apply()
    }

    // ---------- (de)serialisation ----------

    fun toJson(p: Project): String = serialize(p).toString(2)

    fun fromJson(text: String): Project = parse(JSONObject(text))

    fun assignmentsToJson(m: Map<String, Assignment>): String = JSONArray().apply {
        m.values.forEach { put(assignment(it)) }
    }.toString()

    fun assignmentsFromJson(s: String): Map<String, Assignment> {
        val a = JSONArray(s)
        return (0 until a.length()).map { assignmentFrom(a.getJSONObject(it)) }.associateBy { it.photoId }
    }

    private fun assignment(a: Assignment) = JSONObject().apply {
        put("photoId", a.photoId)
        put("groupId", a.groupId ?: JSONObject.NULL)
        put("role", a.role.name)
        put("score", a.score.toDouble())
        put("gap", a.gap.toDouble())
        put("review", a.needsReview)
        put("manual", a.manual)
    }

    private fun assignmentFrom(j: JSONObject) = Assignment(
        photoId = j.getString("photoId"),
        groupId = if (j.isNull("groupId")) null else j.getString("groupId"),
        role = Role.valueOf(j.getString("role")),
        score = j.optDouble("score", 0.0).toFloat(),
        gap = j.optDouble("gap", 1.0).toFloat(),
        needsReview = j.optBoolean("review"),
        manual = j.optBoolean("manual"),
    )

    private fun strArr(items: Collection<String>) = JSONArray().apply { items.forEach { put(it) } }

    private fun strList(a: JSONArray?): List<String> =
        if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }

    private fun serialize(p: Project): JSONObject = JSONObject().apply {
        put("id", p.id)
        put("name", p.name)
        put("created", p.created)
        put("sensitivity", p.sensitivity.toDouble())
        put("categories", strArr(p.categories))
        put("photos", JSONArray().apply {
            p.photos.forEach { ph ->
                put(JSONObject().apply {
                    put("id", ph.id); put("uri", ph.uri); put("name", ph.name)
                    put("w", ph.width); put("h", ph.height)
                    put("rating", ph.rating); put("best", ph.best)
                    put("folderId", ph.folderId ?: JSONObject.NULL)
                    put("parent", ph.parentUri ?: JSONObject.NULL)
                    put("size", ph.size); put("modified", ph.modified); put("hash", ph.hash)
                    put("marks", strArr(ph.marks.map { it.name }))
                    put("cats", strArr(ph.categories))
                    put("reviewed", ph.reviewed); put("missing", ph.missing); put("trashed", ph.trashed)
                })
            }
        })
        put("assignments", JSONArray().apply { p.assignments.values.forEach { put(assignment(it)) } })
        put("folders", JSONArray().apply {
            p.folders.forEach {
                put(JSONObject().put("id", it.id).put("uri", it.uri).put("label", it.label)
                    .put("kind", it.kind.name).put("lastScan", it.lastScan).put("summary", it.lastSummary))
            }
        })
        put("settings", JSONObject().apply {
            val s = p.settings
            put("template", s.template); put("folderTemplate", s.folderTemplate)
            put("byGroup", s.byGroup); put("includeUnsorted", s.includeUnsorted)
            put("scope", s.scope.name); put("zipMode", s.zipMode.name)
            put("zipCount", s.zipCount); put("zipMaxMb", s.zipMaxMb)
            put("persianRoles", s.persianRoles); put("convertFormat", s.convertFormat)
            put("imageQuality", s.imageQuality)
        })
        put("history", JSONArray().apply {
            p.history.forEach { e ->
                put(JSONObject().apply {
                    put("id", e.id); put("time", e.time); put("type", e.type.name); put("title", e.title)
                    put("details", strArr(e.details))
                    put("steps", JSONArray().apply {
                        e.steps.forEach { s ->
                            put(JSONObject().put("k", s.kind).put("p", s.photoId).put("a", s.a)
                                .put("b", s.b).put("c", s.c).put("d", s.d))
                        }
                    })
                    put("snapshot", e.snapshot ?: JSONObject.NULL)
                    put("undoable", e.undoable); put("undone", e.undone)
                })
            }
        })
        put("exports", JSONArray().apply {
            p.exports.forEach { r ->
                put(JSONObject().apply {
                    put("id", r.id); put("time", r.time); put("version", r.version)
                    put("folderName", r.folderName); put("folderUri", r.folderUri)
                    put("files", r.files); put("bytes", r.bytes); put("summary", r.summary)
                    put("zips", JSONArray().apply {
                        r.zips.forEach { z -> put(JSONObject().put("uri", z.uri).put("name", z.name).put("bytes", z.bytes)) }
                    })
                })
            }
        })
        put("errors", JSONArray().apply {
            p.errors.forEach { e ->
                put(JSONObject().put("id", e.id).put("time", e.time).put("kind", e.kind.name)
                    .put("subject", e.subject).put("message", e.message).put("advice", e.advice))
            }
        })
        put("copyLog", JSONObject().apply { p.copyLog.forEach { (k, v) -> put(k, strArr(v)) } })
    }

    private fun parse(o: JSONObject): Project {
        val photosArr = o.optJSONArray("photos") ?: JSONArray()
        val photos = (0 until photosArr.length()).map { i ->
            val j = photosArr.getJSONObject(i)
            Photo(
                uri = j.getString("uri"),
                name = j.optString("name", "photo"),
                id = j.getString("id"),
                width = j.optInt("w"), height = j.optInt("h"),
                rating = j.optInt("rating"), best = j.optBoolean("best"),
                folderId = if (j.isNull("folderId")) null else j.optString("folderId"),
                parentUri = if (j.isNull("parent")) null else j.optString("parent"),
                size = j.optLong("size"), modified = j.optLong("modified"), hash = j.optString("hash"),
                marks = strList(j.optJSONArray("marks")).mapNotNull { runCatching { Mark.valueOf(it) }.getOrNull() }.toSet(),
                categories = strList(j.optJSONArray("cats")).toSet(),
                reviewed = j.optBoolean("reviewed"), missing = j.optBoolean("missing"),
                trashed = j.optBoolean("trashed"),
            )
        }
        val asArr = o.optJSONArray("assignments") ?: JSONArray()
        val assignments = (0 until asArr.length()).map { assignmentFrom(asArr.getJSONObject(it)) }
            .associateBy { it.photoId }
        val fArr = o.optJSONArray("folders") ?: JSONArray()
        val folders = (0 until fArr.length()).map {
            val j = fArr.getJSONObject(it)
            ProjectFolder(
                id = j.getString("id"), uri = j.getString("uri"), label = j.optString("label", "پوشه"),
                kind = runCatching { FolderKind.valueOf(j.optString("kind")) }.getOrDefault(FolderKind.OTHER),
                lastScan = j.optLong("lastScan"), lastSummary = j.optString("summary"),
            )
        }
        val st = o.optJSONObject("settings")
        val d = ProjectSettings()
        val settings = if (st == null) d else ProjectSettings(
            template = st.optString("template", d.template),
            folderTemplate = st.optString("folderTemplate", d.folderTemplate),
            byGroup = st.optBoolean("byGroup", d.byGroup),
            includeUnsorted = st.optBoolean("includeUnsorted", d.includeUnsorted),
            scope = runCatching { ExportScope.valueOf(st.optString("scope")) }.getOrDefault(d.scope),
            zipMode = runCatching { ZipMode.valueOf(st.optString("zipMode")) }.getOrDefault(d.zipMode),
            zipCount = st.optInt("zipCount", d.zipCount),
            zipMaxMb = st.optInt("zipMaxMb", d.zipMaxMb),
            persianRoles = st.optBoolean("persianRoles", d.persianRoles),
            convertFormat = st.optString("convertFormat", d.convertFormat),
            imageQuality = st.optInt("imageQuality", d.imageQuality),
        )
        val hArr = o.optJSONArray("history") ?: JSONArray()
        val history = (0 until hArr.length()).mapNotNull { i ->
            runCatching {
                val j = hArr.getJSONObject(i)
                val sArr = j.optJSONArray("steps") ?: JSONArray()
                OpEntry(
                    id = j.getString("id"), time = j.optLong("time"),
                    type = OpType.valueOf(j.getString("type")), title = j.optString("title"),
                    details = strList(j.optJSONArray("details")),
                    steps = (0 until sArr.length()).map {
                        val s = sArr.getJSONObject(it)
                        UndoStep(s.getString("k"), s.optString("p"), s.optString("a"), s.optString("b"),
                            s.optString("c"), s.optString("d"))
                    },
                    snapshot = if (j.isNull("snapshot")) null else j.optString("snapshot"),
                    undoable = j.optBoolean("undoable"), undone = j.optBoolean("undone"),
                )
            }.getOrNull()
        }
        val eArr = o.optJSONArray("exports") ?: JSONArray()
        val exports = (0 until eArr.length()).map { i ->
            val j = eArr.getJSONObject(i)
            val zArr = j.optJSONArray("zips") ?: JSONArray()
            ExportRecord(
                id = j.getString("id"), time = j.optLong("time"), version = j.optInt("version", 1),
                folderName = j.optString("folderName"), folderUri = j.optString("folderUri"),
                files = j.optInt("files"), bytes = j.optLong("bytes"), summary = j.optString("summary"),
                zips = (0 until zArr.length()).map {
                    val z = zArr.getJSONObject(it)
                    ZipInfo(z.getString("uri"), z.optString("name"), z.optLong("bytes"))
                },
            )
        }
        val errArr = o.optJSONArray("errors") ?: JSONArray()
        val errors = (0 until errArr.length()).mapNotNull { i ->
            runCatching {
                val j = errArr.getJSONObject(i)
                AppError(j.getString("id"), j.optLong("time"), ErrorKind.valueOf(j.getString("kind")),
                    j.optString("subject"), j.optString("message"), j.optString("advice"))
            }.getOrNull()
        }
        val cl = o.optJSONObject("copyLog")
        val copyLog = HashMap<String, Set<String>>()
        cl?.keys()?.forEach { k -> copyLog[k] = strList(cl.getJSONArray(k)).toSet() }
        return Project(
            id = o.optString("id").ifEmpty { java.util.UUID.randomUUID().toString() },
            name = o.optString("name", "پروژه جدید"),
            photos = photos,
            assignments = assignments,
            sensitivity = o.optDouble("sensitivity", 0.5).toFloat(),
            folders = folders,
            categories = strList(o.optJSONArray("categories")),
            settings = settings,
            history = history,
            exports = exports,
            errors = errors,
            copyLog = copyLog,
            created = o.optLong("created", System.currentTimeMillis()),
        )
    }
}
