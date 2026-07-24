package dev.koda.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.koda.desktop.AppModel
import dev.koda.desktop.ConnStatus
import dev.koda.desktop.theme.JetBrainsMono
import dev.koda.desktop.theme.LocalKoda

@Composable
fun ConnectScreen(model: AppModel) {
    val k = LocalKoda.current
    var url by remember { mutableStateOf(model.daemonUrl) }
    // Radial ember glow anchored near the top, over the base background.
    val glow = Brush.radialGradient(
        colors = listOf(k.accent.copy(alpha = 0.06f), Color.Transparent),
        center = Offset(0.5f * 1600f, -160f), radius = 1400f,
    )
    Box(Modifier.fillMaxSize().background(k.bg).background(glow), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(400.dp).clip(RoundedCornerShape(16.dp)).background(k.surface)
                .border(1.dp, k.strong, RoundedCornerShape(16.dp)).padding(28.dp),
        ) {
            // Header.
            Column(Modifier.fillMaxWidth().padding(bottom = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(Brush.linearGradient(listOf(k.accentSoft, k.accent))))
                Spacer(Modifier.size(14.dp))
                Text("Connect to a daemon", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = k.text)
                Text("Koda runs on your machine. Point the app at your local agent.", fontSize = 12.5f.sp, color = k.dim, modifier = Modifier.padding(top = 4.dp))
            }
            Text("Daemon address", fontSize = 11.sp, color = k.dim, fontWeight = FontWeight.Medium)
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(10.dp)).background(k.bg)
                    .border(1.dp, k.strong, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val alpha = blinkAlpha()
                Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(k.accent.copy(alpha = alpha)))
                EmberField(url, { url = it }, "ws://127.0.0.1:4477", Modifier.weight(1f).padding(vertical = 4.dp), mono = true, bordered = false, onSubmit = { model.connect(url) })
            }
            // Status row.
            Spacer(Modifier.size(12.dp))
            when (model.status) {
                ConnStatus.Connecting -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Spinner(14.dp, k.accent)
                    Text("Connecting — handshaking with daemon…", fontSize = 12.sp, color = k.accentSoft)
                }
                ConnStatus.Connected -> Text("Connected", fontSize = 12.sp, color = k.ok, fontFamily = JetBrainsMono)
                ConnStatus.Disconnected -> Text("Offline — check the address and that the daemon is running.", fontSize = 12.sp, color = k.dim)
            }
            Spacer(Modifier.size(6.dp))
            EmberButton("Connect", onClick = { model.connect(url) }, modifier = Modifier.fillMaxWidth())
            // Recent.
            Text("RECENT DAEMONS", fontSize = 10.5f.sp, letterSpacing = 0.9.sp, fontWeight = FontWeight.SemiBold, color = k.faint, modifier = Modifier.padding(top = 22.dp, bottom = 8.dp))
            RecentRow(k.ok, model.daemonUrl.ifBlank { "ws://127.0.0.1:4477" }, "current") { url = model.daemonUrl }
        }
    }
}

@Composable
private fun RecentRow(dot: Color, addr: String, meta: String, onClick: () -> Unit) {
    val k = LocalKoda.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(dot))
        Text(addr, fontFamily = JetBrainsMono, fontSize = 12.5f.sp, color = k.text, modifier = Modifier.weight(1f))
        Text(meta, fontSize = 11.sp, color = k.faint)
    }
}
