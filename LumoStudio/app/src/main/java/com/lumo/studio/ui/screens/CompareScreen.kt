package com.lumo.studio.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumo.studio.model.groups
import com.lumo.studio.ui.components.CompareSlider
import com.lumo.studio.ui.components.PhotoThumb
import com.lumo.studio.ui.theme.LocalLumo
import com.lumo.studio.viewmodel.Screen
import com.lumo.studio.viewmodel.StudioViewModel
import com.lumo.studio.viewmodel.UiState

@Composable
fun CompareScreen(state: UiState, vm: StudioViewModel) {
    val c = LocalLumo.current
    val groupId = state.compareGroup
    val group = remember(state.project, groupId) {
        state.project.groups().firstOrNull { it.id == groupId }
    }
    var selected by remember(groupId) { mutableIntStateOf(0) }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "مقایسه",
                color = c.fg,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { vm.navigate(Screen.STUDIO) }) {
                Icon(Icons.Filled.Close, contentDescription = "بستن", tint = c.fg)
            }
        }

        val orig = group?.original
        if (group == null || orig == null) {
            Text("گروه پیدا نشد.", color = c.fg2)
        } else {
            val version = group.versions.getOrNull(selected)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (version != null) {
                    CompareSlider(orig.uri, version.uri, Modifier.fillMaxSize())
                } else {
                    PhotoThumb(orig.uri, 1280, Modifier.fillMaxSize(), ContentScale.Fit)
                }
            }

            if (group.versions.isEmpty()) {
                Text("هنوز خروجی‌ای به این عکس وصل نشده است.", color = c.fg2)
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(group.versions, key = { _, v -> v.id }) { index, v ->
                        PhotoThumb(
                            v.uri,
                            256,
                            Modifier
                                .size(72.dp)
                                .clickable { selected = index }
                                .border(
                                    2.dp,
                                    if (index == selected) c.g2 else Color.Transparent,
                                    RoundedCornerShape(16.dp),
                                ),
                        )
                    }
                }
            }

            version?.let { v ->
                Text(v.name, color = c.fg2, fontSize = 13.sp, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    (1..5).forEach { n ->
                        IconButton(onClick = { vm.setRating(v.id, if (v.rating == n) 0 else n) }) {
                            Icon(
                                Icons.Filled.Star,
                                contentDescription = "امتیاز $n",
                                tint = if (n <= v.rating) c.gold else c.fg3,
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.toggleBest(v.id) }, modifier = Modifier.weight(1f)) {
                        Text(if (v.best) "بهترین ✓" else "انتخاب بهترین")
                    }
                    OutlinedButton(onClick = { vm.makeOriginal(v.id) }, modifier = Modifier.weight(1f)) {
                        Text("تبدیل به اصلی")
                    }
                    OutlinedButton(onClick = { vm.unsort(v.id) }, modifier = Modifier.weight(1f)) {
                        Text("جدا کردن")
                    }
                }
            }
        }
    }
}
