// Service worker: owns the recording state and the debugger session.
// It keeps no important data in memory, because Chrome can stop it at any time.
// Recording state lives in chrome.storage.session; video and logs live in the recorder window.
//
// The video is recorded by recorder.html, not an offscreen document: a chooseDesktopMedia
// stream id can only be opened by the page that showed the picker.

importScripts('idb.js', 'report.js', 'quick.js'); // crash safety: recoverPartials(); quick settings

const MAX_BODY_CHARS = 1024 * 1024; // keep at most 1 MB of text per response body
const BODY_TYPES = new Set(['XHR', 'Fetch', 'Document', 'EventSource', 'Other']);
const FORWARDED_EVENTS = new Set([
  'Network.requestWillBeSent',
  'Network.responseReceived',
  'Network.loadingFinished',
  'Network.loadingFailed',
  'Runtime.consoleAPICalled',
  'Runtime.exceptionThrown',
  'Log.entryAdded',
  'Page.frameNavigated',
]);

// Server capture: requests from the recorded tab to the Upload URL's server carry this header, so the
// BlackBox plugin can keep their log lines and other server data (see ARCHITECTURE.md "Server capture").
// Session rules are shared with the incognito copy of the extension: each copy has its own rule.
const CAPTURE_RULE = chrome.extension.inIncognitoContext ? 2 : 1;
const CAPTURE_HEADER = 'X-BlackBox-Id';
const ALL_RESOURCE_TYPES = ['main_frame', 'sub_frame', 'stylesheet', 'script', 'image', 'font', 'object',
  'xmlhttprequest', 'ping', 'csp_report', 'media', 'websocket', 'other'];

// requestId -> resource type, so we only fetch bodies worth keeping.
// If the worker restarts this map is empty and we simply fetch every body.
const resourceTypes = new Map();

// rec = { tabId, windowId, startedAt?, serverCapture? }  (startedAt is set once the user has picked a source)
// Kept under REC_KEY (report.js): 'rec', or 'recIncognito' in the incognito copy of the extension
async function getState() {
  return (await chrome.storage.session.get(REC_KEY))[REC_KEY] || null;
}

function setState(rec) {
  return rec ? chrome.storage.session.set({ [REC_KEY]: rec }) : chrome.storage.session.remove(REC_KEY);
}

function cdp(tabId, method, params = {}) {
  return chrome.debugger.sendCommand({ tabId }, method, params);
}

function toRecorder(msg) {
  return chrome.runtime.sendMessage({ target: 'recorder', ...msg });
}

// Popup "Start": open the recorder window, which shows Chrome's source picker.
// mode: 'tab' (streamId from chrome.tabCapture, no picker) | 'window' | 'screen' (picker limited to that type)
async function start(tabId, mode = 'window', streamId) {
  const rec = await getState();
  if (rec) {
    await chrome.windows.update(rec.windowId, { focused: true }).catch(() => {});
    throw new Error(rec.startedAt ? 'Already recording.' : 'The recorder window is already open.');
  }
  const tab = await chrome.tabs.get(tabId);
  if (!/^(https?|file):/.test(tab.url || '')) {
    throw new Error('Open a normal web page first (chrome:// pages and the Web Store cannot be debugged).');
  }
  const win = await chrome.windows.create({
    url: 'recorder.html?' + new URLSearchParams({ tabId, mode, ...(streamId ? { streamId } : {}) }),
    type: 'popup',
    // Chrome's picker is a dialog inside this window and can't be resized while open: make room for all of it
    width: mode === 'tab' ? 520 : 760,
    height: mode === 'tab' ? 300 : 680,
  });
  await setState({ tabId, windowId: win.id, mode });
  return getState();
}

// Recorder window: the user picked a source and the MediaRecorder is running.
async function sourcePicked(startedAt) {
  const rec = await getState();
  if (!rec) throw new Error('Recording was cancelled.');
  const { tabId } = rec;
  resourceTypes.clear();
  // Save state before enabling domains, so the first events are not dropped.
  await setState({ ...rec, startedAt });
  try {
    await chrome.debugger.attach({ tabId }, '1.3');
    await cdp(tabId, 'Network.enable', { maxPostDataSize: 64 * 1024 });
    await cdp(tabId, 'Runtime.enable'); // also replays console messages already on the page
    await cdp(tabId, 'Log.enable');
    await cdp(tabId, 'Page.enable');
  } catch (e) {
    await setState(null);
    await chrome.debugger.detach({ tabId }).catch(() => {});
    throw new Error('Could not attach the debugger: ' + e.message);
  }
  const serverCapture = await startServerCapture(tabId);
  const now = await getState();
  if (now?.startedAt === startedAt) await setState({ ...now, serverCapture }); // also when off, so the popup can say why
  else if (serverCapture.id) await stopServerCapture(serverCapture); // stopped while the server was answering
  await showRecordingBadge(serverCapture);
  const tab = await chrome.tabs.get(tabId);
  return { url: tab.url, title: tab.title, serverCapture };
}

// Red "REC" while recording; orange when the server capture is off, with the reason in the tooltip.
async function showRecordingBadge(capture) {
  const on = capture?.status === 'on';
  await chrome.action.setBadgeBackgroundColor({ color: on ? '#d93025' : '#e37400' });
  await chrome.action.setBadgeText({ text: 'REC' });
  await chrome.action.setTitle({ title: on ? 'BlackBox: recording' : `BlackBox: recording, server data off (${capture?.message || 'not started'})` });
}

// The badge is shared with the incognito copy of the extension: keep it while the other copy is recording
async function clearBadge() {
  const otherKey = REC_KEY === 'rec' ? 'recIncognito' : 'rec';
  const other = (await chrome.storage.session.get(otherKey))[otherKey];
  if (other?.startedAt) return showRecordingBadge(other.serverCapture);
  await chrome.action.setBadgeText({ text: '' });
  await chrome.action.setTitle({ title: 'BlackBox' });
}

// Asks the Upload URL's server to capture its log lines for this recording, then tags the tab's requests to it.
// Returns { status: 'on', id, server } or { status: 'off', message } (the recording works either way).
async function startServerCapture(tabId) {
  const { uploadUrl } = await chrome.storage.local.get('uploadUrl');
  if (!uploadUrl) return { status: 'off', message: 'No Upload URL in Settings.' };
  const origin = new URL(uploadUrl).origin;
  if (!(await chrome.permissions.contains({ origins: [origin + '/*'] }))) {
    return { status: 'off', message: `No access to ${origin} (Settings > Save).` };
  }
  const id = crypto.randomUUID();
  let slowMs; // the server's "slow" (Glowroot's slow threshold) for the Recordings page's slow count
  let pingMs, lostMs; // the recorder's heartbeat timing (recording-ping); missing from plugins without it: no pings
  let maxRecordingMs; // the hard limit (maxRecordingMinutes); missing from plugins without it: no limit
  try {
    // Quick settings can turn off the plugin's all-server logs and Glowroot data for this recording
    const q = await Quick.get();
    const res = await postAction(uploadUrl, 'recording-start', id, { allServer: String(q.allServer), glowrootData: String(q.glowrootData) });
    const body = await res.json().catch(() => null);
    slowMs = (body?.serverCapture || body?.systemLog)?.slowThresholdMs; // systemLog: plugin 8.2.1 and older
    ({ pingMs, lostMs, maxRecordingMs } = body?.serverCapture || {});
    if (!res.ok) {
      const message = res.status === 401 ? `Not logged in to ${origin}.` : body?.error?.message || `HTTP ${res.status}`;
      return { status: 'off', server: origin, message };
    }
  } catch (e) {
    // fetch() throws a TypeError ("Failed to fetch") for network errors: wrong host or port, server down, certificate
    return { status: 'off', server: origin, message: e instanceof TypeError ? `Could not reach ${origin}` : e.message };
  }
  await chrome.declarativeNetRequest.updateSessionRules({
    removeRuleIds: [CAPTURE_RULE],
    addRules: [{
      id: CAPTURE_RULE,
      priority: 1,
      action: { type: 'modifyHeaders', requestHeaders: [{ header: CAPTURE_HEADER, operation: 'set', value: id }] },
      // TAB_ID_NONE: requests a service worker makes. Joget's PWA service worker (sw.js) re-fetches almost every
      // userview request, and those carry no tab id. A service worker serves all tabs of its origin, so other tabs
      // on the same server are tagged too while recording.
      condition: { tabIds: [tabId, chrome.tabs.TAB_ID_NONE], urlFilter: '|' + origin + '/', resourceTypes: ALL_RESOURCE_TYPES },
    }],
  });
  return { status: 'on', id, server: origin, uploadUrl, slowMs, pingMs, lostMs, maxRecordingMs };
}

function stopTagging() {
  return chrome.declarativeNetRequest.updateSessionRules({ removeRuleIds: [CAPTURE_RULE] }).catch(() => {});
}

// Stops tagging requests and tells the server the capture is over (its lines stay there until the upload).
// A normal stop is sent by the recorder instead, with what the server needs to export the apps (stopOnServer).
async function stopServerCapture(capture) {
  await stopTagging();
  if (!capture?.id) return;
  await postAction(capture.uploadUrl, 'recording-stop', capture.id)
    .catch(() => {}); // the server also stops capturing once the id goes quiet
}

function postAction(uploadUrl, action, id, params) {
  return fetch(uploadUrl + '?' + new URLSearchParams({ action, id, ...params }), { method: 'POST', credentials: 'include' });
}

async function stop(reason) {
  const rec = await getState();
  if (!rec) return null;
  // Clear state first so a second stop (e.g. from onDetach) does nothing.
  await setState(null);
  await clearBadge();
  if (!rec.startedAt) {
    await chrome.windows.remove(rec.windowId).catch(() => {});
    return null;
  }
  await chrome.debugger.detach({ tabId: rec.tabId }).catch(() => {});
  await stopTagging();
  let res;
  try {
    res = await toRecorder({ type: 'stop', reason });
  } finally {
    if (!res?.stopSent) await stopServerCapture(rec.serverCapture); // the recorder had nothing to save, or is gone
  }
  if (res?.error) throw new Error(res.error);
  return res;
}

chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
  if (msg.target !== 'background') return;
  const handlers = {
    'get-state': () => getState(),
    'start': () => start(msg.tabId, msg.mode, msg.streamId),
    'source-picked': () => sourcePicked(msg.startedAt),
    'stop': () => stop(msg.reason || 'user'),
  };
  const fn = handlers[msg.type];
  if (!fn) return;
  fn().then(sendResponse, (e) => sendResponse({ error: e.message || String(e) }));
  return true; // keep the channel open for the async response
});

chrome.debugger.onEvent.addListener(async (source, method, params) => {
  if (!FORWARDED_EVENTS.has(method)) return;
  const rec = await getState();
  if (!rec?.startedAt || source.tabId !== rec.tabId) return;
  const t = Date.now() - rec.startedAt; // ms since start = position in the video

  if (method === 'Network.responseReceived') resourceTypes.set(params.requestId, params.type);

  let body;
  if (method === 'Network.loadingFinished') {
    const type = resourceTypes.get(params.requestId);
    resourceTypes.delete(params.requestId);
    if (!type || BODY_TYPES.has(type)) body = await readBody(rec.tabId, params.requestId);
  }

  toRecorder({ type: 'cdp', method, params, t, body }).catch(() => {});
});

async function readBody(tabId, requestId) {
  try {
    const r = await cdp(tabId, 'Network.getResponseBody', { requestId });
    if (r.base64Encoded) return { skipped: 'binary', size: r.body.length };
    if (r.body.length > MAX_BODY_CHARS) {
      return { text: r.body.slice(0, MAX_BODY_CHARS), truncated: true, size: r.body.length };
    }
    return { text: r.body };
  } catch (e) {
    return { skipped: e.message }; // e.g. body evicted, redirect, or no body
  }
}

// The user clicked "Cancel" on Chrome's debugging banner, or the tab closed.
chrome.debugger.onDetach.addListener(async (source, reason) => {
  const rec = await getState();
  if (rec?.startedAt && source.tabId === rec.tabId) stop('debugger-detached: ' + reason).catch(console.error);
});

chrome.tabs.onRemoved.addListener(async (tabId) => {
  const rec = await getState();
  if (rec?.startedAt && tabId === rec.tabId) stop('tab-closed').catch(console.error);
});

// The recorder window was closed while recording: clean up, and keep what it had saved so far.
chrome.windows.onRemoved.addListener(async (windowId) => {
  const rec = await getState();
  if (!rec || rec.windowId !== windowId) return;
  await setState(null);
  await clearBadge();
  if (rec.startedAt) {
    await chrome.debugger.detach({ tabId: rec.tabId }).catch(() => {});
    await stopServerCapture(rec.serverCapture);
    await recoverPartials().catch((e) => console.warn('Could not recover the recording:', e));
  }
});

// After a browser crash, the interrupted recording is still in IndexedDB: turn it into a normal one.
chrome.runtime.onStartup.addListener(() => {
  recoverPartials().catch((e) => console.warn('Could not recover recordings:', e));
  syncServerPages();
});

// The extension's Theme and Text size on the upload server's BlackBox pages: theme.js runs as a content script on
// <context>/blackbox/* of the Upload URL (<context>/api/blackbox) while BlackBox has access to that server.
// Kept in step with Settings.
let serverPagesSync = Promise.resolve(); // one at a time: Save fires permissions.onAdded and storage.onChanged together

function syncServerPages() {
  serverPagesSync = serverPagesSync.then(async () => {
    await chrome.scripting.unregisterContentScripts(); // the only registered script is this one
    const { uploadUrl } = await chrome.storage.local.get('uploadUrl');
    if (!uploadUrl) return;
    const url = new URL(uploadUrl);
    const context = url.pathname.match(/^(.*)\/api\/blackbox$/)?.[1];
    if (context === undefined) return; // another server's upload endpoint: no BlackBox pages there
    if (!(await chrome.permissions.contains({ origins: [url.origin + '/*'] }))) return;
    await chrome.scripting.registerContentScripts([{
      id: 'server-pages',
      matches: [url.origin + context + '/blackbox/*'],
      js: ['theme.js'],
      runAt: 'document_start', // before the page is drawn
    }]);
  }).catch((e) => console.warn('Could not set Theme and Text size for the upload server\'s pages:', e));
}

chrome.runtime.onInstalled.addListener(syncServerPages);
chrome.permissions.onAdded.addListener(syncServerPages);
chrome.permissions.onRemoved.addListener(syncServerPages);
chrome.storage.onChanged.addListener((c) => { if (c.uploadUrl) syncServerPages(); });
