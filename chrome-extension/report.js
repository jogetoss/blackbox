// Builds session.json, and recovers recordings that a crash interrupted (see "crash safety" in idb.js).
// Used by the recorder, the background service worker and the Recordings page.

// meta: { startedAt (ms), settings: { displaySurface }, tab: { url, title } }
function buildReport(meta, consoleEntries, navigations, network, stoppedAt, reason) {
  // "Slow" = Glowroot's slow threshold, sent by the plugin at recording start; 2 s without a server
  const slowMs = meta.systemLog?.slowMs || 2000;
  return {
    tool: 'blackbox ' + (chrome.runtime.getManifest().version_name || chrome.runtime.getManifest().version),
    note: 't = milliseconds since recording start = position in recording.webm',
    recording: {
      startedAt: new Date(meta.startedAt).toISOString(),
      stoppedAt: new Date(stoppedAt).toISOString(),
      durationMs: stoppedAt - meta.startedAt,
      stopReason: reason,
      capturedSurface: meta.settings?.displaySurface, // "window", "monitor" or "browser" (tab)
      audio: meta.audio, // { page, pageSource?: 'tab' | 'system', mic, message? }: which sound recording.webm has (absent: made before sound support)
      tab: meta.tab,
      userAgent: meta.userAgent || navigator.userAgent,
      systemLog: meta.systemLog ? { status: meta.systemLog.status, id: meta.systemLog.id, server: meta.systemLog.server, message: meta.systemLog.message } : undefined,
    },
    summary: {
      console: consoleEntries.length,
      errors: consoleEntries.filter((e) => e.level === 'error').length,
      requests: network.length,
      failedRequests: network.filter((n) => n.failed || n.status >= 400).length,
      slowRequests: network.filter((n) => n.durationMs >= slowMs).length,
      slowThresholdMs: slowMs,
    },
    navigations,
    console: consoleEntries,
    network,
  };
}

function recordingId(startedAtMs) {
  return new Date(startedAtMs).toISOString().replace(/[:.]/g, '-').slice(0, 19);
}

// Turns every partial recording that is not running right now into a normal recording, marked recovered.
// Returns the recovered ids.
async function recoverPartials() {
  const keys = await idbPartialKeys();
  const ids = [...new Set(keys.filter((k) => k.endsWith(':meta')).map((k) => k.slice(0, -5)))];
  if (!ids.length) return [];
  const running = (await chrome.storage.session.get('rec')).rec;
  const runningId = running?.startedAt ? recordingId(running.startedAt) : null;
  const recovered = [];
  for (const id of ids) {
    if (id === runningId) continue;
    const meta = await idbPartialGet(id + ':meta');
    const seq = (prefix) => keys.filter((k) => k.startsWith(prefix)).sort((a, b) => Number(a.slice(prefix.length)) - Number(b.slice(prefix.length)));
    const videoParts = [];
    for (const k of seq(id + ':v:')) videoParts.push(await idbPartialGet(k));
    const logs = { console: [], navigations: [], network: [] };
    for (const k of seq(id + ':l:')) {
      const part = await idbPartialGet(k);
      for (const name of Object.keys(logs)) logs[name].push(...(part?.[name] || []));
    }
    logs.network.sort((a, b) => a.t - b.t);
    const report = buildReport(meta, logs.console, logs.navigations, logs.network, meta.lastSavedAt || meta.startedAt,
      'interrupted (recovered after a browser crash or a closed recorder window)');
    const video = videoParts.length ? new Blob(videoParts, { type: 'video/webm' }) : null;
    await idbAddRecording({ id, savedAt: new Date().toISOString(), video, report, uploaded: null, recovered: true });
    await idbPartialClear(id);
    recovered.push(id);
  }
  return recovered;
}
