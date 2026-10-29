// Incognito ("incognito": "split" in the manifest): the incognito copy of the extension uses the incognito window's
// Joget login, and keeps its own recording state and capture rule. Playwright can't see incognito windows, so the
// test talks to them over the browser's DevTools port.
const fs = require('fs');
const path = require('path');
const { test, expect, JOGET, EXTENSION, loginJoget } = require('./fixtures');

const INCOGNITO_USER = process.env.BB_INCOGNITO_USER || 'cat'; // a non-admin user who can log in (password = username)

/** A browser-level DevTools connection: targets() and evaluate(targetId, expression). */
async function devtools(profile) {
  const [port, wsPath] = fs.readFileSync(path.join(profile, 'DevToolsActivePort'), 'utf8').trim().split('\n');
  const ws = new WebSocket(`ws://127.0.0.1:${port}${wsPath}`);
  await new Promise((ok, fail) => { ws.onopen = ok; ws.onerror = fail; });
  let seq = 0;
  const waiting = new Map();
  ws.onmessage = (e) => {
    const d = JSON.parse(e.data);
    if (waiting.has(d.id)) { waiting.get(d.id)(d); waiting.delete(d.id); }
  };
  const send = (method, params = {}, sessionId) => new Promise((ok) => {
    const id = ++seq;
    waiting.set(id, ok);
    ws.send(JSON.stringify({ id, method, params, sessionId }));
  });
  return {
    targets: async () => (await send('Target.getTargets')).result.targetInfos,
    async evaluate(targetId, expression) {
      const { result: { sessionId } } = await send('Target.attachToTarget', { targetId, flatten: true });
      const r = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true }, sessionId);
      if (r.result.exceptionDetails) throw new Error(r.result.exceptionDetails.exception?.description || r.result.exceptionDetails.text);
      return r.result.result.value;
    },
    close: () => ws.close(),
  };
}

test('incognito uploads with its own login and keeps its own recording state', async ({ context, extensionId, profile }) => {
  test.skip(JSON.parse(fs.readFileSync(path.join(EXTENSION, 'manifest.json'), 'utf8')).incognito !== 'split',
    'an extension from before "incognito": "split" (BB_EXTENSION)');
  test.setTimeout(90_000);
  const page = await context.newPage();
  await loginJoget(page); // the normal window: admin
  // Allow in Incognito, as on chrome://extensions. It reloads the extension, which Chromium then disables unless
  // Developer mode is on.
  await page.goto('chrome://extensions');
  await page.evaluate((id) => new Promise((ok) => chrome.developerPrivate.updateProfileConfiguration({ inDeveloperMode: true },
    () => chrome.developerPrivate.updateExtensionConfiguration({ extensionId: id, incognitoAccess: true }, ok))), extensionId);
  await expect.poll(() => page.evaluate((id) => new Promise((ok) => chrome.developerPrivate.getExtensionInfo(id, (e) => ok(e.state))), extensionId))
    .toBe('ENABLED');
  await page.goto(`chrome-extension://${extensionId}/recordings.html`);
  await expect(page.locator('#incognitoNote')).toBeHidden();
  await page.evaluate(async ({ jw, ext }) => {
    await chrome.storage.local.set({ uploadUrl: jw + '/api/blackbox' });
    await chrome.windows.create({ incognito: true, url: jw + '/web/login' });
    await chrome.windows.create({ incognito: true, url: `chrome-extension://${ext}/popup.html` });
  }, { jw: JOGET, ext: extensionId });

  const dt = await devtools(profile);
  try {
    const find = async (type, part) => (await dt.targets()).filter((t) => t.type === type && t.url.includes(part));
    let login;
    await expect.poll(async () => (login = (await find('page', '/web/login'))[0])).toBeTruthy();
    const incognito = login.browserContextId; // the normal window's pages are in Playwright's context
    await expect.poll(() => dt.evaluate(login.targetId, `document.readyState === 'complete' && !!document.querySelector('#j_password')`)).toBe(true);
    await dt.evaluate(login.targetId, `(() => {
      document.querySelector('#j_username').value = ${JSON.stringify(INCOGNITO_USER)};
      document.querySelector('#j_password').value = ${JSON.stringify(INCOGNITO_USER)};
      document.querySelector('#j_password').form.querySelector('[type=submit]').click();
    })()`);

    const workers = async () => {
      const all = await find('service_worker', `chrome-extension://${extensionId}/`);
      return { normal: all.find((t) => t.browserContextId !== incognito), incognito: all.find((t) => t.browserContextId === incognito) };
    };
    await expect.poll(async () => !!(await workers()).incognito).toBe(true);
    const w = await workers();
    const whoAmI = `fetch(${JSON.stringify(JOGET + '/api/blackbox?action=me')}, { credentials: 'include' })
      .then((r) => r.ok ? r.json().then((me) => me.user.username) : 'HTTP ' + r.status)`;
    expect(await dt.evaluate(w.normal.targetId, whoAmI)).toBe('admin');
    await expect.poll(() => dt.evaluate(w.incognito.targetId, whoAmI)).toBe(INCOGNITO_USER);

    // The incognito popup warns that its recordings are lost when incognito closes
    const [popup] = await find('page', `chrome-extension://${extensionId}/popup.html`);
    expect(popup.browserContextId).toBe(incognito);
    await expect.poll(() => dt.evaluate(popup.targetId, `!document.getElementById('incognitoNote').hidden`)).toBe(true);

    // chrome.storage.session, session rules and the badge are shared between the copies: each keeps its own
    expect(await dt.evaluate(w.normal.targetId, `(async () => {
      await setState({ tabId: 1, windowId: 1, startedAt: 1 }); await showRecordingBadge({ status: 'on' }); return [REC_KEY, CAPTURE_RULE];
    })()`)).toEqual(['rec', 1]);
    expect(await dt.evaluate(w.incognito.targetId, `(async () => {
      const seen = await getState(); await setState({ tabId: 2, windowId: 2 }); await clearBadge(); return [REC_KEY, CAPTURE_RULE, seen];
    })()`)).toEqual(['recIncognito', 2, null]);
    expect(await dt.evaluate(w.normal.targetId, `(async () => [(await getState()).tabId, await chrome.action.getBadgeText({})])()`))
      .toEqual([1, 'REC']); // the incognito copy left the normal recording's state and badge alone
  } finally {
    dt.close();
  }
});
