// Extension only, shared by the Recordings page and the Replay page: Upload (with its form, #uploadBox), Move to folder, Download ZIP and
// Delete of one recording row (a row from Store.allRows()).
const Actions = (() => {
  const $ = (id) => document.getElementById(id);
  let settings = { uploadUrl: '', viewUrl: '' }; // in memory so Upload can ask for permission inside the click
  const created = {};        // row key -> { id, url } on the server, so a retry after a failed video upload doesn't duplicate
  const form = { row: null, onUploaded: async () => {}, onChange: () => {} };

  Server.getSettings().then((s) => { settings = s; });
  chrome.storage.onChanged.addListener(async (changes) => {
    if (changes.uploadUrl || changes.viewUrl) settings = await Server.getSettings();
  });

  // ---------- upload ----------

  // onUploaded(row, title): after a successful upload. onChange(): the form opened or closed.
  function initUploadForm(onUploaded, onChange) {
    form.onUploaded = onUploaded;
    form.onChange = onChange || (() => {});
    $('upCancel').onclick = () => closeUpload();
    $('upSend').onclick = () => send();
  }

  function showUpload(row) {
    form.row = row;
    const title = row.tab?.title || row.tab?.url || '';
    $('upHeading').textContent = 'Upload: ' + (title || 'recording');
    $('upTitle').value = title;
    $('upNotes').value = '';
    status('');
    $('uploadBox').hidden = false;
    $('upTitle').focus();
    form.onChange();
  }

  function closeUpload() {
    $('uploadBox').hidden = true;
    form.row = null;
    form.onChange();
  }

  function uploadRow() {
    return form.row;
  }

  // Uploads without waiting for Send (the "Upload automatically" setting): the form shows the progress, and stays
  // open with the reason if it fails. No click, so it can't ask for host access: Save in Settings gives it.
  async function uploadNow(row) {
    showUpload(row);
    if (!settings.uploadUrl) settings = await Server.getSettings();
    if (!settings.uploadUrl) {
      status('Not uploaded automatically: set the Upload URL first: ', 'Settings', () => chrome.runtime.openOptionsPage());
      return;
    }
    if (!(await Server.hasPermission(settings.uploadUrl))) {
      status('Not uploaded automatically: BlackBox has no access to ' + new URL(settings.uploadUrl).origin + '. Click Send to allow it.');
      return;
    }
    await upload(settings.uploadUrl, settings, row);
  }

  function send() {
    const row = form.row;
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
      const title = $('upTitle').value || row.id;
      closeUpload();
      status('');
      await form.onUploaded(row, title);
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

  // ---------- move to folder ----------

  // Saves recording.webm + session.json to Downloads/blackbox/<start time>/. Once they're written, the extension's
  // copy is deleted: the files on disk are the copy now (listed as "On disk" when that folder is linked).
  // Returns { name, onDisk }.
  async function moveToFolder(row) {
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
    if (failed) throw new Error('Move failed' + (failed.error ? ' (' + failed.error + ')' : '') + '. The recording is still in browser storage.');

    if (rec.uploaded) await idbMetaPut('uploaded:' + row.id, rec.uploaded); // still shown for the copy on disk
    await idbDelete(row.id);
    if (Store.state.folderReady) await Store.scanFolder();
    const onDisk = Store.state.diskItems.some((d) => d.id === row.id);
    if (!onDisk && rec.uploaded) await idbAddHistory(row.id, rec.report, rec.uploaded); // the folder isn't linked: keep it listed
    return { name, onDisk };
  }

  // ---------- download ZIP ----------

  // Saves the recording as one ZIP to Downloads/blackbox/<id>.zip, the same layout as a BlackBox server's Download
  // ZIP, so it can be imported on a server or in another browser's extension. Unlike Move to folder, it stays here.
  // Returns the file's path under Downloads.
  async function downloadZip(row) {
    const rec = await Store.load(row.kind, row.id);
    const json = (o) => new Blob([JSON.stringify(o)], { type: 'application/json' });
    const files = [
      { name: 'meta.json', blob: json({ title: row.tab?.title || row.tab?.url || row.id }), deflate: true },
      { name: 'session.json', blob: json(rec.report), deflate: true },
    ];
    if (rec.video instanceof Blob && rec.video.size > 0) files.push({ name: 'recording.webm', blob: rec.video });
    const zip = await makeZip(files);
    const path = `blackbox/${row.id}.zip`;
    const url = URL.createObjectURL(zip);
    try {
      const d = await waitForDownload(await chrome.downloads.download({ url, filename: path, saveAs: false }));
      if (d.state !== 'complete') throw new Error('Download failed' + (d.error ? ' (' + d.error + ')' : '') + '.');
    } finally {
      URL.revokeObjectURL(url);
    }
    return path;
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

  // ---------- delete ----------

  // Rows that have a Delete action: kept in browser storage, or on disk and already uploaded
  function canDelete(row) {
    return row.kind === 'saved' || (row.kind === 'disk' && !!row.uploaded);
  }

  // Asks first. Returns a message when something was deleted, otherwise null.
  async function deleteRow(row) {
    if (row.kind === 'disk') return deleteDiskRow(row);
    const warn = row.uploaded || row.onDisk ? '' : ' It was not uploaded or exported.';
    if (!confirm('Delete this recording from the extension?' + warn)) return null;
    if (row.uploaded && !row.onDisk) await idbAddHistory(row.id, row, row.uploaded); // stays listed as upload history
    await idbDelete(row.id);
    return 'Deleted the recording from the extension.';
  }

  // Deletes an uploaded recording's files (recording.webm, session.json, then its folder if empty).
  // The folder is linked read-only, so Chrome asks once for permission to edit it.
  async function deleteDiskRow(row) {
    const item = Store.state.diskItems.find((d) => d.id === row.id);
    if (!item) return null;
    const folder = Store.state.folder;
    // Permission first: Chrome only asks while the click still counts, and the confirm dialog below would use it up
    if ((await folder.requestPermission({ mode: 'readwrite' })) !== 'granted') {
      throw new Error(`BlackBox needs permission to edit "${folder.name}" to delete files.`);
    }
    if (!confirm(`Delete the files of this recording from "${folder.name}"? It stays on the server.`)) return null;
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
    return `Deleted the files of ${row.id} from "${folder.name}".`;
  }

  return { initUploadForm, showUpload, closeUpload, uploadRow, uploadNow, moveToFolder, downloadZip, canDelete, deleteRow };
})();
