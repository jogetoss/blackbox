const fs = require('fs');
const path = require('path');
const { test, expect, JOGET, EXTENSION, INCOGNITO_USER, loginJoget, api, openIncognito } = require('./fixtures');

test('a screen recording in incognito uploads as the incognito user, with its logs and server data', async ({ context, extensionId, profile }) => {
  test.skip(!process.env.BB_HEADED, 'records the screen: run with BB_HEADED=1');
  test.skip(JSON.parse(fs.readFileSync(path.join(EXTENSION, 'manifest.json'), 'utf8')).incognito !== 'split',
    'an extension from before "incognito": "split" (BB_EXTENSION)');
  test.setTimeout(120_000);
  const joget = await context.newPage();
  await loginJoget(joget);
  const dt = await openIncognito(joget, extensionId, profile);
  let serverId;
  try {
    await joget.evaluate(() => chrome.storage.local.set({ recordPageAudio: false }));
    const w = await dt.workers();
    let tabId;
    await expect.poll(async () => (tabId = await dt.evaluate(w.incognito.targetId,
      `chrome.tabs.query({ url: ${JSON.stringify(new URL(JOGET).origin + '/*')} }).then((t) => t.find((x) => !x.url.includes('/web/login'))?.id)`))).toBeTruthy();

    await dt.evaluate(w.incognito.targetId, `start(${tabId}, 'screen').then(() => 'ok')`);
    await expect.poll(() => dt.evaluate(w.incognito.targetId, `getState().then((r) => r?.startedAt ? r.serverCapture?.status || 'starting' : 'none')`),
      { timeout: 20_000 }).toBe('on');
    expect(await dt.evaluate(w.normal.targetId, `getState()`)).toBeNull();

    const marker = 'bb-e2e-' + Date.now();
    await dt.evaluate(dt.loginPage, `console.error(${JSON.stringify(marker)});
      fetch(${JSON.stringify(JOGET + '/web/json/workflow/currentUsername?' + 'm=')} + ${JSON.stringify(marker)}).then((r) => r.status)`);
    await new Promise((r) => setTimeout(r, 4000));

    const stopped = await dt.evaluate(w.incognito.targetId, `stop('user')`);
    expect(stopped.id).toBeTruthy();
    let mark;
    await expect.poll(async () => (mark = await dt.evaluate(w.incognito.targetId,
      `idbGet(${JSON.stringify(stopped.id)}).then((r) => r?.uploaded || (r?.uploadProblem ? { problem: r.uploadProblem } : null))`)),
      { timeout: 60_000 }).toBeTruthy();
    expect(mark.problem).toBeUndefined();
    serverId = mark.id;

    const meta = await api(joget, 'meta', { id: serverId });
    expect(meta.owner, 'uploaded by the incognito user').toBe(INCOGNITO_USER);
    const session = await api(joget, 'session', { id: serverId });
    expect(JSON.stringify(session.console)).toContain(marker);
    expect(session.network.some((n) => n.url.includes(marker)), 'the marked request in the network log').toBe(true);
    expect(session.recording.stopReason).toBe('user');
    expect(session.recording.capturedSurface, 'the whole screen, picked without the picker').toBe('monitor');
    expect(meta.serverCapture.status).toBe('attached');
    expect(meta.serverCapture.requests).toBeGreaterThan(0);
    const head = await joget.evaluate(async (url) => {
      const r = await fetch(url, { headers: { Range: 'bytes=0-3' } });
      return [r.status, [...new Uint8Array(await r.arrayBuffer())].map((b) => b.toString(16)).join(' ')];
    }, `${JOGET}/api/blackbox?action=video&id=${serverId}`);
    expect(head[0]).toBe(206);
    expect(head[1]).toBe('1a 45 df a3');
    expect(await dt.evaluate(w.normal.targetId, `chrome.action.getBadgeText({})`)).toBe('');
  } finally {
    dt.close();
    if (serverId) await api(joget, 'delete', { id: serverId }, 'POST');
  }
});
