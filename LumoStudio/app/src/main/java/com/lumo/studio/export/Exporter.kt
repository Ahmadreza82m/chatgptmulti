package com.lumo.studio.export

import com.lumo.studio.engine.FileSafe
import com.lumo.studio.engine.NameTemplate
import com.lumo.studio.model.*

data class ExportOptions(
    val template: String = "{group}_{role}_{n}",
    val folderTemplate: String = "{gi} - {group}",
    val byGroup: Boolean = false,
    val includeUnsorted: Boolean = true,
    val scope: ExportScope = ExportScope.BOTH,
    val groupIds: Set<String>? = null,
    val zipMode: ZipMode = ZipMode.NONE,
    val zipCount: Int = 200,
    val zipMaxMb: Int = 500,
    val persianRoles: Boolean = false,
)

fun ProjectSettings.toOptions(groupIds: Set<String>? = null) = ExportOptions(
    template, folderTemplate, byGroup, includeUnsorted, scope, groupIds, zipMode, zipCount, zipMaxMb, persianRoles,
)

class PlanItem(val photo: Photo, val role: Role, val folder: String, val fileName: String) {
    val relPath: String get() = if (folder.isEmpty()) fileName else "$folder/$fileName"
}

class ExportPlan(
    val items: List<PlanItem>,
    val folders: List<String>,
    val bytes: Long,
    val renamedForUniqueness: Int,
    val skippedMissing: Int,
)

/** قابلیت‌های ۲۷ و ۲۹: انتخاب محتوای خروجی و پیش‌نمایش ساختار، بدون نوشتن هیچ فایلی. */
object ExportPlanner {

    fun plan(project: Project, o: ExportOptions): ExportPlan {
        val groups = project.groups()
        val picked = ArrayList<Triple<Photo, Role, Pair<Int, String>>>() // photo, role, (gi,groupName)
        val vNum = HashMap<String, Int>()
        val kNum = HashMap<String, Int>()
        var missing = 0

        groups.forEachIndexed { index, g ->
            if (o.groupIds != null && g.id !in o.groupIds) return@forEachIndexed
            val orig = g.original ?: return@forEachIndexed
            if (o.scope == ExportScope.APPROVED && !(g.versions.isNotEmpty() && g.pending == 0)) return@forEachIndexed
            val gi = index + 1
            val gname = FileSafe.clean(orig.baseName)
            val members = buildList {
                add(orig to Role.ORIGINAL)
                g.versions.forEach { add(it to Role.VERSION) }
            }.filter { (p, role) ->
                when (o.scope) {
                    ExportScope.ORIGINALS -> role == Role.ORIGINAL
                    ExportScope.VERSIONS -> role == Role.VERSION
                    ExportScope.BEST -> p.best
                    else -> true
                }
            }
            // شمارنده‌ها همیشه از عکس اصلی شروع می‌شوند، حتی اگر فقط بخشی از اعضا خروجی شوند.
            val order = HashMap<String, Pair<Int, Int>>()
            order[orig.id] = 1 to 0
            var k = 1
            var v = 0
            g.versions.forEach { k++; v++; order[it.id] = k to v }
            members.forEach { (p, role) ->
                if (p.missing) { missing++; return@forEach }
                val (kk, vv) = order.getValue(p.id)
                picked += Triple(p, role, gi to gname)
                kNum[p.id] = kk; vNum[p.id] = if (role == Role.VERSION) vv else 0
            }
        }

        val wantUnsorted = o.includeUnsorted && o.groupIds == null &&
            (o.scope == ExportScope.BOTH || o.scope == ExportScope.BEST || o.scope == ExportScope.VERSIONS)
        if (wantUnsorted) {
            var i = 0
            project.unsorted().filter { o.scope != ExportScope.BEST || it.best }.forEach { p ->
                if (p.missing) { missing++; return@forEach }
                i++
                picked += Triple(p, Role.UNSORTED, 0 to "_Unsorted")
                kNum[p.id] = i; vNum[p.id] = 0
            }
        }

        val taken = HashMap<String, MutableSet<String>>()
        var renamed = 0
        val items = picked.map { (p, role, g) ->
            val ctx = NameTemplate.Ctx(
                gi = g.first, group = g.second, role = role, name = p.baseName,
                n = vNum[p.id] ?: 0, k = kNum[p.id] ?: 1, persianRoles = o.persianRoles,
            )
            val folder = when {
                role == Role.UNSORTED -> "_Unsorted"
                o.byGroup -> NameTemplate.render(
                    o.folderTemplate,
                    NameTemplate.Ctx(g.first, g.second, role, p.baseName, 0, 1, o.persianRoles),
                )
                else -> ""
            }
            val ext = p.ext.ifEmpty { "jpg" }
            var name = NameTemplate.render(o.template, ctx) + "." + ext
            val set = taken.getOrPut(folder) { HashSet() }
            if (!set.add(name.lowercase())) {
                renamed++
                val base = name.substringBeforeLast('.')
                var n = 2
                while (!set.add("${base}_$n.$ext".lowercase())) n++
                name = "${base}_$n.$ext"
            }
            PlanItem(p, role, folder, name)
        }
        return ExportPlan(
            items = items,
            folders = items.map { it.folder }.filter { it.isNotEmpty() }.distinct(),
            bytes = items.sumOf { it.photo.size },
            renamedForUniqueness = renamed,
            skippedMissing = missing,
        )
    }

    fun treeLines(plan: ExportPlan, root: String, limit: Int = 60): List<String> {
        val lines = ArrayList<String>()
        lines += root
        var shown = 0
        val byFolder = plan.items.groupBy { it.folder }
        for ((folder, list) in byFolder.toSortedMap()) {
            if (folder.isNotEmpty()) lines += "  $folder"
            for (it in list) {
                if (shown >= limit) break
                lines += (if (folder.isEmpty()) "  " else "    ") + it.fileName
                shown++
            }
            if (shown >= limit) break
        }
        if (plan.items.size > shown) lines += "  … و ${plan.items.size - shown} فایل دیگر"
        return lines
    }
}
