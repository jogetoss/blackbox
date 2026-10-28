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
- **Quick settings:** the extension's popup picks some options for each recording. See
  [Per-recording choices](#per-recording-choices-popup).
- **Downloads:** **Download ZIP** (to import on another server or in the extension) and **Download HTML** (one file
  that replays anywhere, no Joget needed). Both include the server data only for users allowed to see it, so share
  them only with people who may see it.

## Settings

This section is the one place that says what each setting turns on, who gets what, and what the extension's popup
can change. The sections after it describe the data itself.

### Plugin settings

**Settings > Manage Plugins > BlackBox > Configure** (system-wide). The permission settings take a Joget permission
plugin (user, group, role, department, BeanShell…).

**Access**

| Setting | Default | Notes |
|---|---|---|
| Who can upload | any logged-in user | |
| Who can view all recordings | admins only | |
| Who can delete any recording | admins only | Only recordings they can view |
| Uploaders can view and delete their own recordings | Yes | |
| Max file size (MB) | 500 | For the video and session.json separately |

**System logs** (everything in this group needs **Capture system logs**)

| Setting | Default | Notes | Popup checkbox |
|---|---|---|---|
| Capture system logs during recordings | No | Also needed by the Info tab | |
| Who can view system logs | admins only | See [Who sees what](#who-sees-what) | |
| Uploaders can view the server data of their own recordings | No | See [Who sees what](#who-sees-what) | |
| Max size of system logs per recording (MB) | 20 | Later lines are dropped; the viewer says it was cut off | |
| Delete system logs not uploaded after (days) | 7 | Also deletes app exports never uploaded | |
| Sample server load during recordings | Yes | | |
| Copy Glowroot traces of slow and failed requests | Yes | | |
| Copy Glowroot data for the recording | No | Needs Glowroot 0.14 | **Glowroot data** |
| Also capture all other server log lines during recordings | No | Other threads and users; only for recorders in **Who can view system logs** | **All server log lines** |
| Glowroot address | empty | Empty = this Joget's own Glowroot agent (usually `http://localhost:4000`) | |

**Support data**

| Setting | Default | Notes | Popup checkbox |
|---|---|---|---|
| Recent changes period (days) | 7 | The Info tab's first section: app versions (only the recording's apps and apps sharing their form tables, one line each) and wflow files | |
| Export the apps used in the recording | Yes | When the recording stops (or at upload without a system log capture) | **Export the apps used** |
| Most apps to export | 5 | 0 = no limit | |
| Allow capturing form data | Yes | Sample form data in the exported apps; needs **Export the apps used** | **Sample form data**, **Mask data** |
| Newest rows per table when the recording touched none | 20 | | |
| Record ids in sample data | New unique ids | New ids: importing never overwrites existing rows. Or **Keep the original ids** | |
| Always-masked column names | password, ic, email, phone, address… | Words in column names; masked even with real values | |

### Who sees what

Admins can always do everything below.

| What | Who |
|---|---|
| Upload | **Who can upload** |
| View a recording | **Who can view all recordings**, or its uploader (with **Uploaders can view and delete…**) |
| Delete a recording | **Who can delete any recording** (only recordings they can view), or its uploader (same setting) |
| A recording's server data: system logs, traces, server load, Glowroot data, Info, exported apps and their lanes in the recordings list | **Who can view system logs** (who must also be able to view the recording), or its uploader with **Uploaders can view the server data…**. App definitions can hold plugin settings and environment variables |
| Sample form data in their recordings | Users who can see their own recordings' server data (row above) |
| Capture all server log lines | **Who can view system logs** only; the uploader setting doesn't count, since the lines hold other users' data |
| Downloads (ZIP, HTML) | Anyone who can view the recording; the server data is included only for users who may see it |

### Per-recording choices (popup)

The extension popup's **Next recording** section shows a server option only when the settings and the user's rights
allow it. The user must be logged in to the Upload URL's server for the popup to ask.

| Popup checkbox | Shown when | Default |
|---|---|---|
| **Export the apps used** | **Export the apps used in the recording** is on | ticked |
| **Sample form data** | **Allow capturing form data** is on, and the user can see their own recordings' server data | ticked |
| **Mask data** | Same as **Sample form data** | ticked |
| **All server log lines** | **Capture system logs** + **Also capture all other server log lines** are on, and the user is in **Who can view system logs** and can upload | ticked |
| **Glowroot data** | **Capture system logs** + **Copy Glowroot data** are on, and the user can upload | ticked |

- Each choice can only turn a setting off for one recording. **Mask data** is the exception: unticking it keeps real
  values (always-masked columns stay masked; files over 5 MB, or 50 MB per app, become placeholders).
- **Sample form data** needs **Export the apps used** ticked; **Mask data** needs both.
- The server checks each choice again.
- The popup remembers the choices and the recording keeps them, so automatic uploads use them too.
- When the export ran at stop, the upload form says what it made. Otherwise it offers **Export the apps used**,
  **Include sample form data** and **Mask data** (shown by the same rules as the popup), ticked as chosen in the popup.
- Not in the popup (admin only): server load, Glowroot traces, the system log size and keep period.

## Server data

Each kind needs its setting (see [Plugin settings](#plugin-settings)) and is shown only on this plugin's replay page,
to the users in [Who sees what](#who-sees-what).

- **System logs** (**System logs** tab): the Joget log lines (info and up) written while serving the recorded tab's
  requests, plus one entry per request, lined up with the video. A network request's details show its own lines. Not
  captured: work a request hands to another thread.
- **Other server threads** (opt-in): every other log line written while the recording runs (scheduler,
  email, background threads, other users' requests), on the nodes that started or served the recording. Greyed in the
  System logs tab under the filter **This tab + other server threads**; not counted as the tab's errors.
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
  (without the heap and processor values Java and Host already show). **Cluster** says whether Joget runs as a cluster and lists its
  nodes, each with the recorded requests it served; **Node switches** counts how often the tab's requests moved
  between nodes: Joget's logins don't follow, so the load balancer needs sticky sessions. Then Tomcat's `jvmRoute`. **Plugin** lists every jar in `app_plugins` with its date and size;
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
title, ⚠ marking apps with problems. Only users who may see server data see them (see [Who sees what](#who-sees-what)).
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

**Real values:** when **Mask data** is unticked (see [Per-recording choices](#per-recording-choices-popup)).

**Record ids** get new unique ids everywhere they appear, so importing never overwrites rows of the same id. The old → new
map stays in `apps.json` (in the ZIP download), not on the page.

**Process data** (with the sample data, read only: Joget can't import running processes): the App tab lists the
app's process instances the recording touched (process and activity ids in the URLs and posts, and in the sample rows):
state, requester, dates, workflow variables, and each activity with its state, assignees and who completed it. Masked
the same way (same user pseudonyms; variable values masked unless found in the app definition). At most 20 processes
per app and 100 activities each; processes already archived out of the running tables are not found.

## Storage

One folder per recording under `<wflow>/blackbox/<id>/`, no database tables. A shared `wflow` folder works for
clustered nodes. Back it up or clean it like any other `wflow` data; deleting a recording removes its folder.
