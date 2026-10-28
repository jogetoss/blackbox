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

Errors: an HTTP error status with `{"error":{"message":"…"}}` (the plugin adds `"code"`, the status as a string);
**401** means "not logged in". `url` may be a path; it is resolved against the Upload URL. The Recording page URL
setting, when set, takes priority over `url`.

The plugin serves this at `<context>/api/blackbox` with more actions: `list`, `meta`, `session`, `video` (GET with HTTP
Range), `systemlog`, `download` (`format=zip` or `html`), `import`, `delete`, `recording-start`, `recording-stop`,
`traces`, `trace`, `trace-retry`, `gauges`, `serverinfo`, `glowroot`, `apps`, `appfile`, `app-export`.

- The API lives at `/api/*` because Spring Security runs there (the logged-in user is known), but Joget's
  `JsonResponseFilter` does not: on `/web/json/*` it rejects requests whose `Referer` is not Joget, and the extension's
  requests have none. The filter answers the request itself, so it never reaches Joget's API Builder servlet.
- CSRF: POSTs are accepted from a `chrome-extension://` `Origin` (no web page can send that) or with the
  CSRF token header (named by `SecurityUtil.getCsrfTokenName()`, `OWASP-CSRFTOKEN` by default; the pages get name and
  value from `/web/json/directory/user/csrfToken`). CSRFGuard itself only
  covers `/web/*`.
- Uploads are raw request bodies (not multipart): `create` takes `session.json` (title and notes as query
  parameters), then `video` takes `recording.webm`. Only the uploader can add the video, once.
- `session.json` (in `create`, in `import`, and the `recording-stop` body) and an imported `system-log.json` are never
  held whole in memory: they are copied to disk (counting bytes against **Max file size**, decompressed for a ZIP),
  then checked and read as a stream (`SessionFile` on `JsonStream`, a small pull parser): the check validates the
  whole document but builds only `recording`, `summary` and `tool`; readers (app URLs, hits, post data) build one
  entry at a time and only the fields they need. Limits: an entry at most 16 MB, URLs kept up to 8 KB, post data up
  to 64 KB each and 16 MB in all, 200,000 hits. Measured: a 395 MB session.json is stored and read in a 128 MB heap
  (the code that parsed it whole ran out of memory at 1 GB). At most 2 checks run at once; others wait up to 30 s,
  then get 503.
- The rest of a ZIP import is streamed too: Glowroot packages, `.jwa` files and trace pages are copied entry to file;
  `gauges.json` and `traces.json` are read as streams and written line by line (`GaugeStore.write(Path, Path)`,
  `importTraceEntries`). Real uploaded files in sample data are copied into the `.jwa` from disk at `addTo`
  (`Table.fileSources`), and `TraceStore.moveFolder` appends index files as streams. Read whole on purpose, all small or
  bounded: `meta.json` and status files, ZIP `meta.json` / `apps.json` / `server-info.json` (64 KB to 1 MB), Glowroot's
  `config.json` and JSON replies (20 MB cap), the app definition text used for masking, and the system log merge at
  upload (capped by **Max size of system logs per recording**; it sorts all nodes' lines).
- `meta.json` changes go through `RecordingStore.modifyMeta`: the latest copy is changed and saved under a per-recording
  lock (in the JVM, plus a file lock on `.meta.lock` for nodes sharing the folder, where the file system supports it).
  `updateMeta` replaces only the keys it is given. So the trace and Glowroot refreshes, the video upload and the
  upload's own changes never put back an older copy (`writeVideo` also checks "already has a video" under the lock).
- Server data in `meta.json` (`systemLog`, `serverData`, `apps`) is removed by `hideServerData` for users who may not
  see it, in `list`, `meta` and `download&format=html` (whose page carries the meta as JSON). The ZIP keeps only a
  fixed list of meta keys (`RecordingZip.META_KEYS`).
- `import` keeps a ZIP's server data only for **Who can view server data** (`canViewServerData()`, without the
  uploader rule: a ZIP can hold another user's recording). When it drops some, the response (not `meta.json`) has
  `serverDataSkipped: true`, and the list page says so instead of opening the copy.

## Storage

One folder per recording under `<wflow>/blackbox/<id>/`:

- always: `meta.json`, `session.json`, `recording.webm`;
- with a server data capture: `system-log.json`, `server-info.json`, and (when their settings are on) `traces/`,
  `gauges/` (server load) and `glowroot/` (Glowroot data);
- `app/` with the exported apps (`apps.json` + `<appId>-<version>.jwa`; `apps.json` also holds the sample's
  `idMap`), exported at stop, or at upload without a capture.

Captures waiting for an upload are in
`<wflow>/blackbox/pending/`, Retry requests in `<wflow>/blackbox/retry/`. No database tables. Folders on a shared
`wflow` directory work for clustered nodes; deleting a recording removes its folder.

The extension keeps recordings in IndexedDB (newest 10), or reads them from a linked folder on disk.

## System logs

1. When recording starts, the extension calls `recording-start` with a random UUID (lower case). The server refuses
   with 403 when **Capture server data** (`captureServerData`, off by default) is off or the user may not upload, and
   with 409 when the id belongs to another user's capture (the same user may start it again). The extension then adds `X-BlackBox-Id: <id>` to the recorded tab's requests to the Upload URL's server
   (`declarativeNetRequest` session rule). Requests made by a service worker carry no tab id, and Joget's PWA service
   worker re-fetches almost every userview request, so those are tagged too; while recording, other tabs on the same
   server can add lines.
2. `SystemLogFilter` marks the threads of those requests; an appender on log4j's root logger keeps their log lines (what
   `joget.log` would show, level info and up) plus one entry per request (method, URL, status, time). They go to
   `<wflow>/blackbox/pending/<id>/system-log.<node>.jsonl`, flushed every 2 seconds.
3. Stop removes the rule; the recorder calls `recording-stop` with the app export's input (see "App export"), or the
   service worker calls it plainly when the recorder can't. The lines wait on the server until the recording is uploaded;
   `session.json` carries the id (`recording.systemLog.id`), and `create` moves them into the recording as
   `system-log.json`. Only the user who started the capture can attach it. After the move, the pending folder keeps
   only `meta.json` pointing at the recording, for 24 hours (`FORWARD_MS`), so late traces still reach it. Captures
   never uploaded are deleted after the keep period (checked every hour).
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
  upload. Glowroot stores a trace just after the request, so the node waits 2, 5, 15, 30 and 60 seconds between tries
  (about 2, 7, 22, 52 and 112 s after the request), then every minute, for up to 10 minutes. A request still running
  past the threshold is listed as pending at once and refreshed every 3 minutes while it runs. A trace export over
  20 MB is not saved; only its summary is kept.
- **Clusters:** each node writes its own files to the shared folder. With **Glowroot address** empty, each node reads
  its own Glowroot (the local agent's port); when it is set, every node uses that address.
- **What is kept:** `traces/<node>.jsonl` (one line per state change: pending, saved, none, failed, with a reason) and
  `traces/<node>-<traceId>.html`, Glowroot's own export of the trace (a self-contained page, about 0.4–1 MB). At most
  100 traces per recording per node.
- **After the upload:** the files move into the recording with the system logs. The pending folder keeps only
  `meta.json` pointing at the recording for a day, so traces of requests still running go to the recording. The replay
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
- **Setting:** **Copy Glowroot data** (`captureGlowrootData`) is off by default. Only Glowroot 0.14 is supported; other
  versions fail with the reason.
- **When:** about a minute after the stop (after Glowroot has written the aggregates of the stop's minute), each node
  where the capture is open builds `glowroot/<node>.zip`: the node that took `recording-start` and every node that
  served a recorded request. It goes in the pending folder, or in the recording once uploaded. `glowroot/<node>.json`
  holds the state (waiting, building, saved, failed) and the reason. A capture never stopped fails after 3 hours.
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
- **The ZIP:** `data/` (`data.h2.db` and the five capped files), `config.json` (the server's, without gauges, alerts
  and synthetic monitors, so the viewer adds no samples of its own JVM, sends no mail and calls no live URL),
  `admin.json` (localhost on port 4000, nothing expires; none of the server's users, LDAP or SMTP), `README.txt` with
  the steps and the time range, and `Sleep.java` (an idle JVM for the agent).
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
- **Joget**: version, edition, application server, time zone, Glowroot address and version.
- **Recent changes** (first in the Info tab): app versions by `dateModified` (`AppDefinitionDao.findByVersion`, newest
  50; saving a form, list or userview updates it), top-level files of the wflow folder modified
  in the period (not logs, not `servers.json` / `serversIP.properties` / `app_presence.json`, which Joget rewrites), and
  the JVM start when it is in the period. Every changed app version among those 50 goes into `server-info.json` as `recentApps`
  (`{id, version, published, name, modified}`); with the app export (at stop, or at upload without one)
  `ServerInfo.summarizeApps` narrows the shown **App versions** to the apps the recording used and other apps whose
  forms use one of their tables (`SampleData.tables`), one line per app: its latest change and how many versions
  changed, plus `shares <tables>` for the others.
- **Cluster**:
  - Saved: `ServerUtil` (this node's `wflow.name`, the registered nodes in the shared `servers.json`, the active
    nodes, each with its `serversIP.properties` address), `wflow.cluster` (set by the wflow-cluster agent), Tomcat's
    Engine `jvmRoute` and the requested session id's route suffix.
  - No session replication row: Joget's `web.xml` has no `<distributable/>`, so Tomcat never copies sessions between
    nodes and logins need sticky sessions.
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
2. `exportAtStop` writes the body and the capture's lines so far (`SystemLogStore.writeLines`; lines other nodes
   haven't flushed are missing) next to the capture, decides which apps the user may have exported (`exportJob`, on
   the request thread: the checks need the request and its user), and queues the export into the pending folder's
   `app/` on `ExportJobs` (one background thread).
3. The state is kept in the capture's `meta.json` as `appExport`: `off`, `exporting` (with `at`), `done` (with `saved`
   and `sampleData`) or `failed`.
4. `recording-stop` waits up to 20 s (`STOP_SYNC_MS`, under common proxy timeouts such as nginx's 60 s) and answers
   the state, `exporting` if it runs on. The recorder then polls `GET app-export&id=<capture>` (owner only) every 3 s,
   up to 150 s, and saves a summary in `recording.systemLog.appExport`: `{state, sampleData, apps}` (`apps` = how many).
5. `create` also waits up to 20 s while it is `exporting`:
   - `done`: `attach` moves `app/` into the recording and `meta.apps` takes `saved`; `off` exports nothing.
   - still `exporting`: `attach` leaves `app/` (`withApps` false), the recording gets `appExport: {state: exporting,
     capture}`, and `deliverStopExport` moves `app/` in and sets `apps` when the export ends (called by the job at its
     end and by `create` once more; it acts only while the recording still waits for that capture). No second export.
6. Fallback: with no usable export at stop (no capture, a body that never arrived, `failed`, a capture of another
   user), `create` queues an export of the uploaded files (`apps` / `sampleData` from the upload: the form, or
   `recording.options` when automatic) and answers at once; `meta.appExport` says `exporting` until it ends, then
   `done` or `failed`. The replay page shows **Exporting apps…** and reloads when it is done.

`me.options` (`quickSettings`) says what this user may get, for the popup and the upload form alike: `systemLog`,
`apps` (upload rights and `Access.mayExportApps`: admin, an app admin of some app, or **Who can export apps they
don't administer**), `sample` (`apps` + **Allow capturing form data**), `real` (Mask data: the same as `sample`), `allServer` and `glowrootData`. `me.sampleData: {enabled, real}` repeats `sample` and `real`
for older extensions. Apart from Mask data, a choice can only turn a setting off: `recording-start` takes
`allServer=false` and `glowrootData=false`.

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
problems}]` for the list (hidden by `hideServerData`, see the API). `appfile` serves only names
matching `<id>-<n>.jwa`.

**Who may have an app exported** (`Access.canExportApp`, checked per app inside the export, so it holds at stop and at
upload; a refused app throws `AppExport.Denied`, is listed as `denied` and doesn't use up `maxApps`; its sample rows
are never read):

- Joget admins;
- the app's own admins, read as Joget 8.2's App Composer does (`EnhancedWorkflowUserManager.checkCustomAppAdmin`):
  `ROLE_ADMIN` (usernames) and `ROLE_ADMIN_GROUP` (group ids, the user's groups from `directoryManager`) in
  `app_src/<appId>/app.properties`, split on `;` or `,` (`AppAdmins`; read directly, since
  `AppDevUtil.getAppDevProperties` creates the folder when missing);
- users allowed by **Who can export apps they don't administer** (`appExportPermission`, empty = nobody), only for apps
  Joget lets them access: one of the app's userviews authorizes them by Joget's own check
  (`UserviewService.getUserviewSetting(...).isIsAuthorize()`: `permission` and `permission_rules`). An app with no
  userview is never theirs.

**Uploaders can view the server data…** never grants an export; it only lets owners see what was exported.

`viewer.js` repeats `appOf` to draw the Apps lane (segments from the same page signals, flips under 0.5 s merged), the
App filter and the App tab's problem lists, so the lane works for local recordings too.

### Sample data

`recording-stop` and `create` take `sampleData=none|masked|real` (missing: masked). `AppExport.save` calls an `AfterExport` hook per saved `.jwa`: `SampleData.build` with one
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
| `Activator` | Registers `BlackBoxPlugin`, `BlackBoxPageFilter`, `BlackBoxApiFilter` and `SystemLogFilter` |
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
| `AppExport` | Finds the apps in the recorded URLs, exports them (at stop, else at upload), lists them in `app/apps.json` |
| `AppAdmins` | An app's own admins from `app_src/<app>/app.properties`, as App Composer reads them |
| `ExportJobs` | The background thread app exports run on, so requests answer within about 20 s |
| `SampleData` | Picks the sample rows of an exported app, masks / remaps them, adds them to the `.jwa` |
| `ProcessData` | The touched process instances of an exported app, read only, masked like the sample |
| `Masker` | Shape-keeping fake values, kept values, user pseudonyms, the old -> new record id map |
| `GaugeSampler` | Per node: samples heap, GC, CPU, busy threads and DB pool every second while a capture is open |
| `GaugeStore` | `gauges/` files: append, merge per node, move on upload, ZIP import |
| `TraceStore` | `traces/` files: append, merge (latest state per request), move on upload, summary of a trace |
| `ServerData` | The `serverData` summary in `meta.json` for the list (traces, peak load, Info, Glowroot state), made at upload and refreshed as traces arrive |
| `src/main/resources/static/blackbox/` | Pages: list, view (+ shared viewer from the extension) |

## Extension parts

| Part | Job |
|---|---|
| `popup.js` | Record this tab / a window / the screen, Stop; gets the tab-capture stream id (the video for "this tab", the tab's sound for a window or the screen); system logs warning; quick settings (from `me.options`, cached as `quickOffer`) |
| `recorder.js` | Shows the `desktopCapture` picker (window/screen) or uses the tab stream, mixes page sound and microphone into one track, records with `MediaRecorder`, saves progress every 5 s, sends `recording-stop` with the app export's input, keeps the recording in IndexedDB |
| `background.js` | Attaches the debugger (Network, Runtime, Log, Page), fetches response bodies, forwards events to the recorder; starts/stops server data capture; REC badge |
| `viewer.js` | Replay UI shared by every replay page: video + synced lists, timeline, request details, Features drawer |
| `recordings.js` | Recordings page: list with Browser issues; batch Upload, Download ZIP, Delete for ticked rows (Upload off when `me.canUpload` is false) |
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
that showed the picker (tested early in the project).

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
