// Loads one stored recording into the shared replay viewer (viewer.js, copied from the extension at build time).
window.blackboxPage = 'server'; // for the Features list
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
    if (meta.canViewSystemLog && meta.systemLog && meta.systemLog.status === 'attached') {
      $('dlHtml').title += ' (includes the system logs: share it only with people who may see it)';
      $('dlZip').title += ' (includes the system logs)';
    }
    if (meta.canDelete) {
      $('delete').hidden = false;
      $('delete').onclick = remove;
    }

    // System log and Glowroot traces: only for users the plugin settings allow; the extension's own viewer never shows them
    let systemLog = null;
    let systemLogError = null;
    let traces = null;
    let gauges = null;
    let serverInfo = null;
    let apps = null;
    if (meta.systemLog && meta.systemLog.status === 'attached' && meta.canViewSystemLog) {
      systemLog = await BB.getJson('systemlog', { id }).catch((e) => { systemLogError = e; return null; });
      traces = await BB.getJson('traces', { id }).catch(() => null);
      gauges = await BB.getJson('gauges', { id }).catch(() => null);
      serverInfo = await BB.getJson('serverinfo', { id }).catch(() => null); // missing in older recordings
    }
    if (meta.apps && meta.canViewSystemLog) apps = await BB.getJson('apps', { id }).catch(() => null);
    $('serverLegend').hidden = !systemLog;
    window.blackboxOpen(meta.videoSize ? BB.url('video', { id }) : null, report, {
      systemLog,
      traces: traces && traces.traces,
      gauges: gauges && gauges.nodes ? gauges : null,
      serverInfo,
      apps,
      appFileUrl: (file) => BB.url('appfile', { id, file }),
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
    if (meta.systemLog && meta.systemLog.status === 'attached' && meta.canViewSystemLog) glowrootData().catch(() => { /* no button */ });
    $('recStatus').hidden = false;
    neighbours().catch(() => { /* no Older / Newer, the recording still plays */ });
    if (systemLogError) window.blackboxShowError('Could not load the system logs: ' + (systemLogError.message || systemLogError));
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

  // Glowroot data (one package per node, built about a minute after the stop): a button per node, checked again
  // every 15 seconds while one is still being built
  let glowrootTimer = null;
  let zipTitle = ''; // Download ZIP's own title, while it says why it waits
  async function glowrootData() {
    clearTimeout(glowrootTimer);
    const nodes = (await BB.getJson('glowroot', { id })).nodes || [];
    const box = $('dlGlowroot');
    box.replaceChildren();
    for (const n of nodes) {
      const label = nodes.length > 1 ? 'Glowroot data (' + n.node + ')' : 'Download Glowroot data';
      if (n.state === 'saved') {
        const a = document.createElement('a');
        a.className = 'button';
        a.href = BB.url('glowroot', { id, node: n.node });
        a.textContent = label;
        a.title = 'This recording cut out of ' + n.node + "'s Glowroot data, in Glowroot's own format ("
          + Math.max(1, Math.round(n.bytes / 1024)) + ' KB): ' + n.traces + ' traces, plus the aggregates and gauges of all '
          + 'traffic during the recording. Open it in a standalone Glowroot (README.txt inside).' + (n.reason ? ' ' + n.reason + '.' : '');
        box.append(a);
      } else {
        const b = document.createElement('button');
        b.disabled = true;
        b.textContent = n.state === 'failed' ? label + ': failed' : label + ': preparing…';
        b.title = n.reason || '';
        box.append(b);
      }
    }
    box.hidden = !nodes.length;
    // The ZIP carries the Glowroot data: it can be downloaded once every node has built its package
    const building = nodes.some((n) => n.state === 'waiting' || n.state === 'building');
    $('dlZip').classList.toggle('busy', building);
    $('dlZip').setAttribute('aria-disabled', String(building));
    if (building) {
      zipTitle = zipTitle || $('dlZip').title;
      $('dlZip').title = 'Available once the Glowroot data is built (about a minute after the recording stopped): the ZIP includes it';
      glowrootTimer = setTimeout(() => glowrootData().catch(() => {}), 15000);
    } else if (zipTitle) {
      $('dlZip').title = zipTitle + ' (includes the Glowroot data)';
      zipTitle = '';
    }
  }
  $('dlZip').addEventListener('click', (e) => {
    if ($('dlZip').classList.contains('busy')) e.preventDefault();
  }, true);

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
