# BlackBox Joget plugin

Joget DX 8.2 plugin that stores and replays recordings made by the BlackBox Chrome extension
(`chrome-extension/`): `recording.webm` (video) + `session.json` (console, network, navigations).

Built against `wflow-core` **8.2-SNAPSHOT** (javax.servlet). Jar: `target/blackbox-<version>.jar`.

## Build and install

```bash
mvn -o package                                          # runs the unit tests too
cp target/blackbox-8.2-SNAPSHOT.jar <wflow>/app_plugins/ # or upload in Settings > Manage Plugins
```

## Versions

The first two numbers are the Joget line the plugin runs on (8.2 = Joget DX 8.2); the third is the plugin's own
patch level.

| Version | Tag | Meaning |
|---|---|---|
| `8.2-SNAPSHOT` | none | Work in progress on the `8.2-SNAPSHOT` branch, between releases |
| `8.2.0-beta.N` | `8.2.0-beta.N` | Unstable: features may still change |
| `8.2.0-rc.N` | `8.2.0-rc.N` | Release candidate: feature-complete, testing only |
| `8.2.0`, `8.2.1` | `8.2.0`, `8.2.1` | Stable release, then bug-fix releases |

Release steps: set the version in `pom.xml` and `BlackBoxPlugin.VERSION`, commit `Plugin <version>`, tag it
(annotated, message naming the plugin and extension versions), then commit `Back to 8.2-SNAPSHOT after the <version>
tag`. Maven sorts `beta < rc < SNAPSHOT < release`; `PREVIEW` is not a Maven qualifier and sorts after the release, so
it is no longer used (`8.2-PREVIEW` is the last such tag).

The extension: Chrome's `version` takes only 1–4 numbers, so a pre-release is `"version": "0.10.0.1"` with
`"version_name": "0.10.0-beta.1"` (the name Chrome shows); the stable release drops the 4th number and the name.

The build copies `chrome-extension/viewer.js` and `viewer.css` into the jar, so the replay viewer is the same code
in the extension and in Joget. Rebuild the jar after changing them.

## Use

| Page | URL |
|---|---|
| Recordings list | `<context>/blackbox/` (e.g. `http://localhost:8080/jw/blackbox/`) |
| Replay | `<context>/blackbox/view?id=<id>` — video with synced console/network lists, downloads, delete |

**Download ZIP** (Replay page, and **ZIP** in the list) saves the recording as one ZIP: `meta.json` (title, notes,
who uploaded it), `session.json`, `server-log.json` (only for users allowed to view server logs) and `recording.webm`.
**Import ZIP…** on the Recordings page turns such a ZIP into a new recording owned by the importer (the first
uploader is kept as "imported; first uploaded by …"); its server log is kept only if the importer may view server
logs. The BlackBox extension makes and opens the same ZIP, so recordings move between servers and browsers.

**Download HTML** on the Replay page saves one `.html` file that replays the recording in any browser, with no Joget,
plugin or extension: the viewer, `session.json` and the video (base64) are inside it. The server log is included for
users allowed to view it, so share the file only with people who may see it. The file is about 1.35× the video size.

Recordings are uploaded from the extension viewer (**Upload**). In the extension's Settings, set **Upload URL** to
`<context>/api/blackbox` (e.g. `http://localhost:8080/jw/api/blackbox`). Uploads use the user's Joget login in that
browser; this plugin implements the extension's Upload endpoint (`chrome-extension/README.md`) and returns the view page as `url`
after `create`. Logged-out users see "Not logged in" in the extension and log in to Joget in another tab.

## Settings

**Settings > Manage Plugins > BlackBox > Configure** (system-wide). Admins can always upload, view and delete.

| Setting | Default | Meaning |
|---|---|---|
| Who can upload | any logged-in user | A Joget permission plugin (user, group, role, department, BeanShell…) |
| Who can view all recordings | admins only | Same |
| Who can delete any recording | admins only | Applies to recordings they can view |
| Uploaders can view and delete their own recordings | Yes | |
| Max file size (MB) | 500 | For the video and session.json separately |
| Capture server logs during recordings | No | See [Server logs](#server-logs) |
| Who can view server logs | admins only | A Joget permission plugin; they must also be able to view the recording |
| Max server log size per recording (MB) | 20 | Later lines are dropped and the viewer says it was cut off |
| Delete server logs not uploaded after (days) | 7 | |

## Server logs

With **Capture server logs** on, a recording also keeps the Joget log lines written while serving the recorded tab's
requests. They are shown only on this plugin's replay page (**Server** tab), never in the extension.

1. When recording starts, the extension calls `serverlog-start` with a random id (the user must be logged in and allowed
   to upload), then adds `X-BlackBox-Id: <id>` to the recorded tab's requests to the Upload URL's server.
2. `ServerLogFilter` marks the threads of those requests; an appender on log4j's root logger keeps their log lines (what
   `joget.log` would show, level info and up) plus one entry per request (method, URL, status, time). They go to
   `<wflow>/blackbox/pending/<id>/server-log.<node>.jsonl`.
3. Stop calls `serverlog-stop`. The lines wait on the server until the recording is uploaded; `session.json` carries the
   id, and `create` moves them into the recording as `server-log.json`. Only the user who started the capture can
   attach it. Captures never uploaded are deleted after the keep period.
4. The replay page loads `server-log.json` for users allowed by **Who can view server logs** and lines it up with the
   video. The plugin sends `X-BlackBox-Time` (server clock) on tagged responses; if the server clock differs from the
   browser's by more than 250 ms, the viewer corrects it. A network request's details show its server entry and log lines.
   The Server tab lists log lines, filtered by level with checkboxes (DEBUG, INFO, WARN, ERROR, FATAL). The plugin's
   own request entries (not in `joget.log`) are only shown in a network request's details.

Not captured: work a request hands to another thread, and log lines below the root logger's level.

## Storage

One folder per recording under `<wflow>/blackbox/<id>/`: `meta.json`, `session.json`, `recording.webm`, and
`server-log.json` when a server log was attached. Server logs waiting for an upload are in `<wflow>/blackbox/pending/`.
No database tables. Folders on a shared `wflow` directory work for clustered nodes. Back up or clean that folder like
any other `wflow` data; deleting a recording removes its folder.

## How it works

| Part | Job |
|---|---|
| `BlackBoxPlugin` | System settings + API logic: `me`, `list`, `meta`, `session`, `video` (GET with HTTP Range), `serverlog`, `download` (`format=zip` or `html`), `create`, `import`, `video` (POST), `delete`, `serverlog-start`, `serverlog-stop` |
| `BlackBoxApiFilter` | Serves the API at `/api/blackbox?action=…` |
| `BlackBoxPageFilter` | Serves the static pages at `/blackbox/…` |
| `RecordingStore` | Folder storage, validation (JSON shape, WebM header, size limits, id format) |
| `RecordingZip` | `download&format=zip` and `import`: the recording as one ZIP and back |
| `RecordingHtml` | `download&format=html`: view.html with viewer.css/js inlined, the recording in `<script>` data blocks, `export.js` to open it |
| `ServerLogFilter` | Marks requests that carry `X-BlackBox-Id` (all URLs, before Spring Security) |
| `ServerLogCapture` | Root-logger appender, per-thread marking, pending files, flush/idle/cleanup timer |
| `ServerLogStore` | Pending server log folders: start, stop, attach (merge nodes, sort by time), clean up |
| `src/main/resources/static/blackbox/` | Pages: list, view (+ shared viewer from the extension) |

Notes:
- The API lives at `/api/*` because Spring Security runs there (the logged-in user is known), but Joget's
  `JsonResponseFilter` does not: on `/web/json/*` it rejects requests whose `Referer` is not Joget, and the extension's
  requests have none. The filter answers the request itself, so it never reaches Joget's API Builder servlet.
- CSRF: POSTs are accepted from a `chrome-extension://` `Origin` (no web page can send that) or with the
  `OWASP-CSRFTOKEN` header (the pages get it from `/web/json/directory/user/csrfToken`). CSRFGuard itself only
  covers `/web/*`.
- Uploads are raw request bodies (not multipart): `create` takes `session.json` (title and notes as query
  parameters), then `video` takes `recording.webm`. Only the uploader can add the video, once.
- Error responses: `{"error":{"code":"403","message":"..."}}`.
- Log4j 2 is not visible to plugin bundles (OSGi exports neither `org.apache.logging.log4j` nor `org.apache.log4j`),
  so `ServerLogCapture` reaches it through Joget's classloader by reflection; the appender is a `java.lang.reflect.Proxy`.
- The extension needs host access to the Joget server; it asks for it in its Settings.
