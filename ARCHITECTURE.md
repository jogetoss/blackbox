# BlackBox architecture

How the extension (`chrome-extension/`) and the Joget plugin (`src/`) work inside. For installing and using them, see
[README.md](README.md) and [chrome-extension/README.md](chrome-extension/README.md).

## Docs in this repository

| Path | What |
|---|---|
| `README.md` | Plugin: build, install, use, settings |
| `e2e/README.md` | Browser tests of the extension (Playwright, its own Chromium) against a running Joget |
| `chrome-extension/README.md` | Extension: install, settings, record, recordings, replay |
| `ARCHITECTURE.md` | This file: internals, API, permissions, storage |
| `docs/` (git-ignored) | Working notes: demo videos and slides (`DEMO-VIDEOS.md`, `SLIDES-*.md`, `demo-app/`), and the session logs in `docs/sessions/<date>-<topic>.md` (what each work session did and what is next; a resumed session reads its own) |

## Upload endpoint (API)

Any server can receive recordings by implementing this at the extension's Upload URL. Requests carry the browser's
cookies for that server (`withCredentials`), so the server sees the user's normal login; the extension never handles
passwords. The extension's own `Origin` (`chrome-extension://…`) is on every POST; web pages can't send that, so a
server can accept it in place of a CSRF token.

| Request | Body | Success response (JSON) |
|---|---|---|
| `GET ?action=me` | — | `{"user":{"name":"…"},"canUpload":true,"maxSizeMb":500}` (the plugin adds `sampleData` and `options`, see "App export") |
| `POST ?action=create&title=…&notes=…` | `session.json` (`application/json`) | `{"id":"…","url":"/path/to/view"}` — `url` optional |
| `POST ?action=video&id=<id>` | `recording.webm` (`video/webm`) | any JSON object |

Errors: an HTTP error status with `{"error":{"message":"…"}}` (the plugin adds `"code"`, the status as a string);
**401** means "not logged in". `url` may be a path; it is resolved against the Upload URL. The Recording page URL
setting, when set, takes priority over `url`.

The plugin serves this at `<context>/api/blackbox`. All its actions need a login (401 without one); an unknown action,
or a known one with the wrong method, gets 400 "Unknown action". Who may do what: see "Permissions".

| Action | Method | Who | Notes |
|---|---|---|---|
| `me` | GET | anyone logged in | Also `user.username`, `options` (see "App export") and `sampleData` (see "Compatibility") |
| `list` | GET | | The recordings the user can view, newest first; server data hidden where not allowed |
| `create` | POST | uploaders | |
| `video` | POST | the recording's uploader | Once; 404 for others |
| `import` | POST | uploaders | A recording ZIP as the raw body (see "Storage" for its limits) |
| `meta`, `session`, `video` (GET, HTTP Range) | GET | viewers | |
| `download&format=zip\|html` | GET | viewers | Server data only for those allowed; the ZIP answers 409 while the Glowroot data is built |
| `delete` | POST | viewers | |
| `systemlog`, `serverinfo`, `apps`, `appfile&file=`, `traces`, `trace&file=`, `gauges`, `glowroot[&node=]`, `trace-retry[&keys=]` | GET (`trace-retry`: POST) | server data | 403 for viewers without it. `glowroot`: node statuses, or that node's ZIP. `trace-retry`: 429 within 20 s |
| `recording-start` | POST | anyone logged in | Starts the capture (see "Server capture"); 400 for an invalid id, 409 for another user's id |
| `recording-stop`, `app-export` | POST, GET | the capture's owner | 404 for anyone else |
| `recording-ping[&lastSavedAt=&videoBytes=]` | POST | the capture's owner | The recorder's heartbeat (see "Server capture"); 404 for anyone else |

Recordings the user can't view answer 404 (not 403), so ids of other people's recordings can't be probed. A busy server
(see "Storage") answers 503; a CSRF failure 403.

- The API lives at `/api/*` because Spring Security runs there (the logged-in user is known), but Joget's
  `JsonResponseFilter` does not: on `/web/json/*` it rejects requests whose `Referer` is not Joget, and the extension's
  requests have none. The filter answers the request itself, so it never reaches Joget's API Builder servlet.
- CSRF: POSTs are accepted from a `chrome-extension://` `Origin` (no web page can send that) or with the
  CSRF token header (named by `SecurityUtil.getCsrfTokenName()`, `OWASP-CSRFTOKEN` by default; the pages get name and
  value from `/web/json/directory/user/csrfToken`). CSRFGuard itself only
  covers `/web/*`.
- Uploads are raw request bodies (not multipart): `create` takes `session.json` (title and notes as query
  parameters), then `video` takes `recording.webm`.

### Compatibility

Plugin and extension 8.2.1 call the capture summary `systemLog`. The plugin never writes that name
(`RecordingStore.LEGACY_CAPTURE`) but reads it:

- `meta.json`: `readMeta` moves `systemLog` to `serverCapture`; the next change saves the new name.
- `create` takes the capture id from `recording.serverCapture`, else `recording.systemLog` (the file is kept as is).
- `recording-start` answers under both `serverCapture` and `systemLog` (the 8.2.1 extension reads its slow threshold
  there); `me.sampleData: {enabled, real}` repeats `options.sample` (both) for that extension's upload form.
- The extension reads `systemLog` in recordings and start answers from 8.2.1.
- Plugin 8.2.1 doesn't read `recording.serverCapture`, so recordings from this extension get no server data there.

## Permissions

`BlackBoxPlugin.Access` decides, per request; Joget admins may always do everything. The settings are listed in
README "Who sees what".

| Right | Who |
|---|---|
| Upload (`canUpload`): `create`, `video`, `import` | **Who can upload** (empty = any logged-in user). Only uploads from the browser; the capture and the quick settings don't use it |
| View and delete (`canView`, `canDelete`) | The recording's uploader (`meta.owner`) |
| Server data of a recording (`canViewServerData(meta)`) | **Who can capture other users' server data**, or the uploader, so every viewer |
| Server data in general (`canCaptureServerData()`, no uploader rule) | **Who can capture other users' server data**: keeping a ZIP's server data on import, capturing all other server log lines |
| Have an app exported (`canExportApp`) | The app's own admins, or **Who can export apps they don't administer** for apps Joget lets them access (see "App export") |

- Server data in `meta.json` (`serverCapture`, `serverData`, `apps`) is removed by `hideServerData` for users without
  `canViewServerData(meta)`, in `list`, `meta` and `download&format=html` (whose page carries the meta as JSON). The ZIP keeps only a
  fixed list of meta keys (`RecordingZip.META_KEYS`).
- `import` keeps a ZIP's server data only with `canCaptureServerData()`, since a ZIP can hold another user's recording.
  When it drops some, the response (not `meta.json`) has `serverDataSkipped: true`, and the list page says so instead
  of opening the copy.
- The popup and upload form offer only what `me.options` allows (`quickSettings`, see "App export"); the server
  checks every choice again.

## Storage

One folder per recording under `<wflow>/blackbox/<id>/`:

- always: `meta.json`, `session.json`, `.meta.lock`; `recording.webm` once the video is uploaded;
- from the capture: `system-log.json`, `server-info.json`, and (when their settings are on) `traces/`,
  `gauges/` (server load) and `glowroot/` (Glowroot data); a ZIP import puts its traces in `traces/import.jsonl`;
- `app/` with the exported apps (`apps.json` + `<appId>-<version>.jwa`; `apps.json` also holds the sample's
  `idMap`), exported at stop, or at upload when there is no usable export from the stop.

Captures waiting for an upload are in `<wflow>/blackbox/pending/<id>/`, trace retry requests in
`<wflow>/blackbox/retry/`. No database tables. Folders on a shared `wflow` directory work for clustered nodes;
deleting a recording removes its folder.

### Test timing override

`<wflow>/blackbox/liveness-test.json` (optional, for tests; not a plugin setting, admins don't see it) may set
`pingMs`, `lostMs`, `checkMs` and `minLimitMs` in ms. `LivenessKnobs` reads it on every call (no cache, no restart), and
a missing or invalid file, or a value below 1000, gives the default. `minLimitMs` only lowers the hard limit (see
"Hard limit"), never raises it. While it sets a value, a warning is logged (at most
once a minute). It is no recording folder (`RecordingStore.list` and `readMeta` need a valid id) and the capture sweep
only looks inside `pending/`, so both skip it. On j8213 the host path is `~/Joget/docker/wflow8/blackbox/liveness-test.json`.
With virtual hosts each profile has its own file (`<wflow>/app_profiles/<profile>/blackbox/`): the lost-browser check
uses each profile's `lostMs` for that profile's captures, and runs every `checkMs` of the shortest one any profile sets.

- `meta.json` changes go through `RecordingStore.modifyMeta`: the latest copy is changed and saved under a per-recording
  lock (in the JVM, plus a file lock on `.meta.lock` for nodes sharing the folder, where the file system supports it).
  `updateMeta` replaces only the keys it is given. So the trace and Glowroot refreshes, the video upload and the
  upload's own changes never put back an older copy (`writeVideo` also checks "already has a video" under the lock).
- `session.json` (in `create`, in `import`, and the `recording-stop` body) and an imported `system-log.json` are never
  held whole in memory: they are copied to disk (counting bytes against **Max file size**, decompressed for a ZIP),
  then checked and read as a stream (`SessionFile` on `JsonStream`, a small pull parser): the check validates the
  whole document but builds only `recording`, `summary` and `tool`; readers (app URLs, hits, post data) build one
  entry at a time and only the fields they need. Limits: an entry at most 16 MB, URLs kept up to 8 KB, post data up
  to 64 KB each and 16 MB in all, 200,000 hits. Measured: a 395 MB session.json is stored and read in a 128 MB heap. At most 2 checks run at once; others wait up to 30 s,
  then get 503.
- ZIP import: the ZIP may be at most 2 × **Max file size** + 64 MB; one with more than 1000 traces is refused. Server data other than
  the apps is taken only when the ZIP has `system-log.json`. The rest is streamed too: Glowroot packages, `.jwa` files
  and trace pages are copied entry to file; `gauges.json` and `traces.json` are read as streams and written line by
  line (`GaugeStore.write(Path, Path)`, `importTraceEntries`).
- Real uploaded files in sample data are copied into the `.jwa` from disk at `addTo` (`Table.fileSources`), and
  `TraceStore.moveFolder` appends index files as streams.
- Read whole on purpose, all small or bounded: `meta.json` and status files, ZIP `meta.json` / `apps.json` /
  `server-info.json` (64 KB to 1 MB), Glowroot's JSON replies (20 MB cap), the app definition text
  used for masking, the stop's copy of the log lines (`ServerCaptureStore.writeLines`) and the system log merge at
  upload (each node's file capped by **Max size of system logs per node per recording**; it sorts all nodes' lines).

The extension's storage: see "Extension storage and messages".

## Server capture

The capture collects all server data of a recording: log lines and requests (below), traces, server load, System
Info and the app export at stop (their own sections).

1. When recording starts, the extension calls `recording-start` with a random UUID (lower case). Every recording gets
   a capture: the tab's log lines and requests and the System Info snapshot; traces (`allowCaptureTraces`), server load
   (`allowCaptureGauges`) and Glowroot data (`allowCaptureGlowrootData`) for admins always, for others by their own
   settings, all off by default (unset = off); other threads' lines when the recorder asks (see "All server"). The quick settings can turn off `traces`,
   `gauges` and `glowrootData`. It answers 409 when the id belongs to another user's capture (the same user may start it again). The
   extension then adds `X-BlackBox-Id: <id>` to the recorded tab's requests to the Upload URL's server
   (`declarativeNetRequest` session rule). Requests made by a service worker carry no tab id, and Joget's PWA service
   worker re-fetches almost every userview request, so those are tagged too; while recording, other tabs on the same
   server can add lines.
2. `ServerCaptureFilter` marks the threads of those requests; an appender on log4j's root logger keeps their log lines (what
   reaches the root logger's appenders, the same as `joget.log`: info and up with Joget's own `log4j2.xml`, whose
   global `ThresholdFilter` drops debug everywhere) plus one entry per request (method, URL, status, time). They go to
   `<wflow>/blackbox/pending/<id>/system-log.<node>.jsonl`, flushed every 2 seconds. Each node stops at
   **Max size of system logs per node per recording** and writes a `truncated` entry.
3. Stop removes the rule; the recorder calls `recording-stop` with the app export's input (see "App export"), or the
   service worker calls it plainly when the recorder can't. The lines wait on the server until the recording is uploaded;
   `session.json` carries the id (`recording.serverCapture.id`), and `create` moves them into the recording as
   `system-log.json`. Only the user who started the capture can attach it. After the move, the pending folder keeps
   only `meta.json` pointing at the recording, for 24 hours (`FORWARD_MS`), so late traces still reach it. Captures
   never uploaded are deleted after the keep period (checked every hour).
   While it records, the recorder also sends a heartbeat (below).
4. The replay page loads `system-log.json` for allowed users and lines it up with the video. The plugin sends
   `X-BlackBox-Time` (server clock) on tagged responses; if the server clock differs from the browser's by more than
   250 ms, the viewer corrects it. The plugin's own request entries (not in `joget.log`) are only shown in a network
   request's details.

Not captured: work a request hands to another thread, and log lines that never reach the root logger's appenders.

### Heartbeat

The `recording-start` answer carries `pingMs` and `lostMs` (`LivenessKnobs`: 30 s and 3 min, or the test override
under "Storage"), so the extension has no timing constants of its own. Every `pingMs` the recorder window (not the
service worker, which Chrome suspends) POSTs `recording-ping` with `lastSavedAt` (its last crash-safety save) and
`videoBytes` (the video so far). `ServerCaptureStore.ping` checks the owner and, under the capture's lock, writes
`lastSeenAt` (and the two values) into the pending `meta.json`, then answers `{state: "on"}`. A capture that is
stopped or attached is not marked seen and answers `{state: "stopped", reason}` (`stopReason` from the meta, else
`stopped`); the recorder then stops through the normal stop with reason `server-stopped: <reason>`. A 401 (logged
out) stops the pings and shows "Server capture ended: logged out"; the recording goes on. Other errors (offline, 5xx)
are retried on the next tick. Chrome may run a hidden page's timers only about once a minute after 5 minutes, which
is why `lostMs` is 3 minutes. Pings keep the Joget session alive. An answer without `pingMs` means no pings.

### Lost browser

Every `checkMs` (30 s, read again before each run so the test override applies) each node runs
`ServerCapture.checkLost` on the server capture timer. It reads the meta from disk, not memory, since a ping can land
on any node, for every pending capture: those open on this node and every folder in `pending/` (so a capture no node
has open, after an idle close or a restart, still stops). The timer thread only has the profile it inherited when the
plugin started (`SetupManager.getBaseDirectory()` follows the thread's profile), so it doesn't use the request-side
store: `ServerCapture.checkDirs` lists the folders by path, `<wflow>/blackbox` without virtual hosts and
`<wflow>/app_profiles/<each profile>/blackbox` with them, and the check and the hourly sweep run on each one's
`pending/`. It doesn't switch the thread's profile (`HostManager.setCurrentProfile` is guarded in cloud installs). `ServerCaptureStore.stopIfLost` then, under the capture's
lock, stops a capture with no `stoppedAt` whose `lastSeenAt` is older than `lostMs`: `stoppedAt = lastSeenAt`,
`stopReason = "browser-lost"`, one info line in the log. The lock and the `stoppedAt` check make it happen once, on
one node. A capture with no `lastSeenAt` (never pinged) is never stopped this way. Each node then
closes its own copy of every stopped capture, so it stops writing there too after a normal stop sent to another node.
Gauges (`GaugeSampler`) and the Glowroot package (`GlowrootPackager`) read `stoppedAt` from the meta and follow it as
for a normal stop. A later ping answers `{state: "stopped", reason: "browser-lost"}`.

### Recovery stop time

When the extension recovers a crashed recording (`recoverPartials`, report.js), it POSTs a plain `recording-stop` (no
body, so no app export) for the partial meta's capture with `stoppedAt = lastSavedAt`, the last crash-safety save.
`ServerCaptureStore.stop(id, owner, clientStoppedAt, lostMs)` takes that time only between `startedAt` and
`lastSeenAt + lostMs` (both inclusive; with no `lastSeenAt`, between `startedAt` and now); otherwise it uses
`lastSeenAt`, or now when there is none. A stop without `stoppedAt` (the recorder's normal stop)
uses now. A capture that already has `stoppedAt` (for example stopped as `browser-lost`) keeps it; the
owner's stop still answers 200. The extension doesn't retry a stop that fails (offline, logged out): the lost-browser
check above closes the capture. Closing the recorder window sends a plain stop first, so that stop's time wins there.

**Upload stop time:** `ServerCaptureStore.attach` sets `stoppedAt` when the capture has none (nothing stopped it: the
upload came first). It uses `lastSeenAt` when the capture was pinged, else now. The value is set before
`system-log.json` is written, so that file and the meta carry the same `stoppedAt`. A capture pinged at T and uploaded
90 minutes later gets `stoppedAt = T`, not the upload time.

### Hard limit

Every recording has one, with or without server capture. The plugin setting `maxRecordingMinutes` (default 30) is read
at `recording-start` by `BlackBoxPlugin.maxRecordingMs`:
5 to 120 minutes (a value outside goes to the nearest bound; 120 is `GaugeSampler`'s 2 h cap), 30 when empty or not a
number, then lowered to the test override's `minLimitMs` when that is smaller. The settings page refuses values outside
5–120. The start answer carries it as `maxRecordingMs`, and `ServerCaptureStore.start` writes `maxUntil = startedAt +
maxRecordingMs` into the meta. The recorder shows the time left next to the timer, shows a warning (and puts it in the
window title, seen in the taskbar while minimized) in the last 2 minutes, and at the limit sends the background `stop`
with reason `time-limit`, the same path as the Stop button, so `stopAndSave` and the app export run. The limit is
counted from the recorder's own start time; a hidden window's timer may run late, up to about a minute. Server backup:
`checkLost` also calls `ServerCaptureStore.stopIfOverLimit`, which, under the lock, stops a capture with no `stoppedAt`
that is past `maxUntil + 2 min` (`LIMIT_GRACE_MS`): `stoppedAt = maxUntil`, `stopReason = "time-limit"`, one info line
in the log, pings or not. The lost-browser check runs first, so a capture that also stopped pinging keeps
`browser-lost` at its last ping.

Without a capture (no Upload URL, no host access, 401, network error, an error status): `startServerCapture` in
`background.js` keeps `status: "off"` with the reason, and the recorder uses the answer's `serverCapture.maxRecordingMs`
when there is one, else its `DEFAULT_LIMIT` of 30 minutes. There is no server backup then. A capture that never got a
ping is never stopped as `browser-lost`; only this backup ends it (`NoHeartbeatTest`).

**Size guard:** `recording-start` also answers `maxSizeMb` (`maxSizeMb()`, the upload limit; `withLiveness(started,
maxRecordingMs, maxSizeMb)`). The recorder creates its `MediaRecorder` with `videoBitsPerSecond: 1_500_000` (about 340 MB
for the default 30 minutes), adds up the chunk sizes in `ondataavailable`, and when `sizeLimitReached` (video bytes at
90% of `maxSizeMb`) sends the background `stop` with reason `size-limit`, the same path as `time-limit`. No
`maxSizeMb` in the answer (no capture): no size guard. The server has no part in it.

**All server** (`recording-start` with `allServer=true`, the popup's **All server log lines**; only for recorders in
**Who can capture other users' server data**, `canCaptureServerData()` without the uploader rule; stored as `allServer` in the
capture's meta): log lines not from the recording. While such a
capture is open on a node, the appender also writes every other thread's events
to it with `"other": true` (no `req`), including tagged requests of other recordings. Nodes write it once the capture
is open there (the starting node, and any node that served one of its requests). `summary` counts those lines as
`otherEntries`, not as entries or errors.

Log4j 2 is not visible to plugin bundles (OSGi exports neither `org.apache.logging.log4j` nor `org.apache.log4j`),
so `ServerCapture` reaches it through Joget's classloader by reflection; the appender is a `java.lang.reflect.Proxy`.

## Glowroot traces

- **Which requests:** only those Glowroot keeps a trace for, by its own settings: slower than its slow threshold
  (default 2000 ms, or a lower per-transaction override), or failed (exception, 5xx, and 4xx when its servlet plugin
  says so). BlackBox reads these settings and never changes them. Glowroot lists failed requests' traces only under
  `/backend/error/points` (`/backend/transaction/points` leaves out fast ones), so the lookup reads both.
- **When:** each node collects the traces of the requests it served, as soon as each request ends, not at stop or
  upload. Glowroot stores a trace just after the request, so the node waits 2, 5, 15, 30 and 60 seconds between tries
  (about 2, 7, 22, 52 and 112 s after the request), then every minute, for up to 10 minutes. A request still running
  past the threshold is listed as pending at once and refreshed every 3 minutes while it runs. A trace export over
  20 MB is not saved; only its summary is kept.
- **Clusters:** each node writes its own files to the shared folder. With **Glowroot address** empty, each node reads
  its own Glowroot (the local agent's port); when it is set, every node uses that address.
- **What is kept:** `traces/<node>.jsonl` (one line per state change: pending, saved, none, failed, with a reason) and
  `traces/<node>-<traceId>.html`, Glowroot's own export of the trace (a self-contained page, about 0.4–1 MB). At most
  100 traces per recording per node.
- **After the upload:** the files move into the recording with the system logs; traces of requests still running
  follow through the pending folder's forward (see "Server capture", step 3). The replay
  page shows "⏳" on the Traces tab while some are pending, refreshes every 5 seconds, and asks before a download that
  would miss them. A trace with no news for 10 minutes is shown as failed.
- **Glowroot not reachable:** the recording is saved as usual. With no Glowroot agent in the JVM, the request is
  marked failed at once ("not running"), judged by a 2000 ms / 5xx / exception rule. Other errors (connection refused,
  needs a login) keep retrying and end as failed ("Could not read Glowroot: …") after 10 minutes. When Glowroot's
  settings can't be read, the same fallback rule decides (4xx never counts). Only anonymous access is supported.
- **Retry:** only the node that served a request can read its trace, and the click may reach any node, so
  `trace-retry` marks the traces pending and writes `<wflow>/blackbox/retry/<recording>@<node>.json`; each node picks
  up its own every 20 seconds and tries again as above. A node that never answers (removed, or renamed) makes the trace
  failed again after 10 minutes. One retry per recording every 20 seconds on each node (an in-memory map; more often
  gets 429). Glowroot keeps traces for 14 days by default, so older ones may be gone. The newest state wins across
  nodes, so node clocks should be in sync.
- **Viewing a trace:** `trace&file=…` serves Glowroot's page with `Content-Security-Policy: sandbox allow-scripts
  allow-popups` and `Referrer-Policy: no-referrer`, so its script runs without access to Joget's cookies or pages. It
  loads Google Fonts and Font Awesome from the internet.

## Glowroot data

- **Tagging:** every recorded request (those with `X-BlackBox-Id`) gets the Glowroot transaction attribute
  `BlackBox recording = <id>` through Glowroot's agent API (`GlowrootTag`). Glowroot still decides which requests get
  a trace; the ones it keeps carry the tag, and Glowroot's own trace search can filter by it.
- **What is kept:** the tagged traces, the aggregates and gauges of all traffic on that node during the recording
  (plus one rollup bucket on each side, which Glowroot's charts show), and Glowroot's lookup tables. Aggregates and
  gauges can't be split per user.
- **Setting:** **Allow capturing Glowroot data** (`allowCaptureGlowrootData`) is off by default. Only Glowroot 0.14 is supported; other
  versions fail with the reason.
- **When:** 30 s after the end of the stop's minute (so 30–90 s after the stop, checked every 10 s; by then Glowroot
  has written that minute's aggregates), each node
  where the capture is open builds `glowroot/<node>.zip`: the node that took `recording-start` and every node that
  served a recorded request. It goes in the pending folder, or in the recording once uploaded. `glowroot/<node>.json`
  holds the state (waiting, building, saved, failed) and the reason. A capture never stopped fails after 3 hours.
  A `failed` status stays: if the capture reopens on the node later (a tagged request after the idle close),
  `watch` leaves it `failed` and starts no build.
- **Restart:** an in-memory list (`GlowrootPackager.WATCHED`) tracks what to build, so on start `recover` checks this
  node's status files left `waiting` or `building`: while the capture's pending `meta.json` exists, it is watched again
  and built as usual (blocks Glowroot overwrote meanwhile count as lost); in a recording whose capture is gone, it is
  marked failed. Otherwise the recording would count as building for good (Download ZIP 409, **Glowroot ⏳**). A build
  cut off by a stop or shutdown is not marked failed: the copy is opened with `DB_CLOSE_ON_EXIT=FALSE` (else H2's own
  shutdown hook closes it under the build), and an error while the plugin or JVM stops leaves `building`, so it is
  built again after the restart.
- **How:** `GlowrootData` copies `data.h2.db` (Glowroot keeps it locked; the copy is not), deletes every row outside
  the recording with Glowroot's own shaded H2 driver (`lib/glowroot-embedded-collector.jar`, H2 1.3), reads only the
  blocks the kept rows point to straight from the live `*.capped.db` ring files, writes them into new capped files of
  the same ring size, rewrites the capped ids, and compacts the copy. It runs on one low-priority thread inside Joget,
  with a small H2 cache; temporary files go to `java.io.tmpdir` and are deleted afterwards. A block Glowroot
  overwrites before it is read is left out, and the status says how many.
- **The ZIP:** `data/` (`data.h2.db` and the five capped files) and `README.txt` with the steps and the time range.
  It opens in Glowroot's offline viewer (`java -jar glowroot.jar` in a dist folder holding `data/`), which only reads:
  it starts no agent (no samples of its own JVM, no alerts or monitors) and schedules no reaper, so nothing expires.
  On its first start it writes Glowroot's default `admin.json` (127.0.0.1:4000) and `config.json`; none of the
  server's users, LDAP or SMTP are shipped. Checked on 0.14.2 and 0.14.4.
- **Disk space while building:** one copy of Glowroot's `data.h2.db` in Joget's temp folder (`java.io.tmpdir`,
  `/opt/tomcat/temp` in the Joget image), plus the new capped files holding only the kept blocks, all deleted when the
  package is done; the live capped files are read in place, not copied. The ZIP is written to the capture's folder.
  The rows are deleted in batches of 20,000, each committed, so the copy never grows past its starting
  size (measured: 127 MB stayed 127 MB, then 1.5 MB after compacting; one big delete made it 1.36 times larger first).
  Before each build the node checks for that size plus 10% (at least 64 MB) free; with less, the package fails with
  the reason, shown on the replay page's button and in Joget's log.
- **Downloads:** while a package is still being built, Download ZIP waits (the server answers 409). Rows are tagged
  **Glowroot ⏳**, and the list's batch **Download ZIP** is off while any selected row is building.

## Server load

Each node that serves a recording samples its own load once a second while the capture is open, from the JVM itself
(no Glowroot needed): heap used and max, GC time in that second, process and system CPU, Tomcat's busy request threads
(all `Catalina:type=ThreadPool` pools) and Joget's database pool (`setupDataSource`: active, max, waiting). Sampling
starts at `recording-start` (or the node's first recorded request) and ends at stop, or after 2 hours. Samples go to
`gauges/<node>.jsonl` and move into the recording on upload. Values a server can't give are left out.

## System Info snapshot

`recording-start` saves `server-info.json`, grouped like Glowroot's **JVM > Environment** page, then everything
**Settings > System Info** shows. The Info tab shows the sections whose changes can be tracked first: Recent changes,
Joget (with Process), Plugin; then Host, Cluster, Java, Database, DB pool, License, System properties.

`server-info.json` (and the ZIP) keeps everything; the viewer leaves out what another section already shows:

- the JVM start (in Recent changes) is shown in Joget;
- Host's **Node** when Cluster lists **Nodes**;
- License keys `platform.version`, `platform.db.profile`, `platform.db.jdbc.driver`;
- system properties shown in Joget, Host, Cluster or Java (`platform.jvm.total/max/processors/used`, `wflow.name`,
  `os.*`, `java.version`, `java.runtime.version`, `java.vm.name`, `java.vm.vendor`, `user.timezone`), and those a
  `-D` argument sets to the same value.

Sections:

- **Host**: hostname, node, processors, total and free RAM (`TotalPhysicalMemorySize`, `FreePhysicalMemorySize`: the
  OS's free memory, page cache not counted), OS. **Process**: id, start time, uptime.
- **Java**: version, vendor, VM, heap used/committed/max, every JVM argument (one per line).
- **Joget**: version, edition, application server, time zone (the JVM's zone id as set, with its offset:
  `GMT (UTC+0)`), Glowroot address and version.

Every time in these sections (process start, recent changes, plugin jar dates) is in UTC with a ` UTC` label, whatever
the server's time zone; so are **Uploaded** and **First uploaded** (`BB.fmtDate`, and its copy in `export.js`).
- **Recent changes** (first in the Info tab): app versions by `dateModified` (`AppDefinitionDao.findByVersion`, newest
  50; saving a form, list or userview updates it), top-level files of the wflow folder modified
  in the period (**Recent changes period**, `recentChangesDays`: default 7, at most 365) (not logs, not `servers.json` / `serversIP.properties` / `app_presence.json`, which Joget rewrites), and
  the JVM start when it is in the period. Every changed app version among those 50 goes into `server-info.json` as `recentApps`
  (`{id, version, published, name, modified}`); with the app export (at stop, or at upload without one)
  `ServerInfo.summarizeApps` narrows the shown **App versions** to the apps the recording used and other apps whose
  forms use one of their tables (`SampleData.tables`), one line per app: its latest change and how many versions
  changed, plus `shares <tables>` for the others.
- **Cluster**:
  - Saved: `ServerUtil` (this node's `wflow.name`, the registered nodes in the shared `servers.json`, the active
    nodes, each with its `serversIP.properties` address), `wflow.cluster` (set by the wflow-cluster agent), Tomcat's
    Engine `jvmRoute`, the requested session id's route suffix and **Session replication**: `On (Tomcat cluster)`
    only when a `Catalina:type=Cluster` MBean exists and this web app's `WebModule` MBean says `distributable`.
    Enterprise's `web.xml` has `<distributable/>`, Community's doesn't; without replication, logins need sticky
    sessions.
  - Shown: **Cluster** (`Yes: N nodes` or `No: one node`) and **Nodes**, the registered and active nodes in one list,
    each with its address and the recorded requests it served; with several nodes, `this node` (where the snapshot
    was taken) and `not active`. With no node list, this node alone (the wflow-cluster agent alone isn't a cluster).
  - **Node switches** (only when it happened): how often the next recorded request went to another node.
  - The served counts come from the system log: each node names its files by Joget's node name (`wflow.name`, else
    the hostname), and `attach` puts the `node` from the `system-log.<node>.jsonl` file name on each request entry.
- **Plugins**: one entry per jar in `app_plugins` (`PluginManager.getBaseDirectory()`): modified date and size, plus
  `not loaded` when no loaded plugin comes from it (`listOsgiPlugin` + `getJarFileName`): it failed to load or holds no
  plugin. A loaded jar not in the folder shows `Not in the plugin folder`. Plugin names and versions are not saved.
- **Database**: the current profile's `app_datasource-<profile>.properties`: profile, JDBC driver, URL and user, then
  every other key in the file. The password is left out, and `password=` values in the URL are masked.
- **DB pool** (`connectionPool`): the pool state at recording start (size, active, idle, waiting), then every Tomcat JDBC pool
  setting as the pool really uses it. A value that differs from the file shows `(file says x)`. **Ignored file keys**
  lists keys the pool has no setting for: Joget skips them silently, usually because of a typo (`maxactive`).
- **License**: Enterprise's license table (`SysInfo.getLicenseProperties()`), including the license signature, system
  key and MAC addresses. Missing on Community.
- **System properties**: System Info's system table (`SysInfo.getSystemProperties()`): every Java system property plus
  JVM memory and processors. On Community the same values are read directly.

## App export

**When:** at stop, with the upload as the fallback.

1. The recorder builds `session.json`, then POSTs `recording-stop` with a JSON body: its network entries (`t`, `url`,
   `type`, `status`, `failed`, `postData`, the `referer` header) and console errors, plus `apps=false` or
   `sampleData=none|masked|real` from `recording.options` (the popup's quick settings, `quick.js`).
2. `exportAtStop` writes the body and the capture's lines so far (`ServerCaptureStore.writeLines`; lines other nodes
   haven't flushed are missing) next to the capture, decides which apps the user may have exported (`exportJob`, on
   the request thread: the checks need the request and its user), and queues the export into the pending folder's
   `app/` on `ExportJobs` (one background thread). Each job runs as a `PluginThread` made on the request thread, so it
   has that request's profile and user: with virtual hosts on, the one thread serves every tenant.
3. The state is kept in the capture's `meta.json` as `appExport`: `off`, `exporting` (with `at` and `node`), `done`
   (with `saved` and `sampleData`) or `failed`. `ServerCaptureStore` changes `meta.json` under a per-capture lock (JVM
   and file lock, like `RecordingStore.modifyMeta`), so the job's `put` and the upload's `attach` never undo each other.
   A plugin reload or shutdown (`ExportJobs.stop`) saves the jobs that haven't started as `failed` (each job's dropped
   callback, run on a thread of its own that `stop` waits up to 5 s for, not on the stopping request's thread); the
   running one isn't interrupted: it goes on (or fails) in the old bundle and saves its own end. The JVM lock is per
   plugin copy; the file lock (`FileLocks`) is per JVM, so it also keeps the two copies apart during a reload: one
   the other copy holds (`OverlappingFileLockException`) is waited for up to 10 s, then the change fails (logged)
   instead of being saved unlocked. Only where the file system has no locks does the JVM lock alone hold.
   An `exporting` state is lost (`ExportJobs.lost`) when it is from this node and older than the JVM (a restart or
   crash lost its job; no `node`: counted as this node's), or older than 2 h (`MAX_EXPORT_MS`) on
   any node (its job died without saving its end). Its readers (`app-export`, `recording-stop`, `create`, the
   recording's `meta`) save it as `failed` with `reason: lost`, checked again under the meta lock
   (`ServerCaptureStore.markExportLost`, `RecordingStore.modifyMeta`) so a job's end saved meanwhile wins; a late end
   of that job still replaces it.
4. `recording-stop` waits up to 20 s (`STOP_SYNC_MS`, under common proxy timeouts such as nginx's 60 s) and answers
   the state, `exporting` if it runs on. The recorder then polls `GET app-export&id=<capture>` (owner only) every 3 s,
   up to 150 s, and saves a summary in `recording.serverCapture.appExport`: `{state, sampleData, apps}` (`apps` = how many).
5. `create` also waits up to 20 s while it is `exporting`:
   - `done`: `attach` moves `app/` into the recording and `meta.apps` takes `saved`; `off` exports nothing.
   - still `exporting`: `attach` leaves `app/` (`withApps` false), the recording gets `appExport: {state: exporting,
     capture}`, and `deliverStopExport` moves `app/` in and sets `apps` when the export ends (called by the job at its
     end and by `create` once more; it acts only while the recording still waits for that capture, or has it as
     `failed` with `reason: lost`, which a late end replaces). No second export. During a reload both plugin copies
     may run it at once: the second finds the apps moved and saves the same state. If that delivery fails (a lock
     timeout, a move error), the recording's `meta` tries it again while its capture says `done` or `failed` (not
     lost), until the capture expires (24 h).
6. Fallback: with no usable export at stop (no capture, a body that never arrived, `failed`, a capture of another
   user), `create` queues an export of the uploaded files (`apps` / `sampleData` from the upload: the form, or
   `recording.options` when automatic) and answers at once; `meta.appExport` says `exporting` until it ends, then
   `done` or `failed`. The replay page shows **Exporting apps…** and reloads when it is done; `meta` ends a lost
   export as `failed` (its capture's export lost or its pending folder gone; a capture whose meta can't be read just
   now is asked again next time), and the page stops asking after 30 min.

`me.options` (`quickSettings`) says what this user may get, for the popup and the upload form alike:
`serverCapture` (always true), `apps` (**Allow exporting the apps used…** + `Access.mayExportApps`: admin, an app admin
of some app, or **Who can export apps they don't administer**), `sample` (Capture apps data: `apps` + **Allow capturing apps data**), `mask`
(Mask data: `sample` + **Allow masking apps data**), `allServer` (`canCaptureServerData()`), `gauges` (`allowCaptureGauges`), `traces`
(`allowCaptureTraces`) and `glowrootData` (`allowCaptureGlowrootData`); for these three and the app export, apps data and
mask settings `Access.allows`: admins always, others when the setting is ticked (the export at stop and at upload checks the same). None needs the upload right. `recording-start`
takes `gauges=false`, `traces=false` and `glowrootData=false` (each turns its setting off) and `allServer=true` (opt-in;
every box in `quick.js` starts unticked).

The export (`exportApps`) runs `AppExport`: `hits` reads `session.json` (each request with its page:
a document's own URL, else its Referer; failed or 4xx/5xx = a problem; console errors = a problem on the page shown
then) and the system log (requests, 5xx or an `error` = a problem; error lines = a problem on their request; other threads skipped),
URL-decoded. `find` matches them against Joget's app URL patterns (`appOf`; app icons and screenshots name none). The
browser's requests name the apps; system log hits only add to those (it also gets other tabs' service worker
requests, which carry no tab id), unless the browser named none. App Center is dropped unless it had problems or was
alone. Apps rank by problems, then requests; the top `maxApps` (default 5, 0 = all) that the user may have exported
are exported with `AppService.exportApp` (reads the current request's parameters, which carry no `tablenames`, `usergroups` or
`exportplugins`, so no data, groups or plugins go in). `apps.json` records each app's state: `saved`, `missing`,
`denied`, `failed` or `skipped` (beyond the cap), all but `saved` with a `reason`, and `problems`; a failing sample hook leaves
`sample: {error}`. `meta.apps` keeps `[{id, name, version,
problems}]` for the list (hidden by `hideServerData`, see "Permissions"). `appfile` serves only names
matching `<id>-<n>.jwa`.

**Who may have an app exported** (`Access.canExportApp`, checked per app inside the export, so it holds at stop and at
upload; a refused app throws `AppExport.Denied`, is listed as `denied` and doesn't use up `maxApps`; its sample rows
are never read):

- Joget admins;
- the app's own admins, read as Joget 8.2's App Composer does (`EnhancedWorkflowUserManager.checkCustomAppAdmin`):
  `ROLE_ADMIN` (usernames) and `ROLE_ADMIN_GROUP` (group ids, the user's groups from `directoryManager`) in
  `app_src/<appId>/app.properties`, split on `;` or `,` (`AppAdmins`; read directly, since
  `AppDevUtil.getAppDevProperties` creates the folder when missing);
- users allowed by **Who can export apps they don't administer** (`canExportOtherApps`, empty = nobody), only for apps
  Joget lets them access: one of the app's userviews authorizes them by Joget's own check
  (`UserviewService.getUserviewSetting(...).isIsAuthorize()`: `permission` and `permission_rules`). An app with no
  userview is never theirs.

Seeing a recording's server data never grants an export; it only shows what was exported.

`viewer.js` repeats `appOf` to draw the Apps lane (segments from the same page signals, flips under 0.5 s merged), the
App filter and the App tab's problem lists, so the lane works for local recordings too.

### Apps data (`SampleData`)

`recording-stop` and `create` take `sampleData=none|masked|real` (missing: real; none without **Allow capturing apps
data**, admins excepted). `masked` also gives every record a new id (`IdMap(keep=false)`); `real` keeps the ids. `AppExport.save` calls an `AfterExport` hook per saved `.jwa`: `SampleData.build` with one
`Masker` (random HMAC key per export, so fakes are stable within a recording and differ between them) and one `IdMap` for all apps of
the recording, then `SampleData.addTo` rewrites the `.jwa` with `data/<table>.json` (`FormUtil.formRowSetToJson(rows,
true)`, the format `AppService.importFormData` reads) and `app_formuploads/<table>/<newId>/<file>`. Data comes from
`FormDataDao` (`load`, `find` with `e.customProperties.<col> IN (...)`, newest by `dateModified`) and
`FileUtil.getUploadPath`; activity ids are resolved with `WorkflowManager.getActivityById`. `apps.json` gets each app's
`sample` summary (mode, tables: rows, how picked, files, masked columns) and the recording's `idMap`.

`ProcessData` runs in the same hook: process ids from the recorded ids, the sample's old record ids and the process ids
in its rows (activity ids resolved with `getActivityById`), kept when the process definition id starts with
`<appId>#`; `getRunningProcessById`, `getProcessVariableList`, `getActivityList` (by `dateCreated`, oldest first) and
`getRunningActivityInfo` (assignees, accepted user). At most 20 processes per app and 100 activities per process
(`activitiesCut: true` when cut). Written to the app's `processes` in `apps.json`.

## Plugin parts

| Part | Job |
|---|---|
| `Activator` | Registers `BlackBoxPlugin`, `BlackBoxPageFilter`, `BlackBoxApiFilter` and `ServerCaptureFilter`; starts and stops `ServerCapture` (appender, timers, trace / gauge / Glowroot / export threads) |
| `BlackBoxPlugin` | System settings + API logic (actions above); its inner class `Access` holds the permission checks |
| `BlackBoxApiFilter` | Serves the API at `/api/blackbox?action=…` |
| `BlackBoxPageFilter` | Serves the static pages at `/blackbox/…` |
| `RecordingStore` | Folder storage, validation (JSON shape, WebM header, size limits, id format), `meta.json` locking, old `systemLog` names |
| `SessionFile` | Copies `session.json` / `system-log.json` to disk, checks them and reads them as streams |
| `JsonStream` | The small pull parser `SessionFile` uses |
| `RecordingZip` | `download&format=zip` and `import`: the recording as one ZIP and back |
| `RecordingHtml` | `download&format=html`: view.html with viewer.css/js inlined and display.js in `<head>`, the recording in `<script>` data blocks, `export.js` to open it |
| `ServerCaptureFilter` | Marks requests that carry `X-BlackBox-Id` (all URLs, before Spring Security) |
| `ServerCapture` | Root-logger appender, per-thread marking, pending files, flush/idle/cleanup timer |
| `ServerCaptureStore` | Pending capture folders: start, stop, the stop's copy of the lines (`writeLines`), state such as `appExport` (`put`), attach (merge nodes, sort by time, move traces, gauges, Glowroot data, `server-info.json` and `app/`), clean up |
| `TraceCollector` | Per node: picks the slow and failed recorded requests, finds their Glowroot trace (retries), saves it; Retry requests per node |
| `GlowrootClient` | Glowroot's JSON backend and trace export; finds the local agent's port |
| `GlowrootData`, `GlowrootPackager`, `GlowrootTag` | Glowroot data packages; tagging recorded requests |
| `ServerInfo` | System Info snapshot at recording-start (host, cluster, plugins, Java, Joget, DB, pool, license) |
| `AppExport` | Finds the apps in the recorded URLs, exports them (at stop, else at upload), lists them in `app/apps.json` |
| `AppAdmins` | An app's own admins from `app_src/<app>/app.properties`, as App Composer reads them |
| `ExportJobs` | The background thread app exports run on, so requests answer within about 20 s |
| `FileLocks` | The file lock around a `meta.json` change (both stores): across nodes and across plugin copies in one JVM |
| `SampleData` | Picks the sample rows of an exported app, masks / remaps them, adds them to the `.jwa` |
| `ProcessData` | The touched process instances of an exported app, read only, masked like the sample |
| `Masker` | Shape-keeping fake values, kept values, user pseudonyms, the old -> new record id map |
| `GaugeSampler` | Per node: samples heap, GC, CPU, busy threads and DB pool every second while a capture is open |
| `GaugeStore` | `gauges/` files: append, merge per node, move on upload, ZIP import |
| `TraceStore` | `traces/` files: append, merge (latest state per request), move on upload, summary of a trace |
| `ServerData` | The `serverData` summary in `meta.json` for the list (traces, peak load, Info, Glowroot state), made at upload and refreshed as traces arrive |
| `src/main/resources/static/blackbox/` | Pages: `list.html/js`, `view.html/js` (+ the shared viewer from the extension), `common.js` (API calls, CSRF token), `export.js` (opens a downloaded HTML file) |

## Extension parts

Manifest V3, Chrome 116 or later (`manifest.json`); the Upload URL's host access is an optional permission, asked
for in Settings or on the first upload.

| Part | Job |
|---|---|
| `popup.html/js` | Record the screen / a window / this tab (one row, in that order), Stop; gets the tab-capture stream id (the video for "this tab", the tab's sound for a window or the screen); Page sound and Microphone toggles; the server data note (`#serverNote`: no Upload URL, a tab on another server, or why the server isn't capturing); quick settings (from `me.options`, cached as `quickOffer`); links to Recordings and Settings |
| `recorder.html/js` | The recorder window (minimized while recording; asks before closing): shows the `desktopCapture` picker (window/screen) or uses the tab stream, mixes page sound and microphone into one track, records with `MediaRecorder`, saves progress every 5 s, sends `recording-ping` every `pingMs`, shows the time left and stops at `maxRecordingMs`, sends `recording-stop` with the app export's input, keeps the recording in IndexedDB (or downloads it when it can't), opens the Replay page |
| `background.js` | Service worker: owns the recording state, opens the recorder window, attaches the debugger (Network, Runtime, Log, Page), fetches response bodies (XHR, Fetch, Document, EventSource, Other; up to 1 MB, not binary), forwards events to the recorder; starts/stops the server capture and tags the tab's requests; REC badge (orange with the reason when the server capture is off); stops on debugger detach, tab closed or recorder closed; crash recovery |
| `viewer.html/js`, `viewer.css` | Replay UI shared by every replay page (the extension's, the plugin's, downloaded HTML, standalone): video + synced lists, timeline, request details, Features drawer |
| `recordings.html/js` | Recordings page: kept, on-disk and upload-history rows (100 at a time) with Browser issues; batch Upload, Download ZIP, Delete for ticked rows (Upload off when `me.canUpload` is false); Import ZIP; Link / Allow / Unlink folder; crash recovery on load |
| `actions.js` | Upload (with its form; `uploadNow` without it, `uploadMany` for a batch), Move to folder, Download ZIP and Delete of one recording; links in messages |
| `replay.js` | Replay page (`viewer.html?id=` or `?disk=`): loads the recording, its status bar, ⚠ Upload and the fix box, Move to folder / Download ZIP / Delete, Older / Newer; with `upload=server` it uploads and opens the server's copy |
| `report.js` | Builds `session.json`; turns a recording interrupted by a crash or a closed recorder into a normal one (`recovered`, stop reason "interrupted") and stops its server capture at the last save |
| `quick.js` | Quick settings (`chrome.storage.local` `quick`): `apps`, `sample`, `real` are the per-recording app export choices (`recording.options`, the export parameters); `allServer`, `gauges`, `traces` and `glowrootData` go to `recording-start` |
| `store.js` | Row list (kept, on disk: newest 50, upload history), badges, loading a recording, the linked folder |
| `server.js` | Calls the Upload endpoint with the browser's cookies; Upload URL and Recording page URL, host access, 15 s timeout on GETs |
| `zip.js` | Reads and writes recording ZIPs (no library: `CompressionStream` / `DecompressionStream`; top-level files only, no ZIP64) |
| `idb.js` | IndexedDB (see below) |
| `options.html/js` | Settings: Upload URL, Recording page URL, Theme, Text size, Open recordings on the server when you are logged in |
| `theme.js`, `ui.css` | `theme.js`: Theme and Text size (as `zoom`) for every extension page, cached in `localStorage`; also the content script on the Upload URL server's `<context>/blackbox/*` pages, registered by `background.js` while BlackBox has access to that server. `ui.css`: styles of the popup, Settings and recorder (the other pages use `viewer.css`) |
| `display.js` | Theme and Text size pickers in the header of the two pages without the extension (downloaded HTML, `dist/blackbox-viewer.html`), in place of `theme.js`: inlined in `<head>` so the saved choice applies before the page draws; the same values and `localStorage` keys (`bb-theme`, `bb-text-size`) as `theme.js`, shared by every file opened from disk. Copied into the plugin by the pom with `viewer.js`/`viewer.css` |
| `build-standalone.py` | Builds `dist/blackbox-viewer.html`: `viewer.css`, `viewer.js`, `zip.js` and `display.js` inlined, without storage, server and upload code; re-run after viewer changes |

## Extension storage and messages

**`chrome.storage.local`:** `uploadUrl`, `viewUrl` (Recording page URL), `theme`, `textSize`, `openOnServer` (on
unless turned off), `recordPageAudio`, `recordMic`, `quick` (quick settings), `quickOffer` (the server's last
`me.options`). **`chrome.storage.session`:** `rec` (`recIncognito` in incognito) = `{tabId, windowId, mode, startedAt?,
serverCapture?}`, the recording in progress (`startedAt` once a source is picked).

**Incognito:** the manifest sets `"incognito": "split"`, so an incognito window gets its own service worker and
extension pages with incognito's cookies: calls to the Upload URL use that window's login. The two copies can't
message each other. IndexedDB belongs to each copy (incognito's goes when incognito closes). Shared by both copies
(checked by `e2e/tests/incognito.spec.js`): `chrome.storage.local`, `chrome.storage.session`, the session rules and
the toolbar badge. So the recording in progress is `rec`, or `recIncognito` in the incognito copy (`REC_KEY` in
`report.js`); the capture rule is id 1, or 2 in incognito; and clearing the badge keeps it while the other copy
records. A normal and an incognito recording can run at the same time. `chrome.extension.inIncognitoContext` also
shows the note in the popup and on the Recordings page.

**IndexedDB** `blackbox` (version 4):

- `recordings`: kept recordings `{id, savedAt, video, report, uploaded, imported?, recovered?}`. Only the newest 10
  (`MAX_KEPT`) stay: saving one drops older ones; an uploaded one becomes a `history` row, one never uploaded is gone.
- `meta`: the linked folder handle (`folder`) and upload marks for recordings on disk (`uploaded:<id>`).
- `history`: small rows (no video, no logs) of uploaded recordings whose local copy is gone.
- `partial`: crash safety while recording: `<id>:meta`, video parts `<id>:v:<n>` and log parts `<id>:l:<n>`, cleared
  once the recording is saved in full.

**Messages** (`chrome.runtime.sendMessage` with a `target`):

- to `background`: `get-state`, `start {tabId, mode, streamId}` (popup), `source-picked {startedAt}` (recorder; the
  answer has the tab and `serverCapture`), `stop {reason}` (popup, or the recorder: `user`, `sharing-stopped`).
- to `recorder`: `cdp {method, params, t, body}` (debugger events), `stop {reason}`; it answers `{id, stopSent}`.

**Stop:**

1. A stop comes from the popup, the recorder, the debugger detaching, or the tab closing. `background.js` detaches
   the debugger, stops tagging and sends `stop` to the recorder.
2. The recorder builds `session.json`, POSTs `recording-stop` with the app export's input and polls `app-export`
   (see "App export"), then saves the recording in IndexedDB. If it can't, it downloads `recording.webm` and
   `session.json` to `Downloads/blackbox/<id>/`.
3. If the recorder didn't send the stop (`stopSent` false: no answer, or not HTTP 2xx such as 401, 413 or 5xx; or
   the recorder is gone), `background.js` sends a plain `recording-stop`. A time-out still counts as sent.
4. The recorder opens `viewer.html?id=<id>&upload=server` (without `upload` when **Open recordings on the server
   when you are logged in** is off). `replay.js` then uploads it when the user is logged in to the server and opens the server's copy;
   otherwise it replays it in the extension.

**Crash recovery:** closing the recorder window stops the recording and runs `recoverPartials()`, as do browser
startup and the Recordings page. It turns `partial` parts (except the recording still running) into a kept recording
with `recovered: true`, then sends `recording-stop` with the last save time for its server capture (see "Recovery
stop time").

The recorder is a window, not an offscreen document: a `chooseDesktopMedia` stream id can only be opened by the page
that showed the picker.

**Sound.** Page sound comes from `chrome.tabCapture` on the started tab in every mode, because the picker shares
system audio only for a screen on Windows and ChromeOS (Linux and macOS get none, and windows never have sound).
When the picker does share system audio, it replaces the tab's sound. Details:

- A tab stream id expires within seconds, so for a window or the screen the recorder opens the tab's sound before it
  shows the picker.
- Capturing a tab's sound mutes the tab, so the recorder plays it back through an `AudioContext`.
- `MediaRecorder` records only the first sound track: page sound and microphone are mixed into one track (Opus).
- The microphone is opened after the picked source, with `getUserMedia({ audio: true })` in the recorder window (the
  permission belongs to the extension's origin). A refused microphone or missing page sound doesn't stop the
  recording. `session.json` says what it has: `recording.audio` = `{ page, pageSource: "tab" | "system", mic,
  message }`, where `message` says why a sound is missing.
- With **Page sound** off, neither the tab's sound nor system audio is requested.
