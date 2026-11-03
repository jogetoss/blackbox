# Browser tests of the extension

Playwright starts its own Chromium with `chrome-extension/` loaded unpacked, in a temporary profile, so the tests can
open and click the extension's own pages (`chrome-extension://…`), which Claude in Chrome and other extensions can't.
Your Chrome and its profile are not touched.

The tests run against a running Joget with the BlackBox plugin. They log in, add a recording to the extension's
storage, upload it through the real upload form and check what the server did. Every recording they upload is
deleted at the end.

## Run

```bash
cd e2e
npm install                      # once
npx playwright install chromium  # once: Chromium for Playwright (~100 MB, in ~/.cache/ms-playwright)
npx playwright test              # headless
BB_HEADED=1 npx playwright test  # watch it in a window
```

Needs a Joget with the BlackBox plugin and j8213's iService app (the tests' recordings open one of its assignments).
The all-server log test in `liveness.spec.js` ticks **All server log lines** itself; it needs a user in **Who can capture
server data**.

| Variable | Default | |
|---|---|---|
| `BB_JOGET` | `http://localhost:3128/jw` | The Joget to test against (j8213) |
| `BB_USER` | `admin` | |
| `BB_PASSWORD` | the user name | Local test servers use the user name as password |
| `BB_INCOGNITO_USER` | `cat` | The user `incognito.spec.js` logs in as in the incognito window (password = user name) |
| `BB_EXTENSION` | `../chrome-extension` | The extension to load, e.g. an older release to test this plugin with it |
| `BB_CONTAINER`, `BB_WFLOW` | `j8213`, `/opt/wflow` | Where `legacy-names.spec.js` edits a `meta.json` (`docker exec`) |
| `BB_DB_CONTAINER`, `BB_DB` | `mariadb`, `jw8` | Where `export-import.spec.js` and `glowroot-standalone.spec.js` read and lock iService's tables (`docker exec`) |
| `BB_GLOWROOT_DISTS` | `~/Joget/glowroot-0.14.2-dist.zip,~/Joget/glowroot-0.14.4-dist.zip` | The standalone Glowroot dists `glowroot-standalone.spec.js` opens the data in |
| `BB_GLOWROOT_PORT`, `BB_JAVA_IMAGE` | `14000`, `eclipse-temurin:17-jdk` | The standalone Glowroot's host port (localhost only) and its throwaway container's image |

## How it works

- `tests/fixtures.js` copies the extension to a temporary folder and adds the Joget origin to its manifest's
  `host_permissions`, so uploads don't ask for host access (Playwright can't click Chrome's permission dialog).
- Pages call the extension's own code (`idbAddRecording`, `Store`, `Actions`) through `page.evaluate`, since a real
  recording needs Chrome's screen picker.
- `tests/quick-settings.spec.js`: the popup's quick settings (shown as `me.options` allows, remembered), the app export
  at `recording-stop` kept by the upload, apps turned off at stop, and the automatic upload sending the choices saved
  with the recording; apps data without Mask data keeps real values (skipped when the user may not capture apps data).
- `tests/legacy-names.spec.js`: recordings, ZIPs and extensions of 8.2.1 (capture summary `systemLog`) work: the extension's own capture start and upload, a kept recording with `systemLog`, a `meta.json`
  with `systemLog`, and an old ZIP imported on the server and in the extension. Run the whole suite a second time with
  the previous extension (`git archive <tag> chrome-extension | tar -x -C /some/dir`, then
  `BB_EXTENSION=/some/dir/chrome-extension npx playwright test`); its ZIP test is skipped there.
- `tests/incognito.spec.js`: turns on Allow in Incognito, logs in as `BB_INCOGNITO_USER` (default `cat`) in an
  incognito window and checks that the incognito copy of the extension calls the server as that user (the normal copy
  as `BB_USER`), shows its note, and keeps its own recording state, capture rule and badge. Playwright can't see
  incognito windows, so it uses the browser's DevTools port (`--remote-debugging-port=0`, `openIncognito` in
  `fixtures.js`). Skipped for an extension without `"incognito": "split"`.
- `tests/normal-recording.spec.js`: a real screen recording in the normal window as `BB_USER`, from `start` to the
  automatic upload: its console and network logs, the attached server capture and the video. It records the screen,
  so it runs only with `BB_HEADED=1` (picker auto-selected, as below).
- `tests/incognito-recording.spec.js`: a real screen recording in incognito, from `start` to the automatic upload:
  the picker is skipped by `--auto-select-desktop-capture-source=Entire screen` (`fixtures.js`), then MediaRecorder,
  the console and network logs, the server capture, stop, and the upload as `BB_INCOGNITO_USER` with its video. It
  records the screen, so it runs only with `BB_HEADED=1` (windows open on your desktop for about 15 s; page sound off,
  as tab sound needs a click on the icon). "Record this tab" can't be tested: `chrome.tabCapture` needs that click.
- `tests/heartbeat.spec.js`: a headless screen recording (blank video) pings the server (`lastSeenAt`, `lastSavedAt`,
  `videoBytes` in the capture's `meta.json`, read with `docker exec`), stops with `server-stopped: stopped` after the
  server stopped the capture, and goes on after a 401 with "Server capture ended: logged out". Nothing is uploaded.
  Set the test override first (`{"pingMs":2000}`, see the plugin README's Storage) or each test waits about a minute.
- `tests/lost-browser.spec.js`: through the API, a capture pinged once and then left silent is stopped by the server
  at its last ping (`stopReason` `browser-lost`, read with `docker exec`); one never pinged stays open. Set the test
  override first (`{"lostMs":5000,"checkMs":2000}`) or it waits over 3 minutes. Nothing is uploaded.
- `tests/recovery-stop.spec.js`: a partial recording left in IndexedDB as after a crash, for captures started and
  pinged through the API: `recoverPartials()` stops each capture at its `lastSavedAt` when it is within the bounds,
  else at `lastSeenAt` (read with `docker exec`). Needs no override (a short `lostMs` works too). Nothing is uploaded.
- `tests/time-limit.spec.js`: a headless screen recording shows the time left and the 2-minute warning, the capture's
  `meta.json` has `maxUntil`, and the recording stops itself with reason `time-limit`. Nothing is uploaded. Set the
  test override first (`{"minLimitMs":20000}`) or it runs for the full **Max recording length** (30 min).
- `tests/time-limit-no-capture.spec.js`: without server capture the recorder still has the time limit and no size
  guard: 30 minutes with no Upload URL, and the server's `maxRecordingMs` from an error answer that carries it (stubbed in
  the service worker's `fetch`). Needs no override. Nothing is uploaded.
- `tests/size-limit.spec.js`: the start response carries `maxSizeMb`, the recorder's bitrate is 1.5 Mbit/s, and with
  its size limit lowered to 1 byte it stops itself with reason `size-limit`. A headless blank recording delivers 0-byte
  chunks, so the spec feeds the real `ondataavailable` handler a 0-byte chunk (no stop) and then a 1 KB chunk (stop).
  Needs no override. Nothing is uploaded.
- `tests/liveness.spec.js`: recording liveness with real headless recordings, checked in the capture's `meta.json`:
  a normal stop (the app export runs), a crash (`pkill -9` on the test's own Chromium) stopped by the server as
  `browser-lost` at the last ping and kept through recovery and upload, a restart before `lostMs` whose recovery stop
  sets the last save (it keeps the Joget login with Chrome's "Continue where you left off"; without it the restarted
  browser is logged out and the server's lost-browser check closes the capture instead), the time limit with upload,
  a server stop, the 8.2.1 extension (no pings: stopped only 2 minutes past its limit; `git archive 8.2.1`, or
  `BB_OLD_TAG`), and the all-server log stopping after a crash (skipped for a user not in **Who can capture other users' server data**, like `cat`). The spec writes its own test override
  (`{"pingMs":5000,"lostMs":20000,"checkMs":5000,"minLimitMs":60000}`, with `docker exec` as your user) and puts back
  the file that was there after; about 7 minutes. Uploads are deleted.
- `tests/upload-form.spec.js`: the apps data and Mask data choices on the upload form, the `sampleData` parameter it sends, and the
  App data the server builds.

- `tests/export-import.spec.js`: for apps without data, with real data and with masked data: the server's Download
  ZIP (recording, server data, `app/apps.json`, the `.jwa`, same as the App tab's **Download .jwa**), the `.jwa`'s
  `data/` rows (real: original ids; masked: the idMap's new ids, `userN`), Import ZIP on the server, and the `.jwa`
  imported over iService through App Center's import form (a new iService version, its rows in the tables). The test
  deletes the version and the masked rows afterwards. Also Download HTML opened from disk with Joget blocked (App tab),
  its Theme and Text size pickers (kept after a reload and shared with the standalone viewer), the standalone viewer (`dist/blackbox-viewer.html`) with the server ZIP and with `session.json`, Import ZIP in the
  extension, and the extension's Download ZIP imported on the server. About 6 minutes (each ZIP waits for Glowroot data).

- `tests/glowroot-standalone.spec.js`: a recording (quick settings: Glowroot trace files and data on) whose tab makes
  one slow request (a datalist read while the test holds a table lock for 4 s) and one failed one (a form load whose
  waiting query is killed: 500). BlackBox saves both traces; **Download Glowroot data** equals `glowroot/<node>.zip` in
  the Download ZIP, which holds only `data/` and `README.txt`; then, for each dist, the README's steps (`java -jar
  glowroot.jar`, the offline viewer) in a throwaway Java container (an `admin.json` with only `bindAddress` `0.0.0.0`,
  the port published on localhost only): the slow trace (≥ 2 s) and the error
  trace in the recording's window, both tagged with the capture id, and the Transactions page. Screenshots of each
  Glowroot's pages are in the test's output folder. About 2 minutes.

A failed test keeps a trace in `test-results/` (`npx playwright show-trace <trace.zip>`).
