// Popup quick settings and the app export at recording stop: the choices made before recording reach the server
// at stop (recording-stop) or, without a system log capture, at the upload, also when nobody clicks Send.
const { test, expect, JOGET, loginJoget, api } = require('./fixtures');

const ASSIGNMENT = `${JOGET}/web/client/app/iService/2/assignment/82809_70801_iService_supportRequest_requestAcceptByDepartment`;

// A recording that opened an iService assignment (j8213's test data); no video
function report(now, title, recording = {}) {
  return {
    tool: 'BlackBox e2e',
    recording: {
      startedAt: new Date(now - 8000).toISOString(),
      stoppedAt: new Date(now).toISOString(),
      durationMs: 8000,
      tab: { title, url: `${JOGET}/web/userview/iService/v/_/req` },
      ...recording,
    },
    summary: { console: 0, errors: 0, requests: 1, failedRequests: 0 },
    console: [],
    network: [{ t: 1000, method: 'GET', status: 200, type: 'Document', url: ASSIGNMENT }],
    navigations: [],
  };
}

// The extension's settings and one kept recording, written with its own storage code
async function keep(page, extensionId, id, rep) {
  await page.goto(`chrome-extension://${extensionId}/recordings.html`);
  await page.evaluate(async ({ id, uploadUrl, report }) => {
    await chrome.storage.local.set({ uploadUrl });
    await idbAddRecording({ id, savedAt: new Date().toISOString(), video: null, report, uploaded: null });
  }, { id, uploadUrl: `${JOGET}/api/blackbox`, report: rep });
}

// What the recorder does: start a capture, then stop it with the recording's requests (stopOnServer)
async function captureAndStop(page, params) {
  return page.evaluate(async ({ uploadUrl, url, params }) => {
    const id = crypto.randomUUID();
    const post = (action, extra, body) => fetch(uploadUrl + '?' + new URLSearchParams({ action, id, ...extra }),
      { method: 'POST', credentials: 'include', headers: { 'Content-Type': 'application/json' }, body });
    const start = await post('recording-start', {});
    if (!start.ok) throw new Error('recording-start: HTTP ' + start.status + ' ' + await start.text());
    const body = JSON.stringify({ network: [{ t: 1000, url, type: 'Document', status: 200 }], console: [] });
    const stop = await (await post('recording-stop', params, body)).json();
    return { id, stop };
  }, { uploadUrl: `${JOGET}/api/blackbox`, url: ASSIGNMENT, params });
}

async function uploaded(joget, title) {
  const rec = (await api(joget, 'list')).recordings.find((r) => r.title === title);
  expect(rec, 'uploaded recording in the list').toBeTruthy();
  return rec;
}

test('popup shows the server options and keeps the choices', async ({ context, extensionId }) => {
  const joget = await context.newPage();
  await loginJoget(joget);
  await joget.waitForLoadState('networkidle'); // the first login may still be redirecting
  const me = await api(joget, 'me');
  const page = await context.newPage();
  await page.goto(`chrome-extension://${extensionId}/recordings.html`);
  await page.evaluate((uploadUrl) => chrome.storage.local.set({ uploadUrl }), `${JOGET}/api/blackbox`);
  await page.goto(`chrome-extension://${extensionId}/popup.html`);

  await expect(page.locator('#qPage')).toBeChecked();
  await expect(page.locator('#qAppsRow')).toBeVisible({ visible: !!me.options.apps });
  await expect(page.locator('#qSampleRow')).toBeVisible({ visible: !!me.options.sample });
  await expect(page.locator('#qMaskRow')).toBeVisible({ visible: !!me.options.real });
  if (me.options.real) {
    await expect(page.locator('#qMask')).toBeChecked(); // masked unless unticked
    await page.uncheck('#qMask');
    await expect(page.locator('#qMaskWarn')).toBeVisible();
    expect((await page.evaluate(() => Quick.get())).real).toBe(true);
    await page.check('#qMask');
  }
  await expect(page.locator('#qAllServerRow')).toBeVisible({ visible: !!me.options.allServer });
  await expect(page.locator('#qGlowrootRow')).toBeVisible({ visible: !!me.options.glowrootData });

  await page.uncheck('#qApps');
  await expect(page.locator('#qSample')).toBeDisabled();
  await page.uncheck('#qMic').catch(() => {});
  const kept = await page.evaluate(() => Quick.get());
  expect(kept.apps).toBe(false);
  expect(await page.evaluate(() => Quick.options({ ...Quick.DEFAULTS, apps: false }))).toEqual({ apps: false, sampleData: 'masked' });
  // the server's options are remembered for the next time the popup opens
  expect(await page.evaluate(async () => (await chrome.storage.local.get('quickOffer')).quickOffer)).toEqual(me.options);
});

test('stop exports the apps; the upload keeps that export', async ({ context, extensionId }) => {
  const joget = await context.newPage();
  await loginJoget(joget);
  const page = await context.newPage();
  await page.goto(`chrome-extension://${extensionId}/recordings.html`);
  const { id: logId, stop } = await captureAndStop(page, { sampleData: 'masked' });
  expect(stop.appExport.state).toBe('done');
  expect(stop.appExport.sampleData).toBe('masked');
  expect(stop.appExport.saved.map((a) => a.id)).toContain('iService');

  const id = 'e2e-stop-' + Date.now();
  await keep(page, extensionId, id, report(Date.now(), id, {
    systemLog: { status: 'on', id: logId, appExport: { state: 'done', sampleData: 'masked', apps: stop.appExport.saved.length } },
    options: { apps: true, sampleData: 'masked' },
  }));
  await page.goto(`chrome-extension://${extensionId}/viewer.html?id=${id}`);
  await page.evaluate(async (id) => Actions.showUpload((await Store.allRows()).find((r) => r.id === id)), id);
  await expect(page.locator('#upAtStop')).toContainText('exported when the recording stopped');
  await expect(page.locator('#upSampleRow')).toBeHidden();

  const before = Date.now();
  const create = page.waitForResponse((r) => r.url().includes('action=create'), { timeout: 60_000 });
  await page.click('#upSend');
  expect((await create).status()).toBe(200);
  const rec = await uploaded(joget, id);
  try {
    expect(rec.systemLog.status).toBe('attached');
    expect(rec.apps.map((a) => a.id)).toContain('iService');
    const apps = await api(joget, 'apps', { id: rec.id });
    const iService = apps.apps.find((a) => a.id === 'iService');
    expect(iService.state).toBe('saved');
    expect(iService.sample.mode).toBe('masked');
    expect(iService.processes.length).toBeGreaterThan(0);
    expect(apps.exportedAt, 'exported at the stop, not at the upload').toBeLessThan(before);
  } finally {
    await api(joget, 'delete', { id: rec.id }, 'POST');
  }
});

test('Mask data unticked: the stop export keeps real values', async ({ context, extensionId }) => {
  const joget = await context.newPage();
  await loginJoget(joget);
  await joget.waitForLoadState('networkidle');
  test.skip(!(await api(joget, 'me')).options.real, 'this user may not keep real values');
  const page = await context.newPage();
  await page.goto(`chrome-extension://${extensionId}/recordings.html`);
  const { stop } = await captureAndStop(page, { sampleData: 'real' });
  expect(stop.appExport.state).toBe('done');
  expect(stop.appExport.sampleData).toBe('real');
});

test('apps turned off at stop: the upload exports nothing', async ({ context, extensionId }) => {
  const joget = await context.newPage();
  await loginJoget(joget);
  const page = await context.newPage();
  await page.goto(`chrome-extension://${extensionId}/recordings.html`);
  const { id: logId, stop } = await captureAndStop(page, { apps: 'false' });
  expect(stop.appExport.state).toBe('off');

  const id = 'e2e-off-' + Date.now();
  await keep(page, extensionId, id, report(Date.now(), id, {
    systemLog: { status: 'on', id: logId, appExport: { state: 'off' } }, options: { apps: false, sampleData: 'masked' },
  }));
  await page.goto(`chrome-extension://${extensionId}/viewer.html?id=${id}`);
  // The auto-upload after a recording stops: no click on Send
  const ok = await page.evaluate(async (id) => Actions.uploadNow((await Store.allRows()).find((r) => r.id === id)), id);
  expect(ok).toBe(true);
  const rec = await uploaded(joget, id);
  try {
    expect(rec.apps).toBeUndefined();
  } finally {
    await api(joget, 'delete', { id: rec.id }, 'POST');
  }
});

test('no capture: the auto-upload sends the choices saved with the recording', async ({ context, extensionId }) => {
  const joget = await context.newPage();
  await loginJoget(joget);
  const page = await context.newPage();
  const id = 'e2e-auto-' + Date.now();
  await keep(page, extensionId, id, report(Date.now(), id, { options: { apps: true, sampleData: 'none' } }));
  await page.goto(`chrome-extension://${extensionId}/viewer.html?id=${id}`);
  const create = page.waitForResponse((r) => r.url().includes('action=create'), { timeout: 60_000 });
  const ok = page.evaluate(async (id) => Actions.uploadNow((await Store.allRows()).find((r) => r.id === id)), id);
  expect(new URL((await create).url()).searchParams.get('sampleData')).toBe('none');
  expect(await ok).toBe(true);
  const rec = await uploaded(joget, id);
  try {
    const iService = (await api(joget, 'apps', { id: rec.id })).apps.find((a) => a.id === 'iService');
    expect(iService.state).toBe('saved');
    expect(iService.sample).toBeUndefined();
  } finally {
    await api(joget, 'delete', { id: rec.id }, 'POST');
  }
});
