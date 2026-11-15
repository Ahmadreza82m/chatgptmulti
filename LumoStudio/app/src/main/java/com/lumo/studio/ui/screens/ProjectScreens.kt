package com.lumo.studio.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lumo.studio.ui.components.*
import com.lumo.studio.ui.theme.LocalLumo
import com.lumo.studio.viewmodel.*

// ====================== چندپروژه‌ای (۳۲) ======================

@Composable
fun ProjectsScreen(state: UiState, vm: StudioViewModel) {
    val c = LocalLumo.current
    var name by remember { mutableStateOf("") }
    var rename by remember { mutableStateOf<String?>(null) }
    var rn by remember { mutableStateOf("") }
    Page("پروژه‌ها", { vm.back() }) {
        SectionCard("پروژه‌ی جدید") {
            TextField1("نام پروژه (مثلاً عکس‌های محصولات)", name, Modifier.fillMaxWidth()) { name = it }
            Button(onClick = { vm.createProject(name); name = "" }, modifier = Modifier.fillMaxWidth()) { Text("ساخت پروژه") }
            Dim("هر پروژه پوشه‌ها، گروه‌بندی‌ها، تنظیمات و خروجی‌های مخصوص خودش را دارد.")
        }
        state.projects.forEach { p ->
            val active = p.id == state.project.id
            GlassCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text((if (active) "● " else "") + (if (active) state.project.name else p.name), color = c.fg, style = MaterialTheme.typography.titleSmall)
                        Dim("${if (active) state.project.photos.count { !it.trashed } else p.count} عکس")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!active) Button(onClick = { vm.switchProject(p.id) }) { Text("باز کردن") }
                    OutlinedButton(onClick = { rename = p.id; rn = if (active) state.project.name else p.name }) { Text("تغییر نام") }
                    TextButton(onClick = { vm.deleteProject(p.id) }, enabled = state.projects.size > 1 || !active) { Text("حذف") }
                }
            }
        }
    }
    rename?.let { id ->
        AlertDialog(
            onDismissRequest = { rename = null },
            title = { Text("نام پروژه") },
            text = { TextField1("نام", rn, Modifier.fillMaxWidth()) { rn = it } },
            confirmButton = { TextButton(onClick = { vm.renameProject(id, rn); rename = null }) { Text("ذخیره") } },
            dismissButton = { TextButton(onClick = { rename = null }) { Text("انصراف") } },
        )
    }
}

// ====================== پشتیبان‌گیری و انتقال به گوشی دیگر (۳۳، ۳۴) ======================

@Composable
fun BackupScreen(state: UiState, vm: StudioViewModel) {
    var withPhotos by remember { mutableStateOf(false) }
    var pickBackup by remember { mutableStateOf(false) }
    var pendingRestore by remember { mutableStateOf<android.net.Uri?>(null) }
    var pickRestoreDest by remember { mutableStateOf(false) }
    var pendingExtract by remember { mutableStateOf(false) }
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { pendingRestore = uri; pickRestoreDest = true }
    }
    Page("پشتیبان‌گیری و انتقال", { vm.back() }) {
        SectionCard("پشتیبان‌گیری از «${state.project.name}»") {
            Dim("شامل گروه‌بندی‌ها، ارتباط اصلی و خروجی‌ها، تنظیمات، قوانین نام‌گذاری، تاریخچه و مسیرها.")
            SwitchRow("خود عکس‌ها هم داخل پشتیبان باشند", withPhotos) { withPhotos = it }
            Button(onClick = { pickBackup = true }, enabled = state.job?.finished != false, modifier = Modifier.fillMaxWidth()) { Text("ساخت فایل پشتیبان") }
        }
        SectionCard("بازیابی / انتقال به گوشی دیگر") {
            Dim("۱) در گوشی اول پشتیبان بگیر.  ۲) فایل ZIP را به گوشی دوم ببر.  ۳) اینجا «بازیابی» را بزن.  " +
                "۴) اگر عکس‌ها داخل پشتیبان نبودند، پوشه‌های عکس را در «پوشه‌ها» معرفی کن؛ فایل‌ها با نام و حجم خودکار به گروه‌ها وصل می‌شوند.")
            OutlinedButton(onClick = { restorePicker.launch(arrayOf("application/zip", "application/octet-stream")) }, modifier = Modifier.fillMaxWidth()) {
                Text("انتخاب فایل پشتیبان")
            }
        }
    }
    if (pickBackup) DestinationDialog(state, vm, "محل ذخیره‌ی پشتیبان", { pickBackup = false; vm.createBackup(it, withPhotos) }) { pickBackup = false }
    if (pickRestoreDest) {
        AlertDialog(
            onDismissRequest = { pickRestoreDest = false },
            title = { Text("بازیابی") },
            text = { Text("اگر پشتیبان شامل عکس باشد، به پوشه‌ای برای استخراج نیاز است. اگر عکس ندارد، «بدون استخراج» را بزن.") },
            confirmButton = { TextButton(onClick = { pickRestoreDest = false; pendingExtract = true }) { Text("انتخاب پوشه‌ی استخراج") } },
            dismissButton = { TextButton(onClick = { pickRestoreDest = false; pendingRestore?.let { vm.restoreBackup(it, null) } }) { Text("بدون استخراج") } },
        )
    }
    if (pendingExtract) {
        DestinationDialog(state, vm, "پوشه‌ی استخراج عکس‌ها", { pendingExtract = false; pendingRestore?.let { u -> vm.restoreBackup(u, it) } }) { pendingExtract = false }
    }
}
