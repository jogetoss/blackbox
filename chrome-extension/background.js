importScripts('idb.js', 'report.js', 'quick.js');

const MAX_BODY_CHARS = 1024 * 1024;
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

const CAPTURE_RULE = chrome.extension.inIncognitoContext ? 2 : 1;
const CAPTURE_HEADER = 'X-BlackBox-Id';
const ALL_RESOURCE_TYPES = ['main_frame', 'sub_frame', 'stylesheet', 'script', 'image', 'font', 'object',
  'xmlhttprequest', 'ping', 'csp_report', 'media', 'websocket', 'other'];

const resourceTypes = new Map();

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
    width: mode === 'tab' ? 520 : 760,
    height: mode === 'tab' ? 300 : 680,
  });
  await setState({ tabId, windowId: win.id, mode });
  return getState();
}

async function sourcePicked(startedAt) {
  const rec = await getState();
  if (!rec) throw new Error('Recording was cancelled.');
  const { tabId } = rec;
  resourceTypes.clear();
  await setState({ ...rec, startedAt });
  try {
    await chrome.debugger.attach({ tabId }, '1.3');
    await cdp(tabId, 'Network.enable', { maxPostDataSize: 64 * 1024 });
    await cdp(tabId, 'Runtime.enable');
    await cdp(tabId, 'Log.enable');
    await cdp(tabId, 'Page.enable');
  } catch (e) {
    await setState(null);
    await chrome.debugger.detach({ tabId }).catch(() => {});
    throw new Error('Could not attach the debugger: ' + e.message);
  }
  const serverCapture = await startServerCapture(tabId);
  const now = await getState();
  if (now?.startedAt === startedAt) await setState({ ...now, serverCapture });
  else if (serverCapture.id) await stopServerCapture(serverCapture);
  await showRecordingBadge(serverCapture);
  const tab = await chrome.tabs.get(tabId);
  return { url: tab.url, title: tab.title, serverCapture };
}

async function showRecordingBadge(capture) {
  const on = capture?.status === 'on';
  await chrome.action.setBadgeBackgroundColor({ color: on ? '#d93025' : '#e37400' });
  await chrome.action.setBadgeText({ text: 'REC' });
  await chrome.action.setTitle({ title: on ? 'BlackBox: recording' : `BlackBox: recording, server data off (${capture?.message || 'not started'})` });
}

async function clearBadge() {
  const otherKey = REC_KEY === 'rec' ? 'recIncognito' : 'rec';
  const other = (await chrome.storage.session.get(otherKey))[otherKey];
  if (other?.startedAt) return showRecordingBadge(other.serverCapture);
  await chrome.action.setBadgeText({ text: '' });
  await chrome.action.setTitle({ title: 'BlackBox' });
}

async function startServerCapture(tabId) {
  const { uploadUrl } = await chrome.storage.local.get('uploadUrl');
  if (!uploadUrl) return { status: 'off', message: 'No Upload URL in Settings.' };
  const origin = new URL(uploadUrl).origin;
  if (!(await chrome.permissions.contains({ origins: [origin + '/*'] }))) {
    return { status: 'off', message: `No access to ${origin} (Settings > Save).` };
  }
  const id = crypto.randomUUID();
  let slowMs;
  let pingMs, lostMs;
  let maxSizeMb;
  let maxRecordingMs;
  try {
    const q = await Quick.get();
    const res = await postAction(uploadUrl, 'recording-start', id, { allServer: String(q.allServer),
      gauges: String(q.gauges), traces: String(q.traces), otherTraces: String(q.traces && q.otherTraces),
      glowrootData: String(q.glowrootData), systemSecrets: String(q.systemSecrets),
      license: String(q.license) });
    const body = await res.json().catch(() => null);
    slowMs = (body?.serverCapture || body?.systemLog)?.slowThresholdMs;
    ({ pingMs, lostMs, maxRecordingMs, maxSizeMb } = body?.serverCapture || {});
    if (!res.ok) {
      const message = res.status === 401 ? `Not logged in to ${origin}.` : body?.error?.message || `HTTP ${res.status}`;
      return { status: 'off', server: origin, message, maxRecordingMs };
    }
  } catch (e) {
    return { status: 'off', server: origin, message: e instanceof TypeError ? `Could not reach ${origin}` : e.message };
  }
  await chrome.declarativeNetRequest.updateSessionRules({
    removeRuleIds: [CAPTURE_RULE],
    addRules: [{
      id: CAPTURE_RULE,
      priority: 1,
      action: { type: 'modifyHeaders', requestHeaders: [{ header: CAPTURE_HEADER, operation: 'set', value: id }] },
      condition: { tabIds: [tabId, chrome.tabs.TAB_ID_NONE], urlFilter: '|' + origin + '/', resourceTypes: ALL_RESOURCE_TYPES },
    }],
  });
  return { status: 'on', id, server: origin, uploadUrl, slowMs, pingMs, lostMs, maxRecordingMs, maxSizeMb };
}

function stopTagging() {
  return chrome.declarativeNetRequest.updateSessionRules({ removeRuleIds: [CAPTURE_RULE] }).catch(() => {});
}

async function stopServerCapture(capture) {
  await stopTagging();
  if (!capture?.id) return;
  await postAction(capture.uploadUrl, 'recording-stop', capture.id)
    .catch(() => {});
}

function postAction(uploadUrl, action, id, params) {
  return fetch(uploadUrl + '?' + new URLSearchParams({ action, id, ...params }), { method: 'POST', credentials: 'include' });
}

async function stop(reason) {
  const rec = await getState();
  if (!rec) return null;
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
    if (!res?.stopSent) await stopServerCapture(rec.serverCapture);
  }
  if (res?.error) throw new Error(res.error);
  return res;
}

chrome.storage.local.setAccessLevel({ accessLevel: 'TRUSTED_CONTEXTS' })
  .catch((e) => console.warn('Could not keep the settings from content scripts:', e));

const fromExtension = (sender) => sender.id === chrome.runtime.id && (sender.url || '').startsWith(chrome.runtime.getURL(''));

chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  if (msg.target !== 'background' || sender.id !== chrome.runtime.id) return;
  if (msg.type === 'display') {
    chrome.storage.local.get(['theme', 'textSize']).then(({ theme, textSize }) => sendResponse({ theme, textSize }),
      () => sendResponse(null));
    return true;
  }
  if (!fromExtension(sender)) return;
  const handlers = {
    'get-state': () => getState(),
    'start': () => start(msg.tabId, msg.mode, msg.streamId),
    'source-picked': () => sourcePicked(msg.startedAt),
    'stop': () => stop(msg.reason || 'user'),
  };
  const fn = handlers[msg.type];
  if (!fn) return;
  fn().then(sendResponse, (e) => sendResponse({ error: e.message || String(e) }));
  return true;
});

chrome.debugger.onEvent.addListener(async (source, method, params) => {
  if (!FORWARDED_EVENTS.has(method)) return;
  const rec = await getState();
  if (!rec?.startedAt || source.tabId !== rec.tabId) return;
  const t = Date.now() - rec.startedAt;

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
    return { skipped: e.message };
  }
}

chrome.debugger.onDetach.addListener(async (source, reason) => {
  const rec = await getState();
  if (rec?.startedAt && source.tabId === rec.tabId) stop('debugger-detached: ' + reason).catch(console.error);
});

chrome.tabs.onRemoved.addListener(async (tabId) => {
  const rec = await getState();
  if (rec?.startedAt && tabId === rec.tabId) stop('tab-closed').catch(console.error);
});

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

chrome.runtime.onStartup.addListener(() => {
  recoverPartials().catch((e) => console.warn('Could not recover recordings:', e));
  syncServerPages();
});

let serverPagesSync = Promise.resolve();

function syncServerPages() {
  serverPagesSync = serverPagesSync.then(async () => {
    await chrome.scripting.unregisterContentScripts();
    const { uploadUrl } = await chrome.storage.local.get('uploadUrl');
    if (!uploadUrl) return;
    const url = new URL(uploadUrl);
    const context = url.pathname.match(/^(.*)\/api\/blackbox$/)?.[1];
    if (context === undefined) return;
    if (!(await chrome.permissions.contains({ origins: [url.origin + '/*'] }))) return;
    await chrome.scripting.registerContentScripts([{
      id: 'server-pages',
      matches: [url.origin + context + '/blackbox/*'],
      js: ['theme.js'],
      runAt: 'document_start',
    }]);
  }).catch((e) => console.warn('Could not set Theme and Text size for the upload server\'s pages:', e));
}

chrome.runtime.onInstalled.addListener(syncServerPages);
chrome.permissions.onAdded.addListener(syncServerPages);
chrome.permissions.onRemoved.addListener(syncServerPages);
chrome.storage.onChanged.addListener((c) => { if (c.uploadUrl) syncServerPages(); });
