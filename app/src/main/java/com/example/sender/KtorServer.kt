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

  /* ── download bar ── */
  #dl-bar { display:flex; align-items:center; justify-content:space-between;
            background:#161616; border:1px solid #2a2a2a; border-radius:6px;
            padding:8px 12px; margin-bottom:10px; flex-wrap:wrap; gap:8px; }
  #dl-info { display:flex; align-items:center; gap:6px; font-size:0.82rem; color:#888; }
  #dl-path { color:#4caf50; font-size:0.82rem; }
  #dl-change { padding:2px 8px; font-size:0.75rem; background:#2a2a2a;
               color:#aaa; border:1px solid #444; border-radius:3px; cursor:pointer; }
  #dl-change:hover { background:#3a3a3a; }
  #auto-dl-label { display:flex; align-items:center; gap:5px; cursor:pointer;
                   font-size:0.82rem; color:#ccc; }
  #dl-warn { background:#2a1500; border:1px solid #5a3800; border-radius:6px;
             padding:8px 12px; font-size:0.8rem; color:#ffcc66; margin-bottom:8px; display:none; }
  progress.file-dl-progress { width:100%; height:4px; margin:4px 0; display:none; accent-color:#42a5f5; }
  #upload-progress { width:100%; height:8px; display:none; accent-color:#4caf50; border-radius:4px; }
  #batch-status { font-size:0.78rem; color:#888; padding:4px 0 6px; display:none; }
  #zip-ul-label { display:flex; align-items:center; gap:4px; font-size:0.82rem; color:#ccc; cursor:pointer; white-space:nowrap; }
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
  <div id="dl-bar">
    <div id="dl-info">📁 Save to: <span id="dl-path">Downloads folder</span>
      <button id="dl-change" onclick="chooseFolder()">Change</button>
    </div>
    <label id="auto-dl-label">
      <input type="checkbox" id="auto-dl"> Auto-download
    </label>
  </div>
  <div id="batch-status"></div>
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
    <label id="zip-ul-label"><input type="checkbox" id="zip-upload"> ZIP</label>
    <button id="upload-btn" onclick="uploadFiles()">Upload</button>
    <span id="upload-status"></span>
    <progress id="upload-progress" max="100" value="0"></progress>
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
  const pendingTransferCallbacks = new Map();
  let dirHandle = null;

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
  const ws = new WebSocket('ws://' + location.host + '/socket');

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
    if (msg.type === 'file_batch') {
      _batchTotal = parseInt(msg.count) || 0; _batchReceived = 0;
      updateBatchStatus(); return;
    }
    if (msg.type === 'file')      { addFile(msg.name, parseInt(msg.index) || 0); return; }
    if (msg.type === 'transfer_accept') {
      const cb = pendingTransferCallbacks.get(msg.transferId);
      if (cb) cb(true);
      return;
    }
    if (msg.type === 'transfer_reject') {
      const cb = pendingTransferCallbacks.get(msg.transferId);
      if (cb) cb(false);
      return;
    }
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
  }

  /* ── batch download status ── */
  let _batchTotal = 0, _batchReceived = 0;

  function updateBatchStatus() {
    const el = document.getElementById('batch-status');
    if (_batchTotal <= 1) { el.style.display = 'none'; return; }
    const queued = _dlQueue.length + _dlActive;
    el.textContent = 'Received ' + _batchReceived + ' / ' + _batchTotal + ' files'
      + (queued > 0 ? ' · ' + queued + ' downloading' : ' · done');
    el.style.display = 'block';
    if (_batchReceived >= _batchTotal && queued === 0) {
      setTimeout(() => { el.style.display = 'none'; _batchTotal = 0; _batchReceived = 0; }, 3000);
    }
  }

  /* ── ZIP creation (pure JS, no external libraries, STORED method) ── */
  const _crcT = new Uint32Array(256);
  for (let i = 0; i < 256; i++) {
    let c = i;
    for (let j = 0; j < 8; j++) c = c & 1 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1;
    _crcT[i] = c >>> 0;
  }
  function _crc32(buf) {
    let c = 0xFFFFFFFF;
    for (let i = 0; i < buf.length; i++) c = _crcT[(c ^ buf[i]) & 0xFF] ^ (c >>> 8);
    return (c ^ 0xFFFFFFFF) >>> 0;
  }
  async function createZipBlob(files, onProgress) {
    const enc = new TextEncoder();
    const parts = [], central = [];
    let offset = 0;
    for (let i = 0; i < files.length; i++) {
      const f = files[i];
      const nb = enc.encode(f.name);
      const data = new Uint8Array(await f.arrayBuffer());
      const crc = _crc32(data), sz = data.length;
      const lh = new Uint8Array(30 + nb.length);
      const ld = new DataView(lh.buffer);
      ld.setUint32(0, 0x04034b50, true); ld.setUint16(4, 20, true);
      ld.setUint32(14, crc, true); ld.setUint32(18, sz, true); ld.setUint32(22, sz, true);
      ld.setUint16(26, nb.length, true); lh.set(nb, 30);
      const ce = new Uint8Array(46 + nb.length);
      const cd = new DataView(ce.buffer);
      cd.setUint32(0, 0x02014b50, true); cd.setUint16(4, 20, true); cd.setUint16(6, 20, true);
      cd.setUint32(16, crc, true); cd.setUint32(20, sz, true); cd.setUint32(24, sz, true);
      cd.setUint16(28, nb.length, true); cd.setUint32(42, offset, true); ce.set(nb, 46);
      parts.push(lh, data); central.push(ce);
      offset += lh.length + sz;
      if (onProgress) onProgress((i + 1) / files.length);
    }
    const cdSz = central.reduce((s, e) => s + e.length, 0);
    const eocd = new Uint8Array(22);
    const ev = new DataView(eocd.buffer);
    ev.setUint32(0, 0x06054b50, true);
    ev.setUint16(8, files.length, true); ev.setUint16(10, files.length, true);
    ev.setUint32(12, cdSz, true); ev.setUint32(16, offset, true);
    return new Blob([...parts, ...central, eocd], { type: 'application/zip' });
  }

  /* ── download queue ──────────────────────────────────────────────────────────
     Browsers block programmatic <a>.click() downloads after ~10 rapid calls.
     Queue ensures downloads go one-at-a-time for native downloads (no FSA) and
     at most 3 concurrent streams for FSA, so no browser download limit is hit.
  ─────────────────────────────────────────────────────────────────────────── */
  const _dlQueue = [];
  let _dlActive = 0;
  const _DL_CONCURRENCY = 3;

  async function _runDlTask(task) {
    try { await triggerDownload(task.name, task.index, task.progressEl); } catch(_) {}
    if (!dirHandle) await new Promise(r => setTimeout(r, 350));
    _dlActive--;
    _drainDlQueue();
    updateBatchStatus();
  }

  function _drainDlQueue() {
    const maxSlots = dirHandle ? _DL_CONCURRENCY : 1;
    while (_dlActive < maxSlots && _dlQueue.length > 0) {
      _dlActive++;
      _runDlTask(_dlQueue.shift());
    }
  }

  function queueDownload(name, index, progressEl) {
    console.log('[dl-queue] enqueue', name, 'index=' + index, 'queued=' + (_dlQueue.length + 1), 'active=' + _dlActive);
    _dlQueue.push({ name, index, progressEl });
    _drainDlQueue();
  }

  /* ── download helpers ── */
  async function triggerDownload(name, index, progressEl) {
    if (dirHandle) {
      // FSA folder chosen: stream directly into the file, no RAM buffering
      if (progressEl) { progressEl.style.display = 'block'; progressEl.value = 0; }
      let writable;
      try {
        const resp = await fetch('/file/' + index);
        if (!resp.ok) throw new Error('HTTP ' + resp.status);
        const total = parseInt(resp.headers.get('content-length') || '0');
        const fh = await dirHandle.getFileHandle(name, { create: true });
        writable = await fh.createWritable();
        const reader = resp.body.getReader();
        let rcv = 0;
        while (true) {
          const { done, value } = await reader.read();
          if (done) break;
          await writable.write(value);
          rcv += value.length;
          if (total > 0 && progressEl) progressEl.value = rcv / total * 100;
        }
        await writable.close();
        if (progressEl) { progressEl.value = 100; setTimeout(() => progressEl.style.display = 'none', 500); }
      } catch(_) {
        if (writable) writable.abort().catch(() => {});
        if (progressEl) progressEl.style.display = 'none';
      }
      return;
    }
    // No FSA folder: let the browser download natively via a direct link.
    // This avoids RAM buffering and blob-URL popup-blocker issues.
    if (progressEl) progressEl.style.display = 'none';
    const a = document.createElement('a');
    a.href = '/file/' + index; a.download = name;
    document.body.appendChild(a); a.click(); document.body.removeChild(a);
  }

  function addFile(name, index) {
    const li = document.createElement('li'); li.className = 'file-item';
    const autoOn = document.getElementById('auto-dl').checked;
    const hasFsa  = !!dirHandle;

    const body   = document.createElement('div'); body.className = 'msg-body';
    const textEl = document.createElement('div'); textEl.className = 'msg-text';
    textEl.textContent = (autoOn ? '↓ ' : '📎 ') + name;
    body.appendChild(textEl);
    li.appendChild(body);

    // Progress bar only meaningful when FSA streams directly
    const progressEl = document.createElement('progress');
    progressEl.className = 'file-dl-progress'; progressEl.max = 100; progressEl.value = 0;
    li.appendChild(progressEl);

    const hr = document.createElement('hr'); hr.className = 'msg-divider';
    li.appendChild(hr);

    const actions = document.createElement('div'); actions.className = 'msg-actions';

    _batchReceived++;
    updateBatchStatus();

    if (autoOn) {
      queueDownload(name, index, hasFsa ? progressEl : null);
      if (!hasFsa) {
        const note = document.createElement('span');
        note.style.cssText = 'font-size:0.72rem;color:#888;';
        note.textContent = 'Saving to browser Downloads…';
        actions.appendChild(note);
      }
      const reBtn = document.createElement('button');
      reBtn.className = 'action-btn dl'; reBtn.textContent = '↺ Re-download';
      reBtn.onclick = () => triggerDownload(name, index, hasFsa ? progressEl : null);
      actions.appendChild(reBtn);
    } else {
      const dlBtn = document.createElement('button');
      dlBtn.className = 'action-btn dl'; dlBtn.textContent = '↓ Download';
      dlBtn.onclick = () => triggerDownload(name, index, hasFsa ? progressEl : null);
      const discardBtn = document.createElement('button');
      discardBtn.className = 'action-btn discard'; discardBtn.textContent = '✕ Discard';
      discardBtn.onclick = () => li.remove();
      actions.appendChild(dlBtn); actions.appendChild(discardBtn);
    }

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

  function uploadWithProgress(url, formData, onProgress) {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('POST', url);
      xhr.upload.addEventListener('progress', e => {
        if (e.lengthComputable) onProgress(e.loaded / e.total);
      });
      xhr.onload = () => { onProgress(1); resolve(xhr.status); };
      xhr.onerror = () => reject(new Error('Network error'));
      xhr.send(formData);
    });
  }

  async function _announceAndWait(transferId, fileList, totalBytes, us) {
    us.textContent = 'Waiting for approval…';
    ws.send(JSON.stringify({
      type: 'transfer_announce', transferId,
      files: fileList.map(f => ({ name: f.name, size: f.size })),
      totalBytes
    }));
    return new Promise(resolve => {
      const timer = setTimeout(() => {
        pendingTransferCallbacks.delete(transferId);
        resolve(false);
      }, 60000);
      pendingTransferCallbacks.set(transferId, ok => {
        clearTimeout(timer);
        pendingTransferCallbacks.delete(transferId);
        resolve(ok);
      });
    });
  }

  async function uploadFiles() {
    const input  = document.getElementById('file-input');
    const us     = document.getElementById('upload-status');
    const upProg = document.getElementById('upload-progress');
    if (!input.files.length) return;
    if (ws.readyState !== WebSocket.OPEN) { us.textContent = 'Not connected'; return; }

    const files  = Array.from(input.files);
    const zipMode = document.getElementById('zip-upload').checked && files.length > 1;
    const mkId = () => (typeof crypto !== 'undefined' && crypto.randomUUID)
      ? crypto.randomUUID()
      : Date.now().toString(36) + Math.random().toString(36).slice(2);

    if (zipMode) {
      // ── ZIP path ─────────────────────────────────────────────────────────────
      upProg.style.display = 'block'; upProg.value = 0;
      us.textContent = 'Creating ZIP…';
      const zipName = 'archive_' + Date.now() + '.zip';
      const zipBlob = await createZipBlob(files, p => { upProg.value = p * 50; });
      const transferId = mkId();
      const accepted = await _announceAndWait(
        transferId,
        [{ name: zipName, size: zipBlob.size }],
        zipBlob.size, us
      );
      if (!accepted) {
        upProg.style.display = 'none';
        us.textContent = 'Rejected or timed out.';
        setTimeout(() => us.textContent = '', 3000);
        return;
      }
      us.textContent = 'Uploading ZIP (' + Math.round(zipBlob.size / 1024) + ' KB)…';
      const fd = new FormData(); fd.append('file', zipBlob, zipName);
      await uploadWithProgress(
        '/upload?transferId=' + encodeURIComponent(transferId), fd,
        p => { upProg.value = 50 + p * 50; }
      );
    } else {
      // ── Individual files path ─────────────────────────────────────────────────
      const transferId = mkId();
      const totalBytes = files.reduce((s, f) => s + f.size, 0);
      const accepted = await _announceAndWait(transferId, files, totalBytes, us);
      if (!accepted) {
        us.textContent = 'Rejected or timed out.';
        setTimeout(() => us.textContent = '', 3000);
        return;
      }
      upProg.style.display = 'block'; upProg.value = 0;
      for (let i = 0; i < files.length; i++) {
        const fd = new FormData(); fd.append('file', files[i]);
        us.textContent = (i + 1) + '/' + files.length + ': ' + files[i].name;
        upProg.value = 0;
        await uploadWithProgress(
          '/upload?transferId=' + encodeURIComponent(transferId), fd,
          p => { upProg.value = p * 100; }
        );
      }
    }

    upProg.value = 100;
    setTimeout(() => { upProg.style.display = 'none'; }, 600);
    input.value = ''; us.textContent = 'Done!';
    setTimeout(() => us.textContent = '', 2000);
  }

  function showDlWarn(msg) {
    let warn = document.getElementById('dl-warn');
    if (!warn) {
      warn = document.createElement('div'); warn.id = 'dl-warn';
      document.getElementById('dl-bar').insertAdjacentElement('afterend', warn);
    }
    warn.textContent = msg; warn.style.display = '';
  }
  function hideDlWarn() {
    const w = document.getElementById('dl-warn'); if (w) w.style.display = 'none';
  }
  function checkDlReady() {
    const tip = 'In browser Settings → Downloads, set a fixed save folder and make sure \"Ask where to save each file\" is turned OFF.';
    if (!dirHandle) {
      if (!('showDirectoryPicker' in window))
        showDlWarn('⚠ Your browser does not support folder picker. ' + tip);
      else
        showDlWarn('⚠ No save folder chosen. Tap “Change” above to pick one. ' + tip);
    } else {
      hideDlWarn();
    }
  }

  /* ── download settings init ── */
  (function initDlSettings() {
    const autoDlEl = document.getElementById('auto-dl');
    autoDlEl.checked = localStorage.getItem('autoDownload') === 'true';
    autoDlEl.addEventListener('change', () => {
      localStorage.setItem('autoDownload', autoDlEl.checked);
      if (autoDlEl.checked) checkDlReady(); else hideDlWarn();
    });
    const saved = localStorage.getItem('dl-folder-name');
    if (saved) document.getElementById('dl-path').textContent = saved + ' (re-select to use)';
    if (autoDlEl.checked) checkDlReady();
  })();

  async function chooseFolder() {
    if (!('showDirectoryPicker' in window)) {
      alert('Folder picker not supported in this browser.\nIn browser Settings → Downloads, set a default save location.');
      return;
    }
    try {
      dirHandle = await window.showDirectoryPicker({ mode: 'readwrite' });
      document.getElementById('dl-path').textContent = dirHandle.name;
      localStorage.setItem('dl-folder-name', dirHandle.name);
      hideDlWarn();
    } catch(_) {}
  }
</script>
</body>
</html>
""".trimIndent()

// ── KtorServer ────────────────────────────────────────────────────────────────

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

    // ── Public StateFlows (observed by UI) ───────────────────────────────────
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

    // ── Start / stop ─────────────────────────────────────────────────────────

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

        // Ktor binds to 0.0.0.0 by default → accepts connections on all interfaces
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
                    val text = frame.readText()
                    val obj = runCatching { JSONObject(text) }.getOrNull()
                    when (obj?.optString("type")) {
                        "transfer_announce" -> obj?.let { o ->
                            runCatching { handleTransferAnnounce(o, alias, this) }
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

    // ── Pairing actions (called from UI) ─────────────────────────────────────

    fun acceptPairing(deviceId: String, alias: String = "") {
        if (alias.isNotBlank()) pendingAliasOverrides[deviceId] = alias.trim()
        pairingDeferreds[deviceId]?.complete(true)
    }

    fun rejectPairing(deviceId: String) { pairingDeferreds[deviceId]?.complete(false) }

    // ── Transfer announcement handler ────────────────────────────────────────

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

    fun shareFile(name: String, size: Long, openStream: () -> InputStream?, targetDeviceIds: Set<String>? = null) {
        android.util.Log.d(TAG, "shareFile: $name ($size bytes) → ${targetDeviceIds ?: "all"}")
        shareFiles(listOf(FileToShare(name, size, openStream)), targetDeviceIds)
    }

    fun shareFiles(files: List<FileToShare>, targetDeviceIds: Set<String>? = null) {
        if (files.size > AUTO_ZIP_THRESHOLD) {
            android.util.Log.i(TAG, "shareFiles: ${files.size} files > $AUTO_ZIP_THRESHOLD → auto-zipping")
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
            android.util.Log.i(TAG, "createAndShareZip: zipping ${files.size} files → $zipName")
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

    // ── HTTP handlers ────────────────────────────────────────────────────────

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
                        flush()
                        sent += n
                        _transfers[tid] = TransferProgress(tid, info.name, sent, info.size)
                        refreshTransfers()
                    }
                }
            }
            val done = _outgoingBatchDone.incrementAndGet()
            val total = _outgoingBatchTotal.get()
            outgoingBatch.value = TransferBatch(total, done)
            android.util.Log.d(TAG, "serveSharedFile: completed index=$index name=${info.name} batch=$done/$total")
            if (done >= total) {
                scope.launch { delay(2000); if (_outgoingBatchDone.get() >= _outgoingBatchTotal.get()) outgoingBatch.value = null }
            }
        } finally {
            _transfers.remove(tid)
            _transferLastUpdate.remove(tid)
            refreshTransfers()
        }
    }

    private suspend fun receiveUpload(call: ApplicationCall) {
        val transferId = call.request.queryParameters["transferId"]
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
                if (isAutoSave) autoSaveFile(name, locationUri, part, onProgress)
                else saveToCache(name, part, onProgress)
                val finalSize = if (expectedSize > 0L) expectedSize else rcvBytes
                _incomingXfers[tid] = TransferProgress(tid, name, finalSize, finalSize)
                refreshIncomingTransfers()
                // Update incoming batch done count
                if (transferId != null) {
                    val done = _incomingBatchDone.getOrPut(transferId) { AtomicInteger(0) }.incrementAndGet()
                    val total = _incomingBatchTotal[transferId] ?: done
                    android.util.Log.d(TAG, "receiveUpload: file done $done/$total for transferId=$transferId")
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
                delay(500)
                _incomingXfers.remove(tid)
                _incomingXferLastUpdate.remove(tid)
                refreshIncomingTransfers()
            }
            part.dispose()
        }
        call.respond(HttpStatusCode.OK)
    }

    private suspend fun autoSaveFile(
        name: String, safUriStr: String?, part: PartData.FileItem,
        onProgress: (Long) -> Unit = {}
    ) {
        try {
            val outputStream = if (safUriStr != null) {
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
            withContext(Dispatchers.IO) {
                outputStream?.use { out ->
                    part.streamProvider().use { inp ->
                        val buf = ByteArray(65_536); var n: Int; var rcv = 0L
                        while (inp.read(buf).also { n = it } != -1) {
                            out.write(buf, 0, n); rcv += n; onProgress(rcv)
                        }
                    }
                }
            }
        } catch (_: Exception) {
            saveToCache(name, part, onProgress)
        }
    }

    private suspend fun saveToCache(
        name: String, part: PartData.FileItem,
        onProgress: (Long) -> Unit = {}
    ) {
        val tmp = java.io.File(context.cacheDir, "recv_${UUID.randomUUID()}")
        try {
            withContext(Dispatchers.IO) {
                part.streamProvider().use { input ->
                    tmp.outputStream().use { output ->
                        val buf = ByteArray(65_536); var n: Int; var rcv = 0L
                        while (input.read(buf).also { n = it } != -1) {
                            output.write(buf, 0, n); rcv += n; onProgress(rcv)
                        }
                    }
                }
            }
            receivedFiles.update { it + ReceivedFile(name = name, size = tmp.length(), tempFile = tmp) }
        } catch (_: Exception) { tmp.delete() }
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

    private fun refreshIncomingTransfers() {
        incomingTransfers.value = _incomingXfers.values.toList()
    }

    private fun refreshIncomingBatch() {
        incomingBatch.value = _incomingBatchTotal.mapValues { (tid, total) ->
            TransferBatch(total, _incomingBatchDone[tid]?.get() ?: 0)
        }
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
