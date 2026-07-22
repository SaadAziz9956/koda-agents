package dev.koda.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.koda.desktop.AppModel
import dev.koda.desktop.ConnStatus
import dev.koda.desktop.theme.Ember

@Composable
fun ConnectScreen(model: AppModel) {
    var url by remember { mutableStateOf(model.daemonUrl) }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Column(Modifier.width(420.dp).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(15.dp)).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                Text("🧭", fontSize = 26.sp)
            }
            Spacer(Modifier.size(18.dp))
            Text("Connect to your Koda", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.size(6.dp))
            Text("Point the app at a running daemon — on this machine or your server.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(20.dp))
            EmberField(
                value = url, onValueChange = { url = it },
                placeholder = "ws://127.0.0.1:4477",
                modifier = Modifier.fillMaxWidth(), mono = true, onSubmit = { model.connect(url) },
            )
            Spacer(Modifier.size(8.dp))
            val status = when (model.status) {
                ConnStatus.Connecting -> "connecting…" to MaterialTheme.colorScheme.primary
                ConnStatus.Disconnected -> "offline — check the address and daemon" to MaterialTheme.colorScheme.onSurfaceVariant
                ConnStatus.Connected -> "connected" to Ember.ok
            }
            Text(status.first, fontSize = 12.sp, fontFamily = Ember.mono, color = status.second)
            Spacer(Modifier.size(14.dp))
            EmberButton("Connect", onClick = { model.connect(url) }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.size(18.dp))
            Text("No daemon yet? Run  ./gradlew :daemon:run  on the target machine.",
                fontSize = 11.5f.sp, fontFamily = Ember.mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
