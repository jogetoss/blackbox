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

  async function load() {
    if (!id) throw new Error('No recording id in the URL.');
    const meta = await BB.getJson('meta', { id });
    const r = await fetch(BB.url('session', { id }), { credentials: 'same-origin' });
    if (!r.ok) throw new BB.ApiError(r.status, await r.json().catch(() => null));
    const report = await r.json();

    document.title = meta.title + ' · BlackBox';
    $('recTitle').textContent = meta.title;
    $('owner').textContent = 'by ' + (meta.ownerName || meta.owner) + ', ' + BB.fmtDate(meta.uploadedAt);
    if (meta.notes) {
      $('notes').textContent = meta.notes;
      $('notes').hidden = false;
    }
    $('dlSession').href = BB.url('session', { id, download: 1 });
    if (meta.videoSize) {
      $('dlVideo').href = BB.url('video', { id, download: 1 });
      $('dlVideo').hidden = false;
    }
    if (meta.canDelete) {
      $('delete').hidden = false;
      $('delete').onclick = remove;
    }

    window.blackboxOpen(meta.videoSize ? BB.url('video', { id }) : null, report);
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
