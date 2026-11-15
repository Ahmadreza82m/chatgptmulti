package com.lumo.studio.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.Slider as Slider2
import com.lumo.studio.ui.components.NumField
import com.lumo.studio.ui.components.SwitchRow
import com.lumo.studio.ui.components.Dim
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumo.studio.model.Accent
import com.lumo.studio.model.ThemeMode
import com.lumo.studio.ui.components.LumoChip
import com.lumo.studio.ui.components.SectionCard
import com.lumo.studio.ui.theme.LocalLumo
import com.lumo.studio.viewmodel.Screen
import com.lumo.studio.viewmodel.StudioViewModel
import com.lumo.studio.viewmodel.UiState
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(state: UiState, vm: StudioViewModel) {
    val c = LocalLumo.current
    val settings = state.settings
    val sens = state.project.sensitivity

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "تنظیمات",
                color = c.fg,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { vm.navigate(Screen.STUDIO) }) {
                Icon(Icons.Filled.Close, contentDescription = "بستن", tint = c.fg)
            }
        }

        SectionCard("ظاهر") {
            Text("حالت نمایش", color = c.fg2, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LumoChip("سیستم", settings.themeMode == ThemeMode.SYSTEM) { vm.setTheme(ThemeMode.SYSTEM) }
                LumoChip("تاریک", settings.themeMode == ThemeMode.DARK) { vm.setTheme(ThemeMode.DARK) }
                LumoChip("روشن", settings.themeMode == ThemeMode.LIGHT) { vm.setTheme(ThemeMode.LIGHT) }
            }
            Spacer(Modifier.height(10.dp))
            Text("رنگ نور", color = c.fg2, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LumoChip("شفق", settings.accent == Accent.AURORA) { vm.setAccent(Accent.AURORA) }
                LumoChip("شامپاین", settings.accent == Accent.CHAMPAGNE) { vm.setAccent(Accent.CHAMPAGNE) }
                LumoChip("گل‌سرخ", settings.accent == Accent.ROSE) { vm.setAccent(Accent.ROSE) }
            }
        }

        SectionCard("موتور تشخیص") {
            Text("حساسیت تطبیق: ${(sens * 100).roundToInt()}%", color = c.fg)
            Slider(
                value = sens,
                onValueChange = { vm.setSensitivity(it) },
                onValueChangeFinished = { vm.commitSensitivity() },
            )
            Row(Modifier.fillMaxWidth()) {
                Text("خطای کمتر", color = c.fg3, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Text("تطبیق بیشتر", color = c.fg3, fontSize = 12.sp)
            }
            Text(
                "نتیجه هم‌زمان با کشیدن نوار تغییر می‌کند؛ تصمیم‌های دستی تو حفظ می‌شود.",
                color = c.fg2,
                fontSize = 13.sp,
            )
        }

        SectionCard("قفل عملیات حساس") {
            SwitchRow("نمایش صفحه‌ی تأیید برای عملیات بزرگ", settings.lockEnabled) { v -> vm.setSettings { it.copy(lockEnabled = v) } }
            NumField("از چند فایل به بالا تأیید بخواهد", settings.lockThreshold.toString(), Modifier.fillMaxWidth()) { v ->
                vm.setSettings { it.copy(lockThreshold = v.toDoubleOrNull()?.toInt()?.coerceAtLeast(1) ?: 50) }
            }
        }

        SectionCard("اعلان‌ها") {
            SwitchRow("اعلان پایان عملیات‌های طولانی", settings.notifications) { v -> vm.setSettings { it.copy(notifications = v) } }
        }

        SectionCard("تنظیمات اختصاصی «${state.project.name}»") {
            val ps = state.project.settings
            Dim("این گزینه‌ها فقط برای همین پروژه ذخیره می‌شوند و پیش‌فرض خروجی‌اند. قالب و ZIP را در صفحه‌ی خروجی تنظیم کن.")
            SwitchRow("خروجی در پوشه‌ی جداگانه‌ی هر گروه", ps.byGroup) { v -> vm.updateProjectSettings { it.copy(byGroup = v) } }
            SwitchRow("نقش‌ها در نام فایل فارسی باشند", ps.persianRoles) { v -> vm.updateProjectSettings { it.copy(persianRoles = v) } }
            SwitchRow("شامل «بدون گروه» در خروجی", ps.includeUnsorted) { v -> vm.updateProjectSettings { it.copy(includeUnsorted = v) } }
            Text("کیفیت پیش‌فرض تبدیل/فشرده‌سازی: ${ps.imageQuality}٪", color = c.fg)
            Slider2(value = ps.imageQuality.toFloat(), onValueChange = { q -> vm.updateProjectSettings { it.copy(imageQuality = q.toInt()) } }, valueRange = 30f..100f)
        }

        SectionCard("حالت آفلاین") {
            Dim("این برنامه مجوز اینترنت ندارد؛ گروه‌بندی، جست‌وجوی تصویری، تغییر نام، کپی و انتقال، مقایسه، ZIP و مدیریت پروژه‌ها همیشه بدون اینترنت و بدون سرور کار می‌کنند.")
        }

        SectionCard("حریم خصوصی") {
            Text(
                "همه‌چیز روی دستگاه می‌ماند. بدون API Key، بدون حساب و بدون ارسال عکس به سرور. " +
                    "تحلیل تصاویر کاملاً محلی است.",
                color = c.fg2,
                fontSize = 13.sp,
            )
        }

        SectionCard("پروژه") {
            OutlinedButton(onClick = { vm.resetProject() }, modifier = Modifier.fillMaxWidth()) {
                Text("پاک کردن کل پروژه (فایل‌های اصلی حذف نمی‌شوند)")
            }
        }
    }
}
