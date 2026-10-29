// Recordings page: the list (the title opens the Replay page), Browser issues, and batch Upload / Download ZIP / Delete
// for the ticked rows. Move to folder and the other single-recording actions are on the Replay page.
(function () {
  const $ = (id) => document.getElementById(id);
  const { state } = Store;
  const PAGE = 100;          // rows shown at first; Show more adds this many
  let shown = PAGE;

  $('settings').onclick = () => chrome.runtime.openOptionsPage();
  // Incognito has its own copy of the extension ("incognito": "split") whose browser storage is gone when it closes
  $('incognitoNote').hidden = !chrome.extension.inIncognitoContext;
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
    await checkUpload();
  });

  // Set when the Upload URL's server says this user may not upload: Upload selected is then disabled with the reason
  let uploadRefused = '';
  async function checkUpload() {
    const { uploadUrl } = await Server.getSettings();
    if (!uploadUrl || !(await Server.hasPermission(uploadUrl))) return;
    try {
      const me = await Server.me(uploadUrl);
      uploadRefused = me.canUpload ? '' : `You (${me.user.name}) are not allowed to upload recordings. Ask the server's admin.`;
    } catch (e) {
      return; // not logged in or not reachable: the upload itself says so
    }
    renderBatch();
  }

  function run(fn) {
    Promise.resolve().then(fn).catch(showError);
  }

  function showError(e) {
    $('error').textContent = e ? e.message || String(e) : '';
    $('error').hidden = !e;
  }

  // Rows: the title opens the recording (Replay page, or the server copy for upload history); single-recording
  // actions are on the Replay page. Ticked rows get the batch actions above the table.
  let rows = [];
  const selected = new Set(); // row keys "kind:id"
  const key = (r) => r.kind + ':' + r.id;

  $('selAll').onchange = () => {
    for (const r of rows.slice(0, shown)) if ($('selAll').checked) selected.add(key(r)); else selected.delete(key(r));
    run(render);
  };
  $('batchUpload').onclick = () => run(() => Actions.uploadMany(picked().filter(canUpload)));
  $('batchZip').onclick = () => run(downloadSelected);
  $('batchDelete').onclick = () => run(deleteSelected);

  const canUpload = (r) => r.kind !== 'history' && !r.uploaded;
  const canZip = (r) => r.kind !== 'history';
  const canDelete = (r) => r.kind === 'history' || Actions.canDelete(r);
  const picked = () => rows.filter((r) => selected.has(key(r)));

  async function render() {
    rows = await Store.allRows();
    const keys = new Set(rows.map(key));
    for (const k of [...selected]) if (!keys.has(k)) selected.delete(k);
    const kept = rows.filter((r) => r.kind === 'saved').length;
    $('libraryEmpty').hidden = rows.length > 0;
    $('libraryRows').closest('table').hidden = rows.length === 0;
    const body = $('libraryRows');
    body.textContent = '';
    for (const r of rows.slice(0, shown)) {
      const tr = document.createElement('tr');
      const up = Actions.uploadRow();
      if ((up && up.kind === r.kind && up.id === r.id) || selected.has(key(r))) tr.className = 'current';

      const sel = td('');
      sel.className = 'sel';
      const box = document.createElement('input');
      box.type = 'checkbox';
      box.checked = selected.has(key(r));
      box.onchange = () => { if (box.checked) selected.add(key(r)); else selected.delete(key(r)); run(render); };
      sel.append(box);
      tr.append(sel);

      const started = td(utc(r.startedAt) || r.id);
      if (r.startedAt) started.title = new Date(r.startedAt).toLocaleString(); // the reporter's own clock time
      tr.append(started);
      const page = td('');
      page.className = 'page';
      page.title = r.tab?.url || '';
      const link = document.createElement('a');
      link.className = 'rec-title';
      link.textContent = r.tab?.title || r.tab?.url || r.id;
      if (r.kind === 'history') {
        // Uploaded, local copy gone: opens it on the server
        if (r.uploaded?.url) { link.href = r.uploaded.url; link.target = '_blank'; link.title = 'Opens the uploaded recording on the server'; }
      } else {
        link.href = Store.replayUrl(r);
      }
      page.append(link);
      tr.append(page);
      tr.append(td(Store.duration(r.durationMs)));
      tr.append(issuesCell(r));
      const status = td('');
      status.append(...Store.badges(r));
      tr.append(status);
      body.append(tr);
    }

    $('showMore').hidden = rows.length <= shown;
    $('showMore').textContent = `Show more (${rows.length - shown} more)`;
    const page = rows.slice(0, shown);
    $('selAll').checked = page.length > 0 && page.every((r) => selected.has(key(r)));
    renderBatch();

    const f = state.folder;
    $('linkFolder').textContent = f ? 'Change folder…' : 'Link folder…';
    $('allowFolder').hidden = !f || state.folderReady;
    if (f) $('allowFolder').textContent = `Allow access to "${f.name}"`;
    $('unlinkFolder').hidden = !f;
    $('folderInfo').textContent = (f ? `Folder: ${f.name} (${state.folderReady ? state.diskItems.length + ' on disk' : 'access needed'}) · ` : '')
      + `${kept} in browser storage (newest ${Store.MAX_KEPT})`
      + (rows.some((r) => r.kind === 'history') ? ` · ${rows.filter((r) => r.kind === 'history').length} in upload history` : '');
  }

  // Console errors, failed requests and slow requests of the recording (none for upload history)
  function issuesCell(r) {
    const c = td('');
    c.className = 'nowrap';
    const s = r.issues;
    if (!s) {
      c.append(r.kind === 'history' ? '' : '–');
      return c;
    }
    c.append(badge(`${s.errors || 0} errors`, s.errors ? 'err' : ''), ' ', badge(`${s.failedRequests || 0} failed`, s.failedRequests ? 'warn' : ''));
    if (s.slowRequests != null) {
      const limit = s.slowThresholdMs >= 1000 ? `${s.slowThresholdMs / 1000} s` : `${s.slowThresholdMs} ms`;
      c.append(' ', badge(`${s.slowRequests} slow`, s.slowRequests ? 'warn' : '', `Requests of ${limit} or more (the server's slow threshold, or 2 s)`));
    }
    return c;
  }

  function renderBatch() {
    const sel = picked();
    const n = (f) => sel.filter(f).length;
    $('selCount').textContent = sel.length ? `${sel.length} selected` : 'Tick recordings to act on several at once';
    label('batchUpload', 'Upload selected', uploadRefused ? 0 : n(canUpload), 'Already uploaded, or no local copy');
    if (uploadRefused) $('batchUpload').title = uploadRefused;
    label('batchZip', 'Download ZIP', n(canZip), 'Upload history has no local copy');
    label('batchDelete', 'Delete selected', n(canDelete), 'Recordings on disk can be deleted once uploaded');
    function label(id, text, count, why) {
      $(id).disabled = !count;
      $(id).textContent = count ? `${text} (${count})` : text;
      $(id).title = sel.length > count ? `${sel.length - count} of the selected can't: ${why.toLowerCase()}` : '';
    }
  }

  async function downloadSelected() {
    const names = [];
    for (const r of picked().filter(canZip)) names.push(await Actions.downloadZip(r));
    message(`Saved ${names.map((x) => 'Downloads/' + x).join(', ')}.`);
  }

  async function deleteSelected() {
    const del = picked().filter(canDelete);
    if (!del.length) return;
    // Files on disk: Chrome asks for edit permission only while this click still counts, so before the confirm
    if (del.some((r) => r.kind === 'disk') && (await state.folder.requestPermission({ mode: 'readwrite' })) !== 'granted') {
      throw new Error(`BlackBox needs permission to edit "${state.folder.name}" to delete files.`);
    }
    const notSaved = del.filter((r) => r.kind === 'saved' && !r.uploaded && !r.onDisk).length;
    if (!confirm(`Delete ${del.length} recording${del.length > 1 ? 's' : ''}?`
      + (notSaved ? ` ${notSaved} of them ${notSaved > 1 ? 'were' : 'was'} not uploaded or exported.` : '')
      + ' Copies on a server are kept.')) return;
    for (const r of del) {
      if (r.kind === 'history') await idbHistoryDelete(r.id);
      else await Actions.deleteRow(r, true);
      selected.delete(key(r));
    }
    message(`Deleted ${del.length} recording${del.length > 1 ? 's' : ''}.`);
    await render();
  }

  function badge(text, cls, title) {
    const b = document.createElement('span');
    b.className = 'badge' + (cls ? ' ' + cls : '');
    b.textContent = text;
    if (title) b.title = title;
    return b;
  }

  // A recording ZIP (Download ZIP on a BlackBox server) becomes a recording in browser storage, like a new one:
  // it can be replayed, uploaded (to this or another server) or exported. Its server data is not kept.
  async function importZip(file) {
    const parts = await readZip(file);
    if (!parts.has('session.json')) throw new Error(`${file.name} has no session.json: it is not a BlackBox recording ZIP.`);
    const report = JSON.parse(await parts.get('session.json').text());
    if (!Array.isArray(report.console) || !Array.isArray(report.network)) throw new Error('This is not a BlackBox session.json.');
    // The capture id belongs to the server it was recorded on: an upload elsewhere must not try to attach it
    for (const key of ['serverCapture', 'systemLog']) delete report.recording?.[key]?.id; // systemLog: ZIPs of plugin 8.2.1 and older
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

  // Same as utc() in viewer.js (the standalone viewer can't load a shared file)
  function utc(v) {
    if (v == null || v === '') return undefined;
    const d = new Date(v);
    return isNaN(d) ? String(v) : d.toISOString().replace('T', ' ').slice(0, 19) + ' UTC';
  }

  function td(text) {
    const c = document.createElement('td');
    c.textContent = text;
    return c;
  }
})();
