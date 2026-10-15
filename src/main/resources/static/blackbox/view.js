// Loads one stored recording into the shared replay viewer (viewer.js, copied from the extension at build time).
(function () {
  const $ = (id) => document.getElementById(id);
  const id = new URLSearchParams(location.search).get('id');

  // The viewer's drag-and-drop would replace the stored recording with local files: turn it off here.
  document.addEventListener('drop', (e) => { e.preventDefault(); e.stopImmediatePropagation(); }, true);

  load().catch((e) => {
    $('loading').textContent = '';
    if (!BB.loginNeeded(e, $('error'))) {
      $('error').textContent = e.message || String(e);
      $('error').hidden = false;
    }
  });

  window.blackboxTimeInUrl = true; // view?id=…&t=… opens at that moment

  async function load() {
    if (!id) throw new Error('No recording id in the URL.');
    const meta = await BB.getJson('meta', { id });
    const r = await fetch(BB.url('session', { id }), { credentials: 'same-origin' });
    if (!r.ok) throw new BB.ApiError(r.status, await r.json().catch(() => null));
    const report = await r.json();

    $('where').textContent = 'On ' + location.host;
    $('where').title = 'Stored on this Joget server. Delete removes it for everyone.';
    if (meta.notes) {
      $('notes').textContent = meta.notes;
      $('notes').hidden = false;
    }
    $('dlZip').href = BB.url('download', { id, format: 'zip' });
    $('dlHtml').href = BB.url('download', { id, format: 'html' });
    if (meta.canViewServerLog && meta.serverLog && meta.serverLog.status === 'attached') {
      $('dlHtml').title += ' (includes the server log: share it only with people who may see it)';
      $('dlZip').title += ' (includes the server log)';
    }
    if (meta.canDelete) {
      $('delete').hidden = false;
      $('delete').onclick = remove;
    }

    // Server log and Glowroot traces: only for users the plugin settings allow; the extension's own viewer never shows them
    let serverLog = null;
    let serverLogError = null;
    let traces = null;
    if (meta.serverLog && meta.serverLog.status === 'attached' && meta.canViewServerLog) {
      serverLog = await BB.getJson('serverlog', { id }).catch((e) => { serverLogError = e; return null; });
      traces = await BB.getJson('traces', { id }).catch(() => null);
    }
    $('serverLegend').hidden = !serverLog;
    window.blackboxOpen(meta.videoSize ? BB.url('video', { id }) : null, report, {
      serverLog,
      traces: traces && traces.traces,
      traceUrl: (t) => BB.url('trace', { id, file: t.file }),
      retryTraces: async (keys) => {
        const next = await BB.post('trace-retry', { id, keys: keys.join(',') });
        window.blackboxSetTraces(next.traces);
        followTraces(next);
      },
      title: meta.title,
      info: BB.uploadInfo(meta),
    });
    if (traces) followTraces(traces);
    $('recStatus').hidden = false;
    neighbours().catch(() => { /* no Older / Newer, the recording still plays */ });
    if (serverLogError) window.blackboxShowError('Could not load the server log: ' + (serverLogError.message || serverLogError));
  }

  // Traces of slow requests can arrive after the upload: poll while some are still being collected
  let pendingTraces = 0;
  let traceTimer = null;
  function followTraces(t) {
    pendingTraces = t.pending;
    clearTimeout(traceTimer); // one poll loop, also after a retry
    if (!t.pending) return;
    traceTimer = setTimeout(async () => {
      const next = await BB.getJson('traces', { id }).catch(() => null);
      if (!next) return followTraces(t); // try again later
      window.blackboxSetTraces(next.traces);
      followTraces(next);
    }, 5000);
  }

  // A download made now would miss the traces still being collected
  for (const a of ['dlZip', 'dlHtml']) {
    $(a).addEventListener('click', (e) => {
      if (pendingTraces && !confirm(`${pendingTraces} Glowroot ${pendingTraces === 1 ? 'trace is' : 'traces are'} still being collected and would be missing from this download. Download anyway?`)) {
        e.preventDefault();
      }
    });
  }

  // Older / Newer: the recordings this user can see, in the list page's order (newest first)
  async function neighbours() {
    const list = (await BB.getJson('list')).recordings;
    const i = list.findIndex((r) => r.id === id);
    if (i < 0) return;
    const to = (r) => r && { href: 'view?id=' + encodeURIComponent(r.id), title: r.title };
    window.blackboxSetNeighbours({ older: to(list[i + 1]), newer: to(list[i - 1]), pos: i + 1, total: list.length });
  }

  async function remove() {
    if (!confirm('Delete this recording? This cannot be undone.')) return;
    $('delete').disabled = true;
    try {
      await BB.post('delete', { id });
      location.href = './';
    } catch (e) {
      $('delete').disabled = false;
      $('error').textContent = e.message || String(e);
      $('error').hidden = false;
    }
  }
})();
