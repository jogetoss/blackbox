// The hard limit without server capture (BB6: "No server capture: no size guard, only the time limit"): the recorder
// takes maxRecordingMs from the server's off answer (neither "all other server log lines" nor "Glowroot data" on), else the
// built-in 30 minutes (no Upload URL, not logged in, server down, a plugin without the limit). Nothing is uploaded.
const { test, expect, JOGET, loginJoget } = require('./fixtures');

const DEFAULT_MS = 30 * 60_000;

// before(worker): runs in the service worker before the start, e.g. to stub the server's answer
async function startScreen(context, extensionId, uploadUrl, before) {
  const joget = await context.newPage();
  await loginJoget(joget);
  const [worker] = context.serviceWorkers().filter((w) => w.url().includes(extensionId));
  if (before) await worker.evaluate(before);
  await worker.evaluate((u) => (u ? chrome.storage.local.set({ uploadUrl: u, recordPageAudio: false, openOnServer: false })
    : chrome.storage.local.remove('uploadUrl').then(() => chrome.storage.local.set({ recordPageAudio: false, openOnServer: false }))), uploadUrl);
  const tabId = await worker.evaluate((o) => chrome.tabs.query({ url: o + '/*' }).then((t) => t[0].id), new URL(JOGET).origin);
  await worker.evaluate((tabId) => start(tabId, 'screen').then(() => 'ok'), tabId);
  let capture;
  await expect.poll(async () => (capture = await worker.evaluate(() => getState().then((r) => r?.startedAt && r.serverCapture))),
    { timeout: 20_000 }).toBeTruthy();
  let recorder;
  await expect.poll(() => (recorder = context.pages().find((p) => p.url().includes('recorder.html')))).toBeTruthy();
  return { worker, capture, recorder };
}

async function stopRecording(worker) {
  await worker.evaluate(() => stop('user').then(() => 'ok'));
}

test('no Upload URL: the built-in 30-minute limit, no size guard', async ({ context, extensionId }) => {
  const { worker, capture, recorder } = await startScreen(context, extensionId, null);
  expect(capture.status).toBe('off');
  await expect.poll(() => recorder.evaluate(() => limitAt && limitAt - meta.startedAt)).toBe(DEFAULT_MS);
  expect(await recorder.evaluate(() => sizeLimitBytes)).toBe(0);
  await expect(recorder.locator('#status')).toContainText('left');
  await stopRecording(worker);
});

test("server capture off: the limit from the server's off answer, no size guard", async ({ context, extensionId }) => {
  // The plugin's answer when neither "all other server log lines" nor "Glowroot data" is on: 403 with serverCapture.maxRecordingMs
  const stub = () => {
    const real = fetch;
    self.fetch = (url, init) => (String(url).includes('action=recording-start')
      ? Promise.resolve(new Response(JSON.stringify({ error: { code: '403', message: 'Server data capture is turned off.' },
        serverCapture: { maxRecordingMs: 600000 } }), { status: 403, headers: { 'Content-Type': 'application/json' } }))
      : real(url, init));
  };
  const { worker, capture, recorder } = await startScreen(context, extensionId, `${JOGET}/api/blackbox`, stub);
  expect(capture.status).toBe('off');
  expect(capture.message).toBe('Server data capture is turned off.');
  await expect.poll(() => recorder.evaluate(() => limitAt && limitAt - meta.startedAt)).toBe(600_000);
  expect(await recorder.evaluate(() => sizeLimitBytes)).toBe(0);
  await stopRecording(worker);
});
