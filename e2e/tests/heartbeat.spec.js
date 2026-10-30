// The recorder's heartbeat (recording-ping): a real screen recording, started as the popup does. Headless records a
// blank screen, which is enough here. The server's pingMs is 30 s; set the test override first to keep this short
// (on j8213: joget.sh override '{"pingMs":2000}').
const { execFileSync } = require('child_process');
const { test, expect, JOGET, loginJoget, api } = require('./fixtures');

const CONTAINER = process.env.BB_CONTAINER || 'j8213';
const WFLOW = process.env.BB_WFLOW || '/opt/wflow';

// The capture's pending meta.json on the server (not in the API)
function captureMeta(id) {
  return JSON.parse(execFileSync('docker', ['exec', CONTAINER, 'cat', `${WFLOW}/blackbox/pending/${id}/meta.json`], { encoding: 'utf8' }));
}

// Starts a screen recording of the Joget tab; returns the service worker and the server capture ({ id, pingMs, lostMs })
async function startRecording(context, extensionId) {
  const joget = await context.newPage();
  await loginJoget(joget);
  const [worker] = context.serviceWorkers().filter((w) => w.url().includes(extensionId));
  // openOnServer off: the recording stays in the extension, nothing is uploaded
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

  // Pings mark the capture seen, with the last crash-safety save and the video size
  await expect.poll(() => captureMeta(capture.id).lastSeenAt || 0, { timeout: capture.pingMs * 2 + 5000 }).toBeGreaterThan(0);
  const seen = captureMeta(capture.id);
  expect(seen.lastSavedAt).toBeGreaterThan(0);
  expect(seen).toHaveProperty('videoBytes');

  // The server stops the capture (a plain stop here; BB2 and BB5 will do it on their own): the next ping stops the recording
  const stop = await api(joget, 'recording-stop', { id: capture.id }, 'POST');
  expect(stop.stopped).toBe(capture.id);
  await expect.poll(() => worker.evaluate(() => getState()), { timeout: capture.pingMs * 2 + 10_000 }).toBeNull();
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

  await context.clearCookies(); // logged out of Joget
  await expect.poll(() => recorder.textContent('#status'), { timeout: capture.pingMs * 2 + 5000 })
    .toContain('Server capture ended: logged out');
  expect(await worker.evaluate(() => getState().then((r) => !!r?.startedAt)), 'still recording').toBe(true);
  expect(await recorder.textContent('#status')).toContain('Recording…');

  await worker.evaluate(() => stop('user'));
  await loginJoget(joget); // close the capture the logged-out stop couldn't
  await api(joget, 'recording-stop', { id: capture.id }, 'POST');
});
