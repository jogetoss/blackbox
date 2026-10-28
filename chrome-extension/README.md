# BlackBox

Chrome extension (Manifest V3). It records a video of a **window, the entire screen or a tab**, plus the console and
network activity of the tab you started from. The recording stays inside the extension until you upload it to a server,
export it, or delete it.

- `recording.webm` — the video, with the page's sound and, if turned on, your microphone. Pick a Chrome **window** to
  get the URL bar in it.
- `session.json` — console logs, uncaught exceptions, browser log entries, page navigations and network requests
  (headers, post data, status, timing, text response bodies up to 1 MB). Every entry has `t` = ms since start, which
  matches the position in the video.

Internals and the Upload endpoint API: [ARCHITECTURE.md](../ARCHITECTURE.md).

## Install

1. Open `chrome://extensions`, turn on **Developer mode**.
2. **Load unpacked** → select this folder. After an update, click the reload icon on the extension card.
3. Pin the extension.

## Extension settings

Popup → **Settings** (this browser only; the server's options are in the plugin README's
[Settings](../README.md#settings)):

- **Upload URL** — the server that stores recordings, e.g. `https://example.com/jw/api/blackbox` for the BlackBox
  Joget plugin. **Save** asks Chrome for access to that server and checks the connection.
- **Recording page URL** (optional) — opened by **Open uploaded**; `{id}` is replaced with the server's recording id.
  Empty: the link the server sends.
- **Open recordings on the server when you are logged in** (on by default) — when a recording stops and you are
  logged in to the Upload URL's server, it is uploaded and the server's replay page opens. Otherwise it replays in the
  extension and the button shows **⚠ Upload** (hover for the reason).
- **Text size** and **Theme** — for all extension pages and the BlackBox pages of the Upload URL's server
  (`<context>/blackbox/`); the pages themselves have no pickers. Downloaded HTML files and the standalone viewer
  follow the system theme; use the browser's zoom there.

## Record

1. Open the page to debug, click the extension icon and pick **Record this tab** (no picker, no URL bar), **Record a
   window** (pick the Chrome window to get the URL bar) or **Record the screen**. Logs always come from the tab you
   started from. Under the buttons, **Next recording** sets the choices for it (remembered for the next one):
   - **Page sound** (on by default) — the sound of the tab you start from, also when you record a window or the
     screen. You still hear the tab while it records.
   - **Microphone** (off by default) — to talk through the problem. Chrome asks for the microphone on the first
     recording; if you blocked it, set **Microphone** to **Allow** at
     `chrome://settings/content/siteDetails?site=chrome-extension://<extension id>`.
   - The server's options: **Export the apps used**, **Sample form data**, **Mask data**, **All server log lines**,
     **Glowroot data**. Each shows only when the server's BlackBox settings and your rights allow it; see the plugin
     README's [Per-recording choices](../README.md#per-recording-choices-popup).

   Both sounds go into one sound track. `session.json` says what the recording has: `recording.audio` =
   `{ page, pageSource: "tab" | "system", mic, message }`, where `message` says why a sound is missing.
2. For a window or the screen, pick it in the small recorder window (not the recorder window itself). Chrome shows a
   "started debugging this browser" banner; that is expected. With **Microphone** on, allow the microphone
   when Chrome asks. The recorder window shows what is recorded, e.g. `sound: page + microphone`.
3. Stop with the extension icon → **Stop & save**, the recorder window's button, or Chrome's **Stop sharing** bar.
   Cancelling the debugging banner or closing the tab also stops and saves. If the extension can't keep the
   recording (e.g. disk full), it downloads it instead.

**System logs:** with the BlackBox plugin's **Capture system logs** on, the server keeps its log lines for the recorded
requests. The popup warns before you start when there is no Upload URL or the tab is on another server, and while
recording the **REC** badge is orange instead of red when the server isn't capturing (the tooltip says why).

**Crash safety:** the video and logs are saved to browser storage every 5 seconds. After a crash or a closed recorder
window, the recording is recovered and listed as **Recovered**.

## Recordings page

Popup → **Recordings**: the recordings in browser storage (newest 10) with their **Browser issues** (console errors,
failed and slow requests) and status.

- **Link folder…** — also list the recordings in `Downloads/blackbox`, read from there, not copied. After a browser
  restart, click **Allow access** to read the folder again.
- **Import ZIP…** — add a recording ZIP from a BlackBox server or another browser.
- Tick rows for **Upload selected**, **Download ZIP** and **Delete selected**. Server copies are never deleted from
  the extension.
- Uploaded recordings stay listed as **upload history** after their local copy is gone; their title opens the server
  copy.

## Replay page

Opens after **Stop**, or from the Recordings page. The same viewer runs on the server's replay page, in a downloaded
HTML file and in the standalone viewer. **Features** in the header lists everything each of them can do.

- The status bar says **where this copy is** (*In this browser*, *On <server>*, …) and has **Upload**, **Move to
  folder**, **Download ZIP** and **Delete**.
- **Upload** checks the server first. When uploading can't work, it shows the reason and the fix instead of the form:
  the Upload URL field, a link to log in, **Allow access**, or who to ask.
- Keys: Space play/pause, ← / → 5 seconds. The address carries the playhead time (`&t=5.8`) to share a moment.

**Standalone viewer:** `dist/blackbox-viewer.html` replays `recording.webm` + `session.json`, or a recording ZIP, in
any Chromium-based browser without the extension. Rebuild it after changing the viewer: `python3 build-standalone.py`.

## Limits

- Logs come from one tab only, even when the video shows the whole screen.
- Logs can't be taken from `chrome://` pages or the Chrome Web Store. `file://` pages need **Allow access to file URLs**.
- Binary bodies (images, fonts) and scripts/stylesheets are not saved; only the request metadata.
- Large POST bodies (over 64 KB) show `hasPostData: true` without the content.
- The WebM from `MediaRecorder` has no duration in its header, so some players cannot seek it. The viewer works
  around this; Chrome and VLC play it fine.
- Page sound comes from the tab you started from (you still hear it while recording), also for a window or the
  screen: other tabs and apps are not heard. For all sound on a screen, tick **Share system audio** in the picker
  (Windows and ChromeOS only); it replaces the tab's sound.
- On GNOME Wayland, Chrome's picker is followed by the system "Share Screen" dialog.
