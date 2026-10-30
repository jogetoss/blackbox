// The hard limit (maxRecordingMinutes, at least 5 min): the recorder shows the time left, warns 2 minutes before, and
// stops and saves itself at the limit with reason time-limit. Set the test override first to keep this short (on
// j8213: joget.sh override '{"minLimitMs":20000}'); without it the spec takes the setting's full length.
const { execFileSync } = require('child_process');
const { test, expect, JOGET, loginJoget } = require('./fixtures');

const CONTAINER = process.env.BB_CONTAINER || 'j8213';
const WFLOW = process.env.BB_WFLOW || '/opt/wflow';

// The capture's pending meta.json on the server (not in the API)
function captureMeta(id) {
  return JSON.parse(execFileSync('docker', ['exec', CONTAINER, 'cat', `${WFLOW}/blackbox/pending/${id}/meta.json`], { encoding: 'utf8' }));
}

test('the recorder counts down, warns, and stops itself at the time limit', async ({ context, extensionId }) => {
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
  expect(capture.maxRecordingMs, 'the start response carries the limit').toBeGreaterThanOrEqual(1000);
  test.setTimeout(60_000 + capture.maxRecordingMs * 2);

  // The server keeps the limit in the meta, for its own backup stop
  const meta = captureMeta(capture.id);
  expect(meta.maxUntil).toBe(meta.startedAt + capture.maxRecordingMs);

  let recorder;
  await expect.poll(() => (recorder = context.pages().find((p) => p.url().includes('recorder.html')))).toBeTruthy();
  await expect.poll(() => recorder.textContent('#status')).toMatch(/\d+:\d\d left/);
  if (capture.maxRecordingMs <= 2 * 60_000) { // the warning shows in the last 2 minutes
    await expect.poll(() => recorder.textContent('#limit')).toContain('stops and saves itself');
    expect(await recorder.title()).toContain('BlackBox: stops in');
  }

  // At the limit it stops through the normal stop (the app export runs, the server gets the stop) and closes
  await recorder.waitForEvent('close', { timeout: capture.maxRecordingMs + 40_000 });
  expect(await worker.evaluate(() => getState())).toBeNull();
  const page = await context.newPage();
  await page.goto(`chrome-extension://${extensionId}/recordings.html`);
  let rec;
  await expect.poll(async () => (rec = await page.evaluate((id) => idbList().then((all) =>
    all.find((r) => r.report?.recording?.serverCapture?.id === id)), capture.id)), { timeout: 20_000 }).toBeTruthy();
  expect(rec.report.recording.stopReason).toBe('time-limit');
  const after = captureMeta(capture.id);
  expect(after.stoppedAt, 'the recorder stopped it, not the server backup').toBeGreaterThan(0);
  expect(after.stopReason).toBeUndefined();
});
