const IDB_NAME = 'blackbox';
const IDB_STORE = 'recordings';
const MAX_KEPT = 10;

const IDB_META = 'meta';
const IDB_HISTORY = 'history';
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

async function idbList() {
  const all = await idbTx('readonly', (s) => ({ req: s.getAll() }));
  return all
    .filter((r) => r && r.id)
    .map(({ video, ...rest }) => ({ ...rest, videoSize: video ? video.size : 0 }))
    .sort((a, b) => (a.savedAt < b.savedAt ? 1 : -1));
}

async function idbAddRecording(rec) {
  await idbPut(rec.id, rec);
  await idbDelete('last');
  const all = await idbList();
  for (const old of all.slice(MAX_KEPT)) {
    if (old.uploaded) await idbAddHistory(old.id, old.report, old.uploaded);
    await idbDelete(old.id);
  }
}

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
