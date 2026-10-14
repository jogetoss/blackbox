// Replay viewer: plays recording.webm and keeps the console/network lists in sync with the playhead.
// Every recorded entry has t = ms since start = position in the video.
// All recorded text is shown with textContent, never innerHTML: it comes from untrusted pages.

const $ = (id) => document.getElementById(id);
const video = $('video');

let current = { video: null, report: null }; // what the user has loaded so far
let report = null;
let durationMs = 0;
let hasVideo = false;
let manualMs = 0;          // playhead position when there is no video
let fixingDuration = false;
let tab = 'console';
let rows = [];             // { t, el, item } for the visible list, sorted by t
let pastCount = 0;         // rows[0 .. pastCount-1] have t <= playhead
let selectedEl = null;
let videoUrl = null;
let issues = [];           // sorted t of console errors and failed requests

const FILTERS = {
  console: [
    ['all', 'All levels'],
    ['error', 'Errors'],
    ['warn', 'Warnings & errors'],
    ['page', 'Page console only'],
    ['browser', 'Browser messages only'],
  ],
  network: [
    ['all', 'All requests'],
    ['api', 'Fetch / XHR'],
    ['doc', 'Documents'],
    ['bad', 'Failed (4xx, 5xx, errors)'],
    ['other', 'Other (scripts, images…)'],
  ],
};

// ---------- loading ----------

// The extension's recordings list is a separate page (recordings.html); replay.js loads one recording here.
$('files').onchange = (e) => { loadFiles([...e.target.files]).catch(showError); e.target.value = ''; };
$('videoFile').onchange = (e) => { loadFiles([...e.target.files]).catch(showError); e.target.value = ''; };

document.addEventListener('dragover', (e) => { e.preventDefault(); document.body.classList.add('drag'); });
document.addEventListener('dragleave', (e) => { if (!e.relatedTarget) document.body.classList.remove('drag'); });
document.addEventListener('drop', (e) => {
  e.preventDefault();
  document.body.classList.remove('drag');
  loadFiles([...e.dataTransfer.files]).catch(showError);
});

// Lets a host page (e.g. a server that shows uploaded recordings) open a recording: video = URL or Blob (or null), report = parsed session.json.
window.blackboxOpen = (videoSrc, sessionReport) => {
  current = { video: videoSrc || null, report: sessionReport };
  openRecording();
};

window.blackboxShowError = (e) => showError(e);

// Unloads the open recording and goes back to the empty screen (nothing is deleted).
window.blackboxClose = () => {
  video.pause();
  video.removeAttribute('src');
  video.load();
  if (videoUrl) URL.revokeObjectURL(videoUrl);
  videoUrl = null;
  current = { video: null, report: null };
  report = null;
  hasVideo = false;
  closeDetail();
  $('meta').textContent = '';
  $('meta').title = '';
  showError(null);
  $('app').hidden = true;
  $('empty').hidden = false;
};

// The loaded recording, for the extension's Upload and Download.
window.blackboxCurrent = () => current;

async function loadFiles(files) {
  const name = (f) => f.name.toLowerCase();
  const json = files.find((f) => name(f).endsWith('.json'));
  const webm = files.find((f) => name(f).endsWith('.webm') || f.type.startsWith('video/'));
  if (!json && !webm) throw new Error(`Not a recording file: ${files.map((f) => f.name).join(', ')}. Pick recording.webm and session.json.`);
  if (webm && webm.size === 0) throw new Error(`${webm.name} is empty (0 bytes): the video was not recorded.`);
  if (webm) current.video = webm;
  if (json) current.report = JSON.parse(await json.text());
  if (!current.report) {
    showError('Video loaded. Now add session.json from the same folder.');
    return;
  }
  openRecording();
  document.dispatchEvent(new Event('blackbox-files-opened'));
}

function openRecording() {
  const r = current.report;
  if (!Array.isArray(r?.console) || !Array.isArray(r?.network)) throw new Error('This is not a BlackBox session.json.');
  showError(null);
  report = r;
  durationMs = r.recording?.durationMs || Math.max(0, ...r.console.map((e) => e.t), ...r.network.map((e) => e.t));
  manualMs = 0;

  if (videoUrl) URL.revokeObjectURL(videoUrl);
  videoUrl = null;
  hasVideo = !!current.video;
  video.hidden = !hasVideo;
  $('noVideo').hidden = hasVideo;
  if (hasVideo) {
    // A string is a URL (a server streams the video); a Blob/File is local
    if (typeof current.video === 'string') {
      video.src = current.video;
    } else {
      videoUrl = URL.createObjectURL(current.video);
      video.src = videoUrl;
    }
  } else {
    video.removeAttribute('src');
  }

  const rec = r.recording || {};
  const tabInfo = rec.tab || {};
  $('meta').textContent = [
    tabInfo.title || tabInfo.url,
    rec.startedAt && new Date(rec.startedAt).toLocaleString(),
    fmt(durationMs),
    rec.capturedSurface,
  ].filter(Boolean).join('  ·  ');
  $('meta').title = tabInfo.url || '';

  $('consoleCount').textContent = r.console.length;
  $('networkCount').textContent = r.network.length;
  issues = [
    ...r.console.filter((e) => e.level === 'error').map((e) => e.t),
    ...r.network.filter(isBad).map((e) => e.t),
  ].sort((a, b) => a - b);

  $('empty').hidden = true;
  $('app').hidden = false;
  renderMarkers();
  renderInfo();
  setTab(tab);
  onTime();
}

video.addEventListener('error', () => {
  if (!hasVideo) return;
  const e = video.error;
  showError(`The video can't be played (${e?.message || 'media error ' + e?.code}). Is it the recording.webm from the same folder?`);
});

// MediaRecorder WebM files have no duration, so the browser reports Infinity and can't seek.
// Seeking far past the end makes it scan the file and find the real duration.
video.addEventListener('loadedmetadata', () => {
  if (video.duration !== Infinity) return;
  fixingDuration = true;
  video.addEventListener('durationchange', () => {
    video.currentTime = 0;
    fixingDuration = false;
  }, { once: true });
  video.currentTime = 1e101;
});

// ---------- playhead ----------

function nowMs() {
  return hasVideo ? video.currentTime * 1000 : manualMs;
}

function seek(ms) {
  ms = Math.max(0, Math.min(ms, durationMs));
  if (hasVideo) video.currentTime = ms / 1000;
  else manualMs = ms;
  onTime();
}

function frame() {
  onTime();
  if (!video.paused) requestAnimationFrame(frame);
}
video.addEventListener('play', () => requestAnimationFrame(frame));
video.addEventListener('timeupdate', onTime);
video.addEventListener('seeked', onTime);

function onTime() {
  if (fixingDuration || !report) return;
  const now = nowMs();
  $('playhead').style.left = pct(now);
  $('clock').textContent = `${fmt(now)} / ${fmt(durationMs)}`;

  const n = upperBound(rows, now);
  if (n === pastCount) return;
  rows[pastCount - 1]?.el.classList.remove('current');
  const [from, to] = n > pastCount ? [pastCount, n] : [n, pastCount];
  for (let i = from; i < to; i++) rows[i].el.classList.toggle('future', i >= n);
  pastCount = n;
  const last = rows[n - 1];
  if (last) {
    last.el.classList.add('current');
    if ($('follow').checked) last.el.scrollIntoView({ block: 'nearest' });
  }
}

// Number of rows with t <= ms.
function upperBound(list, ms) {
  let lo = 0, hi = list.length;
  while (lo < hi) {
    const mid = (lo + hi) >> 1;
    if (list[mid].t <= ms) lo = mid + 1; else hi = mid;
  }
  return lo;
}

$('timeline').addEventListener('click', (e) => {
  if (e.target.classList.contains('marker')) return;
  const box = $('timeline').getBoundingClientRect();
  seek(((e.clientX - box.left) / box.width) * durationMs);
});

$('nextIssue').onclick = () => {
  const t = issues.find((x) => x > nowMs() + 1);
  if (t != null) seek(t);
};
$('prevIssue').onclick = () => {
  const t = [...issues].reverse().find((x) => x < nowMs() - 1);
  if (t != null) seek(t);
};

document.addEventListener('keydown', (e) => {
  if (e.target.closest('input, select, textarea, video') || !hasVideo || !report) return;
  if (e.key === ' ') { e.preventDefault(); video.paused ? video.play() : video.pause(); }
  if (e.key === 'ArrowLeft') seek(nowMs() - 5000);
  if (e.key === 'ArrowRight') seek(nowMs() + 5000);
});

function renderMarkers() {
  const box = $('markers');
  box.replaceChildren();
  const add = (t, cls, title, onClick) => {
    const m = el('div', `marker ${cls}`);
    m.style.left = pct(t);
    m.title = `${fmt(t)}  ${title}`;
    m.onclick = (e) => { e.stopPropagation(); seek(t); onClick?.(); };
    box.append(m);
  };
  for (const n of report.navigations || []) add(n.t, 'nav', `Navigated to ${n.url}`);
  for (const n of report.network.filter(isBad)) add(n.t, 'failed', `${n.status || 'ERR'} ${n.method} ${n.url}`, () => openRow('network', n));
  for (const c of report.console.filter((e) => e.level === 'error')) add(c.t, 'error', c.text, () => openRow('console', c));
}

// ---------- tabs and lists ----------

document.querySelectorAll('.tabs button').forEach((b) => { b.onclick = () => setTab(b.dataset.tab); });
$('search').oninput = () => renderList();
$('filter').onchange = () => renderList();
$('hideFuture').onchange = () => $('list').classList.toggle('hide-future', $('hideFuture').checked);
$('detailClose').onclick = () => closeDetail();

function setTab(name) {
  tab = name;
  document.querySelectorAll('.tabs button').forEach((b) => b.classList.toggle('active', b.dataset.tab === name));
  const isInfo = name === 'info';
  $('toolbar').hidden = isInfo;
  $('list').hidden = isInfo;
  $('info').hidden = !isInfo;
  if (!isInfo) {
    $('filter').replaceChildren(...FILTERS[name].map(([v, label]) => {
      const o = el('option', null, label);
      o.value = v;
      return o;
    }));
    renderList();
  }
  closeDetail();
}

function consoleItems() {
  const navs = (report.navigations || []).map((n) => ({ t: n.t, level: 'nav', source: 'navigation', text: `Navigated to ${n.url}` }));
  return [...report.console, ...navs].sort((a, b) => a.t - b.t);
}

function matchesFilter(item, f) {
  if (tab === 'console') {
    if (f === 'error') return item.level === 'error';
    if (f === 'warn') return item.level === 'error' || item.level === 'warn';
    if (f === 'page') return item.source === 'console' || item.source === 'exception';
    if (f === 'browser') return item.source?.startsWith('browser:');
    return true;
  }
  if (f === 'api') return item.type === 'XHR' || item.type === 'Fetch';
  if (f === 'doc') return item.type === 'Document';
  if (f === 'bad') return isBad(item);
  if (f === 'other') return !['XHR', 'Fetch', 'Document'].includes(item.type);
  return true;
}

function renderList() {
  const q = $('search').value.trim().toLowerCase();
  const f = $('filter').value;
  const items = (tab === 'console' ? consoleItems() : [...report.network].sort((a, b) => a.t - b.t))
    .filter((it) => matchesFilter(it, f))
    .filter((it) => !q || searchText(it).includes(q));

  rows = items.map((item) => ({ t: item.t, item, el: tab === 'console' ? consoleRow(item) : networkRow(item) }));
  for (const r of rows) {
    r.el.classList.add('future');
    r.el.onclick = () => { seek(r.t); select(r.el, r.item); };
  }
  pastCount = 0;
  selectedEl = null;
  const list = $('list');
  list.replaceChildren(...rows.map((r) => r.el));
  if (!rows.length) list.append(el('div', 'empty', 'Nothing matches.'));
  onTime();
}

function searchText(it) {
  return tab === 'console'
    ? `${it.text} ${it.location || ''} ${it.source}`.toLowerCase()
    : `${it.method} ${it.url} ${it.status ?? ''} ${it.type || ''}`.toLowerCase();
}

function consoleRow(c) {
  const row = el('div', `row console lvl-${c.level}`);
  row.append(
    el('span', 't', fmt(c.t)),
    el('span', null, c.level),
    el('span', null, c.text),
    el('span', 'muted right', shortLocation(c.location)),
  );
  row.title = c.text;
  return row;
}

function networkRow(n) {
  const row = el('div', `row network${isBad(n) ? ' bad' : ''}`);
  const url = safeUrl(n.url);
  const name = url ? (url.pathname.split('/').filter(Boolean).pop() || url.host) + url.search : n.url;
  row.append(
    el('span', 't', fmt(n.t)),
    el('span', null, n.method),
    el('span', null, statusText(n)),
    el('span', null, name),
    el('span', 'muted', n.type || ''),
    el('span', 'muted right', n.durationMs != null ? `${n.durationMs} ms` : ''),
  );
  row.title = n.url;
  return row;
}

// Clicking a timeline marker: show its row (switching tab if needed) and its details.
function openRow(kind, item) {
  if (tab !== kind) setTab(kind);
  const r = rows.find((x) => x.item === item);
  if (r) { select(r.el, item); r.el.scrollIntoView({ block: 'center' }); }
  else showDetail(item);
}

function select(rowEl, item) {
  selectedEl?.classList.remove('selected');
  selectedEl = rowEl;
  rowEl.classList.add('selected');
  showDetail(item);
}

// ---------- detail pane ----------

function closeDetail() {
  $('detail').hidden = true;
  selectedEl?.classList.remove('selected');
  selectedEl = null;
}

function showDetail(item) {
  const body = $('detailBody');
  const actions = $('detailActions');
  body.replaceChildren();
  actions.replaceChildren();

  if ('method' in item) {
    $('detailTitle').textContent = `${item.method} ${statusText(item)}  ${item.url}`;
    actions.append(copyButton('Copy as cURL', () => toCurl(item)));
    if (item.responseBody?.text != null) actions.append(copyButton('Copy response', () => item.responseBody.text));

    section(body, 'General', kv({
      URL: item.url,
      Method: item.method,
      Status: `${statusText(item)} ${item.statusText || ''}`.trim(),
      Error: item.errorText,
      'Blocked reason': item.blockedReason,
      Type: item.type,
      'Started at': fmt(item.t),
      Duration: item.durationMs != null ? `${item.durationMs} ms` : 'not finished',
      Size: item.encodedDataLength != null ? `${item.encodedDataLength} bytes (on the wire)` : undefined,
      'Remote address': item.remoteAddress,
      'Redirected to': item.redirectedTo,
      Initiator: item.initiator,
      'From cache': item.fromCache ? 'yes' : undefined,
    }));
    if (item.requestHeaders) section(body, 'Request headers', kv(item.requestHeaders));
    if (item.postData) section(body, 'Request body', pre(pretty(item.postData)));
    else if (item.hasPostData) section(body, 'Request body', el('div', 'muted', 'Not captured (over 64 KB).'));
    if (item.responseHeaders) section(body, 'Response headers', kv(item.responseHeaders));
    section(body, 'Response body', responseBody(item));
  } else {
    $('detailTitle').textContent = `${item.level}  ${fmt(item.t)}`;
    actions.append(copyButton('Copy', () => item.text));
    section(body, 'Message', pre(item.text));
    section(body, 'Details', kv({ Level: item.level, Source: item.source, Location: item.location, Time: fmt(item.t) }));
  }
  $('detail').hidden = false;
}

function responseBody(n) {
  const b = n.responseBody;
  if (!b) return el('div', 'muted', n.failed ? 'No response.' : 'Not captured for this request type.');
  if (b.skipped) return el('div', 'muted', b.skipped === 'binary' ? `Binary body, not saved (${b.size} chars base64).` : `Not available: ${b.skipped}`);
  const box = el('div');
  if (b.truncated) box.append(el('div', 'muted', `Truncated to 1 MB (full size ${b.size} chars).`));
  box.append(pre(pretty(b.text)));
  return box;
}

function toCurl(n) {
  const q = (s) => `'${String(s).replace(/'/g, `'\\''`)}'`;
  const parts = [`curl ${q(n.url)}`];
  if (n.method !== 'GET') parts.push(`-X ${n.method}`);
  for (const [k, v] of Object.entries(n.requestHeaders || {})) parts.push(`-H ${q(`${k}: ${v}`)}`);
  if (n.postData) parts.push(`--data-raw ${q(n.postData)}`);
  return parts.join(' \\\n  ');
}

// ---------- info tab ----------

function renderInfo() {
  const rec = report.recording || {};
  const s = report.summary || {};
  $('info').replaceChildren(
    kv({
      'Tab title': rec.tab?.title,
      'Tab URL': rec.tab?.url,
      'Started': rec.startedAt && new Date(rec.startedAt).toLocaleString(),
      'Stopped': rec.stoppedAt && new Date(rec.stoppedAt).toLocaleString(),
      'Duration': fmt(durationMs),
      'Stop reason': rec.stopReason,
      'Captured surface': rec.capturedSurface,
      'Console entries': s.console,
      'Console errors': s.errors,
      'Requests': s.requests,
      'Failed requests': s.failedRequests,
      'Navigations': (report.navigations || []).length,
      'User agent': rec.userAgent,
      'Recorded with': report.tool,
    }),
  );
}

// ---------- helpers ----------

function el(tag, cls, text) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text != null) e.textContent = text;
  return e;
}

function kv(obj) {
  const dl = el('dl', 'kv');
  for (const [k, v] of Object.entries(obj)) {
    if (v == null || v === '') continue;
    dl.append(el('dt', null, k), el('dd', null, String(v)));
  }
  return dl;
}

function pre(text) {
  return el('pre', null, text);
}

function section(parent, title, content) {
  parent.append(el('h3', null, title), content);
}

function copyButton(label, getText) {
  const b = el('button', null, label);
  b.onclick = async () => {
    await navigator.clipboard.writeText(getText());
    b.textContent = 'Copied';
    setTimeout(() => { b.textContent = label; }, 1200);
  };
  return b;
}

function pretty(text) {
  try { return JSON.stringify(JSON.parse(text), null, 2); } catch { return text; }
}

function isBad(n) {
  return n.failed || n.status >= 400;
}

function statusText(n) {
  if (n.failed) return n.errorText === 'canceled' ? '(canceled)' : 'ERR';
  return n.status != null ? String(n.status) : '…';
}

function safeUrl(u) {
  try { return new URL(u); } catch { return null; }
}

function shortLocation(loc) {
  if (!loc) return '';
  return loc.split('/').pop().replace(/\?[^:]*/, ''); // "app.js?v=1:10:5" -> "app.js:10:5"
}

function pct(ms) {
  return `${durationMs ? Math.min(100, (ms / durationMs) * 100) : 0}%`;
}

function fmt(ms) {
  const s = Math.max(0, ms) / 1000;
  return `${Math.floor(s / 60)}:${(s % 60).toFixed(1).padStart(4, '0')}`;
}

function showError(e) {
  $('error').hidden = !e;
  $('error').textContent = e ? (e.message || String(e)) : '';
}
