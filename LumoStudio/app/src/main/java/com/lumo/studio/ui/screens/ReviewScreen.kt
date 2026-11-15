package com.lumo.studio.ui.screens

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.offset
import com.lumo.studio.model.Assignment
import com.lumo.studio.model.Photo
import com.lumo.studio.model.reviewQueue
import com.lumo.studio.ui.components.GlassCard
import com.lumo.studio.ui.components.PhotoThumb
import com.lumo.studio.ui.theme.LocalLumo
import com.lumo.studio.viewmodel.Screen
import com.lumo.studio.viewmodel.StudioViewModel
import com.lumo.studio.viewmodel.UiState
import kotlin.math.roundToInt

@Composable
fun ReviewScreen(state: UiState, vm: StudioViewModel) {
    val c = LocalLumo.current
    val queue = remember(state.project) { state.project.reviewQueue() }
    val byId = remember(state.project) { state.project.photos.associateBy { it.id } }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("بازبینی تطبیق", color = c.fg, style = MaterialTheme.typography.headlineSmall)
                Text("${queue.size} مورد نیازمند بررسی", color = c.fg2)
            }
            IconButton(onClick = { vm.navigate(Screen.STUDIO) }) {
                Icon(Icons.Filled.Close, contentDescription = "بستن", tint = c.fg)
            }
        }

        if (queue.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                GlassCard {
                    Text("همه‌چیز مرتب شد", color = c.fg, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { vm.navigate(Screen.EXPORT) }, modifier = Modifier.fillMaxWidth()) {
                        Text("ادامه به خروجی")
                    }
                }
            }
        } else {
            val item = queue[state.reviewIndex % queue.size]
            val cand = byId[item.photoId]
            val orig = item.groupId?.let { byId[it] }
            if (cand != null) {
                ReviewCard(
                    cand = cand,
                    orig = orig,
                    item = item,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    onConfirm = { item.groupId?.let { vm.confirmVersion(cand.id, it) } },
                    onReject = { vm.unsort(cand.id) },
                )
            }
            Text("کشیدن به راست = تأیید · کشیدن به چپ = رد", color = c.fg3, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { vm.unsort(item.photoId) },
                    modifier = Modifier.weight(1f),
                ) { Text("رد") }
                TextButton(onClick = { vm.skipReview() }, modifier = Modifier.weight(1f)) { Text("بعدی") }
                Button(
                    onClick = { item.groupId?.let { vm.confirmVersion(item.photoId, it) } },
                    modifier = Modifier.weight(1f),
                ) { Text("تأیید") }
            }
        }
    }
}

@Composable
private fun ReviewCard(
    cand: Photo,
    orig: Photo?,
    item: Assignment,
    modifier: Modifier,
    onConfirm: () -> Unit,
    onReject: () -> Unit,
) {
    val c = LocalLumo.current
    var dragX by remember(cand.id) { mutableFloatStateOf(0f) }
    val pct = (item.score * 100).roundToInt()
    val gapPct = (item.gap * 100).roundToInt()

    GlassCard(
        modifier
            .offset { IntOffset(dragX.roundToInt(), 0) }
            .pointerInput(cand.id) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (dragX > 160f) {
                            onConfirm()
                        } else if (dragX < -160f) {
                            onReject()
                        } else {
                            dragX = 0f
                        }
                    },
                    onDragCancel = { dragX = 0f },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        dragX += amount
                    },
                )
            },
    ) {
        Text("شباهت $pct% · فاصله‌ی اطمینان $gapPct%", color = c.fg2, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        PhotoThumb(cand.uri, 1280, Modifier.fillMaxWidth().weight(1f))
        Spacer(Modifier.height(8.dp))
        Text(cand.name, color = c.fg, maxLines = 1)
        if (orig != null) {
            Text("عکس اصلی گروه", color = c.fg2, fontSize = 13.sp)
            PhotoThumb(orig.uri, 512, Modifier.fillMaxWidth().height(140.dp))
        }
    }
}
