# BlackBox architecture

How the extension (`chrome-extension/`) and the Joget plugin (`src/`) work inside. For installing and using them, see
[README.md](README.md) and [chrome-extension/README.md](chrome-extension/README.md).

## Docs in this repository

| Path | What |
|---|---|
| `README.md` | Plugin: build, install, use, settings |
| `e2e/README.md` | Browser tests of the extension (Playwright, its own Chromium) against a running Joget |
| `chrome-extension/README.md` | Extension: install, settings, record, recordings, replay |
| `ARCHITECTURE.md` | This file: internals, API, storage |

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

Errors: an HTTP error status with `{"error":{"message":"…"}}`; **401** means "not logged in". `url` may be a path; it is
resolved against the Upload URL. The Recording page URL setting, when set, takes priority over `url`.

The plugin serves this at `<context>/api/blackbox` with more actions: `list`, `meta`, `session`, `video` (GET with HTTP
Range), `systemlog`, `download` (`format=zip` or `html`), `import`, `delete`, `recording-start`, `recording-stop`,
`traces`, `trace`, `trace-retry`, `gauges`, `serverinfo`, `glowroot`, `apps`, `appfile`.

- The API lives at `/api/*` because Spring Security runs there (the logged-in user is known), but Joget's
  `JsonResponseFilter` does not: on `/web/json/*` it rejects requests whose `Referer` is not Joget, and the extension's
  requests have none. The filter answers the request itself, so it never reaches Joget's API Builder servlet.
- CSRF: POSTs are accepted from a `chrome-extension://` `Origin` (no web page can send that) or with the
  `OWASP-CSRFTOKEN` header (the pages get it from `/web/json/directory/user/csrfToken`). CSRFGuard itself only
  covers `/web/*`.
- Uploads are raw request bodies (not multipart): `create` takes `session.json` (title and notes as query
  parameters), then `video` takes `recording.webm`. Only the uploader can add the video, once.
- Error responses: `{"error":{"code":"403","message":"..."}}`.

## Storage

One folder per recording under `<wflow>/blackbox/<id>/`: `meta.json`, `session.json`, `recording.webm`,
`system-log.json` when system logs were attached, `traces/` when Glowroot traces were copied, `gauges/` with the
server load samples, `server-info.json`, `glowroot/` with the Glowroot data, and `app/` with the exported apps
(`apps.json` + `<appId>-<version>.jwa`). Captures waiting for an upload are in
`<wflow>/blackbox/pending/`, Retry requests in `<wflow>/blackbox/retry/`. No database tables. Folders on a shared
`wflow` directory work for clustered nodes; deleting a recording removes its folder.

The extension keeps recordings in IndexedDB (newest 10), or reads them from a linked folder on disk.

## System logs

1. When recording starts, the extension calls `recording-start` with a random id (the user must be logged in and allowed
   to upload), then adds `X-BlackBox-Id: <id>` to the recorded tab's requests to the Upload URL's server
   (`declarativeNetRequest` session rule). Requests made by a service worker carry no tab id, and Joget's PWA service
   worker re-fetches almost every userview request, so those are tagged too; while recording, other tabs on the same
   server can add lines.
2. `SystemLogFilter` marks the threads of those requests; an appender on log4j's root logger keeps their log lines (what
   `joget.log` would show, level info and up) plus one entry per request (method, URL, status, time). They go to
   `<wflow>/blackbox/pending/<id>/system-log.<node>.jsonl`, flushed every 2 seconds.
3. Stop removes the rule; the recorder calls `recording-stop` with the app export's input (see "App export"), or the
   service worker calls it plainly when the recorder can't. The lines wait on the server until the recording is uploaded;
   `session.json` carries the id (`recording.systemLog`), and `create` moves them into the recording as
   `system-log.json`. Only the user who started the capture can attach it. Captures never uploaded are deleted after
   the keep period (checked every hour).
4. The replay page loads `system-log.json` for allowed users and lines it up with the video. The plugin sends
   `X-BlackBox-Time` (server clock) on tagged responses; if the server clock differs from the browser's by more than
   250 ms, the viewer corrects it. The plugin's own request entries (not in `joget.log`) are only shown in a network
   request's details.

Not captured: work a request hands to another thread, and log lines below the root logger's level.

**All server** (`captureAllServerLogs`, stored as `allServer` in the capture's meta, only for recorders with the global
system-log permission): while such a capture is open on a node, the appender also writes every other thread's events
to it with `"other": true` (no `req`), including tagged requests of other recordings. Nodes write it once the capture
is open there (the starting node, and any node that served one of its requests). `summary` counts those lines as
`otherEntries`, not as entries or errors.

Log4j 2 is not visible to plugin bundles (OSGi exports neither `org.apache.logging.log4j` nor `org.apache.log4j`),
so `SystemLogCapture` reaches it through Joget's classloader by reflection; the appender is a `java.lang.reflect.Proxy`.

## Glowroot traces

- **Which requests:** only those Glowroot keeps a trace for, by its own settings: slower than its slow threshold
  (default 2000 ms, or a lower per-transaction override), or failed (exception, 5xx, and 4xx when its servlet plugin
  says so). BlackBox reads these settings and never changes them.
- **When:** each node collects the traces of the requests it served, as soon as each request ends, not at stop or
  upload. Glowroot stores a trace just after the request, so the node tries after 2, 5, 15, 30 and 60 seconds, then
  every minute, for up to 10 minutes. A request still running past the threshold is listed as pending at once and
  refreshed every 3 minutes while it runs.
- **Clusters:** each node reads only its own Glowroot (on `localhost`) and writes its own files to the shared folder.
- **What is kept:** `traces/<node>.jsonl` (one line per state change: pending, saved, none, failed, with a reason) and
  `traces/<node>-<traceId>.html`, Glowroot's own export of the trace (a self-contained page, about 0.4–1 MB). At most
  100 traces per recording per node.
- **After the upload:** the files move into the recording with the system logs. The pending folder keeps only
  `meta.json` pointing at the recording for a day, so traces of requests still running go to the recording. The replay
  page shows "⏳" on the Traces tab while some are pending, refreshes every 5 seconds, and asks before a download that
  would miss them. A trace with no news for 10 minutes is shown as failed.
- **Glowroot not reachable:** the recording is saved as usual; the trace is marked failed with the reason (not
  installed, connection refused, needs a login). Only Glowroot's anonymous access is supported.
- **Retry:** only the node that served a request can read its trace, and the click may reach any node, so
  `trace-retry` marks the traces pending and writes `<wflow>/blackbox/retry/<recording>@<node>.json`; each node picks
  up its own every 20 seconds and tries again as above. A node that never answers (removed, or renamed) makes the trace
  failed again after 10 minutes. One retry per recording every 20 seconds. Glowroot keeps traces for 14 days by
  default, so older ones may be gone. The newest state wins across nodes, so node clocks should be in sync.
- **Viewing a trace:** `trace&file=…` serves Glowroot's page with `Content-Security-Policy: sandbox allow-scripts`, so
  its script runs without access to Joget's cookies or pages. It loads Google Fonts and Font Awesome from the internet.

## Glowroot data

- **Tagging:** every recorded request (those with `X-BlackBox-Id`) gets the Glowroot transaction attribute
  `BlackBox recording = <id>` through Glowroot's agent API (`GlowrootTag`). Glowroot still decides which requests get
  a trace; the ones it keeps carry the tag, and Glowroot's own trace search can filter by it.
- **What is kept:** the tagged traces, the aggregates and gauges of all traffic on that node during the recording
  (plus one rollup bucket on each side, which Glowroot's charts show), and Glowroot's lookup tables. Aggregates and
  gauges can't be split per user.
- **When:** about a minute after the stop (after Glowroot has written the aggregates of the stop's minute), each node
  that served the recording builds `glowroot/<node>.zip` in the pending folder, or in the recording once uploaded.
  `glowroot/<node>.json` holds the state (waiting, building, saved, failed) and the reason. A package not built when
  Joget stops is not built after a restart.
- **How:** `GlowrootData` copies `data.h2.db` (Glowroot keeps it locked; the copy is not), deletes every row outside
  the recording with Glowroot's own shaded H2 driver (`lib/glowroot-embedded-collector.jar`, H2 1.3), reads only the
  blocks the kept rows point to straight from the live `*.capped.db` ring files, writes them into new capped files of
  the same ring size, rewrites the capped ids, and compacts the copy. It runs on one low-priority thread inside Joget,
  with a small H2 cache; temporary files go to `java.io.tmpdir` and are deleted afterwards. A block Glowroot
  overwrites before it is read is left out, and the status says how many.
- **The ZIP:** `data/` (`data.h2.db` and the five capped files), `config.json` (the server's, without gauges, alerts
  and synthetic monitors, so the viewer adds no samples of its own JVM, sends no mail and calls no live URL),
  `admin.json` (localhost on port 4000, nothing expires; none of the server's users, LDAP or SMTP), `README.txt` with
  the steps and the time range, and `Sleep.java` (an idle JVM for the agent).
- **Disk space while building:** one copy of Glowroot's `data.h2.db` in Joget's temp folder (`java.io.tmpdir`,
  `/opt/tomcat/temp` in the Joget image), deleted when the package is done; the capped files are read in place,
  not copied. The rows are deleted in batches of 20,000, each committed, so the copy never grows past its starting
  size (measured: 127 MB stayed 127 MB, then 1.5 MB after compacting; one big delete made it 1.36 times larger first).
  Before each build the node checks for that size plus 10% (at least 64 MB) free; with less, the package fails with
  the reason, shown on the replay page's button and in Joget's log.
- **Downloads:** while a package is still being built, Download ZIP waits (the server answers 409; the list's batch
  download is off for those rows, tagged **Glowroot ⏳**).

## Server load

Each node that serves a recording samples its own load once a second while the capture is open, from the JVM itself
(no Glowroot needed): heap used and max, GC time in that second, process and system CPU, Tomcat's busy request threads
(all `Catalina:type=ThreadPool` pools) and Joget's database pool (`setupDataSource`: active, max, waiting). Sampling
starts at `recording-start` (or the node's first recorded request) and ends at stop, or after 2 hours. Samples go to
`gauges/<node>.jsonl` and move into the recording on upload. Values a server can't give are left out.

## System Info snapshot

`recording-start` saves `server-info.json`, grouped like Glowroot's **JVM > Environment** page, then everything
**Settings > System Info** shows:

- **Host**: hostname, node, processors, physical memory, OS. **Process**: id, start time, uptime.
- **Java**: version, vendor, VM, heap used/committed/max, every JVM argument (one per line).
- **Joget**: version, edition, application server, time zone, Glowroot address and version.
- **Recent changes** (first in the Info tab): app versions by `dateModified` (`AppDefinitionDao.findByVersion`, newest
  50; saving a form, list or userview updates it), jars in `app_plugins` and top-level files of the wflow folder modified
  in the period (not logs, not `servers.json` / `serversIP.properties` / `app_presence.json`, which Joget rewrites), and
  the JVM start when it is in the period.
- **Cluster**: `ServerUtil` (this node's `wflow.name`, the shared `servers.json` nodes, the active nodes, each with its
  `serversIP.properties` address), `wflow.cluster` (set by the wflow-cluster agent), Tomcat's Engine `jvmRoute`, whether
  a `Catalina:type=Cluster` MBean exists (session replication), and the requested session id's route suffix. The viewer
  adds **Recorded requests served by** and **Sticky sessions** from the system log: `attach` puts the `node` (from the
  `system-log.<node>.jsonl` file name) on each request entry.
- **Plugins**: one entry per jar in `app_plugins` (`PluginManager.getBaseDirectory()`): modified date, size, then each
  plugin it loaded (name, version, class) from `listOsgiPlugin` + `getJarFileName`. A jar with none failed to load or
  holds no plugin; a loaded jar not in the folder says so.
- **Database**: the current profile's `app_datasource-<profile>.properties`: profile, JDBC driver, URL and user, then
  every other key in the file. The password is left out, and `password=` values in the URL are masked.
- **Connection pool**: the pool state at recording start (size, active, idle, waiting), then every Tomcat JDBC pool
  setting as the pool really uses it. A value that differs from the file shows `(file says x)`. **Ignored file keys**
  lists keys the pool has no setting for: Joget skips them silently, usually because of a typo (`maxactive`).
- **License**: Enterprise's license table (`SysInfo.getLicenseProperties()`), including the license signature, system
  key and MAC addresses. Missing on Community.
- **System properties**: System Info's system table (`SysInfo.getSystemProperties()`): every Java system property plus
  JVM memory and processors. On Community the same values are read directly.

## App export

**When:** at stop. The recorder builds `session.json`, then POSTs `recording-stop` with a JSON body of its network
entries (`t`, `url`, `type`, `status`, `failed`, `postData`, the `referer` header) and console errors, plus `apps=false`
or `sampleData=none|masked|real` from `recording.options` (the popup's quick settings, `quick.js`). `exportAtStop`
writes them and the capture's lines so far (`SystemLogStore.writeLines`; lines other nodes haven't flushed are missing)
next to the capture, exports into the pending folder's `app/`, and keeps the state in the capture's `meta.json` as
`appExport` (`off`, `exporting`, `done` with `saved`, `failed`). The recorder waits up to 150 s and saves the state in
`recording.systemLog.appExport`. `create` waits up to 120 s while it is `exporting`, then `attach` moves `app/` into the
recording and `meta.apps` takes `saved`; `off` exports nothing. With no export at stop (no capture, the stop's body
never arrived, or `failed`), `create` exports as below from the uploaded files, taking `apps` / `sampleData` from the
upload (the upload form, or `recording.options` for automatic uploads).

`me.options` says what the popup may offer: `systemLog`, `apps`, `sample` (also needs the server data of one's own
recordings), `real` (may untick Mask data: users in "Who can view system logs"), `allServer` and `glowrootData`. The others only turn a setting off: `recording-start` takes
`allServer=false` and `glowrootData=false`.

The export (`exportApps`) runs `AppExport`: `hits` reads `session.json` (each request with its page:
a document's own URL, else its Referer; failed or 4xx/5xx = a problem; console errors = a problem on the page shown
then) and the system log (requests, 5xx = a problem; error lines = a problem on their request; other threads skipped),
URL-decoded. `find` matches them against Joget's app URL patterns (`appOf`; app icons and screenshots name none). The
browser's requests name the apps; system log hits only add to those (it also gets other tabs' service worker
requests, which carry no tab id), unless the browser named none. App Center is dropped unless it had problems or was
alone. Apps rank by problems, then requests; the top `maxApps` (default 5, 0 = all) are exported with
`AppService.exportApp` (reads the current request's parameters, which carry no `tablenames`, `usergroups` or
`exportplugins`, so no data, groups or plugins go in). `apps.json` records each app's state: `saved`, `missing`,
`failed` (with the reason) or `skipped` (beyond the cap), and `problems`. `meta.apps` keeps `[{id, name, version,
problems}]` for the list; `list` and `meta` drop it for users who may not see server data. `appfile` serves only names
matching `<id>-<n>.jwa`.

`viewer.js` repeats `appOf` to draw the Apps lane (segments from the same page signals, flips under 0.5 s merged), the
App filter and the App tab's problem lists, so the lane works for local recordings too.

### Sample data

`recording-stop` and `create` take `sampleData=none|masked|real` (missing: masked); `me` returns `sampleData: {enabled,
real}` for the upload form. `AppExport.save` calls an `AfterExport` hook per saved `.jwa`: `SampleData.build` with one
`Masker` (random HMAC key per export, so fakes are stable within a recording and differ between them) and one `IdMap` for all apps of
the recording, then `SampleData.addTo` rewrites the `.jwa` with `data/<table>.json` (`FormUtil.formRowSetToJson(rows,
true)`, the format `AppService.importFormData` reads) and `app_formuploads/<table>/<newId>/<file>`. Data comes from
`FormDataDao` (`load`, `find` with `e.customProperties.<col> IN (...)`, newest by `dateModified`) and
`FileUtil.getUploadPath`; activity ids are resolved with `WorkflowManager.getActivityById`. `apps.json` gets each app's
`sample` summary (mode, tables: rows, how picked, files, masked columns) and the recording's `idMap`.

`ProcessData` runs in the same hook: process ids from the recorded ids, the sample's old record ids and the process ids
in its rows (activity ids resolved with `getActivityById`), kept when the process definition id starts with
`<appId>#`; `getRunningProcessById`, `getProcessVariableList`, `getActivityList` (by activated time) and
`getRunningActivityInfo` (assignees, accepted user). Written to the app's `processes` in `apps.json`.

## Plugin parts

| Part | Job |
|---|---|
| `BlackBoxPlugin` | System settings + API logic (actions above) |
| `BlackBoxApiFilter` | Serves the API at `/api/blackbox?action=…` |
| `BlackBoxPageFilter` | Serves the static pages at `/blackbox/…` |
| `RecordingStore` | Folder storage, validation (JSON shape, WebM header, size limits, id format) |
| `RecordingZip` | `download&format=zip` and `import`: the recording as one ZIP and back |
| `RecordingHtml` | `download&format=html`: view.html with viewer.css/js inlined, the recording in `<script>` data blocks, `export.js` to open it |
| `SystemLogFilter` | Marks requests that carry `X-BlackBox-Id` (all URLs, before Spring Security) |
| `SystemLogCapture` | Root-logger appender, per-thread marking, pending files, flush/idle/cleanup timer |
| `SystemLogStore` | Pending capture folders: start, stop, attach (merge nodes, sort by time, move traces), clean up |
| `TraceCollector` | Per node: picks the slow and failed recorded requests, finds their Glowroot trace (retries), saves it; Retry requests per node |
| `GlowrootClient` | Glowroot's JSON backend and trace export; finds the local agent's port |
| `GlowrootData`, `GlowrootPackager`, `GlowrootTag` | Glowroot data packages; tagging recorded requests |
| `ServerInfo` | System Info snapshot at recording-start (host, cluster, plugins, Java, Joget, DB, pool, license) |
| `AppExport` | Finds the apps in the recorded URLs, exports them at upload, lists them in `app/apps.json` |
| `SampleData` | Picks the sample rows of an exported app, masks / remaps them, adds them to the `.jwa` |
| `ProcessData` | The touched process instances of an exported app, read only, masked like the sample |
| `Masker` | Shape-keeping fake values, kept values, user pseudonyms, the old -> new record id map |
| `GaugeSampler` | Per node: samples heap, GC, CPU, busy threads and DB pool every second while a capture is open |
| `GaugeStore` | `gauges/` files: append, merge per node, move on upload, ZIP import |
| `TraceStore` | `traces/` files: append, merge (latest state per request), move on upload, summary of a trace |
| `src/main/resources/static/blackbox/` | Pages: list, view (+ shared viewer from the extension) |

## Extension parts

| Part | Job |
|---|---|
| `popup.js` | Record this tab / a window / the screen, Stop; gets the tab-capture stream id (the video for "this tab", the tab's sound for a window or the screen); system logs warning; quick settings (from `me.options`, cached as `quickOffer`) |
| `recorder.js` | Shows the `desktopCapture` picker (window/screen) or uses the tab stream, mixes page sound and microphone into one track, records with `MediaRecorder`, saves progress every 5 s, sends `recording-stop` with the app export's input, keeps the recording in IndexedDB |
| `background.js` | Attaches the debugger (Network, Runtime, Log, Page), fetches response bodies, forwards events to the recorder; starts/stops system log capture; REC badge |
| `viewer.js` | Replay UI shared by every replay page: video + synced lists, timeline, request details, Features drawer |
| `recordings.js` | Recordings page: list with Browser issues; batch Upload, Download ZIP, Delete for ticked rows |
| `actions.js` | Upload (with its form), Move to folder, Download ZIP and Delete of one recording; links in messages |
| `replay.js` | Replay page: loads the recording named in the address, its status bar, ⚠ Upload and the fix box |
| `report.js` | Builds `session.json`; recovers recordings interrupted by a crash |
| `quick.js` | Quick settings (`chrome.storage.local` `quick`): the popup's per-recording choices, as `recording.options` and the app export parameters |
| `store.js` | Kept recordings + linked folder, row list, loading a recording |
| `server.js` | Calls the Upload endpoint with the browser's cookies |
| `zip.js` | Reads and writes recording ZIPs (no library: `CompressionStream` / `DecompressionStream`) |
| `idb.js` | IndexedDB: kept recordings, linked folder handle, upload marks for recordings on disk |
| `options.js` | Settings |
| `theme.js`, `ui.css` | Theme and Text size for all extension pages; `theme.js` is also the content script on the Upload URL server's `<context>/blackbox/*` pages, registered by `background.js` while BlackBox has access to that server |
| `build-standalone.py` | Builds `dist/blackbox-viewer.html` |

The recorder is a window, not an offscreen document: a `chooseDesktopMedia` stream id can only be opened by the page
that showed the picker (tested in the BlackBox spike X0-01).

**Sound.** Page sound comes from `chrome.tabCapture` on the started tab in every mode, because the picker shares
system audio only for a screen on Windows and ChromeOS (Linux and macOS get none, and windows never have sound).
When the picker does share system audio, it replaces the tab's sound. Details:

- A tab stream id expires within seconds, so for a window or the screen the recorder opens the tab's sound before it
  shows the picker.
- Capturing a tab's sound mutes the tab, so the recorder plays it back through an `AudioContext`.
- `MediaRecorder` records only the first sound track: page sound and microphone are mixed into one track (Opus).
- The microphone is opened after the picked source, with `getUserMedia({ audio: true })` in the recorder window (the
  permission belongs to the extension's origin). A refused microphone or missing page sound doesn't stop the
  recording; the reason goes to `recording.audio.message` in `session.json`.
