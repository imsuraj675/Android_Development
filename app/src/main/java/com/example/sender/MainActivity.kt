package com.example.sender

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import com.example.sender.ui.SenderTheme
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

// ── MainActivity ──────────────────────────────────────────────────────────────

class MainActivity : ComponentActivity() {
    private val app get() = application as SenderApp
    private val server get() = app.server
    private var pendingFileToSave: ReceivedFile? = null
    private var pendingZipStarted = false
    private val sendAsZipFlow         = MutableStateFlow(false)
    private val pendingMultiFilesFlow = MutableStateFlow<List<Pair<String, Uri>>?>(null)
    private val pendingShareFilesFlow = MutableStateFlow<List<Pair<String, Uri>>?>(null)
    private val pendingShareTextFlow  = MutableStateFlow<String?>(null)

    private val requestNotifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val pickFiles = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        val files = uris.map { getFileName(it) to it }
        pendingZipStarted = uris.size > 1 && sendAsZipFlow.value
        if (pendingZipStarted) {
            val toShare = uris.map { uri ->
                FileToShare(getFileName(uri), getFileSize(uri)) { contentResolver.openInputStream(uri) }
            }
            server.startBackgroundZip(toShare, "archive_${System.currentTimeMillis()}.zip")
        }
        pendingMultiFilesFlow.value = files
    }

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            contentResolver.takePersistableUriPermission(uri, flags)
            server.updateDownloadLocation(uri.toString())
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
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        app.startServer()
        SenderService.start(this)
        handleShareIntent(intent)

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
            val incomingTransfers by server.incomingTransfers.collectAsState()
            val zipProgress      by server.zipProgress.collectAsState()
            val outgoingBatch    by server.outgoingBatch.collectAsState()
            val incomingBatch    by server.incomingBatch.collectAsState()
            val pendingMultiFiles by pendingMultiFilesFlow.collectAsState()
            val pendingShareFiles by pendingShareFilesFlow.collectAsState()
            val pendingShareText  by pendingShareTextFlow.collectAsState()
            val pendingTransfers  by server.pendingTransfers.collectAsState()
            val transferPrefs    by server.transferPrefs.collectAsState()
            val sendAsZip        by sendAsZipFlow.collectAsState()
            val isServerRunning  by server.isRunning.collectAsState()

            SenderTheme {
                SenderScreen(
                    networkIfaces     = ifaces,
                    activeMdnsIp      = activeMdnsIp,
                    clientCount       = count,
                    connectedDevices  = connected,
                    pendingPairings   = pending,
                    trustedDevices    = trusted,
                    received          = received,
                    receivedFiles     = receivedFiles,
                    activeTransfers   = activeTransfers,
                    incomingTransfers = incomingTransfers,
                    zipProgress       = zipProgress,
                    outgoingBatch     = outgoingBatch,
                    incomingBatch     = incomingBatch,
                    pendingMultiFiles = pendingMultiFiles,
                    pendingShareFiles = pendingShareFiles,
                    pendingShareText  = pendingShareText,
                    pendingTransfers  = pendingTransfers,
                    transferPrefs     = transferPrefs,
                    sendAsZip         = sendAsZip,
                    isServerRunning   = isServerRunning,
                    onToggleServer    = {
                        if (isServerRunning) { app.stopServer(); SenderService.stop(this) }
                        else { app.startServer(); SenderService.start(this) }
                    },
                    onToggleSendAsZip = { sendAsZipFlow.value = it },
                    onSend            = { text, ids -> server.sendToDevices(ids, text) },
                    onSendAsFile      = { text, ids -> server.sendTextAsFile(text, ids) },
                    onPickFile        = { pickFiles.launch("*/*") },
                    onSendFiles       = { files, ids ->
                        if (pendingZipStarted) server.sendPendingZip(ids)
                        else if (files.size == 1) {
                            val (name, uri) = files.first()
                            server.shareFile(name, getFileSize(uri), { contentResolver.openInputStream(uri) }, ids)
                        } else {
                            val toShare = files.map { (name, uri) ->
                                FileToShare(name, getFileSize(uri)) { contentResolver.openInputStream(uri) }
                            }
                            server.shareFiles(toShare, ids)
                        }
                        pendingMultiFilesFlow.value = null
                        pendingZipStarted = false
                    },
                    onSendShared      = { files, ids ->
                        val toShare = files.map { (name, uri) ->
                            FileToShare(name, getFileSize(uri)) { contentResolver.openInputStream(uri) }
                        }
                        if (sendAsZip && toShare.size > 1)
                            server.createAndShareZip(toShare, "archive_${System.currentTimeMillis()}.zip", ids)
                        else server.shareFiles(toShare, ids)
                        pendingShareFilesFlow.value = null
                    },
                    onDismissShare    = { pendingShareFilesFlow.value = null },
                    onSendSharedText  = { sharedText, asFile, ids ->
                        if (asFile) server.sendTextAsFile(sharedText, ids)
                        else server.sendToDevices(ids, sharedText)
                        pendingShareTextFlow.value = null
                    },
                    onDismissShareText = { pendingShareTextFlow.value = null },
                    onCancelFilePick  = {
                        server.cancelPendingZip()
                        pendingMultiFilesFlow.value = null
                        pendingZipStarted = false
                    },
                    onSaveFile        = { f -> pendingFileToSave = f; saveFile.launch(f.name) },
                    onDiscardFile     = { f -> server.discardFile(f.id) },
                    onCopyText        = { text ->
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("message", text))
                    },
                    onAcceptPairing      = { id, alias -> server.acceptPairing(id, alias) },
                    onRejectPairing      = { id -> server.rejectPairing(id) },
                    onRenameDevice       = { id, alias -> server.renameDevice(id, alias) },
                    onDisconnectDevice   = { id -> server.disconnectDevice(id) },
                    onForgetDevice       = { id -> server.forgetDevice(id) },
                    onToggleBlockDevice  = { id -> server.toggleBlockDevice(id) },
                    onSwitchMdns         = { ip -> server.switchMdnsTo(ip) },
                    onAcceptTransfer     = { id -> server.acceptTransfer(id) },
                    onRejectTransfer     = { id -> server.rejectTransfer(id) },
                    onSaveTransferPrefs  = { prefs -> server.updateTransferPrefs(prefs) },
                    onPickDownloadFolder = { pickFolder.launch(null) }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    override fun onDestroy() { super.onDestroy() }

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

    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action == Intent.ACTION_SEND) {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (sharedText != null && intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java) == null) {
                pendingShareTextFlow.value = sharedText
                return
            }
        }
        val uris = extractUrisFromIntent(intent) ?: return
        uris.forEach { uri -> runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        pendingShareFilesFlow.value = uris.map { getFileName(it) to it }
    }

    private fun extractUrisFromIntent(intent: Intent?): List<Uri>? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_SEND          -> listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
            else -> null
        }.takeIf { it?.isNotEmpty() == true }
    }
}

// ── QR dialog ─────────────────────────────────────────────────────────────────

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
        modifier = Modifier
            .size(200.dp)
            .clip(RoundedCornerShape(12.dp))
    )
}

@Composable
private fun QrDialog(ifaces: List<NetworkIface>, activeMdnsIp: String?, onDismiss: () -> Unit) {
    val entries = remember(ifaces, activeMdnsIp) {
        val mdnsEntry = "mDNS (phone.local)" to "http://phone.local:8080"
        val ifaceEntries = ifaces.map { it.label to "http://${it.ip}:8080" }
        // Primary IP iface first, phone.local always last
        val sortedIfaces = if (activeMdnsIp != null) {
            val primIdx = ifaces.indexOfFirst { it.ip == activeMdnsIp }
            if (primIdx <= 0) ifaceEntries
            else listOf(ifaceEntries[primIdx]) + ifaceEntries.subList(0, primIdx) + ifaceEntries.subList(primIdx + 1, ifaceEntries.size)
        } else ifaceEntries
        sortedIfaces + listOf(mdnsEntry)
    }
    var expandedIdx by remember { mutableStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.QrCode, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text("Scan to Connect") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                entries.forEachIndexed { i, (label, url) ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ExpandableQrCard(
                        label = label, url = url,
                        expanded = i == expandedIdx,
                        onToggle = { expandedIdx = if (expandedIdx == i) -1 else i }
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun ExpandableQrCard(label: String, url: String, expanded: Boolean, onToggle: () -> Unit) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle)
                .padding(horizontal = 4.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                if (label.contains("mDNS", ignoreCase = true)) Icons.Filled.Router else Icons.Filled.Wifi,
                null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(url.removePrefix("http://"), fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace)
            }
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                QrCodeImage(url)
                SelectionContainer {
                    Text(url, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun SelectionContainer(content: @Composable () -> Unit) = content()

// ── Pairing dialog ────────────────────────────────────────────────────────────

@Composable
private fun PairingDialog(request: PairingRequest, onAccept: (String) -> Unit, onReject: () -> Unit) {
    var alias by remember { mutableStateOf(request.name) }
    AlertDialog(
        onDismissRequest = {},
        icon = { Icon(Icons.Filled.DevicesOther, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text("New Device") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Name:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(request.name, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Platform:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(request.platform, fontSize = 12.sp)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("IP:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(request.ip, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
                OutlinedTextField(
                    value = alias, onValueChange = { alias = it },
                    label = { Text("Alias (optional)") },
                    leadingIcon = { Icon(Icons.Filled.Edit, null) },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = { onAccept(alias.trim()) }) {
                Icon(Icons.Filled.Check, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Accept")
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onReject,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Filled.Close, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Reject")
            }
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
        AlertDialog(
            onDismissRequest = { renamingId = null },
            icon = { Icon(Icons.Filled.Edit, null) },
            title = { Text("Rename Device") },
            text = {
                OutlinedTextField(
                    value = renameAlias, onValueChange = { renameAlias = it },
                    label = { Text("Alias") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = { if (renameAlias.isNotBlank()) onRename(renamingId!!, renameAlias.trim()); renamingId = null }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renamingId = null }) { Text("Cancel") } }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Devices, null, tint = MaterialTheme.colorScheme.primary)
                Text("Trusted Devices")
                if (trusted.isNotEmpty()) {
                    Spacer(Modifier.width(4.dp))
                    Badge { Text("${trusted.size}") }
                }
            }
        },
        text = {
            if (trusted.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Outlined.DevicesOther, null, modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("No trusted devices yet", fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(trusted, key = { it.id }) { d ->
                        val online = d.id in connectedIds
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Box(
                                        modifier = Modifier.size(8.dp).clip(CircleShape)
                                            .background(if (online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                                    )
                                    Text(
                                        d.alias + if (d.isBlocked) " · blocked" else "",
                                        fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (online) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                            shape = RoundedCornerShape(4.dp)
                                        ) { Text("Online", fontSize = 10.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            color = MaterialTheme.colorScheme.onPrimaryContainer) }
                                    }
                                }
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.padding(start = 16.dp)
                                ) {
                                    Text(d.platform, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (d.lastSeen > 0) {
                                        Text("·", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                                        Text(fmt.format(Date(d.lastSeen)), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OutlinedButton(
                                        onClick = { renameAlias = d.alias; renamingId = d.id },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 3.dp)
                                    ) { Icon(Icons.Filled.Edit, null, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(3.dp)); Text("Rename", fontSize = 11.sp) }
                                    OutlinedButton(
                                        onClick = { onToggleBlock(d.id) },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 3.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                    ) { Icon(if (d.isBlocked) Icons.Filled.LockOpen else Icons.Filled.Block, null, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(3.dp)); Text(if (d.isBlocked) "Unblock" else "Block", fontSize = 11.sp) }
                                    OutlinedButton(
                                        onClick = { onForget(d.id) },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 3.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                    ) { Icon(Icons.Filled.PersonRemove, null, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(3.dp)); Text("Forget", fontSize = 11.sp) }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

// ── Target device picker ──────────────────────────────────────────────────────

@Composable
private fun TargetDeviceDialog(
    devices: List<ConnectedDeviceInfo>,
    onSelect: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedIds by remember { mutableStateOf(devices.map { it.deviceId }.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.AutoMirrored.Filled.Send, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text("Send to…") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(devices, key = { it.deviceId }) { d ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (d.deviceId in selectedIds) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth().clickable {
                            selectedIds = if (d.deviceId in selectedIds) selectedIds - d.deviceId else selectedIds + d.deviceId
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = d.deviceId in selectedIds, onCheckedChange = { checked ->
                                selectedIds = if (checked) selectedIds + d.deviceId else selectedIds - d.deviceId
                            })
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(d.alias, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text(d.ip, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSelect(selectedIds) }, enabled = selectedIds.isNotEmpty()) {
                Icon(Icons.AutoMirrored.Filled.Send, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Send")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ── Share target dialog ───────────────────────────────────────────────────────

@Composable
private fun ShareTargetDialog(
    files: List<Pair<String, Uri>>,
    connectedDevices: List<ConnectedDeviceInfo>,
    isServerRunning: Boolean,
    onSend: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedIds by remember { mutableStateOf(connectedDevices.map { it.deviceId }.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Share, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(if (files.size == 1) "Share \"${files.first().first}\"" else "Share ${files.size} files") },
        text = {
            when {
                !isServerRunning -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Server is stopped. Tap ▶ to start.", fontSize = 13.sp)
                }
                connectedDevices.isEmpty() -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("No devices connected.", fontSize = 13.sp)
                }
                connectedDevices.size == 1 -> Text("Send to ${connectedDevices.first().alias}?")
                else -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LazyColumn(modifier = Modifier.heightIn(max = 250.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(connectedDevices, key = { it.deviceId }) { d ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (d.deviceId in selectedIds) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    selectedIds = if (d.deviceId in selectedIds) selectedIds - d.deviceId else selectedIds + d.deviceId
                                }
                            ) {
                                Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = d.deviceId in selectedIds, onCheckedChange = { checked ->
                                        selectedIds = if (checked) selectedIds + d.deviceId else selectedIds - d.deviceId
                                    })
                                    Spacer(Modifier.width(8.dp))
                                    Text(d.alias, fontSize = 14.sp)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (isServerRunning && connectedDevices.isNotEmpty()) {
                Button(
                    onClick = { onSend(if (connectedDevices.size == 1) setOf(connectedDevices.first().deviceId) else selectedIds) },
                    enabled = connectedDevices.size == 1 || selectedIds.isNotEmpty()
                ) { Text("Send") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ── Share text dialog ─────────────────────────────────────────────────────────

@Composable
private fun ShareTextDialog(
    text: String,
    connectedDevices: List<ConnectedDeviceInfo>,
    isServerRunning: Boolean,
    onSend: (asFile: Boolean, targets: Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var sendAsFile  by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(connectedDevices.map { it.deviceId }.toSet()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.AutoMirrored.Filled.TextSnippet, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text("Share text") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    !isServerRunning -> Text("Server is stopped.")
                    connectedDevices.isEmpty() -> Text("No devices connected.")
                    else -> {
                        val preview = if (text.length > 400) text.take(400) + "…" else text
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
                            Text(preview, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().heightIn(max = 120.dp).verticalScroll(rememberScrollState()).padding(10.dp))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = !sendAsFile, onClick = { sendAsFile = false },
                                label = { Text("As text") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.Message, null, modifier = Modifier.size(16.dp)) })
                            FilterChip(selected = sendAsFile, onClick = { sendAsFile = true },
                                label = { Text("As .txt") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null, modifier = Modifier.size(16.dp)) })
                        }
                        if (connectedDevices.size > 1) {
                            Text("Send to:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            LazyColumn(modifier = Modifier.heightIn(max = 130.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                items(connectedDevices, key = { it.deviceId }) { d ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().clickable {
                                            selectedIds = if (d.deviceId in selectedIds) selectedIds - d.deviceId else selectedIds + d.deviceId
                                        }.padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(checked = d.deviceId in selectedIds, onCheckedChange = { checked ->
                                            selectedIds = if (checked) selectedIds + d.deviceId else selectedIds - d.deviceId
                                        })
                                        Spacer(Modifier.width(8.dp))
                                        Text(d.alias, fontSize = 14.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (isServerRunning && connectedDevices.isNotEmpty()) {
                Button(
                    onClick = { onSend(sendAsFile, if (connectedDevices.size == 1) setOf(connectedDevices.first().deviceId) else selectedIds) },
                    enabled = connectedDevices.size == 1 || selectedIds.isNotEmpty()
                ) { Text("Send") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ── Transfer approval dialog ──────────────────────────────────────────────────

@Composable
private fun TransferApprovalDialog(
    transfer: IncomingTransfer,
    onAccept: () -> Unit,
    onReject: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {},
        icon = { Icon(Icons.Filled.Download, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text("Incoming Transfer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Filled.DevicesOther, null, tint = MaterialTheme.colorScheme.primary)
                        Column {
                            Text(transfer.deviceAlias, fontWeight = FontWeight.Medium)
                            Text("${transfer.files.size} file(s) · ${formatBytes(transfer.totalBytes)}",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (transfer.files.isNotEmpty()) {
                    LazyColumn(modifier = Modifier.heightIn(max = 180.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(transfer.files) { f ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(fileIcon(f.name), null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(f.name, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(formatBytes(f.size), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onAccept) {
                Icon(Icons.Filled.Check, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Accept")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onReject, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Icon(Icons.Filled.Close, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Reject")
            }
        }
    )
}

// ── Settings dialog ───────────────────────────────────────────────────────────

@Composable
private fun SettingsDialog(
    prefs: TransferPrefs,
    onSave: (TransferPrefs) -> Unit,
    onPickFolder: () -> Unit,
    onDismiss: () -> Unit
) {
    var autoDownload by remember { mutableStateOf(prefs.autoDownload) }
    var thresholdMb  by remember { mutableStateOf((prefs.approvalThresholdBytes / 1_048_576L).toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Settings, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text("Transfer Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Auto-download row
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Auto-download", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text("Save incoming files automatically", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = autoDownload, onCheckedChange = { autoDownload = it })
                    }
                }
                // Threshold
                AnimatedVisibility(visible = autoDownload) {
                    OutlinedTextField(
                        value = thresholdMb, onValueChange = { thresholdMb = it },
                        label = { Text("Auto-accept up to (MB)") },
                        leadingIcon = { Icon(Icons.Filled.Shield, null) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                // Download folder
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp)) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Text("Download folder", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                        Text(
                            prefs.downloadLocationUri?.let { uriDisplayPath(it) } ?: "Downloads/LocalShare (default)",
                            fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                        OutlinedButton(onClick = onPickFolder, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                            Icon(Icons.Filled.FolderOpen, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Choose Folder", fontSize = 12.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val mb = thresholdMb.toLongOrNull()?.coerceAtLeast(1L) ?: 50L
                onSave(prefs.copy(autoDownload = autoDownload, approvalThresholdBytes = mb * 1_048_576L))
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
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
        icon = { Icon(Icons.Filled.FileCopy, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text("${files.size} Files Selected") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LazyColumn(modifier = Modifier.heightIn(max = 200.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(files) { (name, _) ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(fileIcon(name), null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Text("Send individually or bundle into a ZIP?", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                OutlinedButton(onClick = onSendAsZip) {
                    Icon(Icons.Filled.FolderZip, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("ZIP")
                }
                Button(onClick = onSendIndividual) {
                    Icon(Icons.Filled.FileOpen, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Individual")
                }
            }
        }
    )
}

// ── Message card ──────────────────────────────────────────────────────────────

private fun String.isLong() = length > 250 || count { it == '\n' } >= 4

@Composable
private fun MessageCard(msg: ReceivedMessage, onCopy: () -> Unit) {
    val long     = msg.text.isLong()
    var expanded by remember(msg.id) { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().then(if (long) Modifier.clickable { expanded = !expanded } else Modifier),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.AutoMirrored.Filled.Message, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                Text("from ${msg.fromAlias}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            }
            Text(
                text = msg.text,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                maxLines = if (long && !expanded) 4 else Int.MAX_VALUE,
                overflow = if (long && !expanded) TextOverflow.Ellipsis else TextOverflow.Clip
            )
            if (long) {
                Text(
                    text = if (expanded) "▲ Read less" else "▼ Read more",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.primary,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                FilledTonalButton(
                    onClick = onCopy,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Filled.ContentCopy, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Copy", fontSize = 12.sp)
                }
            }
        }
    }
}

// ── File card ─────────────────────────────────────────────────────────────────

@Composable
private fun FileCard(file: ReceivedFile, onSave: () -> Unit, onDiscard: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // File type icon in a circle
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(fileIcon(file.name), null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(file.name, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(formatBytes(file.size), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("·", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                    Text("from ${file.fromAlias}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FilledTonalIconButton(onClick = onSave, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Save, "Save", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onDiscard, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.Delete, "Discard", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

// ── Transfer progress card ────────────────────────────────────────────────────

@Composable
private fun TransferCard(
    transfers: List<TransferProgress>,
    batch: TransferBatch?,
    label: String,
    icon: ImageVector,
    tint: Color
) {
    if (transfers.isEmpty() && batch == null) return
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
                Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
                if (batch != null && batch.total > 1) {
                    Spacer(Modifier.weight(1f))
                    Surface(color = tint.copy(alpha = 0.15f), shape = RoundedCornerShape(4.dp)) {
                        Text("${batch.done}/${batch.total} files",
                            fontSize = 10.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            color = tint)
                    }
                }
            }
            transfers.forEach { t ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                            if (t.totalBytes > 0) {
                                CircularProgressIndicator(
                                    progress = { t.fraction },
                                    modifier = Modifier.size(36.dp),
                                    strokeWidth = 3.dp,
                                    color = tint,
                                    trackColor = tint.copy(alpha = 0.15f),
                                    strokeCap = StrokeCap.Round
                                )
                                Text("${(t.fraction * 100).toInt()}%", fontSize = 8.sp, color = tint)
                            } else {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(36.dp),
                                    strokeWidth = 3.dp,
                                    color = tint,
                                    trackColor = tint.copy(alpha = 0.15f),
                                    strokeCap = StrokeCap.Round
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(t.name, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (t.totalBytes > 0) "${formatBytes(t.bytesSent)} / ${formatBytes(t.totalBytes)}"
                                else formatBytes(t.bytesSent),
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (t.totalBytes > 0) {
                        LinearProgressIndicator(
                            progress = { t.fraction },
                            modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                            color = tint,
                            trackColor = tint.copy(alpha = 0.15f),
                            strokeCap = StrokeCap.Round
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                            color = tint,
                            trackColor = tint.copy(alpha = 0.15f),
                            strokeCap = StrokeCap.Round
                        )
                    }
                }
            }
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun formatBytes(bytes: Long): String = when {
    bytes < 0L             -> "?"
    bytes < 1_024L         -> "${bytes} B"
    bytes < 1_048_576L     -> "${bytes / 1_024} KB"
    bytes < 1_073_741_824L -> "${"%.1f".format(bytes / 1_048_576f)} MB"
    else                   -> "${"%.2f".format(bytes / 1_073_741_824f)} GB"
}

private fun uriDisplayPath(uriString: String): String = try {
    val last = Uri.parse(uriString).lastPathSegment ?: return uriString
    "/" + Uri.decode(last).substringAfter(':')
} catch (_: Exception) { uriString }

private fun fileIcon(name: String): ImageVector {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "heic" -> Icons.Filled.Image
        "mp4", "mkv", "avi", "mov", "webm", "flv", "wmv"          -> Icons.Filled.Videocam
        "mp3", "flac", "aac", "ogg", "wav", "m4a"                 -> Icons.Filled.Audiotrack
        "pdf"                                                       -> Icons.Filled.PictureAsPdf
        "zip", "rar", "7z", "tar", "gz", "bz2"                    -> Icons.Filled.FolderZip
        "apk"                                                       -> Icons.Filled.Android
        "txt", "md", "log"                                         -> Icons.Filled.Description
        "doc", "docx"                                              -> Icons.AutoMirrored.Filled.Article
        "xls", "xlsx", "csv"                                       -> Icons.Filled.TableChart
        "ppt", "pptx"                                              -> Icons.Filled.Slideshow
        else                                                        -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
}

// ── SenderScreen ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
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
    incomingTransfers: List<TransferProgress>,
    zipProgress: Float?,
    outgoingBatch: TransferBatch?,
    incomingBatch: Map<String, TransferBatch>,
    pendingMultiFiles: List<Pair<String, Uri>>?,
    pendingShareFiles: List<Pair<String, Uri>>?,
    pendingShareText: String?,
    pendingTransfers: List<IncomingTransfer>,
    transferPrefs: TransferPrefs,
    sendAsZip: Boolean,
    isServerRunning: Boolean,
    onToggleServer: () -> Unit,
    onToggleSendAsZip: (Boolean) -> Unit,
    onSend: (String, Set<String>) -> Unit,
    onSendAsFile: (String, Set<String>) -> Unit,
    onPickFile: () -> Unit,
    onSendFiles: (List<Pair<String, Uri>>, Set<String>) -> Unit,
    onSendShared: (List<Pair<String, Uri>>, Set<String>) -> Unit,
    onDismissShare: () -> Unit,
    onSendSharedText: (String, Boolean, Set<String>) -> Unit,
    onDismissShareText: () -> Unit,
    onCancelFilePick: () -> Unit,
    onSaveFile: (ReceivedFile) -> Unit,
    onDiscardFile: (ReceivedFile) -> Unit,
    onCopyText: (String) -> Unit,
    onAcceptPairing: (String, String) -> Unit,
    onRejectPairing: (String) -> Unit,
    onRenameDevice: (String, String) -> Unit,
    onDisconnectDevice: (String) -> Unit,
    onForgetDevice: (String) -> Unit,
    onToggleBlockDevice: (String) -> Unit,
    onSwitchMdns: (String) -> Unit,
    onAcceptTransfer: (String) -> Unit,
    onRejectTransfer: (String) -> Unit,
    onSaveTransferPrefs: (TransferPrefs) -> Unit,
    onPickDownloadFolder: () -> Unit
) {
    var text         by remember { mutableStateOf("") }
    var showQr       by remember { mutableStateOf(false) }
    var showDevices  by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<String?>(null) }

    // ── Dialogs ──────────────────────────────────────────────────────────────

    if (showQr) QrDialog(ifaces = networkIfaces, activeMdnsIp = activeMdnsIp, onDismiss = { showQr = false })
    if (showDevices) DevicesDialog(trusted = trustedDevices, connected = connectedDevices,
        onRename = onRenameDevice, onForget = onForgetDevice,
        onToggleBlock = onToggleBlockDevice, onDismiss = { showDevices = false })
    if (showSettings) SettingsDialog(prefs = transferPrefs, onSave = onSaveTransferPrefs,
        onPickFolder = onPickDownloadFolder, onDismiss = { showSettings = false })

    pendingPairings.firstOrNull()?.let { req ->
        PairingDialog(request = req,
            onAccept = { alias -> onAcceptPairing(req.deviceId, alias) },
            onReject = { onRejectPairing(req.deviceId) })
    }

    pendingTransfers.firstOrNull()?.let { transfer ->
        TransferApprovalDialog(transfer = transfer,
            onAccept = { onAcceptTransfer(transfer.transferId) },
            onReject = { onRejectTransfer(transfer.transferId) })
    }

    if (pendingAction != null && connectedDevices.size > 1) {
        TargetDeviceDialog(devices = connectedDevices, onSelect = { ids ->
            when (pendingAction) {
                "text"   -> { onSend(text, ids); text = "" }
                "asFile" -> { onSendAsFile(text, ids); text = "" }
            }
            pendingAction = null
        }, onDismiss = { pendingAction = null })
    }

    LaunchedEffect(pendingMultiFiles) {
        val files = pendingMultiFiles ?: return@LaunchedEffect
        when (connectedDevices.size) {
            0    -> onCancelFilePick()
            1    -> onSendFiles(files, setOf(connectedDevices.first().deviceId))
        }
    }

    if (pendingMultiFiles != null && connectedDevices.size > 1) {
        TargetDeviceDialog(devices = connectedDevices,
            onSelect = { ids -> onSendFiles(pendingMultiFiles!!, ids) },
            onDismiss = onCancelFilePick)
    }

    if (pendingShareFiles != null) {
        ShareTargetDialog(files = pendingShareFiles, connectedDevices = connectedDevices,
            isServerRunning = isServerRunning,
            onSend = { ids -> onSendShared(pendingShareFiles, ids) },
            onDismiss = onDismissShare)
    }

    if (pendingShareText != null) {
        ShareTextDialog(text = pendingShareText, connectedDevices = connectedDevices,
            isServerRunning = isServerRunning,
            onSend = { asFile, ids -> onSendSharedText(pendingShareText, asFile, ids) },
            onDismiss = onDismissShareText)
    }

    fun triggerSend(action: String) {
        when (connectedDevices.size) {
            0    -> {}
            1    -> {
                val ids = setOf(connectedDevices.first().deviceId)
                when (action) {
                    "text"   -> { onSend(text, ids); text = "" }
                    "asFile" -> { onSendAsFile(text, ids); text = "" }
                }
            }
            else -> pendingAction = action
        }
    }

    // ── Aggregated incoming batch ─────────────────────────────────────────────
    val incomingBatchAgg = if (incomingBatch.isNotEmpty()) TransferBatch(
        total = incomingBatch.values.sumOf { it.total },
        done  = incomingBatch.values.sumOf { it.done }
    ) else null

    // ── Layout ────────────────────────────────────────────────────────────────
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.Wifi, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                        Text("Sender", fontWeight = FontWeight.SemiBold)
                    }
                },
                actions = {
                    IconButton(onClick = { showQr = true }) {
                        Icon(Icons.Filled.QrCode, "QR Code", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { showDevices = true }) {
                        Icon(Icons.Filled.Devices, "Devices", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, "Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ── Discoverable toggle ───────────────────────────────────────────
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            if (isServerRunning) Icons.Filled.Wifi else Icons.Filled.WifiOff,
                            null,
                            tint = if (isServerRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Discoverable", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (isServerRunning) "Visible to devices on this network" else "Not visible to other devices",
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = isServerRunning, onCheckedChange = { onToggleServer() })
                    }
                }
            }

            // ── Network interfaces card ───────────────────────────────────────
            if (networkIfaces.isNotEmpty()) {
                item {
                    NetworkCard(networkIfaces, activeMdnsIp, onSwitchMdns)
                }
            }

            // ── Connection status ─────────────────────────────────────────────
            item {
                ConnectionStatusSection(
                    clientCount = clientCount,
                    connectedDevices = connectedDevices,
                    isServerRunning = isServerRunning,
                    onRenameDevice = onRenameDevice,
                    onDisconnectDevice = onDisconnectDevice,
                    onForgetDevice = onForgetDevice,
                    onToggleBlockDevice = onToggleBlockDevice
                )
            }

            // ── Warnings / banners ────────────────────────────────────────────
            if (pendingPairings.isNotEmpty()) {
                item {
                    InfoBanner(
                        icon = Icons.Filled.HourglassTop,
                        text = "${pendingPairings.size} device(s) waiting for pairing approval",
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor   = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
            if (transferPrefs.autoDownload && transferPrefs.downloadLocationUri == null) {
                item {
                    InfoBannerWithAction(
                        icon = Icons.Filled.Warning,
                        text = "No download folder set — saves to Downloads/LocalShare",
                        actionLabel = "Set Folder",
                        onAction = onPickDownloadFolder,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor   = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            // ── Send card (tabs: Message / File) ──────────────────────────────
            item {
                SendCard(
                    text = text,
                    onTextChange = { text = it },
                    sendAsZip = sendAsZip,
                    onToggleSendAsZip = onToggleSendAsZip,
                    clientCount = clientCount,
                    onSendText = { triggerSend("text") },
                    onSendAsFile = { triggerSend("asFile") },
                    onPickFile = onPickFile
                )
            }

            // ── ZIP progress ──────────────────────────────────────────────────
            if (zipProgress != null) {
                item { ZipProgressCard(zipProgress) }
            }

            // ── Outgoing transfers ────────────────────────────────────────────
            if (activeTransfers.isNotEmpty() || outgoingBatch != null) {
                item {
                    TransferCard(
                        transfers = activeTransfers,
                        batch = outgoingBatch,
                        label = "Sending",
                        icon = Icons.Filled.Upload,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // ── Incoming transfers ────────────────────────────────────────────
            if (incomingTransfers.isNotEmpty() || incomingBatchAgg != null) {
                item {
                    TransferCard(
                        transfers = incomingTransfers,
                        batch = incomingBatchAgg,
                        label = "Receiving",
                        icon = Icons.Filled.Download,
                        tint = MaterialTheme.colorScheme.secondary
                    )
                }
            }

            // ── Received items ────────────────────────────────────────────────
            if (received.isNotEmpty() || receivedFiles.isNotEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Filled.Inbox, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Received", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
                    }
                }
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

// ── NetworkCard ───────────────────────────────────────────────────────────────

@Composable
private fun NetworkCard(
    ifaces: List<NetworkIface>,
    activeMdnsIp: String?,
    onSwitchMdns: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    // Keep primary iface (the one set as active mDNS) at the top of the list
    val sortedIfaces = remember(ifaces, activeMdnsIp) {
        val primIdx = ifaces.indexOfFirst { it.ip == activeMdnsIp }
        if (primIdx <= 0) ifaces
        else listOf(ifaces[primIdx]) + ifaces.subList(0, primIdx) + ifaces.subList(primIdx + 1, ifaces.size)
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            // Primary URL header row
            val primaryUrl = if (activeMdnsIp != null) "phone.local:8080" else ifaces.firstOrNull()?.let { "${it.ip}:8080" } ?: "—"
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Filled.Router, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                Text("Primary URL:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(primaryUrl, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f))
                if (sortedIfaces.size > 1) {
                    IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(28.dp)) {
                        Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, modifier = Modifier.size(18.dp))
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            // Interface rows with radio buttons for "Set as Primary"
            val visibleIfaces = if (expanded) sortedIfaces else sortedIfaces.take(1)
            visibleIfaces.forEachIndexed { idx, iface ->
                if (idx > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSwitchMdns(iface.ip) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(wifiIcon(iface.label), null,
                        tint = if (iface.ip == activeMdnsIp) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(iface.label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${iface.ip}:8080", fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                            fontWeight = if (iface.ip == activeMdnsIp) FontWeight.Medium else FontWeight.Normal)
                    }
                    RadioButton(
                        selected = iface.ip == activeMdnsIp,
                        onClick = { onSwitchMdns(iface.ip) }
                    )
                }
            }
        }
    }
}

private fun wifiIcon(label: String): ImageVector = when (label) {
    "Wi-Fi"   -> Icons.Filled.Wifi
    "Hotspot" -> Icons.Filled.WifiTethering
    "USB"     -> Icons.Filled.Usb
    else      -> Icons.Filled.Wifi
}

// ── Connection status section ─────────────────────────────────────────────────

@Composable
private fun ConnectionStatusSection(
    clientCount: Int,
    connectedDevices: List<ConnectedDeviceInfo>,
    isServerRunning: Boolean,
    onRenameDevice: (String, String) -> Unit,
    onDisconnectDevice: (String) -> Unit,
    onForgetDevice: (String) -> Unit,
    onToggleBlockDevice: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(
                when {
                    !isServerRunning -> MaterialTheme.colorScheme.outline
                    clientCount > 0  -> MaterialTheme.colorScheme.primary
                    else             -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f)
                }
            ))
            Text(
                text = when {
                    !isServerRunning -> "Not Discoverable"
                    clientCount == 0 -> "No devices connected"
                    clientCount == 1 -> "1 device connected"
                    else             -> "$clientCount devices connected"
                },
                fontSize = 12.sp,
                color = when {
                    !isServerRunning -> MaterialTheme.colorScheme.onSurfaceVariant
                    clientCount > 0  -> MaterialTheme.colorScheme.primary
                    else             -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
        if (connectedDevices.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(horizontal = 2.dp)
            ) {
                items(connectedDevices, key = { it.deviceId }) { d ->
                    DeviceCircle(
                        device = d,
                        onRename = { alias -> onRenameDevice(d.deviceId, alias) },
                        onDisconnect = { onDisconnectDevice(d.deviceId) },
                        onForget = { onForgetDevice(d.deviceId) },
                        onToggleBlock = { onToggleBlockDevice(d.deviceId) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceCircle(
    device: ConnectedDeviceInfo,
    onRename: (String) -> Unit,
    onDisconnect: () -> Unit,
    onForget: () -> Unit,
    onToggleBlock: () -> Unit
) {
    var showInfo by remember { mutableStateOf(false) }
    if (showInfo) {
        var aliasEdit by remember { mutableStateOf(device.alias) }
        AlertDialog(
            onDismissRequest = { showInfo = false },
            icon = { Icon(Icons.Filled.DevicesOther, null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text(device.alias) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // IP row
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Router, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(device.ip, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            if (device.isBlocked) {
                                Spacer(Modifier.weight(1f))
                                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(4.dp)) {
                                    Text("Blocked", fontSize = 10.sp, color = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                }
                            }
                        }
                    }
                    // Rename field
                    OutlinedTextField(
                        value = aliasEdit,
                        onValueChange = { aliasEdit = it },
                        label = { Text("Rename device", fontSize = 12.sp) },
                        singleLine = true,
                        trailingIcon = {
                            if (aliasEdit.isNotBlank() && aliasEdit != device.alias) {
                                IconButton(onClick = { onRename(aliasEdit.trim()); showInfo = false }) {
                                    Icon(Icons.Filled.Check, "Save", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    // Action buttons
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = { onDisconnect(); showInfo = false },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.WifiOff, null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(3.dp))
                            Text("Disconnect", fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = { onToggleBlock(); showInfo = false },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(if (device.isBlocked) Icons.Filled.LockOpen else Icons.Filled.Block, null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(3.dp))
                            Text(if (device.isBlocked) "Unblock" else "Block", fontSize = 12.sp)
                        }
                    }
                    OutlinedButton(
                        onClick = { onForget(); showInfo = false },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.PersonRemove, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("Forget device", fontSize = 12.sp)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text("Close") } }
        )
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(60.dp).clickable { showInfo = true }
    ) {
        Surface(
            shape = CircleShape,
            color = if (device.isBlocked) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(48.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    device.alias.take(1).uppercase(),
                    fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    color = if (device.isBlocked) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            device.alias,
            fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
        )
    }
}

// ── Info banners ──────────────────────────────────────────────────────────────

@Composable
private fun InfoBanner(
    icon: ImageVector,
    text: String,
    containerColor: Color,
    contentColor: Color
) {
    Surface(shape = RoundedCornerShape(10.dp), color = containerColor, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, tint = contentColor, modifier = Modifier.size(16.dp))
            Text(text, fontSize = 12.sp, color = contentColor, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun InfoBannerWithAction(
    icon: ImageVector,
    text: String,
    actionLabel: String,
    onAction: () -> Unit,
    containerColor: Color,
    contentColor: Color
) {
    Surface(shape = RoundedCornerShape(10.dp), color = containerColor, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(start = 10.dp, top = 6.dp, bottom = 6.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, tint = contentColor, modifier = Modifier.size(16.dp))
            Text(text, fontSize = 12.sp, color = contentColor, modifier = Modifier.weight(1f))
            TextButton(onClick = onAction, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                Text(actionLabel, fontSize = 11.sp, color = contentColor)
            }
        }
    }
}

// ── Send card with swipeable tabs ────────────────────────────────────────────

@Composable
private fun SendCard(
    text: String,
    onTextChange: (String) -> Unit,
    sendAsZip: Boolean,
    onToggleSendAsZip: (Boolean) -> Unit,
    clientCount: Int,
    onSendText: () -> Unit,
    onSendAsFile: () -> Unit,
    onPickFile: () -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { 2 })
    val coroutineScope = rememberCoroutineScope()

    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(), shadowElevation = 1.dp
    ) {
        Column {
            TabRow(
                selectedTabIndex = pagerState.currentPage,
                containerColor = MaterialTheme.colorScheme.surface,
                divider = {}
            ) {
                Tab(
                    selected = pagerState.currentPage == 0,
                    onClick = { coroutineScope.launch { pagerState.animateScrollToPage(0) } },
                    icon = { Icon(Icons.AutoMirrored.Filled.Message, null, modifier = Modifier.size(18.dp)) },
                    text = { Text("Message", fontSize = 12.sp) }
                )
                Tab(
                    selected = pagerState.currentPage == 1,
                    onClick = { coroutineScope.launch { pagerState.animateScrollToPage(1) } },
                    icon = { Icon(Icons.Filled.AttachFile, null, modifier = Modifier.size(18.dp)) },
                    text = { Text("File", fontSize = 12.sp) }
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            HorizontalPager(state = pagerState) { page ->
                when (page) {
                    0    -> MessageTabContent(text, onTextChange, clientCount, onSendText, onSendAsFile)
                    else -> FileTabContent(sendAsZip, onToggleSendAsZip, clientCount, onPickFile)
                }
            }
        }
    }
}

@Composable
private fun MessageTabContent(
    text: String,
    onTextChange: (String) -> Unit,
    clientCount: Int,
    onSendText: () -> Unit,
    onSendAsFile: () -> Unit
) {
    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ScrollableMessageInput(value = text, onValueChange = onTextChange)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onSendText,
                modifier = Modifier.weight(1f),
                enabled = text.isNotBlank() && clientCount > 0
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Send")
            }
            OutlinedButton(
                onClick = onSendAsFile,
                modifier = Modifier.weight(1f),
                enabled = text.isNotBlank() && clientCount > 0
            ) {
                Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("As .txt")
            }
        }
    }
}

@Composable
private fun ScrollableMessageInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val lineCount = remember(value) { value.count { it == '\n' } + 1 }
    val maxVisible = 6
    val isScrollable = lineCount > maxVisible
    val thumbColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    val trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f)

    Box(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().let { if (isScrollable) it.padding(end = 6.dp) else it },
            placeholder = { Text("Type a message…", color = MaterialTheme.colorScheme.onSurfaceVariant) },
            minLines = 3,
            maxLines = maxVisible,
            shape = RoundedCornerShape(10.dp)
        )
        if (isScrollable) {
            Canvas(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(4.dp)
                    .matchParentSize()
                    .padding(vertical = 6.dp)
            ) {
                drawRoundRect(trackColor, cornerRadius = CornerRadius(2.dp.toPx()))
                val thumbRatio = maxVisible.toFloat() / lineCount.coerceAtLeast(1)
                val thumbH = (size.height * thumbRatio).coerceIn(24.dp.toPx(), size.height)
                drawRoundRect(
                    thumbColor,
                    topLeft = Offset(0f, size.height - thumbH),
                    size = Size(size.width, thumbH),
                    cornerRadius = CornerRadius(2.dp.toPx())
                )
            }
        }
    }
}

@Composable
private fun FileTabContent(
    sendAsZip: Boolean,
    onToggleSendAsZip: (Boolean) -> Unit,
    clientCount: Int,
    onPickFile: () -> Unit
) {
    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Filled.FolderZip, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Column {
                    Text("Bundle as ZIP", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text("Compress multiple files", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Switch(checked = sendAsZip, onCheckedChange = onToggleSendAsZip, enabled = clientCount > 0)
        }
        Button(
            onClick = onPickFile,
            modifier = Modifier.fillMaxWidth(),
            enabled = clientCount > 0
        ) {
            Icon(Icons.Filled.AttachFile, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Choose & Send File")
        }
        if (clientCount == 0) {
            Text("Connect a device to send files", fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth())
        }
    }
}

// ── ZIP progress card ─────────────────────────────────────────────────────────

@Composable
private fun ZipProgressCard(progress: Float) {
    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.size(40.dp),
                    strokeWidth = 3.dp,
                    strokeCap = StrokeCap.Round,
                    color = MaterialTheme.colorScheme.tertiary,
                    trackColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f)
                )
                Icon(Icons.Filled.FolderZip, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.tertiary)
            }
            Column {
                Text("Creating ZIP…", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text("${(progress * 100).toInt()}% complete", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
