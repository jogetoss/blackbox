# Upgrading BlackBox

Breaking changes between releases and what to do about them. **Skipping releases:** follow each section in order,
oldest first. Upgrade the plugin and the extension together.

| Plugin | Extension |
|---|---|
| 8.2.3 | 1.3.0 |
| 8.2.2 | 1.2.0 |
| 8.2.1 | 1.1.0 |

## 8.2.3 → next release

**Joget admins no longer skip the BlackBox settings.** Until 8.2.3 an admin could always use every option and had
no per-user limits. Now every setting applies to admins too. After the upgrade, check these, or admins lose what
they had:

| What admins had | Now |
|---|---|
| Every popup option (server load, traces, other users' traces, Glowroot data, app export, apps data, mask, the three unmasked options) | Only when its **Allow…** setting is on. All are off by default |
| Upload and import a ZIP | **Who can upload** must include them (the default, Logged In User, does) |
| View and delete every recording | New setting **Who can view and delete every recording**; default Is Admin, so nothing changes until you change it |
| Server data of every recording, **All server log lines**, a ZIP's server data on import | **Who can capture other users' server data** must include them (the default, Is Admin, does). A value saved without admins now shuts them out |
| Every app exported, with data | A Joget admin is no longer an admin of every app. They get an app only as one of its own admins (`ROLE_ADMIN`, `ROLE_ADMIN_GROUP`), or through **Who can export apps they don't administer** and **Allow apps data from apps they don't administer** |
| No limits | **Storage per user**, **Max recordings in progress per user**, **Max recordings started per user per minute** and the 3 unused before-login tokens apply. An admin already over the storage limit can't upload until they delete recordings or the limit is raised |

**New setting: Allow app admins to export their own apps** (on by default, as before). Off: an app's own admins get
no app for being its admin.

Is Admin is an Enterprise permission plugin. Where it is missing (Community), the two settings that default to it
still mean Joget admins.

## 8.2.2 → 8.2.3

**Plugin 8.2.3 needs Java 11 or later**, as Joget 8.2 does. On a Joget server still running on Java 8, the plugin no
longer loads.

**The Info tab now masks secrets.** In 8.2.2 it showed JVM arguments, system properties, the datasource file and the
license table as they are. Now secret-named and secret-shaped values are masked (see
[Sensitive data](guide/security.md#sensitive-data-in-system-info)), and so are the license's system key, signature and MAC address.
Admins can still record them unmasked with the popup's **Record System Info secrets unmasked** and **Record license
secrets unmasked**; non-admins only when **Allow recording System Info secrets unmasked** or **Allow recording license
secrets unmasked** is on.

**Recordings now mask browser credentials.** Extension 1.3.0 replaces passwords, tokens, cookies and `Authorization`
headers in the network log, URLs and console with `****` before a recording is saved (see
[Credentials in recordings](guide/security.md#credentials-in-recordings)). Admins can record them unmasked with the popup's **Record
browser secrets unmasked**; non-admins only when **Allow recording browser secrets unmasked** is on.

**Plugin 8.2.3 needs extension 1.3.0.** **Allowed Chrome extension IDs** now accepts only
`anmfmkacddnmmgdcnkgkfjhagnilmkmp` by default. Extension 1.3.0 always has that ID (its manifest `key`); extension
1.2.0 loaded unpacked has another, so its uploads are refused. **Before** putting the 1.3.0 files in the old extension's
folder, upload the recordings it keeps while the plugin is still 8.2.2 (or while **Allowed Chrome extension IDs** is
empty), or download them as ZIP: reloading the old extension over the new files turns it off for good (checked on
Chromium 140), and removing it deletes its storage. Then remove the old extension and load 1.3.0 unpacked. Or empty **Allowed Chrome extension IDs** to accept any extension.

**New limits** (all settings, 0 = no limit; see [Limits](guide/limits.md#limits)). For non-admins: **Storage per user** 10240 MB
(HTTP 413), **Max recordings in progress per user** 3 and **Max recordings started per user per minute** 10 (HTTP 429:
the recording goes on without server data). For every ZIP import: **Max files in a ZIP import** 5000 and **Max total
size of a ZIP import** 3 × **Max file size**. An upload or import that 8.2.2 accepted can now be refused. **Allow uploads over HTTP** (on by default) can be turned off to require HTTPS.

**Failed trace lookups of recordings made with 8.2.2 or older lose their Retry button.** Their entries don't carry
the recording's Glowroot tag, which a trace must now have to be matched. Traces already copied stay.

**With virtual hosts on, Capture Glowroot data is off in every profile** unless the server is started with
`-Dblackbox.sharedGlowroot=true` (the same goes for the new **Include other users' traces**). Glowroot is one agent per
JVM, so its data holds every profile's requests. Only the server's start command counts: setting the property at run
time (for example from a tenant's script) is ignored. With virtual hosts on, **All server log lines** now keeps only the lines of
the recording's own profile's requests (no background jobs); a recording started before the upgrade gets no other
lines from then on. Traces, Glowroot data and server load are now saved for each profile's own recordings, not
only the profile the plugin started in.

## 8.2.1 → 8.2.2

**The plugin settings were reorganised; renamed and removed settings lose their values.** After the upgrade they fall
back to their defaults (see [Settings](guide/settings.md)). Open **Configure** and set them again; check the access settings
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
[Settings](guide/settings.md).

**Every uploader now sees their own recording's server data**, with no setting to turn it off. In 8.2.1 this was
off by default (no capture, and `ownServerData` No). It includes the **Info** tab, with its secrets masked (see
[8.2.2 → 8.2.3](#822--823)). If that is a problem, limit **Who can upload**.

**Also renamed:**

- The `canViewSystemLog` flag in the API's recording JSON is now `canViewServerData`.
- The capture summary `systemLog` is now `serverCapture` (in `meta.json`, in `session.json`'s `recording`, and in the
  `recording-start` answer). Recordings and ZIPs made with 8.2.1 still open, upload and import: their `systemLog` is
  still read.

**Plugin 8.2.2 needs extension 1.2.0 or later** (8.2.3 needs 1.3.0, see [8.2.2 → 8.2.3](#822--823)). Older extensions
are not supported with it, and extension 1.2.0 or later with plugin 8.2.1 or older uploads recordings without their
server data. Upgrade the plugin and the extension together.
