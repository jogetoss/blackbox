# BlackBox Joget plugin

The BlackBox Chrome extension records the browser side of a problem (video, sound, console, network, navigation).
This plugin stores those recordings in Joget, adds the server side (system logs, Glowroot traces, server load, system
info, the apps used) and replays both together. Recordings download as ZIP or as one HTML file that replays anywhere.

Internals, API and storage: [ARCHITECTURE.md](ARCHITECTURE.md). Extension: [chrome-extension/README.md](chrome-extension/README.md).
Browser tests: [e2e/README.md](e2e/README.md).

## Build and install

```bash
mvn -o package                                          # runs the unit tests too
cp target/blackbox-8.2-SNAPSHOT.jar <wflow>/app_plugins/ # or upload in Settings > Manage Plugins
```

Built against `wflow-core` 8.2-SNAPSHOT (javax.servlet). The jar includes the extension's replay viewer: rebuild it
after changing `chrome-extension/viewer.js` or `viewer.css`.

## Upgrading

**Breaking in the next release (after 8.2.1): three settings were renamed.** Their old values are not read, so after
the upgrade they fall back to their defaults. Open the plugin's settings and set them again:

| Old setting (key) | New setting (key) | Default after upgrade |
|---|---|---|
| Capture system logs during recordings (`captureSystemLogs`) | Capture server data during recordings (`captureServerData`) | No: server data stops being captured |
| Who can view system logs (`systemLogPermission`) | Who can view server data (`serverDataPermission`) | admins only |
| Delete system logs not uploaded after (days) (`systemLogKeepDays`) | Delete server data not uploaded after (days) (`serverDataKeepDays`) | 7 |

The old names said "system logs", but these settings cover all server data: logs, traces, server load, Glowroot data,
Info (license key, system properties) and the exported apps. Also renamed: the `canViewSystemLog` flag in the API's
recording JSON is now `canViewServerData`.

**Also renamed: the capture summary `systemLog` is now `serverCapture`** (in `meta.json`, in `session.json`'s
`recording`, and in the `recording-start` answer, which still also sends `systemLog` for the extension of plugin 8.2.1). Existing
recordings, ZIPs and the extension of plugin 8.2.1 keep working: their `systemLog` is still read. One case breaks: **the current extension with plugin 8.2.1 or older** uploads recordings without
their server data. Upgrade the plugin before (or with) the extension.

## Use

- **Recordings list:** `<context>/blackbox/` (server columns only for users who may see server data). Tick rows to **Download ZIP** or **Delete selected**. **Import ZIP…**
  adds a ZIP as a new recording owned by you.
- **Replay page:** `<context>/blackbox/view?id=<id>`. **Features** in its header lists what it can do.
- **Uploading:** set the extension's **Upload URL** to `<context>/api/blackbox`. Uploads use your Joget login in that
  browser; when you are logged in, a stopped recording uploads and opens here by itself.
- **Downloads:** **Download ZIP** (to import elsewhere) and **Download HTML** (replays without Joget). Both include
  server data only for users allowed to see it, so share them only with people who may see it.

## Settings

This section is the single place for what each setting does, who gets what, and what the popup can change.

### Plugin settings

**Settings > Manage Plugins > BlackBox > Configure** (system-wide). Permission settings take a Joget permission plugin
(user, group, role, department, BeanShell…).

**Access**

| Setting | Default | Notes |
|---|---|---|
| Who can upload | any logged-in user | |
| Who can view all recordings | admins only | |
| Who can delete any recording | admins only | Only recordings they can view |
| Uploaders can view and delete their own recordings | Yes | |
| Max file size (MB) | 500 | Video and session.json each |

**Server data** (the capture settings need **Capture server data**)

| Setting | Default | Notes | Popup checkbox |
|---|---|---|---|
| Capture server data during recordings | No | Logs, Info tab, and the settings below; apps exported at stop | |
| Max recording length (minutes) | 30 | 5–120. The recorder stops and saves at this length; see below | |
| Who can view server data | admins only | See [Who sees what](#who-sees-what) | |
| Uploaders can view the server data of their own recordings | No | See [Who sees what](#who-sees-what) | |
| Max size of system logs per node per recording (MB) | 20 | Each server node's lines; later lines are dropped and the viewer says so | |
| Delete server data not uploaded after (days) | 7 | Logs, load, traces, Info and app exports never uploaded | |
| Sample server load during recordings | Yes | | |
| Copy Glowroot traces of slow and failed requests | Yes | | |
| Copy Glowroot data for the recording | No | Needs Glowroot 0.14 | **Glowroot data** |
| Also capture all other server log lines during recordings | No | Other threads and users | **All server log lines** |
| Glowroot address | empty | Empty = this Joget's own agent (usually `http://localhost:4000`) | |

**Support data**

| Setting | Default | Notes | Popup checkbox |
|---|---|---|---|
| Recent changes period (days) | 7 | Info tab: changed app versions and wflow files; needs **Capture server data**; at most 365 | |
| Export the apps used in the recording | Yes | | **Export the apps used** |
| Most apps to export | 5 | 0 = no limit (0–9999; anything else means 5); apps not allowed don't count | |
| Who can export apps they don't administer | nobody | Users chosen here get only apps Joget lets them access | |
| Allow capturing form data | Yes | Needs **Export the apps used** | **Sample form data**, **Mask data** |
| Newest rows per table when the recording touched none | 20 | At most 500 | |
| Record ids in sample data | New unique ids | New ids: importing never overwrites rows. Or **Keep the original ids** | |
| Always-masked column names | password, ic, email, phone, address… | Words in column names; masked even with real values | |

### Who sees what

Admins can always do everything.

| What | Who |
|---|---|
| Upload | **Who can upload** (others get **Upload selected** disabled in the extension) |
| Import a ZIP | **Who can upload** |
| View a recording | **Who can view all recordings**, or its uploader (**Uploaders can view and delete…**) |
| Delete a recording | **Who can delete any recording** (if they can view it), or its uploader (same setting) |
| See a recording's server data: system logs, traces, server load, Glowroot data, Info, exported apps | **Who can view server data** (if they can view the recording), or its uploader with **Uploaders can view the server data…** |
| Have an app exported (definition and sample rows, masked or real) | The app's own admins (its App Composer admins), or **Who can export apps they don't administer** for apps Joget lets them access (one of the app's userviews allows them). Other apps are listed as not exported |
| Capture all server log lines | **Who can view server data** only: the lines hold other users' data |
| Import a ZIP's server data | **Who can view server data** only: a ZIP can hold another user's recording. The list page says when it was left out |
| Downloads (ZIP, HTML) | Anyone who can view the recording; server data only for those who may see it |

### Per-recording choices (popup)

The popup's **Next recording** shows a server option only when the settings and the user's rights allow it (the user
must be logged in to the Upload URL's server). All are ticked until the user changes them; the choices are
remembered for later recordings.

| Popup checkbox | Shown when |
|---|---|
| **Export the apps used** | **Export the apps used in the recording** is on, the user can upload, and they may have some app exported (see above) |
| **Sample form data**, **Mask data** | **Allow capturing form data** is on, and **Export the apps used** is shown |
| **All server log lines** | **Capture server data** + **Also capture all other server log lines** are on, and the user is in **Who can view server data** and can upload |
| **Glowroot data** | **Capture server data** + **Copy Glowroot data** are on, and the user can upload |

- Each choice can only turn a setting off, except **Mask data**: unticking it keeps real values (the popup warns).
- **Sample form data** needs **Export the apps used**; **Mask data** needs both.
- The server checks every choice again. The recording keeps the choices, so automatic uploads use them too.
- If the apps weren't exported at stop, the upload form offers the same three app choices.
- Admin-only (not in the popup): server load, Glowroot traces, system log size and keep period.

## Server data

Shown on this plugin's replay page and included in the ZIP and HTML downloads, only for the users in
[Who sees what](#who-sees-what).

- **System logs** tab: Joget log lines (the same as `joget.log`: info and up by default) written while serving the recorded tab's requests, plus one entry
  per request, lined up with the video; a network request's details show its own lines. Not captured: work a request hands to another thread.
- **Other server threads** (opt-in): every other log line during the recording (scheduler, email, other users), on
  the nodes the recording reached. Greyed under the filter **This tab + other server threads**, not counted as errors.
- **Traces** tab: the [Glowroot](https://glowroot.org) trace of each recorded request Glowroot kept (slow or failed,
  by Glowroot's own settings). Failed lookups have **Retry**. Glowroot must allow anonymous access.
- **Download Glowroot data**: the recording's slice of Glowroot's data, ready 30–90 s after the stop, to open in
  a standalone Glowroot 0.14.2 (see its `README.txt`). About 1 MB for 35 s. Building it needs free temp space about the
  size of Glowroot's `data.h2.db`: check that on busy servers.
- **Server load** strip: CPU, heap, busy request threads, DB connections and GC time per node, every second. No
  Glowroot needed.
- **Info** tab: the server at recording start.
  - **Changes in the last *N* days** (**Recent changes period**; hidden when none): versions edited or published of
    the apps the recording used and of apps sharing their tables, changed wflow files.
  - **Joget** (start time, uptime), **Plugin** (every jar in `app_plugins`; "not loaded" = failed, see the Joget log),
    host, Java, database settings, **DB pool**, license, system properties.
  - **Cluster**: its nodes and the requests each served. **Node switches** counts requests that moved between nodes;
    Joget logins don't follow unless **Session replication** is on (a Tomcat cluster, Enterprise only), so the load
    balancer needs sticky sessions.

While recording, the extension tells the server every 30 s that the recording is still running. If the server has
already ended the capture, the recording stops (reason `server-stopped: …`). If the user is logged out, the recorder
shows **Server capture ended: logged out** and records on without server data. The pings keep the Joget session alive.
When no ping arrives for 3 minutes (the browser crashed, was killed or lost the network), the server ends the
capture itself, at the time of the last ping: its log lines, server load and Glowroot data stop there, with stop reason
`browser-lost`. A capture that never got a ping (an older extension) is not ended this way. When the extension
recovers a recording after a crash, it ends the server capture at the recording's last save (every 5 s), so the
server data ends where the video ends. The server only takes that time if it fits the capture (after its start, at
most 3 minutes after the last ping); otherwise it uses the last ping.

Recordings with server data have a hard limit, **Max recording length** (30 minutes by default; at Chrome's default
bitrate a longer video can pass the 500 MB upload limit). The recorder shows the time left, warns 2 minutes before,
and at the limit stops and saves the recording as if the user pressed stop (stop reason `time-limit`; the apps are
exported as usual). If the browser doesn't stop it, the server ends the capture 2 minutes after the limit, at the
limit, with stop reason `time-limit`, pings or not. This also bounds how long the pings keep the Joget session alive.

**Sensitive data:** traces and Glowroot data hold SQL with parameter values and request details; Info holds JVM
arguments and system properties, which can include secrets passed as `-D` options; app definitions can hold plugin
settings and environment variables.

## App export

When the recording stops, BlackBox exports the Joget apps the tab used as `.jwa` files (definition only: no form data,
plugins or groups), **as they were at that moment**. Apps with problems come first, then the most used, up to **Most
apps to export**. The version is the one the URL names, else the published one, else the latest. App Center is only exported when it had problems or was the only app. If the export runs long, the recording uploads without waiting and the apps are added when it ends (the replay page shows **Exporting apps…**). If server data wasn't
captured (or the export at stop failed), the export runs at upload instead.

- **App** tab: each app's requests, time in use, **Download .jwa**, sample data and processes. Apps not exported are
  one grey line.
- **Recordings list:** app names under the title, ⚠ for apps with problems.
- **Apps lane** (under the Issues timeline, every replay page): which app the tab showed when; click to filter. The
  Network and System logs tabs have the same **App** filter.
- The ZIP includes the `.jwa` files; the HTML download lists the apps only.

## Sample data

Each `.jwa` gets sample rows the way Joget's own export adds them, so **importing the `.jwa` imports the rows too**
(unless "do not import form data" is ticked). Rows per app table:

- **Opened or saved** in the recording (record, process and activity ids in URLs and form posts);
- **Linked** to those (grid and subform rows, rows they point at);
- otherwise the **newest** rows (setting).

**Masked** (default): letters and digits are replaced with the same kind, so length and format stay; the same value
gets the same fake throughout. Kept: option values, values in the app definition, yes/no/true/false, numbers up to 5
digits, UUIDs, process ids. Dates keep their year. User names (from created by / modified by) become `user1`, `user2`… wherever they appear. Uploaded files become
placeholders. Always-masked columns stay masked even with **real values** (Mask data unticked), where files over 5 MB
(50 MB per app) also become placeholders.

**Record ids** are replaced with new ones by default, so importing never overwrites rows; the old → new map is in the
ZIP's `apps.json`.

**Process data** (read only; Joget can't import running processes): the processes the recording touched, with
state, requester, dates, variables and each activity's state and assignees. Masked the same way. At most 20 processes
per app and 100 activities each; archived processes are not found.

## Storage

One folder per recording under `<wflow>/blackbox/<id>/`, no database tables. Server data waiting for its recording's
upload is in `<wflow>/blackbox/pending/` (deleted after **Delete server data not uploaded after**), trace retries in
`<wflow>/blackbox/retry/`. A shared `wflow` folder works for clusters. Back it up like other `wflow` data; deleting a
recording removes its folder.

For tests only: an optional `<wflow>/blackbox/liveness-test.json` (`pingMs`, `lostMs`, `checkMs`, `minLimitMs`, each at
least 1000 ms) shortens the liveness timings without a restart. It is not a setting and isn't shown in the plugin
configuration. Delete it afterwards; while it is in use the log shows a warning. See ARCHITECTURE.md, Storage.
