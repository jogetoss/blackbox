const { test, expect, JOGET, loginJoget, api } = require('./fixtures');

async function record(context, extensionId, joget, quick) {
  const [sw] = context.serviceWorkers().filter((w) => w.url().includes(extensionId));
  await sw.evaluate(({ u, s }) => chrome.storage.local.set({ uploadUrl: u, recordPageAudio: false, quick: s }),
    { u: `${JOGET}/api/blackbox`, s: quick });
  const tabId = await sw.evaluate((o) => chrome.tabs.query({ url: o + '/*' })
    .then((t) => t.find((x) => !x.url.includes('/web/login'))?.id), new URL(JOGET).origin);
  await sw.evaluate((t) => start(t, 'screen').then(() => 'ok'), tabId);
  await expect.poll(() => sw.evaluate(() => getState().then((r) => r?.serverCapture?.status || 'none')), { timeout: 20_000 }).toBe('on');
  await joget.reload();
  await new Promise((r) => setTimeout(r, 3000));
  const stopped = await sw.evaluate(() => stop('user'));
  let mark;
  await expect.poll(async () => (mark = await sw.evaluate((id) => idbGet(id)
    .then((r) => r?.uploaded || (r?.uploadProblem ? { problem: r.uploadProblem } : null)), stopped.id)), { timeout: 60_000 }).toBeTruthy();
  expect(mark.problem).toBeUndefined();
  return mark.id;
}

test('System Info secrets and the license system key / MAC address are masked unless their own box is ticked', async ({ context, extensionId }) => {
  test.setTimeout(180_000);
  const joget = await context.newPage();
  await loginJoget(joget);
  const ids = [];
  const show = (name, si) => console.log(`${name}: wflow.systemkey=${si.systemProperties?.['wflow.systemkey']}`
    + ` platform.systemKey=${si.license?.['platform.systemKey']} platform.net.address=${si.license?.['platform.net.address']}`);
  try {
    const me = await api(joget, 'me');
    expect(me.options.systemSecrets, 'offered to an admin').toBe(true);
    expect(me.options.license, 'offered to an admin').toBe(true);

    const runs = { none: {}, info: { systemSecrets: true }, license: { license: true } };
    const si = {};
    for (const [name, quick] of Object.entries(runs)) {
      const id = await record(context, extensionId, joget, quick);
      ids.push(id);
      si[name] = await api(joget, 'serverinfo', { id });
      show(`${name} ${id}`, si[name]);
    }
    expect([si.none.secretsMasked, si.info.secretsMasked, si.license.secretsMasked]).toEqual([true, false, true]);
    expect([si.none.licenseMasked, si.info.licenseMasked, si.license.licenseMasked]).toEqual([true, true, false]);
    if (si.none.license?.['platform.systemKey'] !== undefined) {
      const sig = si.license.license['platform.license.signature'];
      if (sig) {
        expect(sig, 'the signature unmasked with the license box').not.toBe('****');
        expect(si.none.license['platform.license.signature']).toBe('****');
        expect(si.info.license['platform.license.signature']).toBe('****');
      }
      for (const k of ['platform.systemKey', 'platform.net.address']) {
        expect(si.none.license[k], k).toBe('****');
        expect(si.info.license[k], `${k}: the System Info box doesn't unmask the license`).toBe('****');
        expect(si.license.license[k], k).not.toBe('****');
      }
    }
  } finally {
    if (process.env.BB_CLEANUP) for (const id of ids) await api(joget, 'delete', { id }, 'POST');
  }
});
