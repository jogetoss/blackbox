// Replay viewer: plays recording.webm and keeps the console/network lists in sync with the playhead.
// Every recorded entry has t = ms since start = position in the video.
// All recorded text is shown with textContent, never innerHTML: it comes from untrusted pages.

const $ = (id) => document.getElementById(id);
const video = $('video');

let current = { video: null, report: null, systemLog: null }; // what the user has loaded so far
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
let serverItems = [];      // system log entries with t (server replay page only, see prepareSystemLog)
let serverOffset = 0;      // ms the server clock is ahead of the browser's (0 when too small to matter)
let traceItems = [];       // Glowroot traces with t (server replay page only, see prepareTraces)
let traceLink = null;      // (trace) => URL of its full trace page, or null (see blackboxOpen)
let traceRetry = null;     // async (keys) => asks the server to look for failed traces again, or null
const GLOWROOT_KEEP_MS = 14 * 24 * 3600 * 1000; // Glowroot's default trace retention

// Filter list per tab (none on the System logs tab). Console levels are picked with checkboxes (LEVEL_BOXES).
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
// report = parsed session.json, extra.systemLog = parsed system-log.json (shows the System logs tab),
// extra.title = the title in the header (default: the recorded tab's title),
// extra.info = { label: value } shown first in the Info tab (e.g. who uploaded it),
// extra.traces = Glowroot traces of the recorded requests (shows the Traces tab; see blackboxSetTraces),
// extra.traceUrl = (trace) => URL of a saved trace's full page, or null when the page is not available,
// extra.retryTraces = async (keys) => asks the server to look again for failed traces ([] = all failed ones),
// extra.gauges = { nodes: { name: [samples] } }: the server load during the recording (shows the Server load strip),
// extra.serverInfo = { license, system, blackbox }: System Info at recording start (shown in the Info tab),
// extra.apps = parsed app/apps.json: the apps exported at upload (shows the App tab),
// extra.appFileUrl = (file) => URL of an exported .jwa, or null when the files are not available here.
window.blackboxOpen = (videoSrc, sessionReport, extra) => {
  current = {
    video: videoSrc || null, report: sessionReport, systemLog: extra?.systemLog || null, traces: extra?.traces || null,
    gauges: extra?.gauges || null,
    serverInfo: extra?.serverInfo || null,
    apps: extra?.apps || null,
    title: extra?.title || '', info: extra?.info || {},
  };
  traceLink = extra?.traceUrl || null;
  traceRetry = extra?.retryTraces || null;
  appFileUrl = extra?.appFileUrl || null;
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
  current = { video: null, report: null, systemLog: null };
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
    // Its system-log.json is not shown here; system logs are shown on the server's replay page.
    const parts = await readZip(zip);
    if (!parts.has('session.json')) throw new Error(`${zip.name} has no session.json: it is not a BlackBox recording ZIP.`);
    current = { video: null, report: null, systemLog: null };
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
    current.systemLog = null;
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
  serverItems = prepareSystemLog(current.systemLog, r);
  // All-server captures add other threads' lines: a filter, the tab's own lines first
  if (serverItems.some((e) => e.other)) {
    FILTERS.server = [['tab', 'This tab only'], ['all', 'This tab + other server threads'], ['other', 'Other server threads only']];
  } else {
    delete FILTERS.server;
  }
  if ($('serverTab')) {
    $('serverTab').hidden = !current.systemLog;
    $('serverCount').textContent = serverItems.filter((e) => e.kind === 'log' && !e.other).length;
  }
  traceItems = prepareTraces(current.traces, r);
  updateTracesTab();
  if (tab === 'server' && !current.systemLog) tab = 'console';
  if (tab === 'traces' && !current.traces) tab = 'console';
  if ($('appsTab')) $('appsTab').hidden = !current.apps;
  if (tab === 'apps' && !current.apps) tab = 'console';
  issues = [
    ...r.console.filter((e) => e.level === 'error').map((e) => e.t),
    ...r.network.filter(isBad).map((e) => e.t),
    ...serverItems.filter((e) => isServerError(e) && !e.other).map((e) => e.t),
  ].sort((a, b) => a - b);
  buildAppSegments(r);
  collectAppProblems(r);
  drawLane();
  updateAppFilter();

  $('empty').hidden = true;
  $('app').hidden = false;
  showNetRange(null);
  renderMarkers();
  drawOverview();
  openLoad();
  renderInfo();
  renderApps();
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
  load.playhead.style.left = pct(now);
  lane.playhead.style.left = pct(now);
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
  if (e.target.closest('input, select, textarea, video, .drawer') || !hasVideo || !report) return;
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

// ---------- server load ----------

// Under the Issues timeline, same time axis: one node's load sampled every second by the plugin (GaugeSampler).
// Lines are % of their maximum (CPU, heap, busy request threads, DB connections); GC time is drawn as bars.
const LOAD_SERIES = [
  ['cpu', 'CPU', '--g-cpu', (s) => s.cpu, (s) => `${Math.round(s.cpu * 100)}%`],
  ['heap', 'Heap', '--g-heap', (s) => s.heapMax && s.heapUsed / s.heapMax, (s) => `${gb(s.heapUsed)} / ${gb(s.heapMax)} GB`],
  ['threads', 'Busy threads', '--g-threads', (s) => s.threadsMax && s.threadsBusy / s.threadsMax, (s) => `${s.threadsBusy} / ${s.threadsMax}`],
  ['db', 'DB connections', '--g-db', (s) => s.dbMax && s.dbActive / s.dbMax, (s) => `${s.dbActive} / ${s.dbMax}${s.dbWaiting ? `, ${s.dbWaiting} waiting` : ''}`],
];
let loadSamples = [];      // the shown node's samples, with t
const load = buildLoad();

function gb(bytes) {
  return (bytes / 1073741824).toFixed(bytes < 1073741824 ? 2 : 1);
}

function buildLoad() {
  const box = el('div', 'load');
  box.hidden = true;
  const head = el('div', 'ov-head');
  const node = el('select', 'load-node');
  node.title = 'Server (cluster node)';
  node.hidden = true;
  node.onchange = () => showLoadNode(node.value);
  const readout = el('span', 'load-readout');
  head.append(el('span', null, 'Server load'),
    ...LOAD_SERIES.map(([k, label]) => { const s = el('span'); s.append(el('i', `dot g-${k}`), label); return s; }),
    (() => { const s = el('span'); s.append(el('i', 'dot g-gc'), 'GC'); return s; })(), node, readout);
  const strip = el('div', 'ov-strip load-strip');
  strip.title = 'Server load (% of each maximum; GC time as bars). Click to seek.';
  const canvas = el('canvas');
  const playhead = el('div', 'playhead');
  strip.append(canvas, playhead);
  box.append(head, strip);
  $('timeline').after(box);
  strip.addEventListener('click', (e) => { if (report) seek(overviewMsIn(strip, e.clientX)); });
  strip.addEventListener('mousemove', (e) => showReadout(overviewMsIn(strip, e.clientX)));
  strip.addEventListener('mouseleave', () => showReadout(null));
  new ResizeObserver(() => drawLoad()).observe(strip);
  return { box, strip, canvas, playhead, node, readout };
}

function overviewMsIn(strip, clientX) {
  const box = strip.getBoundingClientRect();
  return Math.max(0, Math.min(1, (clientX - box.left) / box.width)) * durationMs;
}

function openLoad() {
  const nodes = Object.keys(current.gauges?.nodes || {});
  load.box.hidden = !nodes.length;
  load.node.replaceChildren(...nodes.map((n) => { const o = el('option', null, n); o.value = n; return o; }));
  load.node.hidden = nodes.length < 2;
  showLoadNode(nodes[0]);
}

function showLoadNode(name) {
  const start = Date.parse(report.recording?.startedAt);
  loadSamples = (current.gauges?.nodes?.[name] || []).map((s) => ({ ...s, t: start ? s.ts - start - serverOffset : 0 }));
  drawLoad();
  showReadout(null);
}

function drawLoad() {
  const c = load.canvas;
  const w = load.strip.clientWidth, h = load.strip.clientHeight;
  if (!w || !h) return;
  const scale = (window.devicePixelRatio || 1) * (parseFloat(document.documentElement.style.zoom) || 1);
  c.width = Math.round(w * scale);
  c.height = Math.round(h * scale);
  const g = c.getContext('2d');
  g.setTransform(scale, 0, 0, scale, 0, 0);
  g.clearRect(0, 0, w, h);
  if (!report || !durationMs || !loadSamples.length) return;
  const css = getComputedStyle(document.documentElement);
  const x = (ms) => (ms / durationMs) * w;
  const y = (f) => h - 2 - Math.max(0, Math.min(1, f)) * (h - 4);
  // GC: bars of the share of each second spent in GC
  g.fillStyle = css.getPropertyValue('--g-gc').trim();
  g.globalAlpha = 0.6;
  const barW = Math.max(2, x(1000) - 1);
  for (const s of loadSamples) if (s.gcMs) g.fillRect(x(s.t) - barW, y(s.gcMs / 1000), barW, h - 2 - y(s.gcMs / 1000));
  g.globalAlpha = 1;
  g.lineWidth = 1.5;
  for (const [, , color, value] of LOAD_SERIES) {
    g.strokeStyle = css.getPropertyValue(color).trim();
    g.beginPath();
    let drawing = false;
    for (const s of loadSamples) {
      const v = value(s);
      if (v == null || Number.isNaN(v)) { drawing = false; continue; }
      if (drawing) g.lineTo(x(s.t), y(v)); else g.moveTo(x(s.t), y(v));
      drawing = true;
    }
    g.stroke();
  }
}

new MutationObserver(() => drawLoad()).observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme', 'style'] });
matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => drawLoad());

// The values at the mouse (or the playhead when the mouse leaves)
function showReadout(ms) {
  if (!loadSamples.length) { load.readout.textContent = ''; return; }
  const at = ms ?? nowMs();
  let best = loadSamples[0];
  for (const s of loadSamples) if (Math.abs(s.t - at) < Math.abs(best.t - at)) best = s;
  const parts = [fmt(Math.max(0, best.t))];
  for (const [, label, , value, text] of LOAD_SERIES) if (value(best) != null && !Number.isNaN(value(best))) parts.push(`${label} ${text(best)}`);
  if (best.gcMs != null) parts.push(`GC ${best.gcMs} ms`);
  load.readout.textContent = parts.join(' · ');
}

// ---------- apps ----------

// Which Joget app a URL names, by the plugin's rules (AppExport.appOf): userview, embed, form/datalist, builder and
// JSON API paths, process definition ids (app#version#process) and appId parameters. App icons and userview
// screenshots (App Center loads them for every app) name none.
const APP_ID = '([A-Za-z0-9_-]+)';
const APP_PATTERNS = [
  new RegExp(`/web/(?:embed/)?(?:userview|ulogin|mobile|mapp)/${APP_ID}(?:/|$|\\?)`),
  new RegExp(`/web/(?:client/|console/|json/(?:console/)?)?app/${APP_ID}/`),
  new RegExp(`/web/json/data/(?:form|list|app)/(?:\\w+/)?${APP_ID}/\\d+/`),
  new RegExp(`(?:^|[/=&?])${APP_ID}(?:#|%23)(?:\\d+|latest)(?:#|%23)\\w+`),
  new RegExp(`[?&]appId=${APP_ID}(?:&|$)`),
];
const APP_RESOURCE = /\/web\/(?:app\/[A-Za-z0-9_-]+\/(?:\d+\/)?resources\/|userview\/screenshot\/)/;
const APP_COLORS = 6;      // --app-0 .. --app-5 in viewer.css, by order of first appearance

let appSegments = [];      // [{ app, from, to }]: whose page the tab showed when (null app: a page of no app)
let appProblems = {};      // app id -> [{ t, kind, item, text }]: failed requests, console and server errors
let appColor = {};         // app id -> colour index
const appOfCache = new WeakMap();

function appOf(url) {
  if (!url) return null;
  let u = url;
  try { u = decodeURIComponent(url); } catch (e) { /* keep it as it is */ }
  if (APP_RESOURCE.test(u)) return null;
  for (const p of APP_PATTERNS) {
    const m = p.exec(u);
    if (m) return m[1];
  }
  return null;
}

// The page the tab showed: a document's own URL, else the request's Referer (only paths under /web/, so a Referer cut
// to the origin says nothing). Each one says which app's page was on screen from then on.
function buildAppSegments(r) {
  const signals = [];
  for (const n of r.network) {
    const page = n.type === 'Document' ? n.url : header(n.requestHeaders, 'referer');
    if (page && page.includes('/web/')) signals.push({ t: n.t, app: appOf(page) });
  }
  for (const n of r.navigations || []) signals.push({ t: n.t, app: appOf(n.url) });
  signals.sort((a, b) => a.t - b.t);
  let segs = [];
  for (const s of signals) {
    const last = segs[segs.length - 1];
    if (last && last.app === s.app) continue;
    if (last) last.to = s.t;
    segs.push({ app: s.app, from: segs.length ? s.t : 0, to: durationMs });
  }
  // A late request of the page just left flips back for a moment: under half a second between the same app, merged
  for (let i = 1; i < segs.length - 1; i++) {
    if (segs[i].to - segs[i].from < 500 && segs[i - 1].app === segs[i + 1].app) {
      segs[i - 1].to = segs[i + 1].to;
      segs.splice(i, 2);
      i--;
    }
  }
  appSegments = segs;
  appColor = {};
  for (const sg of segs) if (sg.app && !(sg.app in appColor)) appColor[sg.app] = Object.keys(appColor).length % APP_COLORS;
}

function pageAppAt(t) {
  for (const sg of appSegments) if (t >= sg.from && t < sg.to) return sg.app;
  return appSegments.length && t >= durationMs ? appSegments[appSegments.length - 1].app : null;
}

// The app of a list row: the one its URL names, else the one on screen then. Other server threads' lines: none.
function itemApp(item) {
  if (appOfCache.has(item)) return appOfCache.get(item);
  let app;
  if (item.kind === 'request' || item.kind === 'log') {
    app = item.other ? null : appOf(item.kind === 'request' ? item.url : String(item.req || '').split(' ')[1]) || pageAppAt(item.t);
  } else {
    app = appOf(item.url) || pageAppAt(item.t);
  }
  appOfCache.set(item, app);
  return app;
}

function collectAppProblems(r) {
  appProblems = {};
  const add = (app, p) => { if (app) (appProblems[app] ||= []).push(p); };
  for (const n of r.network.filter(isBad)) {
    add(itemApp(n), { t: n.t, kind: 'network', item: n, text: `${statusText(n)} ${n.method} ${safeUrl(n.url)?.pathname || n.url}` });
  }
  for (const c of r.console.filter((e) => e.level === 'error')) add(pageAppAt(c.t), { t: c.t, kind: 'console', item: c, text: c.text });
  for (const e of serverItems.filter((x) => isServerError(x) && !x.other)) add(itemApp(e), { t: e.t, kind: 'server', item: e, text: serverText(e) });
  for (const list of Object.values(appProblems)) list.sort((a, b) => a.t - b.t);
}

function appName(id) {
  const a = (current.apps?.apps || []).find((x) => x.id === id);
  return a?.name || (id === 'appcenter' ? 'App Center' : id);
}

// "2 server errors · 1 failed request"
function problemText(list) {
  const n = (kind) => list.filter((p) => p.kind === kind).length;
  const part = (k, one) => (n(k) ? `${n(k)} ${one}${n(k) === 1 ? '' : 's'}` : '');
  return [part('server', 'server error'), part('network', 'failed request'), part('console', 'console error')].filter(Boolean).join(' · ');
}

function inUseMs(app) {
  return appSegments.filter((sg) => sg.app === app).reduce((sum, sg) => sum + sg.to - sg.from, 0);
}

// Under the Issues timeline, same time axis: which app's page the tab showed. Click a stretch to seek there and list
// only that app's requests and log lines.
const lane = buildLane();

function buildLane() {
  const box = el('div', 'lane');
  box.hidden = true;
  const head = el('div', 'ov-head');
  const strip = el('div', 'app-lane');
  strip.title = 'Which app\'s page the tab showed. Click to seek.';
  const segs = el('div', 'app-segs');
  const playhead = el('div', 'playhead');
  strip.append(segs, playhead);
  box.append(head, strip);
  $('timeline').after(box);
  strip.addEventListener('click', (e) => { if (report && !e.target.closest('.app-seg')) seek(overviewMsIn(strip, e.clientX)); });
  return { box, head, segs, playhead };
}

function drawLane() {
  const shown = appSegments.filter((sg) => sg.app);
  lane.box.hidden = !shown.length;
  lane.head.replaceChildren(el('span', null, 'Apps'), ...Object.keys(appColor).map((id) => {
    const s = el('span');
    s.append(el('i', `dot app-${appColor[id]}`), appName(id));
    return s;
  }));
  lane.segs.replaceChildren(...shown.map((sg) => {
    const probs = (appProblems[sg.app] || []).filter((p) => p.t >= sg.from && p.t < sg.to);
    const requests = report.network.filter((n) => n.t >= sg.from && n.t < sg.to).length;
    const d = el('div', `app-seg app-${appColor[sg.app]}`, (probs.length ? '⚠ ' : '') + appName(sg.app));
    d.style.left = pct(sg.from);
    d.style.width = pct(sg.to - sg.from);
    const a = (current.apps?.apps || []).find((x) => x.id === sg.app);
    d.title = `${appName(sg.app)} (${sg.app})${a?.version != null ? ` v${a.version}` : ''}\n`
      + `${fmt(sg.from)} – ${fmt(sg.to)} · ${requests} request${requests === 1 ? '' : 's'}`
      + (probs.length ? `\n⚠ ${problemText(probs)}` : '') + '\nClick: jump here · list only this app';
    d.onclick = () => { seek(sg.from); pickApp(sg.app); };
    return d;
  }));
}

// ---------- system log ----------

// Entries have ts = server clock (epoch ms). t = ts - recording start, minus the server clock's offset, measured
// from the X-BlackBox-Time header the plugin adds to the tagged responses (server time when the request arrived).
// Small offsets are mostly network delay, so only an offset over 250 ms is corrected.
function prepareSystemLog(log, r) {
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

// Network and System logs: only one app's rows (its URLs, or made while its page was on screen)
const appFilter = el('select', 'app-filter');
appFilter.title = 'Only the requests and log lines of one app';
appFilter.onchange = () => renderList();
$('filter').after(appFilter);

function updateAppFilter() {
  const ids = [...new Set([...Object.keys(appColor), ...report.network.map(itemApp)].filter(Boolean))];
  const keep = ids.includes(appFilter.value) ? appFilter.value : '';
  appFilter.replaceChildren(...[['', 'All apps'], ...ids.map((id) => [id, appName(id)])].map(([v, label]) => {
    const o = el('option', null, label);
    o.value = v;
    return o;
  }));
  appFilter.value = keep;
  appFilter.dataset.count = ids.length;
}

function pickApp(id) {
  if (![...appFilter.options].some((o) => o.value === id)) return;
  appFilter.value = id;
  if (tab === 'network' || tab === 'server') renderList();
}

function setTab(name) {
  tab = name;
  document.querySelectorAll('.tabs button').forEach((b) => b.classList.toggle('active', b.dataset.tab === name));
  const isInfo = name === 'info' || name === 'apps'; // a page of text: no toolbar or list
  $('toolbar').hidden = isInfo;
  $('list').hidden = isInfo;
  $('info').hidden = name !== 'info';
  if ($('appsPage')) $('appsPage').hidden = name !== 'apps';
  if (isInfo) tabNote.hidden = true;
  ov.box.hidden = name !== 'network'; // shown: the ResizeObserver draws it
  if (!isInfo) {
    $('filter').hidden = !FILTERS[name];
    appFilter.hidden = !(name === 'network' || name === 'server') || Number(appFilter.dataset.count || 0) < 2;
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
    if (f === 'other' ? !item.other : f !== 'all' && item.other) return false;
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
    .filter((it) => !appFilter.value || appFilter.hidden || itemApp(it) === appFilter.value)
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
  const row = el('div', `row server lvl-${e.lvl}${e.other ? ' other' : ''}`);
  row.append(
    el('span', 't', fmt(e.t)),
    el('span', null, e.kind === 'request' ? 'request' : e.lvl),
    el('span', null, serverText(e)),
    el('span', 'muted right', e.kind === 'request' ? '' : (e.other ? `${e.thread || 'other thread'} · ` : '') + String(e.logger || '').split('.').pop()),
  );
  if (e.other) row.title = `Another thread (${e.thread || '?'}), not the recorded tab: `;
  row.title = (row.title || '') + (e.kind === 'request' ? `${e.method} ${e.url}` : String(e.message ?? ''));
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

// Trace entries have ts = server clock, like the system log: same start and clock offset (prepareSystemLog runs first)
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
    'Server time': utc(item.ts),
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
      'Server time': utc(item.ts),
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
      'Server time': utc(item.ts),
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
      section(body, 'System logs', req ? serverList([req, ...requestLines(req)]) : el('div', 'muted', 'No server entry for this request.'));
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
      'Tab URL': rec.tab?.url,
      'Started': utc(rec.startedAt),
      'Stopped': utc(rec.stoppedAt),
      'Stop reason': rec.stopReason,
      'Captured surface': rec.capturedSurface,
      'Sound': soundInfo(rec.audio),
      'Console errors': s.errors,
      'Failed requests': s.failedRequests,
      'Navigations': (report.navigations || []).length,
      // the counts are on the tabs; a capture that didn't reach the server still says why here
      'Server data': current.systemLog ? undefined : captureInfo(rec.serverCapture || rec.systemLog), // systemLog: extension 1.1 and older
      'Server clock offset': serverOffset ? `${serverOffset} ms (corrected)` : undefined,
      'User agent': rec.userAgent,
      'Recorded with': report.tool,
    }),
  );
  // Server snapshot at recording start (plugin page): grouped like Glowroot's Environment page, then everything
  // Settings > System Info shows. JVM arguments keep one per line.
  const si = current.serverInfo;
  if (si) {
    const body = $('info');
    const period = si.recentChanges?.Period;
    // Sections whose changes we can track first: Changes, Joget (with its start); then the rest
    const groups = [['recentChanges', period ? `Changes in ${period}` : 'Changes'], ['joget', 'Joget'], ['plugins', 'Plugin'],
      ['host', 'Host'], ['cluster', 'Cluster'], ['java', 'Java'], ['database', 'Database'],
      ['connectionPool', 'DB pool'], ['license', 'License'], ['systemProperties', 'System properties']];
    const served = nodesServed(current.systemLog);
    for (const [key, title] of groups) {
      let values = key === 'cluster' && si.cluster ? clusterRows(si.cluster, served) : si[key];
      if (key === 'joget') values = { ...si.joget, ...si.process }; // the process start is Joget's start
      if (key === 'recentChanges') values = recentChanges(si.recentChanges);
      if (key === 'plugins' && values) values = pluginRows(values);
      if (key === 'host' && values) values = renameKeys(values, { 'Physical memory': 'Total RAM' }); // older recordings
      if (key === 'host' && values && clusterRows(si.cluster || {}).Nodes) values = withoutKeys(values, ['Node']); // in Cluster > Nodes
      if (key === 'license' && values) values = withoutKeys(values, LICENSE_SHOWN_ELSEWHERE);
      if (key === 'systemProperties' && values) values = withoutKeys(values, [...SHOWN_ELSEWHERE, ...setByArguments(si.java?.Arguments, values)]);
      if (!values || !Object.keys(values).length) continue;
      const list = kv(infoOrder(key, values));
      list.classList.add('multiline');
      section(body, title, list);
    }
  }
}

// Recent changes as shown: app versions and wflow files (Joget's start is in the Joget section); nothing left (or
// "None found") hides the section.
function recentChanges(c) {
  const shown = Object.fromEntries(Object.entries(c || {}).filter(([k]) => ['App versions', 'wflow files'].includes(k)));
  return Object.keys(shown).length ? shown : null;
}

// The Plugin section: each jar's date and size, " · not loaded" when it holds no loaded plugin
function pluginRows(plugins) {
  return Object.fromEntries(Object.entries(plugins).map(([jar, v]) => [jar, String(v).replace(' (check the Joget log)', '')]));
}

// System properties also in the Joget, Host, Cluster and Java sections (Enterprise's SysInfo adds the platform.* ones)
const SHOWN_ELSEWHERE = ['platform.jvm.total', 'platform.jvm.max', 'platform.jvm.processors', 'platform.jvm.used',
  'wflow.name', 'os.name', 'os.version', 'os.arch', 'java.version', 'java.runtime.version', 'java.vm.name',
  'java.vm.vendor', 'user.timezone'];

// License keys also in the Joget and Database sections
const LICENSE_SHOWN_ELSEWHERE = ['platform.version', 'platform.db.profile', 'platform.db.jdbc.driver'];

// System properties a -D argument sets to the same value (the last -D of a name wins, as in Java)
function setByArguments(args, props) {
  const set = {};
  for (const line of String(args || '').split('\n')) {
    const m = line.match(/^-D([^=]+)=(.*)$/);
    if (m) set[m[1]] = m[2];
  }
  return Object.keys(set).filter((k) => String(props[k]) === set[k]);
}

function renameKeys(obj, names) {
  return Object.fromEntries(Object.entries(obj).map(([k, v]) => [names[k] || k, v]));
}

function withoutKeys(obj, keys) {
  return Object.fromEntries(Object.entries(obj).filter(([k]) => !keys.includes(k)));
}

// Cluster and Nodes from Joget's node lists: the registered nodes (servers.json) and the active ones (the license's
// node:: entries), each "name (ip)". With neither (one node, or a license without nodes) this node alone. The
// wflow-cluster agent alone doesn't make a cluster (it's in Java Arguments). Each node gets the recorded requests it
// served; "this node" (where the Info snapshot was taken) only matters when there are several.
function clusterRows(c, served) {
  const parse = (list) => String(list || '').split('\n').filter(Boolean).map((line) => {
    const m = line.match(/^(.*?)(?: \((.*)\))?$/);
    return { name: m[1], ip: m[2] };
  });
  const registered = parse(c['Registered nodes']);
  const active = parse(c['Active nodes']);
  const self = c['Joget node name'] && !c['Joget node name'].startsWith('(') ? c['Joget node name'] : null;
  const nodes = new Map();
  for (const n of [...registered, ...active]) nodes.set(n.name, { ...nodes.get(n.name), ...n });
  if (self && !nodes.has(self)) nodes.set(self, { name: self });
  const several = nodes.size > 1; // Joget's nodes only: older recordings name served nodes by hostname
  for (const name of Object.keys(served?.counts || {})) if (!nodes.has(name)) nodes.set(name, { name });
  // Session replication (older recordings): Joget's sessions are never shared, whatever Tomcat's cluster says
  const rows = Object.fromEntries(Object.entries(c).filter(([k]) => !['Joget node name', 'Cluster mode', 'Registered nodes',
    'Active nodes', 'Session replication'].includes(k)));
  rows['Cluster'] = several ? `Yes: ${nodes.size} nodes` : 'No: one node';
  rows['Nodes'] = [...nodes.values()].sort((a, b) => a.name.localeCompare(b.name)).map((n) => {
    const count = served?.counts[n.name];
    return [
      n.ip ? `${n.name} (${n.ip})` : n.name,
      several && n.name === self ? 'this node' : null,
      count ? `${count} request${count === 1 ? '' : 's'}` : null,
      several && active.length && !active.some((a) => a.name === n.name) ? 'not active' : null,
    ].filter(Boolean).join(' · ');
  }).join('\n') || undefined;
  if (served?.switches) rows['Node switches'] = served.switches;
  return rows;
}

// Which node served each recorded request (system log request entries carry it), in order: the requests per node and
// how often the next request went to another node. Switches usually mean no sticky sessions, or a node failed over.
function nodesServed(log) {
  const reqs = (log?.entries || []).filter((e) => e.kind === 'request' && e.node);
  if (!reqs.length) return null;
  const counts = {};
  let switches = 0;
  reqs.forEach((e, i) => {
    counts[e.node] = (counts[e.node] || 0) + 1;
    if (i && e.node !== reqs[i - 1].node) switches++;
  });
  return { counts, switches };
}

// The server's JSON keeps no key order: labels first in this order, then file and pool keys A-Z.
const INFO_LABELS = {
  database: ['Profile', 'JDBC driver', 'JDBC URL', 'User'],
  connectionPool: ['Ignored file keys', 'Pool size', 'Active connections', 'Idle connections', 'Waiting threads'],
  plugins: [], // jar files A-Z
  recentChanges: ['App versions', 'wflow files'],
  host: ['OS', 'Node', 'Hostname', 'Processors', 'Total RAM', 'Free RAM'],
  joget: ['Version', 'Edition', 'Application server', 'Started', 'Uptime', 'Process id', 'Time zone', 'Glowroot', 'Glowroot version'],
  cluster: ['Cluster', 'Nodes', 'Node switches', 'Tomcat jvmRoute', 'This session\'s route'],
};

function infoOrder(group, obj) {
  const labels = INFO_LABELS[group];
  if (!labels) return obj;
  const rank = (k) => (labels.includes(k) ? labels.indexOf(k) : labels.length);
  const keys = Object.keys(obj).sort((a, b) => rank(a) - rank(b) || a.localeCompare(b));
  return Object.fromEntries(keys.map((k) => [k, obj[k]]));
}

// ---------- app tab ----------

let appFileUrl = null;

// The apps the recording used, exported at upload (plugin page and downloaded HTML; the .jwa files only on the server).
// One folded block per app, apps with problems first; apps not exported are one grey line.
function renderApps() {
  const page = $('appsPage');
  if (!page) return;
  page.replaceChildren();
  if (!current.apps) return;
  const all = current.apps.apps || [];
  const probs = (a) => appProblems[a.id] || [];
  const withProblems = all.filter((a) => probs(a).length).sort((a, b) => probs(b).length - probs(a).length);
  const rest = all.filter((a) => !probs(a).length && a.state === 'saved');
  const notExported = all.filter((a) => !probs(a).length && a.state !== 'saved');
  const used = all.length;
  page.append(el('p', 'apps-head', `${used} app${used === 1 ? '' : 's'} used`));
  const when = utc(current.apps.exportedAt);
  page.append(el('p', 'note', `Exported at${when ? ` ${when}` : ''}.`
    + (appFileUrl ? '' : ' The .jwa files are in the recording ZIP, not in this file.')));
  if (!all.length) page.append(el('p', null, 'No apps were found in the recorded URLs.'));
  for (const a of [...withProblems, ...rest]) page.append(appBlock(a));
  if (withProblems.length + rest.length === 1) page.querySelector('details.app').open = true; // the only app: no need to fold it
  if (notExported.length) {
    page.append(el('p', 'note muted', 'Not exported: ' + notExported.map((a) => `${a.id} (${a.reason || a.state})`).join(', ')));
  }
}

// One app, folded to its name, requests, time in use and Download .jwa. Its errors are in the Console, Network and
// System logs tabs (App filter).
function appBlock(a) {
  const d = el('details', 'app');
  const summary = el('summary');
  summary.append(el('strong', null, a.name || a.id)); // id and version are in the rows below
  const ms = inUseMs(a.id);
  summary.append(el('span', 'app-badge', `${a.requests} request${a.requests === 1 ? '' : 's'}${ms ? ` · ${fmt(ms)} in use` : ''}`));
  if (a.state === 'saved' && a.file && appFileUrl) {
    const link = el('a', 'button app-download', 'Download .jwa');
    link.href = appFileUrl(a.file);
    link.title = `${a.file}: import it in App Center > Import App`;
    summary.append(link);
  }
  d.append(summary);
  const sample = a.sample;
  d.append(kv({
    'App id': a.id,
    'Version': a.version != null ? `${a.version}${a.published ? ' (published)' : ' (not published)'}` : undefined,
    'Version in the URLs': a.urlVersion,
    'Last modified': utc(a.modified),
    'Size': a.bytes != null ? `${Math.max(1, Math.round(a.bytes / 1024))} KB` : undefined,
    'Not exported': a.state !== 'saved' ? a.reason || a.state : undefined,
    'Sample data': sample?.error ? `could not be added: ${sample.error}`
      : sample ? (sample.tables?.length ? (sample.mode === 'real' ? 'real values' : 'masked') : 'none: the app has no form rows') : undefined,
  }));
  // The captured tables and their rows (in the .jwa: importing it imports them)
  if (sample?.tables?.length) {
    d.append(kv(Object.fromEntries(sample.tables.map((t) => [t.table, `${t.rows} row${t.rows === 1 ? '' : 's'}`]))));
  }
  for (const p of a.processes || []) d.append(processBlock(p));
  return d;
}

// One process instance (read only: Joget can't import running processes)
function processBlock(p) {
  const d = el('details', 'process');
  d.append(el('summary', null, `Process: ${p.name || p.processDefId} · ${p.state || ''} · ${p.id}`));
  d.append(kv({
    'Process definition': p.processDefId,
    'Record id in the sample': p.recordId,
    'Requester': p.requester,
    'Started': utc(p.started || p.created),
    'Finished': utc(p.finished),
  }));
  const vars = p.variables || {};
  if (Object.keys(vars).length) {
    d.append(el('h4', null, 'Workflow variables'));
    d.append(kv(vars));
  }
  if (p.activities?.length) {
    d.append(el('h4', null, `Activities${p.activitiesCut ? ' (first 100)' : ''}`));
    // One row per activity; its id is in the name's tooltip
    const table = el('table', 'act-table');
    const head = el('tr');
    const finished = p.activities.some((x) => x.finished); // no column when none has finished
    for (const h of ['#', 'Activity', 'State', 'User', 'Created', ...(finished ? ['Finished'] : [])]) head.append(el('th', null, h));
    table.append(head);
    p.activities.forEach((x, i) => {
      const row = el('tr');
      const name = el('td', null, x.name || x.defId);
      name.title = x.id;
      row.append(el('td', 'num', String(i + 1)), name, el('td', null, x.state || ''),
        el('td', null, x.acceptedBy ? `done by ${x.acceptedBy}` : x.assignees?.length ? `assigned to ${x.assignees.join(', ')}` : ''),
        el('td', 'date', utc(x.created) || ''));
      if (finished) row.append(el('td', 'date', utc(x.finished) || ''));
      table.append(row);
    });
    d.append(table);
  }
  return d;
}

// ---------- features drawer ----------

// Opened by the header's Features button; it covers the panel, not the video. ✕, Esc, the button again or a click
// outside the drawer close it.
$('featuresBtn').onclick = () => ($('featuresDrawer').hidden ? openFeatures() : closeFeatures());
$('featuresClose').onclick = () => closeFeatures();
document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape' && !$('featuresDrawer').hidden) closeFeatures();
});

function openFeatures() {
  renderFeatures();
  $('featuresDrawer').hidden = false;
  $('featuresBtn').classList.add('active');
  $('featuresClose').focus();
}

document.addEventListener('pointerdown', (e) => {
  if (!$('featuresDrawer').hidden && !e.target.closest('#featuresDrawer, #featuresBtn')) closeFeatures(false);
});

// refocus: false after a click elsewhere, so focus stays where the user clicked
function closeFeatures(refocus = true) {
  $('featuresDrawer').hidden = true;
  $('featuresBtn').classList.remove('active');
  if (refocus) $('featuresBtn').focus();
}

// The replay pages that use this viewer. The page that loads a recording sets window.blackboxPage: replay.js
// 'extension', the plugin's view.js 'server', its downloaded file (export.js) 'file'; dist/blackbox-viewer.html sets
// nothing: 'viewer'.
const PAGES = {
  extension: 'Extension',
  server: 'Joget server (plugin)',
  file: 'Downloaded HTML',
  viewer: 'Standalone viewer',
};
const ALL = Object.keys(PAGES);

// [title, what it does, the pages that have it, notes for some pages]
const FEATURES = [
  ['Video replay', 'Plays the recorded window, screen or tab, with the page\'s sound and the microphone when they were recorded (the Info tab says which). Space plays and pauses, ← / → jump 5 seconds. The clock under the video shows where you are.', ALL],
  ['Console tab', 'Console logs, uncaught exceptions, browser messages and page navigations from the recorded tab, in time order. Tick levels to show, pick a source, or type in Filter.', ALL],
  ['Network tab', 'Every request of the recorded tab with method, status, type and time. Filter by kind (Fetch / XHR, documents, failed, other). Click a row for headers, request and response bodies (JSON pretty-printed) and Copy as cURL.', ALL,
    { server: 'A request\'s details also show its system log lines and its Glowroot trace, when they were captured.', file: 'A request\'s details also show its system log lines, when they were included.' }],
  ['Lists follow the video', 'Rows before the playhead are normal, later rows are faded (Hide future hides them). Follow keeps the latest row in view. Click a row to jump the video there.', ALL],
  ['Issues timeline', 'The strip under the video marks console errors (red), failed requests (orange) and navigations (blue). Click it to seek. ◀ Prev issue / Next issue ▶ jump between the problems.', ALL,
    { server: 'Server errors from the system logs are marked too (purple).', file: 'Server errors are marked too (purple) when the system logs are in the file.' }],
  ['Apps lane', 'Under the Issues timeline: which app\'s page the tab showed, one colour per app, ⚠ where that app had a problem. Hover for its time, requests and problems; click to jump there and list only that app on the Network and System logs tabs.', ALL],
  ['App filter', 'On the Network and System logs tabs when the tab used more than one app: list only one app\'s rows (its URLs, or made while its page was on screen).', ALL],
  ['App tab', 'The apps the recording used, exported as .jwa with sample data and process data. Each app is folded to its requests, time in use and Download .jwa; open it for its version, the captured tables with their rows, and its processes. Errors are in the Console, Network and System logs tabs (App filter).', ['server', 'file'],
    { file: 'The .jwa files themselves are only in the ZIP download.' }],
  ['Requests strip', 'At the top of the Network tab: one bar per request, from start to end, coloured by kind. Drag across it to list only the requests in that time range; double-click or ✕ shows all again.', ALL],
  ['System logs tab', 'The Joget log lines written while serving the recorded requests, lined up with the video and filtered by level. Only for users allowed to view server data, and only when the plugin captured them.', ['server', 'file'],
    { file: 'Included only when the person who downloaded it was allowed to view server data.' }],
  ['Traces tab', 'Glowroot traces of slow and failed requests: time, CPU, SQL count and time, slowest queries and the error.', ['server', 'file'],
    { server: 'Open full trace shows Glowroot\'s page. Retry looks again for failed ones. The tab updates by itself while traces are still being collected.', file: 'Summaries only; the full trace pages are in the ZIP download.' }],
  ['Server load strip', 'Under the timeline: CPU, heap, busy request threads and database connections (as % of each maximum) and GC time, sampled every second on the server. Hover for values, click to seek.', ['server', 'file']],
  ['Info tab', 'The recording: tab title and URL, start, stop, length, stop reason, counts and the browser.', ALL,
    { extension: 'Also says whether server data was captured; they show on the server after the upload.', server: 'Also a snapshot of the server at recording start: host, process, Java, Joget, database, connection pool, license and system properties.', file: 'Also the server snapshot at recording start, when it was included.' }],
  ['Where this copy is', 'The first label in the status bar says where the open copy lives (In this browser, In folder, Opened from files, On <server>, Downloaded file), so Delete is never mistaken for deleting another copy.', ALL],
  ['Notes', 'The notes typed at upload (steps, what went wrong), under the header.', ['server', 'file']],
  ['Upload', 'Sends the recording to the Upload URL\'s server, using your login there. After a recording stops, it is uploaded by itself and the server\'s page opens when you are logged in (Settings: Open recordings on the server when you are logged in). When that automatic upload fails, the button shows ⚠ Upload: hover for the reason; click to check again and, if it still fails, see the fix (change the Upload URL, log in, allow access) in place of the form.', ['extension']],
  ['Move to folder', 'Moves recording.webm and session.json to Downloads/blackbox/ to free browser storage. Link that folder on the Recordings page to keep it listed.', ['extension']],
  ['Download ZIP', 'One ZIP of the recording, to import on a BlackBox server or in another browser.', ['extension', 'server'],
    { server: 'Includes the system logs, traces and Glowroot data for users allowed to view them; while the Glowroot data is still being built (about a minute after the recording stopped) it waits.' }],
  ['Download Glowroot data', 'This recording cut out of each server node\'s Glowroot data, in Glowroot\'s own format: the traces of the recorded requests, plus the aggregates and gauges of all traffic during the recording. Open it in a standalone Glowroot of the same version (README.txt inside). Built about a minute after the recording stops, when the plugin setting "Copy Glowroot data for the recording" is on; only for users allowed to view server data.', ['server']],
  ['Download HTML', 'One .html file that replays the recording in any browser, with no Joget, plugin or extension. Share it only with people who may see what is inside.', ['server']],
  ['Delete', 'Deletes this copy.', ['extension', 'server'],
    { extension: 'Removes only the copy in this browser or folder, never one on a server.', server: 'Removes it from the server for everyone; shown only to users allowed to delete it.' }],
  ['Open files / drag and drop', 'Replays recording.webm + session.json, or a recording ZIP, from your disk.', ['extension', 'viewer']],
  ['◀ Older / Newer ▶', 'Opens the next recording in the list, with its position (e.g. 5 / 6).', ['extension', 'server'],
    { extension: 'Recordings in browser storage and the linked folder.', server: 'The recordings you can see.' }],
  ['← Recordings', 'Back to the recordings list.', ['extension', 'server']],
  ['Link to a moment', 'The address carries the playhead time (?t=5.8) and follows it. Copy the address to share that moment; opening it starts there.', ['extension', 'server', 'file']],
  ['Resize', 'Drag the line between the video and the panel, or above a row\'s details. Double-click a line to reset it. Remembered in this browser.', ALL],
  ['Text size and Theme', 'Set in the extension\'s Settings.', ['extension', 'server'],
    { server: 'Used when the BlackBox extension\'s Upload URL is this server. Without it the page follows the system theme; use the browser\'s zoom.' }],
];

function renderFeatures() {
  const here = PAGES[window.blackboxPage] ? window.blackboxPage : 'viewer';
  const body = $('features');
  const intro = el('p', 'muted');
  intro.append('You are on: ', el('strong', null, PAGES[here]), '. Features this page does not have are marked.');
  body.replaceChildren(intro);
  for (const [title, text, where, notes] of FEATURES) {
    const h = el('h3', null, title);
    if (!where.includes(here)) h.append(' ', el('span', 'badge', 'Not on this page'));
    const line = el('p', 'feature-where', 'Where: ');
    for (const p of where) line.append(el('span', p === here ? 'badge yes' : 'badge', PAGES[p]));
    body.append(h, el('p', null, text), line);
    if (notes?.[here] && where.includes(here)) body.append(el('p', 'feature-note', 'On this page: ' + notes[here]));
  }
}

// recording.audio = { page, pageSource?: 'tab' | 'system', mic, message? }; absent before the extension recorded sound
function soundInfo(a) {
  if (!a) return 'not recorded (made before BlackBox recorded sound)';
  const page = a.pageSource === 'system' ? 'computer audio' : a.pageSource === 'tab' ? 'tab audio' : 'page audio';
  const parts = [a.mic ? 'mic' : '', a.page ? page : ''].filter(Boolean);
  return (parts.join(' + ') || 'none') + (a.message ? ` (${a.message})` : '');
}

function captureInfo(s) {
  const log = current.systemLog;
  if (log) {
    const n = serverItems.filter((e) => e.kind === 'log' && !e.other).length;
    const other = serverItems.filter((e) => e.other).length;
    const requests = serverItems.filter((e) => e.kind === 'request').length;
    return `${n} log lines, ${requests} requests${other ? `, ${other} lines of other server threads` : ''}`
      + (log.truncated ? ' (cut off at the size limit)' : '');
  }
  if (!s) return undefined;
  if (s.status === 'on') return `Captured on ${s.server}; shown on the server's replay page after upload`;
  // Recordings made before the extension said "Could not reach" carry fetch()'s own "Failed to fetch"
  const message = s.message === 'Failed to fetch' && s.server ? `Could not reach ${s.server}` : s.message;
  return `Not captured${message ? ': ' + message : ''}`;
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

// One date/time format for the reporter and the investigator: UTC, whatever their timezone.
// A missing value gives undefined (kv() skips it); a bad one is shown as it is instead of throwing.
function utc(v) {
  if (v == null || v === '') return undefined;
  const d = new Date(v);
  return isNaN(d) ? String(v) : d.toISOString().replace('T', ' ').slice(0, 19) + ' UTC';
}

function showError(e) {
  $('error').hidden = !e;
  $('error').textContent = e ? (e.message || String(e)) : '';
}
