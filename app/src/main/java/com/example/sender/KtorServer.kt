package com.example.sender

import android.content.Context
import io.ktor.http.*
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.server.application.*
import io.ktor.server.netty.*
import io.ktor.server.engine.*
import com.example.sender.R
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import com.example.sender.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.InputStream
import java.net.Inet4Address
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

// ── Shared data models ────────────────────────────────────────────────────────

data class NetworkIface(val name: String, val label: String, val ip: String)

data class PairingRequest(
    val deviceId: String,
    val ip: String,
    val name: String,
    val platform: String,
    val requestedAt: Long = System.currentTimeMillis()
)

data class ConnectedDeviceInfo(val deviceId: String, val alias: String, val ip: String)

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

data class TransferProgress(
    val id: String,
    val name: String,
    val bytesSent: Long,
    val totalBytes: Long
) {
    val fraction: Float
        get() = if (totalBytes > 0) (bytesSent.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}

// ── Network interface enumeration ─────────────────────────────────────────────

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

// ── HTML page ─────────────────────────────────────────────────────────────────

private val HTML_PAGE = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Sender — Receiver</title>
<style>
  *, *::before, *::after { box-sizing: border-box; }
  body { font-family: sans-serif; max-width: 640px; margin: 0 auto; padding: 20px;
         background: #0f0f0f; color: #e0e0e0; }
  h1   { font-size: 1.2rem; margin: 0 0 6px; }
  #status { font-size: 0.8rem; color: #888; margin-bottom: 12px; }
  #status.connected { color: #4caf50; }

  /* ── pairing overlay ── */
  #pairing-overlay {
    position: fixed; inset: 0; display: flex;
    align-items: center; justify-content: center;
    background: rgba(0,0,0,0.88); z-index: 100;
  }
  #pairing-overlay.hidden { display: none; }
  #pair-box {
    background: #1e1e1e; border: 1px solid #444; border-radius: 10px;
    padding: 28px 36px; text-align: center; max-width: 340px;
  }
  #pair-msg     { font-size: 1rem; margin: 0 0 8px; }
  #pair-sub     { font-size: 0.82rem; color: #888; margin: 0; }
  #pair-retry   { margin-top: 16px; padding: 6px 18px; background: #2a2a2a;
                  color: #ccc; border: 1px solid #555; border-radius: 4px;
                  cursor: pointer; font-size: 0.85rem; }
  #pair-retry:hover { background: #3a3a3a; }

  /* ── toolbar ── */
  .toolbar { display: flex; gap: 8px; margin-bottom: 14px; }
  .toolbar button { padding: 4px 12px; font-size: 0.8rem; background: #2a2a2a;
                    color: #ccc; border: 1px solid #444; border-radius: 4px; cursor: pointer; }
  .toolbar button:hover { background: #3a3a3a; }

  /* ── message list ── */
  #messages { list-style: none; padding: 0; margin: 0 0 16px; }
  #messages li { display: flex; flex-direction: column; gap: 0;
                 background: #1e1e1e; border-left: 3px solid #4caf50;
                 border-radius: 6px; padding: 10px 12px; margin-bottom: 10px; }
  #messages li.file-item { background: #1a2030; border-left-color: #42a5f5; }

  .msg-meta { font-size: 0.72rem; color: #666; margin-bottom: 4px; }
  .msg-body { margin-bottom: 6px; }
  .msg-text { word-break: break-word; white-space: pre-wrap; line-height: 1.55; font-size: 0.9rem; }
  .msg-text.collapsed { max-height: 6.2em; overflow: hidden; position: relative; }
  .msg-text.collapsed::after {
    content: ''; position: absolute; bottom: 0; left: 0; right: 0; height: 2em;
    background: linear-gradient(transparent, #1e1e1e); pointer-events: none;
  }
  .file-item .msg-text.collapsed::after { background: linear-gradient(transparent, #1a2030); }
  .read-more-btn { display: inline-block; background: none; border: none;
                   color: #4caf50; cursor: pointer; font-size: 0.78rem; padding: 3px 0 0; }
  .read-more-btn:hover { color: #66bb6a; }
  .msg-divider { border: none; border-top: 1px solid #2a2a2a; margin: 7px 0; }
  .msg-actions { display: flex; gap: 6px; flex-wrap: wrap; align-items: center; }
  .action-btn { padding: 3px 10px; font-size: 0.75rem; background: #2a2a2a;
                color: #aaa; border: 1px solid #444; border-radius: 3px;
                cursor: pointer; white-space: nowrap; }
  .action-btn:hover { background: #3a3a3a; }
  .action-btn.copied  { color: #4caf50; border-color: #4caf50; }
  .action-btn.dl      { color: #64b5f6; border-color: #64b5f6; }
  .action-btn.discard { color: #ef9a9a; border-color: #555; }
  .action-btn.discard:hover { background: #2a1a1a; border-color: #ef9a9a; }

  /* ── reply area ── */
  #reply-area { border-top: 1px solid #2a2a2a; padding-top: 14px; margin-bottom: 8px; }
  #reply-input { width: 100%; background: #1e1e1e; color: #e0e0e0;
                 border: 1px solid #444; border-radius: 6px;
                 padding: 10px 12px; font-size: 0.9rem; font-family: sans-serif;
                 resize: none; overflow-y: hidden; min-height: 58px; max-height: 280px;
                 transition: border-color .15s; display: block; }
  #reply-input:focus { outline: none; border-color: #4caf50; }
  .reply-btns { display: flex; gap: 8px; margin-top: 8px; flex-wrap: wrap; }
  #send-btn { padding: 8px 20px; background: #4caf50; color: #000;
              border: none; border-radius: 4px; cursor: pointer; font-weight: 600; }
  #send-btn:hover { background: #66bb6a; }
  #send-btn:disabled, #send-file-btn:disabled { background: #2a2a2a; color: #555; cursor: default; }
  #send-file-btn { padding: 8px 16px; background: #2a2a2a; color: #ccc;
                   border: 1px solid #444; border-radius: 4px; cursor: pointer; font-size: .85rem; }
  #send-file-btn:hover { background: #3a3a3a; }

  /* ── upload area ── */
  #upload-area { border-top: 1px solid #2a2a2a; padding-top: 14px;
                 display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin-bottom: 4px; }
  #upload-area label { font-size: .85rem; color: #aaa; white-space: nowrap; }
  #file-input { font-size: .8rem; color: #ccc; flex: 1; min-width: 0; }
  #upload-btn { padding: 6px 14px; font-size: .85rem; background: #2a2a2a; color: #ccc;
                border: 1px solid #444; border-radius: 4px; cursor: pointer; white-space: nowrap; }
  #upload-btn:hover { background: #3a3a3a; }
  #upload-status { font-size: .8rem; color: #4caf50; width: 100%; }
</style>
</head>
<body>

<!-- pairing overlay — visible until welcome -->
<div id="pairing-overlay">
  <div id="pair-box">
    <p id="pair-msg">Connecting…</p>
    <p id="pair-sub"></p>
    <button id="pair-retry" hidden onclick="location.reload()">Retry</button>
  </div>
</div>

<!-- main UI — hidden until welcome -->
<div id="main-ui" hidden>
  <h1>Receiver</h1>
  <div id="status">Connected</div>
  <div class="toolbar">
    <button onclick="requestNotify()">Enable Notifications</button>
  </div>
  <ul id="messages"></ul>
  <div id="reply-area">
    <textarea id="reply-input" placeholder="Reply to phone… (Ctrl+Enter to send)"></textarea>
    <div class="reply-btns">
      <button id="send-btn" onclick="sendReply()">Send</button>
      <button id="send-file-btn" onclick="sendTextAsFile()">Send as .txt</button>
    </div>
  </div>
  <div id="upload-area">
    <label>Send file to phone:</label>
    <input type="file" id="file-input" multiple>
    <button id="upload-btn" onclick="uploadFiles()">Upload</button>
    <span id="upload-status"></span>
  </div>
</div>

<script>
  const COLLAPSE_THRESHOLD = 250;

  /* ── device identity (persisted in localStorage) ── */
  const DEVICE_ID = (() => {
    const k = 'sender_device_id';
    let id = localStorage.getItem(k);
    if (!id) {
      id = (typeof crypto !== 'undefined' && crypto.randomUUID)
        ? crypto.randomUUID()
        : 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
            const r = Math.random() * 16 | 0;
            return (c === 'x' ? r : (r & 0x3 | 0x8)).toString(16);
          });
      localStorage.setItem(k, id);
    }
    return id;
  })();

  const ua = navigator.userAgent;
  const BROWSER = [['Edg/', 'Edge'], ['OPR/', 'Opera'], ['Chrome/', 'Chrome'],
                   ['Firefox/', 'Firefox'], ['Safari/', 'Safari']]
    .find(([k]) => ua.includes(k))?.[1] ?? 'Browser';
  const PLATFORM = navigator.platform || 'Unknown';
  const DEVICE_NAME = localStorage.getItem('sender_device_name')
    || (BROWSER + ' on ' + PLATFORM);

  /* ── state machine ── */
  let appState = 'connecting';
  let pairingTimer = null;

  function setAppState(s) {
    appState = s;
    const overlay = document.getElementById('pairing-overlay');
    const mainUI  = document.getElementById('main-ui');
    clearTimeout(pairingTimer);

    if (s === 'connected') {
      overlay.classList.add('hidden');
      mainUI.hidden = false;
      return;
    }

    overlay.classList.remove('hidden');
    mainUI.hidden = true;
    const msgs = {
      connecting: ['Connecting…', ''],
      pending:    ['⏳ Waiting for approval', 'Open the Sender app and tap Accept'],
      rejected:   ['⛔ Connection rejected',  'Reload the page to try again.'],
      timeout:    ['⏳ No response from phone', 'The phone may be busy. Reload to retry.'],
      kicked:     ['Disconnected by phone',   'Reload the page to reconnect.']
    };
    const [msg, sub] = msgs[s] || ['…', ''];
    document.getElementById('pair-msg').textContent = msg;
    document.getElementById('pair-sub').textContent = sub;
    document.getElementById('pair-retry').hidden = (s === 'connecting' || s === 'pending');

    if (s === 'pending') {
      pairingTimer = setTimeout(() => setAppState('timeout'), 60000);
    }
  }

  /* ── WebSocket ── */
  const statusEl = document.getElementById('status');
  const wsProto = location.protocol === 'https:' ? 'wss:' : 'ws:';
  const ws = new WebSocket(wsProto + '//' + location.host + '/socket');

  ws.onopen = () => {
    ws.send(JSON.stringify({
      type: 'hello', deviceId: DEVICE_ID,
      name: DEVICE_NAME, platform: BROWSER + '/' + PLATFORM
    }));
  };

  ws.onclose = () => {
    if (appState === 'connected') {
      statusEl.textContent = 'Disconnected — reload to reconnect';
      statusEl.className = '';
    } else if (appState === 'pending') {
      setAppState('timeout');
    }
  };

  ws.onmessage = (e) => {
    let msg;
    try { msg = JSON.parse(e.data); } catch { return; }

    if (msg.type === 'welcome') {
      setAppState('connected');
      statusEl.textContent = 'Connected ✓';
      statusEl.className = 'connected';
      return;
    }
    if (msg.type === 'pending')   { setAppState('pending');  return; }
    if (msg.type === 'rejected')  { setAppState('rejected'); return; }
    if (msg.type === 'kicked')    { setAppState('kicked');   return; }
    if (msg.type === 'text')      { addText(msg.data, msg.from || 'Phone'); return; }
    if (msg.type === 'file')      { addFile(msg.name, parseInt(msg.index) || 0); return; }
  };

  /* ── auto-expand textarea ── */
  const replyInput = document.getElementById('reply-input');
  replyInput.addEventListener('input', function () {
    this.style.height = 'auto';
    this.style.height = Math.min(this.scrollHeight, 280) + 'px';
  });

  /* ── clipboard ── */
  function copyText(text, btn) {
    if (navigator.clipboard && window.isSecureContext) {
      navigator.clipboard.writeText(text).then(() => showCopied(btn)).catch(() => fallbackCopy(text, btn));
    } else { fallbackCopy(text, btn); }
  }
  function fallbackCopy(text, btn) {
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.style.cssText = 'position:fixed;opacity:0;top:0;left:0;width:1px;height:1px;';
    document.body.appendChild(ta); ta.focus(); ta.select();
    try { document.execCommand('copy'); showCopied(btn); } catch(_) {}
    document.body.removeChild(ta);
  }
  function showCopied(btn) {
    const prev = btn.textContent;
    btn.textContent = 'Copied!'; btn.classList.add('copied');
    setTimeout(() => { btn.textContent = prev; btn.classList.remove('copied'); }, 1500);
  }

  /* ── message helpers ── */
  function isLong(text) {
    return text.length > COLLAPSE_THRESHOLD || (text.match(/\n/g) || []).length >= 4;
  }

  function makeReadMoreToggle(textEl) {
    const btn = document.createElement('button');
    btn.className = 'read-more-btn';
    btn.textContent = '▼ Read more';
    function toggle() {
      const nowCollapsed = textEl.classList.toggle('collapsed');
      btn.textContent = nowCollapsed ? '▼ Read more' : '▲ Read less';
    }
    btn.onclick = toggle;
    textEl.style.cursor = 'pointer';
    textEl.onclick = toggle;
    return btn;
  }

  const messages = document.getElementById('messages');

  function addText(text, fromName) {
    const long = isLong(text);
    const li = document.createElement('li');

    if (fromName) {
      const meta = document.createElement('div');
      meta.className = 'msg-meta';
      meta.textContent = 'from ' + fromName;
      li.appendChild(meta);
    }

    const body = document.createElement('div');
    body.className = 'msg-body';
    const textEl = document.createElement('div');
    textEl.className = 'msg-text' + (long ? ' collapsed' : '');
    textEl.textContent = text;
    body.appendChild(textEl);
    if (long) body.appendChild(makeReadMoreToggle(textEl));
    li.appendChild(body);

    const hr = document.createElement('hr'); hr.className = 'msg-divider';
    li.appendChild(hr);

    const actions = document.createElement('div'); actions.className = 'msg-actions';

    const copyBtn = document.createElement('button');
    copyBtn.className = 'action-btn'; copyBtn.textContent = 'Copy';
    copyBtn.onclick = e => { e.stopPropagation(); copyText(text, copyBtn); };

    const saveTxtBtn = document.createElement('button');
    saveTxtBtn.className = 'action-btn'; saveTxtBtn.textContent = '.txt';
    saveTxtBtn.title = 'Save as text file';
    saveTxtBtn.onclick = e => {
      e.stopPropagation();
      const blob = new Blob([text], { type: 'text/plain' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url; a.download = 'message_' + Date.now() + '.txt';
      document.body.appendChild(a); a.click();
      document.body.removeChild(a); URL.revokeObjectURL(url);
    };

    actions.appendChild(copyBtn); actions.appendChild(saveTxtBtn);
    li.appendChild(actions);
    messages.prepend(li);

    if (Notification.permission === 'granted')
      new Notification('Sender', { body: text.slice(0, 100) });
  }

  function addFile(name, index) {
    const li = document.createElement('li'); li.className = 'file-item';
    const body = document.createElement('div'); body.className = 'msg-body';
    const textEl = document.createElement('div');
    textEl.className = 'msg-text'; textEl.textContent = '📎 ' + name;
    body.appendChild(textEl); li.appendChild(body);

    const hr = document.createElement('hr'); hr.className = 'msg-divider';
    li.appendChild(hr);

    const actions = document.createElement('div'); actions.className = 'msg-actions';

    const dlBtn = document.createElement('button');
    dlBtn.className = 'action-btn dl'; dlBtn.textContent = '↓ Download';
    dlBtn.onclick = () => {
      const a = document.createElement('a');
      a.href = '/file/' + index; a.download = name;
      document.body.appendChild(a); a.click(); document.body.removeChild(a);
    };

    const discardBtn = document.createElement('button');
    discardBtn.className = 'action-btn discard'; discardBtn.textContent = '✕ Discard';
    discardBtn.onclick = () => li.remove();

    actions.appendChild(dlBtn); actions.appendChild(discardBtn);
    li.appendChild(actions);
    messages.prepend(li);
  }

  /* ── send helpers ── */
  function sendReply() {
    const msg = replyInput.value.trim();
    if (msg && ws.readyState === WebSocket.OPEN) {
      ws.send(msg); replyInput.value = ''; replyInput.style.height = '';
    }
  }

  function sendTextAsFile() {
    const msg = replyInput.value.trim();
    if (!msg) return;
    const blob = new Blob([msg], { type: 'text/plain' });
    const fd = new FormData();
    fd.append('file', blob, 'message_' + Date.now() + '.txt');
    const us = document.getElementById('upload-status');
    us.textContent = 'Sending as file…';
    fetch('/upload', { method: 'POST', body: fd }).then(() => {
      replyInput.value = ''; replyInput.style.height = '';
      us.textContent = 'Sent as file!';
      setTimeout(() => us.textContent = '', 2000);
    });
  }

  replyInput.addEventListener('keydown', e => {
    if (e.key === 'Enter' && e.ctrlKey) { e.preventDefault(); sendReply(); }
  });

  async function uploadFiles() {
    const input = document.getElementById('file-input');
    const us    = document.getElementById('upload-status');
    if (!input.files.length) return;
    us.textContent = 'Uploading...';
    for (const file of input.files) {
      const fd = new FormData(); fd.append('file', file);
      await fetch('/upload', { method: 'POST', body: fd });
    }
    input.value = ''; us.textContent = 'Sent!';
    setTimeout(() => us.textContent = '', 2000);
  }

  function requestNotify() { Notification.requestPermission(); }
</script>
</body>
</html>
""".trimIndent()

// ── KtorServer ────────────────────────────────────────────────────────────────

private const val HTTPS_PORT     = 8443
private const val HTTP_PORT      = 8080  // plain-HTTP listener; redirects to HTTPS
private const val KEYSTORE_PASS  = "senderpass"
private const val KEYSTORE_ALIAS = "sender"

class KtorServer(private val context: Context) {

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

    private val connectedSessions     = ConcurrentHashMap<String, InternalSession>()
    private val pairingDeferreds      = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val pendingAliasOverrides = ConcurrentHashMap<String, String>()
    private val sharedFiles           = AtomicReference<List<SharedFileInfo>>(emptyList())
    private val _transfers            = ConcurrentHashMap<String, TransferProgress>()
    private val _transferLastUpdate   = ConcurrentHashMap<String, Long>()
    private val scope               = CoroutineScope(Dispatchers.IO)

    // ── Public StateFlows (observed by UI) ───────────────────────────────────
    val networkIfaces    = MutableStateFlow<List<NetworkIface>>(emptyList())
    val activeMdnsIp     = MutableStateFlow<String?>(null)
    val connectedDevices = MutableStateFlow<List<ConnectedDeviceInfo>>(emptyList())
    val pendingPairings  = MutableStateFlow<List<PairingRequest>>(emptyList())
    val trustedDevices   = MutableStateFlow<List<TrustedDevice>>(emptyList())
    val clientCount      = MutableStateFlow(0)
    val receivedMessages = MutableStateFlow<List<ReceivedMessage>>(emptyList())
    val receivedFiles    = MutableStateFlow<List<ReceivedFile>>(emptyList())
    val activeTransfers  = MutableStateFlow<List<TransferProgress>>(emptyList())
    val zipProgress      = MutableStateFlow<Float?>(null)

    private var server: ApplicationEngine? = null
    private var jmDns: JmDNS? = null

    // ── TLS keystore ─────────────────────────────────────────────────────────

    private fun loadKeyStore(): java.security.KeyStore =
        java.security.KeyStore.getInstance("PKCS12").also { ks ->
            context.resources.openRawResource(R.raw.sender_keystore).use { input ->
                ks.load(input, KEYSTORE_PASS.toCharArray())
            }
        }

    // ── Start / stop ─────────────────────────────────────────────────────────

    fun start() {
        val ifaces = getNetworkIfaces()
        networkIfaces.value = ifaces
        trustedDevices.value = deviceManager.getAll()

        // mDNS on first WiFi interface (user can switch later)
        val mdnsIp = ifaces.firstOrNull { it.label == "Wi-Fi" }?.ip
            ?: ifaces.firstOrNull()?.ip
        if (mdnsIp != null) {
            activeMdnsIp.value = mdnsIp
            scope.launch {
                try {
                    jmDns = JmDNS.create(java.net.InetAddress.getByName(mdnsIp), "phone")
                    jmDns?.registerService(ServiceInfo.create("_https._tcp.local.", "Sender", HTTPS_PORT, ""))
                } catch (_: Exception) {}
            }
        }

        // HTTP on 8080 (redirect only) + HTTPS on 8443 (all traffic).
        // connector/sslConnector are extensions on ApplicationEngineEnvironmentBuilder,
        // so we build the environment explicitly instead of using the configure lambda.
        val ks = loadKeyStore()
        val env = applicationEngineEnvironment {
            connector { port = HTTP_PORT }
            sslConnector(
                keyStore = ks,
                keyAlias = KEYSTORE_ALIAS,
                keyStorePassword = { KEYSTORE_PASS.toCharArray() },
                privateKeyPassword = { KEYSTORE_PASS.toCharArray() }
            ) { port = HTTPS_PORT }
            module {
                install(WebSockets)
                // Redirect every plain-HTTP request to HTTPS before routing runs
                intercept(ApplicationCallPipeline.Plugins) {
                    if (call.request.local.scheme == "http") {
                        val host = call.request.headers[HttpHeaders.Host]?.substringBefore(':') ?: "localhost"
                        call.respondRedirect("https://$host:$HTTPS_PORT${call.request.uri}")
                        finish()
                    }
                }
                routing {
                    get("/") {
                        // Redirect phone.local → actual IP so the browser URL bar shows the IP
                        // and WebSocket reconnects to the real address.
                        val host = call.request.headers[HttpHeaders.Host]?.substringBefore(':')
                        val target = activeMdnsIp.value
                        if (host == "phone.local" && target != null) {
                            call.respondRedirect("https://$target:$HTTPS_PORT/", permanent = false)
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
        }
        server = embeddedServer(Netty, env)
        server?.start(wait = false)
    }

    fun stop() {
        jmDns?.close()
        server?.stop(0L, 0L)
    }

    fun switchMdnsTo(ip: String) {
        activeMdnsIp.value = ip
        scope.launch {
            try {
                jmDns?.close()
                jmDns = JmDNS.create(java.net.InetAddress.getByName(ip), "phone")
                jmDns?.registerService(ServiceInfo.create("_https._tcp.local.", "Sender", HTTPS_PORT, ""))
            } catch (_: Exception) {}
        }
    }

    // ── WebSocket handler ────────────────────────────────────────────────────

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

        // Trusted — proceed
        deviceManager.updateLastSeen(deviceId, ip)
        val alias = deviceManager.get(deviceId)?.alias ?: deviceName

        send(Frame.Text(json("type" to "welcome", "alias" to alias)))

        connectedSessions[deviceId] = InternalSession(deviceId, this, alias, ip)
        updateConnected()

        try {
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    receivedMessages.value = receivedMessages.value +
                        ReceivedMessage(text = frame.readText(), fromAlias = alias)
                }
            }
        } finally {
            connectedSessions.remove(deviceId)
            updateConnected()
            deviceManager.updateLastSeen(deviceId, ip)
            refreshTrustedDevices()
        }
    }

    // ── Pairing actions (called from UI) ─────────────────────────────────────

    fun acceptPairing(deviceId: String, alias: String = "") {
        if (alias.isNotBlank()) pendingAliasOverrides[deviceId] = alias.trim()
        pairingDeferreds[deviceId]?.complete(true)
    }

    fun rejectPairing(deviceId: String) { pairingDeferreds[deviceId]?.complete(false) }

    // ── Device management ────────────────────────────────────────────────────

    fun renameDevice(id: String, alias: String) {
        deviceManager.rename(id, alias)
        refreshTrustedDevices()
    }

    fun forgetDevice(id: String) {
        kickDevice(id, "forgotten")
        deviceManager.forget(id)
        refreshTrustedDevices()
    }

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

    // ── Send / broadcast ─────────────────────────────────────────────────────

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

    fun shareFile(name: String, size: Long, openStream: () -> InputStream?, targetDeviceIds: Set<String>? = null) =
        shareFiles(listOf(FileToShare(name, size, openStream)), targetDeviceIds)

    fun shareFiles(files: List<FileToShare>, targetDeviceIds: Set<String>? = null) {
        val infos = files.map { SharedFileInfo(it.name, it.size, it.openStream) }
        sharedFiles.set(infos)
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
                shareFile(zipName, tmp.length(), { tmp.inputStream() }, targetDeviceIds)
            } catch (_: Exception) {
                tmp.delete()
            } finally {
                zipProgress.value = null
            }
        }
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

    // ── HTTP handlers ────────────────────────────────────────────────────────

    private suspend fun serveSharedFile(call: ApplicationCall, index: Int) {
        val info = sharedFiles.get().getOrNull(index)
            ?: return call.respond(HttpStatusCode.NotFound)
        val tid = UUID.randomUUID().toString()
        call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"${info.name}\"")
        try {
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
                        write(buf, 0, n)
                        sent += n
                        val now = System.currentTimeMillis()
                        if (now - (_transferLastUpdate[tid] ?: 0L) >= 100L) {
                            _transferLastUpdate[tid] = now
                            _transfers[tid] = TransferProgress(tid, info.name, sent, info.size)
                            refreshTransfers()
                        }
                    }
                }
            }
        } finally {
            _transfers.remove(tid)
            _transferLastUpdate.remove(tid)
            refreshTransfers()
        }
    }

    private suspend fun receiveUpload(call: ApplicationCall) {
        call.receiveMultipart().forEachPart { part ->
            if (part is PartData.FileItem) {
                val name = part.originalFileName ?: "upload_${System.currentTimeMillis()}"
                val tmp = java.io.File(context.cacheDir, "recv_${UUID.randomUUID()}")
                try {
                    withContext(Dispatchers.IO) {
                        part.streamProvider().use { input ->
                            tmp.outputStream().use { output ->
                                input.copyTo(output, bufferSize = 65_536)
                            }
                        }
                    }
                    receivedFiles.update { it + ReceivedFile(name = name, size = tmp.length(), tempFile = tmp) }
                } catch (_: Exception) {
                    tmp.delete()
                }
            }
            part.dispose()
        }
        call.respond(HttpStatusCode.OK)
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

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun refreshTransfers() {
        activeTransfers.value = _transfers.values.toList()
    }

    private fun updateConnected() {
        val list = connectedSessions.values.map { ConnectedDeviceInfo(it.deviceId, it.alias, it.ip) }
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
