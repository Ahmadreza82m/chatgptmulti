package com.lumo.studio.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumo.studio.engine.*
import com.lumo.studio.export.ExportOptions
import com.lumo.studio.model.*
import com.lumo.studio.ui.components.*
import com.lumo.studio.ui.theme.LocalLumo
import com.lumo.studio.viewmodel.*

// ====================== کتابخانه‌ی فایل‌ها (۷، ۸، ۹) ======================

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(state: UiState, vm: StudioViewModel) {
    val c = LocalLumo.current
    val project = state.project
    val photos = remember(project, state.libFilter) { FilterEngine.apply(project, state.libFilter) }
    var detail by remember { mutableStateOf<Photo?>(null) }
    var showFilter by remember { mutableStateOf(false) }
    var showMarks by remember { mutableStateOf(false) }
    var showCats by remember { mutableStateOf(false) }
    val sel = state.selection

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("کتابخانه‌ی فایل‌ها", color = c.fg, style = MaterialTheme.typography.headlineSmall)
                Dim("${photos.size} از ${project.livePhotos().size} فایل" + if (sel.isNotEmpty()) " · ${sel.size} انتخاب" else "")
            }
            TextButton(onClick = { showFilter = true }) { Text(if (state.libFilter.active) "فیلتر ●" else "فیلتر") }
            TextButton(onClick = { vm.back() }) { Text("بستن") }
        }
        ChipRow {
            LumoChip("همه‌ی پوشه‌ها", state.libFilter.folderId == null) { vm.setLibFilter(state.libFilter.copy(folderId = null)) }
            project.folders.forEach { f ->
                LumoChip(f.label, state.libFilter.folderId == f.id) { vm.setLibFilter(state.libFilter.copy(folderId = f.id)) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
            OutlinedButton(onClick = { vm.selectAll(photos.map { it.id }) }) { Text("انتخاب همه (${photos.size})") }
            if (sel.isNotEmpty()) OutlinedButton(onClick = { vm.clearSelection() }) { Text("لغو انتخاب") }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (photos.isEmpty()) Dim("فایلی با این فیلتر نیست. از «پوشه‌ها» پوشه اضافه کن.")
            else LazyVerticalGrid(
                columns = GridCells.Adaptive(96.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(photos, key = { it.id }) { p ->
                    val selected = p.id in sel
                    Box(
                        Modifier.aspectRatio(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .border(if (selected) 3.dp else 0.dp, if (selected) c.g2 else Color.Transparent, RoundedCornerShape(14.dp))
                            .combinedClickable(
                                onClick = { if (sel.isNotEmpty()) vm.toggleSelect(p.id) else detail = p },
                                onLongClick = { vm.toggleSelect(p.id) },
                            ),
                    ) {
                        PhotoThumb(p.uri, 256, Modifier.fillMaxSize())
                        Row(Modifier.align(Alignment.BottomStart).background(Color(0x80000000)).padding(horizontal = 3.dp)) {
                            p.marks.forEach { Text(it.glyph, fontSize = 11.sp) }
                            if (p.best) Text("⭐", fontSize = 11.sp)
                            if (p.missing) Text("✖", fontSize = 11.sp, color = c.bad)
                        }
                        if (selected) Text("✓", color = Color.White, modifier = Modifier.align(Alignment.TopStart).background(c.g2).padding(horizontal = 5.dp))
                    }
                }
            }
        }
        if (sel.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showMarks = true }) { Text("علامت") }
                Button(onClick = { showCats = true }) { Text("دسته") }
                Button(onClick = { vm.navigate(Screen.RENAME) }) { Text("تغییر نام") }
                Button(onClick = { vm.navigate(Screen.MOVE) }) { Text("انتقال") }
                Button(onClick = { vm.navigate(Screen.COPY) }) { Text("کپی") }
                Button(onClick = { vm.navigate(Screen.PROCESS) }) { Text("تبدیل/فشرده") }
                Button(onClick = { vm.trashPhotos(sel) }) { Text("به سطل") }
            }
        }
    }

    if (showFilter) FilterDialog(state, vm) { showFilter = false }
    if (showMarks) {
        AlertDialog(
            onDismissRequest = { showMarks = false },
            title = { Text("علامت‌گذاری ${sel.size} فایل") },
            text = {
                Column {
                    Mark.values().forEach { m ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${m.glyph} ${m.fa}", modifier = Modifier.weight(1f))
                            TextButton(onClick = { vm.setMark(sel, m, true) }) { Text("افزودن") }
                            TextButton(onClick = { vm.setMark(sel, m, false) }) { Text("حذف") }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showMarks = false }) { Text("تمام") } },
        )
    }
    if (showCats) CategoryDialog(state, vm, sel) { showCats = false }
    detail?.let { p -> PhotoDetailDialog(state, vm, p) { detail = null } }
}

@Composable
private fun FilterDialog(state: UiState, vm: StudioViewModel, onDismiss: () -> Unit) {
    val f = state.libFilter
    val exts = remember(state.project) {
        state.project.livePhotos().map { if (it.ext == "jpeg") "jpg" else it.ext }.filter { it.isNotEmpty() }.distinct().sorted()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("فیلتر پیشرفته") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField1("جست‌وجوی نام", f.query, Modifier.fillMaxWidth()) { vm.setLibFilter(f.copy(query = it)) }
                Dim("فرمت")
                ChipRow { exts.forEach { e -> LumoChip(e.uppercase(), e in f.exts) { vm.setLibFilter(f.copy(exts = if (e in f.exts) f.exts - e else f.exts + e)) } } }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField("حداقل حجم (MB)", if (f.minSizeMb == 0f) "" else f.minSizeMb.toString(), Modifier.weight(1f)) {
                        vm.setLibFilter(f.copy(minSizeMb = it.toFloatOrNull() ?: 0f))
                    }
                    NumField("ضلع بزرگ‌تر از (px)", if (f.minSidePx == 0) "" else f.minSidePx.toString(), Modifier.weight(1f)) {
                        vm.setLibFilter(f.copy(minSidePx = it.toDoubleOrNull()?.toInt() ?: 0))
                    }
                }
                Dim("تاریخ فایل")
                ChipRow { DateRange.values().forEach { d -> LumoChip(d.fa, f.date == d) { vm.setLibFilter(f.copy(date = d)) } } }
                SwitchRow("فقط بدون گروه", f.onlyUngrouped) { vm.setLibFilter(f.copy(onlyUngrouped = it)) }
                SwitchRow("فقط بررسی‌نشده", f.onlyUnreviewed) { vm.setLibFilter(f.copy(onlyUnreviewed = it)) }
                Dim("علامت")
                ChipRow { Mark.values().forEach { m -> LumoChip("${m.glyph} ${m.fa}", m in f.marks) { vm.setLibFilter(f.copy(marks = if (m in f.marks) f.marks - m else f.marks + m)) } } }
                if (state.project.categories.isNotEmpty()) {
                    Dim("دسته‌بندی")
                    ChipRow {
                        state.project.categories.forEach { cat ->
                            LumoChip(cat, f.category == cat) { vm.setLibFilter(f.copy(category = if (f.category == cat) null else cat)) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("اعمال") } },
        dismissButton = { TextButton(onClick = { vm.setLibFilter(FilterSpec()) }) { Text("پاک کردن همه") } },
    )
}

@Composable
private fun CategoryDialog(state: UiState, vm: StudioViewModel, sel: Set<String>, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("دسته‌بندی سفارشی") },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                Dim("دسته‌ها مستقل از گروه‌بندی اصلی‌اند.")
                state.project.categories.forEach { cat ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(cat, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.setCategory(sel, cat, true) }) { Text("افزودن") }
                        TextButton(onClick = { vm.setCategory(sel, cat, false) }) { Text("حذف") }
                        TextButton(onClick = { vm.deleteCategory(cat) }) { Text("🗑") }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextField1("دسته‌ی جدید", name, Modifier.weight(1f)) { name = it }
                    TextButton(onClick = { vm.addCategory(name); vm.setCategory(sel, name.trim(), true); name = "" }) { Text("ساخت") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("تمام") } },
    )
}

@Composable
private fun PhotoDetailDialog(state: UiState, vm: StudioViewModel, p: Photo, onDismiss: () -> Unit) {
    val live = state.project.photos.firstOrNull { it.id == p.id } ?: p
    val role = state.project.roleOf(p.id)
    val gid = state.project.assignments[p.id]?.groupId
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(live.name, maxLines = 1) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PhotoThumb(live.uri, 768, Modifier.fillMaxWidth().height(220.dp), ContentScale.Fit)
                InfoLine("حجم", Stats.fmtBytes(live.size))
                InfoLine("ابعاد", if (live.width > 0) "${live.width}×${live.height}" else "—")
                InfoLine("نقش", when (role) { Role.ORIGINAL -> "عکس اصلی"; Role.VERSION -> "نسخه"; Role.UNSORTED -> "بدون گروه" })
                InfoLine("پوشه", state.project.folders.firstOrNull { it.id == live.folderId }?.label ?: "وارد‌شده‌ی تکی")
                Dim("علامت‌ها")
                ChipRow { Mark.values().forEach { m -> LumoChip("${m.glyph} ${m.fa}", m in live.marks) { vm.setMark(setOf(live.id), m, m !in live.marks) } } }
                if (state.project.categories.isNotEmpty()) {
                    Dim("دسته‌ها")
                    ChipRow { state.project.categories.forEach { cat -> LumoChip(cat, cat in live.categories) { vm.setCategory(setOf(live.id), cat, cat !in live.categories) } } }
                }
            }
        },
        confirmButton = {
            Row {
                if (gid != null) TextButton(onClick = { onDismiss(); vm.openCompare(gid) }) { Text("مقایسه‌ی گروه") }
                TextButton(onClick = { vm.toggleSelect(live.id); onDismiss() }) { Text("انتخاب") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("بستن") } },
    )
}

// ====================== تغییر نام گروهی (۳) ======================

@Composable
fun RenameScreen(state: UiState, vm: StudioViewModel) {
    var rules by remember { mutableStateOf(RenameRules()) }
    LaunchedEffect(rules, state.selection) {
        kotlinx.coroutines.delay(300)
        vm.previewRename(rules)
    }
    val preview = state.renamePreview
    Page("تغییر نام گروهی", { vm.back() }) {
        Dim("${state.selection.size} فایل انتخاب شده است. فقط فایل‌های پوشه‌های معرفی‌شده قابل تغییر نام‌اند.")
        SectionCard("قانون‌ها") {
            TextField1("حذف این بخش از نام", rules.removeText, Modifier.fillMaxWidth()) { rules = rules.copy(removeText = it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField1("جایگزینی: این را", rules.find, Modifier.weight(1f)) { rules = rules.copy(find = it) }
                TextField1("با این", rules.replace, Modifier.weight(1f)) { rules = rules.copy(replace = it) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField1("متن ابتدای نام", rules.prefix, Modifier.weight(1f)) { rules = rules.copy(prefix = it) }
                TextField1("متن انتهای نام", rules.suffix, Modifier.weight(1f)) { rules = rules.copy(suffix = it) }
            }
            Dim("شماره‌گذاری خودکار")
            ChipRow { NumberMode.values().forEach { m -> LumoChip(m.fa, rules.numberMode == m) { rules = rules.copy(numberMode = m) } } }
            if (rules.numberMode != NumberMode.NONE) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField("شروع از", rules.start.toString(), Modifier.weight(1f)) { rules = rules.copy(start = it.toDoubleOrNull()?.toInt() ?: 1) }
                    NumField("تعداد ارقام (001 = 3)", rules.digits.toString(), Modifier.weight(1f)) { rules = rules.copy(digits = it.toDoubleOrNull()?.toInt() ?: 3) }
                }
                TextField1("متن قبل از شماره (مثلاً «عکس شماره »)", rules.numberPrefix, Modifier.fillMaxWidth()) { rules = rules.copy(numberPrefix = it) }
            }
        }
        SectionCard("پیش‌نمایش (${preview.count { it.status == RenameStatus.OK }} تغییر)") {
            if (preview.isEmpty()) Dim("ابتدا در کتابخانه فایل انتخاب کن.")
            preview.take(40).forEach { it ->
                val col = when (it.status) {
                    RenameStatus.OK -> LocalLumo.current.fg
                    RenameStatus.UNCHANGED -> LocalLumo.current.fg3
                    else -> LocalLumo.current.bad
                }
                Text("${it.oldName}  ←  ${it.newName}" + when (it.status) {
                    RenameStatus.CONFLICT -> "  (تداخل)"
                    RenameStatus.NO_WRITE -> "  (بدون دسترسی نوشتن)"
                    else -> ""
                }, color = col, fontSize = 12.sp)
            }
            if (preview.size > 40) Dim("… و ${preview.size - 40} مورد دیگر")
        }
        Button(onClick = { vm.runRename(rules) }, enabled = preview.any { it.status == RenameStatus.OK } && state.job?.finished != false, modifier = Modifier.fillMaxWidth()) {
            Text("اجرای تغییر نام")
        }
    }
}

// ====================== انتقال گروهی هوشمند (۴) ======================

@Composable
fun MoveScreen(state: UiState, vm: StudioViewModel) {
    var rule by remember { mutableStateOf(MoveRule()) }
    var dest by remember { mutableStateOf<String?>(null) }
    var pick by remember { mutableStateOf(false) }
    LaunchedEffect(rule, state.selection, state.project.photos) { vm.previewMove(rule) }
    val preview = state.movePreview
    Page("انتقال گروهی هوشمند", { vm.back() }) {
        Dim("${state.selection.size} فایل انتخاب شده است. فایل‌ها واقعاً جابه‌جا می‌شوند، بدون بازنویسی؛ و از «تاریخچه» قابل بازگشت‌اند.")
        SectionCard("قانون انتقال") {
            ChipRow { MoveMode.values().forEach { m -> LumoChip(m.fa, rule.mode == m) { rule = rule.copy(mode = m) } } }
            when (rule.mode) {
                MoveMode.MARK -> ChipRow { Mark.values().forEach { m -> LumoChip("${m.glyph} ${m.fa}", rule.mark == m) { rule = rule.copy(mark = m) } } }
                MoveMode.CATEGORY -> ChipRow { state.project.categories.forEach { cat -> LumoChip(cat, rule.category == cat) { rule = rule.copy(category = cat) } } }
                MoveMode.SINGLE -> TextField1("نام پوشه", rule.fixedName, Modifier.fillMaxWidth()) { rule = rule.copy(fixedName = it) }
                else -> {}
            }
        }
        SectionCard("مقصد") {
            Text(dest?.let { labelFor(state, it) } ?: "هنوز انتخاب نشده", color = LocalLumo.current.fg)
            OutlinedButton(onClick = { pick = true }) { Text("انتخاب پوشه‌ی مقصد") }
        }
        SectionCard("پیش‌نمایش (${preview.count { it.status == MoveStatus.OK }} فایل)") {
            preview.take(40).forEach {
                val ok = it.status == MoveStatus.OK
                Text(
                    if (ok) "${it.name}  →  ${it.folder}/" else "${it.name}  — " + if (it.status == MoveStatus.NO_WRITE) "بدون دسترسی نوشتن" else "با این قانون جابه‌جا نمی‌شود",
                    color = if (ok) LocalLumo.current.fg else LocalLumo.current.fg3, fontSize = 12.sp,
                )
            }
            if (preview.size > 40) Dim("… و ${preview.size - 40} مورد دیگر")
        }
        Button(onClick = { dest?.let { vm.runMove(it) } }, enabled = dest != null && preview.any { it.status == MoveStatus.OK } && state.job?.finished != false, modifier = Modifier.fillMaxWidth()) {
            Text("اجرای انتقال")
        }
    }
    if (pick) DestinationDialog(state, vm, "مقصد انتقال", { dest = it; pick = false }) { pick = false }
}

// ====================== کپی هوشمند (۵) ======================

@Composable
fun CopyScreen(state: UiState, vm: StudioViewModel) {
    var dest by remember { mutableStateOf<String?>(null) }
    var pick by remember { mutableStateOf(false) }
    var policy by remember { mutableStateOf(CopyPolicy.SKIP) }
    val pv = state.copyPreview
    LaunchedEffect(dest, policy, state.selection) { dest?.let { vm.previewCopy(it, policy) } }
    Page("کپی هوشمند", { vm.back() }) {
        Dim("${state.selection.size} فایل انتخاب شده است. فقط فایل‌هایی کپی می‌شوند که قبلاً به این مقصد نرفته‌اند.")
        SectionCard("مقصد") {
            Text(dest?.let { labelFor(state, it) } ?: "هنوز انتخاب نشده", color = LocalLumo.current.fg)
            OutlinedButton(onClick = { pick = true }) { Text("انتخاب پوشه‌ی مقصد") }
        }
        SectionCard("اگر فایل هم‌نام با محتوای متفاوت بود") {
            ChipRow { CopyPolicy.values().forEach { p -> LumoChip(p.fa, policy == p) { policy = p } } }
            Dim("هیچ فایلی بدون اجازه‌ی تو بازنویسی نمی‌شود.")
        }
        if (pv != null) SectionCard("پیش‌نمایش") {
            InfoLine("جمع انتخاب", pv.total.toString())
            InfoLine("قبلاً کپی شده", pv.alreadyCopied.toString())
            InfoLine("هم‌نام و هم‌اندازه (از قبل در مقصد)", pv.sameInDest.toString())
            InfoLine("هم‌نام با محتوای متفاوت", pv.conflicts.toString())
            InfoLine("فایل جدید برای کپی", pv.fresh.toString())
        }
        Button(onClick = { dest?.let { vm.runCopy(it, policy) } }, enabled = dest != null && state.job?.finished != false, modifier = Modifier.fillMaxWidth()) {
            Text("شروع کپی")
        }
    }
    if (pick) DestinationDialog(state, vm, "مقصد کپی", { dest = it; pick = false }) { pick = false }
}

// ====================== تبدیل فرمت و فشرده‌سازی (۱۱، ۱۳) ======================

@Composable
fun ProcessScreen(state: UiState, vm: StudioViewModel) {
    val ps = state.project.settings
    var mode by remember { mutableStateOf(ProcessMode.CONVERT) }
    var format by remember { mutableStateOf(ps.convertFormat) }
    var quality by remember { mutableFloatStateOf(ps.imageQuality.toFloat()) }
    var dest by remember { mutableStateOf<String?>(null) }
    var pick by remember { mutableStateOf(false) }
    val pv = state.compressPreview
    Page("تبدیل فرمت و فشرده‌سازی", { vm.back() }) {
        Dim("${state.selection.size} عکس انتخاب شده است. خروجی کپی جدید در پوشه‌ی مقصد است و فایل اصلی تغییر نمی‌کند.")
        ChipRow {
            LumoChip("تبدیل فرمت", mode == ProcessMode.CONVERT) { mode = ProcessMode.CONVERT }
            LumoChip("فشرده‌سازی", mode == ProcessMode.COMPRESS) { mode = ProcessMode.COMPRESS }
        }
        SectionCard("تنظیمات") {
            Dim("فرمت مقصد")
            ChipRow {
                val formats = if (mode == ProcessMode.COMPRESS) listOf("JPG", "WEBP") else ImageProcessor.FORMATS
                formats.forEach { f -> LumoChip(f, format == f) { format = f } }
            }
            if (format != "PNG") {
                Text("کیفیت: ${quality.toInt()}٪", color = LocalLumo.current.fg)
                Slider(value = quality, onValueChange = { quality = it }, valueRange = 30f..100f)
            } else Dim("PNG بدون افت کیفیت ذخیره می‌شود.")
        }
        if (mode == ProcessMode.COMPRESS) {
            OutlinedButton(onClick = { vm.previewCompress(format, quality.toInt()) }, modifier = Modifier.fillMaxWidth()) {
                Text("مقایسه‌ی قبل و بعد روی اولین عکس")
            }
            if (pv != null) SectionCard("نتیجه روی «${pv.photoName}»") {
                InfoLine("قبل", Stats.fmtBytes(pv.origBytes))
                InfoLine("بعد", Stats.fmtBytes(pv.newBytes))
                if (pv.origBytes > 0) {
                    val ratio = pv.newBytes.toDouble() / pv.origBytes
                    InfoLine("تخمین حجم کل انتخاب", "${Stats.fmtBytes(pv.totalOrigBytes)} ← ≈ ${Stats.fmtBytes((pv.totalOrigBytes * ratio).toLong())}")
                }
                Dim("نوار را بکش: سمت چپ نسخه‌ی فشرده، سمت راست اصلی.")
                CompareImages(pv.before, pv.after, Modifier.fillMaxWidth().height(260.dp))
            }
        }
        SectionCard("مقصد") {
            Text(dest?.let { labelFor(state, it) } ?: "هنوز انتخاب نشده", color = LocalLumo.current.fg)
            OutlinedButton(onClick = { pick = true }) { Text("انتخاب پوشه‌ی مقصد") }
        }
        Button(
            onClick = { dest?.let { vm.runProcess(mode, format, quality.toInt(), it) } },
            enabled = dest != null && state.selection.isNotEmpty() && state.job?.finished != false,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (mode == ProcessMode.CONVERT) "شروع تبدیل" else "شروع فشرده‌سازی") }
    }
    if (pick) DestinationDialog(state, vm, "مقصد خروجی", { dest = it; pick = false }) { pick = false }
}
