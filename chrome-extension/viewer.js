// Replay viewer: plays recording.webm and keeps the console/network lists in sync with the playhead.
// Every recorded entry has t = ms since start = position in the video.
// All recorded text is shown with textContent, never innerHTML: it comes from untrusted pages.

const $ = (id) => document.getElementById(id);
const video = $('video');

let current = { video: null, report: null, serverLog: null }; // what the user has loaded so far
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
let issues = [];           // sorted t of console errors, failed requests and server errors
let serverItems = [];      // server log entries with t (server replay page only, see prepareServerLog)
let serverOffset = 0;      // ms the server clock is ahead of the browser's (0 when too small to matter)

// Filter list per tab (none on the Server tab). Console levels are picked with checkboxes (LEVEL_BOXES).
const FILTERS = {
  console: [
    ['all', 'All sources'],
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

// Level checkboxes per tab: [value, label]. All are ticked at first.
// Server: the request entries the BlackBox plugin adds (not in Joget's log) are not listed; a network request's
// details show its own.
const LEVEL_BOXES = {
  console: [['DEBUG', 'DEBUG'], ['LOG', 'LOG'], ['INFO', 'INFO'], ['WARN', 'WARN'], ['ERROR', 'ERROR'], ['NAV', 'Navigations']],
  server: [['DEBUG', 'DEBUG'], ['INFO', 'INFO'], ['WARN', 'WARN'], ['ERROR', 'ERROR'], ['FATAL', 'FATAL']],
};
const pickedLevels = Object.fromEntries(Object.entries(LEVEL_BOXES).map(([t, list]) => [t, new Set(list.map(([v]) => v))]));

// ---------- text size ----------

// Scales the whole page (CSS zoom), like browser zoom but for this page only. Remembered per browser in
// localStorage ('bb-text-size'); the extension also keeps it in its settings (theme.js).
const TEXT_SIZES = ['0.9', '1', '1.15', '1.3'];

function applyTextSize(v) {
  if (!TEXT_SIZES.includes(String(v))) v = '1';
  document.documentElement.style.zoom = v === '1' ? '' : v;
  if ($('textSize')) $('textSize').value = v;
}

try { applyTextSize(localStorage.getItem('bb-text-size') || '1'); } catch (e) { /* storage blocked: default size */ }
if ($('textSize')) {
  $('textSize').onchange = () => {
    const v = $('textSize').value;
    applyTextSize(v);
    try { localStorage.setItem('bb-text-size', v); } catch (e) { /* not remembered */ }
    document.dispatchEvent(new CustomEvent('blackbox-text-size', { detail: v }));
  };
}

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

// Lets a host page (e.g. a server that shows uploaded recordings) open a recording: video = URL or Blob (or null),
// report = parsed session.json, extra.serverLog = parsed server-log.json (shows the Server tab),
// extra.title = the title in the header (default: the recorded tab's title),
// extra.info = { label: value } shown first in the Info tab (e.g. who uploaded it).
window.blackboxOpen = (videoSrc, sessionReport, extra) => {
  current = { video: videoSrc || null, report: sessionReport, serverLog: extra?.serverLog || null, title: extra?.title || '', info: extra?.info || {} };
  openRecording();
};

window.blackboxShowError = (e) => showError(e);

// Older / Newer in the header, set by the page that knows the list (extension: Recordings page rows, newest
// first; server: the user's list). Each is { href, title } or null; pos and total give "3 / 10".
window.blackboxSetNeighbours = ({ older, newer, pos, total }) => {
  const link = (a, target, word) => {
    a.classList.toggle('disabled', !target);
    a.setAttribute('aria-disabled', String(!target));
    a.href = target ? target.href : '#';
    a.title = target ? `${word} recording: ${target.title}` : `No ${word.toLowerCase()} recording`;
  };
  link($('olderRec'), older, 'Older');
  link($('newerRec'), newer, 'Newer');
  $('recPos').textContent = total ? `${pos} / ${total}` : '';
  $('recPos').title = 'Position in the recordings list (newest first)';
  $('recNav').hidden = !total;
};

// Unloads the open recording and goes back to the empty screen (nothing is deleted).
window.blackboxClose = () => {
  video.pause();
  video.removeAttribute('src');
  video.load();
  if (videoUrl) URL.revokeObjectURL(videoUrl);
  videoUrl = null;
  current = { video: null, report: null, serverLog: null };
  report = null;
  $('recTitle').textContent = 'BlackBox replay';
  $('recTitle').title = '';
  document.title = 'BlackBox Replay';
  hasVideo = false;
  closeDetail();
  showError(null);
  $('app').hidden = true;
  $('empty').hidden = false;
};

// The loaded recording, for the extension's Upload and Download.
window.blackboxCurrent = () => current;

async function loadFiles(files) {
  const name = (f) => f.name.toLowerCase();
  const zip = files.find((f) => name(f).endsWith('.zip'));
  if (zip) {
    // A recording ZIP (Download ZIP on the server) is a whole recording: it replaces what is open.
    // Its server-log.json is not shown here; server logs are shown on the server's replay page.
    const parts = await readZip(zip);
    if (!parts.has('session.json')) throw new Error(`${zip.name} has no session.json: it is not a BlackBox recording ZIP.`);
    current = { video: null, report: null, serverLog: null };
    files = [new File([parts.get('session.json')], 'session.json', { type: 'application/json' })];
    if (parts.has('recording.webm')) files.push(new File([parts.get('recording.webm')], 'recording.webm', { type: 'video/webm' }));
  }
  const json = files.find((f) => name(f).endsWith('.json'));
  const webm = files.find((f) => name(f).endsWith('.webm') || f.type.startsWith('video/'));
  // Files picked here are not named by the page's address: no ?t= for them
  window.blackboxTimeInUrl = false;
  if (!json && !webm) throw new Error(`Not a recording file: ${files.map((f) => f.name).join(', ')}. Pick recording.webm and session.json.`);
  if (webm && webm.size === 0) throw new Error(`${webm.name} is empty (0 bytes): the video was not recorded.`);
  if (webm) current.video = webm;
  if (json) {
    current.report = JSON.parse(await json.text());
    current.serverLog = null;
  }
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
  // Start time, length, surface and the tab's URL are in the Info tab; the header only names the recording
  $('recTitle').textContent = current.title || tabInfo.title || tabInfo.url || 'BlackBox replay';
  // The same browser tab title on every replay page
  document.title = 'BlackBox Replay · ' + $('recTitle').textContent;
  $('recTitle').title = tabInfo.url || '';

  $('consoleCount').textContent = r.console.length;
  $('networkCount').textContent = r.network.length;
  serverItems = prepareServerLog(current.serverLog, r);
  if ($('serverTab')) {
    $('serverTab').hidden = !current.serverLog;
    $('serverCount').textContent = serverItems.filter((e) => e.kind === 'log').length;
  }
  if (tab === 'server' && !current.serverLog) tab = 'console';
  issues = [
    ...r.console.filter((e) => e.level === 'error').map((e) => e.t),
    ...r.network.filter(isBad).map((e) => e.t),
    ...serverItems.filter(isServerError).map((e) => e.t),
  ].sort((a, b) => a - b);

  $('empty').hidden = true;
  $('app').hidden = false;
  renderMarkers();
  renderInfo();
  setTab(tab);
  startMs = Math.min(timeFromUrl(), durationMs);
  if (startMs) seek(startMs); // before the video loads, this sets where it starts
  else onTime();
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
    video.currentTime = startMs / 1000; // back to the start, or to ?t=
    fixingDuration = false;
  }, { once: true });
  video.currentTime = 1e101;
});

// ---------- time in the address ----------

// Pages whose address names one recording (extension ?id= / ?disk=, server ?id=, the downloaded file) set
// window.blackboxTimeInUrl: then ?t=<seconds> opens it at that moment, and the address follows the playhead
// (replaceState: no new Back step per seek), so it can be copied as a link to that moment.
let startMs = 0;           // where the open recording starts (from ?t=)
let urlTimer = null;

function timeFromUrl() {
  const t = parseFloat(new URLSearchParams(location.search).get('t'));
  return window.blackboxTimeInUrl && t > 0 ? t * 1000 : 0;
}

function saveTimeInUrl() {
  if (!window.blackboxTimeInUrl || !report) return;
  clearTimeout(urlTimer);
  urlTimer = setTimeout(() => {
    const params = new URLSearchParams(location.search);
    const s = Math.round(nowMs() / 100) / 10;
    if (s > 0) params.set('t', s.toFixed(1)); else params.delete('t');
    const q = params.toString();
    try {
      history.replaceState(history.state, '', location.pathname + (q ? '?' + q : '') + location.hash);
    } catch (e) { /* a file:// page may refuse: the time just isn't in the address */ }
  }, 250);
}
video.addEventListener('seeked', saveTimeInUrl);
video.addEventListener('pause', saveTimeInUrl);

// ---------- playhead ----------

function nowMs() {
  return hasVideo ? video.currentTime * 1000 : manualMs;
}

function seek(ms) {
  ms = Math.max(0, Math.min(ms, durationMs));
  if (hasVideo) video.currentTime = ms / 1000;
  else manualMs = ms;
  onTime();
  saveTimeInUrl();
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
  for (const e of serverItems.filter(isServerError)) add(e.t, 'server', `Server: ${serverText(e)}`, () => openRow('server', e));
}

// ---------- server log ----------

// Entries have ts = server clock (epoch ms). t = ts - recording start, minus the server clock's offset, measured
// from the X-BlackBox-Time header the plugin adds to the tagged responses (server time when the request arrived).
// Small offsets are mostly network delay, so only an offset over 250 ms is corrected.
function prepareServerLog(log, r) {
  serverOffset = 0;
  const start = Date.parse(r.recording?.startedAt);
  if (!log || !start) return [];
  const offsets = [];
  for (const n of r.network) {
    const v = Number(header(n.responseHeaders, 'x-blackbox-time'));
    if (v) offsets.push(v - (start + n.t));
  }
  if (offsets.length) {
    offsets.sort((a, b) => a - b);
    const median = offsets[offsets.length >> 1];
    if (Math.abs(median) > 250) serverOffset = Math.round(median);
  }
  return (log.entries || []).map((e) => ({ ...e, t: e.ts - start - serverOffset, lvl: serverLevel(e) }));
}

// Timeline markers and Prev/Next issue: error log lines (a failed request already has a network marker)
function isServerError(e) {
  return e.kind === 'log' && e.lvl === 'error';
}

function serverLevel(e) {
  if (e.kind === 'request') return e.error || e.status >= 500 ? 'error' : e.status >= 400 ? 'warn' : 'request';
  const l = String(e.level || '').toLowerCase();
  return l === 'fatal' ? 'error' : l;
}

function serverText(e) {
  if (e.kind === 'request') return `${e.method} ${e.url} → ${e.error ? 'ERR' : e.status} (${e.ms} ms)`;
  return String(e.message ?? '').split('\n')[0];
}

function header(headers, name) {
  for (const [k, v] of Object.entries(headers || {})) if (k.toLowerCase() === name) return v;
  return undefined;
}

// The log lines written while serving a server request entry
function requestLines(req) {
  const key = `${req.method} ${req.url}`;
  return serverItems.filter((x) => x.kind === 'log' && x.req === key && x.ts >= req.ts && x.ts <= req.ts + req.ms + 5);
}

// The server request entry of a recorded network request: same method and path, nearest in time
function serverRequestFor(n) {
  const u = safeUrl(n.url);
  if (!u || !serverItems.length) return null;
  const path = u.pathname + u.search;
  let best = null;
  for (const e of serverItems) {
    if (e.kind !== 'request' || e.method !== n.method || e.url !== path) continue;
    const d = Math.abs(e.t - n.t);
    if (d <= 2000 + (n.durationMs || 0) && (!best || d < Math.abs(best.t - n.t))) best = e;
  }
  return best;
}

function serverList(items) {
  const box = el('div', 'list mini');
  for (const item of items) {
    const row = serverRow(item);
    row.onclick = () => { seek(item.t); openRow('server', item); };
    box.append(row);
  }
  return box;
}

// ---------- tabs and lists ----------

document.querySelectorAll('.tabs button[data-tab]').forEach((b) => { b.onclick = () => setTab(b.dataset.tab); });
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
    $('filter').hidden = !FILTERS[name];
    for (const t of Object.keys(LEVEL_BOXES)) levelBoxes(t).hidden = t !== name;
    if (FILTERS[name]) {
      $('filter').replaceChildren(...FILTERS[name].map(([v, label]) => {
        const o = el('option', null, label);
        o.value = v;
        return o;
      }));
    }
    renderList();
  }
  closeDetail();
}

// A tab's level checkboxes, made on first use next to the filter list
function levelBoxes(name) {
  let box = $('levels-' + name);
  if (box) return box;
  box = el('span', 'levels');
  box.id = 'levels-' + name;
  for (const [level, text] of LEVEL_BOXES[name]) {
    const label = el('label');
    const cb = el('input');
    cb.type = 'checkbox';
    cb.checked = pickedLevels[name].has(level);
    cb.onchange = () => {
      if (cb.checked) pickedLevels[name].add(level); else pickedLevels[name].delete(level);
      renderList();
    };
    label.append(cb, ' ' + text);
    box.append(label);
  }
  $('filter').after(box);
  return box;
}

// Console entry level -> checkbox: console.* types, CDP Log levels (verbose, info, warn, error), exceptions (error)
function consoleLevel(item) {
  switch (item.level) {
    case 'nav': return 'NAV';
    case 'error': case 'assert': return 'ERROR';
    case 'warn': return 'WARN';
    case 'info': return 'INFO';
    case 'debug': case 'verbose': return 'DEBUG';
    default: return 'LOG'; // log, dir, table, trace, count, group…
  }
}

function consoleItems() {
  const navs = (report.navigations || []).map((n) => ({ t: n.t, level: 'nav', source: 'navigation', text: `Navigated to ${n.url}` }));
  return [...report.console, ...navs].sort((a, b) => a.t - b.t);
}

function matchesFilter(item, f) {
  if (tab === 'server') {
    return item.kind === 'log' && pickedLevels.server.has(String(item.level).toUpperCase());
  }
  if (tab === 'console') {
    if (!pickedLevels.console.has(consoleLevel(item))) return false;
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
  const items = (tab === 'console' ? consoleItems() : tab === 'server' ? serverItems : [...report.network].sort((a, b) => a.t - b.t))
    .filter((it) => matchesFilter(it, f))
    .filter((it) => !q || searchText(it).includes(q));

  const rowOf = { console: consoleRow, network: networkRow, server: serverRow }[tab];
  rows = items.map((item) => ({ t: item.t, item, el: rowOf(item) }));
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
  if (tab === 'server') return `${serverText(it)} ${it.logger || ''} ${it.req || ''} ${it.thrown || ''}`.toLowerCase();
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

function serverRow(e) {
  const row = el('div', `row server lvl-${e.lvl}`);
  row.append(
    el('span', 't', fmt(e.t)),
    el('span', null, e.kind === 'request' ? 'request' : e.lvl),
    el('span', null, serverText(e)),
    el('span', 'muted right', e.kind === 'request' ? '' : String(e.logger || '').split('.').pop()),
  );
  row.title = e.kind === 'request' ? `${e.method} ${e.url}` : String(e.message ?? '');
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

  if (item.kind === 'request') {
    $('detailTitle').textContent = `Server: ${item.method} ${item.error ? 'ERR' : item.status}  ${item.url}`;
    section(body, 'General', kv({
      URL: item.url,
      Method: item.method,
      Status: item.status,
      Error: item.error,
      Duration: `${item.ms} ms`,
      'Arrived at': fmt(item.t),
      'Server time': new Date(item.ts).toISOString(),
    }));
    const lines = requestLines(item);
    section(body, 'Log lines of this request', lines.length ? serverList(lines) : el('div', 'muted', 'None.'));
  } else if (item.kind === 'log') {
    $('detailTitle').textContent = `Server ${item.lvl}  ${fmt(item.t)}`;
    actions.append(copyButton('Copy', () => [item.message, item.thrown].filter(Boolean).join('\n')));
    section(body, 'Message', pre(String(item.message ?? '')));
    if (item.thrown) section(body, 'Exception', pre(item.thrown));
    section(body, 'Details', kv({
      Level: item.level,
      Logger: item.logger,
      Thread: item.thread,
      Request: item.req,
      Time: fmt(item.t),
      'Server time': new Date(item.ts).toISOString(),
    }));
  } else if ('method' in item) {
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
    if (serverItems.length) {
      const req = serverRequestFor(item);
      section(body, 'Server log', req ? serverList([req, ...requestLines(req)]) : el('div', 'muted', 'No server entry for this request.'));
    }
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
      ...current.info,
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
      'Server log': serverLogInfo(rec.serverLog),
      'Server clock offset': serverOffset ? `${serverOffset} ms (corrected)` : undefined,
      'User agent': rec.userAgent,
      'Recorded with': report.tool,
    }),
  );
}

function serverLogInfo(s) {
  const log = current.serverLog;
  if (log) {
    const n = serverItems.filter((e) => e.kind === 'log').length;
    return `${n} log lines, ${serverItems.length - n} requests${log.truncated ? ' (cut off at the size limit)' : ''}`;
  }
  if (!s) return undefined;
  if (s.status === 'on') return `Captured on ${s.server}; shown on the server's replay page after upload`;
  return `Not captured${s.message ? ': ' + s.message : ''}`;
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
