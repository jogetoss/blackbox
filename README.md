# BlackBox Joget plugin

Joget DX 8.2 plugin that stores and replays recordings made by the BlackBox Chrome extension
(`chrome-extension/`): `recording.webm` (video) + `session.json` (console, network, navigations).

Built against `wflow-core` **8.2-SNAPSHOT** (javax.servlet). Jar: `target/blackbox-8.2.0.jar`.

## Build and install

```bash
mvn -o package                                   # runs the unit tests too
cp target/blackbox-8.2.0.jar <wflow>/app_plugins/ # or upload in Settings > Manage Plugins
```

The build copies `chrome-extension/viewer.js` and `viewer.css` into the jar, so the replay viewer is the same code
in the extension and in Joget. Rebuild the jar after changing them.

## Use

| Page | URL |
|---|---|
| Recordings list | `<context>/blackbox/` (e.g. `http://localhost:8080/jw/blackbox/`) |
| Replay | `<context>/blackbox/view?id=<id>` — video with synced console/network lists, downloads, delete |

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

## Storage

One folder per recording under `<wflow>/blackbox/<id>/`: `meta.json`, `session.json`, `recording.webm`.
No database tables. Folders on a shared `wflow` directory work for clustered nodes. Back up or clean that folder like
any other `wflow` data; deleting a recording removes its folder.

## How it works

| Part | Job |
|---|---|
| `BlackBoxPlugin` | System settings + API logic: `me`, `list`, `meta`, `session`, `video` (GET with HTTP Range), `create`, `video` (POST), `delete` |
| `BlackBoxApiFilter` | Serves the API at `/api/blackbox?action=…` |
| `BlackBoxPageFilter` | Serves the static pages at `/blackbox/…` |
| `RecordingStore` | Folder storage, validation (JSON shape, WebM header, size limits, id format) |
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
- The extension needs host access to the Joget server; it asks for it in its Settings.
