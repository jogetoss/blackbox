// checks for a real screen recording in the normal window as BB_USER.
const { test, expect, JOGET, loginJoget, api } = require('./fixtures');

test('a screen recording in the normal window uploads as BB_USER, with its logs and server data', async ({ context }) => {
  test.skip(!process.env.BB_HEADED, 'records the screen: run with BB_HEADED=1');
  test.setTimeout(120_000);
  const joget = await context.newPage();
  await loginJoget(joget);
  let [sw] = context.serviceWorkers();
  if (!sw) sw = await context.waitForEvent('serviceworker');
  const me = (await api(joget, 'me')).user.username;
  let serverId;
  try {
    await sw.evaluate((uploadUrl) => chrome.storage.local.set({ uploadUrl, recordPageAudio: false }), `${JOGET}/api/blackbox`);
    const tabId = await sw.evaluate((origin) => chrome.tabs.query({ url: origin + '/*' })
      .then((t) => t.find((x) => !x.url.includes('/web/login'))?.id), new URL(JOGET).origin);
    expect(tabId).toBeTruthy();

    await sw.evaluate(`start(${tabId}, 'screen').then(() => 'ok')`);
    await expect.poll(() => sw.evaluate(`getState().then((r) => r?.startedAt ? r.serverCapture?.status || 'starting' : 'none')`),
      { timeout: 20_000 }).toBe('on');

    const marker = 'bb-e2e-' + Date.now();
    await joget.evaluate((u) => { console.error(u.marker); return fetch(u.url).then((r) => r.status); },
      { marker, url: `${JOGET}/web/json/workflow/currentUsername?m=${marker}` });
    await new Promise((r) => setTimeout(r, 4000));

    const stopped = await sw.evaluate(`stop('user')`);
    expect(stopped.id).toBeTruthy();
    let mark;
    await expect.poll(async () => (mark = await sw.evaluate(
      `idbGet(${JSON.stringify(stopped.id)}).then((r) => r?.uploaded || (r?.uploadProblem ? { problem: r.uploadProblem } : null))`)),
      { timeout: 60_000 }).toBeTruthy();
    expect(mark.problem).toBeUndefined();
    serverId = mark.id;

    const meta = await api(joget, 'meta', { id: serverId });
    expect(meta.owner, 'uploaded by the logged-in user').toBe(me);
    const session = await api(joget, 'session', { id: serverId });
    expect(JSON.stringify(session.console)).toContain(marker);
    expect(session.network.some((n) => n.url.includes(marker)), 'the marked request in the network log').toBe(true);
    expect(session.recording.stopReason).toBe('user');
    expect(session.recording.capturedSurface).toBe('monitor');
    expect(meta.serverCapture.status).toBe('attached');
    expect(meta.serverCapture.requests).toBeGreaterThan(0);
    const head = await joget.evaluate(async (url) => {
      const r = await fetch(url, { headers: { Range: 'bytes=0-3' } });
      return [r.status, [...new Uint8Array(await r.arrayBuffer())].map((b) => b.toString(16)).join(' ')];
    }, `${JOGET}/api/blackbox?action=video&id=${serverId}`);
    expect(head).toEqual([206, '1a 45 df a3']);
    expect(await sw.evaluate(`chrome.action.getBadgeText({})`)).toBe('');
    console.log(`normal: uploaded ${serverId} as ${meta.owner}, ${meta.serverCapture.requests} server requests`);
  } finally {
    if (serverId) await api(joget, 'delete', { id: serverId }, 'POST');
  }
});
