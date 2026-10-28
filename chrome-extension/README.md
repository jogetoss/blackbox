# BlackBox

Chrome extension (Manifest V3). It records a video of a **tab, a window or the whole screen**, plus the console and
network activity of the tab you started from. Recordings stay in the extension until you upload, export or delete them.

- `recording.webm`: the video, with the page's sound and, if on, your microphone.
- `session.json`: console logs, uncaught exceptions, browser log entries, navigations and network requests (headers,
  post data, status, timing, text bodies up to 1 MB). Each entry's `t` (ms since start) matches the video position.

Internals and the upload API: [ARCHITECTURE.md](../ARCHITECTURE.md). Server side: the [plugin README](../README.md).

## Install

1. Open `chrome://extensions` and turn on **Developer mode**.
2. **Load unpacked** → this folder. After an update, click the reload icon on the extension card.
3. Pin the extension.

## Extension settings

Popup → **Settings** (this browser only; server options are in the plugin README's [Settings](../README.md#settings)):

- **Upload URL**: e.g. `https://example.com/jw/api/blackbox`. **Save** asks Chrome for access and checks the connection.
- **Recording page URL** (optional): opened by **Open uploaded**; `{id}` becomes the recording id. Empty: the server's link.
- **Open recordings on the server when you are logged in** (on): a stopped recording uploads and opens on the server.
  Otherwise it replays here and shows **⚠ Upload** (hover for why).
- **Text size** and **Theme**: for the extension's pages and the server's BlackBox pages. Downloaded HTML and the
  standalone viewer follow the system theme.

## Record

1. On the page to debug, click the extension icon and pick **Record this tab** (no URL bar), **Record a window** (with
   the URL bar) or **Record the screen**. Logs always come from the tab you started from. **Next recording** sets:
   - **Page sound** (on): the starting tab's sound, also for a window or the screen.
   - **Microphone** (off): Chrome asks on first use. If you blocked it, allow it at
     `chrome://settings/content/siteDetails?site=chrome-extension://<extension id>`.
   - Server options (**Export the apps used**, **Sample form data**, **Mask data**, **All server log lines**,
     **Glowroot data**), shown only when the server allows them: see
     [Per-recording choices](../README.md#per-recording-choices-popup).
2. For a window or the screen, pick it in the recorder window. Chrome's "started debugging this browser" banner is
   expected.
3. Stop with the icon → **Stop & save**, the recorder window's button, or Chrome's **Stop sharing**. Closing the tab or
   cancelling the banner also stops and saves. If the extension can't keep the recording (e.g. disk full), it
   downloads it.

- **System logs:** with the plugin's **Capture system logs** on, the server keeps its log lines for the tab. The popup
  warns when there is no Upload URL or the tab is on another server. While recording, an orange **REC** badge means
  the server isn't capturing (the tooltip says why).
- **Crash safety:** saved to browser storage every 5 s. After a crash, the recording is listed as **Recovered**.

## Recordings page

Popup → **Recordings**: recordings in browser storage (newest 10), with **Browser issues** (console errors, failed
and slow requests).

- **Link folder…**: also list recordings in `Downloads/blackbox` (read in place). After a browser restart, click
  **Allow access**.
- **Import ZIP…**: a recording ZIP from a BlackBox server or another browser.
- Tick rows for **Upload selected**, **Download ZIP**, **Delete selected**. Server copies are never deleted from here.
- Uploaded recordings stay as **upload history**; the title opens the server copy.

## Replay page

Opens after **Stop** or from the Recordings page. The same viewer runs on the server, in downloaded HTML and in the
standalone viewer; **Features** in the header lists what each can do.

- The status bar shows **where this copy is** and has **Upload**, **Move to folder**, **Download ZIP**, **Delete**.
- **Upload** checks the server first; if it can't work, it shows the reason and the fix (Upload URL, login link,
  **Allow access**, or who to ask).
- Keys: Space play/pause, ← / → 5 s. The URL carries the playhead (`&t=5.8`) to share a moment.

**Standalone viewer:** `dist/blackbox-viewer.html` replays `recording.webm` + `session.json`, or a ZIP, in any
Chromium browser without the extension. Rebuild after viewer changes: `python3 build-standalone.py`.

## Limits

- Logs come from one tab only, even when the video shows the whole screen.
- No logs from `chrome://` pages or the Chrome Web Store; `file://` pages need **Allow access to file URLs**.
- Binary bodies (images, fonts), scripts and stylesheets keep only the request metadata. POST bodies over 64 KB show
  `hasPostData: true` without content.
- The WebM has no duration in its header, so some players can't seek it; the viewer, Chrome and VLC are fine.
- Only the starting tab is heard. For all sound when recording the screen, tick **Share system audio** in the picker
  (Windows and ChromeOS only); it replaces the tab's sound.
- On GNOME Wayland, Chrome's picker is followed by the system "Share Screen" dialog.
