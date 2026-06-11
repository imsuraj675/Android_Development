package com.example.sender

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import io.ktor.http.*
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.server.application.*
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import io.ktor.server.cio.*
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import com.example.sender.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.Inet4Address
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

// â”€â”€ Shared data models â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

data class NetworkIface(val name: String, val label: String, val ip: String)

data class PairingRequest(
    val deviceId: String,
    val ip: String,
    val name: String,
    val platform: String,
    val requestedAt: Long = System.currentTimeMillis()
)

data class ConnectedDeviceInfo(val deviceId: String, val alias: String, val ip: String, val isBlocked: Boolean = false)

data class ReceivedMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val fromAlias: String = "Device"
)

data class ReceivedFile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val size: Long,
    val tempFile: java.io.File,
    val fromAlias: String = "Device"
) {
    override fun equals(other: Any?) = other is ReceivedFile && id == other.id
    override fun hashCode() = id.hashCode()
}

data class FileToShare(
    val name: String,
    val size: Long,
    val openStream: () -> InputStream?
)

data class IncomingTransfer(
    val transferId: String,
    val deviceAlias: String,
    val files: List<FileEntry>,
    val totalBytes: Long
) {
    data class FileEntry(val name: String, val size: Long)
}

data class TransferProgress(
    val id: String,
    val name: String,
    val bytesSent: Long,
    val totalBytes: Long
) {
    val fraction: Float
        get() = if (totalBytes > 0) (bytesSent.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}

data class TransferBatch(val total: Int, val done: Int) {
    val remaining: Int get() = (total - done).coerceAtLeast(0)
}

// â”€â”€ Network interface enumeration â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

fun getNetworkIfaces(): List<NetworkIface> {
    val result = mutableListOf<NetworkIface>()
    try {
        java.net.NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { ni ->
            if (ni.isLoopback || !ni.isUp) return@forEach
            ni.inetAddresses?.toList()?.forEach { addr ->
                if (addr !is Inet4Address || addr.isLoopbackAddress) return@forEach
                val ip = addr.hostAddress ?: return@forEach
                val label = when {
                    ni.name.startsWith("wlan")  -> "Wi-Fi"
                    ni.name.startsWith("ap")
                        || ni.name.startsWith("swlan") -> "Hotspot"
                    ni.name.startsWith("rndis")
                        || ni.name.startsWith("usb")
                        || ni.name.startsWith("ncm")   -> "USB"
                    else -> ni.name
                }
                result += NetworkIface(ni.name, label, ip)
            }
        }
    } catch (_: Exception) {}
    return result
}

// â”€â”€ HTML page â€” defined in ReceiverPage.kt (HTML_PAGE) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
// HTML_PAGE is defined in ReceiverPage.kt in the same package.

// â”€â”€ KtorServer â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

class KtorServer(private val context: Context) {

    companion object {
        private const val TAG = "KtorServer"
        const val AUTO_ZIP_THRESHOLD = 1000
    }

    private val deviceManager = DeviceManager(context)

    private data class InternalSession(
        val deviceId: String,
        val session: DefaultWebSocketSession,
        val alias: String,
        val ip: String
    )

    private data class SharedFileInfo(
        val name: String,
        val size: Long,
        val openStream: () -> InputStream?
    )

    private val connectedSessions       = ConcurrentHashMap<String, InternalSession>()
    private val pairingDeferreds        = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val pendingAliasOverrides   = ConcurrentHashMap<String, String>()
    private val sharedFiles             = AtomicReference<List<SharedFileInfo>>(emptyList())
    private val _transfers              = ConcurrentHashMap<String, TransferProgress>()
    private val _transferLastUpdate     = ConcurrentHashMap<String, Long>()
    private val cancelledTransfers      = ConcurrentHashMap.newKeySet<String>()
    private val transferPrefsManager    = TransferPrefsManager(context)
    private val pendingTransferDeferreds = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val acceptedTransferLocations = ConcurrentHashMap<String, String?>()
    private val acceptedTransferFiles    = ConcurrentHashMap<String, List<IncomingTransfer.FileEntry>>()
    private val transferFileCounters     = ConcurrentHashMap<String, AtomicInteger>()
    private val _incomingXfers           = ConcurrentHashMap<String, TransferProgress>()
    private val _incomingXferLastUpdate  = ConcurrentHashMap<String, Long>()
    private var pendingZipFile: java.io.File? = null
    private var pendingZipName: String = ""
    private var pendingZipJob: Job? = null
    private val _outgoingBatchTotal = AtomicInteger(0)
    private val _outgoingBatchDone  = AtomicInteger(0)
    private val _incomingBatchTotal = ConcurrentHashMap<String, Int>()
    private val _incomingBatchDone  = ConcurrentHashMap<String, AtomicInteger>()
    private val scope               = CoroutineScope(Dispatchers.IO)

    // â”€â”€ Public StateFlows (observed by UI) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    val networkIfaces    = MutableStateFlow<List<NetworkIface>>(emptyList())
    val activeMdnsIp     = MutableStateFlow<String?>(null)
    val connectedDevices = MutableStateFlow<List<ConnectedDeviceInfo>>(emptyList())
    val pendingPairings  = MutableStateFlow<List<PairingRequest>>(emptyList())
    val trustedDevices   = MutableStateFlow<List<TrustedDevice>>(emptyList())
    val clientCount      = MutableStateFlow(0)
    val receivedMessages = MutableStateFlow<List<ReceivedMessage>>(emptyList())
    val receivedFiles    = MutableStateFlow<List<ReceivedFile>>(emptyList())
    val activeTransfers   = MutableStateFlow<List<TransferProgress>>(emptyList())
    val incomingTransfers = MutableStateFlow<List<TransferProgress>>(emptyList())
    val zipProgress      = MutableStateFlow<Float?>(null)
    val outgoingBatch    = MutableStateFlow<TransferBatch?>(null)
    val incomingBatch    = MutableStateFlow<Map<String, TransferBatch>>(emptyMap())
    val isRunning        = MutableStateFlow(false)
    val pendingTransfers = MutableStateFlow<List<IncomingTransfer>>(emptyList())
    val transferPrefs    = MutableStateFlow(TransferPrefs())

    private var server: ApplicationEngine? = null
    private var jmDns: JmDNS? = null

    // â”€â”€ Start / stop â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    fun start() {
        if (isRunning.value) return
        isRunning.value = true
        val ifaces = getNetworkIfaces()
        networkIfaces.value = ifaces
        trustedDevices.value = deviceManager.getAll()
        transferPrefs.value = transferPrefsManager.load()

        // mDNS on first WiFi interface (user can switch later)
        val mdnsIp = ifaces.firstOrNull { it.label == "Wi-Fi" }?.ip
            ?: ifaces.firstOrNull()?.ip
        if (mdnsIp != null) {
            activeMdnsIp.value = mdnsIp
            scope.launch {
                try {
                    jmDns = JmDNS.create(java.net.InetAddress.getByName(mdnsIp), "phone")
                    jmDns?.registerService(ServiceInfo.create("_http._tcp.local.", "Sender", 8080, ""))
                } catch (_: Exception) {}
            }
        }

        // Ktor binds to 0.0.0.0 by default â†’ accepts connections on all interfaces
        server = embeddedServer(CIO, port = 8080) {
            install(WebSockets)
            routing {
                get("/") {
                    // Redirect phone.local requests to the actual IP so the browser
                    // URL bar switches to the IP and WebSocket connects directly.
                    val host = call.request.headers[HttpHeaders.Host]?.substringBefore(':')
                    val target = activeMdnsIp.value
                    if (host == "phone.local" && target != null) {
                        call.respondRedirect("http://$target:8080/", permanent = false)
                    } else {
                        call.respondText(HTML_PAGE, ContentType.Text.Html)
                    }
                }
                get("/file")           { serveSharedFile(call, 0) }
                get("/file/{index}")   { serveSharedFile(call, call.parameters["index"]?.toIntOrNull() ?: 0) }
                post("/upload")        { receiveUpload(call) }
                webSocket("/socket") { handleSocket() }
            }
        }
        server?.start(wait = false)
    }

    fun stop() {
        if (!isRunning.value) return
        isRunning.value = false
        jmDns?.close()
        server?.stop(0L, 0L)
    }

    fun switchMdnsTo(ip: String) {
        activeMdnsIp.value = ip
        scope.launch {
            try {
                jmDns?.close()
                jmDns = JmDNS.create(java.net.InetAddress.getByName(ip), "phone")
                jmDns?.registerService(ServiceInfo.create("_http._tcp.local.", "Sender", 8080, ""))
            } catch (_: Exception) {}
        }
    }

    // â”€â”€ WebSocket handler â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private suspend fun DefaultWebSocketServerSession.handleSocket() {
        val ip = try { call.request.local.remoteHost } catch (_: Exception) { "unknown" }

        // Expect hello frame first
        val firstFrame = runCatching { incoming.receive() }.getOrElse { return }
        if (firstFrame !is Frame.Text) { close(); return }
        val hello = runCatching { JSONObject(firstFrame.readText()) }.getOrElse { close(); return }
        if (hello.optString("type") != "hello") { close(); return }

        val deviceId = hello.optString("deviceId").takeIf { it.isNotEmpty() } ?: run { close(); return }
        val deviceName = hello.optString("name", "Unknown Device")
        val platform   = hello.optString("platform", "Unknown")

        // Blocked?
        if (deviceManager.isBlocked(deviceId)) {
            send(Frame.Text(json("type" to "rejected")))
            close(CloseReason(CloseReason.Codes.NORMAL, "blocked"))
            return
        }

        // Pairing required if unknown OR trust has expired
        val needsPairing = !deviceManager.isKnown(deviceId) ||
            deviceManager.isTrustExpired(deviceId, BuildConfig.TRUST_DURATION_DAYS)

        if (needsPairing) {
            val deferred = CompletableDeferred<Boolean>()
            pairingDeferreds[deviceId] = deferred
            pendingPairings.value = pendingPairings.value +
                PairingRequest(deviceId, ip, deviceName, platform)
            try {
                send(Frame.Text(json("type" to "pending")))
                val accepted = deferred.await()
                if (!accepted) {
                    send(Frame.Text(json("type" to "rejected")))
                    close(CloseReason(CloseReason.Codes.NORMAL, "rejected"))
                    return
                }
                val aliasOverride = pendingAliasOverrides.remove(deviceId)
                val finalAlias = aliasOverride?.takeIf { it.isNotBlank() } ?: deviceName
                deviceManager.trust(TrustedDevice(
                    id = deviceId, alias = finalAlias, platform = platform,
                    lastKnownIp = ip, lastSeen = System.currentTimeMillis()
                ))
                refreshTrustedDevices()
            } finally {
                pairingDeferreds.remove(deviceId)
                pendingPairings.value = pendingPairings.value.filter { it.deviceId != deviceId }
            }
        }

        // Trusted â€” proceed
        deviceManager.updateLastSeen(deviceId, ip)
        val alias = deviceManager.get(deviceId)?.alias ?: deviceName

        send(Frame.Text(json("type" to "welcome", "alias" to alias)))

        connectedSessions[deviceId] = InternalSession(deviceId, this, alias, ip)
        updateConnected()

        try {
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    val text = frame.readText()
                    val obj = runCatching { JSONObject(text) }.getOrNull()
                    when (obj?.optString("type")) {
                        "transfer_announce" -> obj?.let { o ->
                            runCatching { handleTransferAnnounce(o, alias, this) }
                        }
                        "cancel_upload" -> {
                            val tid = obj?.optString("transferId") ?: ""
                            if (tid.isNotEmpty()) {
                                cancelledTransfers.add(tid)
                                _incomingXfers.remove(tid)
                                refreshIncomingTransfers()
                            }
                        }
                        else -> receivedMessages.value = receivedMessages.value +
                            ReceivedMessage(text = text, fromAlias = alias)
                    }
                }
            }
        } finally {
            connectedSessions.remove(deviceId)
            updateConnected()
            deviceManager.updateLastSeen(deviceId, ip)
            refreshTrustedDevices()
        }
    }

    // â”€â”€ Pairing actions (called from UI) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    fun acceptPairing(deviceId: String, alias: String = "") {
        if (alias.isNotBlank()) pendingAliasOverrides[deviceId] = alias.trim()
        pairingDeferreds[deviceId]?.complete(true)
    }

    fun rejectPairing(deviceId: String) { pairingDeferreds[deviceId]?.complete(false) }

    // â”€â”€ Transfer announcement handler â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private suspend fun handleTransferAnnounce(
        msg: JSONObject,
        alias: String,
        session: DefaultWebSocketSession
    ) {
        val transferId = msg.optString("transferId").takeIf { it.isNotEmpty() } ?: return
        val totalBytes = msg.optLong("totalBytes", 0L)
        val filesArr   = msg.optJSONArray("files") ?: JSONArray()
        val files = (0 until filesArr.length()).map { i ->
            val o = filesArr.getJSONObject(i)
            IncomingTransfer.FileEntry(o.optString("name", "file"), o.optLong("size", 0L))
        }

        val prefs      = transferPrefsManager.load()
        val autoAccept = prefs.autoDownload && totalBytes <= prefs.approvalThresholdBytes

        if (autoAccept) {
            acceptedTransferLocations[transferId] = prefs.downloadLocationUri
            acceptedTransferFiles[transferId] = files
            _incomingBatchTotal[transferId] = files.size
            _incomingBatchDone[transferId] = AtomicInteger(0)
            refreshIncomingBatch()
            session.send(Frame.Text(json("type" to "transfer_accept", "transferId" to transferId)))
        } else {
            val announcement = IncomingTransfer(transferId, alias, files, totalBytes)
            val deferred     = CompletableDeferred<Boolean>()
            pendingTransferDeferreds[transferId] = deferred
            pendingTransfers.value = pendingTransfers.value + announcement
            try {
                val accepted = deferred.await()
                if (accepted) {
                    acceptedTransferLocations[transferId] = transferPrefsManager.load().downloadLocationUri
                    acceptedTransferFiles[transferId] = files
                    _incomingBatchTotal[transferId] = files.size
                    _incomingBatchDone[transferId] = AtomicInteger(0)
                    refreshIncomingBatch()
                    session.send(Frame.Text(json("type" to "transfer_accept", "transferId" to transferId)))
                } else {
                    session.send(Frame.Text(json("type" to "transfer_reject", "transferId" to transferId)))
                }
            } finally {
                pendingTransferDeferreds.remove(transferId)
                pendingTransfers.value = pendingTransfers.value.filter { it.transferId != transferId }
            }
        }
    }

    fun acceptTransfer(transferId: String) { pendingTransferDeferreds[transferId]?.complete(true) }
    fun rejectTransfer(transferId: String) { pendingTransferDeferreds[transferId]?.complete(false) }

    fun updateTransferPrefs(prefs: TransferPrefs) {
        transferPrefsManager.save(prefs)
        transferPrefs.value = prefs
    }

    fun updateDownloadLocation(uriString: String) {
        val updated = transferPrefsManager.load().copy(downloadLocationUri = uriString)
        transferPrefsManager.save(updated)
        transferPrefs.value = updated
    }

    // â”€â”€ Device management â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    fun renameDevice(id: String, alias: String) {
        deviceManager.rename(id, alias)
        refreshTrustedDevices()
    }

    fun forgetDevice(id: String) {
        kickDevice(id, "forgotten")
        deviceManager.forget(id)
        refreshTrustedDevices()
    }

    fun disconnectDevice(id: String) = kickDevice(id, "disconnected")

    fun blockDevice(id: String) {
        kickDevice(id, "blocked")
        deviceManager.block(id)
        refreshTrustedDevices()
    }

    fun toggleBlockDevice(id: String) {
        if (deviceManager.isBlocked(id)) {
            deviceManager.unblock(id)
        } else {
            kickDevice(id, "blocked")
            deviceManager.block(id)
        }
        refreshTrustedDevices()
    }

    private fun kickDevice(id: String, reason: String) {
        scope.launch {
            connectedSessions[id]?.session?.let { s ->
                runCatching {
                    s.send(Frame.Text(json("type" to "kicked")))
                    s.close(CloseReason(CloseReason.Codes.NORMAL, reason))
                }
            }
        }
    }

    // â”€â”€ Send / broadcast â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    fun broadcast(message: String) =
        broadcastJson(json("type" to "text", "data" to message, "from" to "Phone"))

    fun sendToDevice(deviceId: String, message: String) = scope.launch {
        connectedSessions[deviceId]?.session?.runCatching {
            send(Frame.Text(json("type" to "text", "data" to message, "from" to "Phone")))
        }
    }

    fun sendToDevices(deviceIds: Set<String>, message: String) {
        val j = json("type" to "text", "data" to message, "from" to "Phone")
        scope.launch {
            deviceIds.forEach { id ->
                connectedSessions[id]?.session?.runCatching { send(Frame.Text(j)) }
            }
        }
    }

    fun shareFile(name: String, size: Long, openStream: () -> InputStream?, targetDeviceIds: Set<String>? = null) {
        android.util.Log.d(TAG, "shareFile: $name ($size bytes) â†’ ${targetDeviceIds ?: "all"}")
        shareFiles(listOf(FileToShare(name, size, openStream)), targetDeviceIds)
    }

    fun shareFiles(files: List<FileToShare>, targetDeviceIds: Set<String>? = null) {
        if (files.size > AUTO_ZIP_THRESHOLD) {
            android.util.Log.i(TAG, "shareFiles: ${files.size} files > $AUTO_ZIP_THRESHOLD â†’ auto-zipping")
            val zipName = "files_${System.currentTimeMillis()}.zip"
            createAndShareZip(files, zipName, targetDeviceIds)
            return
        }
        android.util.Log.d(TAG, "shareFiles: sharing ${files.size} individual file(s)")
        val infos = files.map { SharedFileInfo(it.name, it.size, it.openStream) }
        sharedFiles.set(infos)
        _outgoingBatchTotal.set(infos.size)
        _outgoingBatchDone.set(0)
        outgoingBatch.value = TransferBatch(infos.size, 0)
        val batchMsg = json("type" to "file_batch", "count" to infos.size.toString())
        if (targetDeviceIds != null) {
            scope.launch {
                targetDeviceIds.forEach { id ->
                    connectedSessions[id]?.session?.runCatching { send(Frame.Text(batchMsg)) }
                }
            }
        } else {
            broadcastJson(batchMsg)
        }
        infos.forEachIndexed { index, info ->
            val j = json("type" to "file", "name" to info.name, "index" to index.toString())
            if (targetDeviceIds != null) {
                scope.launch {
                    targetDeviceIds.forEach { id ->
                        connectedSessions[id]?.session?.runCatching { send(Frame.Text(j)) }
                    }
                }
            } else {
                broadcastJson(j)
            }
        }
    }

    fun createAndShareZip(files: List<FileToShare>, zipName: String, targetDeviceIds: Set<String>? = null) {
        scope.launch {
            android.util.Log.i(TAG, "createAndShareZip: zipping ${files.size} files â†’ $zipName")
            zipProgress.value = 0f
            val tmp = java.io.File(context.cacheDir, "zip_${UUID.randomUUID()}.zip")
            try {
                val totalSize = files.sumOf { maxOf(it.size, 0L) }
                var processed = 0L
                java.util.zip.ZipOutputStream(tmp.outputStream().buffered(65_536)).use { zos ->
                    for (file in files) {
                        zos.putNextEntry(java.util.zip.ZipEntry(file.name))
                        file.openStream()?.use { input ->
                            val buf = ByteArray(65_536)
                            var n: Int
                            while (input.read(buf).also { n = it } != -1) {
                                zos.write(buf, 0, n)
                                processed += n
                                if (totalSize > 0) zipProgress.value = processed.toFloat() / totalSize
                            }
                        }
                        zos.closeEntry()
                    }
                }
                android.util.Log.i(TAG, "createAndShareZip: done, zip size=${tmp.length()} bytes")
                shareFile(zipName, tmp.length(), { tmp.inputStream() }, targetDeviceIds)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "createAndShareZip: failed", e)
                tmp.delete()
            } finally {
                zipProgress.value = null
            }
        }
    }

    fun startBackgroundZip(files: List<FileToShare>, zipName: String) {
        cancelPendingZip()
        pendingZipName = zipName
        pendingZipJob = scope.launch {
            zipProgress.value = 0f
            val tmp = java.io.File(context.cacheDir, "zip_${UUID.randomUUID()}.zip")
            var success = false
            try {
                val totalSize = files.sumOf { maxOf(it.size, 0L) }
                var processed = 0L
                java.util.zip.ZipOutputStream(tmp.outputStream().buffered(65_536)).use { zos ->
                    for (file in files) {
                        zos.putNextEntry(java.util.zip.ZipEntry(file.name))
                        file.openStream()?.use { input ->
                            val buf = ByteArray(65_536)
                            var n: Int
                            while (input.read(buf).also { n = it } != -1) {
                                zos.write(buf, 0, n)
                                processed += n
                                if (totalSize > 0) zipProgress.value = processed.toFloat() / totalSize
                            }
                        }
                        zos.closeEntry()
                    }
                }
                pendingZipFile = tmp
                zipProgress.value = 1f
                success = true
            } finally {
                if (!success) { tmp.delete(); zipProgress.value = null }
            }
        }
    }

    fun sendPendingZip(targetDeviceIds: Set<String>?) {
        scope.launch {
            pendingZipJob?.join()
            val file = pendingZipFile ?: return@launch
            val name = pendingZipName
            pendingZipFile = null
            pendingZipName = ""
            zipProgress.value = null
            shareFile(name, file.length(), { file.inputStream() }, targetDeviceIds)
        }
    }

    fun cancelPendingZip() {
        pendingZipJob?.cancel()
        pendingZipJob = null
        pendingZipFile?.delete()
        pendingZipFile = null
        pendingZipName = ""
        zipProgress.value = null
    }

    fun sendTextAsFile(text: String, targetDeviceIds: Set<String>? = null) {
        val name = "message_${System.currentTimeMillis()}.txt"
        val bytes = text.toByteArray(Charsets.UTF_8)
        val tmp = java.io.File(context.cacheDir, "share_${UUID.randomUUID()}")
        tmp.writeBytes(bytes)
        shareFile(name, bytes.size.toLong(), { tmp.inputStream() }, targetDeviceIds)
    }

    fun discardFile(id: String) {
        receivedFiles.update { list ->
            list.find { it.id == id }?.tempFile?.delete()
            list.filter { it.id != id }
        }
    }

    fun clearLogs() {
        receivedFiles.value.forEach { it.tempFile.delete() }
        receivedFiles.value    = emptyList()
        receivedMessages.value = emptyList()
        // Flush any transfer entries that got stuck because cleanup was interrupted
        _transfers.clear()
        _transferLastUpdate.clear()
        _incomingXfers.clear()
        _incomingXferLastUpdate.clear()
        refreshTransfers()
        refreshIncomingTransfers()
    }

    fun cancelTransfer(transferId: String) {
        cancelledTransfers.add(transferId)
        _transfers.remove(transferId)
        _incomingXfers.remove(transferId)
        refreshTransfers()
        refreshIncomingTransfers()
        // Tell the browser to abort its active upload if this is an incoming transfer
        broadcastJson(JSONObject().apply {
            put("type", "transfer_cancelled")
            put("transferId", transferId)
        }.toString())
    }

    fun refreshNetworkIfaces() {
        val newIfaces = getNetworkIfaces()
        networkIfaces.value = newIfaces
        // If the current mDNS IP disappeared, migrate to the first available WiFi IP
        val currentMdns = activeMdnsIp.value
        if (currentMdns != null && newIfaces.none { it.ip == currentMdns }) {
            val fallback = newIfaces.firstOrNull { it.label == "Wi-Fi" }?.ip
                ?: newIfaces.firstOrNull()?.ip
            if (fallback != null) switchMdnsTo(fallback)
        }
    }

    //â”€â”€ HTTP handlers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private suspend fun serveSharedFile(call: ApplicationCall, index: Int) {
        val info = sharedFiles.get().getOrNull(index)
            ?: run {
                android.util.Log.w(TAG, "serveSharedFile: index $index not found (list size=${sharedFiles.get().size})")
                return call.respond(HttpStatusCode.NotFound)
            }
        android.util.Log.d(TAG, "serveSharedFile: serving index=$index name=${info.name} size=${info.size}")
        val tid = UUID.randomUUID().toString()
        call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"${info.name}\"")
        try {
            var cancelled = false
            call.respondOutputStream(
                contentType = ContentType.Application.OctetStream,
                contentLength = info.size.takeIf { it >= 0L }
            ) {
                val buf = ByteArray(65_536)
                var sent = 0L
                _transfers[tid] = TransferProgress(tid, info.name, 0L, info.size)
                refreshTransfers()
                info.openStream()?.use { input ->
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        if (cancelledTransfers.contains(tid)) { cancelled = true; break }
                        write(buf, 0, n)
                        flush()
                        sent += n
                        _transfers[tid] = TransferProgress(tid, info.name, sent, info.size)
                        refreshTransfers()
                    }
                }
            }
            if (!cancelled) {
                val done = _outgoingBatchDone.incrementAndGet()
                val total = _outgoingBatchTotal.get()
                outgoingBatch.value = TransferBatch(total, done)
                android.util.Log.d(TAG, "serveSharedFile: completed index=$index name=${info.name} batch=$done/$total")
                if (done >= total) {
                    scope.launch { delay(2000); if (_outgoingBatchDone.get() >= _outgoingBatchTotal.get()) outgoingBatch.value = null }
                }
            }
        } finally {
            // Use NonCancellable so this cleanup always runs even when the connection drops
            // and Ktor cancels the handler coroutine mid-flight.
            withContext(NonCancellable) {
                cancelledTransfers.remove(tid)
                delay(800)
                _transfers.remove(tid)
                _transferLastUpdate.remove(tid)
                refreshTransfers()
            }
        }
    }

    private suspend fun receiveUpload(call: ApplicationCall) {
        val transferId = call.request.queryParameters["transferId"]
        // Raw binary upload (Content-Type: application/octet-stream) from the browser — gives
        // real XHR progress events. Multipart fallback kept for forward-compatibility.
        if (call.request.contentType().match(ContentType.Application.OctetStream)) {
            receiveRawUpload(call, transferId)
        } else {
            receiveMultipartUpload(call, transferId)
        }
    }

    private suspend fun receiveRawUpload(call: ApplicationCall, transferId: String?) {
        val name = call.request.queryParameters["name"] ?: "upload_${System.currentTimeMillis()}"
        val contentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: -1L
        val isAutoSave = transferId != null && acceptedTransferLocations.containsKey(transferId)
        val locationUri: String? = if (isAutoSave) acceptedTransferLocations[transferId] else null
        val fileIdx = if (transferId != null)
            transferFileCounters.getOrPut(transferId) { AtomicInteger(0) }.getAndIncrement()
        else 0

        val expectedSize = if (contentLength > 0L) contentLength
            else if (transferId != null) acceptedTransferFiles[transferId]?.getOrNull(fileIdx)?.size ?: -1L
            else -1L

        val tid = transferId ?: UUID.randomUUID().toString()
        _incomingXfers[tid] = TransferProgress(tid, name, 0L, expectedSize)
        refreshIncomingTransfers()

        var rcvBytes = 0L
        val onProgress: (Long) -> Unit = { rcv ->
            rcvBytes = rcv
            val now = System.currentTimeMillis()
            if (now - (_incomingXferLastUpdate[tid] ?: 0L) >= 16L) {
                _incomingXferLastUpdate[tid] = now
                _incomingXfers[tid] = TransferProgress(tid, name, rcv, expectedSize)
                refreshIncomingTransfers()
            }
        }

        var wasCancelled = false
        try {
            val channel = call.receiveChannel()
            if (isAutoSave) {
                autoSaveChannel(name, locationUri, channel, onProgress) { cancelledTransfers.contains(tid) }
            } else {
                saveToCacheChannel(name, channel, onProgress) { cancelledTransfers.contains(tid) }
            }
            wasCancelled = cancelledTransfers.remove(tid)

            if (!wasCancelled) {
                val finalSize = if (expectedSize > 0L) expectedSize else rcvBytes
                _incomingXfers[tid] = TransferProgress(tid, name, finalSize, finalSize)
                refreshIncomingTransfers()
                finishBatchFile(transferId)
            }
            call.respond(if (wasCancelled) HttpStatusCode.Gone else HttpStatusCode.OK)
        } finally {
            // NonCancellable ensures cleanup runs even when the connection drops and
            // Ktor cancels the handler coroutine — without it delay() throws and the
            // map entry is never removed, leaving a stuck progress card in the UI.
            withContext(NonCancellable) {
                cancelledTransfers.remove(tid)
                delay(if (wasCancelled) 0L else 500L)
                _incomingXfers.remove(tid)
                _incomingXferLastUpdate.remove(tid)
                refreshIncomingTransfers()
            }
        }
    }

    private suspend fun receiveMultipartUpload(call: ApplicationCall, transferId: String?) {
        val isAutoSave = transferId != null && acceptedTransferLocations.containsKey(transferId)
        val locationUri: String? = if (isAutoSave) acceptedTransferLocations[transferId] else null
        val fileIdx = if (transferId != null)
            transferFileCounters.getOrPut(transferId) { AtomicInteger(0) }.getAndIncrement()
        else 0

        call.receiveMultipart().forEachPart { part ->
            if (part is PartData.FileItem) {
                val name = part.originalFileName ?: "upload_${System.currentTimeMillis()}"
                val expectedSize = if (transferId != null)
                    acceptedTransferFiles[transferId]?.getOrNull(fileIdx)?.size ?: -1L
                else -1L
                val tid = transferId ?: UUID.randomUUID().toString()
                _incomingXfers[tid] = TransferProgress(tid, name, 0L, expectedSize)
                refreshIncomingTransfers()
                var rcvBytes = 0L
                val onProgress: (Long) -> Unit = { rcv ->
                    rcvBytes = rcv
                    val now = System.currentTimeMillis()
                    if (now - (_incomingXferLastUpdate[tid] ?: 0L) >= 16L) {
                        _incomingXferLastUpdate[tid] = now
                        _incomingXfers[tid] = TransferProgress(tid, name, rcv, expectedSize)
                        refreshIncomingTransfers()
                    }
                }
                try {
                    if (isAutoSave) autoSaveStream(name, locationUri, part.streamProvider(), onProgress)
                    else saveToCacheStream(name, part.streamProvider(), onProgress)
                    val finalSize = if (expectedSize > 0L) expectedSize else rcvBytes
                    _incomingXfers[tid] = TransferProgress(tid, name, finalSize, finalSize)
                    refreshIncomingTransfers()
                    finishBatchFile(transferId)
                } finally {
                    withContext(NonCancellable) {
                        delay(500)
                        _incomingXfers.remove(tid)
                        _incomingXferLastUpdate.remove(tid)
                        refreshIncomingTransfers()
                    }
                }
            }
            part.dispose()
        }
        call.respond(HttpStatusCode.OK)
    }

    private fun finishBatchFile(transferId: String?) {
        if (transferId == null) return
        val done  = _incomingBatchDone.getOrPut(transferId) { AtomicInteger(0) }.incrementAndGet()
        val total = _incomingBatchTotal[transferId] ?: done
        refreshIncomingBatch()
        if (done >= total) {
            scope.launch {
                delay(2000)
                _incomingBatchTotal.remove(transferId)
                _incomingBatchDone.remove(transferId)
                refreshIncomingBatch()
            }
        }
    }

    // Write a raw ByteReadChannel to the appropriate output (SAF folder or cache).
    private suspend fun autoSaveChannel(
        name: String, safUriStr: String?,
        channel: ByteReadChannel, onProgress: (Long) -> Unit = {},
        isCancelled: () -> Boolean = { false }
    ) {
        try {
            val out = openOutputStream(name, safUriStr)
            withContext(Dispatchers.IO) {
                out?.use { streamChannel(channel, it, onProgress, isCancelled) }
            }
        } catch (_: Exception) {
            saveToCacheChannel(name, channel, onProgress, isCancelled)
        }
    }

    private suspend fun saveToCacheChannel(
        name: String, channel: ByteReadChannel, onProgress: (Long) -> Unit = {},
        isCancelled: () -> Boolean = { false }
    ) {
        val tmp = java.io.File(context.cacheDir, "recv_${UUID.randomUUID()}")
        try {
            val completed = withContext(Dispatchers.IO) {
                tmp.outputStream().use { out -> streamChannel(channel, out, onProgress, isCancelled) }
            }
            if (completed) {
                receivedFiles.update { it + ReceivedFile(name = name, size = tmp.length(), tempFile = tmp) }
            } else {
                tmp.delete()
            }
        } catch (_: Exception) { tmp.delete() }
    }

    private suspend fun streamChannel(
        channel: ByteReadChannel, out: java.io.OutputStream,
        onProgress: (Long) -> Unit, isCancelled: () -> Boolean = { false }
    ): Boolean {
        val buf = ByteArray(65_536); var rcv = 0L
        while (!channel.isClosedForRead) {
            if (isCancelled()) return false
            val n = channel.readAvailable(buf, 0, buf.size)
            if (n > 0) { out.write(buf, 0, n); rcv += n; onProgress(rcv) }
        }
        return !isCancelled()
    }

    // Multipart fallback helpers (keep for backward compat)
    private suspend fun autoSaveStream(
        name: String, safUriStr: String?,
        inp: java.io.InputStream, onProgress: (Long) -> Unit = {}
    ) {
        try {
            val out = openOutputStream(name, safUriStr)
            withContext(Dispatchers.IO) {
                out?.use { o -> inp.use { streamInputStream(it, o, onProgress) } }
            }
        } catch (_: Exception) {
            saveToCacheStream(name, inp, onProgress)
        }
    }

    private suspend fun saveToCacheStream(
        name: String, inp: java.io.InputStream, onProgress: (Long) -> Unit = {}
    ) {
        val tmp = java.io.File(context.cacheDir, "recv_${UUID.randomUUID()}")
        try {
            withContext(Dispatchers.IO) {
                tmp.outputStream().use { out -> inp.use { streamInputStream(it, out, onProgress) } }
            }
            receivedFiles.update { it + ReceivedFile(name = name, size = tmp.length(), tempFile = tmp) }
        } catch (_: Exception) { tmp.delete() }
    }

    private fun streamInputStream(
        inp: java.io.InputStream, out: java.io.OutputStream, onProgress: (Long) -> Unit
    ) {
        val buf = ByteArray(65_536); var n: Int; var rcv = 0L
        while (inp.read(buf).also { n = it } != -1) { out.write(buf, 0, n); rcv += n; onProgress(rcv) }
    }

    private fun openOutputStream(name: String, safUriStr: String?): java.io.OutputStream? {
        return if (safUriStr != null) {
            val treeUri = Uri.parse(safUriStr)
            val docUri  = DocumentsContract.buildDocumentUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri)
            )
            val fileUri = DocumentsContract.createDocument(
                context.contentResolver, docUri, "application/octet-stream", name
            ) ?: throw Exception("createDocument failed")
            context.contentResolver.openOutputStream(fileUri)
        } else {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, "Downloads/LocalShare/")
            }
            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            ) ?: throw Exception("MediaStore insert failed")
            context.contentResolver.openOutputStream(uri)
        }
    }

    private fun broadcastJson(j: String) {
        scope.launch {
            connectedSessions.values.toList().forEach { cs ->
                runCatching { cs.session.send(Frame.Text(j)) }.onFailure {
                    connectedSessions.remove(cs.deviceId)
                    updateConnected()
                }
            }
        }
    }

    // â”€â”€ Helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private fun refreshTransfers() {
        activeTransfers.value = _transfers.values.toList()
    }

    private fun refreshIncomingTransfers() {
        incomingTransfers.value = _incomingXfers.values.toList()
    }

    private fun refreshIncomingBatch() {
        incomingBatch.value = _incomingBatchTotal.mapValues { (tid, total) ->
            TransferBatch(total, _incomingBatchDone[tid]?.get() ?: 0)
        }
    }

    private fun updateConnected() {
        val list = connectedSessions.values.map { ConnectedDeviceInfo(it.deviceId, it.alias, it.ip, deviceManager.isBlocked(it.deviceId)) }
        connectedDevices.value = list
        clientCount.value = list.size
    }

    private fun refreshTrustedDevices() {
        trustedDevices.value = deviceManager.getAll()
    }

    private fun json(vararg pairs: Pair<String, String>): String {
        val o = JSONObject()
        pairs.forEach { (k, v) -> o.put(k, v) }
        return o.toString()
    }
}
