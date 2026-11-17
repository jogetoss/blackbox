# Settings

**Settings > Manage Plugins > BlackBox > Configure** (system-wide). Permission settings take a Joget permission plugin
(user, group, role, department, BeanShell…); Yes/No settings are checkboxes. Joget admins follow these settings like everyone else: no setting is skipped for them. They can still change the settings.

## Access

The settings page (**Settings > Manage Plugins > BlackBox > Configure**) shows each setting's default when it was never
saved, also for settings added in a later version. **Reset to defaults** (beside **Submit**) fills the whole form with the defaults; nothing
is saved until **Submit**.

| Setting | Default | Notes |
|---|---|---|
| Who can upload | Logged In User (any logged-in user; empty = the same) | Uploads from the browser only: upload, import a ZIP. Uploaders view and delete their own recordings |
| Who can view and delete every recording | Is Admin (admins only). Empty = nobody | Every user's recording on the recordings page: view, download, delete. Server data of other users' recordings shows only with **Who can capture and see other users' server data** |
| Max file size (MB) | 500 | Video and session.json each |
| Max recording length (minutes) | 30 | 5–120; see [Limits](limits.md#limits) |
| Storage per user (MB) | 10240 | Every user, admins too. 0 = no limit; see [Limits](limits.md#limits) |
| Max recordings in progress per user | 3 | Every user, admins too. 0 = no limit |
| Max recordings started per user per minute | 10 | Every user, admins too; per node. 0 = no limit |
| Max total size of a ZIP import (MB) | empty = 3 × Max file size | All files unpacked together. 0 = no limit |
| Max files in a ZIP import | 5000 | Empty = 5000. 0 = no limit |
| Allow recording browser secrets unmasked | No | Off: every recording has credentials masked; see [Credentials in recordings](security.md#credentials-in-recordings). Popup: **Record browser secrets unmasked** |
| Allow recording System Info secrets unmasked | No | Off: the Info tab's secrets are masked for every recording; see [Sensitive data](security.md#sensitive-data-in-system-info). Popup: **Record System Info secrets unmasked** |
| Allow recording license secrets unmasked | No | Enterprise only. Off: the License section's `platform.systemKey`, `platform.license.signature` and `platform.net.address` are masked for every recording; see [Sensitive data](security.md#sensitive-data-in-system-info). Popup: **Record license secrets unmasked** |
| Allow uploads over HTTP | Yes | Off: uploads, ZIP imports and server captures need HTTPS (or `X-Forwarded-Proto: https` from a proxy); a server reached as `localhost` is always allowed. Reading recordings is not affected |
| Allow recordings that start before login | No | For problems on the login page, single sign-on (SAML, OIDC) or anonymous pages. On: the popup offers **Prepare a recording that starts logged out** to users in **Who can upload**; see [Recordings that start logged out](security.md#recordings-that-start-logged-out). Off: a recording started while logged out has no server data |
| Before-login recordings expire after (minutes) | 15 | Shown when **Allow recordings that start before login** is on. 1–60. How long a prepared recording waits to be started. Shorter is safer: the one-time token opens a server capture for whoever holds it. A change applies to recordings prepared after it |
| Allowed Chrome extension IDs | `anmfmkacddnmmgdcnkgkfjhagnilmkmp` | Extensions that may send recordings without a CSRF token. The BlackBox extension has this ID on every computer. Empty = any extension |
| Allow unsandboxed trace pages in HTML exports | Yes | On: **Open full trace** in a downloaded `.html` opens the Glowroot page as it is (internal review). Off: inside a sandbox, so a crafted trace page can't reach the site the file is opened from. The recordings page always sandboxes them |

## Server data

Every recording that reaches the server gets the recorded tab's log lines and a System Info snapshot. The settings
below add the other parts.

| Setting | Default | Notes | Popup checkbox |
|---|---|---|---|
| Who can capture and see other users' server data | Is Admin (admins only). Empty = nobody | Who may see the server data of other users' recordings, tick **All server log lines** and keep a ZIP's server data on import | |
| Max size of system logs per node per recording (MB) | 20 | Later lines are dropped and the viewer says so | |
| Delete server data not uploaded after (days) | 7 | | |
| Allow capturing server load | Yes | | **Capture server load** |
| Allow capturing Glowroot traces of slow and failed requests | Yes | | **Capture Glowroot trace files** |
| Allow capturing other users' traces | No | Needs the setting above. Other users' SQL with values and URLs | **Include other users' traces** |
| Allow capturing Glowroot data | Yes | Needs Glowroot 0.14.2 to 0.14.6 | **Capture Glowroot data** |
| Glowroot address | empty | Empty = this Joget's own agent (usually `http://localhost:4000`) | |
| Secret names to mask in System Info | the default list in [Sensitive data](security.md#sensitive-data-in-system-info) | One per line; `pass*` = starts with, `*key` = ends with. Empty = the default list; see [Sensitive data](security.md#sensitive-data-in-system-info) | |

## Support data

| Setting | Default | Notes | Popup checkbox |
|---|---|---|---|
| Recent changes period (days) | 7 | Info tab: changed app versions and wflow files; at most 365 | |
| Allow exporting the apps used in the recording | Yes | | **Export the apps used** |
| Max apps to export | 3 | 0 = no limit (0–9999; anything else means 3); apps not allowed don't count | |
| Allow app admins to export their own apps | Yes | An app's own admins (`ROLE_ADMIN`, `ROLE_ADMIN_GROUP` in its `app.properties`; on Joget 9 also its app creator) get that app, with its data. Off: only the two settings below decide. A Joget admin is not an app's admin unless the app lists them | |
| Who can export apps they don't administer | Is Admin (admins only). Empty = nobody | Only apps Joget lets them access | |
| Allow apps data from apps they don't administer | No | Off: no apps data for anyone, app admins included; every app is exported without its rows and process data, and the two apps data settings below are hidden. On: those settings decide, also for apps the user doesn't administer | |
| Allow capturing apps data | Yes | Shown only when **Allow apps data from apps they don't administer** is ticked; unticked there, no one gets apps data, app admins included. Needs **Allow exporting the apps used…**; see [Apps data](apps.md#apps-data) | **Capture apps data** |
| Allow masking apps data | Yes | Shown when apps data is allowed | **Mask data** |
| Newest rows per table when the recording touched none | 20 | Shown when apps data is allowed. At most 500 | |
| Always-masked column names | password, ic, email, phone, address… | Shown when apps data is allowed. One word per line; a column whose name has one as a word is always masked. Empty = the default list | |

## Per-recording choices (popup)

The popup's **Quick Settings** shows these when the settings and the user's rights allow them (the user must be
logged in to the Upload URL's server). All start unticked; choices are remembered and kept with the recording, so
automatic uploads use them too. The server checks every choice again.

| Popup checkbox | Shown when |
|---|---|
| **Export the apps used** | **Allow exporting the apps used in the recording** is on and the user may have some app exported (an app admin with **Allow app admins to export their own apps** on, or in **Who can export apps they don't administer**) |
| **Capture apps data** | **Export the apps used** is shown, and **Allow capturing apps data** is on |
| **Mask data** | **Capture apps data** is shown, and **Allow masking apps data** is on |
| **All server log lines** | The user is in **Who can capture and see other users' server data** |
| **Capture server load** | **Allow capturing server load** is on |
| **Capture Glowroot trace files** | **Allow capturing Glowroot traces of slow and failed requests** is on |
| **Include other users' traces** | **Allow capturing other users' traces** is on too. Needs **Capture Glowroot trace files** ticked. With virtual hosts on, only when the server is started with `-Dblackbox.sharedGlowroot=true` |
| **Capture Glowroot data** | **Allow capturing Glowroot data** is on. With virtual hosts on, only when the server is started with `-Dblackbox.sharedGlowroot=true` |
| **Record browser secrets unmasked** | **Allow recording browser secrets unmasked** is on. Unticked: credentials are masked |
| **Record System Info secrets unmasked** | **Allow recording System Info secrets unmasked** is on. Unticked: System Info secrets are masked |
| **Record license secrets unmasked** | Enterprise only. **Allow recording license secrets unmasked** is on. Unticked: the system key, signature and MAC address are masked |

- A ticked box asks for that part for this recording; the settings and the user's rights decide which boxes show.
- Apps data is real unless **Mask data** is ticked; the popup warns while real data will be exported.
- **Capture apps data** needs **Export the apps used**; **Mask data** needs both.
- If the apps weren't exported at stop, the upload form offers the same three app choices.
