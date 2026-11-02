// Download Glowroot data opened in a standalone Glowroot, as its README.txt says: a recording with one slow and one
// failed request, its Glowroot ZIP (the same one in the recording's Download ZIP), then for each Glowroot dist
// (BB_GLOWROOT_DISTS, default 0.14.2 and 0.14.4 in ~/Joget) a throwaway Java container with that agent and the ZIP's
// data/, config.json and admin.json, checked through Glowroot's UI and its backend API.
//
// The slow request: a form load while the test holds a WRITE lock on its table for 4 s (Glowroot's slow threshold is
// 2 s). The failed one: the same load with its waiting query killed (KILL QUERY), which Joget answers with 500.
const { execFileSync, spawn } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { test, expect, JOGET, LEGACY_EXTENSION, loginJoget, api, uploaded } = require('./fixtures');

const DB_CONTAINER = process.env.BB_DB_CONTAINER || 'mariadb';
const DB = process.env.BB_DB || 'jw8';
const DISTS = (process.env.BB_GLOWROOT_DISTS
  || ['0.14.2', '0.14.4'].map((v) => path.join(os.homedir(), 'Joget', `glowroot-${v}-dist.zip`)).join(',')).split(',');
const PORT = Number(process.env.BB_GLOWROOT_PORT || 14000); // host port of the standalone Glowroot (container: 4000)
const JAVA_IMAGE = process.env.BB_JAVA_IMAGE || 'eclipse-temurin:17-jdk';
const TABLE = 'rvp_sr_req'; // j8213's iService: form srReq
const SLOW_URL = `${JOGET}/web/json/data/list/iService/list_srReq?e2e=`; // reads the table
const FAILED_URL = `${JOGET}/web/json/data/form/load/iService/srReq/`; // a query error here is a 500

test.skip(LEGACY_EXTENSION, 'tests this extension only');

const DB_EXEC = ['exec', '-i', DB_CONTAINER, 'sh', '-c', `mariadb -uroot -p"$MARIADB_ROOT_PASSWORD" -N -B ${DB}`];
const sql = (query) => execFileSync('docker', DB_EXEC, { input: query, encoding: 'utf8' });

/** Holds a WRITE lock on the table for `seconds` in its own DB session; resolves once it is held, with
 * { released }, a promise of its release (wrapped: an async function returning a promise would wait for it). */
async function lockTable(seconds) {
  const p = spawn('docker', DB_EXEC, { stdio: ['pipe', 'ignore', 'inherit'] });
  p.stdin.end(`LOCK TABLES app_fd_${TABLE} WRITE; SELECT SLEEP(${seconds}); UNLOCK TABLES;`);
  const released = new Promise((ok) => p.on('exit', ok));
  // the lock is held once the session sleeps
  await expect.poll(() => sql("SELECT COUNT(*) FROM information_schema.processlist WHERE info LIKE 'SELECT SLEEP%'").trim(),
    { timeout: 10_000 }).not.toBe('0');
  return { released };
}

/** A GET from the recorded tab: its status and how long it took. */
const load = (page, url) => page.evaluate(async (url) => {
  const t = Date.now();
  const r = await fetch(url);
  return { status: r.status, ms: Date.now() - t };
}, url);

function unzip(zip, dir) {
  fs.mkdirSync(dir, { recursive: true });
  execFileSync('python3', ['-I', '-c', 'import sys, zipfile; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])', zip, dir]);
}

async function fetchBytes(page, url) {
  const b64 = await page.evaluate(async (url) => {
    let res = await fetch(url);
    for (const until = Date.now() + 150_000; res.status === 409 && Date.now() < until; res = await fetch(url)) {
      await new Promise((r) => setTimeout(r, 5000));
    }
    if (!res.ok) throw new Error(url + ': HTTP ' + res.status + ' ' + await res.text());
    const bytes = new Uint8Array(await res.arrayBuffer());
    let s = '';
    for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
    return btoa(s);
  }, url);
  return Buffer.from(b64, 'base64');
}

/** A recording whose tab made one slow and one failed request, uploaded; its server data built. */
async function recordSlowAndFailed(context, extensionId, joget) {
  const page = await context.newPage();
  await page.goto(`chrome-extension://${extensionId}/recordings.html`);
  // The popup's quick settings: Capture Glowroot trace files and Capture Glowroot data (both off until ticked)
  await page.evaluate((uploadUrl) => chrome.storage.local.set({ uploadUrl, quick: { traces: true, glowrootData: true } }),
    `${JOGET}/api/blackbox`);
  const worker = context.serviceWorkers()[0];
  const capture = await worker.evaluate(async (origin) => {
    const [tab] = await chrome.tabs.query({ url: origin + '/*' });
    return startServerCapture(tab.id);
  }, new URL(JOGET).origin);
  expect(capture.status, capture.message).toBe('on');
  const startedAt = Date.now();

  let { released } = await lockTable(4);
  const slow = await load(joget, SLOW_URL + Date.now());
  await released;
  console.log('slow request', slow);
  expect(slow.status).toBe(200);
  expect(slow.ms, 'slower than Glowroot\'s 2 s threshold').toBeGreaterThan(2500);

  ({ released } = await lockTable(10));
  const pending = load(joget, FAILED_URL + 'e2e-' + Date.now());
  let killed = [];
  await expect.poll(() => (killed = sql("SELECT id FROM information_schema.processlist WHERE state LIKE '%metadata lock%'")
    .split('\n').filter(Boolean)).length, { timeout: 10_000 }).toBeGreaterThan(0);
  for (const id of killed) sql(`KILL QUERY ${id}`);
  const failed = await pending;
  await released;
  expect(failed.status).toBe(500);

  await worker.evaluate((capture) => stopServerCapture(capture), capture);
  const id = 'e2e-glowroot-' + Date.now();
  await page.evaluate(async ({ id, capture, startedAt, url }) => {
    const report = buildReport({ startedAt, tab: { url, title: id }, serverCapture: capture }, [], [], [], Date.now(), 'user');
    await idbAddRecording({ id, savedAt: new Date().toISOString(), video: null, report, uploaded: null });
  }, { id, capture, startedAt, url: joget.url() });
  await page.goto(`chrome-extension://${extensionId}/viewer.html?id=${id}`);
  expect(await page.evaluate(async (id) => Actions.uploadNow((await Store.allRows()).find((r) => r.id === id)), id)).toBe(true);
  const rec = await uploaded(joget, id);
  return { rec, capture, slow, failed };
}

test('Download Glowroot data opens in a standalone Glowroot (slow and failed request)', async ({ context, extensionId }) => {
  test.setTimeout(600_000);
  for (const dist of DISTS) expect(fs.existsSync(dist), dist).toBe(true);
  const joget = await context.newPage();
  await loginJoget(joget);
  const me = await api(joget, 'me');
  test.skip(!me.options.traces || !me.options.glowrootData, 'this user may not capture traces and Glowroot data');
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'bb-glowroot-'));
  const containers = [];
  let rec;
  try {
    const r = await recordSlowAndFailed(context, extensionId, joget);
    rec = r.rec;
    console.log(`recording ${rec.id}: slow ${r.slow.status} in ${r.slow.ms} ms, failed ${r.failed.status} in ${r.failed.ms} ms`);

    // BlackBox's own trace collection: the slow and the failed request each have a saved trace
    let traces = [];
    const saved = (part) => traces.find((t) => t.url?.includes(part) && t.state === 'saved');
    try {
      await expect.poll(async () => {
        traces = (await api(joget, 'traces', { id: rec.id })).traces;
        return !!saved('/data/list/') && !!saved('/form/load/');
      }, { timeout: 120_000, intervals: [5000] }).toBe(true);
    } catch { /* checked one by one below */ }
    console.log('traces', JSON.stringify(traces.map((t) => [t.url, t.status, t.ms, t.state, t.reason])));
    expect(saved('/data/list/'), 'the slow request\'s trace saved by BlackBox').toBeTruthy();
    expect.soft(saved('/form/load/'), 'the failed request\'s trace saved by BlackBox').toBeTruthy();

    // Glowroot data of the node, built 30–90 s after the stop
    let nodes;
    await expect.poll(async () => {
      nodes = (await api(joget, 'glowroot', { id: rec.id })).nodes;
      return nodes.map((n) => n.state).join();
    }, { timeout: 240_000, intervals: [10_000] }).toMatch(/^(saved,?)+$/);
    const node = nodes[0];
    expect(node.traces, 'the slow and the failed trace are in the Glowroot data').toBeGreaterThanOrEqual(2);
    const zip = path.join(dir, 'glowroot.zip');
    fs.writeFileSync(zip, await fetchBytes(joget, `${JOGET}/api/blackbox?action=glowroot&id=${rec.id}&node=${node.node}`));

    // The same file is in the recording's Download ZIP
    const recZip = path.join(dir, 'recording.zip');
    fs.writeFileSync(recZip, await fetchBytes(joget, `${JOGET}/api/blackbox?action=download&format=zip&id=${rec.id}`));
    unzip(recZip, path.join(dir, 'recording'));
    expect(fs.readFileSync(path.join(dir, 'recording', 'glowroot', `${node.node}.zip`)).equals(fs.readFileSync(zip)),
      'Download Glowroot data = glowroot/<node>.zip in the Download ZIP').toBe(true);

    const data = path.join(dir, 'data');
    unzip(zip, data);
    for (const f of ['data/data.h2.db', 'config.json', 'admin.json', 'README.txt', 'Sleep.java']) {
      expect(fs.existsSync(path.join(data, f)), f).toBe(true);
    }
    const readme = fs.readFileSync(path.join(data, 'README.txt'), 'utf8');
    const [, from, to] = readme.match(/from=(\d+)&to=(\d+)/);

    for (const dist of DISTS) {
      const version = path.basename(dist).match(/glowroot-(.+)-dist\.zip/)[1];
      // README steps 1-2: unzip the dist, copy data/, config.json and admin.json into its glowroot folder
      const home = path.join(dir, version);
      unzip(dist, home);
      const g = path.join(home, 'glowroot');
      fs.cpSync(path.join(data, 'data'), path.join(g, 'data'), { recursive: true });
      for (const f of ['config.json', 'admin.json']) fs.copyFileSync(path.join(data, f), path.join(g, f));
      fs.copyFileSync(path.join(data, 'Sleep.java'), path.join(home, 'Sleep.java'));
      // admin.json listens on localhost only; in a container that is unreachable, so it listens on the container's
      // own interfaces and the port is published on the host's localhost only
      const admin = JSON.parse(fs.readFileSync(path.join(g, 'admin.json'), 'utf8'));
      expect(admin.web.bindAddress).toBe('127.0.0.1');
      admin.web.bindAddress = '0.0.0.0';
      fs.writeFileSync(path.join(g, 'admin.json'), JSON.stringify(admin, null, 2));

      // README step 3: any JVM with the agent
      const name = `bb-e2e-glowroot-${version.replace(/\W/g, '')}-${Date.now()}`;
      containers.push(name);
      execFileSync('docker', ['run', '-d', '--name', name, '--user', `${process.getuid()}:${process.getgid()}`,
        '-p', `127.0.0.1:${PORT}:${admin.web.port || 4000}`, '-v', `${home}:/g`, '-w', '/g', JAVA_IMAGE,
        'java', '-javaagent:/g/glowroot/glowroot.jar', 'Sleep.java'], { encoding: 'utf8' });
      const base = `http://127.0.0.1:${PORT}`;
      const ui = await context.newPage();
      await expect.poll(async () => (await ui.request.get(base + '/').catch(() => null))?.status() ?? 0,
        { timeout: 120_000, intervals: [2000] }).toBe(200);

      // README step 4: the recording's transactions and its traces, in the UI
      const window = `transaction-type=Web&from=${from}&to=${to}`;
      // Glowroot lists error traces under error/points, slow ones under transaction/points
      const get = async (p) => (await ui.request.get(`${base}/backend/${p}?agent-rollup-id=&${window}&duration-millis-low=0&limit=100`)).json();
      const errorTraces = (await get('error/points')).errorPoints || [];
      const normalTraces = (await get('transaction/points')).normalPoints || [];
      console.log(`Glowroot ${version}: ${normalTraces.length} normal, ${errorTraces.length} error traces in ${from}..${to}`);
      expect(errorTraces.length, `Glowroot ${version}: the failed request's trace`).toBeGreaterThanOrEqual(1);
      expect(normalTraces.some((p) => p[1] >= 2000), `Glowroot ${version}: the slow request's trace (≥ 2 s)`).toBe(true);

      // Each trace carries the BlackBox recording attribute (the capture id)
      for (const p of [errorTraces[0], normalTraces.find((x) => x[1] >= 2000)]) {
        const header = await (await ui.request.get(`${base}/backend/trace/header?agent-id=&trace-id=${p[3]}`)).json();
        expect(JSON.stringify(header.attributes || {}), `Glowroot ${version}: trace ${p[3]} tagged`).toContain(r.capture.id);
        expect(header.headline || '').toContain('/iService/');
      }

      await ui.goto(`${base}/transaction/traces?${window}`);
      await expect(ui.locator('body')).toContainText('Web', { timeout: 30_000 });
      await ui.screenshot({ path: test.info().outputPath(`glowroot-${version}-traces.png`), fullPage: true });
      await ui.goto(`${base}/transaction/average?${window}`);
      await expect(ui.locator('body')).toContainText('/iService/', { timeout: 30_000 });
      await ui.screenshot({ path: test.info().outputPath(`glowroot-${version}-transactions.png`), fullPage: true });
      await ui.close();

      execFileSync('docker', ['rm', '-f', name]);
      containers.pop();
    }
  } finally {
    for (const name of containers) execFileSync('docker', ['rm', '-f', name]);
    if (rec) await api(joget, 'delete', { id: rec.id }, 'POST');
    fs.rmSync(dir, { recursive: true, force: true });
  }
});
