# Server data

Shown on the replay page and in the ZIP and HTML downloads, to everyone who can view the recording.

- **System logs** tab: Joget log lines (as in `joget.log`: info and up by default) written while serving the recorded
  tab's requests, plus one entry per request, lined up with the video; a network request's details show its own lines.
  Not captured: work a request hands to another thread.
- **Other server threads** (popup's **All server log lines**): every other log line during the recording
  (scheduler, email, other users) on the nodes the recording reached. With virtual hosts on, only the lines of
  requests to the recording's own profile: background threads (scheduler, email, cleanup) are left out, since Joget
  shares them between profiles. Greyed under the filter **This tab + other
  server threads**, not counted as errors.
- **Traces** tab: the [Glowroot](https://glowroot.org) trace of each recorded request Glowroot kept (slow or failed,
  by Glowroot's own settings), and of each one that logged an ERROR, even when it was fast and returned 200 (BlackBox
  asks Glowroot to keep those). Only traces Glowroot tagged with this recording are used, so another user's request
  on the same page at the same moment is never shown as yours. Failed lookups have **Retry** (not for imported
  recordings, nor for ones recorded with plugin 8.2.2 or older). Glowroot must allow anonymous access.
- **Other users' traces** (popup's **Include other users' traces**): about 30 s after the stop, each node the
  recording reached adds the traces Glowroot kept of every other request and background job during the recording
  (up to 200 per node, and what a slow Glowroot gives within 5 minutes), with the user when Glowroot records it: set
  its Servlet plugin's **Session user attribute** to `SPRING_SECURITY_CONTEXT.authentication.name` (empty by default:
  shown as "user unknown"). A note in that list says when they are still coming and how many were copied. Listed under the Traces filter **Other users' traces**, never as a recorded
  request's trace. With **Capture Glowroot data**, the downloaded data keeps all traces of that time too. With
  virtual hosts both hold every profile's requests on the node, so they need the server's opt-in (see [Per-recording choices](settings.md#per-recording-choices-popup)).
- **Download Glowroot data**: the recording's slice of Glowroot's data, ready 30–90 s after the stop, to open in a
  standalone Glowroot of the server's version (see its `README.txt`). About 1 MB for 35 s. Building it needs free temp space about the
  size of Glowroot's database file (`data.h2.db`, or `data.mv.db` on Glowroot 0.14.6).
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
hold plugin settings and environment variables. Info masks secrets in JVM arguments and system properties, and the
license's system key, signature and MAC address (see [Sensitive data in System Info](security.md#sensitive-data-in-system-info)).
