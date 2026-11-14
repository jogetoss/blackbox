# BlackBox for Joget

Record a problem once, and get both sides of it. The BlackBox Chrome extension records the browser (video, sound,
console, network, navigation); this Joget plugin stores the recording, adds the server side (system logs, Glowroot
traces, server load, system info, the apps used) and replays both together.

**Demo (1:11):** [the bug nobody could reproduce](demo/hero.mp4). More clips (app export, reading a replay, slow
requests, setup, safe to share, other users' traces): [demo/README.md](demo/README.md).

## Features

- **Replay page:** video, console, network and server log lines on one timeline, with **Next issue**.
- **Server data:** the recorded tab's Joget log lines, Glowroot traces of slow and failed requests, other users'
  traces, server load and a System Info snapshot. See [Server data](guide/server-data.md).
- **The apps used:** exported as `.jwa` as they were at that moment, optionally with their data, real or masked. See
  [Apps](guide/apps.md).
- **Safe to share:** passwords, tokens and server secrets masked by default; uploaders see only their own recordings.
  See [Security](guide/security.md).
- **Share outside Joget:** **Download ZIP** imports on another Joget; **Download HTML** replays in any browser.
- **Limits:** recording length, size, storage per user and start rate. See [Liveness and limits](guide/limits.md).

## Quick start

1. Build the plugin (`mvn -o package`) and upload `target/blackbox-<version>.jar` in **Settings > Manage Plugins**, or
   copy it to `<wflow>/app_plugins/`.
2. Load the extension: `chrome://extensions` → **Developer mode** → **Load unpacked** → `chrome-extension/`
   ([details](chrome-extension/README.md#install)).
3. In the extension's **Settings**, set **Upload URL** to `<context>/api/blackbox` and log in to Joget in that browser.
4. Record. When you stop, the recording uploads and opens at `<context>/blackbox/view?id=<id>`.

Who may upload, capture server data or export apps: [Settings](guide/settings.md).

## Use

- **Recordings list:** `<context>/blackbox/`: your own recordings (admins: all). Tick rows to **Download ZIP** or
  **Delete selected**. **Import ZIP…** adds a ZIP as a new recording owned by you.
- **Replay page:** `<context>/blackbox/view?id=<id>`. **Features** in its header lists what it can do.
- **Uploading:** uploads use your Joget login in that browser; when you are logged in, a stopped recording uploads and
  opens by itself.
- **Downloads:** **Download ZIP** (to import elsewhere) and **Download HTML** (replays without Joget; **Open full
  trace** works in it too). Both include the server data, so share them only with people who may see it.

## Compatibility

Joget DX 8.2, Chrome 116 or later. Install the plugin before (or with) the extension of the
same release:

| Plugin | Extension |
|---|---|
| 8.2.3 | 1.3.0 |
| 8.2.2 | 1.2.0 |
| 8.2.1 | 1.1.0 |

Upgrading from an earlier release: [UPGRADE.md](UPGRADE.md).

## Documentation

| Page | What |
|---|---|
| [Settings](guide/settings.md) | Every plugin setting, and the popup's per-recording choices |
| [Security](guide/security.md) | Who sees what, masked credentials, sensitive data in System Info |
| [Server data](guide/server-data.md) | System logs, traces, Glowroot data, server load, the Info tab |
| [Liveness and limits](guide/limits.md) | How a recording stays alive, and its time, size and storage limits |
| [Apps](guide/apps.md) | App export and apps data |
| [Storage](guide/storage.md) | Where recordings are kept |
| [UPGRADE.md](UPGRADE.md) | Breaking changes between releases |
| [chrome-extension/README.md](chrome-extension/README.md) | The extension: install, record, recordings, replay |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Internals and the API |
| [e2e/README.md](e2e/README.md) | Browser tests |

## Build

```bash
mvn -o package    # runs the unit tests too; the jar is target/blackbox-8.2-SNAPSHOT.jar
```

Built against `wflow-core` 8.2-SNAPSHOT (javax.servlet) for Java 11 (Joget 8.2 requires Java 11 or later). The jar includes the extension's replay viewer: rebuild it
after changing `chrome-extension/viewer.js` or `viewer.css`.
