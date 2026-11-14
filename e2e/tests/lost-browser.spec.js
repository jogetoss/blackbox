const { execFileSync } = require('child_process');
const { test, expect, JOGET, loginJoget, api } = require('./fixtures');

const CONTAINER = process.env.BB_CONTAINER || 'j8213';
const WFLOW = process.env.BB_WFLOW || '/opt/wflow';

function captureMeta(id) {
  return JSON.parse(execFileSync('docker', ['exec', CONTAINER, 'cat', `${WFLOW}/blackbox/pending/${id}/meta.json`], { encoding: 'utf8' }));
}

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

  await expect.poll(() => captureMeta(lost).stopReason, { timeout: lostMs * 2 + 70_000, intervals: [1000] }).toBe('browser-lost');
  expect(captureMeta(lost).stoppedAt).toBe(seenAt);
  const ping = await post(page, 'recording-ping', lost);
  expect(ping.body).toEqual({ state: 'stopped', reason: 'browser-lost' });

  const meta = captureMeta(silent);
  expect(meta.stoppedAt).toBeUndefined();
  expect(meta.lastSeenAt).toBeUndefined();
  expect((await api(page, 'recording-stop', { id: silent }, 'POST')).stopped).toBe(silent);
});
