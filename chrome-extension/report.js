function buildReport(meta, consoleEntries, navigations, network, stoppedAt, reason) {
  const capture = meta.serverCapture || meta.systemLog;
  const slowMs = capture?.slowMs || 2000;
  return {
    tool: 'blackbox ' + (chrome.runtime.getManifest().version_name || chrome.runtime.getManifest().version),
    note: 't = milliseconds since recording start = position in recording.webm',
    recording: {
      startedAt: new Date(meta.startedAt).toISOString(),
      stoppedAt: new Date(stoppedAt).toISOString(),
      durationMs: stoppedAt - meta.startedAt,
      stopReason: reason,
      capturedSurface: meta.settings?.displaySurface,
      audio: meta.audio,
      tab: meta.tab,
      userAgent: meta.userAgent || navigator.userAgent,
      serverCapture: capture ? { status: capture.status, id: capture.id, server: capture.server, message: capture.message } : undefined,
      options: meta.options,
      credentialsMasked: meta.credentialsMasked,
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

const REC_KEY = chrome.extension.inIncognitoContext ? 'recIncognito' : 'rec';

async function recoverPartials() {
  const keys = await idbPartialKeys();
  const ids = [...new Set(keys.filter((k) => k.endsWith(':meta')).map((k) => k.slice(0, -5)))];
  if (!ids.length) return [];
  const running = (await chrome.storage.session.get(REC_KEY))[REC_KEY];
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
    await stopRecoveredCapture(meta);
    recovered.push(id);
  }
  return recovered;
}

async function stopRecoveredCapture(meta) {
  const capture = meta?.serverCapture || meta?.systemLog;
  if (capture?.status !== 'on' || !capture.id || !capture.uploadUrl) return;
  const params = { action: 'recording-stop', id: capture.id };
  if (meta.lastSavedAt > 0) params.stoppedAt = meta.lastSavedAt;
  const abort = new AbortController();
  const timer = setTimeout(() => abort.abort(), 10_000);
  try {
    await fetch(capture.uploadUrl + '?' + new URLSearchParams(params), { method: 'POST', credentials: 'include', signal: abort.signal,
      headers: capture.captureSecret ? { 'X-BlackBox-Capture-Secret': capture.captureSecret } : {} });
  } catch (e) {
    console.warn('Could not stop the server capture of a recovered recording:', e);
  } finally {
    clearTimeout(timer);
  }
}
