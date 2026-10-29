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

Needs a Joget with the BlackBox plugin, **Capture server data** on, and j8213's iService app (the tests' recordings
open one of its assignments).

| Variable | Default | |
|---|---|---|
| `BB_JOGET` | `http://localhost:3128/jw` | The Joget to test against (j8213) |
| `BB_USER` | `admin` | |
| `BB_PASSWORD` | the user name | Local test servers use the user name as password |
| `BB_INCOGNITO_USER` | `cat` | The user `incognito.spec.js` logs in as in the incognito window (password = user name) |
| `BB_EXTENSION` | `../chrome-extension` | The extension to load, e.g. an older release to test this plugin with it |
| `BB_CONTAINER`, `BB_WFLOW` | `j8213`, `/opt/wflow` | Where `legacy-names.spec.js` edits a `meta.json` (`docker exec`) |

## How it works

- `tests/fixtures.js` copies the extension to a temporary folder and adds the Joget origin to its manifest's
  `host_permissions`, so uploads don't ask for host access (Playwright can't click Chrome's permission dialog).
- Pages call the extension's own code (`idbAddRecording`, `Store`, `Actions`) through `page.evaluate`, since a real
  recording needs Chrome's screen picker.
- `tests/quick-settings.spec.js`: the popup's quick settings (shown as `me.options` allows, remembered), the app export
  at `recording-stop` kept by the upload, apps turned off at stop, and the automatic upload sending the choices saved
  with the recording; Mask data unticked keeps real values (skipped when the user may not).
- `tests/legacy-names.spec.js`: recordings, ZIPs and extensions from before the `systemLog` -> `serverCapture`
  rename still work: the extension's own capture start and upload, a kept recording with `systemLog`, a `meta.json`
  with `systemLog`, and an old ZIP imported on the server and in the extension. Run the whole suite a second time with
  the previous extension (`git archive <tag> chrome-extension | tar -x -C /some/dir`, then
  `BB_EXTENSION=/some/dir/chrome-extension npx playwright test`); its ZIP test is skipped there.
- `tests/incognito.spec.js`: turns on Allow in Incognito, logs in as `BB_INCOGNITO_USER` (default `cat`) in an
  incognito window and checks that the incognito copy of the extension calls the server as that user (the normal copy
  as `BB_USER`), shows its note, and keeps its own recording state, capture rule and badge. Playwright can't see
  incognito windows, so it uses the browser's DevTools port (`--remote-debugging-port=0`, `openIncognito` in
  `fixtures.js`). Skipped for an extension from before `"incognito": "split"`.
- `tests/incognito-recording.spec.js`: a real screen recording in incognito, from `start` to the automatic upload:
  the picker is skipped by `--auto-select-desktop-capture-source=Entire screen` (`fixtures.js`), then MediaRecorder,
  the console and network logs, the server capture, stop, and the upload as `BB_INCOGNITO_USER` with its video. It
  records the screen, so it runs only with `BB_HEADED=1` (windows open on your desktop for about 15 s; page sound off,
  as tab sound needs a click on the icon). "Record this tab" can't be tested: `chrome.tabCapture` needs that click.
- `tests/upload-form.spec.js`: the sample data choice on the upload form, the `sampleData` parameter it sends, and the
  App data the server builds.

A failed test keeps a trace in `test-results/` (`npx playwright show-trace <trace.zip>`).
