package com.example.sender

// Browser receiver UI — served at http://<phone-ip>:8080/
// All functional JS logic is preserved; only HTML/CSS/layout is redesigned.
internal val HTML_PAGE = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Sender</title>
<style>
:root {
  --bg:       #0b0d12;
  --s1:       #13161e;
  --s2:       #1a1e28;
  --s3:       #22273a;
  --border:   #252b3b;
  --primary:  #38bdf8;
  --primary2: #0c4a6e;
  --accent:   #4ade80;
  --danger:   #f87171;
  --text:     #e2e8f0;
  --muted:    #64748b;
  --radius:   12px;
  --r-sm:     8px;
  --r-xs:     5px;
}
*,*::before,*::after { box-sizing:border-box; margin:0; padding:0; }
body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
  background: var(--bg); color: var(--text);
  min-height: 100vh; display: flex; flex-direction: column;
}

/* ---- icons ---- */
.icon {
  width: 1em; height: 1em; display: inline-block;
  vertical-align: middle; fill: currentColor; flex-shrink: 0;
}
.icon-lg { width: 1.25em; height: 1.25em; }
.icon-xl { width: 2rem; height: 2rem; }

/* ---- header ---- */
header {
  position: sticky; top: 0; z-index: 50;
  background: var(--s1); border-bottom: 1px solid var(--border);
  padding: 0 16px;
  display: flex; align-items: center; gap: 10px; height: 52px;
}
.hdr-icon { color: var(--primary); display: flex; align-items: center; }
.app-title { font-size: 1rem; font-weight: 600; flex: 1; }
#status-dot {
  width: 8px; height: 8px; border-radius: 50%;
  background: var(--muted); transition: background .3s; flex-shrink: 0;
}
#status-dot.ok  { background: var(--accent); }
#status-dot.err { background: var(--danger); }
#status-text { font-size: 0.78rem; color: var(--muted); white-space: nowrap; }
#status-text.ok { color: var(--accent); }

/* ---- main layout ---- */
main {
  flex: 1; max-width: 680px; width: 100%;
  margin: 0 auto; padding: 14px 14px 80px;
  display: flex; flex-direction: column; gap: 12px;
}

/* ---- pairing overlay ---- */
#pairing-overlay {
  position: fixed; inset: 0; z-index: 100;
  background: rgba(0,0,0,0.92);
  display: flex; align-items: center; justify-content: center;
}
#pairing-overlay.hidden { display: none; }
#pair-box {
  background: var(--s1); border: 1px solid var(--border);
  border-radius: var(--radius); padding: 32px 28px;
  text-align: center; max-width: 360px; width: 92%;
  display: flex; flex-direction: column; align-items: center; gap: 10px;
}
.pair-status-icon {
  font-size: 2rem; color: var(--primary); line-height: 1;
  width: 56px; height: 56px; border-radius: 50%;
  background: var(--s2); border: 1px solid var(--border);
  display: flex; align-items: center; justify-content: center;
}
#pair-msg { font-size: 1.05rem; font-weight: 600; }
#pair-sub { font-size: 0.83rem; color: var(--muted); line-height: 1.5; }
#pair-retry {
  margin-top: 6px; padding: 8px 22px;
  background: var(--s2); color: var(--text);
  border: 1px solid var(--border); border-radius: var(--r-sm);
  cursor: pointer; font-size: 0.85rem; transition: background .15s;
}
#pair-retry:hover { background: var(--s3); }

/* ---- card ---- */
.card {
  background: var(--s1); border: 1px solid var(--border);
  border-radius: var(--radius); overflow: hidden;
}

/* ---- save-bar ---- */
#save-bar {
  padding: 10px 14px;
  display: flex; align-items: center; gap: 10px; flex-wrap: wrap;
}
.save-bar-left {
  display: flex; align-items: center; gap: 6px; flex: 1; min-width: 180px;
}
.save-label { font-size: 0.78rem; color: var(--muted); white-space: nowrap; }
#dl-path {
  font-size: 0.78rem; color: var(--primary); font-family: monospace;
  max-width: 180px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
#dl-change {
  padding: 2px 10px; font-size: 0.72rem;
  background: var(--s2); color: var(--text);
  border: 1px solid var(--border); border-radius: var(--r-xs);
  cursor: pointer; white-space: nowrap; transition: background .15s;
}
#dl-change:hover { background: var(--s3); }
#auto-dl-label {
  display: flex; align-items: center; gap: 6px;
  cursor: pointer; font-size: 0.82rem; color: var(--text);
  user-select: none; white-space: nowrap;
}

/* ---- toggle switch ---- */
.toggle-track {
  position: relative; width: 36px; height: 20px;
  background: var(--s3); border-radius: 10px;
  transition: background .2s; flex-shrink: 0;
}
.toggle-track input { opacity: 0; width: 0; height: 0; position: absolute; }
.toggle-thumb {
  position: absolute; top: 2px; left: 2px;
  width: 16px; height: 16px; border-radius: 50%;
  background: var(--muted); transition: transform .2s, background .2s;
}
.toggle-track input:checked ~ .toggle-thumb {
  transform: translateX(16px); background: var(--accent);
}
.toggle-track:has(input:checked) { background: #14532d; }

#dl-warn {
  margin: 0 14px 10px; padding: 10px 12px;
  background: #2a1500; border: 1px solid #5a3800;
  border-radius: var(--r-sm); font-size: 0.78rem; color: #ffcc66;
  display: none;
}

/* ---- batch status ---- */
#batch-bar {
  padding: 6px 14px; font-size: 0.75rem; color: var(--muted);
  border-top: 1px solid var(--border); display: none;
  display: flex; align-items: center; gap: 8px;
}
#batch-bar.active { display: flex; }
#batch-progress-track {
  flex: 1; height: 4px; background: var(--s3); border-radius: 2px; overflow: hidden;
}
#batch-progress-fill { height: 100%; background: var(--primary); border-radius: 2px; width: 0%; transition: width .3s; }
#batch-text { white-space: nowrap; }

/* ---- inbox list ---- */
#inbox { list-style: none; display: flex; flex-direction: column; gap: 10px; }

/* ---- message item ---- */
.msg-card {
  background: var(--s1); border: 1px solid var(--border);
  border-radius: var(--radius); overflow: hidden;
}
.msg-card-header {
  display: flex; align-items: center; gap: 8px;
  padding: 8px 12px 0;
}
.msg-type-icon { display: flex; align-items: center; color: var(--primary); opacity: .8; }
.msg-from { font-size: 0.72rem; color: var(--muted); flex: 1; }
.msg-card-body { padding: 6px 12px; }
.msg-text {
  word-break: break-word; white-space: pre-wrap;
  line-height: 1.6; font-size: 0.9rem;
}
.msg-text.collapsed { max-height: 6em; overflow: hidden; position: relative; }
.msg-text.collapsed::after {
  content: ''; position: absolute; bottom: 0; left: 0; right: 0;
  height: 2.5em; background: linear-gradient(transparent, var(--s1));
  pointer-events: none;
}
.read-more-btn {
  background: none; border: none; color: var(--primary);
  cursor: pointer; font-size: 0.75rem; padding: 3px 0; display: block;
}
.read-more-btn:hover { color: #7dd3fc; }
.msg-divider { border: none; border-top: 1px solid var(--border); margin: 8px 0 0; }
.msg-actions { padding: 8px 12px; display: flex; gap: 6px; flex-wrap: wrap; }

/* ---- file item ---- */
.file-card { background: var(--s2); border: 1px solid var(--border); border-radius: var(--radius); overflow: hidden; }
.file-card-header {
  display: flex; align-items: center; gap: 10px; padding: 10px 12px 8px;
}
.file-type-pill {
  width: 36px; height: 36px; border-radius: var(--r-sm);
  background: var(--primary2); display: flex; align-items: center;
  justify-content: center; font-size: 0.65rem; font-weight: 700;
  color: var(--primary); letter-spacing: .03em; flex-shrink: 0;
  text-transform: uppercase;
}
.file-info { flex: 1; min-width: 0; }
.file-name {
  font-size: 0.88rem; font-weight: 500;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.file-meta { font-size: 0.72rem; color: var(--muted); margin-top: 1px; }
.file-dl-progress {
  width: calc(100% - 24px); height: 3px; margin: 0 12px 8px;
  appearance: none; display: none; border-radius: 2px; overflow: hidden;
}
.file-dl-progress::-webkit-progress-bar { background: var(--s3); }
.file-dl-progress::-webkit-progress-value { background: var(--primary); transition: width .1s; }
.file-divider { border: none; border-top: 1px solid var(--border); }
.file-actions { padding: 8px 12px; display: flex; gap: 6px; }

/* ---- buttons ---- */
.btn {
  display: inline-flex; align-items: center; gap: 5px;
  padding: 6px 14px; font-size: 0.8rem; border-radius: var(--r-sm);
  cursor: pointer; border: 1px solid var(--border); transition: background .15s, opacity .15s;
  font-family: inherit; white-space: nowrap;
}
.btn-primary   { background: var(--primary); color: #000; border-color: var(--primary); font-weight: 600; }
.btn-primary:hover { background: #7dd3fc; }
.btn-secondary { background: var(--s2); color: var(--text); }
.btn-secondary:hover { background: var(--s3); }
.btn-dl        { background: transparent; color: var(--primary); border-color: var(--primary2); }
.btn-dl:hover  { background: var(--primary2); }
.btn-danger    { background: transparent; color: var(--danger); border-color: #4b1c1c; }
.btn-danger:hover { background: #2d1414; }
.btn-ghost     { background: transparent; color: var(--muted); border-color: transparent; }
.btn-ghost:hover { color: var(--text); background: var(--s2); }
.btn.copied    { color: var(--accent); border-color: var(--accent); }
.btn:disabled  { opacity: .35; cursor: default; pointer-events: none; }

/* ---- send panel (tabs) ---- */
.tab-bar {
  display: flex; border-bottom: 1px solid var(--border); background: var(--s1);
}
.tab-btn {
  flex: 1; padding: 10px 0; font-size: 0.82rem; color: var(--muted);
  background: none; border: none; border-bottom: 2px solid transparent;
  cursor: pointer; transition: color .15s, border-color .15s; font-family: inherit;
  display: flex; align-items: center; justify-content: center; gap: 6px;
}
.tab-btn.active { color: var(--primary); border-bottom-color: var(--primary); }
.tab-panel { display: none; padding: 14px; }
.tab-panel.active { display: block; }

/* ---- message compose ---- */
#reply-input {
  width: 100%; background: var(--s2); color: var(--text);
  border: 1px solid var(--border); border-radius: var(--r-sm);
  padding: 10px 12px; font-size: 0.88rem; font-family: inherit;
  resize: none; min-height: 72px; max-height: 260px; overflow-y: auto;
  transition: border-color .15s; display: block; line-height: 1.5;
  scrollbar-width: thin; scrollbar-color: var(--s3) transparent;
}
#reply-input::-webkit-scrollbar { width: 4px; }
#reply-input::-webkit-scrollbar-thumb { background: var(--s3); border-radius: 2px; }
#reply-input:focus { outline: none; border-color: var(--primary); }
.compose-btns { display: flex; gap: 8px; margin-top: 10px; flex-wrap: wrap; }

/* ---- file send area ---- */
.file-drop-zone {
  border: 2px dashed var(--border); border-radius: var(--r-sm);
  padding: 22px 18px; text-align: center; cursor: pointer;
  transition: border-color .15s, background .15s;
  position: relative; display: flex; flex-direction: column; align-items: center; gap: 6px;
}
.file-drop-zone:hover, .file-drop-zone.drag { border-color: var(--primary); background: var(--s2); }
.file-drop-zone input { position: absolute; inset: 0; opacity: 0; cursor: pointer; }
.drop-icon { color: var(--primary); opacity: .7; }
.drop-label { font-size: 0.82rem; color: var(--muted); }
.drop-label b { color: var(--text); }
#selected-files-info { font-size: 0.78rem; color: var(--primary); }

.upload-opts { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-top: 10px; }
#upload-btn { min-width: 110px; }

/* upload-status: inline in the opts row, but when it has content it wraps to full width */
#upload-status {
  margin-top: 10px; font-size: 0.78rem; color: var(--accent); flex: 1; text-align: right; min-width: 0;
}
#upload-status:not(:empty) {
  width: 100%; order: 3; text-align: left;
  padding: 6px 10px; background: var(--s2); border: 1px solid var(--border);
  border-radius: var(--r-xs); margin-top: 2px;
}

#upload-progress {
  width: 100%; height: 5px; margin-top: 8px;
  appearance: none; display: none; border-radius: 3px; overflow: hidden;
}
#upload-progress::-webkit-progress-bar { background: var(--s3); }
#upload-progress::-webkit-progress-value { background: var(--primary); }

/* ---- zip toggle ---- */
#zip-upload-label {
  display: flex; align-items: center; gap: 6px;
  font-size: 0.8rem; color: var(--text); cursor: pointer; user-select: none;
}

/* ---- empty state ---- */
.empty-state {
  text-align: center; padding: 40px 20px; color: var(--muted);
  display: flex; flex-direction: column; align-items: center; gap: 10px;
}
.empty-icon { opacity: .35; color: var(--muted); }
.empty-text  { font-size: 0.85rem; }

/* ---- section header ---- */
.section-header {
  display: flex; align-items: center; gap: 8px;
  font-size: 0.72rem; font-weight: 600; color: var(--muted);
  text-transform: uppercase; letter-spacing: .06em;
  padding: 0 2px;
}
.section-header .badge {
  background: var(--s3); color: var(--muted);
  border-radius: 10px; padding: 1px 7px; font-size: 0.7rem;
}
</style>
</head>
<body>

<!-- SVG icon definitions (hidden) -->
<svg xmlns="http://www.w3.org/2000/svg" style="display:none" aria-hidden="true">
  <symbol id="ic-wifi" viewBox="0 0 24 24"><path d="M1 9l2 2c4.97-4.97 13.03-4.97 18 0l2-2C16.93 2.93 7.08 2.93 1 9zm8 8l3 3 3-3a4.237 4.237 0 00-6 0zm-4-4l2 2a7.074 7.074 0 0110 0l2-2C15.14 9.14 8.87 9.14 5 13z"/></symbol>
  <symbol id="ic-folder" viewBox="0 0 24 24"><path d="M10 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z"/></symbol>
  <symbol id="ic-msg" viewBox="0 0 24 24"><path d="M20 2H4c-1.1 0-2 .9-2 2v18l4-4h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2z"/></symbol>
  <symbol id="ic-attach" viewBox="0 0 24 24"><path d="M16.5 6v11.5c0 2.21-1.79 4-4 4s-4-1.79-4-4V5a2.5 2.5 0 015 0v10.5c0 .55-.45 1-1 1s-1-.45-1-1V6H10v9.5a2.5 2.5 0 005 0V5c0-2.21-1.79-4-4-4S7 2.79 7 5v12.5c0 3.04 2.46 5.5 5.5 5.5s5.5-2.46 5.5-5.5V6h-1.5z"/></symbol>
  <symbol id="ic-upload" viewBox="0 0 24 24"><path d="M9 16h6v-6h4l-7-7-7 7h4zm-4 2h14v2H5z"/></symbol>
  <symbol id="ic-send" viewBox="0 0 24 24"><path d="M2.01 21L23 12 2.01 3 2 10l15 2-15 2z"/></symbol>
  <symbol id="ic-file" viewBox="0 0 24 24"><path d="M6 2c-1.1 0-1.99.9-1.99 2L4 20c0 1.1.89 2 1.99 2H18c1.1 0 2-.9 2-2V8l-6-6H6zm7 7V3.5L18.5 9H13z"/></symbol>
  <symbol id="ic-inbox" viewBox="0 0 24 24"><path d="M19 3H4.99C3.89 3 3 3.9 3 5L3 19c0 1.1.89 2 1.99 2H19c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm0 12h-4c0 1.66-1.34 3-3 3s-3-1.34-3-3H4.99V5H19v10z"/></symbol>
  <symbol id="ic-copy" viewBox="0 0 24 24"><path d="M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z"/></symbol>
  <symbol id="ic-dl" viewBox="0 0 24 24"><path d="M19 9h-4V3H9v6H5l7 7 7-7zm-8 2V5h2v6h1.17L12 13.17 9.83 11H11zm-6 8h14v2H5z"/></symbol>
</svg>

<!-- ---- pairing overlay ---- -->
<div id="pairing-overlay">
  <div id="pair-box">
    <div class="pair-status-icon" id="pair-icon">⊙</div>
    <p id="pair-msg">Connecting…</p>
    <p id="pair-sub"></p>
    <button id="pair-retry" hidden onclick="location.reload()">↺ Retry</button>
  </div>
</div>

<!-- ---- header ---- -->
<header>
  <span class="hdr-icon">
    <svg class="icon icon-lg"><use href="#ic-wifi"/></svg>
  </span>
  <span class="app-title">Sender</span>
  <div id="status-dot"></div>
  <span id="status-text">Connecting…</span>
</header>

<!-- ---- main content (hidden until paired) ---- -->
<main id="main-ui" hidden>

  <!-- Send panel — FIRST (compose before inbox) -->
  <div class="card">
    <div class="tab-bar">
      <button class="tab-btn active" onclick="switchTab('message', this)">
        <svg class="icon"><use href="#ic-msg"/></svg> Message
      </button>
      <button class="tab-btn" onclick="switchTab('file', this)">
        <svg class="icon"><use href="#ic-attach"/></svg> File
      </button>
    </div>

    <!-- Message tab -->
    <div class="tab-panel active" id="tab-message">
      <textarea id="reply-input" placeholder="Type a message… (Ctrl+Enter to send)"></textarea>
      <div class="compose-btns">
        <button class="btn btn-primary" id="send-btn" onclick="sendReply()">
          <svg class="icon"><use href="#ic-send"/></svg> Send
        </button>
        <button class="btn btn-secondary" id="send-file-btn" onclick="sendTextAsFile()">
          <svg class="icon"><use href="#ic-file"/></svg> Send as .txt
        </button>
      </div>
    </div>

    <!-- File tab -->
    <div class="tab-panel" id="tab-file">
      <div class="file-drop-zone" id="drop-zone">
        <input type="file" id="file-input" multiple onchange="onFileSelected()">
        <svg class="icon icon-xl drop-icon"><use href="#ic-upload"/></svg>
        <div class="drop-label"><b>Choose files</b> or drag &amp; drop</div>
        <div id="selected-files-info"></div>
      </div>
      <div class="upload-opts">
        <label id="zip-upload-label">
          <label class="toggle-track" style="width:30px;height:16px">
            <input type="checkbox" id="zip-upload">
            <span class="toggle-thumb" style="width:12px;height:12px;top:2px;left:2px"></span>
          </label>
          Bundle as ZIP
        </label>
        <button class="btn btn-primary" id="upload-btn" onclick="uploadFiles()">
          <svg class="icon"><use href="#ic-upload"/></svg> Upload
        </button>
      </div>
      <div style="display:flex;align-items:center;gap:8px;min-height:20px">
        <div id="upload-status" style="flex:1;min-width:0;font-size:13px"></div>
        <button class="btn btn-danger" id="cancel-upload-btn" style="display:none;padding:3px 10px;font-size:12px" onclick="cancelUpload()">✕ Cancel</button>
      </div>
      <progress id="upload-progress" max="100" value="0"></progress>
    </div>
  </div>

  <!-- Save bar -->
  <div class="card" id="save-card">
    <div id="save-bar">
      <div class="save-bar-left">
        <svg class="icon" style="color:var(--muted)"><use href="#ic-folder"/></svg>
        <span class="save-label">Save to:</span>
        <span id="dl-path">Downloads</span>
        <button id="dl-change" onclick="chooseFolder()">Change</button>
      </div>
      <label id="auto-dl-label">
        <label class="toggle-track">
          <input type="checkbox" id="auto-dl">
          <span class="toggle-thumb"></span>
        </label>
        Auto-download
      </label>
    </div>
    <div id="dl-warn"></div>
  </div>

  <!-- Batch status bar -->
  <div id="batch-bar">
    <span id="batch-text">0 / 0 files</span>
    <div id="batch-progress-track"><div id="batch-progress-fill"></div></div>
  </div>

  <!-- Inbox (below send panel) -->
  <div class="section-header" id="inbox-header" style="display:none">
    <span>Inbox</span>
    <span class="badge" id="inbox-badge">0</span>
  </div>
  <ul id="inbox"></ul>
  <div class="empty-state" id="empty-state">
    <svg class="icon icon-xl empty-icon"><use href="#ic-inbox"/></svg>
    <div class="empty-text">Nothing received yet.<br>Messages and files from the phone will appear here.</div>
  </div>

</main>

<script>
  const COLLAPSE_THRESHOLD = 250;

  /* ---- device identity ---- */
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
  const DEVICE_NAME = localStorage.getItem('sender_device_name') || (BROWSER + ' on ' + PLATFORM);

  /* ---- state ---- */
  let appState = 'connecting';
  let pairingTimer = null;
  const pendingTransferCallbacks = new Map();
  let dirHandle = null;
  let inboxCount = 0;

  /* ---- tabs ---- */
  function switchTab(name, btn) {
    document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
    document.querySelectorAll('.tab-panel').forEach(p => p.classList.remove('active'));
    btn.classList.add('active');
    document.getElementById('tab-' + name).classList.add('active');
  }

  /* ---- pairing / state machine ---- */
  function setAppState(s) {
    appState = s;
    const overlay = document.getElementById('pairing-overlay');
    const mainUI  = document.getElementById('main-ui');
    const dot     = document.getElementById('status-dot');
    const stxt    = document.getElementById('status-text');
    clearTimeout(pairingTimer);

    if (s === 'connected') {
      overlay.classList.add('hidden');
      mainUI.hidden = false;
      dot.className = 'ok'; stxt.textContent = 'Connected'; stxt.className = 'ok';
      return;
    }

    overlay.classList.remove('hidden');
    mainUI.hidden = true;
    dot.className = ''; stxt.textContent = ''; stxt.className = '';

    const icons = { connecting:'⊙', pending:'◌', rejected:'✕', timeout:'⚠', kicked:'↩' };
    const msgs  = {
      connecting: ['Connecting…',            ''],
      pending:    ['Waiting for approval',   'Open Sender on the phone and tap Accept'],
      rejected:   ['Connection rejected',    'Reload the page to try again.'],
      timeout:    ['No response from phone', 'The phone may be busy. Reload to retry.'],
      kicked:     ['Disconnected',           'Reload the page to reconnect.']
    };
    const [msg, sub] = msgs[s] || ['…', ''];
    document.getElementById('pair-icon').textContent = icons[s] || '⊙';
    document.getElementById('pair-msg').textContent  = msg;
    document.getElementById('pair-sub').textContent  = sub;
    document.getElementById('pair-retry').hidden = (s === 'connecting' || s === 'pending');
    if (s === 'pending') pairingTimer = setTimeout(() => setAppState('timeout'), 60000);
  }

  /* ---- WebSocket ---- */
  const ws = new WebSocket((location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/socket');
  ws.onopen = () => ws.send(JSON.stringify({
    type: 'hello', deviceId: DEVICE_ID,
    name: DEVICE_NAME, platform: BROWSER + '/' + PLATFORM
  }));
  ws.onclose = () => {
    if (appState === 'connected') {
      document.getElementById('status-dot').className = 'err';
      document.getElementById('status-text').textContent = 'Disconnected — reload to reconnect';
      document.getElementById('status-text').className = '';
    } else if (appState === 'pending') setAppState('timeout');
  };
  ws.onmessage = e => {
    let msg; try { msg = JSON.parse(e.data); } catch { return; }
    if (msg.type === 'welcome')         { setAppState('connected'); return; }
    if (msg.type === 'pending')         { setAppState('pending');   return; }
    if (msg.type === 'rejected')        { setAppState('rejected');  return; }
    if (msg.type === 'kicked')          { setAppState('kicked');    return; }
    if (msg.type === 'text')            { addText(msg.data, msg.from || 'Phone'); return; }
    if (msg.type === 'file_batch')      { _batchTotal = parseInt(msg.count)||0; _batchReceived=0; updateBatchStatus(); return; }
    if (msg.type === 'file')            { addFile(msg.name, parseInt(msg.index)||0); return; }
    if (msg.type === 'transfer_accept') { const cb = pendingTransferCallbacks.get(msg.transferId); if (cb) cb(true);  return; }
    if (msg.type === 'transfer_reject') { const cb = pendingTransferCallbacks.get(msg.transferId); if (cb) cb(false); return; }
    if (msg.type === 'transfer_cancelled') {
      // App cancelled the transfer — abort the active XHR if it matches
      if (_activeXhr && (_activeTransferId === msg.transferId || !msg.transferId)) {
        cancelUpload();
      }
      return;
    }
  };

  /* ---- inbox helpers ---- */
  const inbox = document.getElementById('inbox');

  function bumpInbox() {
    inboxCount++;
    document.getElementById('inbox-header').style.display = 'flex';
    document.getElementById('inbox-badge').textContent = inboxCount;
    document.getElementById('empty-state').style.display = 'none';
  }

  function fileTypeLabel(name) {
    const ext = (name.split('.').pop() || '').toLowerCase();
    const m = { jpg:'IMG', jpeg:'IMG', png:'IMG', gif:'IMG', webp:'IMG', heic:'IMG',
                mp4:'VID', mkv:'VID', mov:'VID', avi:'VID', webm:'VID',
                mp3:'AUD', flac:'AUD', aac:'AUD', wav:'AUD', ogg:'AUD', m4a:'AUD',
                pdf:'PDF', zip:'ZIP', rar:'ZIP', '7z':'ZIP', tar:'ZIP', gz:'ZIP',
                apk:'APK', txt:'TXT', md:'TXT', doc:'DOC', docx:'DOC',
                xls:'XLS', xlsx:'XLS', csv:'CSV', ppt:'PPT', pptx:'PPT' };
    return m[ext] || ext.substring(0,4).toUpperCase() || 'FILE';
  }

  function svgIcon(id) {
    return '<svg class="icon" aria-hidden="true"><use href="#' + id + '"/></svg>';
  }

  /* ---- add text message ---- */
  function isLong(text) {
    return text.length > COLLAPSE_THRESHOLD || (text.match(/\n/g)||[]).length >= 4;
  }

  function makeReadMore(textEl) {
    const btn = document.createElement('button');
    btn.className = 'read-more-btn'; btn.textContent = '▼ Read more';
    function toggle() {
      const c = textEl.classList.toggle('collapsed');
      btn.textContent = c ? '▼ Read more' : '▲ Read less';
    }
    btn.onclick = toggle; textEl.onclick = toggle; textEl.style.cursor='pointer';
    return btn;
  }

  function addText(text, fromName) {
    bumpInbox();
    const long = isLong(text);
    const card = document.createElement('li'); card.className = 'msg-card';

    const hdr = document.createElement('div'); hdr.className = 'msg-card-header';
    const typeIcon = document.createElement('span'); typeIcon.className = 'msg-type-icon';
    typeIcon.innerHTML = svgIcon('ic-msg');
    const from = document.createElement('span'); from.className='msg-from'; from.textContent='from '+fromName;
    hdr.appendChild(typeIcon); hdr.appendChild(from); card.appendChild(hdr);

    const body = document.createElement('div'); body.className = 'msg-card-body';
    const textEl = document.createElement('div');
    textEl.className = 'msg-text' + (long ? ' collapsed' : '');
    textEl.textContent = text; body.appendChild(textEl);
    if (long) body.appendChild(makeReadMore(textEl));
    card.appendChild(body);

    const hr = document.createElement('hr'); hr.className = 'msg-divider'; card.appendChild(hr);

    const acts = document.createElement('div'); acts.className = 'msg-actions';
    const copyBtn = document.createElement('button');
    copyBtn.className='btn btn-secondary';
    copyBtn.innerHTML = svgIcon('ic-copy') + ' Copy';
    copyBtn.onclick = e => { e.stopPropagation(); copyText(text, copyBtn); };

    const saveTxtBtn = document.createElement('button');
    saveTxtBtn.className='btn btn-ghost'; saveTxtBtn.textContent='.txt';
    saveTxtBtn.title='Save as text file';
    saveTxtBtn.onclick = e => {
      e.stopPropagation();
      const blob = new Blob([text], { type: 'text/plain' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href=url; a.download='message_'+Date.now()+'.txt';
      document.body.appendChild(a); a.click(); document.body.removeChild(a);
      URL.revokeObjectURL(url);
    };
    acts.appendChild(copyBtn); acts.appendChild(saveTxtBtn);
    card.appendChild(acts);
    inbox.prepend(card);
  }

  /* ---- batch status ---- */
  let _batchTotal = 0, _batchReceived = 0;

  function updateBatchStatus() {
    const bar = document.getElementById('batch-bar');
    if (_batchTotal <= 1) { bar.classList.remove('active'); return; }
    bar.classList.add('active');
    const queued = _dlQueue.length + _dlActive;
    const done = _batchReceived;
    document.getElementById('batch-text').textContent =
      done + ' / ' + _batchTotal + ' files' + (queued > 0 ? ' · ' + queued + ' downloading' : '');
    const pct = _batchTotal > 0 ? (done / _batchTotal * 100) : 0;
    document.getElementById('batch-progress-fill').style.width = pct + '%';
    if (_batchReceived >= _batchTotal && queued === 0) {
      setTimeout(() => { bar.classList.remove('active'); _batchTotal=0; _batchReceived=0; }, 3000);
    }
  }

  /* ---- add file ---- */
  function addFile(name, index) {
    bumpInbox();
    const autoOn = document.getElementById('auto-dl').checked;
    const hasFsa = !!dirHandle;

    const card = document.createElement('li'); card.className = 'file-card';

    const hdr = document.createElement('div'); hdr.className = 'file-card-header';
    const pill = document.createElement('div'); pill.className='file-type-pill';
    pill.textContent = fileTypeLabel(name);
    const info = document.createElement('div'); info.className='file-info';
    const nameEl = document.createElement('div'); nameEl.className='file-name'; nameEl.textContent=name;
    const metaEl = document.createElement('div'); metaEl.className='file-meta';
    metaEl.textContent = autoOn ? 'Downloading…' : 'Ready to download';
    info.appendChild(nameEl); info.appendChild(metaEl);
    hdr.appendChild(pill); hdr.appendChild(info); card.appendChild(hdr);

    const progressEl = document.createElement('progress');
    progressEl.className='file-dl-progress'; progressEl.max=100; progressEl.value=0;
    card.appendChild(progressEl);

    const hr = document.createElement('hr'); hr.className='file-divider'; card.appendChild(hr);

    const acts = document.createElement('div'); acts.className='file-actions';

    _batchReceived++; updateBatchStatus();

    if (autoOn) {
      queueDownload(name, index, hasFsa ? progressEl : null);
      if (!hasFsa) {
        metaEl.textContent = 'Saving to browser Downloads…';
      }
      const reBtn = document.createElement('button');
      reBtn.className='btn btn-dl';
      reBtn.innerHTML = svgIcon('ic-dl') + ' Re-download';
      reBtn.onclick = () => { metaEl.textContent='Re-downloading…'; triggerDownload(name, index, hasFsa ? progressEl : null); };
      acts.appendChild(reBtn);
    } else {
      metaEl.textContent = 'Tap to download';
      const dlBtn = document.createElement('button');
      dlBtn.className='btn btn-dl';
      dlBtn.innerHTML = svgIcon('ic-dl') + ' Download';
      dlBtn.onclick = () => { metaEl.textContent='Downloading…'; triggerDownload(name, index, hasFsa ? progressEl : null); };
      const discardBtn = document.createElement('button');
      discardBtn.className='btn btn-danger'; discardBtn.textContent='✕ Discard';
      discardBtn.onclick = () => { card.remove(); inboxCount--; document.getElementById('inbox-badge').textContent=inboxCount; };
      acts.appendChild(dlBtn); acts.appendChild(discardBtn);
    }
    card.appendChild(acts);
    inbox.prepend(card);
  }

  /* ---- ZIP creation (pure JS, STORED method) ---- */
  const _crcT = new Uint32Array(256);
  for (let i=0;i<256;i++){let c=i;for(let j=0;j<8;j++)c=c&1?0xEDB88320^(c>>>1):c>>>1;_crcT[i]=c>>>0;}
  function _crc32(buf){let c=0xFFFFFFFF;for(let i=0;i<buf.length;i++)c=_crcT[(c^buf[i])&0xFF]^(c>>>8);return(c^0xFFFFFFFF)>>>0;}
  async function createZipBlob(files, onProgress) {
    const enc=new TextEncoder(),parts=[],central=[];let offset=0;
    for(let i=0;i<files.length;i++){
      const f=files[i],nb=enc.encode(f.name),data=new Uint8Array(await f.arrayBuffer());
      const crc=_crc32(data),sz=data.length;
      const lh=new Uint8Array(30+nb.length),ld=new DataView(lh.buffer);
      ld.setUint32(0,0x04034b50,true);ld.setUint16(4,20,true);ld.setUint32(14,crc,true);
      ld.setUint32(18,sz,true);ld.setUint32(22,sz,true);ld.setUint16(26,nb.length,true);lh.set(nb,30);
      const ce=new Uint8Array(46+nb.length),cd=new DataView(ce.buffer);
      cd.setUint32(0,0x02014b50,true);cd.setUint16(4,20,true);cd.setUint16(6,20,true);
      cd.setUint32(16,crc,true);cd.setUint32(20,sz,true);cd.setUint32(24,sz,true);
      cd.setUint16(28,nb.length,true);cd.setUint32(42,offset,true);ce.set(nb,46);
      parts.push(lh,data);central.push(ce);offset+=lh.length+sz;
      if(onProgress)onProgress((i+1)/files.length);
    }
    const cdSz=central.reduce((s,e)=>s+e.length,0),eocd=new Uint8Array(22),ev=new DataView(eocd.buffer);
    ev.setUint32(0,0x06054b50,true);ev.setUint16(8,files.length,true);ev.setUint16(10,files.length,true);
    ev.setUint32(12,cdSz,true);ev.setUint32(16,offset,true);
    return new Blob([...parts,...central,eocd],{type:'application/zip'});
  }

  /* ---- download queue ---- */
  const _dlQueue=[];let _dlActive=0;const _DL_CONCURRENCY=3;
  async function _runDlTask(task){
    try{await triggerDownload(task.name,task.index,task.progressEl);}catch(_){}
    if(!dirHandle)await new Promise(r=>setTimeout(r,350));
    _dlActive--;_drainDlQueue();updateBatchStatus();
  }
  function _drainDlQueue(){
    const maxSlots=dirHandle?_DL_CONCURRENCY:1;
    while(_dlActive<maxSlots&&_dlQueue.length>0){_dlActive++;_runDlTask(_dlQueue.shift());}
  }
  function queueDownload(name,index,progressEl){
    _dlQueue.push({name,index,progressEl});_drainDlQueue();
  }

  async function triggerDownload(name,index,progressEl){
    if(dirHandle){
      if(progressEl){progressEl.style.display='block';progressEl.value=0;}
      let writable;
      try{
        const resp=await fetch('/file/'+index);
        if(!resp.ok)throw new Error('HTTP '+resp.status);
        const total=parseInt(resp.headers.get('content-length')||'0');
        const fh=await dirHandle.getFileHandle(name,{create:true});
        writable=await fh.createWritable();
        const reader=resp.body.getReader();let rcv=0;
        while(true){const{done,value}=await reader.read();if(done)break;await writable.write(value);rcv+=value.length;if(total>0&&progressEl)progressEl.value=rcv/total*100;}
        await writable.close();
        if(progressEl){progressEl.value=100;setTimeout(()=>progressEl.style.display='none',600);}
      }catch(_){if(writable)writable.abort().catch(()=>{});if(progressEl)progressEl.style.display='none';}
      return;
    }
    if(progressEl)progressEl.style.display='none';
    const a=document.createElement('a');a.href='/file/'+index;a.download=name;
    document.body.appendChild(a);a.click();document.body.removeChild(a);
  }

  /* ---- clipboard ---- */
  function copyText(text,btn){
    if(navigator.clipboard&&window.isSecureContext)
      navigator.clipboard.writeText(text).then(()=>showCopied(btn)).catch(()=>fallbackCopy(text,btn));
    else fallbackCopy(text,btn);
  }
  function fallbackCopy(text,btn){
    const ta=document.createElement('textarea');ta.value=text;
    ta.style.cssText='position:fixed;opacity:0;top:0;left:0;width:1px;height:1px;';
    document.body.appendChild(ta);ta.focus();ta.select();
    try{document.execCommand('copy');showCopied(btn);}catch(_){}
    document.body.removeChild(ta);
  }
  function showCopied(btn){
    const prev=btn.innerHTML;btn.innerHTML=svgIcon('ic-copy')+' Copied';btn.classList.add('copied');
    setTimeout(()=>{btn.innerHTML=prev;btn.classList.remove('copied');},1500);
  }

  /* ---- textarea auto-expand ---- */
  const replyInput=document.getElementById('reply-input');
  replyInput.addEventListener('input',function(){
    this.style.height='auto';this.style.height=Math.min(this.scrollHeight,260)+'px';
  });
  replyInput.addEventListener('keydown',e=>{
    if(e.key==='Enter'&&e.ctrlKey){e.preventDefault();sendReply();}
  });

  /* ---- send helpers ---- */
  function sendReply(){
    const msg=replyInput.value.trim();
    if(msg&&ws.readyState===WebSocket.OPEN){ws.send(msg);replyInput.value='';replyInput.style.height='';}
  }
  function sendTextAsFile(){
    const msg=replyInput.value.trim();if(!msg)return;
    const blob=new Blob([msg],{type:'text/plain'});
    const name='message_'+Date.now()+'.txt';
    const us=document.getElementById('upload-status');
    us.textContent='Sending…';
    uploadRaw('/upload?name='+encodeURIComponent(name),blob,()=>{}).then(()=>{
      replyInput.value='';replyInput.style.height='';
      us.textContent='Done!';setTimeout(()=>us.textContent='',2000);
    });
  }

  /* ---- file selection display ---- */
  function onFileSelected(){
    const files=Array.from(document.getElementById('file-input').files);
    const info=document.getElementById('selected-files-info');
    if(files.length===0){info.textContent='';return;}
    const totalBytes=files.reduce((s,f)=>s+f.size,0);
    info.textContent=files.length+' file'+(files.length>1?'s':'')+' · '+fmtBytes(totalBytes);
  }
  function fmtBytes(b){
    if(b<1024)return b+' B';if(b<1048576)return (b/1024).toFixed(1)+' KB';
    if(b<1073741824)return (b/1048576).toFixed(1)+' MB';return (b/1073741824).toFixed(2)+' GB';
  }

  /* ---- drag & drop ---- */
  const dropZone=document.getElementById('drop-zone');
  dropZone.addEventListener('dragover',e=>{e.preventDefault();dropZone.classList.add('drag');});
  dropZone.addEventListener('dragleave',()=>dropZone.classList.remove('drag'));
  dropZone.addEventListener('drop',e=>{
    e.preventDefault();dropZone.classList.remove('drag');
    const dt=e.dataTransfer;
    if(dt&&dt.files.length>0){
      document.getElementById('file-input').files=dt.files;
      onFileSelected();
    }
  });

  /* ---- raw binary upload — gives XHR real progress events on the upload side ---- */
  let _activeXhr = null;
  let _activeTransferId = null;

  function _setCancelBtn(visible) {
    document.getElementById('cancel-upload-btn').style.display = visible ? '' : 'none';
  }

  function cancelUpload() {
    if (!_activeXhr) return;
    const tid = _activeTransferId;
    _activeXhr.abort();
    _activeXhr = null;
    _activeTransferId = null;
    _setCancelBtn(false);
    if (tid && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({type:'cancel_upload', transferId: tid}));
    }
    const us = document.getElementById('upload-status');
    us.textContent = 'Cancelled';
    setTimeout(() => { us.textContent = ''; }, 2000);
    const upProg = document.getElementById('upload-progress');
    upProg.value = 0;
    upProg.style.display = 'none';
  }

  function uploadRaw(url, fileOrBlob, onProgress) {
    // Extract transferId from the URL query string so we can cancel via WS if needed
    const params = new URL(url, window.location.href).searchParams;
    const tid = params.get('transferId');
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('POST', url);
      xhr.setRequestHeader('Content-Type', 'application/octet-stream');
      _activeXhr = xhr;
      _activeTransferId = tid;
      _setCancelBtn(true);
      xhr.upload.addEventListener('progress', e => { if (e.lengthComputable) onProgress(e.loaded / e.total); });
      xhr.onload = () => {
        _activeXhr = null; _activeTransferId = null; _setCancelBtn(false);
        onProgress(1); resolve(xhr.status);
      };
      xhr.onerror = () => {
        _activeXhr = null; _activeTransferId = null; _setCancelBtn(false);
        reject(new Error('Network error'));
      };
      xhr.onabort = () => {
        _activeXhr = null; _activeTransferId = null; _setCancelBtn(false);
        reject(new Error('Aborted'));
      };
      xhr.send(fileOrBlob);
    });
  }

  async function _announceAndWait(transferId,fileList,totalBytes,us){
    us.textContent='Waiting for approval…';
    ws.send(JSON.stringify({type:'transfer_announce',transferId,files:fileList.map(f=>({name:f.name,size:f.size})),totalBytes}));
    return new Promise(resolve=>{
      const timer=setTimeout(()=>{pendingTransferCallbacks.delete(transferId);resolve(false);},60000);
      pendingTransferCallbacks.set(transferId,ok=>{clearTimeout(timer);pendingTransferCallbacks.delete(transferId);resolve(ok);});
    });
  }

  async function uploadFiles(){
    const input=document.getElementById('file-input');
    const us=document.getElementById('upload-status');
    const upProg=document.getElementById('upload-progress');
    if(!input.files.length)return;
    if(ws.readyState!==WebSocket.OPEN){us.textContent='Not connected';return;}

    const files=Array.from(input.files);
    const zipMode=document.getElementById('zip-upload').checked&&files.length>1;
    const mkId=()=>(typeof crypto!=='undefined'&&crypto.randomUUID)?crypto.randomUUID():Date.now().toString(36)+Math.random().toString(36).slice(2);

    try {
      if(zipMode){
        upProg.style.display='block';upProg.value=0;
        us.textContent='Creating ZIP…';
        const zipName='archive_'+Date.now()+'.zip';
        const zipBlob=await createZipBlob(files,p=>{upProg.value=p*50;});
        const transferId=mkId();
        const accepted=await _announceAndWait(transferId,[{name:zipName,size:zipBlob.size}],zipBlob.size,us);
        if(!accepted){upProg.style.display='none';us.textContent='Rejected';setTimeout(()=>us.textContent='',3000);return;}
        us.textContent='Uploading ZIP…';
        await uploadRaw(
          '/upload?name='+encodeURIComponent(zipName)+'&transferId='+encodeURIComponent(transferId),
          zipBlob, p=>{upProg.value=50+p*50;}
        );
      }else{
        const transferId=mkId();
        const totalBytes=files.reduce((s,f)=>s+f.size,0);
        const accepted=await _announceAndWait(transferId,files,totalBytes,us);
        if(!accepted){us.textContent='Rejected';setTimeout(()=>us.textContent='',3000);return;}
        upProg.style.display='block';upProg.value=0;
        for(let i=0;i<files.length;i++){
          us.textContent=(i+1)+'/'+files.length+': '+files[i].name;upProg.value=0;
          await uploadRaw(
            '/upload?name='+encodeURIComponent(files[i].name)+'&transferId='+encodeURIComponent(transferId),
            files[i], p=>{upProg.value=p*100;}
          );
        }
      }
      upProg.value=100;setTimeout(()=>{upProg.style.display='none';},600);
      input.value='';document.getElementById('selected-files-info').textContent='';
      us.textContent='Done!';setTimeout(()=>us.textContent='',2000);
    } catch(err) {
      // Aborted or network error — UI already cleaned up by uploadRaw / cancelUpload
      if (err.message !== 'Aborted') {
        us.textContent = 'Upload failed';
        setTimeout(() => { us.textContent = ''; }, 3000);
      }
      upProg.style.display='none';
    }
  }

  /* ---- download folder settings ---- */
  function showDlWarn(msg){
    const w=document.getElementById('dl-warn');w.textContent=msg;w.style.display='block';
  }
  function hideDlWarn(){document.getElementById('dl-warn').style.display='none';}
  function checkDlReady(){
    const tip='In browser Settings > Downloads, disable "Ask where to save" and set a fixed folder.';
    if(!dirHandle){
      if(!('showDirectoryPicker' in window))showDlWarn('No folder picker support. '+tip);
      else showDlWarn('No save folder chosen. Tap "Change" to pick one. '+tip);
    }else hideDlWarn();
  }
  (function initDlSettings(){
    const autoDlEl=document.getElementById('auto-dl');
    autoDlEl.checked=localStorage.getItem('autoDownload')==='true';
    autoDlEl.addEventListener('change',()=>{
      localStorage.setItem('autoDownload',autoDlEl.checked);
      if(autoDlEl.checked)checkDlReady();else hideDlWarn();
    });
    const saved=localStorage.getItem('dl-folder-name');
    if(saved)document.getElementById('dl-path').textContent=saved+' (re-select to use)';
    if(autoDlEl.checked)checkDlReady();
  })();
  async function chooseFolder(){
    if(!('showDirectoryPicker' in window)){
      alert('Folder picker not supported.\nIn browser Settings > Downloads, set a default save location.');
      return;
    }
    try{
      dirHandle=await window.showDirectoryPicker({mode:'readwrite'});
      document.getElementById('dl-path').textContent=dirHandle.name;
      localStorage.setItem('dl-folder-name',dirHandle.name);
      hideDlWarn();
    }catch(_){}
  }
</script>
</body>
</html>
""".trimIndent()
