// The extension's Recordings page also lists the user's own recordings on the Upload URL's server that this browser
// doesn't list: the "other computer" case. Each test's browser profile starts empty, like a new computer.
const { test, expect, JOGET, loginJoget, api, report } = require('./fixtures');

/** Uploads a session.json straight to the server (no video), as the logged-in user; returns the server's meta. */
async function uploadDirect(page, title) {
  return page.evaluate(async ({ base, title, body }) => {
    const t = await (await fetch(`${base}/web/json/directory/user/csrfToken`)).json();
    const r = await fetch(`${base}/api/blackbox?` + new URLSearchParams({ action: 'create', title }), {
      method: 'POST', headers: { [t.tokenName]: t.tokenValue, 'Content-Type': 'application/json' }, body: JSON.stringify(body),
    });
    return r.json();
  }, { base: JOGET, title, body: report(Date.now(), 'e2e server list page') });
}

test('lists server recordings this browser never had, once, and says when not logged in', async ({ context, extensionId }) => {
  const page = await context.newPage();
  await loginJoget(page);
  const title = 'e2e server list ' + Date.now();
  const meta = await uploadDirect(page, title);
  expect(meta.id).toBeTruthy();
  try {
    const list = await api(page, 'list');
    const row = list.recordings.find((r) => r.id === meta.id);
    expect(row.url).toMatch(/\/blackbox\/view\?id=/);

    await page.goto(`chrome-extension://${extensionId}/recordings.html`);
    await page.evaluate((uploadUrl) => chrome.storage.local.set({ uploadUrl }), `${JOGET}/api/blackbox`);
    await page.reload();
    const link = page.locator('a.rec-title', { hasText: title });
    await expect(link).toHaveCount(1);
    expect(await link.getAttribute('href')).toBe(new URL(row.url, JOGET).href);
    const tr = page.locator('#libraryRows tr', { has: link });
    await expect(tr.locator('.badge', { hasText: 'Not in this browser' })).toBeVisible();
    await expect(page.locator('#folderInfo')).toContainText('only on the server');

    // Ticked, it takes no batch action
    await tr.locator('td.sel input').check();
    await expect(page.locator('#batchUpload')).toBeDisabled();
    await expect(page.locator('#batchZip')).toBeDisabled();
    await expect(page.locator('#batchDelete')).toBeDisabled();
    await tr.locator('td.sel input').uncheck();

    // Uploaded from this browser (upload history): listed once, as history
    await page.evaluate(({ id, url }) => idbAddHistory('local-' + id, { recording: { startedAt: new Date().toISOString(), tab: { title: 'local copy' } } },
      { id, url, at: new Date().toISOString() }), { id: meta.id, url: new URL(row.url, JOGET).href });
    await page.reload();
    await expect(page.locator('a.rec-title', { hasText: title })).toHaveCount(0);
    await expect(page.locator('a.rec-title', { hasText: 'local copy' })).toHaveCount(1);
    await page.evaluate((id) => idbHistoryDelete('local-' + id), meta.id);

    // Logged out: a note with a link to the server's recordings page, no server rows
    await context.clearCookies();
    await page.reload();
    await expect(page.locator('#serverMsg')).toContainText('Log in to');
    expect(await page.locator('#serverMsg a').getAttribute('href')).toBe(`${JOGET}/blackbox/`);
    await expect(page.locator('a.rec-title', { hasText: title })).toHaveCount(0);
  } finally {
    await loginJoget(page);
    await api(page, 'delete', { id: meta.id }, 'POST');
  }
});
