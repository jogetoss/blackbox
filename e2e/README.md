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

| Variable | Default | |
|---|---|---|
| `BB_JOGET` | `http://localhost:3128/jw` | The Joget to test against (j8213) |
| `BB_USER` | `admin` | |
| `BB_PASSWORD` | the user name | Local test servers use the user name as password |

## How it works

- `tests/fixtures.js` copies the extension to a temporary folder and adds the Joget origin to its manifest's
  `host_permissions`, so uploads don't ask for host access (Playwright can't click Chrome's permission dialog).
- Pages call the extension's own code (`idbAddRecording`, `Store`, `Actions`) through `page.evaluate`, since a real
  recording needs Chrome's screen picker.
- `tests/quick-settings.spec.js`: the popup's quick settings (shown as `me.options` allows, remembered), the app export
  at `recording-stop` kept by the upload, apps turned off at stop, and the automatic upload sending the choices saved
  with the recording (needs "Capture system logs" on).
- `tests/upload-form.spec.js`: the sample data choice on the upload form, the `sampleData` parameter it sends, and the
  App data the server builds (needs j8213's iService app).

A failed test keeps a trace in `test-results/` (`npx playwright show-trace <trace.zip>`).
