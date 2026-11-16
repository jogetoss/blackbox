const { execFileSync } = require('child_process');
const { test, expect, JOGET, CAPTURE_KEY, loginJoget, api, uploaded } = require('./fixtures');

const CONTAINER = process.env.BB_CONTAINER || 'j8213';
const WFLOW = process.env.BB_WFLOW || '/opt/wflow';
const UPLOAD_URL = `${JOGET}/api/blackbox`;

function captureMeta(id) {
  return JSON.parse(execFileSync('docker', ['exec', CONTAINER, 'cat', `${WFLOW}/blackbox/pending/${id}/meta.json`], { encoding: 'utf8' }));
}

function ticketFiles() {
  return execFileSync('docker', ['exec', CONTAINER, 'sh', '-c', `ls ${WFLOW}/blackbox/tickets 2>/dev/null || true`], { encoding: 'utf8' })
    .split('\n').filter(Boolean);
}

async function setup(context, extensionId) {
  const joget = await context.newPage();
  await loginJoget(joget);
  const me = await api(joget, 'me');
  test.skip(!me.options?.arm, 'needs "Allow recordings that start before login" on and a user in Who can upload');
  const [worker] = context.serviceWorkers().filter((w) => w.url().includes(extensionId));
  await worker.evaluate((u) => chrome.storage.local.set({ uploadUrl: u }), UPLOAD_URL);
  return { joget, worker };
}

const tabOn = (worker) => worker.evaluate((o) => chrome.tabs.query({ url: o + '/*' }).then((t) => t[0].id), new URL(JOGET).origin);

const post = (worker, action, id, headers = {}, params = {}) => worker.evaluate(async ({ u, action, id, headers, params }) => {
  const r = await fetch(u + '?' + new URLSearchParams({ action, id, ...params }), { method: 'POST', credentials: 'include', headers });
  return { status: r.status, body: await r.json().catch(() => null) };
}, { u: UPLOAD_URL, action, id, headers, params });

test('a recording prepared while logged in captures the server side of a logged-out start and login', async ({ context, extensionId }) => {
  const { joget, worker } = await setup(context, extensionId);

  const popup = await context.newPage();
  await popup.goto(`chrome-extension://${extensionId}/popup.html`);
  await expect(popup.locator('#armLink')).toBeVisible();
  await popup.click('#armLink');
  await expect(popup.locator('#armNote')).toContainText('Prepared until');
  expect(ticketFiles().length, 'one ticket on the server').toBeGreaterThan(0);
  await popup.close();

  await context.clearCookies();
  await joget.goto(`${JOGET}/web/login`);
  expect((await api(joget, 'me')).error, 'logged out').toBeTruthy();

  const capture = await worker.evaluate((tabId) => startServerCapture(tabId), await tabOn(worker));
  expect(capture.status, capture.message).toBe('on');
  expect(capture.captureSecret, 'secret for ping/stop').toMatch(/^[A-Za-z0-9_-]{43}$/);
  expect(await worker.evaluate(() => getArmed()), 'ticket used up in the extension').toBeNull();

  const meta = captureMeta(capture.id);
  expect(meta.owner).toBe(process.env.BB_USER || 'admin');
  expect(meta.armed).toBe(true);
  expect(meta.secretHash).toMatch(/^[0-9a-f]{64}$/);
  expect(JSON.stringify(meta)).not.toContain(capture.captureSecret);

  await joget.reload();
  const ping = await post(worker, 'recording-ping', capture.id, { 'X-BlackBox-Capture-Secret': capture.captureSecret });
  expect(ping.status).toBe(200);
  expect(ping.body.state).toBe('on');
  expect((await post(worker, 'recording-ping', capture.id)).status, 'no session, no secret').toBe(401);
  expect((await post(worker, 'recording-ping', capture.id, { 'X-BlackBox-Capture-Secret': 'x'.repeat(43) })).status).toBe(404);

  await loginJoget(joget);
  await worker.evaluate((c) => stopServerCapture(c), capture);
  expect(captureMeta(capture.id).stoppedAt, 'stopped with the secret').toBeGreaterThan(0);

  const id = 'e2e-armed-' + Date.now();
  const page = await context.newPage();
  await page.goto(`chrome-extension://${extensionId}/recordings.html`);
  await page.evaluate(async ({ id, key, capture }) => {
    const report = buildReport({ startedAt: Date.now() - 5000, tab: { url: location.href, title: id }, [key]: capture }, [], [], [], Date.now(), 'user');
    await idbAddRecording({ id, savedAt: new Date().toISOString(), video: null, report, uploaded: null });
  }, { id, key: CAPTURE_KEY, capture });
  const session = await page.evaluate(async (id) => (await idbGet(id)).report.recording, id);
  expect(JSON.stringify(session), 'secret never in session.json').not.toContain(capture.captureSecret);

  await page.goto(`chrome-extension://${extensionId}/viewer.html?id=${id}`);
  expect(await page.evaluate(async (id) => Actions.uploadNow((await Store.allRows()).find((r) => r.id === id)), id)).toBe(true);
  const rec = await uploaded(joget, id);
  try {
    expect(rec.serverCapture.status).toBe('attached');
    const log = await api(joget, 'systemlog', { id: rec.id });
    const urls = log.entries.filter((e) => e.kind === 'request').map((e) => e.method + ' ' + e.url);
    expect(urls.some((u) => /GET .*\/web\/login/.test(u)), 'the logged-out login page: ' + urls.join(', ')).toBe(true);
    expect(urls.some((u) => /POST .*j_spring_security_check/.test(u)), 'the login POST').toBe(true);
    expect(captureMeta(capture.id).secretHash, 'secret dropped at upload').toBeUndefined();
  } finally {
    await api(joget, 'delete', { id: rec.id }, 'POST');
  }
});

test('a ticket works once, only from the extension, and a web page cannot use one', async ({ context, extensionId }) => {
  const { joget, worker } = await setup(context, extensionId);
  const armed = await worker.evaluate(() => arm().then(() => chrome.storage.session.get('armedTicket')).then((s) => s.armedTicket));
  const auth = { Authorization: 'BlackBox-Ticket ' + armed.ticket };

  const fromPage = await joget.evaluate(async ({ u, auth }) => {
    const r = await fetch(u + '?' + new URLSearchParams({ action: 'recording-start', id: crypto.randomUUID() }), { method: 'POST', headers: auth });
    return r.status;
  }, { u: UPLOAD_URL, auth });
  expect(fromPage, 'web page Origin').toBe(403);

  await context.clearCookies();
  const id1 = await worker.evaluate(() => crypto.randomUUID());
  const first = await post(worker, 'recording-start', id1, auth);
  expect(first.status).toBe(200);
  const second = await post(worker, 'recording-start', await worker.evaluate(() => crypto.randomUUID()), auth);
  expect(second.status, 'used up').toBe(401);
  expect((await post(worker, 'recording-arm', '')).status, 'arming needs a login').toBe(401);
  expect((await post(worker, 'recording-stop', id1, { 'X-BlackBox-Capture-Secret': first.body.captureSecret })).status).toBe(200);
  await worker.evaluate(() => chrome.storage.session.remove('armedTicket'));
});
