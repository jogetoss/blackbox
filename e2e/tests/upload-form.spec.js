// The upload form's sample data choice: offered as the server allows, sent with the upload, used by the server.
const { test, expect, JOGET, loginJoget, api } = require('./fixtures');

// A recording that opened an iService assignment (j8213's test data); no video
function report(now, title) {
  return {
    tool: 'BlackBox e2e',
    recording: {
      startedAt: new Date(now - 8000).toISOString(),
      stoppedAt: new Date(now).toISOString(),
      durationMs: 8000,
      tab: { title, url: `${JOGET}/web/userview/iService/v/_/req` },
    },
    summary: { console: 0, errors: 0, requests: 1, failedRequests: 0 },
    console: [],
    network: [{ t: 1000, method: 'GET', status: 200, type: 'Document',
      url: `${JOGET}/web/client/app/iService/2/assignment/82809_70801_iService_supportRequest_requestAcceptByDepartment` }],
    navigations: [],
  };
}

test('upload form offers masked sample data and the server builds it', async ({ context, extensionId }) => {
  const joget = await context.newPage();
  await loginJoget(joget);

  // The extension's settings and one kept recording, written with its own storage code
  const page = await context.newPage();
  await page.goto(`chrome-extension://${extensionId}/recordings.html`);
  const id = 'e2e-' + Date.now();
  await page.evaluate(async ({ id, uploadUrl, report }) => {
    await chrome.storage.local.set({ uploadUrl });
    await idbAddRecording({ id, savedAt: new Date().toISOString(), video: null, report, uploaded: null });
  }, { id, uploadUrl: `${JOGET}/api/blackbox`, report: report(Date.now(), id) });

  // The replay page's upload form (what Upload opens once the server check passes)
  await page.goto(`chrome-extension://${extensionId}/viewer.html?id=${id}`);
  await page.evaluate(async (id) => {
    const row = (await Store.allRows()).find((r) => r.id === id);
    Actions.showUpload(row);
  }, id);
  await expect(page.locator('#upSampleRow')).toBeVisible();
  await expect(page.locator('#upSample')).toBeChecked();
  const me = await api(joget, 'me');
  await expect(page.locator('#upRealRow')).toBeVisible({ visible: !!me.sampleData.real });

  const create = page.waitForResponse((r) => r.url().includes('action=create'), { timeout: 30_000 });
  await page.click('#upSend');
  const res = await create;
  expect(new URL(res.url()).searchParams.get('sampleData')).toBe('masked');
  expect(res.status()).toBe(200);

  // The server exported iService with masked sample data and its process
  const list = await api(joget, 'list');
  const rec = list.recordings.find((r) => r.title === id);
  expect(rec, 'uploaded recording in the list').toBeTruthy();
  try {
    const apps = await api(joget, 'apps', { id: rec.id });
    const iService = apps.apps.find((a) => a.id === 'iService');
    expect(iService.state).toBe('saved');
    expect(iService.sample.mode).toBe('masked');
    expect(iService.processes.length).toBeGreaterThan(0);
  } finally {
    await api(joget, 'delete', { id: rec.id }, 'POST');
  }
});

test('unticking sample data sends sampleData=none', async ({ context, extensionId }) => {
  const joget = await context.newPage();
  await loginJoget(joget);
  const page = await context.newPage();
  await page.goto(`chrome-extension://${extensionId}/recordings.html`);
  const id = 'e2e-' + Date.now();
  await page.evaluate(async ({ id, uploadUrl, report }) => {
    await chrome.storage.local.set({ uploadUrl });
    await idbAddRecording({ id, savedAt: new Date().toISOString(), video: null, report, uploaded: null });
  }, { id, uploadUrl: `${JOGET}/api/blackbox`, report: report(Date.now(), id) });
  await page.goto(`chrome-extension://${extensionId}/viewer.html?id=${id}`);
  await page.evaluate(async (id) => Actions.showUpload((await Store.allRows()).find((r) => r.id === id)), id);
  await expect(page.locator('#upSampleRow')).toBeVisible();
  await page.uncheck('#upSample');
  const create = page.waitForResponse((r) => r.url().includes('action=create'), { timeout: 30_000 });
  await page.click('#upSend');
  const res = await create;
  expect(new URL(res.url()).searchParams.get('sampleData')).toBe('none');
  expect(res.status()).toBe(200);
  const rec = (await api(joget, 'list')).recordings.find((r) => r.title === id);
  try {
    const apps = await api(joget, 'apps', { id: rec.id });
    expect(apps.apps.find((a) => a.id === 'iService').sample).toBeUndefined();
  } finally {
    await api(joget, 'delete', { id: rec.id }, 'POST');
  }
});
