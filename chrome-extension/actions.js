const Actions = (() => {
  const $ = (id) => document.getElementById(id);
  let settings = { uploadUrl: '', viewUrl: '' };
  const created = {};
  const form = { row: null, onUploaded: async () => {}, onChange: () => {}, ready: Promise.resolve(), options: null };

  Server.getSettings().then((s) => { settings = s; });
  chrome.storage.onChanged.addListener(async (changes) => {
    if (changes.uploadUrl || changes.viewUrl) settings = await Server.getSettings();
  });

  function initUploadForm(onUploaded, onChange) {
    form.onUploaded = onUploaded;
    form.onChange = onChange || (() => {});
    $('upCancel').onclick = () => closeUpload();
    $('upSend').onclick = () => send();
    $('upApps').onchange = $('upSample').onchange = () => {
      $('upSample').disabled = !$('upApps').checked;
      $('upMask').disabled = !$('upApps').checked || !$('upSample').checked;
    };
  }

  function showUpload(row) {
    form.row = row;
    const title = row.tab?.title || row.tab?.url || '';
    $('upHeading').textContent = 'Upload: ' + (title || 'recording');
    $('upTitle').value = title;
    $('upNotes').value = '';
    for (const id of ['upAtStop', 'upAppsRow', 'upSampleRow', 'upMaskRow']) $(id).hidden = true;
    form.options = null;
    form.ready = sampleOptions(row).catch(() => { });
    status('');
    $('uploadBox').hidden = false;
    $('upTitle').focus();
    form.onChange();
  }

  async function sampleOptions(row) {
    const rec = await Store.load(row.kind, row.id);
    const recording = rec?.report?.recording;
    const atStop = (recording?.serverCapture || recording?.systemLog)?.appExport;
    if (atStop && atStop.state !== 'failed') {
      $('upAtStop').textContent = atStop.state === 'off' ? 'Apps not exported: turned off for this recording.'
        : atStop.state === 'exporting' ? 'The server is still exporting the apps used; they are added to the recording when done.'
        : `Apps exported when the recording stopped (${atStop.apps || 0}), sample data: ${atStop.sampleData || 'none'}.`;
      $('upAtStop').hidden = false;
      return;
    }
    const o = form.options = recording?.options || null;
    $('upApps').checked = o?.apps === true;
    $('upSample').checked = !!o?.sampleData && o.sampleData !== 'none';
    $('upMask').checked = o?.sampleData === 'masked';
    $('upApps').onchange?.();
    if (!settings.uploadUrl) settings = await Server.getSettings();
    if (!settings.uploadUrl || !(await Server.hasPermission(settings.uploadUrl))) return;
    const me = await Server.me(settings.uploadUrl);
    $('upAppsRow').hidden = !me.options?.apps;
    $('upSampleRow').hidden = !me.options?.sample;
    $('upMaskRow').hidden = !me.options?.mask;
  }

  function sampleChoice() {
    if ($('upAppsRow').hidden && $('upSampleRow').hidden) return Quick.exportParams(form.options);
    if (!$('upAppsRow').hidden && !$('upApps').checked) return { apps: 'false' };
    if ($('upSampleRow').hidden) return {};
    if (!$('upSample').checked) return { sampleData: 'none' };
    return { sampleData: !$('upMaskRow').hidden && $('upMask').checked ? 'masked' : 'real' };
  }

  function closeUpload() {
    $('uploadBox').hidden = true;
    form.row = null;
    form.onChange();
  }

  function uploadRow() {
    return form.row;
  }

  async function uploadNow(row) {
    showUpload(row);
    if (!settings.uploadUrl) settings = await Server.getSettings();
    if (!settings.uploadUrl) {
      status('Not uploaded: set the Upload URL first: ', 'Settings', () => chrome.runtime.openOptionsPage());
      return false;
    }
    if (!(await Server.hasPermission(settings.uploadUrl))) {
      status('Not uploaded: BlackBox has no access to ' + new URL(settings.uploadUrl).origin + '. Click Send to allow it.');
      return false;
    }
    return upload(settings.uploadUrl, settings, row);
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
    Server.requestPermission(base).then((granted) => {
      if (!granted) {
        status('BlackBox needs access to ' + new URL(base).origin + ' to upload.');
        return;
      }
      return upload(base, used, row);
    }).catch((e) => status(e.message));
  }

  function uploadMany(rows) {
    if (!rows.length) return Promise.resolve();
    if (!settings.uploadUrl) {
      showUpload(rows[0]);
      status('Set the Upload URL first: ', 'Settings', () => chrome.runtime.openOptionsPage());
      return Promise.resolve();
    }
    const base = settings.uploadUrl;
    const used = settings;
    return Server.requestPermission(base).then(async (granted) => {
      if (!granted) {
        showUpload(rows[0]);
        status('BlackBox needs access to ' + new URL(base).origin + ' to upload.');
        return;
      }
      for (let i = 0; i < rows.length; i++) {
        showUpload(rows[i]);
        if (rows.length > 1) $('upHeading').textContent = `Upload ${i + 1} of ${rows.length}: ` + ($('upTitle').value || 'recording');
        if (!(await upload(base, used, rows[i]))) return;
      }
    });
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
          return false;
        }
        throw e;
      }
      const hasVideo = rec.video instanceof Blob && rec.video.size > 0;
      if (!created[key]) {
        await form.ready;
        status('Uploading session…');
        const meta = await Server.request(base, 'POST', { action: 'create', title: $('upTitle').value, notes: $('upNotes').value, ...sampleChoice() },
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
      if (row.kind === 'saved') await idbUpdate(row.id, { uploaded, uploadProblem: undefined });
      else await idbMetaPut('uploaded:' + row.id, uploaded);
      const title = $('upTitle').value || row.id;
      closeUpload();
      status('');
      await form.onUploaded(row, title);
      return true;
    } catch (e) {
      status(e.message);
      return false;
    } finally {
      $('upSend').disabled = false;
      $('upProgress').hidden = true;
    }
  }

  function status(text, linkText, onClick) {
    const s = $('upStatus');
    s.replaceChildren(...linkify(text));
    if (linkText) s.append(link(linkText, onClick));
  }

  function linkify(text) {
    const parts = [];
    let at = 0;
    for (const m of text.matchAll(/https?:\/\/\S+?(?=[.,;:!?)]*(?:\s|$))|\bSettings\b/g)) {
      const t = m[0];
      parts.push(text.slice(at, m.index), link(t, () => (t === 'Settings' ? chrome.runtime.openOptionsPage() : chrome.tabs.create({ url: t }))));
      at = m.index + t.length;
    }
    parts.push(text.slice(at));
    return parts;
  }

  function link(text, onClick) {
    const a = document.createElement('a');
    a.href = '#';
    a.textContent = text;
    a.onclick = (e) => { e.preventDefault(); onClick(); };
    return a;
  }

  async function moveToFolder(row) {
    const rec = await idbGet(row.id);
    if (!rec?.report) throw new Error('That recording is no longer in browser storage.');
    const started = rec.report.recording?.startedAt;
    const name = started ? new Date(started).toISOString().replace(/[:.]/g, '-').slice(0, 19) : row.id;
    const files = [{ name: 'session.json', blob: new Blob([JSON.stringify(rec.report, null, 2)], { type: 'application/json' }) }];
    if (rec.video instanceof Blob) files.unshift({ name: 'recording.webm', blob: rec.video });
    const ids = [];
    for (const f of files) {
      ids.push(await chrome.downloads.download({ url: URL.createObjectURL(f.blob), filename: `blackbox/${name}/${f.name}`, saveAs: false }));
    }
    const results = await Promise.all(ids.map(waitForDownload));
    const failed = results.find((d) => d.state !== 'complete');
    if (failed) throw new Error('Move failed' + (failed.error ? ' (' + failed.error + ')' : '') + '. The recording is still in browser storage.');

    if (rec.uploaded) await idbMetaPut('uploaded:' + row.id, rec.uploaded);
    await idbDelete(row.id);
    if (Store.state.folderReady) await Store.scanFolder();
    const onDisk = Store.state.diskItems.some((d) => d.id === row.id);
    if (!onDisk && rec.uploaded) await idbAddHistory(row.id, rec.report, rec.uploaded);
    return { name, onDisk };
  }

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
      chrome.downloads.search({ id }).then(([d]) => { if (d && d.state !== 'in_progress') done(d); });
    });
  }

  function canDelete(row) {
    return row.kind === 'saved' || (row.kind === 'disk' && !!row.uploaded);
  }

  async function deleteRow(row, confirmed) {
    if (row.kind === 'disk') return deleteDiskRow(row, confirmed);
    const warn = row.uploaded || row.onDisk ? '' : ' It was not uploaded or exported.';
    if (!confirmed && !confirm('Delete this recording from the extension?' + warn)) return null;
    if (row.uploaded && !row.onDisk) await idbAddHistory(row.id, row, row.uploaded);
    await idbDelete(row.id);
    return 'Deleted the recording from the extension.';
  }

  async function deleteDiskRow(row, confirmed) {
    const item = Store.state.diskItems.find((d) => d.id === row.id);
    if (!item) return null;
    const folder = Store.state.folder;
    if ((await folder.requestPermission({ mode: 'readwrite' })) !== 'granted') {
      throw new Error(`BlackBox needs permission to edit "${folder.name}" to delete files.`);
    }
    if (!confirmed && !confirm(`Delete the files of this recording from "${folder.name}"? It stays on the server.`)) return null;
    for (const name of ['recording.webm', 'session.json']) {
      try {
        await item.dir.removeEntry(name);
      } catch (e) {
        if (e.name !== 'NotFoundError') throw e;
      }
    }
    if (item.parent) {
      try {
        await item.parent.removeEntry(item.dir.name);
      } catch (e) { }
    }
    await idbAddHistory(row.id, row, row.uploaded);
    await idbMetaDelete('uploaded:' + row.id);
    await Store.scanFolder();
    return `Deleted the files of ${row.id} from "${folder.name}".`;
  }

  return { linkify, initUploadForm, showUpload, closeUpload, uploadRow, uploadNow, uploadMany, moveToFolder, downloadZip, canDelete, deleteRow };
})();
