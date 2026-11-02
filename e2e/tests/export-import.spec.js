// Downloads and imports: the server's Download ZIP and Download HTML, the standalone viewer, Import ZIP on the server
// and in the extension, the extension's own Download ZIP, and the exported apps (.jwa) without data, with real data
// and with masked data, imported back into Joget over iService (each import adds an iService version; the test
// deletes it, and the masked rows it added, afterwards).
const { execFileSync } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');
const {
  test, expect, JOGET, LEGACY_EXTENSION, loginJoget, api, report, keep, captureAndStop, uploaded, appsWhenExported,
} = require('./fixtures');

const DB_CONTAINER = process.env.BB_DB_CONTAINER || 'mariadb';
const DB = process.env.BB_DB || 'jw8';
const VIEWER = path.join(__dirname, '..', '..', 'chrome-extension', 'dist', 'blackbox-viewer.html');

test.skip(LEGACY_EXTENSION, 'tests this extension only');

/** One SQL statement on Joget's database; rows as arrays of strings. */
function sql(query) {
  const out = execFileSync('docker', ['exec', '-i', DB_CONTAINER, 'sh', '-c', `mariadb -uroot -p"$MARIADB_ROOT_PASSWORD" -N -B ${DB}`],
    { input: query, encoding: 'utf8' });
  return out.split('\n').filter(Boolean).map((l) => l.split('\t'));
}
const quote = (s) => "'" + String(s).replace(/[\\']/g, '\\$&') + "'";

/** The entries of a ZIP (name → Buffer), read with Python's zipfile. */
function unzip(file) {
  const json = execFileSync('python3', ['-I', '-c', `
import base64, json, sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as z:
    print(json.dumps({i.filename: base64.b64encode(z.read(i.filename)).decode() for i in z.infolist() if not i.is_dir()}))
`, file], { encoding: 'utf8', maxBuffer: 512 * 1024 * 1024 });
  return new Map(Object.entries(JSON.parse(json)).map(([k, v]) => [k, Buffer.from(v, 'base64')]));
}

/** GET a URL with the browser's Joget session as bytes; waits out the ZIP's 409 while the Glowroot data is built. */
async function fetchBytes(page, url) {
  const b64 = await page.evaluate(async (url) => {
    let res = await fetch(url);
    for (const until = Date.now() + 150_000; res.status === 409 && Date.now() < until; res = await fetch(url)) {
      await new Promise((r) => setTimeout(r, 5000));
    }
    if (!res.ok) throw new Error(url + ': HTTP ' + res.status + ' ' + await res.text());
    const bytes = new Uint8Array(await res.arrayBuffer());
    let s = '';
    for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
    return btoa(s);
  }, url);
  return Buffer.from(b64, 'base64');
}

/** POST bytes with the page's CSRF token; multipart when `field` is given (Joget's App import form). */
async function postBytes(page, url, bytes, { field, fileName, contentType = 'application/zip' } = {}) {
  return page.evaluate(async ({ base, url, b64, field, fileName, contentType }) => {
    const t = await (await fetch(`${base}/web/json/directory/user/csrfToken`)).json();
    const body = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
    let init;
    if (field) {
      const form = new FormData();
      form.append(field, new Blob([body], { type: contentType }), fileName);
      form.append(t.tokenName, t.tokenValue);
      init = { method: 'POST', headers: { [t.tokenName]: t.tokenValue }, body: form };
    } else {
      init = { method: 'POST', headers: { [t.tokenName]: t.tokenValue, 'Content-Type': contentType }, body };
    }
    const r = await fetch(url, init);
    return { status: r.status, text: await r.text() };
  }, { base: JOGET, url, b64: bytes.toString('base64'), field, fileName, contentType });
}

/** A recording uploaded the way the extension does after a stop, with its server capture and apps exported with `mode` data. */
async function uploadWithApps(context, extensionId, joget, mode) {
  const page = await context.newPage();
  await page.goto(`chrome-extension://${extensionId}/recordings.html`);
  const { id: captureId, stop } = await captureAndStop(page, { sampleData: mode });
  const id = `e2e-export-${mode}-${Date.now()}`;
  await keep(page, extensionId, id, report(Date.now(), id, {
    serverCapture: { status: 'on', id: captureId, appExport: { state: stop.appExport.state, sampleData: mode, apps: stop.appExport.saved?.length } },
  }));
  await page.goto(`chrome-extension://${extensionId}/viewer.html?id=${id}`);
  expect(await page.evaluate(async (id) => Actions.uploadNow((await Store.allRows()).find((r) => r.id === id)), id)).toBe(true);
  const rec = await uploaded(joget, id);
  const apps = await appsWhenExported(joget, rec.id);
  return { page, id, rec, apps };
}

/** The server's Download ZIP of a recording, written to dir; returns the path and its entries. */
async function downloadZip(joget, dir, recId) {
  const file = path.join(dir, recId + '.zip');
  fs.writeFileSync(file, await fetchBytes(joget, `${JOGET}/api/blackbox?action=download&format=zip&id=${recId}`));
  return { file, entries: unzip(file) };
}

const iServiceVersions = () => sql("SELECT appVersion FROM app_app WHERE appId = 'iService'").map((r) => Number(r[0]));

/** Imports a .jwa over iService through App Center's import form; returns the new version. Every version it
 * added goes into `created`, also when a check fails, so the caller can delete it. */
async function importJwa(joget, bytes, created) {
  const before = iServiceVersions();
  expect(before.length, 'iService versions read from the database').toBeGreaterThan(0);
  const res = await postBytes(joget, `${JOGET}/web/console/app/import/submit`, bytes, { field: 'appZip', fileName: 'iService.jwa' });
  const added = iServiceVersions().filter((v) => !before.includes(v));
  created.push(...added);
  expect(res.status, res.text.slice(0, 500)).toBe(200);
  expect(added, 'one new iService version').toEqual([Math.max(...before) + 1]);
  return added[0];
}

async function deleteIServiceVersion(joget, version) {
  const res = await joget.evaluate(async ({ base, version }) => {
    const t = await (await fetch(`${base}/web/json/directory/user/csrfToken`)).json();
    const r = await fetch(`${base}/web/console/app/iService/${version}/delete`, {
      method: 'POST', headers: { [t.tokenName]: t.tokenValue, 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ [t.tokenName]: t.tokenValue }),
    });
    return r.status;
  }, { base: JOGET, version });
  expect(res).toBe(200);
  expect(iServiceVersions()).not.toContain(version);
}

/** The data/<table>.json files of a .jwa: table → rows. */
function jwaData(jwaBytes, dir) {
  const file = path.join(dir, 'app-' + Date.now() + '.jwa');
  fs.writeFileSync(file, jwaBytes);
  const out = {};
  for (const [name, buf] of unzip(file)) {
    if (name.startsWith('data/')) out[name.slice(5, -5)] = JSON.parse(buf.toString('utf8'));
  }
  return out;
}

// ---------- apps without data, with real data, with masked data ----------

for (const mode of ['none', 'real', 'masked']) {
  test(`Download ZIP with apps ${mode === 'none' ? 'without data' : `with ${mode} data`}: ZIP, .jwa and its import over iService`, async ({ context, extensionId }) => {
    test.setTimeout(300_000);
    const joget = await context.newPage();
    await loginJoget(joget);
    const me = await api(joget, 'me');
    test.skip(mode !== 'none' && !me.options?.sample, 'this user may not capture apps data');
    test.skip(mode === 'masked' && !me.options?.mask, 'this user may not mask apps data');
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'bb-export-'));
    const { rec, apps } = await uploadWithApps(context, extensionId, joget, mode);
    let imported;
    const versions = []; // iService versions the .jwa import added
    let version;
    let added = {}; // masked rows the .jwa import adds: table → ids
    try {
      const iService = apps.apps.find((a) => a.id === 'iService');
      expect(iService?.state).toBe('saved');
      expect(iService.sample?.mode).toBe(mode === 'none' ? undefined : mode);

      // The ZIP: recording, server data and the .jwa
      const { file, entries } = await downloadZip(joget, dir, rec.id);
      for (const name of ['meta.json', 'session.json', 'system-log.json', 'app/apps.json', `app/${iService.file}`]) {
        expect(entries.has(name), name + ' in the ZIP').toBe(true);
      }
      expect(JSON.parse(entries.get('session.json')).recording.tab.title).toBe(rec.title);
      const appsJson = JSON.parse(entries.get('app/apps.json'));
      const jwa = entries.get(`app/${iService.file}`);
      expect(jwa.equals(await fetchBytes(joget, `${JOGET}/api/blackbox?action=appfile&id=${rec.id}&file=${iService.file}`)),
        'the .jwa in the ZIP is the App tab\'s Download .jwa').toBe(true);

      // The .jwa's rows: none, real (original ids), masked (new ids from the idMap, user names userN)
      const data = jwaData(jwa, dir);
      if (mode === 'none') {
        expect(Object.keys(data), 'no data/ in the .jwa').toEqual([]);
        expect(appsJson.idMap).toBeUndefined();
      } else {
        for (const t of iService.sample.tables) {
          expect(data[t.table], `data/${t.table}.json`).toBeDefined();
          expect.soft(data[t.table].length, `rows in data/${t.table}.json (apps.json says ${t.rows})`).toBe(t.rows);
        }
        const rows = Object.values(data).flat();
        const map = appsJson.idMap.ids;
        expect(appsJson.idMap.kept).toBe(mode === 'real');
        for (const r of rows) {
          if (mode === 'real') {
            expect(sql(`SELECT COUNT(*) FROM app_fd_${Object.keys(data).find((t) => data[t].includes(r))} WHERE id = ${quote(r.id)}`)[0][0],
              'a real row has its original id').toBe('1');
          } else {
            expect(Object.values(map), 'a masked row has a new id').toContain(r.id);
            expect(Object.keys(map)).not.toContain(r.id);
            if (r.createdBy) expect(r.createdBy).toMatch(/^user\d+$/);
          }
        }
      }

      // Import ZIP on the server: a new recording with the same apps
      const res = await postBytes(joget, `${JOGET}/api/blackbox?action=import`, fs.readFileSync(file));
      expect(res.status, res.text).toBe(200);
      imported = JSON.parse(res.text);
      expect(imported.id).not.toBe(rec.id);
      const importedApps = await api(joget, 'apps', { id: imported.id });
      const importedIService = importedApps.apps.find((a) => a.id === 'iService');
      expect(importedIService?.state).toBe('saved');
      expect(importedIService.sample?.mode).toBe(iService.sample?.mode);
      expect(jwa.equals(await fetchBytes(joget, `${JOGET}/api/blackbox?action=appfile&id=${imported.id}&file=${iService.file}`)),
        'the imported recording keeps the .jwa').toBe(true);

      // Import the .jwa over iService: a new app version; with data, its rows are in the app's tables
      const counts = Object.fromEntries(Object.keys(data).map((t) => [t, Number(sql(`SELECT COUNT(*) FROM app_fd_${t}`)[0][0])]));
      if (mode === 'masked') added = Object.fromEntries(Object.entries(data).map(([t, rows]) => [t, rows.map((r) => r.id)]));
      version = await importJwa(joget, jwa, versions);
      for (const [t, rows] of Object.entries(data)) {
        for (const r of rows) {
          expect(sql(`SELECT COUNT(*) FROM app_fd_${t} WHERE id = ${quote(r.id)}`)[0][0], `${t} row ${r.id} imported`).toBe('1');
        }
        const now = Number(sql(`SELECT COUNT(*) FROM app_fd_${t}`)[0][0]);
        expect(now, `${t}: ${mode === 'masked' ? 'masked rows added' : 'real rows overwrite their own ids'}`)
          .toBe(counts[t] + (mode === 'masked' ? rows.length : 0));
      }
      console.log(`${mode}: ${Object.values(data).flat().length} rows in the .jwa, imported as iService v${version}`);
    } finally {
      for (const v of versions) await deleteIServiceVersion(joget, v);
      for (const [t, ids] of Object.entries(added)) {
        if (ids.length) sql(`DELETE FROM app_fd_${t} WHERE id IN (${ids.map(quote).join(',')})`);
      }
      if (imported?.id) await api(joget, 'delete', { id: imported.id }, 'POST');
      await api(joget, 'delete', { id: rec.id }, 'POST');
      fs.rmSync(dir, { recursive: true, force: true });
    }
  });
}

// ---------- Download HTML, the standalone viewer, the extension's ZIPs ----------

test('Download HTML replays without Joget; the standalone viewer and the extension open the server ZIP', async ({ context, extensionId }) => {
  test.setTimeout(240_000);
  const joget = await context.newPage();
  await loginJoget(joget);
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'bb-html-'));
  const { page, id, rec } = await uploadWithApps(context, extensionId, joget, 'none');
  let fromExtension;
  try {
    // Download HTML: one file with the recording, server data and the app list, no .jwa
    const html = (await fetchBytes(joget, `${JOGET}/api/blackbox?action=download&format=html&id=${rec.id}`)).toString('utf8');
    for (const block of ['bb-meta', 'bb-session', 'bb-systemlog', 'bb-apps']) expect(html, block).toContain(`id="${block}"`);
    expect(html).not.toContain('.jwa"><'); // no .jwa embedded, only listed
    expect(html).not.toMatch(/<script[^>]+src=/); // nothing loaded from the server
    const htmlFile = path.join(dir, 'recording.html');
    fs.writeFileSync(htmlFile, html);

    // Opened from disk, offline from Joget: the replay shows, with its App tab
    const offline = await context.newPage();
    const errors = [];
    offline.on('pageerror', (e) => errors.push(e.message));
    await offline.route(`${new URL(JOGET).origin}/**`, (r) => r.abort());
    await offline.goto('file://' + htmlFile);
    await expect(offline.locator('#app')).toBeVisible({ timeout: 30_000 });
    await expect(offline.locator('body')).toContainText(rec.title);
    await expect(offline.locator('#appsTab')).toBeVisible();
    await offline.click('#appsTab');
    await expect(offline.locator('#appsPage')).toContainText('Support Request');
    expect(errors, 'no script errors').toEqual([]);

    // The standalone viewer (dist/blackbox-viewer.html) opens the server's ZIP
    const { file: zip } = await downloadZip(joget, dir, rec.id);
    const viewer = await context.newPage();
    const viewerErrors = [];
    viewer.on('pageerror', (e) => viewerErrors.push(e.message));
    await viewer.goto('file://' + VIEWER);
    await viewer.setInputFiles('#files', zip);
    await expect(viewer.locator('#app')).toBeVisible({ timeout: 30_000 });
    await expect(viewer.locator('#recTitle')).toContainText(rec.title);
    expect(viewerErrors, 'no script errors').toEqual([]);

    // The standalone viewer also opens session.json on its own
    const session = path.join(dir, 'session.json');
    fs.writeFileSync(session, unzip(zip).get('session.json'));
    await viewer.reload();
    await viewer.setInputFiles('#files', session);
    await expect(viewer.locator('#app')).toBeVisible({ timeout: 30_000 });

    // Extension: Recordings > Import ZIP of the server's ZIP (drop the kept copy first: same recording id)
    await page.goto(`chrome-extension://${extensionId}/recordings.html`);
    await page.evaluate((id) => idbDelete(id), id);
    await page.reload();
    await page.setInputFiles('#importZip', zip);
    await expect(page.locator('#libraryMsg')).toContainText('Imported');
    const importedRow = () => page.evaluate(async () => {
      for (const row of await Store.allRows()) {
        const r = await idbGet(row.id);
        if (r?.imported) return { id: row.id, title: r.report.recording.tab.title, capture: r.report.recording.serverCapture || null };
      }
      return null;
    });
    await expect.poll(importedRow).not.toBeNull();
    const row = await importedRow();
    expect(row.title).toBe(rec.title);
    expect(row.capture?.id, 'the other server\'s capture id is dropped').toBeUndefined();
    await page.goto(`chrome-extension://${extensionId}/viewer.html?id=${row.id}`);
    await expect(page.locator('#app')).toBeVisible({ timeout: 30_000 });

    // Extension: Download ZIP, then Import ZIP of that file on the server
    const downloaded = await page.evaluate(async (id) => {
      const row = (await Store.allRows()).find((r) => r.id === id);
      await Actions.downloadZip(row);
      const [d] = await chrome.downloads.search({ orderBy: ['-startTime'], limit: 1, state: 'complete' });
      return d?.filename;
    }, row.id);
    expect(downloaded && fs.existsSync(downloaded), `extension ZIP at ${downloaded}`).toBe(true);
    const extEntries = unzip(downloaded);
    expect([...extEntries.keys()].sort()).toEqual(['meta.json', 'session.json']);
    const res = await postBytes(joget, `${JOGET}/api/blackbox?action=import`, fs.readFileSync(downloaded));
    expect(res.status, res.text).toBe(200);
    fromExtension = JSON.parse(res.text);
    const meta = await api(joget, 'meta', { id: fromExtension.id });
    expect(meta.title).toBe(rec.title);
    const s = await api(joget, 'session', { id: fromExtension.id });
    expect(s.network.length).toBe(1);
    fs.rmSync(downloaded, { force: true });
  } finally {
    if (fromExtension?.id) await api(joget, 'delete', { id: fromExtension.id }, 'POST');
    await api(joget, 'delete', { id: rec.id }, 'POST');
    fs.rmSync(dir, { recursive: true, force: true });
  }
});
