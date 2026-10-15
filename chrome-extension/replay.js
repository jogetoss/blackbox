// Extension only: the Replay page. Plays the recording named in the address (?id= in browser storage,
// ?disk= in the linked folder) with viewer.js, and shows its status. The list is on the Recordings page.
(function () {
  const $ = (id) => document.getElementById(id);
  const params = new URLSearchParams(location.search);
  let kind = params.has('disk') ? 'disk' : params.has('id') ? 'saved' : null; // 'disk' after Move to folder into the linked folder
  const id = params.get('disk') || params.get('id');

  $('back').hidden = false;
  // After an upload the Upload button turns into the Uploaded badge: that is the confirmation
  Actions.initUploadForm(() => renderStatus(), () => renderStatus().catch(window.blackboxShowError));
  $('allowFolder').onclick = () => Store.allowFolder().then((ok) => ok && open()).catch(window.blackboxShowError);
  // Files opened from disk replace the recording: the address no longer describes what is shown
  let fromFiles = false;
  document.addEventListener('blackbox-files-opened', () => {
    history.replaceState(null, '', location.pathname);
    kind = null; // no row: the status bar only says where it came from
    window.blackboxTimeInUrl = false; // the address no longer names a recording
    fromFiles = true;
    Actions.closeUpload();
  });

  window.blackboxTimeInUrl = !!kind; // viewer.html?id=…&t=… opens at that moment
  if (kind) start().catch(window.blackboxShowError);

  async function start() {
    if (kind === 'disk') {
      await Store.restoreFolder();
      if (!Store.state.folderReady) {
        $('folderAccess').hidden = !Store.state.folder;
        if (!Store.state.folder) throw new Error('No folder is linked. Link it on the Recordings page.');
        return;
      }
    }
    await open();
  }

  async function open() {
    $('folderAccess').hidden = true;
    const rec = await Store.load(kind, id);
    window.blackboxOpen(rec.video, rec.report);
    await renderStatus();
    if (params.get('upload') === 'auto') {
      // Once only: a reload must not upload again
      const now = new URLSearchParams(location.search); // may already hold ?t=
      now.delete('upload');
      history.replaceState(null, '', '?' + now);
      const row = (await Store.allRows()).find((r) => r.kind === kind && r.id === id);
      if (row && !row.uploaded) await Actions.uploadNow(row);
    }
  }

  // Status bar: Upload (or the Uploaded badge, a link to the server copy), the other badges, Move to folder, Download ZIP and Delete
  let renderSeq = 0;
  async function renderStatus(note) {
    const box = $('recStatus');
    const seq = ++renderSeq;
    const row = (await Store.allRows()).find((r) => r.kind === kind && r.id === id);
    if (seq !== renderSeq) return; // a newer render started while this one waited
    box.textContent = '';
    if (!row) {
      if (fromFiles) box.append(where('Opened from files', 'Read from the files you picked. Nothing is kept in the extension.'));
      if (note) box.append(note);
      box.hidden = !fromFiles && !note;
      window.blackboxSetNeighbours({ total: 0 }); // not one of the listed recordings
      return;
    }
    box.append(row.kind === 'disk'
      ? where(`In folder "${Store.state.folder?.name || 'linked folder'}"`, 'Read from the linked folder on this computer. Delete removes these files.')
      : where('In this browser', "Kept in this browser's extension storage. Delete removes only this copy, never one on a server."));
    // The location label above already says "On disk"
    const [uploadBadge, ...others] = Store.badges(row).filter((b) => b.textContent !== 'On disk');
    if (row.uploaded) {
      if (row.uploaded.url) uploadBadge.title = 'Open the uploaded recording on the server';
      box.append(uploadBadge);
    } else {
      const up = button('Upload', () => Actions.showUpload(row));
      up.disabled = Actions.uploadRow() !== null; // the form is open
      box.append(up);
    }
    box.append(...others);
    if (row.kind === 'saved') {
      const moveBtn = button('Move to folder', () => run(async () => {
        moveBtn.disabled = true;
        try {
          const { name, onDisk } = await Actions.moveToFolder(row);
          const text = `Moved to Downloads/blackbox/${name}` + (onDisk ? '.' : '; it is no longer in the extension.');
          if (onDisk) {
            // Still listed, now read from the linked folder
            kind = 'disk';
            const t = new URLSearchParams(location.search).get('t'); // keep the moment
            history.replaceState(null, '', Store.replayUrl({ kind, id }) + (t ? '&t=' + t : ''));
          }
          await renderStatus(text);
        } catch (e) {
          moveBtn.disabled = false;
          throw e;
        }
      }));
      moveBtn.title = 'Move recording.webm + session.json to Downloads/blackbox/ and free its space in the extension. Link that folder to keep it in this list.';
      box.append(moveBtn);
    }
    const zipBtn = button('Download ZIP', () => run(async () => {
      zipBtn.disabled = true;
      try {
        const path = await Actions.downloadZip(row);
        await renderStatus(`Saved Downloads/${path}.`);
      } finally {
        zipBtn.disabled = false;
      }
    }));
    zipBtn.title = 'One ZIP to import on a BlackBox server or in another browser (Recordings > Import ZIP…). It stays here too.';
    box.append(zipBtn);
    if (Actions.canDelete(row)) {
      box.append(button('Delete', () => run(async () => {
        if (await Actions.deleteRow(row)) location.href = 'recordings.html';
      })));
    }
    if (note) box.append(note);
    box.hidden = false;
    await neighbours();
  }

  // Older / Newer: the recordings that open here (in browser storage, or in the linked folder), in the
  // Recordings page's order (newest first). Upload-history rows have no local copy, so they are skipped.
  async function neighbours() {
    const rows = (await Store.allRows()).filter((r) => r.kind === 'saved' || (r.kind === 'disk' && Store.state.folderReady));
    const i = rows.findIndex((r) => r.kind === kind && r.id === id);
    if (i < 0) {
      window.blackboxSetNeighbours({ total: 0 });
      return;
    }
    const to = (r) => r && { href: Store.replayUrl(r), title: r.tab?.title || r.tab?.url || r.id };
    window.blackboxSetNeighbours({ older: to(rows[i + 1]), newer: to(rows[i - 1]), pos: i + 1, total: rows.length });
  }

  // Where this copy lives: the same label on every replay page (extension, server, downloaded file)
  function where(text, title) {
    const s = document.createElement('span');
    s.className = 'where';
    s.textContent = text;
    s.title = title;
    return s;
  }

  function button(text, onClick) {
    const b = document.createElement('button');
    b.textContent = text;
    b.onclick = onClick;
    return b;
  }

  function run(fn) {
    Promise.resolve().then(fn).catch(window.blackboxShowError);
  }
})();
