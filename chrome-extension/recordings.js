// Recordings page: the list with per-row Open (goes to the Replay page), Upload, Export and Delete.
(function () {
  const $ = (id) => document.getElementById(id);
  const { state } = Store;
  let settings = { uploadUrl: '', viewUrl: '' }; // in memory so Upload can ask for permission inside the click
  let uploadRow = null;      // the row the upload form is for
  const PAGE = 100;          // rows shown at first; Show more adds this many
  let shown = PAGE;
  const created = {};        // row key -> { id, url } on the server, so a retry after a failed video upload doesn't duplicate

  Server.getSettings().then((s) => { settings = s; });
  chrome.storage.onChanged.addListener(async (changes) => {
    if (changes.uploadUrl || changes.viewUrl) settings = await Server.getSettings();
  });

  $('settings').onclick = () => chrome.runtime.openOptionsPage();
  $('showMore').onclick = () => { shown += PAGE; run(render); };
  $('upCancel').onclick = () => { $('uploadBox').hidden = true; uploadRow = null; };
  $('upSend').onclick = () => send();
  $('linkFolder').onclick = () => run(async () => { if (await Store.linkFolder()) render(); });
  $('allowFolder').onclick = () => run(async () => { if (await Store.allowFolder()) render(); });
  $('unlinkFolder').onclick = () => run(async () => { await Store.unlinkFolder(); render(); });
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
      if (uploadRow && uploadRow.kind === r.kind && uploadRow.id === r.id) tr.className = 'current';
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
      if (!r.uploaded) acts.append(button('Upload', () => showUpload(r)));
      if (r.kind === 'saved') {
        const exp = button('Export', () => run(() => exportRow(r, exp).catch((e) => { exp.disabled = false; throw e; })));
        exp.title = 'Save recording.webm + session.json to Downloads/blackbox/, then remove it from the extension';
        acts.append(exp);
        acts.append(button('Delete', () => run(async () => {
          const warn = r.uploaded || r.onDisk ? '' : ' It was not uploaded or exported.';
          if (!confirm('Delete this recording from the extension?' + warn)) return;
          if (r.uploaded && !r.onDisk) await idbAddHistory(r.id, r, r.uploaded); // stays listed as upload history
          await idbDelete(r.id);
          await render();
        })));
      }
      if (r.kind === 'disk' && r.uploaded) {
        // Exported files whose recording is on the server now: Delete removes them from the folder
        acts.append(button('Delete', () => run(() => deleteDiskRow(r))));
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

  function message(text) {
    $('libraryMsg').textContent = text;
    $('libraryMsg').hidden = !text;
  }

  // ---------- delete files on disk ----------

  // Deletes an uploaded recording's files (recording.webm, session.json, then its folder if empty).
  // The folder is linked read-only, so Chrome asks once for permission to edit it.
  async function deleteDiskRow(row) {
    const item = state.diskItems.find((d) => d.id === row.id);
    if (!item) return;
    const folder = state.folder;
    // Permission first: Chrome only asks while the click still counts, and the confirm dialog below would use it up
    if ((await folder.requestPermission({ mode: 'readwrite' })) !== 'granted') {
      throw new Error(`BlackBox needs permission to edit "${folder.name}" to delete files.`);
    }
    if (!confirm(`Delete the files of this recording from "${folder.name}"? It stays on the server.`)) return;
    for (const name of ['recording.webm', 'session.json']) {
      try {
        await item.dir.removeEntry(name);
      } catch (e) {
        if (e.name !== 'NotFoundError') throw e;
      }
    }
    if (item.parent) {
      try {
        await item.parent.removeEntry(item.dir.name); // only succeeds when the folder is now empty
      } catch (e) { /* other files in it: leave the folder */ }
    }
    await idbAddHistory(row.id, row, row.uploaded); // stays listed as upload history
    await idbMetaDelete('uploaded:' + row.id);
    await Store.scanFolder();
    message(`Deleted the files of ${row.id} from "${folder.name}".`);
    await render();
  }

  // ---------- upload ----------

  function showUpload(row) {
    uploadRow = row;
    const title = row.tab?.title || row.tab?.url || '';
    $('upHeading').textContent = 'Upload: ' + (title || 'recording');
    $('upTitle').value = title;
    $('upNotes').value = '';
    status('');
    $('uploadBox').hidden = false;
    $('upTitle').focus();
    render();
  }

  function send() {
    const row = uploadRow;
    if (!row) return;
    if (!settings.uploadUrl) {
      status('Set the Upload URL first: ', 'Settings', () => chrome.runtime.openOptionsPage());
      return;
    }
    const base = settings.uploadUrl;
    const used = settings;
    // Ask for access while this click still counts as a user gesture (no await before this)
    Server.requestPermission(base).then((granted) => {
      if (!granted) {
        status('BlackBox needs access to ' + new URL(base).origin + ' to upload.');
        return;
      }
      return upload(base, used, row);
    }).catch((e) => status(e.message));
  }

  async function upload(base, used, row) {
    const key = row.kind + ':' + row.id;
    $('upSend').disabled = true;
    $('upProgress').hidden = false;
    $('upProgress').value = 0;
    try {
      status('Checking your login…');
      const rec = await Store.load(row.kind, row.id);
      try {
        const me = await Server.me(base);
        if (!me.canUpload) throw new Error(`You (${me.user.name}) are not allowed to upload recordings. Ask the server's admin.`);
        if (rec.video && rec.video.size > me.maxSizeMb * 1024 * 1024) throw new Error(`The video is larger than the server limit (${me.maxSizeMb} MB).`);
      } catch (e) {
        if (e.status === 401) {
          status(Server.notLoggedIn(base));
          return;
        }
        throw e;
      }
      const hasVideo = rec.video instanceof Blob && rec.video.size > 0;
      if (!created[key]) {
        status('Uploading session…');
        const meta = await Server.request(base, 'POST', { action: 'create', title: $('upTitle').value, notes: $('upNotes').value },
          JSON.stringify(rec.report), 'application/json', (d, t) => { $('upProgress').value = (hasVideo ? 0.05 : 1) * d / t; });
        created[key] = { id: meta.id, url: Server.viewLink(used, meta.id, Server.resolve(base, meta.url)) };
      }
      const { id, url } = created[key];
      if (hasVideo) {
        status('Uploading video…');
        await Server.request(base, 'POST', { action: 'video', id }, rec.video, 'video/webm',
          (d, t) => { $('upProgress').value = 0.05 + 0.95 * d / t; });
      }
      delete created[key];
      const uploaded = { id, url, at: new Date().toISOString() };
      if (row.kind === 'saved') await idbUpdate(row.id, { uploaded });
      else await idbMetaPut('uploaded:' + row.id, uploaded);
      $('uploadBox').hidden = true;
      uploadRow = null;
      status('');
      message('Uploaded "' + ($('upTitle').value || row.id) + '".');
      await render();
    } catch (e) {
      status(e.message);
    } finally {
      $('upSend').disabled = false;
      $('upProgress').hidden = true;
    }
  }

  // Shows text in the upload box, optionally followed by a link that runs onClick
  function status(text, linkText, onClick) {
    const s = $('upStatus');
    s.textContent = text;
    if (linkText) {
      const a = document.createElement('a');
      a.href = '#';
      a.textContent = linkText;
      a.onclick = (e) => { e.preventDefault(); onClick(); };
      s.append(a);
    }
  }

  // ---------- export ----------

  // Saves recording.webm + session.json to Downloads/blackbox/<start time>/. Once they're written, the extension's
  // copy is deleted: the files on disk are the copy now (listed as "On disk" when that folder is linked).
  async function exportRow(row, btn) {
    btn.disabled = true;
    const rec = await idbGet(row.id);
    if (!rec?.report) throw new Error('That recording is no longer in browser storage.');
    const started = rec.report.recording?.startedAt;
    const name = started ? new Date(started).toISOString().replace(/[:.]/g, '-').slice(0, 19) : row.id;
    const files = [{ name: 'session.json', blob: new Blob([JSON.stringify(rec.report, null, 2)], { type: 'application/json' }) }];
    if (rec.video instanceof Blob) files.unshift({ name: 'recording.webm', blob: rec.video });
    const ids = [];
    for (const f of files) {
      // The Blob URL lives as long as this page, which outlasts the download
      ids.push(await chrome.downloads.download({ url: URL.createObjectURL(f.blob), filename: `blackbox/${name}/${f.name}`, saveAs: false }));
    }
    const results = await Promise.all(ids.map(waitForDownload));
    const failed = results.find((d) => d.state !== 'complete');
    if (failed) throw new Error('Export failed' + (failed.error ? ' (' + failed.error + ')' : '') + '. The recording is still in browser storage.');

    if (rec.uploaded) await idbMetaPut('uploaded:' + row.id, rec.uploaded); // still shown for the copy on disk
    await idbDelete(row.id);
    if (state.folderReady) await Store.scanFolder();
    const onDisk = state.diskItems.some((d) => d.id === row.id);
    if (!onDisk && rec.uploaded) await idbAddHistory(row.id, rec.report, rec.uploaded); // the folder isn't linked: keep it listed
    message(`Exported to Downloads/blackbox/${name}` + (onDisk ? '.' : ' and removed from the extension.'));
    await render();
  }

  function waitForDownload(id) {
    return new Promise((resolve) => {
      const done = (d) => { chrome.downloads.onChanged.removeListener(onChanged); resolve(d); };
      const onChanged = (delta) => {
        if (delta.id === id && delta.state && delta.state.current !== 'in_progress') chrome.downloads.search({ id }).then(([d]) => done(d));
      };
      chrome.downloads.onChanged.addListener(onChanged);
      // It may have finished before the listener was added
      chrome.downloads.search({ id }).then(([d]) => { if (d && d.state !== 'in_progress') done(d); });
    });
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
