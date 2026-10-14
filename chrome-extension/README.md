# BlackBox

Chrome extension (Manifest V3). It records a video of a **window, the entire screen or a tab**, plus the console and
network activity of the tab you started from. The recording stays inside the extension until you upload it to a server,
export it, or delete it.

- `recording.webm` — the video (no audio). Pick a Chrome **window** to get the URL bar in it.
- `session.json` — console logs, uncaught exceptions, browser log entries, page navigations and network requests
  (headers, post data, status, timing, text response bodies up to 1 MB)

Every entry has `t` = ms since start, which matches the position in the video.

## Install

1. Open `chrome://extensions`, turn on **Developer mode**.
2. **Load unpacked** → select this folder. After an update, click the reload icon on the extension card.
3. Pin the extension.

## Settings

Popup → **Settings** (or the extension's **Details → Extension options**):

- **Upload URL** — the [Upload endpoint](#upload-endpoint) of the server that stores recordings, e.g.
  `https://example.com/api/blackbox`. **Save** asks Chrome for access to that server and checks the connection;
  a message appears only when something is wrong.
- **Recording page URL** (optional) — opened by **Open uploaded**; `{id}` is replaced with the server's recording id,
  e.g. `https://example.com/view?id={id}`. Empty: the link the server sends.
- **Theme** — System, Light or Dark, for the viewer, popup, settings and recorder window.

## Record

1. Open the page to debug, click the extension icon and pick what to record:
   - **Record this tab** — records the page in this tab directly, with no picker (`chrome.tabCapture`). The video
     shows the page only, not the URL bar. Also avoids GNOME's "Share Screen" dialog.
   - **Record a window** — Chrome's picker lists windows only. Pick the Chrome window to get the URL bar.
   - **Record the screen** — the picker lists screens only.
   Logs always come from the tab you started from.
2. For a window or the screen, a small recorder window opens with the picker. Don't pick the recorder window itself.
3. The recorder window minimizes and recording starts. Chrome shows a "started debugging this browser" banner; that is
   expected (`chrome.debugger`).
4. Stop with the extension icon → **Stop & save**, the recorder window's button, or Chrome's **Stop sharing** bar.
   The Replay page opens with the recording. Nothing is saved to disk until you click **Export**.

Clicking **Cancel** on the debugging banner or closing the tab also stops and saves. If the extension can't keep the
recording (e.g. disk full), it downloads it instead.

**Crash safety:** while recording, the video and logs are saved to browser storage every 5 seconds. If Chrome crashes
or the recorder window is closed, the recording is recovered (at the next browser start, or when the Recordings page
opens) and listed as **Recovered**; it ends at the last save.

## Recordings page

Popup → **Recordings**. Lists the recordings in browser storage (newest 10; older ones are dropped) with their
status (Uploaded / Not uploaded). **Link folder…** adds the recordings exported or downloaded earlier: pick
`Downloads/blackbox` (or `Downloads`) once; they are listed as **On disk** and read from there, not copied. After a
browser restart, click **Allow access** to read the folder again.

Each row has:
- **Open** — goes to the Replay page (browser Back returns to the list).
- **Upload** — asks for a title and notes; **Send** uploads it. Uses your login on the server in this browser. The
  copy stays in the extension, marked Uploaded (the badge links to it on the server). Uploaded rows have no Upload.
- **Export** (in browser storage) — saves both files to `Downloads/blackbox/<start time>/`, then removes the
  extension's copy.
- **Delete** — removes the extension's copy. On a row read from the linked folder, Delete appears once the recording
  is uploaded and removes its files from the folder (Chrome asks once to let BlackBox edit the folder). The server
  copy is never deleted from the extension.

**Upload history:** when an uploaded recording's local copy goes away (Delete, Export without a linked folder, or
dropped by the 10-recording limit), its row stays in the list with only **Open** (opens it on the server, new tab) and
**Delete** (removes just the row; the server copy stays). The row keeps only the date, page, length and server link.
History has no limit; the list shows 100 rows at a time with **Show more**.

## Replay page

Opens after **Stop** with the new recording, or from **Open** on the Recordings page. Its address names the recording,
so a reload replays the same one. **← Recordings** goes back to the list. **Open files…** / drag and drop replays
`recording.webm` + `session.json` from disk (to upload old files, link their folder on the Recordings page).

Replay:
- Console and Network lists follow the video: past rows are normal, future rows are faded (or hidden with
  **Hide future**), the latest row is marked and kept in view (**Follow**).
- Click a row to jump the video there and see details: headers, request body, response body (JSON pretty-printed),
  **Copy as cURL**.
- The timeline under the video marks console errors (red), failed requests (orange) and page navigations (blue).
  **Prev / Next issue** jumps between errors and failed requests.
- Keys: Space play/pause, ← / → 5 seconds.

**Standalone viewer:** `dist/blackbox-viewer.html` is one self-contained file (CSS and JS inlined, no network, no
Recordings page). Open it in any Chromium-based browser, or send it to a teammate with the two recording
files. Rebuild it after changing the viewer: `python3 build-standalone.py`.

## Upload endpoint

Any server can receive recordings by implementing this at the Upload URL. Requests carry the browser's cookies for
that server (`withCredentials`), so the server sees the user's normal login; the extension never handles passwords.
The extension's own `Origin` (`chrome-extension://…`) is on every POST; web pages can't send that, so a server can
accept it in place of a CSRF token.

| Request | Body | Success response (JSON) |
|---|---|---|
| `GET ?action=me` | — | `{"user":{"name":"…"},"canUpload":true,"maxSizeMb":500}` |
| `POST ?action=create&title=…&notes=…` | `session.json` (`application/json`) | `{"id":"…","url":"/path/to/view"}` — `url` optional |
| `POST ?action=video&id=<id>` | `recording.webm` (`video/webm`) | any JSON object |

Errors: an HTTP error status with `{"error":{"message":"…"}}`; **401** means "not logged in". `url` may be a path; it is
resolved against the Upload URL. The Recording page URL setting, when set, takes priority over `url`.

## How it works

| Part | Job |
|---|---|
| `popup.js` | Record this tab / a window / the screen, Stop; gets the tab-capture stream id for "this tab" |
| `recorder.js` | Shows the `desktopCapture` picker (window/screen) or uses the tab stream, records with `MediaRecorder`, saves progress every 5 s, keeps the recording in IndexedDB |
| `viewer.js` | Replay UI: video + synced console/network lists, timeline markers, request details (a server can reuse it to show uploaded recordings) |
| `recordings.js` | Recordings page: list, per-row Open, Upload, Export, Delete |
| `replay.js` | Replay page: loads the recording named in the address, shows its status |
| `report.js` | Builds `session.json`; recovers recordings interrupted by a crash |
| `store.js` | Shared by both pages: kept recordings + linked folder, row list, loading a recording |
| `server.js` | Calls the Upload endpoint with the browser's cookies (the user's login on that server) |
| `idb.js` | IndexedDB: kept recordings, linked folder handle, upload marks for recordings on disk |
| `options.js` | Settings: Upload URL (asks for host access), theme |
| `theme.js`, `ui.css` | Theme for all extension pages |
| `background.js` | Attaches the debugger (Network, Runtime, Log, Page), fetches response bodies, forwards events to the recorder |

The recorder is a window, not an offscreen document: a `chooseDesktopMedia` stream id can only be opened by the page
that showed the picker (tested in the BlackBox spike X0-01).

## Limits

- Logs come from one tab only, even when the video shows the whole screen.
- Logs can't be taken from `chrome://` pages or the Chrome Web Store. `file://` pages need **Allow access to file URLs**.
- Binary bodies (images, fonts) and scripts/stylesheets are not saved; only the request metadata.
- Large POST bodies (over 64 KB) show `hasPostData: true` without the content.
- The WebM from `MediaRecorder` has no duration in its header, so some players cannot seek it. The viewer works
  around this; Chrome and VLC play it fine.
- On GNOME Wayland, Chrome's picker is followed by the system "Share Screen" dialog.
