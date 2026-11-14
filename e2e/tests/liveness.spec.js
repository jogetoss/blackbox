const { execFileSync } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { chromium } = require('@playwright/test');
const { test, expect, JOGET, EXTENSION, ASSIGNMENT, loginJoget, api } = require('./fixtures');

const CONTAINER = process.env.BB_CONTAINER || 'j8213';
const WFLOW = process.env.BB_WFLOW || '/opt/wflow';

const sh = (args) => execFileSync('docker', ['exec', CONTAINER, ...args], { encoding: 'utf8' });

const OVERRIDE = { pingMs: 5000, lostMs: 20000, checkMs: 5000, minLimitMs: 60000 };
const OVERRIDE_FILE = `${WFLOW}/blackbox/liveness-test.json`;
const shAsMe = (args, input) => execFileSync('docker', ['exec', '-i', '-u', String(process.getuid()), CONTAINER, ...args], { encoding: 'utf8', input });
let overrideBefore = null;
test.beforeAll(() => {
  overrideBefore = sh(['sh', '-c', `cat ${OVERRIDE_FILE} 2>/dev/null || true`]) || null;
  shAsMe(['sh', '-c', `cat > ${OVERRIDE_FILE}`], JSON.stringify(OVERRIDE) + '\n');
});
test.afterAll(() => {
  if (overrideBefore === null) shAsMe(['rm', '-f', OVERRIDE_FILE]);
  else shAsMe(['sh', '-c', `cat > ${OVERRIDE_FILE}`], overrideBefore);
});
const captureMeta = (id) => JSON.parse(sh(['cat', `${WFLOW}/blackbox/pending/${id}/meta.json`]));
function captureLines(id) {
  const out = sh(['sh', '-c', `cat ${WFLOW}/blackbox/pending/${id}/system-log.*.jsonl 2>/dev/null || true`]);
  return out.split('\n').filter(Boolean).map((l) => { try { return JSON.parse(l); } catch { return null; } }).filter(Boolean);
}
function logALine() {
  execFileSync('curl', ['-s', '-o', '/dev/null', '-d', 'j_username=e2e-liveness&j_password=x', `${JOGET}/j_spring_security_check`]);
}

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
  b.kill = () => {
    execFileSync('pkill', ['-9', '-f', profile]);
  };
  b.dispose = async () => {
    await b.context?.close().catch(() => {});
    try { execFileSync('pkill', ['-9', '-f', profile]); } catch { }
    fs.rmSync(profile, { recursive: true, force: true });
    fs.rmSync(ext, { recursive: true, force: true });
  };
  return b.start();
}

async function startRecording(b, { openOnServer = false, key = 'serverCapture', quick } = {}) {
  const joget = await b.context.newPage();
  await loginJoget(joget);
  await joget.goto(ASSIGNMENT);
  await b.worker.evaluate(({ u, openOnServer, quick }) => chrome.storage.local.set({ uploadUrl: u, recordPageAudio: false, openOnServer,
    quick: { apps: true, ...quick } }), { u: `${JOGET}/api/blackbox`, openOnServer, quick });
  const tabId = await b.worker.evaluate((o) => chrome.tabs.query({ url: o + '/*' }).then((t) => t[0].id), new URL(JOGET).origin);
  await b.worker.evaluate((tabId) => start(tabId, 'screen').then(() => 'ok'), tabId);
  let capture;
  await expect.poll(async () => (capture = await b.worker.evaluate((key) => getState().then((r) => r?.startedAt && r[key]), key)),
    { timeout: 20_000 }).toBeTruthy();
  expect(capture.status, capture.message).toBe('on');
  await joget.reload();
  return { joget, capture };
}

const recorderOf = (b) => b.context.pages().find((p) => p.url().includes('recorder.html'));

async function keptRecording(b, captureId, timeout = 30_000) {
  const page = await b.context.newPage();
  await page.goto(`chrome-extension://${b.extensionId}/recordings.html`);
  let rec;
  await expect.poll(async () => (rec = await page.evaluate((id) => idbList().then((all) =>
    all.find((r) => (r.report?.recording?.serverCapture || r.report?.recording?.systemLog)?.id === id)), captureId)), { timeout }).toBeTruthy();
  return { page, rec };
}

async function waitForPings(id, pingMs, count = 2) {
  const first = await expect.poll(() => captureMeta(id).lastSeenAt || 0, { timeout: pingMs * 2 + 10_000 }).toBeGreaterThan(0)
    .then(() => captureMeta(id).lastSeenAt);
  await expect.poll(() => captureMeta(id).lastSeenAt, { timeout: pingMs * count + 10_000 }).toBeGreaterThanOrEqual(first + pingMs * (count - 1));
  return captureMeta(id);
}

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
    await recorder.waitForEvent('close', { timeout: 170_000 }).catch(() => {});
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
    const { checkMs } = OVERRIDE;
    await waitForPings(capture.id, pingMs);

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

    await b.context.close().catch(() => {});
    await b.start();
    const { page, rec } = await keptRecording(b, capture.id);
    expect(rec.recovered).toBe(true);
    await page.waitForTimeout(3000);
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
  const b = await browser(EXTENSION, { restoreSession: true });
  try {
    const { capture } = await startRecording(b);
    await new Promise((r) => setTimeout(r, 35_000));
    await waitForPings(capture.id, capture.pingMs, 1);
    b.kill();
    const killedAt = Date.now();
    await b.context.close().catch(() => {});
    await b.start();
    const me = await b.worker.evaluate((u) => fetch(u + '?action=me', { credentials: 'include' }).then((r) => r.status), `${JOGET}/api/blackbox`);
    console.log('relaunch: logged in after the restart?', me);
    const { rec } = await keptRecording(b, capture.id);
    const lastSavedAt = Date.parse(rec.report.recording.stoppedAt);
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

test('all-server logs: after a crash, no new lines in the capture log once it is stopped', async () => {
  test.setTimeout(180_000);
  const b = await browser();
  try {
    const { capture } = await startRecording(b, { quick: { allServer: true } });
    test.skip(!captureMeta(capture.id).allServer, 'this user may not capture all server log lines');
    await waitForPings(capture.id, capture.pingMs, 1);
    logALine();
    await expect.poll(() => captureLines(capture.id).filter((e) => e.other).length, { timeout: 10_000 }).toBeGreaterThan(0);

    b.kill();
    await expect.poll(() => captureMeta(capture.id).stopReason, { timeout: capture.lostMs + OVERRIDE.checkMs + 30_000, intervals: [500] })
      .toBe('browser-lost');
    const { stoppedAt } = captureMeta(capture.id);
    const atStop = captureLines(capture.id);
    for (let i = 0; i < 5; i++) logALine();
    await new Promise((r) => setTimeout(r, 2 * OVERRIDE.checkMs));
    const later = captureLines(capture.id);
    const afterStop = later.filter((e) => e.ts > stoppedAt);
    console.log(`all-server: ${atStop.length} lines when seen stopped, ${later.length} after more logging; ${afterStop.length} with ts > stoppedAt (between the last ping and the server's stop)`);
    expect(later.length, 'the capture log stops growing once the server has stopped it').toBe(atStop.length);
    expect(afterStop.filter((e) => e.ts > stoppedAt + capture.lostMs + OVERRIDE.checkMs), 'nothing after the server\'s stop').toEqual([]);
  } finally {
    await b.dispose();
  }
});
