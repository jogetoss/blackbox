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

    // Server log: only for users the plugin settings allow; the extension's own viewer never shows it
    let serverLog = null;
    let serverLogError = null;
    if (meta.serverLog && meta.serverLog.status === 'attached' && meta.canViewServerLog) {
      serverLog = await BB.getJson('serverlog', { id }).catch((e) => { serverLogError = e; return null; });
    }
    $('serverLegend').hidden = !serverLog;
    window.blackboxOpen(meta.videoSize ? BB.url('video', { id }) : null, report, { serverLog, title: meta.title, info: BB.uploadInfo(meta) });
    $('recStatus').hidden = false;
    neighbours().catch(() => { /* no Older / Newer, the recording still plays */ });
    if (serverLogError) window.blackboxShowError('Could not load the server log: ' + (serverLogError.message || serverLogError));
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
