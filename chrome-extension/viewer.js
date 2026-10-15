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
let traceItems = [];       // Glowroot traces with t (server replay page only, see prepareTraces)
let traceLink = null;      // (trace) => URL of its full trace page, or null (see blackboxOpen)
let traceRetry = null;     // async (keys) => asks the server to look for failed traces again, or null
const GLOWROOT_KEEP_MS = 14 * 24 * 3600 * 1000; // Glowroot's default trace retention

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
  traces: [
    ['all', 'All traces'],
    ['saved', 'Saved'],
    ['pending', 'Still being collected'],
    ['missing', 'No trace or failed'],
  ],
};

const TRACE_STATES = { saved: '✓ saved', pending: '⏳ waiting', none: '– none', failed: '✕ failed' };

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
// extra.info = { label: value } shown first in the Info tab (e.g. who uploaded it),
// extra.traces = Glowroot traces of the recorded requests (shows the Traces tab; see blackboxSetTraces),
// extra.traceUrl = (trace) => URL of a saved trace's full page, or null when the page is not available,
// extra.retryTraces = async (keys) => asks the server to look again for failed traces ([] = all failed ones).
window.blackboxOpen = (videoSrc, sessionReport, extra) => {
  current = {
    video: videoSrc || null, report: sessionReport, serverLog: extra?.serverLog || null, traces: extra?.traces || null,
    title: extra?.title || '', info: extra?.info || {},
  };
  traceLink = extra?.traceUrl || null;
  traceRetry = extra?.retryTraces || null;
  openRecording();
};

// New trace states (the server page polls while some are still being collected)
window.blackboxSetTraces = (list) => {
  current.traces = list;
  if (!report) return;
  traceItems = prepareTraces(list, report);
  updateTracesTab();
  if (tab === 'traces') renderList();
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
  traceItems = prepareTraces(current.traces, r);
  updateTracesTab();
  if (tab === 'server' && !current.serverLog) tab = 'console';
  if (tab === 'traces' && !current.traces) tab = 'console';
  issues = [
    ...r.console.filter((e) => e.level === 'error').map((e) => e.t),
    ...r.network.filter(isBad).map((e) => e.t),
    ...serverItems.filter(isServerError).map((e) => e.t),
  ].sort((a, b) => a - b);

  $('empty').hidden = true;
  $('app').hidden = false;
  showNetRange(null);
  renderMarkers();
  drawOverview();
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
  ov.playhead.style.left = pct(now);
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

// ---------- network overview ----------

// Like the DevTools Network overview, on the Network tab only: one bar per request, from its start (t) to its end
// (t + durationMs), on the same time axis as the timeline under the video. Drag across it to list only the requests active in that range; a click
// seeks, a double-click or ✕ shows all requests again.
let netRange = null;       // [fromMs, toMs] picked on the overview, or null
const ov = buildOverview();

function buildOverview() {
  const dot = (cls, text) => {
    const s = el('span');
    s.append(el('i', `dot ${cls}`), text);
    return s;
  };
  const rangeText = el('span');
  const clear = el('button', null, '✕');
  clear.title = 'Show all requests';
  clear.onclick = () => setNetRange(null);
  const range = el('span', 'ov-range');
  range.append(rangeText, clear);
  const head = el('div', 'ov-head');
  head.append(el('span', null, 'Requests'), dot('doc', 'Document'), dot('api', 'Fetch / XHR'), dot('other', 'Other'),
    dot('failed', 'Failed'), range);

  const strip = el('div', 'ov-strip');
  strip.title = 'Drag to list only the requests in that time range. Click to seek, double-click to show all.';
  const canvas = el('canvas');
  const sel = el('div', 'ov-sel');
  sel.hidden = true;
  const playhead = el('div', 'playhead');
  strip.append(canvas, sel, playhead);
  const box = el('div', 'net-overview');
  box.append(head, strip);
  box.hidden = true;
  $('toolbar').after(box);
  return { box, strip, canvas, sel, playhead, range, rangeText };
}

// Bar colour group of a request
function netKind(n) {
  if (isBad(n)) return 'failed';
  if (n.type === 'Document') return 'doc';
  if (n.type === 'XHR' || n.type === 'Fetch') return 'api';
  return 'other';
}

function drawOverview() {
  const c = ov.canvas;
  const w = ov.strip.clientWidth, h = ov.strip.clientHeight;
  if (!w || !h) return;
  const scale = (window.devicePixelRatio || 1) * (parseFloat(document.documentElement.style.zoom) || 1);
  c.width = Math.round(w * scale);
  c.height = Math.round(h * scale);
  const g = c.getContext('2d');
  g.setTransform(scale, 0, 0, scale, 0, 0);
  g.clearRect(0, 0, w, h);
  if (!report || !durationMs) return;

  const css = getComputedStyle(document.documentElement);
  const colors = Object.fromEntries(['doc:--nav', 'api:--api', 'other:--fg-2', 'failed:--failed']
    .map((p) => p.split(':')).map(([k, v]) => [k, css.getPropertyValue(v).trim()]));
  const x = (ms) => (ms / durationMs) * w;

  // Page navigations: full-height lines, like DevTools' load lines
  g.globalAlpha = 0.5;
  g.fillStyle = colors.doc;
  for (const n of report.navigations || []) g.fillRect(Math.round(x(n.t)), 0, 1, h);

  // Requests: each goes in the first lane free at its start, so parallel requests stack
  const BAR = 3, GAP = 1;
  const laneEnd = new Array(Math.max(1, Math.floor((h - 2) / (BAR + GAP)))).fill(-Infinity);
  for (const n of [...report.network].sort((a, b) => a.t - b.t)) {
    const x0 = x(n.t), x1 = Math.max(x0 + 2, x(n.t + (n.durationMs || 0)));
    let lane = laneEnd.findIndex((end) => end < x0);
    if (lane < 0) lane = laneEnd.indexOf(Math.min(...laneEnd)); // all busy: the lane that frees first
    laneEnd[lane] = x1 + 1;
    const kind = netKind(n);
    g.globalAlpha = kind === 'other' ? 0.6 : 1;
    g.fillStyle = colors[kind];
    g.fillRect(x0, 1 + lane * (BAR + GAP), x1 - x0, BAR);
  }
  g.globalAlpha = 1;
}

// Redraw when the strip is resized, the page is zoomed (Text size) or the theme changes
new ResizeObserver(() => drawOverview()).observe(ov.strip);
new MutationObserver(() => drawOverview()).observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme', 'style'] });
matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => drawOverview());

function overviewMs(clientX) {
  const box = ov.strip.getBoundingClientRect();
  return Math.max(0, Math.min(1, (clientX - box.left) / box.width)) * durationMs;
}

ov.strip.addEventListener('pointerdown', (e) => {
  if (!report || e.button !== 0) return;
  const startX = e.clientX;
  const from = overviewMs(startX);
  const dragged = (ev) => Math.abs(ev.clientX - startX) > 3;
  const move = (ev) => { if (dragged(ev)) showSelection(from, overviewMs(ev.clientX)); };
  const end = (ev) => {
    ov.strip.removeEventListener('pointermove', move);
    ov.strip.removeEventListener('pointerup', end);
    ov.strip.removeEventListener('pointercancel', end);
    if (ev.type === 'pointercancel') showSelection(...(netRange || []));
    else if (dragged(ev)) setNetRange([from, overviewMs(ev.clientX)].sort((a, b) => a - b));
    else seek(from);
  };
  ov.strip.setPointerCapture(e.pointerId);
  ov.strip.addEventListener('pointermove', move);
  ov.strip.addEventListener('pointerup', end);
  ov.strip.addEventListener('pointercancel', end);
});
ov.strip.addEventListener('dblclick', () => setNetRange(null));

function showSelection(a, b) {
  ov.sel.hidden = a == null;
  if (a == null) return;
  ov.sel.style.left = pct(Math.min(a, b));
  ov.sel.style.width = pct(Math.abs(b - a));
}

// Only the range's look; the caller renders the list
function showNetRange(range) {
  netRange = range;
  ov.range.hidden = !range;
  if (range) ov.rangeText.textContent = `${fmt(range[0])} – ${fmt(range[1])}`;
  showSelection(...(range || []));
}

// The Network list shows only the requests active in the picked range
function setNetRange(range) {
  if (!range && !netRange) return;
  showNetRange(range);
  renderList();
}

function inNetRange(n) {
  return !netRange || (n.t <= netRange[1] && n.t + (n.durationMs || 0) >= netRange[0]);
}

// A request's bar in its list row: on the whole recording, or on the picked range
function waterfall(n) {
  const [from, to] = netRange || [0, durationMs];
  const span = Math.max(1, to - from);
  const a = Math.max(0, (n.t - from) / span);
  const b = Math.min(1, (n.t + (n.durationMs || 0) - from) / span);
  const bar = el('i', `wf-bar ${netKind(n)}`);
  bar.style.left = `${a * 100}%`;
  bar.style.width = `max(2px, ${Math.max(0, b - a) * 100}%)`;
  const box = el('span', 'wf');
  box.title = n.durationMs != null ? `${fmt(n.t)} + ${n.durationMs} ms` : `${fmt(n.t)}, not finished`;
  box.append(bar);
  return box;
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
    // A cached response keeps the header of when it was first fetched (maybe days ago): only fresh ones count
    if (n.fromCache) continue;
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

// ---------- resizable panes ----------

// Drag the line left of the panel (its width) or above the detail pane (its height); double-click resets it.
// Sizes are CSS variables on #app, so the one-column layout of narrow windows still wins. Remembered per browser.
const SPLITS = {
  panel: { varName: '--panel-w', min: 320, edge: 'left', box: () => $('app'), keep: 320 },
  detail: { varName: '--detail-h', min: 80, edge: 'top', box: () => document.querySelector('.panel'), keep: 120 },
};

function addSplitter(name, host, cls) {
  const sp = SPLITS[name];
  const handle = el('div', `splitter ${cls}`);
  handle.title = 'Drag to resize, double-click to reset';
  host.prepend(handle);
  handle.addEventListener('pointerdown', (e) => {
    if (e.button !== 0) return;
    e.preventDefault();
    document.body.classList.add(`resizing-${cls}`);
    const move = (ev) => {
      // Rects and pointer positions are in zoomed pixels; the size is set in the page's own (Text size) pixels
      const zoom = parseFloat(document.documentElement.style.zoom) || 1;
      const box = sp.box().getBoundingClientRect();
      const px = sp.edge === 'left' ? box.right - ev.clientX : box.bottom - ev.clientY;
      const room = (sp.edge === 'left' ? box.width : box.height) / zoom - sp.keep;
      setSplit(name, Math.round(Math.max(sp.min, Math.min(px / zoom, room))));
    };
    const end = () => {
      handle.removeEventListener('pointermove', move);
      handle.removeEventListener('pointerup', end);
      handle.removeEventListener('pointercancel', end);
      document.body.classList.remove(`resizing-${cls}`);
      saveSplits();
    };
    handle.addEventListener('pointermove', move);
    handle.addEventListener('pointerup', end);
    handle.addEventListener('pointercancel', end);
    handle.setPointerCapture(e.pointerId);
  });
  handle.addEventListener('dblclick', () => { setSplit(name, null); saveSplits(); });
}

function setSplit(name, px) {
  if (px) $('app').style.setProperty(SPLITS[name].varName, `${px}px`);
  else $('app').style.removeProperty(SPLITS[name].varName);
}

function saveSplits() {
  const sizes = Object.fromEntries(Object.entries(SPLITS).map(([n, sp]) => [n, parseInt($('app').style.getPropertyValue(sp.varName)) || 0]));
  try { localStorage.setItem('bb-splits', JSON.stringify(sizes)); } catch (e) { /* not remembered */ }
}

addSplitter('panel', document.querySelector('.panel'), 'split-x');
addSplitter('detail', $('detail'), 'split-y');
try {
  const sizes = JSON.parse(localStorage.getItem('bb-splits') || '{}');
  for (const n of Object.keys(SPLITS)) if (sizes[n] > 0) setSplit(n, sizes[n]);
} catch (e) { /* storage blocked: default sizes */ }

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
  if (isInfo) tabNote.hidden = true;
  ov.box.hidden = name !== 'network'; // shown: the ResizeObserver draws it
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
  if (tab === 'traces') {
    if (f === 'saved' || f === 'pending') return item.state === f;
    if (f === 'missing') return item.state === 'none' || item.state === 'failed';
    return true;
  }
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
  const items = (tab === 'console' ? consoleItems() : tab === 'server' ? serverItems : tab === 'traces' ? traceItems
    : [...report.network].sort((a, b) => a.t - b.t))
    .filter((it) => matchesFilter(it, f))
    .filter((it) => tab !== 'network' || inNetRange(it))
    .filter((it) => !q || searchText(it).includes(q));

  const rowOf = { console: consoleRow, network: networkRow, server: serverRow, traces: traceRow }[tab];
  rows = items.map((item) => ({ t: item.t, item, el: rowOf(item) }));
  for (const r of rows) {
    r.el.classList.add('future');
    r.el.onclick = () => { seek(r.t); select(r.el, r.item); };
  }
  pastCount = 0;
  selectedEl = null;
  const list = $('list');
  list.replaceChildren(...rows.map((r) => r.el));
  if (!rows.length) list.append(el('div', 'empty', tab === 'traces' && !traceItems.length ? 'No traces.' : 'Nothing matches.'));
  renderTabNote();
  onTime();
}

function searchText(it) {
  if (tab === 'traces') return `${it.method} ${it.url} ${it.status ?? ''} ${it.state} ${it.reason || ''}`.toLowerCase();
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
    waterfall(n),
  );
  row.title = n.url;
  return row;
}

function traceRow(e) {
  const row = el('div', `row trace state-${e.state}`);
  const s = e.summary || {};
  row.append(
    el('span', 't', fmt(e.t)),
    el('span', 'state', TRACE_STATES[e.state] || e.state),
    el('span', null, e.status != null ? String(e.status) : '…'),
    el('span', null, `${e.method} ${e.url}`),
    el('span', 'muted right', s.sqlCount != null ? `${s.sqlCount} SQL` : ''),
    el('span', 'muted right', e.ms != null ? `${e.ms} ms` : ''),
  );
  row.title = e.reason ? `${e.url}\n${e.reason}` : e.url;
  return row;
}

// ---------- Glowroot traces ----------

// Trace entries have ts = server clock, like the server log: same start and clock offset (prepareServerLog runs first)
function prepareTraces(list, r) {
  const start = Date.parse(r.recording?.startedAt);
  return (list || []).map((e) => ({ ...e, kind: 'trace', t: start ? e.ts - start - serverOffset : 0 }))
    .sort((a, b) => a.t - b.t);
}

function updateTracesTab() {
  if (!$('tracesTab')) return;
  $('tracesTab').hidden = !current.traces;
  const pending = traceItems.filter((e) => e.state === 'pending').length;
  $('tracesCount').textContent = traceItems.length + (pending ? ` ⏳${pending}` : '');
  $('tracesTab').title = pending ? `${pending} still being collected` : 'Glowroot traces of slow and failed requests';
}

// Above the Traces list: says when traces are still coming, or why there are none
const tabNote = el('div', 'tab-note');
tabNote.hidden = true;
$('list').before(tabNote);

function renderTabNote() {
  const pending = traceItems.filter((e) => e.state === 'pending').length;
  const failed = traceItems.filter((e) => e.state === 'failed').length;
  tabNote.hidden = tab !== 'traces' || (!pending && traceItems.length > 0 && !(failed && traceRetry));
  if (pending) {
    tabNote.textContent = `${pending} ${pending === 1 ? 'trace is' : 'traces are'} still being collected (slow requests, a retry, or Glowroot has not stored them yet). This tab updates by itself.`;
  } else if (failed && traceRetry) {
    const b = el('button', null, `Retry failed (${failed})`);
    b.title = 'Ask the server that handled each request to look in its Glowroot again';
    b.onclick = () => retryTraces(traceItems.filter((e) => e.state === 'failed').map((e) => e.key), b);
    tabNote.replaceChildren(`${failed} ${failed === 1 ? 'trace' : 'traces'} could not be collected. `, b);
  } else {
    tabNote.textContent = 'No traces: no recorded request was slower than Glowroot\'s threshold or failed.';
  }
}

async function retryTraces(keys, button) {
  button.disabled = true;
  try {
    await traceRetry(keys);
    showError(null);
  } catch (e) {
    button.disabled = false;
    showError(e);
  }
}

// The trace of a recorded network request: same method and path, nearest in time
function traceFor(n) {
  const u = safeUrl(n.url);
  if (!u) return null;
  const path = u.pathname + u.search;
  let best = null;
  for (const e of traceItems) {
    if (e.method !== n.method || e.url !== path) continue;
    const d = Math.abs(e.t - n.t);
    if (d <= 2000 + (n.durationMs || 0) && (!best || d < Math.abs(best.t - n.t))) best = e;
  }
  return best;
}

function traceSummaryText(e) {
  const s = e.summary;
  if (e.state !== 'saved' || !s) return `${TRACE_STATES[e.state] || e.state}${e.reason ? ': ' + e.reason : ''}`;
  return `${s.durationMs} ms, ${s.sqlCount} SQL (${s.sqlMs} ms)${s.error ? ', error: ' + s.error : ''}`;
}

function traceDetail(item, body, actions) {
  const s = item.summary || {};
  $('detailTitle').textContent = `Trace: ${item.method} ${item.status ?? '…'}  ${item.url}`;
  const href = item.file && traceLink ? traceLink(item) : null;
  if (href) {
    const a = el('a', 'button', 'Open full trace');
    a.href = href;
    a.target = '_blank';
    a.rel = 'noopener';
    a.title = "Glowroot's page for this trace: every SQL statement, timers and thread profile";
    actions.append(a);
  }
  if (item.state === 'failed' && traceRetry) {
    const b = el('button', null, 'Retry');
    b.title = `Ask ${item.node || 'the server'} to look in its Glowroot again`;
    b.onclick = () => retryTraces([item.key], b);
    actions.append(b);
  }
  if (item.state === 'none') {
    body.append(el('div', 'muted', 'Glowroot kept no trace for this request (faster than its threshold, or not stored), so there is nothing to retry.'));
  } else if (item.state === 'failed' && Date.now() - item.ts > GLOWROOT_KEEP_MS) {
    body.append(el('div', 'muted', 'Glowroot keeps traces for 14 days by default: this one may be gone, so a retry may find nothing.'));
  }
  section(body, 'Trace', kv({
    State: TRACE_STATES[item.state] || item.state,
    Note: item.reason,
    'Full trace': item.file ? (href ? undefined : 'Not in this file: download the ZIP from the server to get it') : undefined,
    URL: item.url,
    Method: item.method,
    Status: item.status,
    Duration: item.ms != null ? `${item.ms} ms` : 'still running',
    'Started at': fmt(item.t),
    'Server time': new Date(item.ts).toISOString(),
    Node: item.node,
    'Glowroot trace id': item.traceId,
  }));
  if (!item.summary) return;
  if (s.error) section(body, 'Error', pre(s.error));
  section(body, 'Summary', kv({
    'Total (Glowroot)': `${s.durationMs} ms`,
    CPU: s.cpuMs != null ? `${s.cpuMs} ms` : undefined,
    SQL: `${s.sqlCount} statements, ${s.sqlMs} ms`,
    'Trace entries': s.entries,
  }));
  if (s.timers?.length) section(body, 'Where the time went (self time)', kv(Object.fromEntries(s.timers.map((x) => [x.name, `${x.ms} ms`]))));
  if (s.slowestQueries?.length) {
    const box = el('div');
    for (const q of s.slowestQueries) {
      box.append(el('div', 'muted', `${q.ms} ms · ${q.count}× · ${q.rows} rows`), pre(q.text));
    }
    section(body, 'Slowest queries', box);
  }
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

  if (item.kind === 'trace') {
    traceDetail(item, body, actions);
  } else if (item.kind === 'request') {
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
    const trace = traceItems.length ? traceFor(item) : null;
    if (trace) {
      const box = el('div');
      const show = el('button', null, 'Show in Traces tab');
      show.onclick = () => openRow('traces', trace);
      box.append(el('div', null, traceSummaryText(trace)), show);
      section(body, 'Glowroot trace', box);
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
