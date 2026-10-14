// Extension only: the Replay page. Plays the recording named in the address (?id= in browser storage,
// ?disk= in the linked folder) with viewer.js, and shows its status. The list is on the Recordings page.
(function () {
  const $ = (id) => document.getElementById(id);
  const params = new URLSearchParams(location.search);
  const kind = params.has('disk') ? 'disk' : params.has('id') ? 'saved' : null;
  const id = params.get('disk') || params.get('id');

  $('back').hidden = false;
  $('allowFolder').onclick = () => Store.allowFolder().then((ok) => ok && open()).catch(window.blackboxShowError);
  // Files opened from disk replace the recording: the address no longer describes what is shown
  document.addEventListener('blackbox-files-opened', () => {
    history.replaceState(null, '', location.pathname);
    $('recStatus').hidden = true;
  });

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
    const row = (await Store.allRows()).find((r) => r.kind === kind && r.id === id);
    if (row) {
      const box = $('recStatus');
      box.textContent = '';
      box.append(...Store.badges(row));
      if (row.uploaded?.url) {
        const a = document.createElement('a');
        a.href = row.uploaded.url;
        a.target = '_blank';
        a.textContent = 'Open uploaded';
        box.append(' ', a);
      }
      box.hidden = false;
    }
  }
})();
