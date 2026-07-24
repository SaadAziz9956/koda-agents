package dev.koda.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.koda.desktop.AppModel
import dev.koda.desktop.ConnStatus
import dev.koda.desktop.theme.Ember

@Composable
fun Rail(model: AppModel, view: AppView, onSelectView: (AppView) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        ViewToggle(view, onSelectView)
        HDivider()
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 14.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Eyebrow("Sessions")
            Spacer(Modifier.weight(1f))
            Text("+", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { model.newSession() }.padding(horizontal = 4.dp))
        }
        LazyColumn(
            Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(model.sessions, key = { it.id }) { s ->
                SessionRow(s.id, s.title ?: "${s.messageCount} messages", s.messageCount, active = s.id == model.sessionId) { model.resume(s.id) }
            }
        }
        HDivider()
        Column(Modifier.padding(12.dp)) {
            Eyebrow("Surfaces", Modifier.padding(start = 8.dp, bottom = 6.dp))
            SurfaceRow("CLI", "active", Ember.ok)
            SurfaceRow("WhatsApp", "idle", Ember.info)
            SurfaceRow(
                "Daemon", model.status.name.lowercase(),
                if (model.status == ConnStatus.Connected) Ember.ok else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ViewToggle(view: AppView, onSelect: (AppView) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(10.dp).clip(RoundedCornerShape(9.dp))
            .background(MaterialTheme.colorScheme.background).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        ViewTab("Home", view == AppView.Home, Modifier.weight(1f)) { onSelect(AppView.Home) }
        ViewTab("Code", view == AppView.Code, Modifier.weight(1f)) { onSelect(AppView.Code) }
    }
}

@Composable
private fun ViewTab(label: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(7.dp))
            .background(if (active) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick).padding(vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 12.5f.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier = modifier, fontSize = 10.sp, letterSpacing = 1.4.sp,
        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SessionRow(name: String, subtitle: String, count: Int, active: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(9.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (active) MaterialTheme.colorScheme.background else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 9.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(if (active) MaterialTheme.colorScheme.primary else Ember.ok, 6.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 13.sp, fontFamily = Ember.mono, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, fontSize = 11.5f.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 1.dp))
        }
        Spacer(Modifier.width(6.dp))
        Text("$count", fontSize = 11.sp, fontFamily = Ember.mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SurfaceRow(name: String, meta: String, dot: androidx.compose.ui.graphics.Color) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Dot(dot, 7.dp)
        Spacer(Modifier.width(9.dp))
        Text(name, fontSize = 12.5f.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Text(meta, fontSize = 11.sp, fontFamily = Ember.mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
