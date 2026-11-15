package com.lumo.studio.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumo.studio.engine.Stats
import com.lumo.studio.model.*
import com.lumo.studio.ui.components.*
import com.lumo.studio.ui.theme.LocalLumo
import com.lumo.studio.viewmodel.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ====================== پوشه‌ها و علاقه‌مندی‌ها (۱، ۱۰، ۳۶) ======================

@Composable
fun FoldersScreen(state: UiState, vm: StudioViewModel) {
    val c = LocalLumo.current
    var kind by remember { mutableStateOf(FolderKind.ORIGINALS) }
    val addFolderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.addFolder(uri, kind)
    }
    val favLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.addFavoritePath(uri)
    }
    val p = state.project
    Page("پوشه‌ها", { vm.back() }) {
        SectionCard("افزودن پوشه به پروژه") {
            Dim("نوع پوشه")
            ChipRow { FolderKind.values().forEach { k -> LumoChip(k.fa, kind == k) { kind = k } } }
            Button(onClick = { addFolderLauncher.launch(null) }, modifier = Modifier.fillMaxWidth()) { Text("انتخاب و اسکن پوشه") }
            Dim("همه‌ی پوشه‌ها در یک محیط مدیریت می‌شوند و نیازی به انتقال عکس‌ها به یک پوشه نیست. زیرپوشه‌ها هم اسکن می‌شوند.")
        }
        if (p.folders.isNotEmpty()) {
            Button(onClick = { vm.rescanAll() }, modifier = Modifier.fillMaxWidth(), enabled = state.job?.finished != false) {
                Text("اسکن هوشمند همه‌ی پوشه‌ها (فقط تغییرات)")
            }
        }
        p.folders.forEach { f ->
            val count = p.photos.count { it.folderId == f.id && !it.trashed }
            val fav = state.settings.favorites.any { it.uri == f.uri }
            GlassCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f.label, color = c.fg, style = MaterialTheme.typography.titleSmall)
                        Dim("${f.kind.fa} · $count فایل")
                    }
                    TextButton(onClick = { vm.toggleFavorite(f.uri, f.label) }) { Text(if (fav) "★" else "☆", fontSize = 22.sp) }
                }
                if (f.lastScan > 0) Dim("آخرین اسکن ${SimpleDateFormat("MM/dd HH:mm", Locale.US).format(Date(f.lastScan))}: ${f.lastSummary}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.scanFolders(listOf(f.id)) }, enabled = state.job?.finished != false) { Text("اسکن") }
                    OutlinedButton(onClick = {
                        vm.setLibFilter(FilterSpec(folderId = f.id)); vm.navigate(Screen.LIBRARY)
                    }) { Text("مشاهده") }
                    TextButton(onClick = { vm.removeFolder(f.id) }) { Text("حذف از پروژه") }
                }
            }
        }
        SectionCard("علاقه‌مندی‌ها (بین پروژه‌ها مشترک)") {
            if (state.settings.favorites.isEmpty()) Dim("با ستاره‌ی هر پوشه یا دکمه‌ی زیر، مسیرهای پرکاربرد را ذخیره کن.")
            state.settings.favorites.forEach { fav ->
                val inProject = p.folders.firstOrNull { it.uri == fav.uri }
                Row(Modifier.fillMaxWidth().clickable {
                    if (inProject != null) { vm.setLibFilter(FilterSpec(folderId = inProject.id)); vm.navigate(Screen.LIBRARY) }
                    else vm.addFolder(android.net.Uri.parse(fav.uri), FolderKind.OTHER)
                }, verticalAlignment = Alignment.CenterVertically) {
                    Text("★ ${fav.label}", color = c.fg, modifier = Modifier.weight(1f))
                    Text(if (inProject != null) "ورود" else "افزودن به پروژه", color = c.g2, fontSize = 12.sp)
                    TextButton(onClick = { vm.toggleFavorite(fav.uri, fav.label) }) { Text("حذف") }
                }
            }
            OutlinedButton(onClick = { favLauncher.launch(null) }) { Text("افزودن مسیر علاقه‌مندی") }
        }
    }
}

// ====================== مقایسه‌ی دو پوشه (۲) ======================

@Composable
fun FolderCompareScreen(state: UiState, vm: StudioViewModel) {
    val folders = state.project.folders
    var a by remember { mutableStateOf<String?>(null) }
    var b by remember { mutableStateOf<String?>(null) }
    var showList by remember { mutableStateOf<String?>(null) }
    val r = state.folderCompare
    var pickCopy by remember { mutableStateOf(false) }
    Page("مقایسه‌ی دو پوشه", { vm.back() }) {
        if (folders.size < 2) { Dim("برای مقایسه حداقل دو پوشه به پروژه اضافه کن."); return@Page }
        SectionCard("پوشه‌ی اول") { ChipRow { folders.forEach { f -> LumoChip(f.label, a == f.id) { a = f.id } } } }
        SectionCard("پوشه‌ی دوم") { ChipRow { folders.forEach { f -> LumoChip(f.label, b == f.id) { b = f.id } } } }
        Button(
            onClick = { vm.compareFolders(a!!, b!!) },
            enabled = a != null && b != null && a != b && state.job?.finished != false,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("مقایسه (بر اساس نام و محتوای واقعی)") }
        if (r != null) {
            SectionCard("نتیجه: «${r.aLabel}» و «${r.bLabel}»") {
                ResultRow("در هر دو (هم‌نام و هم‌محتوا)", r.same.size) { showList = "same" }
                ResultRow("فقط در «${r.aLabel}»", r.onlyA.size) { showList = "a" }
                ResultRow("فقط در «${r.bLabel}»", r.onlyB.size) { showList = "b" }
                ResultRow("محتوای یکسان با نام متفاوت", r.renamed.size) { showList = "ren" }
                ResultRow("هم‌نام با محتوای متفاوت", r.sameNameDiff.size) { showList = "diff" }
            }
            if (r.onlyAIds.isNotEmpty()) {
                Button(onClick = { pickCopy = true }, modifier = Modifier.fillMaxWidth(), enabled = state.job?.finished != false) {
                    Text("کپی ${r.onlyAIds.size} فایلِ «فقط اول» به یک پوشه")
                }
            }
        }
    }
    if (pickCopy && r != null) {
        DestinationDialog(state, vm, "مقصد کپی", { dest -> pickCopy = false; vm.runCopy(dest, CopyPolicy.SKIP, r.onlyAIds.toSet()) }) { pickCopy = false }
    }
    if (showList != null && r != null) {
        val lines: List<String> = when (showList) {
            "same" -> r.same
            "a" -> r.onlyA
            "b" -> r.onlyB
            "ren" -> r.renamed.map { "${it.a}  ⇄  ${it.b}" }
            else -> r.sameNameDiff
        }
        AlertDialog(
            onDismissRequest = { showList = null },
            title = { Text("${lines.size} مورد") },
            text = {
                androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(lines.take(300).size) { i -> Text(lines[i], fontSize = 12.sp) }
                }
            },
            confirmButton = { TextButton(onClick = { showList = null }) { Text("بستن") } },
        )
    }
}

@Composable
private fun ResultRow(label: String, n: Int, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = LocalLumo.current.fg, modifier = Modifier.weight(1f))
        Text(n.toString(), color = LocalLumo.current.g2)
    }
}

// ====================== فایل‌های تکراری (۶) ======================

@Composable
fun DuplicatesScreen(state: UiState, vm: StudioViewModel) {
    val c = LocalLumo.current
    val byId = remember(state.project) { state.project.photos.associateBy { it.id } }
    var pick by remember { mutableStateOf(false) }
    Page("مدیریت فایل‌های تکراری", { vm.back() }) {
        Dim("فقط فایل‌هایی که محتوای آن‌ها بایت‌به‌بایت یکسان است تکراری حساب می‌شوند؛ عکس‌های مشابه ولی متفاوت دست نمی‌خورند.")
        Button(onClick = { vm.findDuplicates() }, enabled = state.job?.finished != false, modifier = Modifier.fillMaxWidth()) {
            Text("جست‌وجوی تکراری‌ها")
        }
        if (state.dupesScanned && state.dupes.isEmpty()) Dim("فایل تکراری پیدا نشد 🎉")
        state.dupes.forEach { cl ->
            GlassCard(Modifier.fillMaxWidth()) {
                Dim("${cl.ids.size} نسخه‌ی یکسان — یکی را برای نگه‌داشتن انتخاب کن")
                cl.ids.forEach { id ->
                    val ph = byId[id] ?: return@forEach
                    Row(Modifier.fillMaxWidth().clickable { vm.setKeep(cl.hash, id) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = cl.keep == id, onClick = { vm.setKeep(cl.hash, id) })
                        PhotoThumb(ph.uri, 128, Modifier.size(44.dp))
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(ph.name, color = c.fg, maxLines = 1, fontSize = 13.sp)
                            Dim(state.project.folders.firstOrNull { it.id == ph.folderId }?.label ?: "وارد‌شده‌ی تکی")
                        }
                    }
                }
            }
        }
        if (state.dupes.isNotEmpty()) {
            val extra = state.dupes.sumOf { it.ids.size - 1 }
            Button(onClick = { vm.removeDuplicates(null) }, enabled = state.job?.finished != false, modifier = Modifier.fillMaxWidth()) {
                Text("انتقال $extra نسخه‌ی اضافه به سطل Lumo")
            }
            OutlinedButton(onClick = { pick = true }, modifier = Modifier.fillMaxWidth()) { Text("انتقال نسخه‌های اضافه به پوشه‌ی دیگر…") }
            Dim("حذف قطعی انجام نمی‌شود؛ از «تاریخچه» می‌توانی برگردانی.")
        }
    }
    if (pick) DestinationDialog(state, vm, "مقصد نسخه‌های اضافه", { pick = false; vm.removeDuplicates(it) }) { pick = false }
}
