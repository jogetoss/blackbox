# BlackBox Joget plugin

Joget DX 8.2 plugin that stores and replays recordings made by the BlackBox Chrome extension
(`chrome-extension/`): `recording.webm` (video with the page's sound and, optionally, the microphone) + `session.json`
(console, network, navigations). It can also keep
what the server did during a recording: system logs, Glowroot traces, server load and a System Info snapshot.

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
- **Replay page:** `<context>/blackbox/view?id=<id>`. **? Features** lists everything it can do.
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
- **System Info** (**Info** tab): the server at recording start: host, Java, Joget, database settings, connection pool,
  license and system properties.

**Sensitive data:** traces and Glowroot data hold SQL with its parameter values and request details; System Info holds
JVM arguments and system properties, which can include secrets passed as `-D` options. Share recordings accordingly.

## Storage

One folder per recording under `<wflow>/blackbox/<id>/`, no database tables. A shared `wflow` folder works for
clustered nodes. Back it up or clean it like any other `wflow` data; deleting a recording removes its folder.
