// Tag-matched traces and other users' traces against j8213's own Glowroot: while the recording runs, the recorded tab
// and another session (BB_OTHER_USER, default the same user as BB_USER, in its own browser) make the same slow request
// at the same moment (both wait on a table lock held for 4 s; Glowroot's slow threshold is 2 s). Same user, method,
// path and time: only Glowroot's tag tells them apart. The recording's trace must be its own, and with Include other
// users' traces the other session's trace arrives about 30 s after the stop, marked other, with its user. Needs a
// user who may capture traces and other users' traces, and read the iService list (admin; cat may not).
// Glowroot records a trace's user only when its Servlet plugin's Session user attribute is set: the spec sets it to
// Joget's (Spring Security's) session attribute for its run, through Glowroot's UI API (BB_GLOWROOT_UI), and puts the
// old config back after.
const { execFileSync, spawn } = require('child_process');
const { chromium } = require('@playwright/test');
const { test, expect, JOGET, LEGACY_EXTENSION, loginJoget, api, uploaded } = require('./fixtures');

const DB_CONTAINER = process.env.BB_DB_CONTAINER || 'mariadb';
const DB = process.env.BB_DB || 'jw8';
const OTHER_USER = process.env.BB_OTHER_USER || process.env.BB_USER || 'admin';
const TABLE = 'rvp_sr_req'; // j8213's iService: form srReq
const SLOW_PATH = '/web/json/data/list/iService/list_srReq'; // reads the table
const GLOWROOT_UI = process.env.BB_GLOWROOT_UI || 'http://j8213.local:4001';
const SESSION_USER = 'SPRING_SECURITY_CONTEXT.authentication.name';

test.skip(LEGACY_EXTENSION, 'tests this extension only');

// Glowroot's Servlet plugin config (Joget 8 is javax): set the session user attribute, restore it after
const SERVLET = `${GLOWROOT_UI}/backend/config/plugins?agent-id=&plugin-id=servlet`;
const saveServlet = async (props, version) => {
  const r = await fetch(`${GLOWROOT_UI}/backend/config/plugins?agent-id=`, { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ pluginId: 'servlet', version, properties: props.map(({ name, type, value }) => ({ name, type, value })) }) });
  expect(r.ok, 'Glowroot config saved').toBe(true);
};
let servletBefore = null;
test.beforeAll(async () => {
  servletBefore = await (await fetch(SERVLET)).json();
  await saveServlet(servletBefore.properties.map((p) => (p.name === 'sessionUserAttribute' ? { ...p, value: SESSION_USER } : p)),
    servletBefore.version);
});
test.afterAll(async () => {
  if (!servletBefore) return;
  const now = await (await fetch(SERVLET)).json();
  await saveServlet(servletBefore.properties, now.version);
});

const DB_EXEC = ['exec', '-i', DB_CONTAINER, 'sh', '-c', `mariadb -uroot -p"$MARIADB_ROOT_PASSWORD" -N -B ${DB}`];
const sql = (query) => execFileSync('docker', DB_EXEC, { input: query, encoding: 'utf8' });

/** Holds a WRITE lock on the table for `seconds`; resolves once it is held, with { released }. */
async function lockTable(seconds) {
  const p = spawn('docker', DB_EXEC, { stdio: ['pipe', 'ignore', 'inherit'] });
  p.stdin.end(`LOCK TABLES app_fd_${TABLE} WRITE; SELECT SLEEP(${seconds}); UNLOCK TABLES;`);
  const released = new Promise((ok) => p.on('exit', ok));
  await expect.poll(() => sql("SELECT COUNT(*) FROM information_schema.processlist WHERE info LIKE 'SELECT SLEEP%'").trim(),
    { timeout: 10_000 }).not.toBe('0');
  return { released };
}

/** Another Joget session in its own browser: { page, close }. */
async function otherSession() {
  const browser = await chromium.launch();
  const page = await (await browser.newContext({ ignoreHTTPSErrors: true })).newPage();
  await page.goto(`${JOGET}/web/login`);
  await page.fill('#j_username', OTHER_USER);
  await page.fill('#j_password', process.env.BB_OTHER_PASSWORD || OTHER_USER); // local test servers
  await Promise.all([page.waitForNavigation(), page.press('#j_password', 'Enter')]);
  await page.waitForLoadState('networkidle');
  expect(page.url(), `${OTHER_USER} logged in`).not.toMatch(/login/);
  return { page, close: () => browser.close() };
}

/** A GET from a page: its status and how long it took. */
const load = (page, url) => page.evaluate(async (url) => {
  const t = Date.now();
  const r = await fetch(url);
  return { status: r.status, ms: Date.now() - t };
}, url);

test('the recording keeps its own trace; another user\'s comes apart, marked other', async ({ context, extensionId }) => {
  test.setTimeout(300_000);
  const joget = await context.newPage();
  await loginJoget(joget);
  const me = await api(joget, 'me');
  test.skip(!me.options.traces || !me.options.otherTraces, 'this user may not capture other users\' traces');
  const other = await otherSession();
  let rec;
  try {
    const page = await context.newPage();
    await page.goto(`chrome-extension://${extensionId}/recordings.html`);
    await page.evaluate((uploadUrl) => chrome.storage.local.set({ uploadUrl, quick: { traces: true, otherTraces: true } }),
      `${JOGET}/api/blackbox`);
    const worker = context.serviceWorkers()[0];
    const capture = await worker.evaluate(async (origin) => {
      const [tab] = await chrome.tabs.query({ url: origin + '/*' });
      return startServerCapture(tab.id);
    }, new URL(JOGET).origin);
    expect(capture.status, capture.message).toBe('on');
    const startedAt = Date.now();

    // The same slow request from both users at the same moment
    const { released } = await lockTable(4);
    const mark = Date.now();
    const [own, theirs] = await Promise.all([load(joget, `${JOGET}${SLOW_PATH}?mine=${mark}`),
      load(other.page, `${JOGET}${SLOW_PATH}?theirs=${mark}`)]);
    await released;
    console.log('own', own, 'theirs', theirs);
    expect([own.status, theirs.status]).toEqual([200, 200]);
    expect(own.ms, 'own request slower than Glowroot\'s 2 s threshold').toBeGreaterThan(2500);
    expect(theirs.ms, 'other request slower than Glowroot\'s 2 s threshold').toBeGreaterThan(2500);

    await worker.evaluate((capture) => stopServerCapture(capture), capture);
    const id = 'e2e-other-traces-' + Date.now();
    await page.evaluate(async ({ id, capture, startedAt, url, mark, base }) => {
      const network = [{ t: 1000, url: `${base}/web/json/data/list/iService/list_srReq?mine=${mark}`, method: 'GET',
        type: 'XHR', status: 200, startedDateTime: new Date(mark).toISOString() }];
      const report = buildReport({ startedAt, tab: { url, title: id }, serverCapture: capture }, [], network, [], Date.now(), 'user');
      await idbAddRecording({ id, savedAt: new Date().toISOString(), video: null, report, uploaded: null });
    }, { id, capture, startedAt, url: joget.url(), mark, base: JOGET });
    await page.goto(`chrome-extension://${extensionId}/viewer.html?id=${id}`);
    expect(await page.evaluate(async (id) => Actions.uploadNow((await Store.allRows()).find((r) => r.id === id)), id)).toBe(true);
    rec = await uploaded(joget, id);

    // Own trace by tag; the other user's about 30 s after the stop, as an "other" entry with the user
    let traces = [];
    const mine = () => traces.find((t) => !t.other && t.url?.includes(`mine=${mark}`) && t.state === 'saved');
    const theirsEntry = () => traces.find((t) => t.other && t.user === OTHER_USER && t.url?.includes('/data/list/'));
    try {
      await expect.poll(async () => {
        traces = (await api(joget, 'traces', { id: rec.id })).traces;
        return !!mine() && !!theirsEntry();
      }, { timeout: 150_000, intervals: [5000] }).toBe(true);
    } catch { /* checked one by one below */ }
    console.log('traces', JSON.stringify(traces.map((t) => [t.other ? 'other' : 'own', t.user, t.url, t.ms, t.state, t.reason])));
    expect(mine(), 'the recorded request\'s own trace saved').toBeTruthy();
    expect(theirsEntry(), `${OTHER_USER}'s trace copied as other`).toBeTruthy();
    expect(theirsEntry().state).toBe('saved');
    expect(theirsEntry().ms).toBeGreaterThan(2000);
    expect(traces.filter((t) => t.other && t.url?.includes('/data/list/')), 'only the other session\'s request as other').toHaveLength(1);
  } finally {
    if (rec) await api(joget, 'delete', { id: rec.id }, 'POST');
    await other.close();
  }
});
