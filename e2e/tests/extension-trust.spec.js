// What the extension takes from the upload server and its pages: links (server.js resolve) only to the server itself,
// and the settings and recording messages only from the extension's own pages, not from the theme.js content script.
const { test, expect, JOGET } = require('./fixtures');
const Server = require('../../chrome-extension/server.js');

test('server links: http(s) on the upload server only', () => {
  const ep = 'http://joget.example:8080/jw/api/blackbox';
  expect(Server.resolve(ep, '/jw/blackbox/view?id=1')).toBe('http://joget.example:8080/jw/blackbox/view?id=1');
  expect(Server.resolve(ep, 'https://joget.example:8080/jw/x')).toBe('https://joget.example:8080/jw/x'); // TLS proxy
  for (const bad of ['javascript:alert(1)', 'data:text/html,x', 'https://evil.example/x', '//evil.example/x',
    'http://joget.example:9999/x', 'file:///etc/passwd']) {
    expect(Server.resolve(ep, bad), bad).toBe('');
  }
  expect(Server.resolve(ep, '')).toBe('');
});

test('settings stay out of content scripts; the server pages still get the theme', async ({ context, extensionId }) => {
  const [worker] = context.serviceWorkers();
  await worker.evaluate(async (joget) => chrome.storage.local.set({ uploadUrl: `${joget}/api/blackbox`, theme: 'dark',
    textSize: '1.15' }), JOGET);
  const page = await context.newPage();
  await expect.poll(async () => {
    await page.goto(`${JOGET}/blackbox/`); // the content script is registered a moment after uploadUrl changes
    return page.evaluate(() => document.documentElement.dataset.theme || '');
  }, { timeout: 15000 }).toBe('dark');
  expect(await page.evaluate(() => document.documentElement.style.zoom)).toBe('1.15');

  // The content script's own world: run code there through the DevTools protocol
  const cdp = await context.newCDPSession(page);
  await cdp.send('Runtime.enable');
  const contexts = [];
  cdp.on('Runtime.executionContextCreated', (e) => contexts.push(e.context));
  await page.reload();
  await expect.poll(() => contexts.some((c) => c.origin === `chrome-extension://${extensionId}` || c.name === 'BlackBox')).toBe(true);
  const world = contexts.find((c) => c.auxData?.type === 'isolated' && (c.origin.includes(extensionId) || c.name === 'BlackBox'));
  const inScript = async (expr) => (await cdp.send('Runtime.evaluate', { expression: expr, contextId: world.id,
    awaitPromise: true, returnByValue: true })).result.value;

  expect(await inScript(`chrome.storage.local.get('uploadUrl').then(() => 'read', (e) => 'blocked')`)).toBe('blocked');
  expect(await inScript(`chrome.runtime.sendMessage({ target: 'background', type: 'get-state' })
    .then((r) => r === undefined ? 'ignored' : 'answered', () => 'ignored')`)).toBe('ignored');
  expect(await inScript(`chrome.runtime.sendMessage({ target: 'background', type: 'display' }).then((r) => r.theme)`)).toBe('dark');
});
