// Recorder window: shows Chrome's source picker, records the video, collects console/network
// events from the service worker, and keeps the recording in IndexedDB when it stops.

const $ = (id) => document.getElementById(id);
const params = new URLSearchParams(location.search);
const tabId = Number(params.get('tabId'));
const mode = params.get('mode') || 'window'; // 'tab' | 'window' | 'screen'

let recorder = null;
let stream = null;
let chunks = [];
let meta = null;
let consoleEntries = [];
let navigations = [];
let network = [];             // finished and in-flight entries, in start order
const inFlight = new Map();   // requestId -> entry
let timer = null;

// Crash safety: every SAVE_EVERY ms the new video chunks and log entries go to IndexedDB (idb.js "partial").
const SAVE_EVERY = 5000;
let recId = null;
let saveTimer = null;
let saveSeq = 0;
let savedChunks = 0;          // chunks[0 .. savedChunks-1] are saved
let saved = { console: 0, navigations: 0, network: 0 };
let finishedNetwork = [];     // network entries in the order they finished (only finished ones are saved)
let saving = Promise.resolve();

chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
  if (msg.target !== 'recorder') return;
  if (msg.type === 'cdp') { if (meta) onCdp(msg); return; }
  if (msg.type === 'stop') {
    stopAndSave(msg.reason).then(sendResponse, (e) => sendResponse({ error: e.message || String(e) }));
    return true;
  }
});

$('stop').onclick = () => {
  $('stop').disabled = true;
  chrome.runtime.sendMessage({ target: 'background', type: 'stop', reason: 'user' });
};

// Closing this window while recording loses the data, so ask first.
window.addEventListener('beforeunload', (e) => { if (meta) e.preventDefault(); });

pickAndStart().catch((e) => {
  $('error').textContent = e.message || String(e);
  $('status').textContent = 'Not recording.';
  stream?.getTracks().forEach((t) => t.stop());
  meta = null;
});

async function pickAndStart() {
  // 'tab': the popup already got a stream id from chrome.tabCapture (no picker). Otherwise show Chrome's picker,
  // limited to windows or screens.
  const streamId = mode === 'tab' ? params.get('streamId') : await new Promise((resolve) =>
    chrome.desktopCapture.chooseDesktopMedia([mode === 'screen' ? 'screen' : 'window'], (id) => resolve(id)));
  if (!streamId) {
    // Picker cancelled: closing the window also clears the state in the service worker.
    window.close();
    return;
  }

  stream = await navigator.mediaDevices.getUserMedia({
    audio: false,
    video: {
      mandatory: {
        chromeMediaSource: mode === 'tab' ? 'tab' : 'desktop',
        chromeMediaSourceId: streamId,
        maxWidth: 2560,
        maxHeight: 1600,
        maxFrameRate: 24,
      },
    },
  });

  const mimeType = ['video/webm;codecs=vp9', 'video/webm;codecs=vp8', 'video/webm']
    .find((m) => MediaRecorder.isTypeSupported(m));
  chunks = []; consoleEntries = []; navigations = []; network = []; inFlight.clear();
  recorder = new MediaRecorder(stream, { mimeType });
  finishedNetwork = []; savedChunks = 0; saveSeq = 0; saved = { console: 0, navigations: 0, network: 0 };
  recorder.ondataavailable = (e) => { if (e.data.size > 0) chunks.push(e.data); };
  const startedAt = Date.now();
  recorder.start(1000); // one chunk per second

  // Chrome's "Stop sharing" bar ends the track: treat it as a normal stop.
  stream.getVideoTracks()[0].onended = () =>
    chrome.runtime.sendMessage({ target: 'background', type: 'stop', reason: 'sharing-stopped' });

  const settings = stream.getVideoTracks()[0].getSettings();
  meta = { startedAt, settings: { ...settings, displaySurface: settings.displaySurface || (mode === 'tab' ? 'browser' : undefined) } };
  const res = await chrome.runtime.sendMessage({ target: 'background', type: 'source-picked', startedAt });
  if (res?.error) throw new Error(res.error);
  meta.tab = res;
  meta.userAgent = navigator.userAgent;
  recId = recordingId(startedAt);
  await idbPartialPut(recId + ':meta', { ...meta, lastSavedAt: startedAt });
  saveTimer = setInterval(() => { saving = saving.then(savePart).catch((e) => console.warn('Could not save progress:', e)); }, SAVE_EVERY);

  $('stop').hidden = false;
  const tick = () => {
    const s = Math.floor((Date.now() - startedAt) / 1000);
    $('status').textContent = `Recording… ${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')} (logs from: ${res.title || res.url})`;
  };
  tick();
  timer = setInterval(tick, 1000);

  // Get out of the way; recording continues while minimized.
  const win = await chrome.windows.getCurrent();
  await chrome.windows.update(win.id, { state: 'minimized' });
}

// Saves what is new since the last save, so a crash loses at most SAVE_EVERY ms.
async function savePart() {
  if (!meta || !recId) return;
  const n = saveSeq++;
  const newChunks = chunks.slice(savedChunks);
  const logs = {
    console: consoleEntries.slice(saved.console),
    navigations: navigations.slice(saved.navigations),
    network: finishedNetwork.slice(saved.network),
  };
  if (newChunks.length) await idbPartialPut(`${recId}:v:${n}`, new Blob(newChunks, { type: 'video/webm' }));
  if (logs.console.length || logs.navigations.length || logs.network.length) await idbPartialPut(`${recId}:l:${n}`, logs);
  savedChunks += newChunks.length;
  saved = { console: saved.console + logs.console.length, navigations: saved.navigations + logs.navigations.length, network: saved.network + logs.network.length };
  await idbPartialPut(recId + ':meta', { ...meta, lastSavedAt: Date.now() });
}

async function stopAndSave(reason) {
  if (!meta) return null;
  clearInterval(timer);
  clearInterval(saveTimer);
  await saving; // let a save in progress finish before the parts are cleared
  $('status').textContent = 'Saving…';
  const stoppedAt = Date.now();
  if (recorder && recorder.state !== 'inactive') {
    await new Promise((resolve) => { recorder.onstop = resolve; recorder.stop(); });
  }
  stream?.getTracks().forEach((t) => t.stop());

  const report = buildReport(meta, consoleEntries, navigations, network, stoppedAt, reason);

  // Nothing is downloaded: the recording stays in the extension until the user uploads or downloads it.
  const id = recId;
  meta = null; // allow the window to close without the "leave page" prompt
  const video = new Blob(chunks, { type: 'video/webm' });
  try {
    await idbAddRecording({ id, savedAt: new Date().toISOString(), video, report, uploaded: null });
  } catch (e) {
    // Could not keep it (e.g. disk full): download it instead so the recording is not lost
    console.warn('Could not keep the recording in the extension, downloading it:', e);
    await downloadFiles(id, video, report);
    await idbPartialClear(id).catch(() => {});
    $('status').textContent = `Could not keep the recording in the extension; saved to Downloads/blackbox/${id}`;
    return { id, downloaded: true };
  }
  await idbPartialClear(id); // saved in full: the crash-safety copy is no longer needed
  chrome.tabs.create({ url: chrome.runtime.getURL('viewer.html?id=' + encodeURIComponent(id)) });
  $('status').textContent = 'Saved.';
  setTimeout(() => window.close(), 500);
  return { id };
}

async function downloadFiles(id, video, report) {
  const files = [
    { name: 'recording.webm', blob: video },
    { name: 'session.json', blob: new Blob([JSON.stringify(report, null, 2)], { type: 'application/json' }) },
  ];
  for (const f of files) {
    // The Blob URL must outlive the download, so this window stays open (the user closes it)
    await chrome.downloads.download({ url: URL.createObjectURL(f.blob), filename: `blackbox/${id}/${f.name}`, saveAs: false });
  }
}

// ---- CDP event handling ----

function onCdp({ method, params, t, body }) {
  switch (method) {
    case 'Runtime.consoleAPICalled': {
      const frame = params.stackTrace?.callFrames?.[0];
      consoleEntries.push({
        t,
        source: 'console',
        level: params.type === 'warning' ? 'warn' : params.type,
        text: params.args.map(argToText).join(' '),
        location: frame ? `${frame.url}:${frame.lineNumber + 1}:${frame.columnNumber + 1}` : undefined,
      });
      break;
    }
    case 'Runtime.exceptionThrown': {
      const d = params.exceptionDetails;
      consoleEntries.push({
        t,
        source: 'exception',
        level: 'error',
        text: d.exception?.description || d.text,
        location: d.url ? `${d.url}:${d.lineNumber + 1}:${d.columnNumber + 1}` : undefined,
      });
      break;
    }
    case 'Log.entryAdded': {
      // Browser-side messages: failed resource loads, CSP violations, deprecations...
      const e = params.entry;
      consoleEntries.push({ t, source: `browser:${e.source}`, level: e.level === 'warning' ? 'warn' : e.level, text: e.text, location: e.url });
      break;
    }
    case 'Page.frameNavigated':
      if (!params.frame.parentId) navigations.push({ t, url: params.frame.url });
      break;
    case 'Network.requestWillBeSent':
      onRequest(params, t);
      break;
    case 'Network.responseReceived': {
      const entry = inFlight.get(params.requestId);
      if (entry) Object.assign(entry, responseFields(params.response), { type: params.type });
      break;
    }
    case 'Network.loadingFinished': {
      const entry = inFlight.get(params.requestId);
      if (!entry) break;
      entry.durationMs = Math.round((params.timestamp - entry._ts) * 1000);
      entry.encodedDataLength = params.encodedDataLength;
      if (body) entry.responseBody = body;
      finish(params.requestId);
      break;
    }
    case 'Network.loadingFailed': {
      const entry = inFlight.get(params.requestId);
      if (!entry) break;
      entry.durationMs = Math.round((params.timestamp - entry._ts) * 1000);
      entry.failed = true;
      entry.errorText = params.canceled ? 'canceled' : params.errorText;
      if (params.blockedReason) entry.blockedReason = params.blockedReason;
      finish(params.requestId);
      break;
    }
  }
}

function onRequest(p, t) {
  // A redirect reuses the same requestId: close the previous hop first.
  const prev = inFlight.get(p.requestId);
  if (prev && p.redirectResponse) {
    Object.assign(prev, responseFields(p.redirectResponse));
    prev.durationMs = Math.round((p.timestamp - prev._ts) * 1000);
    prev.redirectedTo = p.request.url;
    finish(p.requestId);
  }
  const entry = {
    t,
    requestId: p.requestId,
    method: p.request.method,
    url: p.request.url,
    type: p.type,
    initiator: p.initiator?.type,
    requestHeaders: p.request.headers,
    postData: p.request.postData,
    hasPostData: p.request.hasPostData || undefined,
    _ts: p.timestamp,
  };
  inFlight.set(p.requestId, entry);
  network.push(entry);
}

function responseFields(r) {
  return {
    status: r.status,
    statusText: r.statusText,
    mimeType: r.mimeType,
    responseHeaders: r.headers,
    remoteAddress: r.remoteIPAddress ? `${r.remoteIPAddress}:${r.remotePort}` : undefined,
    fromCache: r.fromDiskCache || r.fromServiceWorker || r.fromPrefetchCache || undefined,
  };
}

function finish(requestId) {
  const entry = inFlight.get(requestId);
  if (entry) {
    delete entry._ts;
    finishedNetwork.push(entry);
  }
  inFlight.delete(requestId);
}

function argToText(a) {
  if (a.type === 'string') return a.value;
  if ('value' in a) return JSON.stringify(a.value);
  if (a.unserializableValue) return a.unserializableValue;
  return a.description ?? a.type; // objects, functions, DOM nodes
}
