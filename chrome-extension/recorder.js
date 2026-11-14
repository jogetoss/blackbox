const $ = (id) => document.getElementById(id);
const params = new URLSearchParams(location.search);
const tabId = Number(params.get('tabId'));
const mode = params.get('mode') || 'window';
const TITLE = document.title;

let recorder = null;
let stream = null;
let inputs = [];
let audioCtx = null;
let chunks = [];
let meta = null;
let consoleEntries = [];
let navigations = [];
let network = [];
const inFlight = new Map();
let maskOn = true;
let timer = null;

const SAVE_EVERY = 5000;
let recId = null;
let saveTimer = null;
let saveSeq = 0;
let savedChunks = 0;
let saved = { console: 0, navigations: 0, network: 0 };
let finishedNetwork = [];
let saving = Promise.resolve();

let pingTimer = null;
let pinging = false;
let lastSavedAt = 0;
let videoBytes = 0;
let serverNote = '';

const WARN_BEFORE = 2 * 60 * 1000;
const DEFAULT_LIMIT = 30 * 60 * 1000;
let limitAt = 0;
let limitHit = false;

const VIDEO_BITS_PER_SECOND = 1_500_000;
const SIZE_GUARD = 0.9;
let sizeLimitBytes = 0;
let sizeHit = false;
function sizeLimitReached(bytes, limitBytes) { return limitBytes > 0 && bytes >= limitBytes * SIZE_GUARD; }

chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  if (msg.target !== 'recorder' || sender.id !== chrome.runtime.id
    || !(sender.url || '').startsWith(chrome.runtime.getURL(''))) return;
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

window.addEventListener('beforeunload', (e) => { if (meta) e.preventDefault(); });

pickAndStart().catch((e) => {
  $('error').textContent = e.message || String(e);
  $('status').textContent = 'Not recording.';
  stopInputs();
  meta = null;
});

async function pickAndStart() {
  const { recordPageAudio, recordMic } = await chrome.storage.local.get(['recordPageAudio', 'recordMic']);
  const wantPage = recordPageAudio === true;

  const tabStreamId = params.get('streamId');
  let tabSound = null;
  if (mode !== 'tab' && wantPage && tabStreamId) {
    tabSound = await navigator.mediaDevices.getUserMedia({ audio: { mandatory: { chromeMediaSource: 'tab', chromeMediaSourceId: tabStreamId } } })
      .catch((e) => { console.warn('No tab sound:', e); return null; });
    if (tabSound) { inputs.push(tabSound); playBack(tabSound.getAudioTracks()); }
  }

  const { streamId, canAudio } = mode === 'tab' ? { streamId: tabStreamId, canAudio: true } : await new Promise((resolve) =>
    chrome.desktopCapture.chooseDesktopMedia([mode === 'screen' ? 'screen' : 'window', ...(wantPage ? ['audio'] : [])],
      (id, options) => resolve({ streamId: id, canAudio: !!options?.canRequestAudioTrack })));
  if (!streamId) {
    window.close();
    return;
  }

  const source = { chromeMediaSource: mode === 'tab' ? 'tab' : 'desktop', chromeMediaSourceId: streamId };
  const picked = await navigator.mediaDevices.getUserMedia({
    audio: wantPage && canAudio ? { mandatory: source } : false,
    video: { mandatory: { ...source, maxWidth: 2560, maxHeight: 1600, maxFrameRate: 24 } },
  });
  inputs.push(picked);
  const systemSound = mode !== 'tab' && picked.getAudioTracks().length > 0;
  if (systemSound && tabSound) { tabSound.getTracks().forEach((t) => t.stop()); tabSound = null; }
  const pageTracks = mode === 'tab' || systemSound ? picked.getAudioTracks() : tabSound?.getAudioTracks() || [];
  if (mode === 'tab') playBack(pageTracks);
  let micError = '', mic = null;
  if (recordMic) {
    mic = await navigator.mediaDevices.getUserMedia({ audio: true }).catch((e) => { micError = e.message || e.name; return null; });
    if (mic) inputs.push(mic);
  }
  const audio = mixAudio(pageTracks, mic?.getAudioTracks() || []);
  stream = new MediaStream([...picked.getVideoTracks(), ...audio]);

  const mimeType = (audio.length
    ? ['video/webm;codecs=vp9,opus', 'video/webm;codecs=vp8,opus', 'video/webm']
    : ['video/webm;codecs=vp9', 'video/webm;codecs=vp8', 'video/webm'])
    .find((m) => MediaRecorder.isTypeSupported(m));
  chunks = []; consoleEntries = []; navigations = []; network = []; inFlight.clear();
  recorder = new MediaRecorder(stream, { mimeType, videoBitsPerSecond: VIDEO_BITS_PER_SECOND });
  finishedNetwork = []; savedChunks = 0; saveSeq = 0; saved = { console: 0, navigations: 0, network: 0 };
  videoBytes = 0; serverNote = '';
  sizeLimitBytes = 0; sizeHit = false;
  recorder.ondataavailable = (e) => {
    if (e.data.size > 0) { chunks.push(e.data); videoBytes += e.data.size; }
    if (!sizeHit && sizeLimitReached(videoBytes, sizeLimitBytes)) {
      sizeHit = true;
      chrome.runtime.sendMessage({ target: 'background', type: 'stop', reason: 'size-limit' });
    }
  };
  const startedAt = Date.now();
  recorder.start(1000);

  stream.getVideoTracks()[0].onended = () =>
    chrome.runtime.sendMessage({ target: 'background', type: 'stop', reason: 'sharing-stopped' });

  const settings = stream.getVideoTracks()[0].getSettings();
  meta = { startedAt, settings: { ...settings, displaySurface: settings.displaySurface || (mode === 'tab' ? 'browser' : undefined) } };
  meta.audio = {
    page: pageTracks.length > 0,
    pageSource: pageTracks.length ? (systemSound ? 'system' : 'tab') : undefined,
    mic: !!mic,
    message: [
      wantPage && !pageTracks.length ? 'No page sound: Chrome could not capture the tab' : '',
      micError ? `No microphone: ${micError}` : '',
    ].filter(Boolean).join('; ') || undefined,
  };
  const res = await chrome.runtime.sendMessage({ target: 'background', type: 'source-picked', startedAt });
  if (res?.error) throw new Error(res.error);
  meta.tab = { url: res.url, title: res.title };
  meta.serverCapture = res.serverCapture;
  meta.options = Quick.options(await Quick.get());
  maskOn = Mask.on((await chrome.storage.local.get('quickOffer')).quickOffer, await Quick.get());
  meta.credentialsMasked = maskOn;
  meta.userAgent = navigator.userAgent;
  recId = recordingId(startedAt);
  lastSavedAt = startedAt;
  await idbPartialPut(recId + ':meta', { ...meta, lastSavedAt });
  saveTimer = setInterval(() => { saving = saving.then(savePart).catch((e) => console.warn('Could not save progress:', e)); }, SAVE_EVERY);
  if (meta.serverCapture?.status === 'on' && meta.serverCapture.pingMs > 0) pingTimer = setInterval(ping, meta.serverCapture.pingMs);

  if (meta.serverCapture?.status === 'on' && meta.serverCapture.maxSizeMb > 0) sizeLimitBytes = meta.serverCapture.maxSizeMb * 1024 * 1024;
  limitAt = startedAt + (meta.serverCapture?.maxRecordingMs > 0 ? meta.serverCapture.maxRecordingMs : DEFAULT_LIMIT);

  $('stop').hidden = false;
  const tick = () => {
    const now = Date.now();
    $('status').textContent = `Recording… ${clock(now - startedAt)}${limitAt ? ` (${clock(Math.max(0, limitAt - now))} left)` : ''} (logs from: ${res.title || res.url}; sound: ${soundLabel(meta.audio)})${serverNote ? ' ' + serverNote : ''}`;
    if (!limitAt) return;
    const warn = limitAt - now <= WARN_BEFORE;
    $('limit').hidden = !warn;
    if (warn) $('limit').textContent = `The recording stops and saves itself in ${clock(Math.max(0, limitAt - now))} (time limit).`;
    document.title = warn ? `BlackBox: stops in ${clock(Math.max(0, limitAt - now))}` : TITLE;
    if (now >= limitAt && !limitHit) {
      limitHit = true;
      chrome.runtime.sendMessage({ target: 'background', type: 'stop', reason: 'time-limit' });
    }
  };
  tick();
  timer = setInterval(tick, 1000);

  const win = await chrome.windows.getCurrent();
  await chrome.windows.update(win.id, { state: 'minimized' });
}

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
  lastSavedAt = Date.now();
  await idbPartialPut(recId + ':meta', { ...meta, lastSavedAt });
}

async function ping() {
  const log = meta?.serverCapture;
  if (pinging || !log?.id) return;
  pinging = true;
  try {
    const res = await fetch(log.uploadUrl + '?' + new URLSearchParams({ action: 'recording-ping', id: log.id, lastSavedAt, videoBytes }),
      { method: 'POST', credentials: 'include' });
    if (res.status === 401) {
      clearInterval(pingTimer);
      serverNote = 'Server capture ended: logged out';
      return;
    }
    const out = res.ok ? await res.json().catch(() => null) : null;
    if (out?.state === 'stopped' && meta) {
      clearInterval(pingTimer);
      chrome.runtime.sendMessage({ target: 'background', type: 'stop', reason: 'server-stopped: ' + (out.reason || 'stopped') });
    }
  } catch (e) {
    console.warn('Could not reach the server (heartbeat):', e);
  } finally {
    pinging = false;
  }
}

function mixAudio(pageTracks, micTracks) {
  if (!pageTracks.length && !micTracks.length) return [];
  audioCtx ??= new AudioContext();
  const out = audioCtx.createMediaStreamDestination();
  for (const tracks of [pageTracks, micTracks]) {
    if (tracks.length) audioCtx.createMediaStreamSource(new MediaStream(tracks)).connect(out);
  }
  audioCtx.resume().catch(() => {});
  return out.stream.getAudioTracks();
}

function playBack(tracks) {
  if (!tracks.length) return;
  audioCtx ??= new AudioContext();
  audioCtx.createMediaStreamSource(new MediaStream(tracks)).connect(audioCtx.destination);
  audioCtx.resume().catch(() => {});
}

function clock(ms) {
  const s = Math.floor(ms / 1000);
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
}

function soundLabel(audio) {
  return [audio?.page ? (audio.pageSource === 'tab' && mode !== 'tab' ? 'tab' : 'page') : '', audio?.mic ? 'microphone' : ''].filter(Boolean).join(' + ') || 'none';
}

function stopInputs() {
  stream?.getTracks().forEach((t) => t.stop());
  inputs.forEach((s) => s.getTracks().forEach((t) => t.stop()));
  audioCtx?.close().catch(() => {});
  audioCtx = null;
}

async function stopAndSave(reason) {
  if (!meta) return null;
  clearInterval(timer);
  $('limit').hidden = true;
  document.title = TITLE;
  clearInterval(saveTimer);
  clearInterval(pingTimer);
  await saving;
  $('status').textContent = 'Saving…';
  const stoppedAt = Date.now();
  if (recorder && recorder.state !== 'inactive') {
    await new Promise((resolve) => { recorder.onstop = resolve; recorder.stop(); });
  }
  stopInputs();

  const report = buildReport(meta, consoleEntries, navigations, network, stoppedAt, reason);
  const stopSent = await stopOnServer(report);

  const id = recId;
  meta = null;
  const video = new Blob(chunks, { type: 'video/webm' });
  try {
    await idbAddRecording({ id, savedAt: new Date().toISOString(), video, report, uploaded: null });
  } catch (e) {
    console.warn('Could not keep the recording in the extension, downloading it:', e);
    await downloadFiles(id, video, report);
    await idbPartialClear(id).catch(() => {});
    $('status').textContent = `Could not keep the recording in the extension; saved to Downloads/blackbox/${id}`;
    return { id, downloaded: true, stopSent };
  }
  await idbPartialClear(id);
  const { openOnServer } = await chrome.storage.local.get('openOnServer');
  chrome.tabs.create({ url: chrome.runtime.getURL('viewer.html?' + new URLSearchParams({ id, ...(openOnServer !== false ? { upload: 'server' } : {}) })) });
  $('status').textContent = 'Saved.';
  setTimeout(() => window.close(), 500);
  return { id, stopSent };
}

const STOP_WAIT_MS = 150000;
const STOP_POLL_MS = 3000;

async function stopOnServer(report) {
  const log = meta.serverCapture;
  if (log?.status !== 'on' || !log.uploadUrl) return false;
  $('status').textContent = 'Saving… the server is exporting the apps used';
  const body = {
    network: report.network.map((n) => ({
      t: n.t, url: n.url, type: n.type, status: n.status, failed: n.failed, postData: n.postData,
      requestHeaders: { referer: Object.entries(n.requestHeaders || {}).find(([k]) => k.toLowerCase() === 'referer')?.[1] },
    })),
    console: report.console.filter((c) => c.level === 'error').map((c) => ({ t: c.t, level: c.level })),
  };
  const abort = new AbortController();
  const timer = setTimeout(() => abort.abort(), STOP_WAIT_MS);
  try {
    const res = await fetch(log.uploadUrl + '?' + new URLSearchParams({ action: 'recording-stop', id: log.id, ...Quick.exportParams(meta.options) }),
      { method: 'POST', credentials: 'include', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body), signal: abort.signal });
    if (!res.ok) {
      console.warn('Could not stop the server capture: HTTP', res.status);
      return false;
    }
    const out = await res.json().catch(() => null);
    let e = out?.appExport;
    const until = Date.now() + STOP_WAIT_MS;
    while (e?.state === 'exporting' && Date.now() < until) {
      await new Promise((r) => setTimeout(r, STOP_POLL_MS));
      const poll = await fetch(log.uploadUrl + '?' + new URLSearchParams({ action: 'app-export', id: log.id }),
        { credentials: 'include', signal: abort.signal }).then((r) => r.json()).catch(() => null);
      if (!poll?.appExport) break;
      e = poll.appExport;
    }
    if (e && report.recording.serverCapture) {
      report.recording.serverCapture.appExport = { state: e.state, sampleData: e.sampleData, apps: e.saved?.length };
    }
    return true;
  } catch (e) {
    console.warn('Could not stop the server capture:', e);
    return e.name === 'AbortError';
  } finally {
    clearTimeout(timer);
  }
}

async function downloadFiles(id, video, report) {
  const files = [
    { name: 'recording.webm', blob: video },
    { name: 'session.json', blob: new Blob([JSON.stringify(report, null, 2)], { type: 'application/json' }) },
  ];
  for (const f of files) {
    await chrome.downloads.download({ url: URL.createObjectURL(f.blob), filename: `blackbox/${id}/${f.name}`, saveAs: false });
  }
}

function onCdp({ method, params, t, body }) {
  switch (method) {
    case 'Runtime.consoleAPICalled': {
      const frame = params.stackTrace?.callFrames?.[0];
      consoleEntries.push({
        t,
        source: 'console',
        level: params.type === 'warning' ? 'warn' : params.type,
        text: masked(params.args.map(argToText).join(' ')),
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
        text: masked(d.exception?.description || d.text),
        location: d.url ? `${d.url}:${d.lineNumber + 1}:${d.columnNumber + 1}` : undefined,
      });
      break;
    }
    case 'Log.entryAdded': {
      const e = params.entry;
      consoleEntries.push({ t, source: `browser:${e.source}`, level: e.level === 'warning' ? 'warn' : e.level, text: masked(e.text), location: maskOn ? Mask.url(e.url) : e.url });
      break;
    }
    case 'Page.frameNavigated':
      if (!params.frame.parentId) navigations.push({ t, url: maskOn ? Mask.url(params.frame.url) : params.frame.url });
      break;
    case 'Network.requestWillBeSent':
      onRequest(params, t);
      break;
    case 'Network.responseReceived': {
      const entry = inFlight.get(params.requestId);
      if (entry) maskEntry(Object.assign(entry, responseFields(params.response), { type: params.type }));
      break;
    }
    case 'Network.loadingFinished': {
      const entry = inFlight.get(params.requestId);
      if (!entry) break;
      entry.durationMs = Math.round((params.timestamp - entry._ts) * 1000);
      entry.encodedDataLength = params.encodedDataLength;
      if (body) entry.responseBody = body;
      maskEntry(entry);
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
  const prev = inFlight.get(p.requestId);
  if (prev && p.redirectResponse) {
    maskEntry(Object.assign(prev, responseFields(p.redirectResponse)));
    prev.durationMs = Math.round((p.timestamp - prev._ts) * 1000);
    prev.redirectedTo = maskOn ? Mask.url(p.request.url) : p.request.url;
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
  network.push(maskEntry(entry));
}

const masked = (text) => (maskOn ? Mask.text(text) : text);
const maskEntry = (entry) => (maskOn ? Mask.entry(entry) : entry);

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
  return a.description ?? a.type;
}
