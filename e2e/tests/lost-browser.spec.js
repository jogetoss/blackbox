// The server's lost-browser check: a capture whose pings stop is ended by the server at its last ping. Driven through
// the API (start, one ping, then silence), as a crashed recorder would leave it. The defaults wait 3 min; set the test
// override first to keep this short (on j8213: joget.sh override '{"lostMs":5000,"checkMs":2000}').
const { execFileSync } = require('child_process');
const { test, expect, JOGET, loginJoget, api } = require('./fixtures');

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

test('a capture with no ping for lostMs is stopped at its last ping; one never pinged stays open', async ({ page }) => {
  await loginJoget(page);
  const lost = await page.evaluate(() => crypto.randomUUID());
  const silent = await page.evaluate(() => crypto.randomUUID());
  const start = await post(page, 'recording-start', lost);
  expect(start.status, JSON.stringify(start.body)).toBe(200);
  expect((await post(page, 'recording-start', silent)).status).toBe(200);
  const { lostMs } = start.body;
  test.setTimeout(60_000 + lostMs * 3);

  expect((await post(page, 'recording-ping', lost, { lastSavedAt: Date.now() })).body.state).toBe('on');
  const seenAt = captureMeta(lost).lastSeenAt;
  expect(seenAt).toBeGreaterThan(0);

  // No more pings: within lostMs and a check or two, the server stops it at lastSeenAt
  await expect.poll(() => captureMeta(lost).stopReason, { timeout: lostMs * 2 + 70_000, intervals: [1000] }).toBe('browser-lost');
  expect(captureMeta(lost).stoppedAt).toBe(seenAt);
  const ping = await post(page, 'recording-ping', lost);
  expect(ping.body).toEqual({ state: 'stopped', reason: 'browser-lost' });

  // Never pinged (an extension without the heartbeat): not stopped as browser-lost
  const meta = captureMeta(silent);
  expect(meta.stoppedAt).toBeUndefined();
  expect(meta.lastSeenAt).toBeUndefined();
  expect((await api(page, 'recording-stop', { id: silent }, 'POST')).stopped).toBe(silent);
});
