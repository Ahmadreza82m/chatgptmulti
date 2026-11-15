package com.lumo.studio.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumo.studio.engine.NameTemplate
import com.lumo.studio.engine.Stats
import com.lumo.studio.export.ExportOptions
import com.lumo.studio.export.toOptions
import com.lumo.studio.model.*
import com.lumo.studio.ui.components.*
import com.lumo.studio.ui.theme.LocalLumo
import com.lumo.studio.viewmodel.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ====================== خروجی (۲۶–۲۹) ======================

@Composable
fun ExportScreen(state: UiState, vm: StudioViewModel) {
    val c = LocalLumo.current
    val s = state.project.settings
    var groupSel by remember { mutableStateOf<Set<String>?>(null) }
    var showGroups by remember { mutableStateOf(false) }
    var pick by remember { mutableStateOf(false) }
    var patName by remember { mutableStateOf("") }
    val options: ExportOptions = s.toOptions(groupSel)
    LaunchedEffect(options, state.project.photos, state.project.assignments) {
        kotlinx.coroutines.delay(250)
        vm.previewExport(options)
    }
    val sample = NameTemplate.render(s.template, NameTemplate.Ctx(1, "علی احمدی", Role.VERSION, "IMG_0001", 1, 2, s.persianRoles)) + ".jpg"
    val pv = state.exportPreview
    val groups = remember(state.project) { state.project.groups() }

    Page("خروجی", { vm.back() }) {
        SectionCard("چه چیزی خروجی شود؟") {
            ChipRow { ExportScope.values().forEach { sc -> LumoChip(sc.fa, s.scope == sc) { vm.updateProjectSettings { it.copy(scope = sc) } } } }
            SwitchRow("شامل «بدون گروه»", s.includeUnsorted) { v -> vm.updateProjectSettings { it.copy(includeUnsorted = v) } }
            OutlinedButton(onClick = { showGroups = true }) {
                Text(if (groupSel == null) "همه‌ی ${groups.size} گروه" else "${groupSel!!.size} گروه انتخاب‌شده")
            }
        }
        SectionCard("نام‌گذاری") {
            TextField1("الگوی نام فایل", s.template, Modifier.fillMaxWidth()) { v -> vm.updateProjectSettings { it.copy(template = v) } }
            Dim("متغیرها: {gi} شماره‌ی گروه · {group} نام گروه · {role} {role_fa} نقش · {n} شماره‌ی نسخه · {k} شماره‌ی تصویر در گروه · {name} · {date}")
            Dim("نمونه: $sample")
            Dim("الگوهای ذخیره‌شده")
            ChipRow {
                state.settings.patterns.forEach { p -> LumoChip(p.name, s.template == p.template) { vm.updateProjectSettings { it.copy(template = p.template) } } }
            }
            state.settings.patterns.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${p.name}:  ${p.template}", color = c.fg2, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.deletePattern(p.id) }) { Text("حذف") }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField1("نام الگو", patName, Modifier.weight(1f)) { patName = it }
                OutlinedButton(onClick = { vm.savePattern(patName, s.template); patName = "" }, enabled = patName.isNotBlank()) { Text("ذخیره") }
            }
            SwitchRow("نقش‌ها فارسی باشند ({role})", s.persianRoles) { v -> vm.updateProjectSettings { it.copy(persianRoles = v) } }
        }
        SectionCard("ساختار پوشه") {
            ChipRow {
                LumoChip("همه یک‌جا", !s.byGroup) { vm.updateProjectSettings { it.copy(byGroup = false) } }
                LumoChip("پوشه برای هر گروه", s.byGroup) { vm.updateProjectSettings { it.copy(byGroup = true) } }
            }
            if (s.byGroup) TextField1("الگوی نام پوشه‌ی گروه", s.folderTemplate, Modifier.fillMaxWidth()) { v -> vm.updateProjectSettings { it.copy(folderTemplate = v) } }
        }
        SectionCard("ZIP") {
            ChipRow { ZipMode.values().forEach { z -> LumoChip(z.fa, s.zipMode == z) { vm.updateProjectSettings { it.copy(zipMode = z) } } } }
            if (s.zipMode == ZipMode.BY_COUNT) NumField("تعداد فایل در هر بسته", s.zipCount.toString()) { v -> vm.updateProjectSettings { it.copy(zipCount = v.toDoubleOrNull()?.toInt()?.coerceAtLeast(1) ?: 200) } }
            if (s.zipMode == ZipMode.BY_SIZE) NumField("حداکثر حجم هر بسته (MB)", s.zipMaxMb.toString()) { v -> vm.updateProjectSettings { it.copy(zipMaxMb = v.toDoubleOrNull()?.toInt()?.coerceAtLeast(1) ?: 500) } }
        }
        SectionCard("پیش‌نمایش خروجی (بدون ساخت فایل)") {
            if (pv == null) Dim("در حال محاسبه…") else {
                InfoLine("تعداد فایل", pv.files.toString())
                InfoLine("حجم تقریبی", Stats.fmtBytes(pv.bytes))
                if (pv.freeBytes in 1 until pv.bytes) Text("فضای آزاد کمتر از حجم خروجی است!", color = c.bad, fontSize = 12.sp)
                if (pv.renamed > 0) Dim("${pv.renamed} نام تکراری با پسوند _2 اصلاح می‌شود.")
                if (pv.missing > 0) Dim("${pv.missing} فایل ناپدیدشده نادیده گرفته می‌شود.")
                pv.lines.forEach { Text(it, color = c.fg2, fontSize = 12.sp) }
            }
        }
        Dim("از هر عکس یک کپی ساخته می‌شود؛ فایل‌های اصلی دست‌نخورده می‌مانند و manifest.csv کنار خروجی قرار می‌گیرد.")
        Button(onClick = { pick = true }, enabled = (pv?.files ?: 0) > 0 && state.job?.finished != false, modifier = Modifier.fillMaxWidth()) {
            Text("انتخاب پوشه و شروع خروجی")
        }
    }
    if (pick) DestinationDialog(state, vm, "مقصد خروجی", { pick = false; vm.runExport(it, options) }) { pick = false }
    if (showGroups) {
        val cur = groupSel ?: groups.map { it.id }.toSet()
        AlertDialog(
            onDismissRequest = { showGroups = false },
            title = { Text("انتخاب گروه‌ها") },
            text = {
                androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(groups.size) { i ->
                        val g = groups[i]
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = g.id in cur, onCheckedChange = { on -> groupSel = if (on) cur + g.id else cur - g.id })
                            Text(g.original?.name ?: "گروه", maxLines = 1, fontSize = 13.sp)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showGroups = false }) { Text("تمام") } },
            dismissButton = { TextButton(onClick = { groupSel = null; showGroups = false }) { Text("همه") } },
        )
    }
}

// ====================== خروجی‌های قبلی (۳۰، ۳۱) ======================

@Composable
fun ExportsScreen(state: UiState, vm: StudioViewModel) {
    val c = LocalLumo.current
    val s = state.project.settings
    Page("خروجی‌های قبلی", { vm.back() }) {
        if (state.project.exports.isEmpty()) Dim("هنوز خروجی‌ای ساخته نشده است.")
        state.project.exports.forEach { r ->
            GlassCard(Modifier.fillMaxWidth()) {
                Text("نسخه ${r.version} · ${state.project.name}", color = c.fg, style = MaterialTheme.typography.titleSmall)
                Dim(SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).format(Date(r.time)) + " · ${r.files} فایل · ${Stats.fmtBytes(r.bytes)}")
                Dim(r.folderName + if (r.summary.isNotEmpty()) " · ${r.summary}" else "")
                if (r.zips.isNotEmpty()) Dim("ZIP: " + r.zips.joinToString("، ") { "${it.name} (${Stats.fmtBytes(it.bytes)})" })
                ChipRow {
                    Button(onClick = { vm.shareExport(r.id, true) }, enabled = r.zips.isNotEmpty()) { Text("اشتراک ZIP") }
                    OutlinedButton(onClick = { vm.shareExport(r.id, false) }) { Text("اشتراک فایل‌ها") }
                    OutlinedButton(
                        onClick = {
                            vm.reZip(r.id, if (s.zipMode == ZipMode.NONE) ZipMode.SINGLE else s.zipMode, s.zipCount, s.zipMaxMb)
                        },
                        enabled = state.job?.finished != false,
                    ) { Text("ساخت دوباره‌ی ZIP") }
                    TextButton(onClick = { vm.deleteExport(r.id) }) { Text("حذف") }
                }
            }
        }
        Dim("ZIP دوباره با تنظیمات ZIP همین پروژه ساخته می‌شود (بخش خروجی).")
    }
}
