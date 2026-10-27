# BlackBox Joget plugin

BlackBox Chrome extension records both client-side browser activity (video, audio, console, network, and navigation) and server-side Joget data (system logs, Glowroot traces, server load, and system info snapshots). Recorded sessions can be exported as self-contained HTML or ZIP files for easy playback without needing the plugin.

Internals, API and storage: [ARCHITECTURE.md](ARCHITECTURE.md).

## Build and install

```bash
mvn -o package                                          # runs the unit tests too
cp target/blackbox-8.2-SNAPSHOT.jar <wflow>/app_plugins/ # or upload in Settings > Manage Plugins
```

Built against `wflow-core` **8.2-SNAPSHOT** (javax.servlet). The jar includes the extension's replay viewer, so
rebuild it after changing `chrome-extension/viewer.js` or `viewer.css`.

## Use

- **Recordings list:** `<context>/blackbox/` (e.g. `http://localhost:8080/jw/blackbox/`). Users who may see server
  data also get **Server issues** and **Server data** columns. Tick rows for **Download ZIP** / **Delete selected**;
  **Import ZIP…** adds a recording ZIP as a new recording owned by you.
- **Replay page:** `<context>/blackbox/view?id=<id>`. **Features** lists everything it can do.
- **Uploading:** in the extension's Settings, set **Upload URL** to `<context>/api/blackbox`. Uploads use your Joget
  login in that browser. When a recording stops and you are logged in, the extension uploads it and opens this replay
  page by itself.
- **Quick settings:** the extension's popup sets the next recording's choices: page sound and microphone, and, as far
  as the settings below and the user's rights allow, **Export the apps used**, **Sample form data**, **Mask data**,
  **All server log lines** and **Glowroot data**. Unticking **Mask data** keeps real values; the others can only
  turn a setting off for one recording. The server checks each again. The popup remembers them, and the recording keeps them, so the automatic upload uses them too.
- **Downloads:** **Download ZIP** (to import on another server or in the extension) and **Download HTML** (one file
  that replays anywhere, no Joget needed). Both include the server data only for users allowed to see it, so share
  them only with people who may see it.

## Settings

**Settings > Manage Plugins > BlackBox > Configure** (system-wide). **Admins can always upload, view and delete.**
The permission settings take a Joget permission plugin (user, group, role, department, BeanShell…).

| Setting | Default | Meaning |
|---|---|---|
| Who can upload | any logged-in user | |
| Who can view all recordings | admins only | |
| Who can delete any recording | admins only | Only recordings they can view |
| Uploaders can view and delete their own recordings | Yes | |
| Max file size (MB) | 500 | For the video and session.json separately |
| Capture system logs during recordings | No | Needed by all the server data below |
| Who can view system logs | admins only | All server data; they must also be able to view the recording |
| Uploaders can view the server data of their own recordings | No | Without being in "Who can view system logs" |
| Max size of system logs per recording (MB) | 20 | Later lines are dropped; the viewer says it was cut off |
| Delete system logs not uploaded after (days) | 7 | |
| Sample server load during recordings | Yes | |
| Copy Glowroot traces of slow and failed requests | Yes | |
| Also capture all other server log lines during recordings | No | Other threads and users; only for "Who can view system logs" recorders |
| Copy Glowroot data for the recording | No | Needs Glowroot 0.14 |
| Glowroot address | empty | Empty = this Joget's own Glowroot agent (usually `http://localhost:4000`) |
| Recent changes period (days) | 7 | The Info tab's first section: app versions (only the recording's apps and apps sharing their form tables, one line each) and wflow files |
| Export the apps used in the recording | Yes | When the recording stops; shown like server data |
| Allow capturing form data | Yes | Sample form data in the exported apps, masked unless **Mask data** is unticked; only for uploaders who may see the recording's server data |
| Newest rows per table when the recording touched none | 20 | |
| Record ids in sample data | New unique ids | Importing never overwrites existing rows |
| Always-masked column names | password, ic, email, phone, address… | Words in column names; masked even with real values |

## Server data

All of it needs **Capture system logs**, is shown only on this plugin's replay page, and only to users allowed by
**Who can view system logs** (or to the uploader, with **Uploaders can view the server data…**).

- **System logs** (**System logs** tab): the Joget log lines (info and up) written while serving the recorded tab's
  requests, plus one entry per request, lined up with the video. A network request's details show its own lines. Not
  captured: work a request hands to another thread.
- **Other server threads** (opt-in setting): every other log line written while the recording runs (scheduler,
  email, background threads, other users' requests), on the nodes that started or served the recording. Greyed in the
  System logs tab under the filter **This tab + other server threads**; not counted as the tab's errors. Only for
  recordings started by users allowed by **Who can view system logs**.
- **Glowroot traces** (**Traces** tab): the [Glowroot](https://glowroot.org) trace of each recorded request that
  Glowroot kept one for (slow or failed, by Glowroot's own settings, which BlackBox never changes). Failed lookups
  have **Retry**. Glowroot must allow anonymous access.
- **Glowroot data** (**Download Glowroot data**): the recording cut out of Glowroot's own data, about a minute after
  the stop, to open in a standalone Glowroot 0.14.2 (steps in its `README.txt`). About 1 MB for a 35 s recording.
  Building it needs free space in Joget's temp folder about the size of Glowroot's `data.h2.db`; check that on busy
  servers before turning it on.
- **Server load** (strip under the timeline): CPU, heap, busy request threads, DB connections and GC time, sampled
  every second on each node. No Glowroot needed.
- **System Info** (**Info** tab): the server at recording start. The sections whose changes can be tracked come
  first: **Changes in the last 7 days** (setting; hidden when there are none): app versions edited or published and
  wflow files (such as `app_datasource-*.properties`) changed; **Joget**, with its start time and uptime. Then
  **Plugin**, host (total and free RAM), cluster, Java, database settings, **DB pool**, license and system properties
  (without the heap and processor values Java and Host already show). **Cluster** shows Joget's node name and node lists, Tomcat's
  `jvmRoute` and session replication, and which node served each recorded request: **Sticky sessions** warns when the
  tab's requests moved between nodes. **Plugin** lists every jar in `app_plugins` with its date and size;
  "not loaded" means the jar failed to load (the Joget log says why).

**Sensitive data:** traces and Glowroot data hold SQL with its parameter values and request details; System Info holds
JVM arguments and system properties, which can include secrets passed as `-D` options. Share recordings accordingly.

## App export

When the recording stops, BlackBox finds the Joget apps the recorded tab used (userview, embed, form/datalist, App Composer and JSON
API paths, `appId`/`appVersion` parameters, process ids like `app#2#process`) and exports them with Joget's own app
export (`.jwa`: the definition only, no form data, plugins or groups). Apps with problems come first (failed requests,
server errors, console errors on their pages), then the most used; **Most apps to export** (default 5, 0 = no limit)
caps them. App icons and screenshots don't count (App Center loads them for every app), and App Center itself is
exported only when it had problems or was the only app. Apps come from the browser's requests; the system log only adds
to them, since it can also hold other tabs' service worker requests. The version is the one a URL names, else the
published one, else the latest.

The export, with its sample and process data, shows the apps and records **as they were when the recording stopped**.
The extension sends the recorded requests and console errors with `recording-stop` and waits for the export (up to
150 s), then the upload adds it to the recording. Exported but never uploaded, it is deleted with the system logs after
**Delete system logs not uploaded after (days)**. Without a system log capture (setting off, or the server could not be
reached when recording started), the export runs at upload instead, with the choices saved with the recording.

The **App** tab starts with how many apps were used. Apps with problems come first; each app
is folded to its requests, time in use and **Download .jwa**, and opens to its id, version, sample data mode, the captured
tables with their rows, and its processes. Errors are in the Console, Network and System logs tabs (**App** filter).
Apps not exported are one grey line. The recordings list shows the app names under the
title, ⚠ marking apps with problems. Only users allowed by **Who can view system logs** (or the uploader, with
**Uploaders can view the server data…**) see them: app definitions can hold plugin settings and environment variables.
The ZIP download includes the `.jwa` files; the HTML download shows the list only.

On every replay page (no upload needed), the **Apps** lane under the Issues timeline shows which app's page the tab
showed, one colour per app, ⚠ where it had a problem; hover for its time, requests and problems, click to jump there
and list only that app. The **App** filter on the Network and System logs tabs does the same.

## Sample data

Each exported `.jwa` gets sample form data the way Joget's own export adds it (`data/<table>.json`,
`app_formuploads/`), so **importing the `.jwa` imports the rows too** (unless the import's "do not import form data"
is ticked). Rows picked, per app table:

- **Opened or saved in the recording:** record ids in the recorded URLs and form posts (`id=`, UUIDs, process and
  activity ids; an activity id brings its process id, the record id of workflow forms).
- **Linked:** rows with a column pointing at those (grid and subform rows, logs by process id), and rows those point at.
- **Newest:** for tables with none of the above, the newest rows (setting).

**Masked** (default): each letter becomes a letter of the same case, each digit a digit; spaces, punctuation and
symbols stay, so length and format stay. The same value gets the same fake everywhere in the recording. Kept as they are:
the forms' option values, values found in the app's own definition (status names, activity ids, hash variables),
yes/no/true/false, numbers of up to 5 digits, UUIDs and process ids. Dates keep their year. User names become `user1`,
`user2`… in every column. Uploaded files become small placeholder files with masked names. Columns named like a
sensitive field (setting) are always masked.

**Real values:** for any uploader who gets sample data, by unticking **Mask data** in the popup (per recording). Sensitive columns stay masked; files over 5 MB (50 MB per app) become placeholders.

**Record ids** get new unique ids everywhere they appear, so importing never overwrites rows of the same id. The old → new
map stays in `apps.json` (in the ZIP download), not on the page.

**Process data** (with the sample data, read only: Joget can't import running processes): the App tab lists the
app's process instances the recording touched (process and activity ids in the URLs and posts, and in the sample rows):
state, requester, dates, workflow variables, and each activity with its state, assignees and who completed it. Masked
the same way (same user pseudonyms; variable values masked unless found in the app definition). At most 20 processes
per app and 100 activities each; processes already archived out of the running tables are not found.

The popup's **Sample form data** and **Mask data** (ticked by default)
pick the sample per recording. When the export ran at stop, the upload form says what it made; otherwise it offers
**Export the apps used**, **Include sample form data** and, where allowed, **Mask data**, ticked as chosen in the popup. Automatic uploads use the popup's
choices.

## Storage

One folder per recording under `<wflow>/blackbox/<id>/`, no database tables. A shared `wflow` folder works for
clustered nodes. Back it up or clean it like any other `wflow` data; deleting a recording removes its folder.
