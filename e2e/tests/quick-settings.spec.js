// Popup quick settings and the app export at recording stop: the choices made before recording reach the server
// at stop (recording-stop) or, without a server data capture, at the upload, also when nobody clicks Send.
const { test, expect, JOGET, CAPTURE_KEY, loginJoget, api, appsWhenExported, report, keep, captureAndStop, uploaded } = require('./fixtures');

test('popup shows the server options and keeps the choices', async ({ context, extensionId }) => {
  const joget = await context.newPage();
  await loginJoget(joget);
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
    [CAPTURE_KEY]: { status: 'on', id: logId, appExport: { state: 'done', sampleData: 'masked', apps: stop.appExport.saved.length } },
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
    expect(rec.serverCapture.status).toBe('attached');
    expect(rec.systemLog).toBeUndefined();
    expect(rec.apps.map((a) => a.id)).toContain('iService');
    const apps = await appsWhenExported(joget, rec.id);
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
    [CAPTURE_KEY]: { status: 'on', id: logId, appExport: { state: 'off' } }, options: { apps: false, sampleData: 'masked' },
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
    const iService = (await appsWhenExported(joget, rec.id)).apps.find((a) => a.id === 'iService');
    expect(iService.state).toBe('saved');
    expect(iService.sample).toBeUndefined();
  } finally {
    await api(joget, 'delete', { id: rec.id }, 'POST');
  }
});
