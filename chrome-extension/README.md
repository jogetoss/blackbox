# BlackBox

Chrome extension (Manifest V3). It records a video of a **tab, a window or the whole screen**, plus the console and
network activity of the tab you started from. The extension keeps the newest 10 recordings; to keep more, move them
to a folder, download them as ZIP or upload them.

- `recording.webm`: the video, with the page's sound and, if on, your microphone.
- `session.json`: console logs, uncaught exceptions, browser log entries, navigations and network requests (headers,
  post data, status, timing, text bodies up to 1 MB). Each entry's `t` (ms since start) matches the video position.

Internals and the upload API: [ARCHITECTURE.md](../ARCHITECTURE.md). Server side: the [plugin README](../README.md).

## Install

1. Use Chrome 116 or later. Open `chrome://extensions` and turn on **Developer mode**.
2. **Load unpacked** → this folder. After an update, click the reload icon on the extension card.
3. Pin the extension.

## Extension settings

Popup → **Settings** (this browser only; server options are in the plugin README's [Settings](../README.md#settings)):

- **Upload URL**: e.g. `https://example.com/jw/api/blackbox`. **Save** asks Chrome for access and checks the connection.
- **Recording page URL** (optional): opened by **Open uploaded**; `{id}` becomes the recording id. Empty: the server's link.
- **Open recordings on the server when you are logged in** (on): a stopped recording uploads and opens on the server.
  Otherwise it replays here and shows **⚠ Upload** (hover for why).
- **Text size** and **Theme**: for the extension's pages and the server's BlackBox pages. Downloaded HTML and the
  standalone viewer have their own Text size and Theme pickers in the header, remembered in that browser.

## Record

1. On the page to debug, click the extension icon and pick **Record this tab** (no URL bar), **Record a window** (with
   the URL bar) or **Record the screen**. Logs always come from the tab you started from. **Quick Settings** sets:
   - **Page sound** (off): the starting tab's sound, also for a window or the screen.
   - **Microphone** (off): Chrome asks on first use. If you blocked it, allow it at
     `chrome://settings/content/siteDetails?site=chrome-extension://<extension id>`.
   - Server options (**Export the apps used**, **Capture apps data**, **Mask data**, **All server log lines**,
     **Capture server load**, **Capture Glowroot trace files**, **Capture Glowroot data**), shown only when the server
     allows them: see
     [Per-recording choices](../README.md#per-recording-choices-popup). The choices are remembered for later
     recordings. All start unticked; apps data is real unless **Mask data** is ticked (the popup warns).
2. For a window or the screen, pick it in the recorder window. Chrome's "started debugging this browser" banner is
   expected.
3. Stop with the icon → **Stop & save**, the recorder window's button, or Chrome's **Stop sharing**. Closing the tab or
   cancelling the banner also stops and saves. If the extension can't keep the recording (e.g. disk full), it
   downloads it.

- **Server data:** when the tab is on the Upload URL's server, that server keeps the tab's server data (log lines and
  System Info; traces, server load and Glowroot data by the plugin's settings; every other server log line when you
  tick **All server log lines**). The popup
  warns when there is no Upload URL or the tab is on another server. While recording, an orange **REC** badge means
  the server isn't capturing (the tooltip says why). The recorder pings the server while recording (every 30 s by
  default): when the server has ended the capture, the recording stops and saves; when you are logged out, the
  recorder shows **Server capture ended: logged out** and goes on recording without server data. If the pings stop for
  3 minutes (crash, killed browser, no network), the server ends the capture at the last ping.
- **Time limit:** every recording is at most the plugin's **Max recording length** (30 minutes by default). With no
  answer from a server (no Upload URL, logged out, server down) the limit is 30 minutes. The recorder shows the time left, warns 2 minutes
  before, and then stops and saves the recording (stop reason `time-limit`).
- **Size limit:** video is recorded at 1.5 Mbit/s. When the server answered the start, the recorder stops and saves at 90% of the plugin's
  upload limit (**Max file size (MB)**), with stop reason `size-limit`, so the upload is not refused as too large.
- **Crash safety:** saved to browser storage every 5 s. After a crash, the recording is listed as **Recovered**, and
  its server capture is ended at the last save.

## Incognito

To record in incognito, turn on **Allow in Incognito** on the extension's details page. Incognito windows run their
own copy of the extension (`"incognito": "split"`):

- **Logins:** uploads and server data use the incognito window's Joget login, so the server shows the user who was
  recording there, not the normal window's user.
- **Settings** (Upload URL, theme, quick settings) are shared with normal windows.
- **Recording:** a normal and an incognito window can each record at the same time; the REC badge shows while either
  records.
- **Recordings** kept in incognito are not listed in normal windows and are gone when the last incognito window
  closes. Upload, download or move them to a folder first. The popup and the Recordings page say so.

## Recordings page

Popup → **Recordings**: recordings in browser storage, with **Browser issues** (console errors, failed and slow
requests). Only the newest 10 stay: saving a new one drops older ones. An uploaded one stays listed as upload history;
one never uploaded is gone, so move it to a folder or download it first.

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
