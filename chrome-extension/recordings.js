// Recordings page: the list with per-row Open (goes to the Replay page), Upload, Move to folder, Download ZIP and Delete.
(function () {
  const $ = (id) => document.getElementById(id);
  const { state } = Store;
  const PAGE = 100;          // rows shown at first; Show more adds this many
  let shown = PAGE;

  $('settings').onclick = () => chrome.runtime.openOptionsPage();
  $('showMore').onclick = () => { shown += PAGE; run(render); };
  Actions.initUploadForm(async (row, title) => {
    message('Uploaded "' + title + '".');
    await render();
  }, () => run(render));
  $('linkFolder').onclick = () => run(async () => { if (await Store.linkFolder()) render(); });
  $('allowFolder').onclick = () => run(async () => { if (await Store.allowFolder()) render(); });
  $('unlinkFolder').onclick = () => run(async () => { await Store.unlinkFolder(); render(); });
  $('importZip').onchange = (e) => {
    const file = e.target.files[0];
    e.target.value = '';
    if (file) run(() => importZip(file));
  };
  // Another page (the recorder, the Replay page) may change the list
  window.addEventListener('focus', () => run(render));

  run(async () => {
    // Normally done at browser start; also here in case the extension was reloaded instead
    const recovered = await recoverPartials();
    if (recovered.length) message(`Recovered ${recovered.length} recording(s) interrupted by a crash. They end where the last save was (every 5 s).`);
    await Store.restoreFolder();
    await render();
  });

  function run(fn) {
    Promise.resolve().then(fn).catch(showError);
  }

  function showError(e) {
    $('error').textContent = e ? e.message || String(e) : '';
    $('error').hidden = !e;
  }

  async function render() {
    const rows = await Store.allRows();
    const kept = rows.filter((r) => r.kind === 'saved').length;
    $('libraryEmpty').hidden = rows.length > 0;
    $('libraryRows').closest('table').hidden = rows.length === 0;
    const body = $('libraryRows');
    body.textContent = '';
    for (const r of rows.slice(0, shown)) {
      const tr = document.createElement('tr');
      const up = Actions.uploadRow();
      if (up && up.kind === r.kind && up.id === r.id) tr.className = 'current';
      tr.append(td(r.startedAt ? new Date(r.startedAt).toLocaleString() : r.id));
      const page = td(r.tab?.title || r.tab?.url || '');
      page.className = 'page';
      page.title = r.tab?.url || '';
      tr.append(page);
      tr.append(td(Store.duration(r.durationMs)));
      const status = td('');
      status.append(...Store.badges(r));
      tr.append(status);

      const acts = td('');
      acts.className = 'acts';
      if (r.kind === 'history') {
        // Uploaded, local copy gone: Open shows it on the server, Delete only removes the row
        if (r.uploaded?.url) {
          const open = button('Open', () => window.open(r.uploaded.url, '_blank'));
          open.title = 'Opens the uploaded recording on the server';
          acts.append(open);
        }
        acts.append(button('Delete', () => run(async () => {
          if (!confirm('Remove this row from the list? The recording stays on the server.')) return;
          await idbHistoryDelete(r.id);
          await render();
        })));
        tr.append(acts);
        body.append(tr);
        continue;
      }
      acts.append(button('Open', () => { location.href = Store.replayUrl(r); }));
      if (!r.uploaded) acts.append(button('Upload', () => Actions.showUpload(r)));
      if (r.kind === 'saved') {
        const moveBtn = button('Move to folder', () => run(async () => {
          moveBtn.disabled = true;
          try {
            const { name, onDisk } = await Actions.moveToFolder(r);
            message(`Moved to Downloads/blackbox/${name}` + (onDisk ? '.' : '; it is no longer in the extension.'));
            await render();
          } catch (e) {
            moveBtn.disabled = false;
            throw e;
          }
        }));
        moveBtn.title = 'Move recording.webm + session.json to Downloads/blackbox/ and free its space in the extension. Link that folder to keep it in this list.';
        acts.append(moveBtn);
      }
      const zipBtn = button('Download ZIP', () => run(async () => {
        zipBtn.disabled = true;
        try {
          message(`Saved Downloads/${await Actions.downloadZip(r)}.`);
        } finally {
          zipBtn.disabled = false;
        }
      }));
      zipBtn.title = 'Download as one ZIP, to import on a BlackBox server or in another browser. It stays here too.';
      acts.append(zipBtn);
      // On disk, Delete only appears once uploaded: it removes the exported files from the folder
      if (Actions.canDelete(r)) {
        acts.append(button('Delete', () => run(async () => {
          const done = await Actions.deleteRow(r);
          if (done) {
            if (r.kind === 'disk') message(done);
            await render();
          }
        })));
      }
      tr.append(acts);
      body.append(tr);
    }

    $('showMore').hidden = rows.length <= shown;
    $('showMore').textContent = `Show more (${rows.length - shown} more)`;

    const f = state.folder;
    $('linkFolder').textContent = f ? 'Change folder…' : 'Link folder…';
    $('allowFolder').hidden = !f || state.folderReady;
    if (f) $('allowFolder').textContent = `Allow access to "${f.name}"`;
    $('unlinkFolder').hidden = !f;
    $('folderInfo').textContent = (f ? `Folder: ${f.name} (${state.folderReady ? state.diskItems.length + ' on disk' : 'access needed'}) · ` : '')
      + `${kept} in browser storage (newest ${Store.MAX_KEPT})`
      + (rows.some((r) => r.kind === 'history') ? ` · ${rows.filter((r) => r.kind === 'history').length} in upload history` : '');
  }

  // A recording ZIP (Download ZIP on a BlackBox server) becomes a recording in browser storage, like a new one:
  // it can be replayed, uploaded (to this or another server) or exported. Its server log is not kept.
  async function importZip(file) {
    const parts = await readZip(file);
    if (!parts.has('session.json')) throw new Error(`${file.name} has no session.json: it is not a BlackBox recording ZIP.`);
    const report = JSON.parse(await parts.get('session.json').text());
    if (!Array.isArray(report.console) || !Array.isArray(report.network)) throw new Error('This is not a BlackBox session.json.');
    // The capture id belongs to the server it was recorded on: an upload elsewhere must not try to attach it
    if (report.recording?.serverLog) delete report.recording.serverLog.id;
    const started = Date.parse(report.recording?.startedAt);
    const id = started ? recordingId(started) : file.name.replace(/\.zip$/i, '');
    if (await idbGet(id)) throw new Error('This recording is already in the extension.');
    const video = parts.get('recording.webm') || null;
    await idbAddRecording({ id, savedAt: new Date().toISOString(), video: video && new Blob([video], { type: 'video/webm' }), report, uploaded: null, imported: true });
    message(`Imported ${file.name}.`);
    await render();
  }

  function message(text) {
    $('libraryMsg').textContent = text;
    $('libraryMsg').hidden = !text;
  }

  // ---------- helpers ----------

  function td(text) {
    const c = document.createElement('td');
    c.textContent = text;
    return c;
  }

  function button(text, onClick) {
    const b = document.createElement('button');
    b.textContent = text;
    b.onclick = onClick;
    return b;
  }
})();
