package com.ahmadreza.photoflow

/**
 * Builds deterministic, collision-aware file names for recursive batch renaming.
 *
 * Supported tokens:
 * {name}       original filename without extension
 * {clean}      cleaned filename stem (numeric prefixes / noisy separators removed)
 * {index}      01, 02, 03... (always numbered)
 * {number}     1, 2, 3... (always numbered)
 * {autoIndex}  empty for the first occurrence of a cleaned name, then  - 01, - 02...
 * {ext}        original extension, without the dot
 * {folder}     immediate parent folder name
 *
 * The planner never invents a person's identity. A name must come from a source filename
 * or from a separately confirmed image-group assignment.
 */
object SmartRenameEngine {
    data class RenameItem(val oldName: String, val parentPath: String, val newName: String)

    fun cleanStem(fileName: String): String {
        val raw = fileName.substringBeforeLast('.', fileName)
        var value = raw
            .replace('_', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()

        // Remove common leading counters / export prefixes, but preserve meaningful words.
        val prefixes = listOf(
            Regex("^\\s*\\d{1,6}\\s*[-_.،:)]\\s*"),
            Regex("^\\s*\\(\\d{1,6}\\)\\s*"),
            Regex("^\\s*(?:copy|copie|کپی)\\s*[-_ ]*\\d*\\s*[-_ ]*", RegexOption.IGNORE_CASE)
        )
        var changed: Boolean
        do {
            val before = value
            prefixes.forEach { value = value.replace(it, "") }
            changed = value != before
        } while (changed)

        return value
            .replace(Regex("[\\s._-]+$"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun buildPlan(
        files: List<Pair<String, String>>,
        pattern: String
    ): List<RenameItem> {
        require(files.isNotEmpty()) { "ابتدا فایل‌ها را انتخاب کن." }
        require(pattern.isNotBlank()) { "الگوی نام‌گذاری خالی است." }

        val cleaned = files.map { (_, name) -> cleanStem(name).ifBlank { name.substringBeforeLast('.', name) } }
        val totals = cleaned.groupingBy { it.lowercase() }.eachCount()
        val seen = mutableMapOf<String, Int>()

        val planned = files.mapIndexed { index, (parentPath, oldName) ->
            val ext = oldName.substringAfterLast('.', "").takeIf { '.' in oldName }.orEmpty()
            val originalStem = oldName.substringBeforeLast('.', oldName)
            val clean = cleaned[index]
            val key = clean.lowercase()
            val occurrence = (seen[key] ?: 0) + 1
            seen[key] = occurrence
            val autoIndex = if (totals[key] > 1 && occurrence > 1) " - %02d".format(occurrence - 1) else ""

            var result = pattern
                .replace("{name}", originalStem)
                .replace("{clean}", clean)
                .replace("{index}", "%02d".format(index + 1))
                .replace("{number}", (index + 1).toString())
                .replace("{autoIndex}", autoIndex)
                .replace("{folder}", parentPath.substringAfterLast('/').ifBlank { "پوشه" })
                .replace("{ext}", ext)
                .replace(Regex("[\\\\/:*?\\\"<>|]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()

            if (ext.isNotBlank() && !pattern.contains("{ext}") && !result.endsWith(".$ext", true)) {
                result += ".$ext"
            }
            require(result.isNotBlank() && result != ".$ext") { "نام نهایی یکی از فایل‌ها خالی است." }
            RenameItem(oldName, parentPath, result)
        }

        val duplicateTargets = planned.groupBy { (it.parentPath + "/" + it.newName).lowercase() }
            .filterValues { it.size > 1 }
        require(duplicateTargets.isEmpty()) { "الگو برای چند فایل در یک پوشه نام یکسان ساخته است." }
        return planned
    }

    fun previewName(fileName: String, parentPath: String, pattern: String, index: Int, occurrence: Int = 1, total: Int = 1): String {
        val ext = fileName.substringAfterLast('.', "").takeIf { '.' in fileName }.orEmpty()
        val originalStem = fileName.substringBeforeLast('.', fileName)
        val clean = cleanStem(fileName).ifBlank { originalStem }
        val autoIndex = if (total > 1 && occurrence > 1) " - %02d".format(occurrence - 1) else ""
        var result = pattern
            .replace("{name}", originalStem)
            .replace("{clean}", clean)
            .replace("{index}", "%02d".format(index + 1))
            .replace("{number}", (index + 1).toString())
            .replace("{autoIndex}", autoIndex)
            .replace("{folder}", parentPath.substringAfterLast('/').ifBlank { "پوشه" })
            .replace("{ext}", ext)
            .replace(Regex("[\\\\/:*?\\\"<>|]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (ext.isNotBlank() && !pattern.contains("{ext}") && !result.endsWith(".$ext", true)) result += ".$ext"
        return result
    }
}