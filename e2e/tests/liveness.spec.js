// Recording liveness end to end (heartbeat, lost browser, crash recovery, hard limit, old extensions): real headless
// screen recordings against j8213, checked in the capture's meta.json on the server (read with docker exec).
// The crash tests kill this test's own Chromium (pkill -9 on its profile folder) and start it again on the same
// profile, as after a real crash. The spec sets the test override (liveness-test.json) itself for its run and puts
// back what was there after; its checks (the 60 s limit, checkMs) depend on these values.
// Every recording uploaded here is deleted at the end of its test.
const { execFileSync, execSync } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { chromium } = require('@playwright/test');
const { test, expect, JOGET, EXTENSION, ASSIGNMENT, loginJoget, api } = require('./fixtures');

const CONTAINER = process.env.BB_CONTAINER || 'j8213';
const WFLOW = process.env.BB_WFLOW || '/opt/wflow';
const OLD_TAG = process.env.BB_OLD_TAG || '8.2.1'; // the last release without the heartbeat
const REPO = path.join(__dirname, '..', '..');

const sh = (args) => execFileSync('docker', ['exec', CONTAINER, ...args], { encoding: 'utf8' });

// The test override (plugin README, Storage): short timing for this spec. Written as this user, as joget.sh does.
const OVERRIDE = { pingMs: 5000, lostMs: 20000, checkMs: 5000, minLimitMs: 60000 };
const OVERRIDE_FILE = `${WFLOW}/blackbox/liveness-test.json`;
const shAsMe = (args, input) => execFileSync('docker', ['exec', '-i', '-u', String(process.getuid()), CONTAINER, ...args], { encoding: 'utf8', input });
let overrideBefore = null; // the file's content before this spec (null: no file)
test.beforeAll(() => {
  overrideBefore = sh(['sh', '-c', `cat ${OVERRIDE_FILE} 2>/dev/null || true`]) || null;
  shAsMe(['sh', '-c', `cat > ${OVERRIDE_FILE}`], JSON.stringify(OVERRIDE) + '\n');
});
test.afterAll(() => {
  if (overrideBefore === null) shAsMe(['rm', '-f', OVERRIDE_FILE]);
  else shAsMe(['sh', '-c', `cat > ${OVERRIDE_FILE}`], overrideBefore);
});
// The capture's pending meta.json on the server (not in the API); kept after the upload, pointing at the recording
const captureMeta = (id) => JSON.parse(sh(['cat', `${WFLOW}/blackbox/pending/${id}/meta.json`]));
// The capture's log lines so far (all nodes), before the upload moves them into the recording
function captureLines(id) {
  const out = sh(['sh', '-c', `cat ${WFLOW}/blackbox/pending/${id}/system-log.*.jsonl 2>/dev/null || true`]);
  return out.split('\n').filter(Boolean).map((l) => { try { return JSON.parse(l); } catch { return null; } }).filter(Boolean);
}
// One ERROR line in Joget's log, on a thread no recording tags: a POST without a CSRF token
function logALine() {
  execFileSync('curl', ['-s', '-o', '/dev/null', '-d', 'j_username=e2e-liveness&j_password=x', `${JOGET}/j_spring_security_check`]);
}

// The extension copied to a folder of its own with host access to Joget (as fixtures.js does). Its id depends on the
// folder, so a browser started again on the same profile and folder finds its own IndexedDB.
function copyExtension(from) {
  const ext = fs.mkdtempSync(path.join(os.tmpdir(), 'bb-ext-'));
  fs.cpSync(from, ext, { recursive: true });
  const manifest = JSON.parse(fs.readFileSync(path.join(ext, 'manifest.json'), 'utf8'));
  manifest.host_permissions = [new URL(JOGET).origin + '/*'];
  fs.writeFileSync(path.join(ext, 'manifest.json'), JSON.stringify(manifest, null, 2));
  return ext;
}

function launch(profile, ext) {
  return chromium.launchPersistentContext(profile, {
    channel: 'chromium',
    headless: !process.env.BB_HEADED,
    args: [`--disable-extensions-except=${ext}`, `--load-extension=${ext}`, '--auto-select-desktop-capture-source=Entire screen'],
  });
}

async function workerOf(context) {
  let [worker] = context.serviceWorkers();
  if (!worker) worker = await context.waitForEvent('serviceworker');
  return worker;
}

// A browser of our own (not the fixture's), so a test can kill it and start it again
// restoreSession: Chrome's "Continue where you left off", which keeps session cookies (the Joget login) over a restart
async function browser(from = EXTENSION, { restoreSession = false } = {}) {
  const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'bb-profile-'));
  if (restoreSession) {
    fs.mkdirSync(path.join(profile, 'Default'));
    fs.writeFileSync(path.join(profile, 'Default', 'Preferences'), JSON.stringify({ session: { restore_on_startup: 1 } }));
  }
  const ext = copyExtension(from);
  const b = { profile, ext };
  b.start = async () => {
    b.context = await launch(profile, ext);
    b.worker = await workerOf(b.context);
    b.extensionId = new URL(b.worker.url()).host;
    return b;
  };
  b.kill = () => { // the browser process and its children: everything started with this profile
    execFileSync('pkill', ['-9', '-f', profile]);
  };
  b.dispose = async () => {
    await b.context?.close().catch(() => {});
    try { execFileSync('pkill', ['-9', '-f', profile]); } catch { /* none left */ }
    fs.rmSync(profile, { recursive: true, force: true });
    fs.rmSync(ext, { recursive: true, force: true });
  };
  return b.start();
}

// Logs in and starts a screen recording of the Joget tab (on the iService assignment, so the app export has an app).
// Returns the Joget page and the capture from the recording state ({ id, pingMs, lostMs, maxRecordingMs, ... }).
async function startRecording(b, { openOnServer = false, key = 'serverCapture' } = {}) {
  const joget = await b.context.newPage();
  await loginJoget(joget);
  await joget.goto(ASSIGNMENT);
  await b.worker.evaluate(({ u, openOnServer }) => chrome.storage.local.set({ uploadUrl: u, recordPageAudio: false, openOnServer }),
    { u: `${JOGET}/api/blackbox`, openOnServer });
  const tabId = await b.worker.evaluate((o) => chrome.tabs.query({ url: o + '/*' }).then((t) => t[0].id), new URL(JOGET).origin);
  await b.worker.evaluate((tabId) => start(tabId, 'screen').then(() => 'ok'), tabId);
  let capture;
  await expect.poll(async () => (capture = await b.worker.evaluate((key) => getState().then((r) => r?.startedAt && r[key]), key)),
    { timeout: 20_000 }).toBeTruthy();
  expect(capture.status, capture.message).toBe('on');
  await joget.reload(); // a tagged request with the recording running
  return { joget, capture };
}

const recorderOf = (b) => b.context.pages().find((p) => p.url().includes('recorder.html'));

// The kept recording of a capture, in the extension's IndexedDB
async function keptRecording(b, captureId, timeout = 30_000) {
  const page = await b.context.newPage();
  await page.goto(`chrome-extension://${b.extensionId}/recordings.html`);
  let rec;
  await expect.poll(async () => (rec = await page.evaluate((id) => idbList().then((all) =>
    all.find((r) => (r.report?.recording?.serverCapture || r.report?.recording?.systemLog)?.id === id)), captureId)), { timeout }).toBeTruthy();
  return { page, rec };
}

// Waits for the capture's pings, then for a crash-safety save after the last ping; returns the meta
async function waitForPings(id, pingMs, count = 2) {
  const first = await expect.poll(() => captureMeta(id).lastSeenAt || 0, { timeout: pingMs * 2 + 10_000 }).toBeGreaterThan(0)
    .then(() => captureMeta(id).lastSeenAt);
  await expect.poll(() => captureMeta(id).lastSeenAt, { timeout: pingMs * count + 10_000 }).toBeGreaterThanOrEqual(first + pingMs * (count - 1));
  return captureMeta(id);
}

// Deletes the uploaded recordings at the end (also when the test fails)
const uploads = [];
test.afterEach(async ({ page }) => {
  if (!uploads.length) return;
  await loginJoget(page);
  while (uploads.length) {
    const id = uploads.pop();
    const r = await api(page, 'delete', { id }, 'POST');
    console.log('deleted recording', id, JSON.stringify(r));
  }
});

test('normal stop: the recorder stops the capture, the app export runs', async () => {
  const b = await browser();
  try {
    const { capture } = await startRecording(b);
    test.setTimeout(240_000);
    await waitForPings(capture.id, capture.pingMs, 1);
    const recorder = recorderOf(b);
    const before = Date.now();
    await b.worker.evaluate(() => stop('user').then(() => 'ok'));
    await recorder.waitForEvent('close', { timeout: 170_000 }).catch(() => {}); // the stop waits for the export
    const after = Date.now();
    const meta = captureMeta(capture.id);
    expect(meta.stoppedAt, 'stopped by the recorder').toBeGreaterThanOrEqual(before - 1000);
    expect(meta.stoppedAt).toBeLessThanOrEqual(after);
    expect(meta.stopReason, 'no server stop reason').toBeUndefined();
    const { rec } = await keptRecording(b, capture.id);
    expect(rec.report.recording.stopReason).toBe('user');
    expect(rec.report.recording.serverCapture.appExport?.state, 'the app export ran at stop').toBe('done');
    expect(rec.report.recording.serverCapture.appExport.apps).toBeGreaterThan(0);
  } finally {
    await b.dispose();
  }
});

test('crash: the server stops the capture at the last ping; recovery and upload keep that time', async () => {
  test.setTimeout(300_000);
  const b = await browser();
  try {
    const { capture } = await startRecording(b);
    const { pingMs, lostMs } = capture;
    const { checkMs } = OVERRIDE; // the start response doesn't carry it
    const seen = await waitForPings(capture.id, pingMs);
    expect(seen.allServer, 'all-server logs on for this capture').toBe(true);

    b.kill();
    const killedAt = Date.now();
    const lastPing = captureMeta(capture.id).lastSeenAt;
    await expect.poll(() => captureMeta(capture.id).stopReason, { timeout: lostMs + checkMs + 30_000, intervals: [500] })
      .toBe('browser-lost');
    const detected = Date.now();
    const lost = captureMeta(capture.id);
    console.log(`crash: killed ${killedAt}, last ping ${lastPing} (${killedAt - lastPing} ms before), stopped ${lost.stoppedAt}, seen stopped after ${detected - killedAt} ms`);
    expect(detected - killedAt, 'stopped within lostMs + checkMs (+ 2 s polling)').toBeLessThanOrEqual(lostMs + checkMs + 2000);
    expect(lost.stoppedAt).toBe(lastPing);
    expect(killedAt - lost.stoppedAt, 'within one pingMs of the crash').toBeLessThanOrEqual(pingMs + 1000);

    // Start again on the same profile: recovery turns the partial into a recording and sends its stop
    await b.context.close().catch(() => {});
    await b.start();
    const { page, rec } = await keptRecording(b, capture.id);
    expect(rec.recovered).toBe(true);
    await page.waitForTimeout(3000); // the recovery stop
    expect(captureMeta(capture.id).stoppedAt, 'recovery keeps the stop time').toBe(lost.stoppedAt);

    const joget = await b.context.newPage();
    await loginJoget(joget);
    await page.goto(`chrome-extension://${b.extensionId}/viewer.html?id=${rec.id}`);
    const ok = await page.evaluate(async (id) => Actions.uploadNow((await Store.allRows()).find((r) => r.id === id)), rec.id);
    expect(ok, 'uploaded').toBe(true);
    const uploaded = await page.evaluate((id) => idbList().then((all) => all.find((r) => r.id === id).uploaded), rec.id);
    uploads.push(uploaded.id);
    const after = captureMeta(capture.id);
    expect(after.recording, 'attached to the upload').toBe(uploaded.id);
    expect(after.stoppedAt, 'upload keeps the stop time').toBe(lost.stoppedAt);
    const body = JSON.parse(sh(['cat', `${WFLOW}/blackbox/${uploaded.id}/system-log.json`]));
    expect(body.stoppedAt).toBe(lost.stoppedAt);
  } finally {
    await b.dispose();
  }
});

test('crash, started again before lostMs: the recovery stop sets stoppedAt to the last save', async () => {
  test.setTimeout(180_000);
  // Without it the restarted browser is logged out of Joget: the recovery stop gets 401 and BB2 closes the capture
  const b = await browser(EXTENSION, { restoreSession: true });
  try {
    const { capture } = await startRecording(b);
    await new Promise((r) => setTimeout(r, 35_000)); // Chrome writes cookies to disk about every 30 s
    await waitForPings(capture.id, capture.pingMs, 1);
    b.kill();
    const killedAt = Date.now();
    await b.context.close().catch(() => {});
    await b.start();
    const me = await b.worker.evaluate((u) => fetch(u + '?action=me', { credentials: 'include' }).then((r) => r.status), `${JOGET}/api/blackbox`);
    console.log('relaunch: logged in after the restart?', me);
    const { rec } = await keptRecording(b, capture.id);
    const lastSavedAt = Date.parse(rec.report.recording.stoppedAt); // a recovered recording ends at its last save
    await expect.poll(() => captureMeta(capture.id).stoppedAt || 0, { timeout: 15_000 }).toBeGreaterThan(0);
    const meta = captureMeta(capture.id);
    console.log(`relaunch: recovered ${Date.now() - killedAt} ms after the kill, lastSavedAt ${lastSavedAt}, stoppedAt ${meta.stoppedAt}, lastSeenAt ${meta.lastSeenAt}`);
    expect(Date.now() - meta.lastSeenAt, 'still within lostMs of the last ping').toBeLessThan(capture.lostMs);
    expect(meta.stopReason, 'not stopped as lost').toBeUndefined();
    expect(meta.stoppedAt).toBe(lastSavedAt);
  } finally {
    await b.dispose();
  }
});

test('time limit: the recorder stops itself, saves and uploads', async () => {
  const b = await browser();
  try {
    const { capture } = await startRecording(b, { openOnServer: true });
    test.setTimeout(capture.maxRecordingMs + 240_000);
    expect(capture.maxRecordingMs, 'the override limit (60 s)').toBe(OVERRIDE.minLimitMs);
    const meta = captureMeta(capture.id);
    expect(meta.maxUntil).toBe(meta.startedAt + capture.maxRecordingMs);
    const recorder = recorderOf(b);
    await recorder.waitForEvent('close', { timeout: capture.maxRecordingMs + 170_000 });
    const { page, rec } = await keptRecording(b, capture.id);
    expect(rec.report.recording.stopReason).toBe('time-limit');
    // openOnServer: the Replay page uploads it
    let up;
    await expect.poll(async () => (up = await page.evaluate((id) => idbList().then((all) => all.find((r) => r.id === id).uploaded), rec.id)),
      { timeout: 60_000 }).toBeTruthy();
    uploads.push(up.id);
    const after = captureMeta(capture.id);
    expect(after.stoppedAt, 'stopped by the recorder near the limit').toBeGreaterThanOrEqual(meta.maxUntil - 1000);
    expect(after.stoppedAt).toBeLessThan(meta.maxUntil + 120_000);
    expect(after.stopReason, 'not the server backup').toBeUndefined();
    expect(after.recording).toBe(up.id);
  } finally {
    await b.dispose();
  }
});

test('server stop: the next ping stops the recorder with server-stopped', async () => {
  test.setTimeout(120_000);
  const b = await browser();
  try {
    const { joget, capture } = await startRecording(b);
    await waitForPings(capture.id, capture.pingMs, 1);
    const recorder = recorderOf(b);
    const stop = await api(joget, 'recording-stop', { id: capture.id }, 'POST');
    expect(stop.stopped).toBe(capture.id);
    await recorder.waitForEvent('close', { timeout: capture.pingMs * 2 + 20_000 });
    const { rec } = await keptRecording(b, capture.id);
    expect(rec.report.recording.stopReason).toMatch(/^server-stopped: /);
  } finally {
    await b.dispose();
  }
});

test('old extension (no heartbeat): not stopped as lost, stopped by the server 2 min past its limit', async () => {
  const old = fs.mkdtempSync(path.join(os.tmpdir(), 'bb-old-'));
  try {
    execSync(`git -C "${REPO}" archive ${OLD_TAG} chrome-extension | tar -x -C "${old}"`);
  } catch (e) {
    test.skip(true, `no ${OLD_TAG} tag: ${e.message}`);
  }
  const b = await browser(path.join(old, 'chrome-extension'));
  try {
    const { capture } = await startRecording(b, { key: 'systemLog' }); // 8.2.1 keeps the capture under systemLog
    const meta = captureMeta(capture.id);
    expect(meta.maxUntil, 'the server sets a limit for every capture').toBeGreaterThan(0);
    const limitMs = meta.maxUntil - meta.startedAt;
    test.setTimeout(limitMs + 120_000 + 120_000);
    expect(capture.pingMs, 'the old extension keeps no ping timing').toBeUndefined();

    // Well past lostMs: no ping ever came, so it is not lost
    await new Promise((r) => setTimeout(r, OVERRIDE.lostMs + 2 * OVERRIDE.checkMs + 2000));
    const open = captureMeta(capture.id);
    expect(open.lastSeenAt).toBeUndefined();
    expect(open.stoppedAt, 'not stopped as lost').toBeUndefined();

    // The server backup: maxUntil + 2 min (+ a check)
    await expect.poll(() => captureMeta(capture.id).stopReason, { timeout: meta.maxUntil + 120_000 + 30_000 - Date.now(), intervals: [2000] })
      .toBe('time-limit');
    const stopped = captureMeta(capture.id);
    expect(stopped.stoppedAt).toBe(meta.maxUntil);
    expect(Date.now(), 'not before maxUntil + 2 min').toBeGreaterThanOrEqual(meta.maxUntil + 120_000);
    await b.worker.evaluate(() => stop('user').then(() => 'ok')).catch(() => {}); // the old recorder still runs; nothing uploaded
  } finally {
    await b.dispose();
    fs.rmSync(old, { recursive: true, force: true });
  }
});

test('all-server logs: after a crash, no new lines in the capture log once it is stopped', async () => {
  test.setTimeout(180_000);
  const b = await browser();
  try {
    const { capture } = await startRecording(b);
    const seen = await waitForPings(capture.id, capture.pingMs, 1);
    expect(seen.allServer, 'all-server logs on').toBe(true);
    logALine();
    await expect.poll(() => captureLines(capture.id).filter((e) => e.other).length, { timeout: 10_000 }).toBeGreaterThan(0);

    b.kill();
    await expect.poll(() => captureMeta(capture.id).stopReason, { timeout: capture.lostMs + OVERRIDE.checkMs + 30_000, intervals: [500] })
      .toBe('browser-lost');
    const { stoppedAt } = captureMeta(capture.id);
    const atStop = captureLines(capture.id);
    for (let i = 0; i < 5; i++) logALine();
    await new Promise((r) => setTimeout(r, 2 * OVERRIDE.checkMs)); // two checks
    const later = captureLines(capture.id);
    const afterStop = later.filter((e) => e.ts > stoppedAt);
    console.log(`all-server: ${atStop.length} lines when seen stopped, ${later.length} after more logging; ${afterStop.length} with ts > stoppedAt (between the last ping and the server's stop)`);
    expect(later.length, 'the capture log stops growing once the server has stopped it').toBe(atStop.length);
    expect(afterStop.filter((e) => e.ts > stoppedAt + capture.lostMs + OVERRIDE.checkMs), 'nothing after the server\'s stop').toEqual([]);
  } finally {
    await b.dispose();
  }
});
