# Liveness and limits

## Liveness

- The extension tells the server every 30 s that the recording is running (`recording-ping`); this keeps the Joget
  session alive.
- If the server has ended the capture, the recording stops (reason `server-stopped: …`). If the user is logged out,
  the recorder shows **Server capture ended: logged out** and records on without server data.
- No ping for 3 minutes (browser crashed, killed or offline): the server ends the capture at the last ping, with stop
  reason `browser-lost`. A capture that never got a ping is ended only by the hard limit.
- A recording recovered after a crash ends its capture at its last save (every 5 s), if that fits the capture (after
  its start, at most 3 minutes after the last ping); otherwise at the last ping. The save time comes from the
  browser's clock, so a browser clock that is off moves the end by the same amount.

## Limits

- **Max recording length** (30 minutes by default): the recorder shows the time left, warns 2 minutes before, and at
  the limit stops and saves as if the user pressed stop (reason `time-limit`; apps are exported as usual). When no
  server answers the start (no Upload URL, logged out, server down) the extension uses 30 minutes. If the browser
  doesn't stop, the server ends the capture 2 minutes after the limit, at the limit, with reason `time-limit`.
- **Size:** video is recorded at 1.5 Mbit/s (about 340 MB for 30 minutes). At 90% of **Max file size** the recorder
  stops and saves (reason `size-limit`), so the upload doesn't fail. No size guard when no server answered the start.
- **Storage per user** (10 GB): an upload, video or ZIP import that would take a user's recordings past it
  (every file in their recording folders, server data and exported apps included) gets HTTP 413 and "Delete some
  recordings first".
- **Recordings in progress** (3) and **started per minute** (10): a `recording-start` past either gets
  HTTP 429; the recording still works, without server data. A closed browser's captures stop on their own.
- **ZIP import:** besides **Max file size** per file, all files together may unpack to at most **Max total size of a
  ZIP import** (3 × Max file size), and the ZIP may hold at most **Max files in a ZIP import** (5000). A ZIP past
  either is refused and nothing is kept.
