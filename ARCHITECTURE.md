# BlackBox architecture

How the extension (`chrome-extension/`) and the Joget plugin (`src/`) work inside. For installing and using them, see
[README.md](README.md), the [guide](guide/) and [chrome-extension/README.md](chrome-extension/README.md).

## Docs in this repository

| Path | What |
|---|---|
| `README.md` | Plugin: features, quick start, compatibility, build |
| `guide/` | Plugin guide: settings, security, server data, liveness and limits, apps, storage |
| `UPGRADE.md` | Breaking changes between releases |
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
**401** means "not logged in". `url` may be a path; it is resolved against the Upload URL, and kept only when it is http(s) on the
Upload URL's own host and port (`Server.resolve`; otherwise no link). The Recording page URL
setting, when set, takes priority over `url`.

The plugin serves this at `<context>/api/blackbox`. All its actions need a login (401 without one), except the ticket
and secret requests under "Armed start"; an unknown action,
or a known one with the wrong method, gets 400 "Unknown action". Who may do what: see "Permissions".

| Action | Method | Who | Notes |
|---|---|---|---|
| `me` | GET | anyone logged in | Also `user.username`, `options` (see "App export") and `sampleData` (see "Compatibility") |
| `list` | GET | | The recordings the user can view, newest first, each with its `url` (the viewer page); server data hidden where not allowed |
| `create` | POST | uploaders | |
| `video` | POST | the recording's uploader | Once; 404 for others |
| `import` | POST | uploaders | A recording ZIP as the raw body (see "Storage" for its limits) |
| `meta`, `session`, `video` (GET, HTTP Range) | GET | viewers | |
| `download&format=zip\|html` | GET | viewers | Server data only for those allowed; the ZIP answers 409 while the Glowroot data is built |
| `delete` | POST | viewers | |
| `systemlog`, `serverinfo`, `apps`, `appfile&file=`, `traces`, `trace&file=`, `gauges`, `glowroot[&node=]`, `trace-retry[&keys=]` | GET (`trace-retry`: POST) | server data | 403 for viewers without it. `glowroot`: node statuses, or that node's ZIP. `trace-retry`: 429 within 20 s |
| `recording-arm` | POST | uploaders, with **Allow recordings that start before login** | `{ticket, expiresAt, ttlMs}`; takes the same parameters as `recording-start` (see "Armed start") |
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
[Who sees what](guide/security.md#who-sees-what).

| Right | Who |
|---|---|
| Upload (`canUpload`): `create`, `video`, `import` | **Who can upload** (default Logged In User; empty = any logged-in user). Only uploads from the browser; the capture and the quick settings don't use it |
| View and delete (`canView`, `canDelete`) | The recording's uploader (`meta.owner`) |
| Server data of a recording (`canViewServerData(meta)`) | **Who can capture other users' server data**, or the uploader, so every viewer |
| Server data in general (`canCaptureServerData()`, no uploader rule) | **Who can capture other users' server data**: keeping a ZIP's server data on import, capturing all other server log lines |
| Have an app exported (`canExportApp`) | The app's own admins, or **Who can export apps they don't administer** for apps Joget lets them access (see "App export") |
| Apps data of an exported app (`isAppAdmin` or `allowOtherAppsData`) | The app's own admins; others only with **Allow apps data from apps they don't administer**. Otherwise the `.jwa` has no rows or process data and `apps[].sample.skipped` says why |
| Record credentials unmasked (`me.options.credentials`) | Admins, or **Allow recording browser secrets unmasked**; enforced in the extension only (`mask.js`) |
| Record System Info secrets unmasked (`me.options.systemSecrets`) | Admins, or **Allow recording System Info secrets unmasked**; enforced on the server at `recording-start` (`systemSecrets=true`, `Secrets.none()`) |
| Record license secrets unmasked (`me.options.license`) | Admins, or **Allow recording license secrets unmasked**; enforced on the server at `recording-start` (`license=true`, `ServerInfo.LICENSE_SECRETS`) |

- Server data in `meta.json` (`serverCapture`, `serverData`, `apps`) is removed by `hideServerData` for users without
  `canViewServerData(meta)`, in `list`, `meta` and `download&format=html` (whose page carries the meta as JSON). The ZIP keeps only a
  fixed list of meta keys (`RecordingZip.META_KEYS`).
- `import` keeps a ZIP's server data only with `canCaptureServerData()`, since a ZIP can hold another user's recording.
  When it drops some, the response (not `meta.json`) has `serverDataSkipped: true`, and the list page says so instead
  of opening the copy.
- The popup and upload form offer only what `me.options` allows (`quickSettings`, see "App export"); the server
  checks every choice again.

**Extension POSTs** skip the CSRF token only when `fromAllowedExtension`: the `Origin` is `chrome-extension://<id>`
with the id in `allowedExtensionIds` (never saved: `OFFICIAL_EXTENSION_ID`, fixed by the manifest's `key`; empty: any).

**Limits** (non-admins; 0 = no limit): `overQuota` (`userQuotaMb`, `RecordingStore.usedBytes` + the upload's
Content-Length, 413) on create, video and import; `recording-start` checks `StartLimiter` (`captureStartsPerMinute`,
in memory per node, 429) and `ServerCaptureStore.openCount` (`maxOpenCaptures`, 429). `RecordingZip.importZip` reads
through `BudgetZip`, whose entry streams share one budget of uncompressed bytes (`importMaxTotalMb`, empty = 3 ×
`maxSizeMb`), and refuses more than `importMaxEntries` entries (empty = 5000). Both exist because a ZIP of many entries just under
the per-file limit, or of entries sharing one compressed block, would otherwise unpack to far more than its own size.

**HTTPS-only uploads** (`allowHttpUpload` off): `handle()` refuses the POST actions in `UPLOAD_ACTIONS` (create,
video, import, recording-arm/start/stop/ping, and the ticket and secret requests) with 403 unless `secureOrLocal`: `isSecure()`, `X-Forwarded-Proto: https`,
or a server name of `localhost`/`127.0.0.1`/`::1`. The extension shows the message (capture off, upload problem).

## Storage

One folder per recording under `<wflow>/blackbox/<id>/`:

- always: `meta.json`, `session.json`, `.meta.lock`; `recording.webm` once the video is uploaded;
- from the capture: `system-log.json`, `server-info.json`, and (when their settings are on) `traces/`,
  `gauges/` (server load) and `glowroot/` (Glowroot data); a ZIP import puts its traces in `traces/import.jsonl`;
- `app/` with the exported apps (`apps.json` + `<appId>-<version>.jwa`; `apps.json` also holds the sample's
  `idMap`), exported at stop, or at upload when there is no usable export from the stop.

Captures waiting for an upload are in `<wflow>/blackbox/pending/<id>/`, trace retry requests in
`<wflow>/blackbox/retry/`, unused arm tickets in `<wflow>/blackbox/tickets/<sha256 of the ticket>.json` (owner,
expiry, resolved capture options; deleted when used, expired ones by the hourly sweep). No database tables. Folders on a shared `wflow` directory work for clustered nodes;
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
   250 ms, the viewer corrects it. A cached response keeps the header of when it was first fetched, so only fresh
   responses count. The plugin's own request entries (not in `joget.log`) are only shown in a network
   request's details.

Not captured: work a request hands to another thread, and log lines that never reach the root logger's appenders.

### Armed start

For recordings that start logged out (design: `docs/design/anonymous-server-capture.md`). Setting
`allowArmedRecordings` (off by default).

1. `recording-arm` (logged in, `canUpload`): non-admins pass `StartLimiter` and have at most `ArmTickets.MAX_PER_USER`
   (3) unexpired tickets. `captureOptions` resolves the start parameters against the user's rights now (plus
   `admin`) and `ArmTickets.arm` stores them with the owner under the ticket's SHA-256 hash, valid for the setting `armTicketMinutes` (**Prepared recording expiry**,
   `ArmTickets.ttlMs`: 1 to 60 minutes, a value outside goes to the nearest bound, 15 when empty or not a number). The
   expiry is saved in the ticket, so a later change of the setting doesn't move it.
   The ticket is 32 random bytes, base64url (43 characters).
2. `recording-start` with `Authorization: BlackBox-Ticket <ticket>`: `handle()` takes this path before the login
   check, whatever the session. It needs `fromAllowedExtension` (403 otherwise) and the HTTPS rule. The setting must
   still be on. `ArmTickets.use` reads and deletes the file (only one `Files.delete` succeeds, so concurrent uses get
   one capture); expired or unknown: 401. Non-admin owners pass `maxOpenCaptures`. `openCapture` then starts the
   capture as the owner with the stored options, saves `secretHash` (SHA-256 of a new random `captureSecret`) and
   `armed: true` in the pending meta, and answers `captureSecret` with the usual start answer.
3. `recording-ping` and `recording-stop` with `X-BlackBox-Capture-Secret`: same Origin and HTTPS checks, then
   `ServerCaptureStore.ownerBySecret` (constant-time hash compare) gives the owner, and the normal ping or stop runs
   as that owner (404 for a wrong secret). The stop never exports apps (no user to check `canExportApp` against); the
   upload exports them. A request with the header always takes this path, so a login as another user during the
   recording doesn't matter.
4. `attach` (the upload, as the owner) removes `secretHash`.

`me.options.arm` is the setting and `canUpload`; the popup shows its link from it. The extension keeps the ticket in
`chrome.storage.session` (`armedTicket`: ticket, Upload URL, expiry; shared by the normal and incognito copies, so
the next recording in either uses it), and
`startServerCapture` uses and removes it when the Upload URL matches. The `captureSecret` stays in the capture
state, the recorder's meta and the crash-safety partial meta; `buildReport` copies only `status`, `id`, `server`,
`message`, so it never reaches `session.json`.

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
`lastSeenAt`, or now when there is none. `lastSavedAt` is the browser's clock and the bounds are the server's, with
no correction for clock skew: a skewed browser clock moves the stop time by the skew, and a skew that puts it outside
the bounds gives `lastSeenAt` instead. A stop without `stoppedAt` (the recorder's normal stop)
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
is open there (the starting node, and any node that served one of its requests). With virtual hosts, only lines of
HTTP requests to the capture's own profile: `recording-start` saves the request's profile in the meta (`profile`);
`ServerCaptureFilter` (every request) sets a BlackBox thread-local to the request's profile
(`DynamicDataSourceManager.getCurrentProfile()`, `ServerCapture.enterRequest`), and the appender compares the two.
Joget's own profile thread-local is inheritable, and shared background threads (the FileManager temp cleaner, pool
workers) keep a borrowed profile while working for every profile, so lines outside a request (no BlackBox profile) are
left out, as is every line for a capture without `profile` (started by 8.2.2). `summary` counts those lines as
`otherEntries`, not as entries or errors.

Log4j 2 is not visible to plugin bundles (OSGi exports neither `org.apache.logging.log4j` nor `org.apache.log4j`),
so `ServerCapture` reaches it through Joget's classloader by reflection; the appender is a `java.lang.reflect.Proxy`.
A log4j reconfiguration replaces the root logger's config and drops the appender, so each flush checks and adds it
again. The appender never throws into the application's logging call, and a failed write (pending folder gone, disk
full) is not logged, since it runs inside a logging call.

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
- **Matching:** a Glowroot trace is a request's only when its header's `attributes` hold `BlackBox recording` = the
  capture id (`TraceStore.tagged`), besides the same method, path and start ±2 s. A trace that matches all but the tag
  ends the lookup as "none" with that reason. Each entry keeps its `capture` id.
- **Glowroot's API:** a trace can be listed a moment before its header can be read (404): the lookup skips it and
  tries again later instead of failing. The queries of a trace with no SQL answer 404 (its header says whether there
  are any). Glowroot's duration is a little longer than the filter measures, and its start a little earlier.
- **Slow threshold:** a Glowroot slow threshold below 100 ms reads as "trace everything", so the lists then use the
  default 2000 ms (`ServerData.slowMs`).
- **Finding Glowroot:** `GlowrootClient` reads the address from `admin.json` next to the `-javaagent` jar:
  `agent-<host>/admin.json` when Glowroot keeps one folder per server (multi-directory mode), else `admin.json`. The
  agent version comes from the agent jar's manifest, as Glowroot's Environment page shows it. `glowroot.jar` is on the
  bootstrap or system class path, never in the bundle, so `GlowrootTag` looks the agent API up through the bootstrap,
  system and context loaders; an older Glowroot without `setTransactionSlowThreshold(long, TimeUnit)` keeps only
  slow or failed traces.
- **Other users' traces** (`otherTraces`): `TraceCollector.watchOthers` on each node the capture opened on; 30 s
  after `stoppedAt`, `collectOthers` reads the Web and Background points of [start, stop + 30 s], skips the
  capture's own (tagged) and those started after the stop, and writes up to `MAX_OTHERS` (200) entries `other: true`
  with `user` (Glowroot's, empty unless its Servlet plugin's `sessionUserAttribute` is set, e.g.
  `SPRING_SECURITY_CONTEXT.authentication.name`), `type`, the summary and the export, keyed `<node>-other-<traceId>`. It runs on its own thread
  ("BlackBox other traces"), so it never delays the heartbeat, lookups or retries, and stops calling Glowroot after
  `OTHERS_BUDGET_MS` (5 min), apart from the trace already being copied; past either limit the rest of the list
  are only counted, and once the time is up the Background list is not read. One note per node (`<node>-other-note`)
  says where it stands: "pending" from when the capture opens on the node (or is recovered) until the copy, rewritten
  when older than 2 minutes, on each tick and before each trace of a copy (the replay page keeps polling while it is
  pending), then "none" with how many were copied and why any were left out, or "failed". Each 10 s tick copies at
  most one capture. A note can still turn stale (`TraceStore.STALE_MS`, 10 min) only when the Glowroot calls
  between two refreshes take about 8 minutes together (trace reads and list reads; the note may already be 2 minutes old
  when they start): each read times out after 30 s, but a slow stream of a large export or list can take longer. The folder is looked up for each trace, as the upload
  may move the capture to its recording meanwhile. The capture's `meta.json` has `othersPending-<node>: true` from
  the watch until the copy ends, so after a restart `recoverOthers` watches it again. Copied once: no retry. The viewer
  lists them only under the filter "Other users' traces" and never matches them to a network request;
  `ServerData.traces` counts them apart (`others`). `GlowrootData.slice(..., allTraces)` then keeps every trace
  that overlapped the recording (`CAPTURE_TIME >= from` and `START_TIME <= to`).
- **Retry:** only the node that served a request can read its trace, and the click may reach any node, so
  `trace-retry` marks the traces pending and writes `<wflow>/blackbox/retry/<recording>@<node>.json`; each node picks
  up its own every 20 seconds and tries again as above. A node that never answers (removed, or renamed) makes the trace
  failed again after 10 minutes. One retry per recording every 20 seconds on each node (an in-memory map; more often
  gets 429). Only `TraceCollector.retryable` entries: not imported (`imported: true`, set by the ZIP import), not
  other users' traces, and with a `capture` id. A lookup with no known end searches at most 10 minutes from the
  request's start. Glowroot keeps traces for 14 days by default, so older ones may be gone. The newest state wins across
  nodes, so node clocks should be in sync.
- **Virtual hosts:** the trace and server load threads ("BlackBox traces", "BlackBox other traces", "BlackBox Glowroot
  data", "BlackBox gauges") only have the profile they inherited when the plugin started, and Joget picks the base
  folder from the thread's profile. So each watched capture (and each request whose trace is looked up) keeps the
  blackbox folder of the request that opened it (`ProfileDir.current()`, on the request's thread), and its background
  work runs in that folder (`ProfileDir.in`, a BlackBox thread-local): `ServerCapture.store()`, `BlackBoxPlugin.store()`
  and the retry folder resolve through it. Work that belongs to no request goes through every profile's folder by path
  (`ServerCapture.checkDirs`): restart recovery of other users' traces and Glowroot data, and the trace retry
  requests. Like the liveness check, it doesn't switch the thread's Joget profile (`HostManager.setCurrentProfile` is
  guarded in cloud installs). Glowroot is one agent per JVM, so other users' traces and Glowroot data hold every
  profile's requests: with virtual hosts the plugin offers them (`me`) and starts them (`recording-start`) only when
  the server is started with `-Dblackbox.sharedGlowroot=true` (`BlackBoxPlugin.glowrootShared`), never on a tenant
  admin's setting. It reads the JVM's start options (`RuntimeMXBean.getInputArguments`), not the live system property,
  which a tenant's script could set; virtual hosts count as on when either the live `wflow.virtualhost` or the start
  option says so. When the start options can't be read (a security manager refusing it), the opt-in is off.
  The build and the copy check it again, so a capture started before a restart without the opt-in (or watched again
  after one) ends "failed" or with a note instead. Each profile, capture and running request is handled on its own
  (`ProfileDir.safely`): one unreadable folder or refused file access is logged (the same message at most every 10
  minutes) and stops neither the others nor the scheduled job; a capture or request whose work fails is no longer
  watched (its Glowroot data status is set to failed when the folder still takes it). Cloud installs (`MultiTenantSecurityManager`) may refuse a background thread's access to another
  profile's folder; this, like the liveness check, is untested there. The server load sample is the JVM's and the server's, the same for every profile: Community and Enterprise
  have one database pool; cloud installs keep one per profile, which the strip doesn't show.
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
  built again after the restart. Readers (`GlowrootData.statuses`) also show a status with no news for too long as
  failed: `waiting` after 3 h 15 min, `building` after 2 h, so a node that stopped watching (its folder failed, or it
  is gone) doesn't leave the recording building for good; a later status still replaces it. The recordings list reads
  a row's statuses again while its saved state is `building` (`ServerData.listedGlowroot`) and saves the new one.
- **How:** `GlowrootData` copies `data.h2.db` (Glowroot keeps it locked; the copy is not), deletes every row outside
  the recording with Glowroot's own shaded H2 driver (`lib/glowroot-embedded-collector.jar`, H2 1.3), reads only the
  blocks the kept rows point to straight from the live `*.capped.db` ring files, writes them into new capped files of
  the same ring size, rewrites the capped ids, and compacts the copy. It runs on one low-priority thread inside Joget,
  with a small H2 cache (`CACHE_SIZE=8192`); temporary files go to `java.io.tmpdir` and are deleted afterwards. A block Glowroot
  overwrites before it is read is left out, and the status says how many.
- **Order and class loading:** H2 is copied first and the capped files are read after, so every capped id in the copy
  exists in the capped files. The shaded H2 driver loads in a `URLClassLoader` whose parent is the loader of
  `java.sql` (the platform loader on Java 9+), so it sees JDBC and nothing of Joget or the bundle.
- **Capped files (Glowroot 0.14):** a header of `currIndex` (long), `sizeKb` (int) and `lastResizeBaseIndex` (long),
  then the ring. Each block is its length (long) and its LZF bytes; Glowroot never splits a length across the ring's
  end, though the bytes may wrap. Glowroot writes a chunk (one LZF block, up to 64 KB) and then the header, so up to
  1/16 of the ring (at most 1 MB; rings are at least 1 MB) past the header's position may already be overwritten. A
  block counts only if it wasn't overwritten before or while it was read (the header is checked again after the read);
  the smallest id still kept is computed the way Glowroot does.
- **Tests:** H2 1.4.199 (test scope) lets `GlowrootDataTest` build a Glowroot-shaped database in the PageStore format
  that Glowroot's H2 1.3 writes.
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
  `-D` argument sets to the same value. When a name has several `-D`, the last one wins, as in Java.

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
  lists keys the pool has no setting for: Joget's `DynamicDataSource` copies every other file key onto the Tomcat JDBC
  pool with BeanUtils and skips the ones it can't set silently, usually because of a typo (`maxactive`).
- **License**: Enterprise's license table (`SysInfo.getLicenseProperties()`), including the license signature, system
  key and MAC addresses (masked, see below). Missing on Community.
- **System properties**: System Info's system table (`SysInfo.getSystemProperties()`): every Java system property plus
  JVM memory and processors. On Community the same values are read directly.

**Masking** (`Secrets`, applied when the snapshot is taken, so `server-info.json` and every export hold only the masked
values): JVM arguments (`-Dname=value` by name, others by value), system properties, datasource keys and pool settings.
A secret name (the `secretNames` setting; empty = `Secrets.DEFAULT_NAMES`, the names of Upgrade Advisor's `SecretMasker`) masks the whole
value; secret-shaped values (URL user info, Bearer/Basic, JWT, private keys, `%%%%…%%%%`, known API key prefixes,
secret-named `name=value` pairs) are masked inside any value. Setting words are matched without regex (the parts between
`*` found in order with `indexOf`), and `KEY_VALUE` takes a name in one possessive run, so no word an admin enters
can make matching slow. A `!word` line is an exception for one whole name
(`*` at an end leaves it open); the default list ends with `!wflow.systemkey` (Enterprise's system key generator), and a
saved list equal to `Secrets.OLD_DEFAULT_NAMES` (the default before that line) counts as the default. A recording
started with `systemSecrets=true` by a user `allowUnmaskedSystemInfo` allows (admins always) uses `Secrets.none()`:
nothing is masked by name or shape, except in the database and pool sections, which then use `Secrets.passwords()`
(password-like names and shapes such as `user:password@` in the URL), so the database password stays out.

The license table doesn't use the word list: `ServerInfo.license()` masks the entries starting with
`ServerInfo.LICENSE_SECRETS` (`platform.systemKey`, `platform.license.signature` — the system key the license was
issued for — and `platform.net.address`) unless the recording was started with `license=true` by a user
`allowUnmaskedLicense` allows (offered on Enterprise only). `server-info.json` records the choice as `secretsMasked`
and `licenseMasked`; when either is `false`, the viewer's Info tab says so in the Joget section.

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
(`allowCaptureTraces`), `otherTraces` (`traces` + `allowCaptureOtherUsersTraces`) and `glowrootData`
(`allowCaptureGlowrootData`); for these and the app export, apps data and
mask settings `Access.allows`: admins always, others when the setting is ticked (the export at stop and at upload checks the same). None needs the upload right. `recording-start`
takes `gauges=false`, `traces=false` and `glowrootData=false` (each turns its setting off), `allServer=true` and
`otherTraces=true` (opt-in;
every box in `quick.js` starts unticked).

The export (`exportApps`) runs `AppExport`: `hits` reads `session.json` (each request with its page:
a document's own URL, else its Referer; failed or 4xx/5xx = a problem; console errors = a problem on the page shown
then) and the system log (requests, 5xx or an `error` = a problem; error lines = a problem on their request; other threads skipped),
URL-decoded. `find` matches them against Joget's app URL patterns (`appOf`; app icons and screenshots name none). The
browser's requests name the apps; system log hits only add to those (it also gets other tabs' service worker
requests, which carry no tab id), unless the browser named none. App Center is dropped unless it had problems or was
alone. Apps rank by problems, then requests; the top `maxApps` (default 3, 0 = all) that the user may have exported
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
the recording, then `SampleData.addTo` rewrites the `.jwa` with `data/<table>.json` (the format `AppService.importFormData`
reads; dates as `yyyy-MM-dd HH:mm:ss` in the server's time zone, the pattern `FormUtil.jsonToFormRowSet` parses) and `app_formuploads/<table>/<newId>/<file>`. Data comes from
`FormDataDao` (`load`, `find` with `e.customProperties.<col> IN (...)`, newest by `dateModified`) and
`FileUtil.getUploadPath`; activity ids are resolved with `WorkflowManager.getActivityById`. `apps.json` gets each app's
`sample` summary (mode, tables: rows, how picked, files, masked columns) and the recording's `idMap`.

The export runs on a background thread after the stopping request ended, so it must not use the request's locale:
`FormUtil.formRowSetToJson` and `FormRow.getProperty` format dates with it and throw there (Tomcat: "The request
object has been recycled"), and Joget's own export then writes `[]`. So `SampleData` formats the rows itself.

`ProcessData` runs in the same hook: process ids from the recorded ids, the sample's old record ids and the process ids
in its rows (activity ids resolved with `getActivityById`), kept when the process definition id starts with
`<appId>#`; `getRunningProcessById`, `getProcessVariableList`, `getActivityList` (by `dateCreated`, oldest first) and
`getRunningActivityInfo` (assignees, accepted user). At most 20 processes per app and 100 activities per process
(`activitiesCut: true` when cut). Written to the app's `processes` in `apps.json`. Joget returns an empty
`WorkflowProcess` (not null) for an unknown process id.

## Plugin parts

| Part | Job |
|---|---|
| `Activator` | Registers `BlackBoxPlugin`, `BlackBoxPageFilter`, `BlackBoxApiFilter` and `ServerCaptureFilter`; starts and stops `ServerCapture` (appender, timers, trace / gauge / Glowroot / export threads) |
| `BlackBoxPlugin` | System settings + API logic (actions above); its inner class `Access` holds the permission checks |
| `BlackBoxApiFilter` | Serves the API at `/api/blackbox?action=…` |
| `BlackBoxPageFilter` | Serves the static pages at `/blackbox/…`, and `assets/settings.js` for the settings page |
| `settings.js` | The settings page's `_settingsDefaults` custom field: fills settings never saved (and empty permission pickers that have a default) with their defaults from `blackboxPlugin.json`, and **Reset to defaults** (a page button beside Submit, `buttons` with a `callback`) fills the whole form. Joget 8.2 shows `value` defaults only when nothing was ever saved. Joget's property editor `eval()`s the file as one object literal and mixes it into the field, so it isn't valid stand-alone JS. Saves nothing itself |
| `RecordingStore` | Folder storage, validation (JSON shape, WebM header, size limits, id format), `meta.json` locking, old `systemLog` names |
| `SessionFile` | Copies `session.json` / `system-log.json` to disk, checks them and reads them as streams |
| `JsonStream` | The small pull parser `SessionFile` uses; keeps its own read buffer (`Reader.read()` per character is slow), and caps a name at 4096 characters so one huge key can't grow to the file's size |
| `RecordingZip` | `download&format=zip` and `import`: the recording as one ZIP and back |
| `RecordingHtml` | `download&format=html`: view.html with viewer.css/js inlined and display.js in `<head>`, the recording in `<script>` data blocks, `export.js` to open it. The full trace pages go in too, one `application/gzip` block each (base64, ~105 KB instead of ~450 KB); `export.js` unzips them with `DecompressionStream` into `blob:` URLs for **Open full trace** (no link in a browser without it). With `allowUnsandboxedTraces` off, `meta.tracesSandboxed` makes each page a wrapper with a `sandbox="allow-scripts allow-popups"` iframe (`srcdoc`), the same sandbox as the `trace` endpoint's CSP. In the data blocks every `<` is written `\u003c`, so no `</script` can end a block early; a literal `</script>` or `</style>` in an inlined viewer file breaks every HTML download (`RecordingHtmlTest`). `export.js` writes `<\/` for `</` for the same reason, and decodes the video with `fetch()` of its `data:` URL so the page doesn't block |
| `ServerCaptureFilter` | Marks requests that carry `X-BlackBox-Id` (all URLs, before Spring Security) |
| `ServerCapture` | Root-logger appender, per-thread marking, pending files, flush/idle/cleanup timer |
| `ServerCaptureStore` | Pending capture folders: start, stop, the stop's copy of the lines (`writeLines`), state such as `appExport` (`put`), attach (merge nodes, sort by time, move traces, gauges, Glowroot data, `server-info.json` and `app/`), clean up |
| `TraceCollector` | Per node: picks the slow and failed recorded requests, and those that logged an ERROR (`ServerCapture` saw the line on the request's thread and `GlowrootTag.keep()` set Glowroot's slow threshold to 0 for that request), finds their Glowroot trace (retries), saves it; Retry requests per node |
| `GlowrootClient` | Glowroot's JSON backend and trace export; finds the local agent's port |
| `GlowrootData`, `GlowrootPackager`, `GlowrootTag` | Glowroot data packages; tagging recorded requests |
| `ServerInfo` | System Info snapshot at recording-start (host, cluster, plugins, Java, Joget, DB, pool, license) |
| `Secrets` | Masks secrets in the System Info snapshot: the `secretNames` setting (default: Upgrade Advisor's secret names) and value shapes (from Upgrade Advisor) |
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
| `recordings.html/js` | Recordings page: kept, on-disk and upload-history rows, plus the user's own recordings on the Upload URL's server that none of those rows has uploaded (`list`, filtered to `owner`; fetched on load and on focus after a minute; open on the server, no batch actions; on 401 a "Log in" note) (100 at a time) with Browser issues; batch Upload, Download ZIP, Delete for ticked rows (Upload off when `me.canUpload` is false); Import ZIP; Link / Allow / Unlink folder; crash recovery on load |
| `actions.js` | Upload (with its form; `uploadNow` without it, `uploadMany` for a batch), Move to folder, Download ZIP and Delete of one recording; links in messages |
| `replay.js` | Replay page (`viewer.html?id=` or `?disk=`): loads the recording, its status bar, ⚠ Upload and the fix box, Move to folder / Download ZIP / Delete, Older / Newer; with `upload=server` it uploads and opens the server's copy |
| `report.js` | Builds `session.json`; turns a recording interrupted by a crash or a closed recorder into a normal one (`recovered`, stop reason "interrupted") and stops its server capture at the last save |
| `quick.js` | Quick settings (`chrome.storage.local` `quick`): `apps`, `sample`, `mask` are the per-recording app export choices (`recording.options`, the export parameters); `allServer`, `gauges`, `traces` and `glowrootData` go to `recording-start`; `credentials` (with `me.options.credentials`) turns masking off |
| `mask.js` | Credential masking in the recorder (`Mask.entry` on every network entry as it is created and completed, `Mask.text` on console text, `Mask.url` on navigations); on unless `Mask.on(quickOffer, quick)`. Secret words from `Secrets.java`, plus `csrf`, `xsrf`, `session id`, `otp` |
| `store.js` | Row list (kept, on disk: newest 50, upload history), badges, loading a recording, the linked folder |
| `server.js` | Calls the Upload endpoint with the browser's cookies; Upload URL and Recording page URL, host access, 15 s timeout on GETs |
| `zip.js` | Reads and writes recording ZIPs (no library: `CompressionStream` / `DecompressionStream`; top-level files only, no ZIP64) |
| `idb.js` | IndexedDB (see below) |
| `options.html/js` | Settings: Upload URL, Recording page URL, Theme, Text size, Open recordings on the server when you are logged in |
| `theme.js`, `ui.css` | `theme.js`: Theme and Text size (as `zoom`) for every extension page, cached in `localStorage`; also the content script on the Upload URL server's `<context>/blackbox/*` pages, registered by `background.js` while BlackBox has access to that server. There it can't read `chrome.storage` (`background.js` sets `storage.local` to `TRUSTED_CONTEXTS`) and asks the background with a `display` message, again when the page comes back into view; the background and the recorder ignore every other message that doesn't come from an extension page (`sender.url`). `ui.css`: styles of the popup, Settings and recorder (the other pages use `viewer.css`) |
| `display.js` | Theme and Text size pickers in the header of the two pages without the extension (downloaded HTML, `dist/blackbox-viewer.html`), in place of `theme.js`: inlined in `<head>` so the saved choice applies before the page draws; the same values and `localStorage` keys (`bb-theme`, `bb-text-size`) as `theme.js`, shared by every file opened from disk. Copied into the plugin by the pom with `viewer.js`/`viewer.css` |
| `build-standalone.py` | Builds `dist/blackbox-viewer.html`: `viewer.css`, `viewer.js`, `zip.js` and `display.js` inlined, without storage, server and upload code; re-run after viewer changes |

## Extension storage and messages

**`chrome.storage.local`:** `uploadUrl`, `viewUrl` (Recording page URL), `theme`, `textSize`, `openOnServer` (on
unless turned off), `recordPageAudio`, `recordMic`, `quick` (quick settings), `quickOffer` (the server's last
`me.options`). **`chrome.storage.session`:** `rec` (`recIncognito` in incognito) = `{tabId, windowId, mode, startedAt?,
serverCapture?}`, the recording in progress (`startedAt` once a source is picked); `armedTicket` (see "Armed start").

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

- to `background`: `get-state`, `arm`, `disarm`, `get-armed` (popup), `start {tabId, mode, streamId}` (popup), `source-picked {startedAt}` (recorder; the
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
that showed the picker. The picker is a dialog inside that window and can't be resized while open, so the window
opens big enough for all of it; the picker lists the recorder window too, so its title tells the user not to pick it.
`chrome.tabCapture` is called in the popup: opening the popup grants it for that tab (window and screen use it only for
the tab's sound, so they still start without it).

**Extension behaviours worth knowing:**

- **State before events:** `background.js` saves the recording state before enabling the CDP domains, so the first
  events aren't dropped (`Runtime.enable` replays console messages already on the page). On stop it clears the state
  first, so a second stop (the debugger's `onDetach` after the user's stop) does nothing.
- **Redirects:** a redirect reuses the same CDP `requestId`, so the recorder closes the previous hop as its own entry
  before starting the next.
- **Network errors:** `fetch()` rejects with `TypeError: Failed to fetch` for every network failure (wrong host or
  port, server down, certificate), so the extension says "Could not reach …" instead; older recordings may still
  carry "Failed to fetch".
- **Permissions:** `chrome.permissions.request` and `FileSystemHandle.requestPermission` work only while the click
  still counts as a user gesture: ask before any `await` and before a `confirm()`. Automatic uploads have no click,
  so Save in Settings asks for host access instead.
- **Blob URLs:** a Blob URL must outlive its `chrome.downloads` download, so the recorder window stays open after a
  download fallback, and Move to folder keeps its page until the download ends.
- **IndexedDB:** each connection is closed after use, so another page (recorder, viewer, Recordings) can upgrade the
  database version; an open connection blocks the upgrade.
- **Uploads:** a row keeps its server id after `create`, so a retry after a failed video upload reuses it instead of
  creating a second recording. An imported ZIP's capture id belongs to the server it was recorded on, so the import
  drops it.
- **Video:** `MediaRecorder`'s WebM has no duration (the video element reports `Infinity` and can't seek); the viewer
  seeks far past the end once, which makes Chrome scan the file and find the real duration.
- **Untrusted text:** recorded text comes from other pages, and `session.json` can be crafted, so the viewer shows it
  with `textContent`, never `innerHTML`. **Copy as cURL** single-quotes every value for the shell, puts a URL that
  starts with `-` after `--url`, and turns a method that isn't 1–20 letters into GET (`e2e/tests/curl.spec.js`).

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

## Things to keep in step

Nothing in the code checks these; change both sides together.

- `Secrets.DEFAULT_NAMES` equals the `secretNames` default in `blackboxPlugin.json`.
- `Masker.DEFAULT_SENSITIVE` equals the `sensitiveColumns` default in `blackboxPlugin.json`.
- `ServerCaptureStore.FORWARD_MS` (24 h) stays longer than `TraceCollector.GIVE_UP_MS` (10 min), so a late trace still
  finds the pending folder's pointer to the recording.
- `TraceCollector.HEARTBEAT_MS` (3 min) stays shorter than `TraceStore.STALE_MS` (10 min), so a pending note is
  rewritten before it reads as failed.
- `BlackBoxPlugin`'s top recording length (120 min) stays within `GaugeSampler.MAX_MS` (2 h).
- `session.json` writes its `recording` block first and `summary` right after: the Recordings list reads only the
  first 16 KB of each file on disk (`store.js` `scanFolder`). Keep that key order in `report.js`.
- `recordings.js` `utc()` is a copy of `viewer.js` `utc()`: the standalone viewer can't load a shared file.
- `export.js` `uploadInfo()` is a copy of `common.js` `BB.uploadInfo`: `common.js` is not in the HTML download.
- `viewer.html`'s `<!-- upload form (actions.js) -->` and `<!-- /upload form -->` are code: `build-standalone.py` cuts
  the upload form out between them.
- The header `X-BlackBox-Capture-Secret` and the scheme `BlackBox-Ticket` (`ArmTickets.SECRET_HEADER`,
  `TICKET_SCHEME`) are spelled out in `background.js`, `recorder.js` (`secretHeader`) and `report.js`
  (`stopRecoveredCapture`).
- `armTicketMinutes`: the default 15 and the range 1–60 in `blackboxPlugin.json` (value, regex, text), the guide and
  the extension README equal `ArmTickets.DEFAULT_TTL_MIN`, `MIN_TTL_MIN` and `MAX_TTL_MIN`. The popup shows the expiry
  from the server's `ttlMs`, so it needs no copy.
