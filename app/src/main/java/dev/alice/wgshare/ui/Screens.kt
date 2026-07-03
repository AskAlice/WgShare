package dev.alice.wgshare.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.alice.wgshare.model.MessageKind
import dev.alice.wgshare.pairing.decodePairing
import dev.alice.wgshare.pairing.encode
import dev.alice.wgshare.pairing.qrBitmap

@Composable
fun AppNav(vm: AppViewModel) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "history") {
        composable("history") { HistoryScreen(vm, onDevices = { nav.navigate("home") }) }
        composable("home") { HomeScreen(vm, onPair = { nav.navigate("pair") }, onMessages = { nav.navigate("messages") }) }
        composable("pair") { PairScreen(vm, onDone = { nav.popBackStack() }) }
        composable("messages") { MessagesScreen(vm, onBack = { nav.popBackStack() }) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryScreen(vm: AppViewModel, onDevices: () -> Unit) {
    val clips by vm.clips.collectAsState()
    val context = LocalContext.current
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Clipboard history") },
            actions = {
                IconButton(onClick = { vm.clearHistory() }) { Icon(Icons.Default.Delete, "clear all") }
                IconButton(onClick = onDevices) { Icon(Icons.Default.Devices, "devices") }
            },
        )
    }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp)) {
            if (clips.isEmpty()) Text(
                "No clips yet. Copy text anywhere (with the SwiftKey hook installed) and it lands here and syncs via KDE Connect.",
                style = MaterialTheme.typography.bodyMedium,
            )
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(clips, key = { it.id }) { clip ->
                    Card(Modifier.fillMaxWidth().clickable {
                        vm.copyToClipboard(clip.text)
                        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                    }) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${if (clip.fromMe) "This device" else "Peer"} • ${formatTime(clip.timestamp)}",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                Text(clip.text, maxLines = 4, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = { vm.deleteClip(clip.id) }) { Icon(Icons.Default.Delete, "delete") }
                        }
                    }
                }
            }
        }
    }
}

private fun formatTime(ts: Long): String =
    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(ts)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(vm: AppViewModel, onPair: () -> Unit, onMessages: () -> Unit) {
    val peers by vm.peers.collectAsState()
    val connected by vm.connected.collectAsState()
    val myIp = remember(connected) { vm.myIp() }
    Scaffold(topBar = { TopAppBar(title = { Text("Devices (Tailscale fallback)") }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AssistChip(
                onClick = {},
                label = { Text(if (connected) "Tailscale • ${myIp ?: "?"}" else "Tailscale offline") },
            )
            if (!connected) Text(
                "Open the Tailscale app and connect to your tailnet, then reopen WgShare.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPair, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.QrCode2, null); Text(" Pair")
                }
                Button(onClick = onMessages, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Send, null); Text(" Messages")
                }
            }
            OutlinedButton(onClick = vm::sendClipboard, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.ContentPaste, null); Text(" Send clipboard to peers")
            }
            Text("Paired devices", style = MaterialTheme.typography.titleMedium)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(peers, key = { it.deviceId }) { peer ->
                    ListItem(
                        headlineContent = { Text(peer.name) },
                        supportingContent = { Text("${peer.tsIp}  •  ${peer.deviceId.take(8)}") },
                        trailingContent = {
                            IconButton(onClick = { vm.removePeer(peer.deviceId) }) {
                                Icon(Icons.Default.Delete, "remove")
                            }
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PairScreen(vm: AppViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    var scanning by remember { mutableStateOf(false) }
    var hasCamera by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCamera = granted
        scanning = granted
    }
    val payload by produceState<String?>(initialValue = null) { value = vm.myPayload().encode() }

    Scaffold(topBar = { TopAppBar(title = { Text("Pair device") }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (scanning && hasCamera) {
                Text("Scan the other device's code")
                QrScanner(Modifier.fillMaxWidth().aspectRatio(1f)) { raw ->
                    decodePairing(raw)?.let { vm.pairWith(it); onDone() }
                }
                OutlinedButton(onClick = { scanning = false }) { Text("Cancel scan") }
            } else {
                Text("Both devices must be on the same Tailscale tailnet. Scan this code from the other device (one scan is enough).")
                payload?.let {
                    val bmp = remember(it) { qrBitmap(it) }
                    Image(bmp.asImageBitmap(), "pairing QR", Modifier.fillMaxWidth().aspectRatio(1f))
                }
                Button(
                    onClick = { if (hasCamera) scanning = true else cameraLauncher.launch(Manifest.permission.CAMERA) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (hasCamera) "Scan a device" else "Grant camera & scan") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessagesScreen(vm: AppViewModel, onBack: () -> Unit) {
    val messages by vm.messages.collectAsState()
    var draft by remember { mutableStateOf("") }
    Scaffold(topBar = { TopAppBar(title = { Text("Messages") }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).fillMaxSize()) {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(messages.reversed(), key = { it.id }) { m ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                "${if (m.fromMe) "Me" else "Peer"} • ${if (m.kind == MessageKind.CLIPBOARD) "clipboard" else "message"}",
                                style = MaterialTheme.typography.labelSmall,
                            )
                            Text(m.text, maxLines = 6, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(draft, { draft = it }, Modifier.weight(1f), label = { Text("Message") })
                IconButton(onClick = { if (draft.isNotBlank()) { vm.sendMessage(draft); draft = "" } }) {
                    Icon(Icons.Default.Send, "send")
                }
            }
        }
    }
}
