// Service worker: owns the recording state and the debugger session.
// It keeps no important data in memory, because Chrome can stop it at any time.
// Recording state lives in chrome.storage.session; video and logs live in the recorder window.
//
// The video is recorded by recorder.html, not an offscreen document: a chooseDesktopMedia
// stream id can only be opened by the page that showed the picker.

importScripts('idb.js', 'report.js'); // crash safety: recoverPartials()

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

// Server log capture: requests from the recorded tab to the Upload URL's server carry this header, so the
// BlackBox plugin can keep their log lines (see README "Server logs").
const SERVER_LOG_RULE = 1;
const SERVER_LOG_HEADER = 'X-BlackBox-Id';
const ALL_RESOURCE_TYPES = ['main_frame', 'sub_frame', 'stylesheet', 'script', 'image', 'font', 'object',
  'xmlhttprequest', 'ping', 'csp_report', 'media', 'websocket', 'other'];

// requestId -> resource type, so we only fetch bodies worth keeping.
// If the worker restarts this map is empty and we simply fetch every body.
const resourceTypes = new Map();

// rec = { tabId, windowId, startedAt?, serverLog? }  (startedAt is set once the user has picked a source)
async function getState() {
  return (await chrome.storage.session.get('rec')).rec || null;
}

function setState(rec) {
  return rec ? chrome.storage.session.set({ rec }) : chrome.storage.session.remove('rec');
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
  const serverLog = await startServerLog(tabId);
  if (serverLog.id) {
    const now = await getState();
    if (now?.startedAt === startedAt) await setState({ ...now, serverLog });
    else await stopServerLog(serverLog); // stopped while the server was answering
  }
  await chrome.action.setBadgeBackgroundColor({ color: '#d93025' });
  await chrome.action.setBadgeText({ text: 'REC' });
  const tab = await chrome.tabs.get(tabId);
  return { url: tab.url, title: tab.title, serverLog };
}

// Asks the Upload URL's server to capture its log lines for this recording, then tags the tab's requests to it.
// Returns { status: 'on', id, server } or { status: 'off', message } (the recording works either way).
async function startServerLog(tabId) {
  const { uploadUrl } = await chrome.storage.local.get('uploadUrl');
  if (!uploadUrl) return { status: 'off', message: 'No Upload URL in Settings.' };
  const origin = new URL(uploadUrl).origin;
  if (!(await chrome.permissions.contains({ origins: [origin + '/*'] }))) {
    return { status: 'off', message: `No access to ${origin} (Settings > Save).` };
  }
  const id = crypto.randomUUID();
  try {
    const res = await postAction(uploadUrl, 'recording-start', 'serverlog-start', id);
    const body = await res.json().catch(() => null);
    if (!res.ok) {
      const message = res.status === 401 ? `Not logged in to ${origin}.` : body?.error?.message || `HTTP ${res.status}`;
      return { status: 'off', server: origin, message };
    }
  } catch (e) {
    return { status: 'off', server: origin, message: e.message };
  }
  await chrome.declarativeNetRequest.updateSessionRules({
    removeRuleIds: [SERVER_LOG_RULE],
    addRules: [{
      id: SERVER_LOG_RULE,
      priority: 1,
      action: { type: 'modifyHeaders', requestHeaders: [{ header: SERVER_LOG_HEADER, operation: 'set', value: id }] },
      // TAB_ID_NONE: requests a service worker makes. Joget's PWA service worker (sw.js) re-fetches almost every
      // userview request, and those carry no tab id. A service worker serves all tabs of its origin, so other tabs
      // on the same server are tagged too while recording.
      condition: { tabIds: [tabId, chrome.tabs.TAB_ID_NONE], urlFilter: '|' + origin + '/', resourceTypes: ALL_RESOURCE_TYPES },
    }],
  });
  return { status: 'on', id, server: origin, uploadUrl };
}

// Stops tagging requests and tells the server the capture is over (its lines stay there until the upload).
async function stopServerLog(serverLog) {
  await chrome.declarativeNetRequest.updateSessionRules({ removeRuleIds: [SERVER_LOG_RULE] }).catch(() => {});
  if (!serverLog?.id) return;
  await postAction(serverLog.uploadUrl, 'recording-stop', 'serverlog-stop', serverLog.id)
    .catch(() => {}); // the server also stops capturing once the id goes quiet
}

// POSTs action; a server with an older BlackBox plugin only knows the old name and answers 400 "Unknown action"
async function postAction(uploadUrl, action, oldName, id) {
  const send = (a) => fetch(uploadUrl + '?' + new URLSearchParams({ action: a, id }), { method: 'POST', credentials: 'include' });
  const res = await send(action);
  if (res.status !== 400) return res;
  const body = await res.clone().json().catch(() => null);
  return body?.error?.message === 'Unknown action.' ? send(oldName) : res;
}

async function stop(reason) {
  const rec = await getState();
  if (!rec) return null;
  // Clear state first so a second stop (e.g. from onDetach) does nothing.
  await setState(null);
  await chrome.action.setBadgeText({ text: '' });
  if (!rec.startedAt) {
    await chrome.windows.remove(rec.windowId).catch(() => {});
    return null;
  }
  await chrome.debugger.detach({ tabId: rec.tabId }).catch(() => {});
  await stopServerLog(rec.serverLog);
  const res = await toRecorder({ type: 'stop', reason });
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
  await chrome.action.setBadgeText({ text: '' });
  if (rec.startedAt) {
    await chrome.debugger.detach({ tabId: rec.tabId }).catch(() => {});
    await stopServerLog(rec.serverLog);
    await recoverPartials().catch((e) => console.warn('Could not recover the recording:', e));
  }
});

// After a browser crash, the interrupted recording is still in IndexedDB: turn it into a normal one.
chrome.runtime.onStartup.addListener(() => {
  recoverPartials().catch((e) => console.warn('Could not recover recordings:', e));
});
