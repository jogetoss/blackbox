const fs = require('fs');
const path = require('path');
const { test, expect, EXTENSION, INCOGNITO_USER, WHO_AM_I, loginJoget, openIncognito } = require('./fixtures');

test('incognito uploads with its own login and keeps its own recording state', async ({ context, extensionId, profile }) => {
  test.skip(JSON.parse(fs.readFileSync(path.join(EXTENSION, 'manifest.json'), 'utf8')).incognito !== 'split',
    'an extension from before "incognito": "split" (BB_EXTENSION)');
  test.setTimeout(90_000);
  const page = await context.newPage();
  await loginJoget(page);
  const dt = await openIncognito(page, extensionId, profile, [`chrome-extension://${extensionId}/popup.html`]);
  try {
    await expect(page.locator('#incognitoNote')).toBeHidden();
    const w = await dt.workers();
    expect(await dt.evaluate(w.normal.targetId, WHO_AM_I)).toBe('admin');
    await expect.poll(() => dt.evaluate(w.incognito.targetId, WHO_AM_I)).toBe(INCOGNITO_USER);

    const [popup] = await dt.find('page', `chrome-extension://${extensionId}/popup.html`);
    expect(popup.browserContextId).toBe(dt.incognito);
    await expect.poll(() => dt.evaluate(popup.targetId, `!document.getElementById('incognitoNote').hidden`)).toBe(true);

    expect(await dt.evaluate(w.normal.targetId, `(async () => {
      await setState({ tabId: 1, windowId: 1, startedAt: 1 }); await showRecordingBadge({ status: 'on' }); return [REC_KEY, CAPTURE_RULE];
    })()`)).toEqual(['rec', 1]);
    expect(await dt.evaluate(w.incognito.targetId, `(async () => {
      const seen = await getState(); await setState({ tabId: 2, windowId: 2 }); await clearBadge(); return [REC_KEY, CAPTURE_RULE, seen];
    })()`)).toEqual(['recIncognito', 2, null]);
    expect(await dt.evaluate(w.normal.targetId, `(async () => [(await getState()).tabId, await chrome.action.getBadgeText({})])()`))
      .toEqual([1, 'REC']);
  } finally {
    dt.close();
  }
});
