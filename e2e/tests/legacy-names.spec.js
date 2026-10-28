// Names from before the "server capture" rename: plugin 8.2.1 and extension 1.1 keep the capture summary under
// "systemLog" (meta.json, session.json's recording, the recording-start answer). This plugin and extension still read
// them. Run the suite with BB_EXTENSION=<an extension 1.1 folder> too, to test this plugin with the old extension.
const { execFileSync } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');
const {
  test, expect, JOGET, LEGACY_EXTENSION, CAPTURE_KEY, loginJoget, api, report, keep, captureAndStop, uploaded,
} = require('./fixtures');

const CONTAINER = process.env.BB_CONTAINER || 'j8213'; // the Joget container, for meta.json as plugin 8.2.1 saved it
const WFLOW = process.env.BB_WFLOW || '/opt/wflow';

// Uploads a kept recording the way the extension does after a recording stops (no click on Send)
async function uploadKept(page, extensionId, id) {
  await page.goto(`chrome-extension://${extensionId}/viewer.html?id=${id}`);
  expect(await page.evaluate(async (id) => Actions.uploadNow((await Store.allRows()).find((r) => r.id === id)), id)).toBe(true);
}

// A recording on the server with an attached server capture, uploaded with session.json's capture under key
async function uploadWithCapture(context, extensionId, joget, prefix, key) {
  const page = await context.newPage();
  await page.goto(`chrome-extension://${extensionId}/recordings.html`);
  const { id: captureId, stop } = await captureAndStop(page, { sampleData: 'masked' });
  const id = prefix + Date.now();
  await keep(page, extensionId, id, report(Date.now(), id, {
    [key]: { status: 'on', id: captureId, appExport: { state: stop.appExport.state, sampleData: 'masked', apps: stop.appExport.saved?.length } },
  }));
  return { page, id, captureId };
}

function docker(...args) {
  return execFileSync('docker', ['exec', CONTAINER, ...args], { encoding: 'utf8' });
}

test('the extension starts a server capture; the upload attaches its requests', async ({ context, extensionId }) => {
  const joget = await context.newPage();
  await loginJoget(joget);
  const page = await context.newPage();
  await page.goto(`chrome-extension://${extensionId}/recordings.html`);
  await page.evaluate((uploadUrl) => chrome.storage.local.set({ uploadUrl }), `${JOGET}/api/blackbox`);
  const worker = context.serviceWorkers()[0];

  // background.js's own start (startSystemLog in extension 1.1): reads the slow threshold from the plugin's answer
  const capture = await worker.evaluate(async (origin) => {
    const [tab] = await chrome.tabs.query({ url: origin + '/*' });
    const start = typeof startServerCapture === 'function' ? startServerCapture : startSystemLog;
    return start(tab.id);
  }, new URL(JOGET).origin);
  expect(capture.status, capture.message).toBe('on');
  expect(typeof capture.slowMs, 'slow threshold from the recording-start answer').toBe('number');

  await joget.reload(); // a tagged request: its log lines go to the capture
  await worker.evaluate(async (capture) => {
    const stop = typeof stopServerCapture === 'function' ? stopServerCapture : stopSystemLog;
    await stop(capture);
  }, capture);

  // session.json as the extension's own buildReport writes it (its meta key is the one its recorder sets)
  const id = 'e2e-ext-' + Date.now();
  await page.evaluate(async ({ id, key, capture }) => {
    const startedAt = Date.now() - 5000;
    const report = buildReport({ startedAt, tab: { url: location.href, title: id }, [key]: capture }, [], [], [], Date.now(), 'user');
    await idbAddRecording({ id, savedAt: new Date().toISOString(), video: null, report, uploaded: null });
  }, { id, key: CAPTURE_KEY, capture });
  const session = await page.evaluate(async (id) => (await idbGet(id)).report.recording, id);
  expect(session[CAPTURE_KEY].id).toBe(capture.id);

  await uploadKept(page, extensionId, id);
  const rec = await uploaded(joget, id);
  try {
    expect(rec.serverCapture.status).toBe('attached');
    expect(rec.serverCapture.requests, 'the tagged reload').toBeGreaterThan(0);
    expect(rec.systemLog).toBeUndefined();
  } finally {
    await api(joget, 'delete', { id: rec.id }, 'POST');
  }
});

test('a recording kept by extension 1.1 (systemLog in session.json) uploads with its server data', async ({ context, extensionId }) => {
  const joget = await context.newPage();
  await loginJoget(joget);
  const { page, id } = await uploadWithCapture(context, extensionId, joget, 'e2e-old-session-', 'systemLog');

  // the upload form reads the export done at stop from the old key too
  await page.goto(`chrome-extension://${extensionId}/viewer.html?id=${id}`);
  await page.evaluate(async (id) => Actions.showUpload((await Store.allRows()).find((r) => r.id === id)), id);
  await expect(page.locator('#upAtStop')).toContainText('exported when the recording stopped');

  await uploadKept(page, extensionId, id);
  const rec = await uploaded(joget, id);
  try {
    expect(rec.serverCapture.status).toBe('attached');
    expect(rec.systemLog).toBeUndefined();
  } finally {
    await api(joget, 'delete', { id: rec.id }, 'POST');
  }
});

test('meta.json saved by plugin 8.2.1 (systemLog) still shows its server data', async ({ context, extensionId }) => {
  const joget = await context.newPage();
  await loginJoget(joget);
  const { page, id } = await uploadWithCapture(context, extensionId, joget, 'e2e-old-meta-', CAPTURE_KEY);
  await uploadKept(page, extensionId, id);
  const rec = await uploaded(joget, id);
  try {
    const file = `${WFLOW}/blackbox/${rec.id}/meta.json`;
    docker('sed', '-i', 's/"serverCapture":/"systemLog":/', file);
    expect(docker('cat', file)).toContain('"systemLog":');

    const meta = await api(joget, 'meta', { id: rec.id });
    expect(meta.serverCapture.status).toBe('attached');
    expect(meta.systemLog).toBeUndefined();
    expect((await uploaded(joget, id)).serverCapture.status).toBe('attached');

    await joget.goto(`${JOGET}/blackbox/view?id=${rec.id}`);
    await expect(joget.locator('#serverTab')).toBeVisible({ timeout: 30_000 });
  } finally {
    await api(joget, 'delete', { id: rec.id }, 'POST');
  }
});

test('a ZIP made by plugin 8.2.1 (systemLog in session.json) imports on the server and in the extension', async ({ context, extensionId }) => {
  test.setTimeout(240_000); // the ZIP waits for the Glowroot data
  test.skip(LEGACY_EXTENSION, 'the extension part tests this extension only');
  const joget = await context.newPage();
  await loginJoget(joget);
  const { page, id } = await uploadWithCapture(context, extensionId, joget, 'e2e-old-zip-', CAPTURE_KEY);
  await uploadKept(page, extensionId, id);
  const rec = await uploaded(joget, id);
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'bb-zip-'));
  let imported;
  try {
    // Download ZIP (409 while the Glowroot data is built, about a minute), then turn its session.json into the old one
    const b64 = await joget.evaluate(async (url) => {
      let res = await fetch(url);
      for (const until = Date.now() + 150_000; res.status === 409 && Date.now() < until; res = await fetch(url)) {
        await new Promise((r) => setTimeout(r, 5000));
      }
      if (!res.ok) throw new Error('download: HTTP ' + res.status + ' ' + await res.text());
      const bytes = new Uint8Array(await res.arrayBuffer());
      let s = '';
      for (const b of bytes) s += String.fromCharCode(b);
      return btoa(s);
    }, `${JOGET}/api/blackbox?action=download&format=zip&id=${rec.id}`);
    const fresh = path.join(dir, 'new.zip');
    const old = path.join(dir, 'old.zip');
    fs.writeFileSync(fresh, Buffer.from(b64, 'base64'));
    execFileSync('python3', ['-I', '-c', `
import json, sys, zipfile
src, dst = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(src) as a, zipfile.ZipFile(dst, 'w', zipfile.ZIP_DEFLATED) as b:
    for i in a.infolist():
        data = a.read(i.filename)
        if i.filename == 'session.json':
            s = json.loads(data)
            s['recording']['systemLog'] = s['recording'].pop('serverCapture')
            data = json.dumps(s).encode()
        b.writestr(i.filename, data)
`, fresh, old]);
    expect(fs.readFileSync(old).includes('system-log.json')).toBe(true);

    // Server: Import ZIP keeps its server data
    imported = await joget.evaluate(async ({ base, b64 }) => {
      const t = await (await fetch(`${base}/web/json/directory/user/csrfToken`)).json();
      const body = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
      const r = await fetch(`${base}/api/blackbox?action=import`, {
        method: 'POST', headers: { [t.tokenName]: t.tokenValue, 'Content-Type': 'application/zip' }, body,
      });
      return r.json();
    }, { base: JOGET, b64: fs.readFileSync(old).toString('base64') });
    expect(imported.serverCapture?.status, JSON.stringify(imported)).toBe('attached');
    expect(imported.systemLog).toBeUndefined();
    await joget.goto(`${JOGET}/blackbox/view?id=${imported.id}`);
    await expect(joget.locator('#serverTab')).toBeVisible({ timeout: 30_000 });

    // Extension: Recordings > Import ZIP drops the other server's capture id under the old key too
    await page.goto(`chrome-extension://${extensionId}/recordings.html`);
    await page.setInputFiles('#importZip', old);
    const oldCapture = () => page.evaluate(async () => {
      for (const row of await Store.allRows()) {
        const r = await idbGet(row.id);
        if (r?.imported && r.report?.recording?.systemLog) return r.report.recording.systemLog;
      }
      return null;
    });
    await expect.poll(oldCapture, { timeout: 15_000 }).not.toBeNull();
    const capture = await oldCapture();
    expect(capture.status).toBe('on');
    expect(capture.id).toBeUndefined();
  } finally {
    if (imported?.id) await api(joget, 'delete', { id: imported.id }, 'POST');
    await api(joget, 'delete', { id: rec.id }, 'POST');
    fs.rmSync(dir, { recursive: true, force: true });
  }
});
