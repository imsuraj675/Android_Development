package com.example.sender

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow

// ── MainActivity ──────────────────────────────────────────────────────────────

class MainActivity : ComponentActivity() {
    private lateinit var server: KtorServer
    private var multicastLock: WifiManager.MulticastLock? = null
    private var pendingFileToSave: ReceivedFile? = null
    private var pendingFileTargetIds: Set<String>? = null
    private val pendingMultiFilesFlow = MutableStateFlow<List<Pair<String, Uri>>?>(null)

    private val pickFiles = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isEmpty()) { pendingFileTargetIds = null; return@registerForActivityResult }
        if (uris.size == 1) {
            val uri = uris.first()
            server.shareFile(getFileName(uri), getFileSize(uri), { contentResolver.openInputStream(uri) }, pendingFileTargetIds)
            pendingFileTargetIds = null
        } else {
            pendingMultiFilesFlow.value = uris.map { getFileName(it) to it }
            // pendingFileTargetIds held until dialog is resolved
        }
    }

    private val saveFile = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri: Uri? ->
        if (uri != null) pendingFileToSave?.let { f ->
            contentResolver.openOutputStream(uri)?.use { out ->
                f.tempFile.inputStream().use { it.copyTo(out) }
            }
        }
        pendingFileToSave = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        server = KtorServer(applicationContext)

        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        multicastLock = wifiManager.createMulticastLock("sender_mdns").also { it.acquire() }

        server.start()   // listens on 0.0.0.0 — all interfaces

        setContent {
            val ifaces           by server.networkIfaces.collectAsState()
            val activeMdnsIp     by server.activeMdnsIp.collectAsState()
            val count            by server.clientCount.collectAsState()
            val connected        by server.connectedDevices.collectAsState()
            val pending          by server.pendingPairings.collectAsState()
            val trusted          by server.trustedDevices.collectAsState()
            val received         by server.receivedMessages.collectAsState()
            val receivedFiles    by server.receivedFiles.collectAsState()
            val activeTransfers  by server.activeTransfers.collectAsState()
            val zipProgress      by server.zipProgress.collectAsState()
            val pendingMultiFiles by pendingMultiFilesFlow.collectAsState()

            SenderScreen(
                networkIfaces    = ifaces,
                activeMdnsIp     = activeMdnsIp,
                clientCount      = count,
                connectedDevices = connected,
                pendingPairings  = pending,
                trustedDevices   = trusted,
                received         = received,
                receivedFiles    = receivedFiles,
                activeTransfers  = activeTransfers,
                zipProgress      = zipProgress,
                pendingMultiFiles = pendingMultiFiles,
                onSend           = { text, ids -> server.sendToDevices(ids, text) },
                onSendAsFile     = { text, ids -> server.sendTextAsFile(text, ids) },
                onPickFile       = { ids ->
                    pendingFileTargetIds = ids
                    pickFiles.launch("*/*")
                },
                onSendFilesIndividual = { files ->
                    val ids = pendingFileTargetIds
                    val toShare = files.map { (name, uri) ->
                        FileToShare(name, getFileSize(uri)) { contentResolver.openInputStream(uri) }
                    }
                    server.shareFiles(toShare, ids)
                    pendingMultiFilesFlow.value = null
                    pendingFileTargetIds = null
                },
                onSendFilesAsZip = { files ->
                    val ids = pendingFileTargetIds
                    val zipName = "archive_${System.currentTimeMillis()}.zip"
                    val toShare = files.map { (name, uri) ->
                        FileToShare(name, getFileSize(uri)) { contentResolver.openInputStream(uri) }
                    }
                    server.createAndShareZip(toShare, zipName, ids)
                    pendingMultiFilesFlow.value = null
                    pendingFileTargetIds = null
                },
                onDismissMultiFiles = {
                    pendingMultiFilesFlow.value = null
                    pendingFileTargetIds = null
                },
                onSaveFile       = { f -> pendingFileToSave = f; saveFile.launch(f.name) },
                onDiscardFile    = { f -> server.discardFile(f.id) },
                onCopyText       = { text ->
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("message", text))
                },
                onAcceptPairing      = { id, alias -> server.acceptPairing(id, alias) },
                onRejectPairing      = { id -> server.rejectPairing(id) },
                onRenameDevice       = { id, alias -> server.renameDevice(id, alias) },
                onForgetDevice       = { id -> server.forgetDevice(id) },
                onToggleBlockDevice  = { id -> server.toggleBlockDevice(id) },
                onSwitchMdns         = { ip -> server.switchMdnsTo(ip) }
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        server.stop()
        multicastLock?.release()
    }

    private fun getFileName(uri: Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val col = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (col >= 0 && c.moveToFirst()) return c.getString(col)
        }
        return uri.lastPathSegment ?: "file"
    }

    private fun getFileSize(uri: Uri): Long {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            val col = c.getColumnIndex(OpenableColumns.SIZE)
            if (col >= 0 && c.moveToFirst()) return c.getLong(col)
        }
        return -1L
    }
}

// ── QR dialog (all interfaces) ────────────────────────────────────────────────

@Composable
private fun QrCodeImage(content: String) {
    val bitmap = remember(content) {
        runCatching {
            val size = 512
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            for (x in 0 until size) for (y in 0 until size)
                bmp.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            bmp
        }.getOrNull()
    }
    if (bitmap != null) Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "QR",
        modifier = Modifier.size(200.dp)
    )
}

@Composable
private fun QrDialog(ifaces: List<NetworkIface>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scan to Connect") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ifaces.forEachIndexed { i, iface ->
                    if (i > 0) { Spacer(Modifier.height(8.dp)); HorizontalDivider(); Spacer(Modifier.height(8.dp)) }
                    Text(iface.label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    QrCodeImage("https://${iface.ip}:8443")
                    Text("https://${iface.ip}:8443", fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary)
                }
                if (ifaces.isNotEmpty()) { Spacer(Modifier.height(8.dp)); HorizontalDivider(); Spacer(Modifier.height(8.dp)) }
                Text("mDNS", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                QrCodeImage("https://phone.local:8443")
                Text("https://phone.local:8443", fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

// ── Pairing dialog ────────────────────────────────────────────────────────────

@Composable
private fun PairingDialog(request: PairingRequest, onAccept: (String) -> Unit, onReject: () -> Unit) {
    var alias by remember { mutableStateOf(request.name) }
    AlertDialog(
        onDismissRequest = { /* force explicit choice */ },
        title = { Text("New Device Wants to Connect") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(request.name, fontSize = 15.sp)
                Text("Platform: ${request.platform}", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("IP: ${request.ip}", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
                OutlinedTextField(
                    value = alias,
                    onValueChange = { alias = it },
                    label = { Text("Alias") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = { onAccept(alias.trim()) }) { Text("Accept") }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onReject,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) { Text("Reject") }
        }
    )
}

// ── Device management dialog ──────────────────────────────────────────────────

@Composable
private fun DevicesDialog(
    trusted: List<TrustedDevice>,
    connected: List<ConnectedDeviceInfo>,
    onRename: (String, String) -> Unit,
    onForget: (String) -> Unit,
    onToggleBlock: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var renamingId  by remember { mutableStateOf<String?>(null) }
    var renameAlias by remember { mutableStateOf("") }

    val connectedIds = connected.map { it.deviceId }.toSet()
    val fmt = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

    if (renamingId != null) {
        // Show rename sub-dialog, hiding the list behind it
        AlertDialog(
            onDismissRequest = { renamingId = null },
            title = { Text("Rename Device") },
            text = {
                OutlinedTextField(
                    value = renameAlias,
                    onValueChange = { renameAlias = it },
                    label = { Text("Alias") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (renameAlias.isNotBlank()) onRename(renamingId!!, renameAlias.trim())
                    renamingId = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renamingId = null }) { Text("Cancel") } }
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Trusted Devices") },
            text = {
                if (trusted.isEmpty()) {
                    Text("No trusted devices yet.", fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 400.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(trusted, key = { it.id }) { d ->
                            val online = d.id in connectedIds
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text  = "● ",
                                        color = if (online) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 10.sp
                                    )
                                    Text(
                                        text     = d.alias + if (d.isBlocked) "  (blocked)" else "",
                                        fontSize = 14.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                Text(d.platform, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (d.lastKnownIp.isNotEmpty())
                                    Text(d.lastKnownIp, fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontFamily = FontFamily.Monospace)
                                if (d.lastSeen > 0)
                                    Text("Last seen ${fmt.format(Date(d.lastSeen))}", fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OutlinedButton(
                                        onClick = { renameAlias = d.alias; renamingId = d.id },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 3.dp)
                                    ) { Text("Rename", fontSize = 11.sp) }
                                    OutlinedButton(
                                        onClick = { onForget(d.id) },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 3.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = MaterialTheme.colorScheme.error)
                                    ) { Text("Forget", fontSize = 11.sp) }
                                    OutlinedButton(
                                        onClick = { onToggleBlock(d.id) },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 3.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = MaterialTheme.colorScheme.error)
                                    ) { Text(if (d.isBlocked) "Unblock" else "Block", fontSize = 11.sp) }
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
        )
    }
}

// ── Target-device picker ──────────────────────────────────────────────────────

@Composable
private fun TargetDeviceDialog(
    devices: List<ConnectedDeviceInfo>,
    onSelect: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedIds by remember { mutableStateOf(devices.map { it.deviceId }.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send to…") },
        text = {
            LazyColumn {
                items(devices, key = { it.deviceId }) { d ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedIds = if (d.deviceId in selectedIds)
                                    selectedIds - d.deviceId else selectedIds + d.deviceId
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = d.deviceId in selectedIds,
                            onCheckedChange = { checked ->
                                selectedIds = if (checked) selectedIds + d.deviceId
                                              else selectedIds - d.deviceId
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(d.alias, fontSize = 14.sp)
                            Text(d.ip, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSelect(selectedIds) },
                enabled = selectedIds.isNotEmpty()
            ) { Text("Send") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ── Message card ──────────────────────────────────────────────────────────────

private fun String.isLong() = length > 250 || count { it == '\n' } >= 4

@Composable
private fun MessageCard(msg: ReceivedMessage, onCopy: () -> Unit) {
    val long     = msg.text.isLong()
    var expanded by remember(msg.id) { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth()
            .then(if (long) Modifier.clickable { expanded = !expanded } else Modifier)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text  = "from ${msg.fromAlias}",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text      = msg.text,
                fontSize  = 14.sp,
                lineHeight = 20.sp,
                maxLines  = if (long && !expanded) 4 else Int.MAX_VALUE,
                overflow  = if (long && !expanded) TextOverflow.Ellipsis else TextOverflow.Clip
            )
            if (long) Text(
                text     = if (expanded) "▲ Read less" else "▼ Read more",
                fontSize = 11.sp,
                color    = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp)
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = onCopy, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                    Text("Copy", fontSize = 12.sp)
                }
            }
        }
    }
}

// ── File card ─────────────────────────────────────────────────────────────────

@Composable
private fun FileCard(file: ReceivedFile, onSave: () -> Unit, onDiscard: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(text = "from ${file.fromAlias}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text("📎 ${file.name}", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSave, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                    Text("Save", fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = onDiscard,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Discard", fontSize = 12.sp) }
            }
        }
    }
}

// ── Multi-file send dialog ────────────────────────────────────────────────────

@Composable
private fun MultiFileSendDialog(
    files: List<Pair<String, Uri>>,
    onSendIndividual: () -> Unit,
    onSendAsZip: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${files.size} Files Selected") },
        text = {
            Column {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 240.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(files) { (name, _) ->
                        Text("• $name", fontSize = 13.sp, modifier = Modifier.padding(vertical = 1.dp))
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Send each file individually, or bundle into a ZIP?",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                OutlinedButton(onClick = onSendAsZip) { Text("ZIP") }
                Button(onClick = onSendIndividual) { Text("Individual") }
            }
        }
    )
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun formatBytes(bytes: Long): String = when {
    bytes < 0L             -> "?"
    bytes < 1_024L         -> "${bytes}B"
    bytes < 1_048_576L     -> "${bytes / 1_024}KB"
    bytes < 1_073_741_824L -> "${"%.1f".format(bytes / 1_048_576f)}MB"
    else                   -> "${"%.2f".format(bytes / 1_073_741_824f)}GB"
}

// ── SenderScreen ──────────────────────────────────────────────────────────────

@Composable
fun SenderScreen(
    networkIfaces: List<NetworkIface>,
    activeMdnsIp: String?,
    clientCount: Int,
    connectedDevices: List<ConnectedDeviceInfo>,
    pendingPairings: List<PairingRequest>,
    trustedDevices: List<TrustedDevice>,
    received: List<ReceivedMessage>,
    receivedFiles: List<ReceivedFile>,
    activeTransfers: List<TransferProgress>,
    zipProgress: Float?,
    pendingMultiFiles: List<Pair<String, Uri>>?,
    onSend: (String, Set<String>) -> Unit,
    onSendAsFile: (String, Set<String>) -> Unit,
    onPickFile: (Set<String>) -> Unit,
    onSendFilesIndividual: (List<Pair<String, Uri>>) -> Unit,
    onSendFilesAsZip: (List<Pair<String, Uri>>) -> Unit,
    onDismissMultiFiles: () -> Unit,
    onSaveFile: (ReceivedFile) -> Unit,
    onDiscardFile: (ReceivedFile) -> Unit,
    onCopyText: (String) -> Unit,
    onAcceptPairing: (String, String) -> Unit,
    onRejectPairing: (String) -> Unit,
    onRenameDevice: (String, String) -> Unit,
    onForgetDevice: (String) -> Unit,
    onToggleBlockDevice: (String) -> Unit,
    onSwitchMdns: (String) -> Unit
) {
    var text          by remember { mutableStateOf("") }
    var showQr        by remember { mutableStateOf(false) }
    var showDevices   by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<String?>(null) }

    // ── Dialogs ──────────────────────────────────────────────────────────────

    if (showQr) QrDialog(ifaces = networkIfaces, onDismiss = { showQr = false })

    if (showDevices) DevicesDialog(
        trusted       = trustedDevices,
        connected     = connectedDevices,
        onRename      = onRenameDevice,
        onForget      = onForgetDevice,
        onToggleBlock = onToggleBlockDevice,
        onDismiss     = { showDevices = false }
    )

    pendingPairings.firstOrNull()?.let { req ->
        PairingDialog(
            request  = req,
            onAccept = { alias -> onAcceptPairing(req.deviceId, alias) },
            onReject = { onRejectPairing(req.deviceId) }
        )
    }

    if (pendingAction != null && connectedDevices.size > 1) {
        TargetDeviceDialog(
            devices  = connectedDevices,
            onSelect = { ids ->
                when (pendingAction) {
                    "text"   -> { onSend(text, ids); text = "" }
                    "file"   -> onPickFile(ids)
                    "asFile" -> { onSendAsFile(text, ids); text = "" }
                }
                pendingAction = null
            },
            onDismiss = { pendingAction = null }
        )
    }

    if (pendingMultiFiles != null) {
        MultiFileSendDialog(
            files           = pendingMultiFiles,
            onSendIndividual = { onSendFilesIndividual(pendingMultiFiles) },
            onSendAsZip      = { onSendFilesAsZip(pendingMultiFiles) },
            onDismiss        = onDismissMultiFiles
        )
    }

    // ── Helper: resolve send target ──────────────────────────────────────────

    fun triggerSend(action: String) {
        when (connectedDevices.size) {
            0    -> { /* button is disabled */ }
            1    -> {
                val ids = setOf(connectedDevices.first().deviceId)
                when (action) {
                    "text"   -> { onSend(text, ids); text = "" }
                    "file"   -> onPickFile(ids)
                    "asFile" -> { onSendAsFile(text, ids); text = "" }
                }
            }
            else -> pendingAction = action
        }
    }

    // ── UI ───────────────────────────────────────────────────────────────────

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Title bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Sender", fontSize = 22.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = { showDevices = true },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) { Text("Devices", fontSize = 12.sp) }
                        OutlinedButton(
                            onClick = { showQr = true },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) { Text("QR", fontSize = 12.sp) }
                    }
                }

                // Network interfaces with phone.local toggle
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    networkIfaces.forEach { iface ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${iface.label}: https://${iface.ip}:8443",
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f)
                            )
                            val isMdnsActive = iface.ip == activeMdnsIp
                            TextButton(
                                onClick = { onSwitchMdns(iface.ip) },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = if (isMdnsActive) "📡 phone.local" else "Set phone.local",
                                    fontSize = 10.sp,
                                    color = if (isMdnsActive) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Text(
                        text = if (activeMdnsIp != null) "phone.local → $activeMdnsIp"
                               else "phone.local (unavailable)",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Connection status
                val statusText = when (clientCount) {
                    0    -> "No devices connected"
                    1    -> "1 device connected — ${connectedDevices.first().alias}"
                    else -> "$clientCount devices connected"
                }
                Text(
                    text  = statusText,
                    fontSize = 12.sp,
                    color = if (clientCount > 0) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Pending pairing badge
                if (pendingPairings.isNotEmpty()) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                        Text(
                            text = "⏳ ${pendingPairings.size} device(s) waiting for pairing approval",
                            modifier = Modifier.padding(10.dp),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }

                // Message input
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Message") },
                    minLines = 3,
                    maxLines = Int.MAX_VALUE
                )

                // Send buttons
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick  = { triggerSend("text") },
                        modifier = Modifier.weight(1f),
                        enabled  = text.isNotBlank() && clientCount > 0
                    ) { Text("Send") }
                    OutlinedButton(
                        onClick  = { triggerSend("asFile") },
                        modifier = Modifier.weight(1f),
                        enabled  = text.isNotBlank() && clientCount > 0
                    ) { Text("As File") }
                    OutlinedButton(
                        onClick  = { triggerSend("file") },
                        modifier = Modifier.weight(1f),
                        enabled  = clientCount > 0
                    ) { Text("File…") }
                }

                // ZIP creation progress
                if (zipProgress != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Creating ZIP…", fontSize = 12.sp)
                            Text(
                                "${(zipProgress * 100).toInt()}%",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        LinearProgressIndicator(
                            progress = { zipProgress },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // Active download progress bars
                if (activeTransfers.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        activeTransfers.forEach { t ->
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = t.name,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = "${formatBytes(t.bytesSent)} / ${formatBytes(t.totalBytes)}",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                LinearProgressIndicator(
                                    progress = { t.fraction },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }

                // Received section
                if (received.isNotEmpty() || receivedFiles.isNotEmpty()) {
                    HorizontalDivider()
                    Text("From devices", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(receivedFiles.asReversed(), key = { it.id }) { f ->
                            FileCard(f, onSave = { onSaveFile(f) }, onDiscard = { onDiscardFile(f) })
                        }
                        items(received.asReversed(), key = { it.id }) { m ->
                            MessageCard(m, onCopy = { onCopyText(m.text) })
                        }
                    }
                }
            }
        }
    }
}
