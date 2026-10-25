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
| Copy Glowroot data for the recording | No | Needs Glowroot 0.14 |
| Glowroot address | empty | Empty = this Joget's own Glowroot agent (usually `http://localhost:4000`) |
| Recent changes period (days) | 7 | The Info tab's first section |
| Export the apps used in the recording | Yes | At upload; shown like server data |
| Add sample form data to the exported apps | Yes (masked) | Only for uploaders who may see the recording's server data |
| Newest rows per table when the recording touched none | 20 | |
| Record ids in sample data | New unique ids | Importing never overwrites existing rows |
| Allow real values in sample data | No | Then "Who can view system logs" users may tick **Use real values** at upload |
| Always-masked column names | password, ic, email, phone, address… | Words in column names; masked even with real values |

## Server data

All of it needs **Capture system logs**, is shown only on this plugin's replay page, and only to users allowed by
**Who can view system logs** (or to the uploader, with **Uploaders can view the server data…**).

- **System logs** (**System logs** tab): the Joget log lines (info and up) written while serving the recorded tab's
  requests, plus one entry per request, lined up with the video. A network request's details show its own lines. Not
  captured: work a request hands to another thread.
- **Glowroot traces** (**Traces** tab): the [Glowroot](https://glowroot.org) trace of each recorded request that
  Glowroot kept one for (slow or failed, by Glowroot's own settings, which BlackBox never changes). Failed lookups
  have **Retry**. Glowroot must allow anonymous access.
- **Glowroot data** (**Download Glowroot data**): the recording cut out of Glowroot's own data, about a minute after
  the stop, to open in a standalone Glowroot 0.14.2 (steps in its `README.txt`). About 1 MB for a 35 s recording.
  Building it needs free space in Joget's temp folder about the size of Glowroot's `data.h2.db`; check that on busy
  servers before turning it on.
- **Server load** (strip under the timeline): CPU, heap, busy request threads, DB connections and GC time, sampled
  every second on each node. No Glowroot needed.
- **System Info** (**Info** tab): the server at recording start: host, Java, Joget, plugins, database settings,
  connection pool, license and system properties. It starts with **Changed in the last 7 days** (setting): app
  versions edited or published, plugin jars and wflow files (such as `app_datasource-*.properties`) changed, and a Joget
  restart. **Cluster** shows Joget's node name and node lists, Tomcat's
  `jvmRoute` and session replication, and which node served each recorded request: **Sticky sessions** warns when the
  tab's requests moved between nodes. **Plugins** lists every jar in `app_plugins` with its date, size and
  the plugins it loaded; "No plugin loaded" means the jar failed to load (the Joget log says why).

**Sensitive data:** traces and Glowroot data hold SQL with its parameter values and request details; System Info holds
JVM arguments and system properties, which can include secrets passed as `-D` options. Share recordings accordingly.

## App export

At upload, BlackBox finds the Joget apps in the recorded URLs (userview, embed, form/datalist, App Composer and JSON API
paths, `appId`/`appVersion` parameters, process ids like `app#2#process`) and exports up to 5, most used first, with
Joget's own app export (`.jwa`: the definition only, no form data, plugins or groups). App Center is left out. The
version is the one a URL names, else the published one, else the latest.

The **App** tab lists them with **Download .jwa**; the recordings list shows the app names under the title. Only users
allowed by **Who can view system logs** (or the uploader, with **Uploaders can view the server data…**) see them:
app definitions can hold plugin settings and environment variables. The ZIP download includes the `.jwa` files; the HTML
download shows the list only.

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

**Real values:** only when the admin allows them and the uploader is in **Who can view system logs**, ticked per upload.
Sensitive columns stay masked; files over 5 MB (50 MB per app) become placeholders.

**Record ids** get new unique ids everywhere they appear, so importing never overwrites rows of the same id. The App tab
lists old → new ids, since the URLs, logs and traces show the old ones.

**Process data** (with the sample data, read only: Joget can't import running processes): the App tab lists the
app's process instances the recording touched (process and activity ids in the URLs and posts, and in the sample rows):
state, requester, dates, workflow variables, and each activity with its state, assignees and who completed it. Masked
the same way (same user pseudonyms; variable values masked unless found in the app definition). At most 20 processes
per app and 100 activities each; processes already archived out of the running tables are not found.

The extension's upload form offers **Include sample form data (masked)** and, where allowed, **Use real values**.
Automatic uploads use the server's default (masked).

## Storage

One folder per recording under `<wflow>/blackbox/<id>/`, no database tables. A shared `wflow` folder works for
clustered nodes. Back it up or clean it like any other `wflow` data; deleting a recording removes its folder.
