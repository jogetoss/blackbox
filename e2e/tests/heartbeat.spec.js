const { execFileSync } = require('child_process');
const { test, expect, JOGET, loginJoget, api } = require('./fixtures');

const CONTAINER = process.env.BB_CONTAINER || 'j8213';
const WFLOW = process.env.BB_WFLOW || '/opt/wflow';

function captureMeta(id) {
  return JSON.parse(execFileSync('docker', ['exec', CONTAINER, 'cat', `${WFLOW}/blackbox/pending/${id}/meta.json`], { encoding: 'utf8' }));
}

async function startRecording(context, extensionId) {
  const joget = await context.newPage();
  await loginJoget(joget);
  const [worker] = context.serviceWorkers().filter((w) => w.url().includes(extensionId));
  await worker.evaluate((u) => chrome.storage.local.set({ uploadUrl: u, recordPageAudio: false, openOnServer: false }), `${JOGET}/api/blackbox`);
  const tabId = await worker.evaluate((o) => chrome.tabs.query({ url: o + '/*' }).then((t) => t[0].id), new URL(JOGET).origin);
  await worker.evaluate((tabId) => start(tabId, 'screen').then(() => 'ok'), tabId);
  let capture;
  await expect.poll(async () => (capture = await worker.evaluate(() => getState().then((r) => r?.startedAt && r.serverCapture))),
    { timeout: 20_000 }).toBeTruthy();
  expect(capture.status, capture.message).toBe('on');
  return { joget, worker, capture };
}

test('the recorder pings the server, and stops when the server has stopped the capture', async ({ context, extensionId }) => {
  const { joget, worker, capture } = await startRecording(context, extensionId);
  test.setTimeout(60_000 + capture.pingMs * 4);
  expect(capture.pingMs, 'the start response carries the timing').toBeGreaterThanOrEqual(1000);
  expect(capture.lostMs).toBeGreaterThanOrEqual(1000);

  await expect.poll(() => captureMeta(capture.id).lastSeenAt || 0, { timeout: capture.pingMs * 2 + 5000 }).toBeGreaterThan(0);
  const seen = captureMeta(capture.id);
  expect(seen.lastSavedAt).toBeGreaterThan(0);
  expect(seen).toHaveProperty('videoBytes');

  const recorder = context.pages().find((p) => p.url().includes('recorder.html'));
  const stop = await api(joget, 'recording-stop', { id: capture.id }, 'POST');
  expect(stop.stopped).toBe(capture.id);
  await recorder.waitForEvent('close', { timeout: capture.pingMs * 2 + 20_000 });
  expect(await worker.evaluate(() => getState())).toBeNull();
  const page = await context.newPage();
  await page.goto(`chrome-extension://${extensionId}/recordings.html`);
  let rec;
  await expect.poll(async () => (rec = await page.evaluate((id) => idbList().then((all) =>
    all.find((r) => r.report?.recording?.serverCapture?.id === id)), capture.id)), { timeout: 20_000 }).toBeTruthy();
  expect(rec.report.recording.stopReason).toBe('server-stopped: stopped');
});

test('a logged-out ping ends the server capture, the recording goes on', async ({ context, extensionId }) => {
  const { joget, worker, capture } = await startRecording(context, extensionId);
  test.setTimeout(60_000 + capture.pingMs * 4);
  let recorder;
  await expect.poll(() => (recorder = context.pages().find((p) => p.url().includes('recorder.html')))).toBeTruthy();

  await context.clearCookies();
  await expect.poll(() => recorder.textContent('#status'), { timeout: capture.pingMs * 2 + 5000 })
    .toContain('Server capture ended: logged out');
  expect(await worker.evaluate(() => getState().then((r) => !!r?.startedAt)), 'still recording').toBe(true);
  expect(await recorder.textContent('#status')).toContain('Recording…');

  await worker.evaluate(() => stop('user'));
  await loginJoget(joget);
  await api(joget, 'recording-stop', { id: capture.id }, 'POST');
});
