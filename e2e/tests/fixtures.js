// A Chromium with the extension loaded unpacked, in its own temporary profile (your Chrome is not touched).
// The extension is copied to a temporary folder and given host access to the Joget server in its manifest, so
// uploads need no permission prompt (Playwright can't click Chrome's own dialogs).
const { test: base, chromium, expect } = require('@playwright/test');
const fs = require('fs');
const os = require('os');
const path = require('path');

const JOGET = process.env.BB_JOGET || 'http://localhost:3128/jw';
const USER = process.env.BB_USER || 'admin';
const PASSWORD = process.env.BB_PASSWORD || USER; // local test servers: the password is the username

const test = base.extend({
  // eslint-disable-next-line no-empty-pattern
  context: async ({}, use) => {
    const ext = fs.mkdtempSync(path.join(os.tmpdir(), 'bb-ext-'));
    fs.cpSync(path.join(__dirname, '..', '..', 'chrome-extension'), ext, { recursive: true });
    const manifest = JSON.parse(fs.readFileSync(path.join(ext, 'manifest.json'), 'utf8'));
    manifest.host_permissions = [new URL(JOGET).origin + '/*'];
    fs.writeFileSync(path.join(ext, 'manifest.json'), JSON.stringify(manifest, null, 2));
    const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'bb-profile-'));
    const context = await chromium.launchPersistentContext(profile, {
      channel: 'chromium', // the full Chromium: headless mode that runs extensions
      headless: !process.env.BB_HEADED,
      args: [`--disable-extensions-except=${ext}`, `--load-extension=${ext}`],
    });
    await use(context);
    await context.close();
    fs.rmSync(profile, { recursive: true, force: true });
    fs.rmSync(ext, { recursive: true, force: true });
  },
  extensionId: async ({ context }, use) => {
    let [worker] = context.serviceWorkers();
    if (!worker) worker = await context.waitForEvent('serviceworker');
    await use(new URL(worker.url()).host);
  },
});

/** Logs in to Joget in this browser (the extension uploads with the browser's cookies). */
async function loginJoget(page) {
  await page.goto(`${JOGET}/web/login`);
  await page.fill('#j_username', USER);
  await page.fill('#j_password', PASSWORD);
  await Promise.all([page.waitForNavigation(), page.press('#j_password', 'Enter')]);
}

/** Calls the BlackBox API with the browser's Joget session. POSTs send the page's CSRF token. */
async function api(page, action, params = {}, method = 'GET') {
  return page.evaluate(async ({ base, action, params, method }) => {
    const headers = {};
    if (method === 'POST') {
      const t = await (await fetch(`${base}/web/json/directory/user/csrfToken`)).json();
      headers[t.tokenName] = t.tokenValue;
    }
    const r = await fetch(`${base}/api/blackbox?` + new URLSearchParams({ action, ...params }), { method, headers });
    return r.json();
  }, { base: JOGET, action, params, method });
}

module.exports = { test, expect, JOGET, loginJoget, api };
