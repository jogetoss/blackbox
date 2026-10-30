// The size guard: the recorder records at 1.5 Mbit/s and stops itself (reason size-limit) at 90% of the server's
// maxSizeMb. A headless recording of a blank screen delivers 0-byte chunks, so the spec lowers the recorder's limit to
// 1 byte and feeds the real ondataavailable handler one small fake chunk (like MediaRecorder would).
const { test, expect, JOGET, loginJoget } = require('./fixtures');

test('the recorder records at a fixed bitrate and stops itself at the size limit', async ({ context, extensionId }) => {
  test.setTimeout(90_000);
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
  expect(capture.maxSizeMb, 'the start response carries the upload limit').toBeGreaterThan(0);

  let recorder;
  await expect.poll(() => (recorder = context.pages().find((p) => p.url().includes('recorder.html')))).toBeTruthy();
  await expect.poll(() => recorder.evaluate(() => sizeLimitBytes)).toBe(capture.maxSizeMb * 1024 * 1024);
  expect(await recorder.evaluate(() => recorder.videoBitsPerSecond)).toBe(1_500_000);
  expect(await recorder.evaluate((mb) => [sizeLimitReached(mb * 1024 * 1024 * 0.89, mb * 1024 * 1024),
    sizeLimitReached(mb * 1024 * 1024 * 0.9, mb * 1024 * 1024), sizeLimitReached(1e12, 0)], capture.maxSizeMb)).toEqual([false, true, false]);

  // A 0-byte chunk (what headless delivers) never trips the guard
  await recorder.evaluate(() => { sizeLimitBytes = 1; recorder.ondataavailable({ data: new Blob([]) }); });
  expect(await recorder.evaluate(() => sizeHit)).toBe(false);
  // A chunk over the limit: the normal stop runs and the recording is kept
  await recorder.evaluate(() => recorder.ondataavailable({ data: new Blob([new Uint8Array(1024)]) }));
  expect(await recorder.evaluate(() => sizeHit)).toBe(true);
  await recorder.waitForEvent('close', { timeout: 40_000 });
  expect(await worker.evaluate(() => getState())).toBeNull();
  const page = await context.newPage();
  await page.goto(`chrome-extension://${extensionId}/recordings.html`);
  let rec;
  await expect.poll(async () => (rec = await page.evaluate((id) => idbList().then((all) =>
    all.find((r) => r.report?.recording?.serverCapture?.id === id)), capture.id)), { timeout: 20_000 }).toBeTruthy();
  expect(rec.report.recording.stopReason).toBe('size-limit');
});
