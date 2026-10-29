// IndexedDB helper shared by the recorder and the viewer (same extension origin).
// Recordings are kept here; only the newest MAX_KEPT stay (older uploaded ones remain as upload history).
// Record: { id, savedAt, video: Blob, report, uploaded: { url, id, at } | null }
// id = recording start time, e.g. 2026-10-13T16-47-29 (also the folder name under Downloads/blackbox/)

const IDB_NAME = 'blackbox';
const IDB_STORE = 'recordings';
const MAX_KEPT = 10;

const IDB_META = 'meta'; // linked folder handle, upload marks for recordings on disk
// Uploaded recordings whose local copy is gone: { id, startedAt, durationMs, tab, uploaded } (no limit)
const IDB_HISTORY = 'history';
// Crash safety: what a running recording has saved so far, keyed "<id>:meta", "<id>:v:<n>" (video Blob),
// "<id>:l:<n>" (new log entries). Cleared after a normal Stop; turned into a recording after a crash (report.js).
const IDB_PARTIAL = 'partial';

function idbOpen() {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(IDB_NAME, 4);
    req.onupgradeneeded = () => {
      const db = req.result;
      if (!db.objectStoreNames.contains(IDB_STORE)) db.createObjectStore(IDB_STORE);
      if (!db.objectStoreNames.contains(IDB_META)) db.createObjectStore(IDB_META);
      if (!db.objectStoreNames.contains(IDB_HISTORY)) db.createObjectStore(IDB_HISTORY);
      if (!db.objectStoreNames.contains(IDB_PARTIAL)) db.createObjectStore(IDB_PARTIAL);
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

async function idbTx(mode, fn, store = IDB_STORE) {
  const db = await idbOpen();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(store, mode);
    const result = fn(tx.objectStore(store));
    // Close each connection, so another page can upgrade the database version
    tx.oncomplete = () => { db.close(); resolve(result.req ? result.req.result : undefined); };
    tx.onerror = () => { db.close(); reject(tx.error); };
  });
}

function idbMetaGet(key) {
  return idbTx('readonly', (s) => ({ req: s.get(key) }), IDB_META);
}

function idbMetaPut(key, value) {
  return idbTx('readwrite', (s) => { s.put(value, key); return {}; }, IDB_META);
}

function idbMetaDelete(key) {
  return idbTx('readwrite', (s) => { s.delete(key); return {}; }, IDB_META);
}

function idbPut(key, value) {
  return idbTx('readwrite', (s) => { s.put(value, key); return {}; });
}

function idbGet(key) {
  return idbTx('readonly', (s) => ({ req: s.get(key) }));
}

function idbDelete(key) {
  return idbTx('readwrite', (s) => { s.delete(key); return {}; });
}

// Newest first, without the video Blobs (cheap to list).
async function idbList() {
  const all = await idbTx('readonly', (s) => ({ req: s.getAll() }));
  return all
    .filter((r) => r && r.id)
    .map(({ video, ...rest }) => ({ ...rest, videoSize: video ? video.size : 0 }))
    .sort((a, b) => (a.savedAt < b.savedAt ? 1 : -1));
}

// Saves a new recording and drops the oldest ones beyond MAX_KEPT.
async function idbAddRecording(rec) {
  await idbPut(rec.id, rec);
  await idbDelete('last'); // the single-recording format of older versions
  const all = await idbList();
  for (const old of all.slice(MAX_KEPT)) {
    if (old.uploaded) await idbAddHistory(old.id, old.report, old.uploaded); // still listed, as upload history
    await idbDelete(old.id);
  }
}

// ---------- upload history ----------

// Keeps a small row (no video, no logs) for an uploaded recording whose local copy is being removed.
// summary: the report, or an object with startedAt / durationMs / tab
function idbAddHistory(id, summary, uploaded) {
  const rec = summary?.recording || summary || {};
  const row = { id, startedAt: rec.startedAt, durationMs: rec.durationMs, tab: rec.tab || {}, uploaded };
  return idbTx('readwrite', (s) => { s.put(row, id); return {}; }, IDB_HISTORY);
}

function idbHistoryList() {
  return idbTx('readonly', (s) => ({ req: s.getAll() }), IDB_HISTORY);
}

function idbHistoryDelete(id) {
  return idbTx('readwrite', (s) => { s.delete(id); return {}; }, IDB_HISTORY);
}

async function idbUpdate(key, changes) {
  const rec = await idbGet(key);
  if (rec) await idbPut(key, { ...rec, ...changes });
}

// ---------- crash safety (partial recordings) ----------

function idbPartialPut(key, value) {
  return idbTx('readwrite', (s) => { s.put(value, key); return {}; }, IDB_PARTIAL);
}

function idbPartialKeys() {
  return idbTx('readonly', (s) => ({ req: s.getAllKeys() }), IDB_PARTIAL);
}

function idbPartialGet(key) {
  return idbTx('readonly', (s) => ({ req: s.get(key) }), IDB_PARTIAL);
}

async function idbPartialClear(id) {
  const keys = (await idbPartialKeys()).filter((k) => k.startsWith(id + ':'));
  return idbTx('readwrite', (s) => { for (const k of keys) s.delete(k); return {}; }, IDB_PARTIAL);
}
