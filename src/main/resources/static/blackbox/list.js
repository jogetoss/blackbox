// The server's recordings list: backend first (server issues, server data), a short browser summary, and batch
// Download ZIP / Delete for the ticked rows. Single-recording actions are on the replay page.
(function () {
  const { el, fmtDate, fmtDuration, fmtSize } = BB;
  const $ = (id) => document.getElementById(id);
  const PEAK = 0.8; // a resource at 80% or more of its limit is worth a badge
  let recordings = [];
  let shown = [];
  const selected = new Set();

  load();
  $('search').oninput = render;
  $('importFile').onchange = (e) => {
    const file = e.target.files[0];
    e.target.value = '';
    if (file) importZip(file);
  };
  $('selAll').onchange = () => {
    for (const r of shown) if ($('selAll').checked) selected.add(r.id); else selected.delete(r.id);
    render();
  };
  $('batchZip').onclick = downloadSelected;
  $('batchDelete').onclick = deleteSelected;

  // A ZIP from Download ZIP (here or on another Joget) becomes a new recording owned by the user
  async function importZip(file) {
    const label = $('importLabel');
    const text = label.firstChild;
    label.classList.add('disabled');
    showError(null);
    try {
      const meta = await BB.post('import', {}, file, 'application/zip', (done, total) => {
        text.textContent = 'Importing… ' + Math.round(100 * done / total) + '% ';
      });
      const url = 'view?id=' + encodeURIComponent(meta.id);
      if (!meta.serverDataSkipped) {
        location.href = url;
        return;
      }
      // Stay here to say why the copy has no server data; the ZIP may hold other users' data
      const open = document.createElement('a');
      open.href = url;
      open.textContent = 'Open it';
      $('notice').replaceChildren(`Imported "${meta.title || meta.id}" without its server data (system logs, traces, `
        + 'apps): importing it needs "Who can view server data" in the BlackBox settings. ', open);
      $('notice').hidden = false;
      await load();
    } catch (e) {
      if (!BB.loginNeeded(e, $('error'))) showError(e);
    } finally {
      text.textContent = 'Import ZIP… ';
      label.classList.remove('disabled');
    }
  }

  async function load() {
    try {
      const data = await BB.getJson('list');
      $('who').textContent = data.user.name;
      $('importLabel').hidden = !data.canUpload;
      recordings = data.recordings;
      $('content').hidden = false;
      render();
    } catch (e) {
      if (!BB.loginNeeded(e, $('error'))) showError(e);
    }
  }

  function render() {
    const q = $('search').value.trim().toLowerCase();
    shown = recordings.filter((r) => !q || [r.title, r.ownerName, r.owner, r.notes, r.recording?.tab?.url]
      .some((v) => (v || '').toLowerCase().includes(q)));
    for (const id of [...selected]) if (!recordings.some((r) => r.id === id)) selected.delete(id);
    // The server columns only when the user may see server data of at least one recording
    const server = recordings.some((r) => r.canViewServerData);
    document.querySelectorAll('.server-col').forEach((th) => { th.hidden = !server; });
    $('rows').replaceChildren(...shown.map((r) => row(r, server)));
    $('table').hidden = shown.length === 0;
    $('empty').hidden = shown.length > 0;
    $('empty').textContent = recordings.length ? 'No recording matches the filter.'
      : 'No recordings yet. Record with the BlackBox Chrome extension and click Upload in its viewer.';
    $('selAll').checked = shown.length > 0 && shown.every((r) => selected.has(r.id));
    renderBatch();
  }

  function renderBatch() {
    const rows = recordings.filter((r) => selected.has(r.id));
    const deletable = rows.filter((r) => r.canDelete).length;
    $('selCount').textContent = rows.length ? `${rows.length} selected` : 'Tick recordings to download or delete several at once';
    // A ZIP carries the Glowroot data for users who see server data: wait until it is built
    const building = rows.filter((r) => r.canViewServerData && r.serverData?.glowroot === 'building').length;
    $('batchZip').disabled = !rows.length || building > 0;
    $('batchZip').title = building ? `${building} of the selected ${building === 1 ? 'is' : 'are'} still building Glowroot data: reload in a minute` : '';
    $('batchDelete').disabled = !deletable;
    $('batchDelete').textContent = deletable ? `Delete selected (${deletable})` : 'Delete selected';
    $('batchDelete').title = rows.length > deletable ? `${rows.length - deletable} of the selected can't be deleted by you` : '';
  }

  function row(r, server) {
    const tr = el('tr');
    if (selected.has(r.id)) tr.className = 'current';
    const view = 'view?id=' + encodeURIComponent(r.id);

    const sel = el('td', 'sel');
    const box = el('input');
    box.type = 'checkbox';
    box.checked = selected.has(r.id);
    box.onchange = () => { if (box.checked) selected.add(r.id); else selected.delete(r.id); render(); };
    sel.append(box);
    tr.append(sel);

    const title = el('td', 'page');
    const a = el('a', 'rec-title', r.title);
    a.href = view;
    title.append(a);
    if (r.recording?.tab?.url) title.append(el('div', 'muted small', r.recording.tab.url));
    if (r.notes) title.append(el('div', 'muted small', r.notes.length > 140 ? r.notes.slice(0, 140) + '…' : r.notes));
    if (r.canViewServerData && r.apps?.length) {
      // Apps with problems first (the server lists them so), marked ⚠; at most 3 named
      const names = r.apps.map((x) => `${x.problems ? '⚠ ' : ''}${x.name || x.id} v${x.version}`);
      const apps = el('div', 'muted small', 'App: ' + names.slice(0, 3).join(', ') + (names.length > 3 ? ` +${names.length - 3}` : ''));
      apps.title = names.join('\n') + '\n\n⚠ = had failed requests, server errors or console errors. Exported at upload: '
        + 'open the recording\'s App tab to download the .jwa';
      title.append(apps);
    }
    tr.append(title);

    const owner = el('td', 'hide-sm', r.ownerName || r.owner);
    owner.title = r.owner;
    tr.append(owner);
    tr.append(el('td', 'nowrap', fmtDate(r.uploadedAt)));
    tr.append(el('td', 'nowrap hide-sm', fmtDuration(r.recording?.durationMs)));

    if (server) tr.append(serverIssues(r));
    const s = r.summary || {};
    const browser = el('td', 'nowrap');
    browser.append(badge(`${s.errors || 0} errors`, s.errors ? 'err' : ''), ' ', badge(`${s.failedRequests || 0} failed`, s.failedRequests ? 'warn' : ''));
    tr.append(browser);
    if (server) tr.append(serverTags(r));

    tr.append(el('td', 'nowrap hide-sm', r.videoSize ? fmtSize(r.videoSize) : 'missing'));
    return tr;
  }

  // System log errors, slow requests and peak load (only shown to users who may see server data)
  function serverIssues(r) {
    const td = el('td');
    if (!r.canViewServerData) return td;
    if (r.systemLog?.status !== 'attached') {
      td.append(el('span', 'muted', '–'));
      td.title = 'No server data: the system logs were not captured for this recording';
      return td;
    }
    const d = r.serverData || {};
    const errors = r.systemLog.errors || 0;
    td.append(badge(`${errors} server errors`, errors ? 'err' : ''));
    const t = d.traces;
    if (t?.slow) td.append(' ', badge(`${t.slow} slow`, 'warn', `Requests of ${fmtMs(d.slowMs)} or more (Glowroot's slow threshold)`));
    if (t?.pending) td.append(' ', badge(`⏳ ${t.pending}`, '', 'Traces still being collected'));
    const peak = peakLoad(d.load);
    if (peak) td.append(' ', badge(peak.text, 'warn', peak.title));
    return td;
  }

  // The resource that came closest to its limit, when it reached 80%; or DB connections that had to wait
  function peakLoad(load) {
    if (!load) return null;
    const items = [['CPU', load.cpu], ['Heap', load.heap], ['Threads', load.threads], ['DB', load.db]]
      .filter(([, v]) => v >= PEAK).sort((a, b) => b[1] - a[1]);
    if (items.length) {
      return { text: `${items[0][0]} ${Math.round(items[0][1] * 100)}%`, title: 'Peak load: ' + items.map(([k, v]) => `${k} ${Math.round(v * 100)}%`).join(', ') };
    }
    if (load.dbWaiting) return { text: `DB waits ${load.dbWaiting}`, title: 'Requests waited for a database connection' };
    return null;
  }

  function serverTags(r) {
    const td = el('td', 'hide-sm');
    if (!r.canViewServerData || r.systemLog?.status !== 'attached') return td;
    const d = r.serverData || {};
    td.append(badge('System logs', 'yes'));
    if (d.traces?.total) td.append(' ', badge(`Traces ${d.traces.total}`, 'yes'));
    if (d.load) td.append(' ', badge('Load', 'yes'));
    if (d.info) td.append(' ', badge('Info', 'yes', 'System Info snapshot'));
    if (d.glowroot === 'saved') td.append(' ', badge('Glowroot', 'yes', 'Glowroot data, in Glowroot\'s own format (Download Glowroot data on the replay page)'));
    if (d.glowroot === 'building') td.append(' ', badge('Glowroot ⏳', 'warn', 'The Glowroot data is still being built; reload in a minute'));
    if (d.glowroot === 'failed') td.append(' ', badge('Glowroot', 'err', 'The Glowroot data could not be built: see the replay page'));
    return td;
  }

  function badge(text, cls, title) {
    const b = el('span', 'badge' + (cls ? ' ' + cls : ''), text);
    if (title) b.title = title;
    return b;
  }

  function fmtMs(ms) {
    return ms >= 1000 ? `${ms / 1000} s` : `${ms} ms`;
  }

  // One download per recording; the browser may ask once to allow several downloads
  async function downloadSelected() {
    const rows = recordings.filter((r) => selected.has(r.id));
    for (const r of rows) {
      const a = el('a');
      a.href = BB.url('download', { id: r.id, format: 'zip' });
      a.download = '';
      document.body.append(a);
      a.click();
      a.remove();
      await new Promise((res) => setTimeout(res, 400));
    }
  }

  async function deleteSelected() {
    const rows = recordings.filter((r) => selected.has(r.id) && r.canDelete);
    if (!rows.length || !confirm(`Delete ${rows.length} recording${rows.length > 1 ? 's' : ''}? This cannot be undone.`)) return;
    $('batchDelete').disabled = true;
    showError(null);
    const failed = [];
    for (const r of rows) {
      try {
        await BB.post('delete', { id: r.id });
        recordings = recordings.filter((x) => x.id !== r.id);
        selected.delete(r.id);
      } catch (e) {
        failed.push(`${r.title}: ${e.message || e}`);
      }
    }
    render();
    if (failed.length) showError(new Error('Not deleted: ' + failed.join('; ')));
  }

  function showError(e) {
    $('error').textContent = e ? e.message || String(e) : '';
    $('error').hidden = !e;
  }
})();
