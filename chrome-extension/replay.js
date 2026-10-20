// Extension only: the Replay page. Plays the recording named in the address (?id= in browser storage,
// ?disk= in the linked folder) with viewer.js, and shows its status. The list is on the Recordings page.
window.blackboxPage = 'extension'; // for the Features list
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
    $('fixBox').hidden = true;
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
    if (params.get('upload') === 'server') {
      // Once only: a reload must not upload again
      const now = new URLSearchParams(location.search); // may already hold ?t=
      now.delete('upload');
      history.replaceState(null, '', '?' + now);
      const row = (await Store.allRows()).find((r) => r.kind === kind && r.id === id);
      if (row && !row.uploaded) await openOnServer(row);
    }
  }

  // Just after a recording stops: logged in to the Upload URL's server and allowed to upload -> upload it and open
  // the server's replay page in this tab. Otherwise it replays here, and the Upload button says why: the reason is
  // saved with the recording (uploadProblem), so "⚠ Upload" is still there after a reload.
  async function openOnServer(row) {
    const { uploadUrl } = await Server.getSettings();
    if (!uploadUrl) return; // no server set up: the extension on its own
    const problem = await serverProblem(uploadUrl);
    if (problem) return setProblem(row, problem);
    if (!(await Actions.uploadNow(row))) {
      // The form stays open with the reason; keep it for the button once the form is closed
      return setProblem(row, { kind: 'other', text: $('upStatus').textContent });
    }
    const done = (await Store.allRows()).find((r) => r.kind === kind && r.id === id);
    if (done?.uploaded?.url) location.replace(done.uploaded.url); // the extension's copy stays in Recordings
  }

  // Why uploading to the Upload URL's server can't work now (null = it can), as { kind, text, origin }.
  // kind picks what the fix box offers: 'url' (no URL, no answer, no BlackBox endpoint), 'login', 'access'
  // (no host permission), 'denied' (logged in but not allowed), 'other'. Server.me gives up after 15 s.
  async function serverProblem(uploadUrl) {
    if (!uploadUrl) return { kind: 'url', text: 'There is no Upload URL in Settings.' };
    const origin = new URL(uploadUrl).origin;
    if (!(await Server.hasPermission(uploadUrl))) return { kind: 'access', text: `BlackBox has no access to ${origin}.`, origin };
    let me;
    try {
      me = await Server.me(uploadUrl);
    } catch (e) {
      if (e.status === 401) return { kind: 'login', text: `Not logged in to ${origin}.`, origin };
      const urlProblem = e.status === 0 || e.status === 404 || e.message.startsWith('No BlackBox upload endpoint');
      return { kind: urlProblem ? 'url' : 'other', text: e.message, origin };
    }
    if (me.canUpload) return null;
    return { kind: 'denied', text: `You (${me.user.name}) are not allowed to upload to ${origin}. Ask an admin of that server.`, origin };
  }

  // Saves the problem shown on "⚠ Upload" (null = a plain Upload button again)
  async function setProblem(row, problem) {
    if (row.kind === 'saved') await idbUpdate(row.id, { uploadProblem: problem || undefined });
    return renderStatus();
  }

  // Recordings from before kinds were saved carry only the text
  function asProblem(p) {
    return typeof p === 'string' ? { kind: 'other', text: p } : p;
  }

  // The Upload URL changed in Settings while "⚠ Upload" shows: check the new server, so the warning is about it,
  // or goes away when uploading there can work. No reload needed: Actions already uses the new URL.
  // The fix box does its own check after its Save.
  chrome.storage.onChanged.addListener((changes, area) => {
    if (area !== 'local' || !changes.uploadUrl || !$('fixBox').hidden) return;
    run(async () => {
      const row = (await Store.allRows()).find((r) => r.kind === kind && r.id === id);
      if (!row?.uploadProblem || row.uploaded) return;
      await setProblem(row, await serverProblem(changes.uploadUrl.newValue || ''));
    });
  });

  // ---------- fix box: Upload clicked ----------

  // Checks the server again: fixed -> the normal upload form; still a problem -> the reason and the action that
  // fixes it, in place of the form (no title or notes typed for an upload that can't work)
  async function openFix(row) {
    showFix('Checking the server…', []);
    const { uploadUrl } = await Server.getSettings();
    const problem = await serverProblem(uploadUrl);
    await setProblem(row, problem);
    if (!problem) {
      closeFix();
      Actions.showUpload(row);
      return;
    }
    const again = button('Try again', () => run(() => openFix(row)));
    const close = button('Close', () => closeFix());
    const actions = {
      // Saves like Settings does: normalized, host permission asked for inside this click, then stored
      url: () => {
        const save = button('Save and check', () => run(async () => {
          let next;
          try {
            next = Server.normalize($('fixUrlInput').value);
          } catch (e) {
            $('fixText').replaceChildren(...Actions.linkify(e.message));
            return;
          }
          if (!(await Server.requestPermission(next))) {
            $('fixText').replaceChildren(...Actions.linkify(`BlackBox needs access to ${new URL(next).origin} to upload there.`));
            return;
          }
          await chrome.storage.local.set({ uploadUrl: next });
          await openFix(row);
        }));
        save.className = 'primary';
        // The reason links Settings itself when it names it ("There is no Upload URL in Settings.")
        const settings = problem.text.includes('Settings') ? [] : [link('Settings', () => chrome.runtime.openOptionsPage())];
        return [save, ...settings, close];
      },
      login: () => [again, close], // the server's address in the reason is a link: log in there
      access: () => {
        const allow = button('Allow access', () => run(async () => {
          if (await Server.requestPermission(uploadUrl)) await openFix(row);
        }));
        allow.className = 'primary';
        return [allow, close];
      },
      denied: () => [close],
      other: () => [again, close],
    };
    showFix(problem.text, (actions[problem.kind] || actions.other)());
    $('fixUrl').hidden = problem.kind !== 'url';
    if (problem.kind === 'url') {
      $('fixUrlInput').value = uploadUrl || '';
      $('fixUrlInput').focus();
    }
  }

  function showFix(text, actions) {
    Actions.closeUpload();
    $('fixText').replaceChildren(...Actions.linkify(text)); // addresses and "Settings" as links
    $('fixUrl').hidden = true;
    $('fixActions').replaceChildren(...actions);
    $('fixBox').hidden = false;
    renderStatus().catch(window.blackboxShowError); // Upload is disabled while the box is open
  }

  function closeFix() {
    $('fixBox').hidden = true;
    renderStatus().catch(window.blackboxShowError);
  }

  function link(text, onClick) {
    const a = document.createElement('a');
    a.href = '#';
    a.textContent = text;
    a.onclick = (e) => { e.preventDefault(); onClick(); };
    return a;
  }

  // Status bar: Upload (or the Uploaded badge, a link to the server copy), the other badges, Move to folder, Download ZIP and Delete
  let renderSeq = 0;
  async function renderStatus() {
    const box = $('recStatus');
    const seq = ++renderSeq;
    const row = (await Store.allRows()).find((r) => r.kind === kind && r.id === id);
    if (seq !== renderSeq) return; // a newer render started while this one waited
    box.textContent = '';
    if (!row) {
      if (fromFiles) box.append(where('Opened from files', 'Read from the files you picked. Nothing is kept in the extension.'));
      box.hidden = !fromFiles;
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
      // "⚠ Upload" after a failed automatic upload: the reason in its tooltip. Either way a click checks the server
      // first (openFix): the form only when uploading can work, otherwise the reason in the fix box.
      const problem = asProblem(row.uploadProblem);
      const up = button(problem ? '⚠ Upload' : 'Upload', () => run(() => openFix(row)));
      up.disabled = Actions.uploadRow() !== null || !$('fixBox').hidden; // the form or the fix box is open
      if (problem) {
        up.classList.add('warn');
        up.title = 'Not uploaded: ' + problem.text;
      }
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
          await renderStatus();
          toast(text);
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
        toast(`Saved Downloads/${path}.`);
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

  // A short confirmation that fades out, so the header stays free of sentences
  let toastTimer;
  function toast(text) {
    let t = $('toast');
    if (!t) {
      t = document.createElement('div');
      t.id = 'toast';
      t.className = 'toast';
      t.setAttribute('role', 'status');
      document.body.append(t);
    }
    t.textContent = text;
    t.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { t.hidden = true; }, 5000);
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
