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
// The extension under test: this repo's, or BB_EXTENSION (e.g. an older release, to test this plugin with it)
const EXTENSION = process.env.BB_EXTENSION || path.join(__dirname, '..', '..', 'chrome-extension');
// The extension of plugin 8.2.1 and older keeps the server capture under recording.systemLog; later ones under serverCapture
const LEGACY_EXTENSION = !fs.readFileSync(path.join(EXTENSION, 'report.js'), 'utf8').includes('serverCapture');
const CAPTURE_KEY = LEGACY_EXTENSION ? 'systemLog' : 'serverCapture';

const test = base.extend({
  // eslint-disable-next-line no-empty-pattern
  context: async ({}, use) => {
    const ext = fs.mkdtempSync(path.join(os.tmpdir(), 'bb-ext-'));
    fs.cpSync(EXTENSION, ext, { recursive: true });
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
  await page.waitForLoadState('networkidle'); // the first login may still be redirecting
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

/** The apps of a recording once its app export, which runs in the background on the server, has finished. */
async function appsWhenExported(page, id, timeoutMs = 90_000) {
  const until = Date.now() + timeoutMs;
  for (;;) {
    const meta = await api(page, 'meta', { id });
    if (meta.appExport?.state !== 'exporting') return api(page, 'apps', { id });
    if (Date.now() > until) throw new Error('app export still running after ' + timeoutMs + ' ms');
    await page.waitForTimeout(1000);
  }
}

const ASSIGNMENT = `${JOGET}/web/client/app/iService/2/assignment/82809_70801_iService_supportRequest_requestAcceptByDepartment`;

/** A session.json of a recording that opened an iService assignment (j8213's test data); no video. */
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

/** The extension's settings and one kept recording, written with its own storage code. */
async function keep(page, extensionId, id, rep) {
  await page.goto(`chrome-extension://${extensionId}/recordings.html`);
  await page.evaluate(async ({ id, uploadUrl, report }) => {
    await chrome.storage.local.set({ uploadUrl });
    await idbAddRecording({ id, savedAt: new Date().toISOString(), video: null, report, uploaded: null });
  }, { id, uploadUrl: `${JOGET}/api/blackbox`, report: rep });
}

/** What the recorder does: start a server capture, then stop it with the recording's requests (stopOnServer). */
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

module.exports = {
  test, expect, JOGET, LEGACY_EXTENSION, CAPTURE_KEY, ASSIGNMENT, loginJoget, api, appsWhenExported, report, keep, captureAndStop, uploaded,
};
