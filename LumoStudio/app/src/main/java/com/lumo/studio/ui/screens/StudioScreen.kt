package com.lumo.studio.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumo.studio.model.GroupView
import com.lumo.studio.model.Photo
import com.lumo.studio.model.groups
import com.lumo.studio.model.reviewQueue
import com.lumo.studio.model.unsorted
import com.lumo.studio.ui.components.GlassCard
import com.lumo.studio.ui.components.LumoChip
import com.lumo.studio.ui.components.PhotoThumb
import com.lumo.studio.ui.theme.LocalLumo
import com.lumo.studio.viewmodel.Filter
import com.lumo.studio.viewmodel.Screen
import com.lumo.studio.viewmodel.StudioViewModel
import com.lumo.studio.viewmodel.UiState

@Composable
fun StudioScreen(state: UiState, vm: StudioViewModel, onImport: () -> Unit) {
    val c = LocalLumo.current
    val project = state.project
    val groups = remember(project) { project.groups() }
    val unsorted = remember(project) { project.unsorted() }
    val pending = remember(project) { project.reviewQueue().size }
    val shown = remember(groups, state.filter) {
        when (state.filter) {
            Filter.REVIEW -> groups.filter { it.pending > 0 }
            Filter.BEST -> groups.filter { it.bests > 0 }
            else -> groups
        }
    }
    var assignTo by remember { mutableStateOf<Photo?>(null) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Lumo",
                    style = TextStyle(
                        brush = Brush.linearGradient(listOf(c.g1, c.g2)),
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                Text("استودیو", color = c.fg2, fontSize = 13.sp)
            }
            TextButton(onClick = { vm.navigate(Screen.HUB) }) { Text("ابزارها", color = c.fg) }
            IconButton(onClick = onImport) {
                Icon(Icons.Filled.Add, contentDescription = "افزودن عکس", tint = c.fg)
            }
            IconButton(onClick = { vm.analyze() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "تحلیل دوباره", tint = c.fg)
            }
            IconButton(onClick = { vm.navigate(Screen.SETTINGS) }) {
                Icon(Icons.Filled.Settings, contentDescription = "تنظیمات", tint = c.fg)
            }
        }

        Text(
            "${project.photos.size} عکس · ${groups.size} گروه · ${unsorted.size} بدون گروه",
            color = c.fg2,
            fontSize = 13.sp,
        )

        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LumoChip("همه", state.filter == Filter.ALL) { vm.setFilter(Filter.ALL) }
            LumoChip("نیاز به بررسی ($pending)", state.filter == Filter.REVIEW) { vm.setFilter(Filter.REVIEW) }
            LumoChip("بدون گروه (${unsorted.size})", state.filter == Filter.UNSORTED) { vm.setFilter(Filter.UNSORTED) }
            LumoChip("بهترین‌ها", state.filter == Filter.BEST) { vm.setFilter(Filter.BEST) }
        }

        if (state.analyzing) {
            LinearProgressIndicator(progress = state.progress, modifier = Modifier.fillMaxWidth())
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                project.photos.isEmpty() -> EmptyState(onImport)
                state.filter == Filter.UNSORTED -> UnsortedGrid(unsorted) { assignTo = it }
                shown.isEmpty() -> Text(
                    "موردی در این دسته نیست.",
                    color = c.fg2,
                    modifier = Modifier.padding(top = 24.dp),
                )
                else -> LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(shown, key = { it.id }) { g ->
                        GroupRow(g) { vm.openCompare(g.id) }
                    }
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Button(
                onClick = { vm.navigate(Screen.REVIEW) },
                enabled = pending > 0,
                modifier = Modifier.weight(1f),
            ) { Text("بازبینی ($pending)") }
            OutlinedButton(
                onClick = { vm.navigate(Screen.EXPORT) },
                enabled = project.photos.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) { Text("خروجی") }
        }
    }

    assignTo?.let { photo ->
        AssignDialog(
            groups = groups,
            onPick = { gid ->
                vm.confirmVersion(photo.id, gid)
                assignTo = null
            },
            onOriginal = {
                vm.makeOriginal(photo.id)
                assignTo = null
            },
            onDismiss = { assignTo = null },
        )
    }
}

@Composable
private fun EmptyState(onImport: () -> Unit) {
    val c = LocalLumo.current
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GlassCard(Modifier.fillMaxWidth()) {
            Text("پروژه خالی است", color = c.fg, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "عکس‌های اصلی و نسخه‌های ساخته‌شده را اضافه کن تا Lumo آن‌ها را به هم وصل کند. " +
                    "همه‌ی تحلیل‌ها روی همین دستگاه انجام می‌شود.",
                color = c.fg2,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onImport, modifier = Modifier.fillMaxWidth()) { Text("افزودن عکس") }
        }
    }
}

@Composable
private fun GroupRow(g: GroupView, onOpen: () -> Unit) {
    val c = LocalLumo.current
    GlassCard(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            g.original?.let { PhotoThumb(it.uri, 256, Modifier.size(84.dp)) }
            Column(Modifier.weight(1f)) {
                Text(
                    g.original?.name ?: "گروه بی‌اصل",
                    color = c.fg,
                    maxLines = 1,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text("${g.versions.size} نسخه", color = c.fg2, fontSize = 13.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (g.pending > 0) {
                        Text("${g.pending} نیاز به بررسی", color = c.warn, fontSize = 12.sp)
                        Spacer(Modifier.size(10.dp))
                    }
                    if (g.bests > 0) {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = null,
                            tint = c.gold,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(" ${g.bests}", color = c.gold, fontSize = 12.sp)
                    }
                }
            }
        }
        if (g.versions.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(g.versions, key = { it.id }) { v ->
                    PhotoThumb(v.uri, 128, Modifier.size(40.dp))
                }
            }
        }
    }
}

@Composable
private fun UnsortedGrid(photos: List<Photo>, onPick: (Photo) -> Unit) {
    val c = LocalLumo.current
    if (photos.isEmpty()) {
        Text("همه‌ی عکس‌ها گروه‌بندی شده‌اند.", color = c.fg2)
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(photos, key = { it.id }) { p ->
            PhotoThumb(
                p.uri,
                256,
                Modifier.aspectRatio(1f).clickable { onPick(p) },
            )
        }
    }
}

@Composable
private fun AssignDialog(
    groups: List<GroupView>,
    onPick: (String) -> Unit,
    onOriginal: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تخصیص عکس") },
        text = {
            Column(
                Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("این عکس را به کدام گروه اضافه کنم؟")
                groups.forEach { g ->
                    TextButton(onClick = { onPick(g.id) }) {
                        Text(g.original?.name ?: "گروه")
                    }
                }
                TextButton(onClick = onOriginal) { Text("تبدیل به عکس اصلی گروه جدید") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("بستن") } },
    )
}
