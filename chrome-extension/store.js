// Extension only, shared by the Recordings page and the Replay page: the recordings in browser storage
// (IndexedDB, see idb.js) and the ones in the linked folder (read from disk, never copied).
const Store = (() => {
  const MAX_DISK = 50;       // newest recordings listed from the linked folder
  const state = {
    folder: null,            // linked FileSystemDirectoryHandle
    diskItems: [],           // scanned from the linked folder
    folderReady: false,      // read access granted and scanned
  };

  // { kind: 'saved' | 'disk' | 'history', id, startedAt, durationMs, tab, uploaded, onDisk }, newest first.
  // 'history' = uploaded, local copy gone.
  async function allRows() {
    const saved = (await idbList()).map((r) => ({ kind: 'saved', id: r.id, ...summary(r.report), uploaded: r.uploaded, uploadProblem: r.uploadProblem, recovered: !!r.recovered, imported: !!r.imported, onDisk: false }));
    const savedIds = new Set(saved.map((r) => r.id));
    for (const r of saved) if (state.diskItems.some((d) => d.id === r.id)) r.onDisk = true;
    const disk = [];
    for (const d of state.diskItems) {
      if (savedIds.has(d.id)) continue; // already listed; the badge says it's on disk too
      disk.push({ ...d, kind: 'disk', uploaded: await idbMetaGet('uploaded:' + d.id), onDisk: true });
    }
    const local = new Set([...saved, ...disk].map((r) => r.id));
    const history = (await idbHistoryList()).filter((h) => !local.has(h.id)).map((h) => ({ ...h, kind: 'history', onDisk: false }));
    return [...saved, ...disk, ...history].sort((a, b) => ((a.startedAt || a.id) < (b.startedAt || b.id) ? 1 : -1));
  }

  // issues: the Browser issues column (errors, failedRequests, slowRequests, slowThresholdMs; older recordings lack slow)
  function summary(report) {
    const rec = report?.recording || {};
    return { startedAt: rec.startedAt, durationMs: rec.durationMs, tab: rec.tab || {}, issues: report?.summary || null };
  }

  // { video: Blob|File|null, report } of one recording
  async function load(kind, id) {
    if (kind === 'disk') {
      const item = state.diskItems.find((d) => d.id === id);
      if (!item) throw new Error('That recording is no longer in the linked folder.');
      return {
        report: JSON.parse(await (await item.session.getFile()).text()),
        video: item.video ? await item.video.getFile() : null,
      };
    }
    const rec = await idbGet(id);
    if (!rec) throw new Error('That recording is no longer in browser storage.');
    return { video: rec.video, report: rec.report };
  }

  // Replay page address of a row
  function replayUrl(row) {
    return 'viewer.html?' + new URLSearchParams(row.kind === 'disk' ? { disk: row.id } : { id: row.id });
  }

  // ---------- linked folder ----------

  async function linkFolder() {
    if (!window.showDirectoryPicker) throw new Error('This browser cannot open folders.');
    let handle;
    try {
      handle = await window.showDirectoryPicker({ id: 'blackbox', mode: 'read', startIn: 'downloads' });
    } catch (e) {
      if (e.name === 'AbortError') return false; // the user closed the picker
      throw e;
    }
    state.folder = handle;
    await idbMetaPut('folder', handle);
    await scanFolder();
    return true;
  }

  async function unlinkFolder() {
    state.folder = null;
    state.diskItems = [];
    state.folderReady = false;
    await idbMetaDelete('folder');
  }

  // After a browser restart Chrome asks again; that needs a click (allowFolder).
  async function restoreFolder() {
    state.folder = (await idbMetaGet('folder')) || null;
    if (state.folder && (await state.folder.queryPermission({ mode: 'read' })) === 'granted') await scanFolder();
  }

  async function allowFolder() {
    if (!state.folder) return false;
    if ((await state.folder.requestPermission({ mode: 'read' })) !== 'granted') return false;
    await scanFolder();
    return true;
  }

  // Finds recordings in the folder: <folder>/<time>/session.json (+ recording.webm). Also accepts the parent
  // folder (blackbox/ inside it) or a single recording folder.
  async function scanFolder() {
    const folder = state.folder;
    const dirs = [];
    const blackbox = await child(folder, 'blackbox', 'directory');
    const base = blackbox || folder;
    if (await child(base, 'session.json', 'file')) dirs.push(base);
    for await (const entry of base.values()) if (entry.kind === 'directory') dirs.push(entry);
    dirs.sort((a, b) => (a.name < b.name ? 1 : -1));

    const items = [];
    for (const dir of dirs) {
      if (items.length >= MAX_DISK) break;
      const session = await child(dir, 'session.json', 'file');
      if (!session) continue;
      const head = await (await session.getFile()).slice(0, 16384).text();
      const item = { id: dir.name, dir, parent: dir === base ? null : base, session, video: await child(dir, 'recording.webm', 'file'), ...headerSummary(head) };
      if (!item.startedAt && !/"console"\s*:/.test(head) && !/"recording"\s*:/.test(head)) continue; // not ours
      items.push(item);
    }
    state.diskItems = items;
    state.folderReady = true;
  }

  async function child(dir, name, kind) {
    try {
      return kind === 'directory' ? await dir.getDirectoryHandle(name) : await dir.getFileHandle(name);
    } catch (e) {
      return null;
    }
  }

  // Reads startedAt / durationMs / tab from the start of session.json without parsing the whole file
  // (the "recording" block comes first, see recorder.js).
  function headerSummary(head) {
    const STR = /"((?:[^"\\]|\\.)*)"/.source;
    const unquote = (m) => { if (!m) return undefined; try { return JSON.parse('"' + m[1] + '"'); } catch (e) { return m[1]; } };
    const tabAt = head.indexOf('"tab"');
    const tab = tabAt >= 0 ? head.slice(tabAt) : '';
    const ms = head.match(/"durationMs"\s*:\s*(\d+)/);
    // "summary" follows the "recording" block, so it is in the first 16 KB too
    const sumAt = head.indexOf('"summary"');
    const sum = sumAt >= 0 ? head.slice(sumAt, sumAt + 400) : '';
    const num = (key) => { const m = sum.match(new RegExp('"' + key + '"\\s*:\\s*(\\d+)')); return m ? Number(m[1]) : undefined; };
    const issues = sum ? { errors: num('errors'), failedRequests: num('failedRequests'), slowRequests: num('slowRequests'), slowThresholdMs: num('slowThresholdMs') } : null;
    return {
      issues,
      startedAt: unquote(head.match(new RegExp('"startedAt"\\s*:\\s*' + STR))),
      durationMs: ms ? Number(ms[1]) : undefined,
      tab: { url: unquote(tab.match(new RegExp('"url"\\s*:\\s*' + STR))), title: unquote(tab.match(new RegExp('"title"\\s*:\\s*' + STR))) },
    };
  }

  // ---------- small DOM helpers shared by both pages ----------

  function badges(r) {
    const out = [];
    if (r.uploaded?.url) {
      const a = document.createElement('a');
      a.className = 'badge yes';
      a.href = r.uploaded.url;
      a.target = '_blank';
      a.textContent = 'Uploaded';
      out.push(a);
    } else {
      out.push(badge(r.uploaded ? 'Uploaded' : 'Not uploaded', !!r.uploaded));
    }
    if (r.recovered) out.push(badge('Recovered', false));
    if (r.imported) out.push(badge('Imported', false));
    if (r.kind === 'disk') out.push(badge('On disk', true));
    else if (r.onDisk) out.push(badge('Also on disk', true));
    return out;
  }

  function badge(text, yes) {
    const b = document.createElement('span');
    b.className = 'badge' + (yes ? ' yes' : '');
    b.textContent = text;
    return b;
  }

  function duration(ms) {
    if (!ms && ms !== 0) return '';
    const s = Math.round(ms / 1000);
    return Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0');
  }

  return { state, MAX_KEPT, allRows, load, replayUrl, linkFolder, unlinkFolder, restoreFolder, allowFolder, scanFolder, badges, duration };
})();
