// Crash recovery stops the server capture at the recording's last save. The crash is simulated: a capture started and
// pinged through the API, and a partial recording left in the extension's IndexedDB as a crashed recorder leaves it.
// The server takes the client's time only between startedAt and lastSeenAt + lostMs; otherwise it uses lastSeenAt.
const { execFileSync } = require('child_process');
const { test, expect, JOGET, loginJoget } = require('./fixtures');

const CONTAINER = process.env.BB_CONTAINER || 'j8213';
const WFLOW = process.env.BB_WFLOW || '/opt/wflow';

// The capture's pending meta.json on the server (not in the API)
function captureMeta(id) {
  return JSON.parse(execFileSync('docker', ['exec', CONTAINER, 'cat', `${WFLOW}/blackbox/pending/${id}/meta.json`], { encoding: 'utf8' }));
}

// POSTs an action for a capture with the page's Joget session and CSRF token
async function post(page, action, id, params = {}) {
  return page.evaluate(async ({ base, action, id, params }) => {
    const t = await (await fetch(`${base}/web/json/directory/user/csrfToken`)).json();
    const r = await fetch(`${base}/api/blackbox?` + new URLSearchParams({ action, id, ...params }),
      { method: 'POST', headers: { [t.tokenName]: t.tokenValue } });
    return { status: r.status, body: await r.json() };
  }, { base: JOGET, action, id, params });
}

test('recovering a crashed recording stops its server capture at the last save, within bounds', async ({ context, extensionId }) => {
  const joget = await context.newPage();
  await loginJoget(joget);
  const ids = { inBounds: await joget.evaluate(() => crypto.randomUUID()), late: await joget.evaluate(() => crypto.randomUUID()) };
  for (const id of Object.values(ids)) {
    const start = await post(joget, 'recording-start', id);
    expect(start.status, JSON.stringify(start.body)).toBe(200);
  }
  const page = await context.newPage();
  await page.goto(`chrome-extension://${extensionId}/recordings.html`);
  // The last pings, just before the crash (with a short test lostMs the lost-browser check must not get there first)
  for (const id of Object.values(ids)) expect((await post(joget, 'recording-ping', id)).body.state).toBe('on');
  const seen = { inBounds: captureMeta(ids.inBounds).lastSeenAt, late: captureMeta(ids.late).lastSeenAt };

  // Partial recordings as a crashed recorder leaves them: meta with the capture and the last save, no video
  const lastSaved = { inBounds: seen.inBounds + 1000, late: Date.now() + 24 * 3600 * 1000 }; // late: past lastSeenAt + lostMs
  await page.evaluate(async ({ ids, lastSaved, uploadUrl, server }) => {
    let startedAt = Date.now() - 60_000;
    for (const k of Object.keys(ids)) {
      startedAt += 1000; // a different recording id each
      await idbPartialPut(recordingId(startedAt) + ':meta', {
        startedAt, lastSavedAt: lastSaved[k], settings: {}, tab: { url: server, title: 'crashed' },
        serverCapture: { status: 'on', id: ids[k], server, uploadUrl },
      });
    }
    await recoverPartials(); // the page's own recovery at load may get there first: either one sends the stop
  }, { ids, lastSaved, uploadUrl: `${JOGET}/api/blackbox`, server: new URL(JOGET).origin });

  await expect.poll(() => captureMeta(ids.inBounds).stoppedAt, { timeout: 15_000 }).toBe(lastSaved.inBounds); // the last save
  await expect.poll(() => captureMeta(ids.late).stoppedAt, { timeout: 15_000 }).toBe(seen.late); // out of bounds: the last ping
  const kept = await page.evaluate((ids) => idbList().then((all) => all.filter((r) => r.recovered
    && ids.includes(r.report?.recording?.serverCapture?.id)).length), Object.values(ids));
  expect(kept, 'both are kept as recovered recordings').toBe(2);
});
