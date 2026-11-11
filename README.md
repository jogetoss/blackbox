# BlackBox Joget plugin

The BlackBox Chrome extension records the browser side of a problem (video, sound, console, network, navigation).
This plugin stores those recordings in Joget, adds the server side (system logs, Glowroot traces, server load, system
info, the apps used) and replays both together. Recordings download as ZIP or as one HTML file that replays anywhere.

**Demo (1:07):** [the bug nobody could reproduce](demo/hero.mp4). More clips (app export, reading a replay, slow
requests, setup): [demo/README.md](demo/README.md).

Internals, API and storage: [ARCHITECTURE.md](ARCHITECTURE.md). Extension: [chrome-extension/README.md](chrome-extension/README.md).
Browser tests: [e2e/README.md](e2e/README.md).

## Build and install

```bash
mvn -o package                                          # runs the unit tests too
cp target/blackbox-8.2-SNAPSHOT.jar <wflow>/app_plugins/ # or upload in Settings > Manage Plugins
```

Built against `wflow-core` 8.2-SNAPSHOT (javax.servlet). The jar includes the extension's replay viewer: rebuild it
after changing `chrome-extension/viewer.js` or `viewer.css`. Install the plugin before (or with) the extension of the
same version.

## Upgrading

### From 8.2.2

**Failed trace lookups of recordings made with 8.2.2 or older lose their Retry button.** Their entries don't carry
the recording's Glowroot tag, which a trace must now have to be matched. Traces already copied stay.

### From 8.2.1

**The plugin settings were reorganised; renamed and removed settings lose their values.** After the upgrade they fall
back to their defaults (see [Settings](#settings)). Open **Configure** and set them again; check the access settings
first, as a restricted install opens up otherwise:

| 8.2.1 setting (key) | Now (key) | Default now |
|---|---|---|
| Who can upload (`uploadPermission`) | Who can upload (`canUpload`) | any logged-in user: **a restriction set in 8.2.1 is lost** |
| Who can view all recordings (`viewPermission`) | removed: admins see every recording, others their own | |
| Who can delete any recording (`deletePermission`) | removed: admins delete any recording, others their own | |
| Uploaders can view and delete their own recordings (`ownerAccess`) | removed: always on | |
| Capture system logs during recordings (`captureSystemLogs`) | removed: every recording is captured | |
| Who can view system logs (`systemLogPermission`) | Who can capture other users' server data (`canCaptureServerData`): **All server log lines** and keeping a ZIP's server data on import | admins only |
| Uploaders can view the server data of their own recordings (`ownServerData`) | removed: always on | |
| Delete system logs not uploaded after (days) (`systemLogKeepDays`) | Delete server data not uploaded after (days) (`serverDataKeepDays`) | 7 |
| Sample server load during recordings (`captureGauges`) | Allow capturing server load (`allowCaptureGauges`) | No (was Yes) |
| Copy Glowroot traces of slow and failed requests (`captureTraces`) | Allow capturing Glowroot traces… (`allowCaptureTraces`) | No (was Yes) |
| Copy Glowroot data for the recording (`captureGlowrootData`) | Allow capturing Glowroot data (`allowCaptureGlowrootData`) | No |
| Max file size (MB) (`maxSizeMb`) | unchanged: value kept | |
| Max size of system logs per recording (MB) (`systemLogMaxMb`) | Max size of system logs per node per recording (MB), same key: value kept | |
| Glowroot address (`glowrootUrl`) | unchanged: value kept | |

Server load, traces, Glowroot data, app export and apps data are now asked per recording in the popup's
**Quick Settings**; the **Allow…** settings decide whether non-admins see those boxes. New settings are listed under
[Settings](#settings).

**Every uploader now sees their own recording's server data**, with no setting to turn it off. In 8.2.1 this was
off by default (no capture, and `ownServerData` No). It includes the **Info** tab: the license table (system key,
MAC addresses) and every `-D` system property, which may hold secrets. If that is a problem, limit **Who can upload**.

**Also renamed:**

- The `canViewSystemLog` flag in the API's recording JSON is now `canViewServerData`.
- The capture summary `systemLog` is now `serverCapture` (in `meta.json`, in `session.json`'s `recording`, and in the
  `recording-start` answer). Recordings and ZIPs made with 8.2.1 still open, upload and import: their `systemLog` is
  still read.

**Plugin 8.2.2 needs extension 1.2.0.** Older extensions are not supported with it, and extension 1.2.0 with plugin
8.2.1 or older uploads recordings without their server data. Upgrade the plugin and the extension together.

## Use

- **Recordings list:** `<context>/blackbox/`: your own recordings (admins: all). Tick rows to
  **Download ZIP** or **Delete selected**. **Import ZIP…** adds a ZIP as a new recording owned by you.
- **Replay page:** `<context>/blackbox/view?id=<id>`. **Features** in its header lists what it can do.
- **Uploading:** set the extension's **Upload URL** to `<context>/api/blackbox`. Uploads use your Joget login in that
  browser; when you are logged in, a stopped recording uploads and opens here by itself.
- **Downloads:** **Download ZIP** (to import elsewhere) and **Download HTML** (replays without Joget; **Open full trace**
  works in it too). Both include the server data, so share them only with people who may see it.

## Settings

**Settings > Manage Plugins > BlackBox > Configure** (system-wide). Permission settings take a Joget permission plugin
(user, group, role, department, BeanShell…); Yes/No settings are checkboxes. Admins can always do everything.

### Access

| Setting | Default | Notes |
|---|---|---|
| Who can upload | any logged-in user | Uploads from the browser only: upload, import a ZIP. Uploaders view and delete their own recordings; admins every recording |
| Max file size (MB) | 500 | Video and session.json each |
| Max recording length (minutes) | 30 | 5–120; see [Limits](#limits) |
| Storage per user (MB) | 10240 | Non-admins. 0 = no limit; see [Limits](#limits) |
| Max recordings in progress per user | 3 | Non-admins. 0 = no limit |
| Max recordings started per user per minute | 10 | Non-admins, per node. 0 = no limit |
| Max total size of a ZIP import (MB) | empty = 3 × Max file size | All files unpacked together. 0 = no limit |
| Max files in a ZIP import | empty = 5000 | 0 = no limit |
| Allow recording passwords and tokens unmasked | No | Off: every non-admin recording has credentials masked; see [Credentials in recordings](#credentials-in-recordings). Popup: **Record passwords and tokens unmasked** |
| Allow uploads over HTTP | Yes | Off: uploads, ZIP imports and server captures need HTTPS (or `X-Forwarded-Proto: https` from a proxy); a server reached as `localhost` is always allowed. Reading recordings is not affected |
| Allowed extension IDs | `anmfmkacddnmmgdcnkgkfjhagnilmkmp` | Extensions that may send recordings without a CSRF token. The BlackBox extension has this ID on every computer. Empty = any extension |
| Allow unsandboxed trace pages in HTML exports | Yes | On: **Open full trace** in a downloaded `.html` opens the Glowroot page as it is (internal review). Off: inside a sandbox, so a crafted trace page can't reach the site the file is opened from. The recordings page always sandboxes them |

#### Credentials in recordings

The extension masks credentials as `****` before a recording is saved anywhere (browser storage, files, the upload),
unless the user may record them unmasked (admins, or **Allow recording passwords and tokens unmasked**) and ticks
**Record passwords and tokens unmasked** in the popup (unticked by default). Masked:

- `Authorization`, `Proxy-Authorization`, `Cookie`, `Set-Cookie`, `X-API-Key`, `api_key` headers, and any header whose
  name has a secret word: `password`, `pwd`, `secret`, `token`, `api key`, `credential`, `csrf`, `session id`, `otp`… (so
  `OWASP_CSRFTOKEN` too).
- Values with a secret-word name in URLs (`?token=`), form posts (`j_password=`), JSON request and response bodies
  (`"access_token": …`), and `user:password@` in URLs.
- Secret-shaped values anywhere, also in console messages: `Bearer`/`Basic` tokens, JWTs, private keys, AWS, GitHub,
  Slack and Google keys.

Record ids and other values stay, so the app export still finds the records the recording used. `session.json`
says `recording.credentialsMasked`. Masking runs in the browser: the data is the user's own, so it guards against
leaking it by accident, not against a user who crafts a session.json.

### Server data

Every recording that reaches the server gets the recorded tab's log lines and a System Info snapshot. The settings
below add the other parts.

| Setting | Default | Notes | Popup checkbox |
|---|---|---|---|
| Who can capture other users' server data | admins only | Who may tick **All server log lines** and keep a ZIP's server data on import | |
| Max size of system logs per node per recording (MB) | 20 | Later lines are dropped and the viewer says so | |
| Delete server data not uploaded after (days) | 7 | | |
| Allow capturing server load | No | | **Capture server load** |
| Allow capturing Glowroot traces of slow and failed requests | No | | **Capture Glowroot trace files** |
| Allow capturing other users' traces | No | Needs the setting above. Other users' SQL with values and URLs | **Include other users' traces** |
| Allow capturing Glowroot data | No | Needs Glowroot 0.14 | **Capture Glowroot data** |
| Glowroot address | empty | Empty = this Joget's own agent (usually `http://localhost:4000`) | |
| More secret names to mask in System Info | empty | Words, `*` wildcard. Added to the built-in names, which are always masked; see [Sensitive data](#sensitive-data-in-system-info) | |

### Support data

| Setting | Default | Notes | Popup checkbox |
|---|---|---|---|
| Recent changes period (days) | 7 | Info tab: changed app versions and wflow files; at most 365 | |
| Allow exporting the apps used in the recording | No | | **Export the apps used** |
| Max apps to export | 3 | 0 = no limit (0–9999; anything else means 3); apps not allowed don't count | |
| Who can export apps they don't administer | nobody | Only apps Joget lets them access | |
| Allow apps data from apps they don't administer | No | Off: such an app is exported without its rows and process data (the Apps tab says why) | |
| Allow capturing apps data | No | Needs **Allow exporting the apps used…**; see [Apps data](#apps-data) | **Capture apps data** |
| Allow masking apps data | No | Shown when apps data is allowed | **Mask data** |
| Newest rows per table when the recording touched none | 20 | Shown when apps data is allowed. At most 500 | |
| Always-masked column names | password, ic, email, phone, address… | Shown when apps data is allowed. Words in column names; always masked | |

### Who sees what

| What | Who |
|---|---|
| Upload, import a ZIP | **Who can upload** (others get **Upload selected** disabled in the extension) |
| View and delete a recording, with its server data (system logs, traces, server load, Glowroot data, Info, exported apps) | Its uploader |
| Capture all server log lines (popup), keep a ZIP's server data on import | **Who can capture other users' server data**: these can hold other users' data. The list page says when a ZIP's server data was left out |
| Have an app exported (definition and data) | The app's own admins, or **Who can export apps they don't administer** for apps Joget lets them access (one of the app's userviews allows them). Other apps are listed as not exported. Data of an app the user doesn't administer only with **Allow apps data from apps they don't administer** |
| Downloads (ZIP, HTML) | Anyone who can view the recording |

### Per-recording choices (popup)

The popup's **Quick Settings** shows these when the settings and the user's rights allow them (the user must be
logged in to the Upload URL's server). All start unticked; choices are remembered and kept with the recording, so
automatic uploads use them too. The server checks every choice again.

| Popup checkbox | Shown when |
|---|---|
| **Export the apps used** | Admins always; others when **Allow exporting the apps used in the recording** is on and they may have some app exported |
| **Capture apps data** | **Export the apps used** is shown, and the user is an admin or **Allow capturing apps data** is on |
| **Mask data** | **Capture apps data** is shown, and the user is an admin or **Allow masking apps data** is on |
| **All server log lines** | The user is in **Who can capture other users' server data** |
| **Capture server load** | Admins always; others when **Allow capturing server load** is on |
| **Capture Glowroot trace files** | Admins always; others when **Allow capturing Glowroot traces of slow and failed requests** is on |
| **Include other users' traces** | Admins always; others when **Allow capturing other users' traces** is on too. Needs **Capture Glowroot trace files** ticked |
| **Capture Glowroot data** | Admins always; others when **Allow capturing Glowroot data** is on |
| **Record passwords and tokens unmasked** | Admins always; others when **Allow recording passwords and tokens unmasked** is on. Unticked: credentials are masked |

- A ticked box asks for that part for this recording; the settings and the user's rights decide which boxes show.
- Apps data is real unless **Mask data** is ticked; the popup warns while real data will be exported.
- **Capture apps data** needs **Export the apps used**; **Mask data** needs both.
- If the apps weren't exported at stop, the upload form offers the same three app choices.

## Server data

Shown on the replay page and in the ZIP and HTML downloads, to everyone who can view the recording.

- **System logs** tab: Joget log lines (as in `joget.log`: info and up by default) written while serving the recorded
  tab's requests, plus one entry per request, lined up with the video; a network request's details show its own lines.
  Not captured: work a request hands to another thread.
- **Other server threads** (popup's **All server log lines**): every other log line during the recording
  (scheduler, email, other users) on the nodes the recording reached. Greyed under the filter **This tab + other
  server threads**, not counted as errors.
- **Traces** tab: the [Glowroot](https://glowroot.org) trace of each recorded request Glowroot kept (slow or failed,
  by Glowroot's own settings), and of each one that logged an ERROR, even when it was fast and returned 200 (BlackBox
  asks Glowroot to keep those). Only traces Glowroot tagged with this recording are used, so another user's request
  on the same page at the same moment is never shown as yours. Failed lookups have **Retry** (not for imported
  recordings, nor for ones recorded with plugin 8.2.2 or older). Glowroot must allow anonymous access. With virtual
  hosts, traces, other users' traces, Glowroot data and server load are saved for one profile only (see ARCHITECTURE).
- **Other users' traces** (popup's **Include other users' traces**): about 30 s after the stop, each node the
  recording reached adds the traces Glowroot kept of every other request and background job during the recording
  (up to 200 per node, and what a slow Glowroot gives within 5 minutes), with the user when Glowroot records it: set
  its Servlet plugin's **Session user attribute** to `SPRING_SECURITY_CONTEXT.authentication.name` (empty by default:
  shown as "user unknown"). A note in that list says when they are still coming and how many were copied. Listed under the Traces filter **Other users' traces**, never as a recorded
  request's trace. With **Capture Glowroot data**, the downloaded data keeps all traces of that time too.
- **Download Glowroot data**: the recording's slice of Glowroot's data, ready 30–90 s after the stop, to open in a
  standalone Glowroot 0.14.2 (see its `README.txt`). About 1 MB for 35 s. Building it needs free temp space about the
  size of Glowroot's `data.h2.db`.
- **Server load** strip: CPU, heap, busy request threads, DB connections and GC time per node, every second. No
  Glowroot needed.
- **Info** tab: the server at recording start.
  - **Changes in the last *N* days** (hidden when none): edited or published versions of the apps the recording used
    and of apps sharing their tables, changed wflow files.
  - **Joget** (start time, uptime), **Plugin** (every jar in `app_plugins`; "not loaded" = failed, see the Joget log),
    host, Java, database settings, **DB pool**, license, system properties.
  - **Cluster**: its nodes and the requests each served. **Node switches** counts requests that moved between nodes;
    Joget logins don't follow unless **Session replication** is on (a Tomcat cluster, Enterprise only), so the load
    balancer needs sticky sessions.

**Sensitive data:** traces and Glowroot data hold SQL with parameter values and request details; app definitions can
hold plugin settings and environment variables. Info masks secrets in JVM arguments and system properties (below), but
keeps the license table as System Info shows it.

#### Sensitive data in System Info

JVM arguments, system properties, the datasource file and pool settings are masked as `****` when:

- **the name** contains a built-in word: `password`, `passwd`, `passphrase`, `pwd`, `secret`, `token`, `api key`,
  `access key`, `private key`, `client secret`, `auth key`, `credential`, `signing key`, `encryption key`, `salt`,
  `cert`, or starts or ends with `pass` or `key` (`smtp.pass`, `platform.systemKey`); or a word from **More secret
  names to mask in System Info**. Built-in words can't be turned off.
- **the value** looks like a secret, whatever its name: `user:password@` in a URL, `Bearer`/`Basic` tokens, JWTs,
  private keys, Joget-encrypted passwords (`%%%%…%%%%`), AWS, GitHub, Slack, Google and OpenAI-style keys, and
  `name=value` pairs with a secret name inside a longer value (`user=root;pwd=****`).

The lists come from Joget Upgrade Advisor's secret masker. The snapshot is taken at recording start, so a changed
setting applies to recordings started after it.

### Liveness

- The extension tells the server every 30 s that the recording is running (`recording-ping`); this keeps the Joget
  session alive.
- If the server has ended the capture, the recording stops (reason `server-stopped: …`). If the user is logged out,
  the recorder shows **Server capture ended: logged out** and records on without server data.
- No ping for 3 minutes (browser crashed, killed or offline): the server ends the capture at the last ping, with stop
  reason `browser-lost`. A capture that never got a ping is ended only by the hard limit.
- A recording recovered after a crash ends its capture at its last save (every 5 s), if that fits the capture (after
  its start, at most 3 minutes after the last ping); otherwise at the last ping. The save time comes from the
  browser's clock, so a browser clock that is off moves the end by the same amount.

### Limits

- **Max recording length** (30 minutes by default): the recorder shows the time left, warns 2 minutes before, and at
  the limit stops and saves as if the user pressed stop (reason `time-limit`; apps are exported as usual). When no
  server answers the start (no Upload URL, logged out, server down) the extension uses 30 minutes. If the browser
  doesn't stop, the server ends the capture 2 minutes after the limit, at the limit, with reason `time-limit`.
- **Size:** video is recorded at 1.5 Mbit/s (about 340 MB for 30 minutes). At 90% of **Max file size** the recorder
  stops and saves (reason `size-limit`), so the upload doesn't fail. No size guard when no server answered the start.
- **Storage per user** (10 GB): an upload, video or ZIP import that would take a non-admin's recordings past it
  (every file in their recording folders, server data and exported apps included) gets HTTP 413 and "Delete some
  recordings first".
- **Recordings in progress** (3) and **started per minute** (10): a non-admin's `recording-start` past either gets
  HTTP 429; the recording still works, without server data. A closed browser's captures stop on their own.
- **ZIP import:** besides **Max file size** per file, all files together may unpack to at most **Max total size of a
  ZIP import** (3 × Max file size), and the ZIP may hold at most **Max files in a ZIP import** (5000). A ZIP past
  either is refused and nothing is kept.

## App export

When the recording stops, BlackBox exports the Joget apps the tab used as `.jwa` files (definition only: no form data,
plugins or groups), **as they were at that moment**. Apps with problems come first, then the most used, up to **Max
apps to export**. The version is the one the URL names, else the published one, else the latest. App Center is only
exported when it had problems or was the only app. If the export runs long, the recording uploads without waiting and
the apps are added when it ends (the replay page shows **Exporting apps…**). If there was no export at stop (no
capture, or it failed), the export runs at upload.

- **App** tab: each app's requests, time in use, **Download .jwa**, apps data and processes. Apps not exported are
  one grey line.
- **Recordings list:** app names under the title, ⚠ for apps with problems.
- **Apps lane** (under the Issues timeline): which app the tab showed when; click to filter. The Network and System
  logs tabs have the same **App** filter.
- The ZIP includes the `.jwa` files; the HTML download lists the apps only.

## Apps data

With **Capture apps data**, each `.jwa` gets rows the way Joget's own export adds them, so **importing the `.jwa`
imports the rows too** (unless "do not import form data" is ticked). Rows per app table:

- **Opened or saved** in the recording (record, process and activity ids in URLs and form posts);
- **Linked** to those (grid and subform rows, rows they point at);
- otherwise the **newest** rows (setting).

**Real** (default): real values and original record ids, so importing overwrites rows with the same ids. Columns
matching **Always-masked column names** are masked. Uploaded files are copied up to 5 MB each and 50 MB per app; larger
ones become placeholders.

**Masked** (**Mask data** ticked): letters and digits are replaced with the same kind, so length and format stay; the
same value gets the same fake throughout. Record ids (primary keys) get new ones, also where other rows point at them,
so importing never overwrites rows; the old → new map is in the ZIP's `apps.json`. User names (created by / modified
by) become `user1`, `user2`… wherever they appear; uploaded files become placeholders. Kept: option values, values in
the app definition, yes/no/true/false, numbers up to 5 digits, UUIDs, process ids. Dates keep their year.

**Process data** (read only; Joget can't import running processes): the processes the recording touched, with state,
requester, dates, variables and each activity's state and assignees; masked with the rest when **Mask data** is
ticked. At most 20 processes per app and 100 activities each; archived processes are not found.

## Storage

One folder per recording under `<wflow>/blackbox/<id>/`, no database tables. Server data waiting for its recording's
upload is in `<wflow>/blackbox/pending/` (deleted after **Delete server data not uploaded after**), trace retries in
`<wflow>/blackbox/retry/`. A shared `wflow` folder works for clusters. Back it up like other `wflow` data; deleting a
recording removes its folder.

For tests only: `<wflow>/blackbox/liveness-test.json` (`pingMs`, `lostMs`, `checkMs`, `minLimitMs`, each at least
1000 ms) shortens the liveness timings without a restart; the log warns while it is in use. Delete it afterwards. With
virtual hosts each profile has its own (`<wflow>/app_profiles/<profile>/blackbox/liveness-test.json`). See
ARCHITECTURE.md, Storage.
