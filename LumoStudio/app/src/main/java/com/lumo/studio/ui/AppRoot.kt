package com.lumo.studio.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumo.studio.ui.components.AuroraBackground
import com.lumo.studio.ui.components.ConfirmDialog
import com.lumo.studio.ui.components.GlassCard
import com.lumo.studio.ui.components.JobBar
import com.lumo.studio.ui.screens.*
import com.lumo.studio.ui.theme.LocalLumo
import com.lumo.studio.ui.theme.LumoTheme
import com.lumo.studio.viewmodel.Screen
import com.lumo.studio.viewmodel.StudioViewModel
import com.lumo.studio.viewmodel.discardRecovery
import com.lumo.studio.viewmodel.resumeRecovery
import kotlinx.coroutines.delay

@Composable
fun AppRoot(vm: StudioViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val ctx = LocalContext.current
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> vm.importUris(uris) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    BackHandler(enabled = state.screen != Screen.STUDIO) { vm.back() }

    LumoTheme(state.settings.themeMode, state.settings.accent) {
        AuroraBackground {
            Box(Modifier.fillMaxSize().systemBarsPadding()) {
                when (state.screen) {
                    Screen.STUDIO -> StudioScreen(state, vm, onImport = { importLauncher.launch(arrayOf("image/*")) })
                    Screen.REVIEW -> ReviewScreen(state, vm)
                    Screen.COMPARE -> CompareScreen(state, vm)
                    Screen.EXPORT -> ExportScreen(state, vm)
                    Screen.SETTINGS -> SettingsScreen(state, vm)
                    Screen.HUB -> HubScreen(state, vm)
                    Screen.LIBRARY -> LibraryScreen(state, vm)
                    Screen.FOLDERS -> FoldersScreen(state, vm)
                    Screen.RENAME -> RenameScreen(state, vm)
                    Screen.MOVE -> MoveScreen(state, vm)
                    Screen.COPY -> CopyScreen(state, vm)
                    Screen.FOLDER_COMPARE -> FolderCompareScreen(state, vm)
                    Screen.DUPLICATES -> DuplicatesScreen(state, vm)
                    Screen.PROCESS -> ProcessScreen(state, vm)
                    Screen.DASHBOARD -> DashboardScreen(state, vm)
                    Screen.ERRORS -> ErrorsScreen(state, vm)
                    Screen.HEALTH -> HealthScreen(state, vm)
                    Screen.HISTORY -> HistoryScreen(state, vm)
                    Screen.EXPORTS -> ExportsScreen(state, vm)
                    Screen.PROJECTS -> ProjectsScreen(state, vm)
                    Screen.BACKUP -> BackupScreen(state, vm)
                    Screen.STORAGE -> StorageScreen(state, vm)
                }
                Column(Modifier.align(Alignment.BottomCenter).padding(12.dp)) {
                    state.message?.let { msg -> Toast(msg) { vm.clearMessage() } }
                    JobBar(state, vm)
                }
                ConfirmDialog(state, vm)
                state.recovery?.let { r ->
                    AlertDialog(
                        onDismissRequest = {},
                        title = { Text("عملیات ناتمام پیدا شد") },
                        text = {
                            Text("«${r.title}» وسط کار متوقف شده بود (${r.done} از ${r.total} انجام شده). " +
                                "اگر ادامه بدهی، کارهای انجام‌شده دوباره انجام نمی‌شوند.")
                        },
                        confirmButton = { TextButton(onClick = { vm.resumeRecovery() }) { Text("ادامه از نقطه‌ی امن") } },
                        dismissButton = { TextButton(onClick = { vm.discardRecovery() }) { Text("دور انداختن") } },
                    )
                }
            }
        }
    }
}

@Composable
private fun Toast(text: String, onDone: () -> Unit) {
    LaunchedEffect(text) {
        delay(3200)
        onDone()
    }
    val c = LocalLumo.current
    GlassCard(Modifier.padding(bottom = 8.dp)) {
        Text(text, color = c.fg)
    }
}
