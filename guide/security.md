# Security and sensitive data

What BlackBox masks, and who can see what. The settings are in [Settings](settings.md). Joget admins have no
built-in rights here: every row below applies to them too.

## Who sees what

| What | Who |
|---|---|
| Upload, import a ZIP | **Who can upload** (others get **Upload selected** disabled in the extension) |
| View and delete a recording, with its server data (system logs, traces, server load, Glowroot data, Info, exported apps) | Its uploader |
| View and delete other users' recordings | **Who can view and delete every recording** (default Is Admin; empty = nobody). Their server data only with **Who can capture and see other users' server data** |
| Capture all server log lines (popup), keep a ZIP's server data on import | **Who can capture and see other users' server data**: these can hold other users' data. The list page says when a ZIP's server data was left out |
| Have an app exported (definition and data) | The app's own admins (on Joget 9, also an app creator for the apps they created) while **Allow app admins to export their own apps** is on, or **Who can export apps they don't administer** for apps Joget lets them access (one of the app's userviews allows them). Other apps are listed as not exported. Data (rows and process data) for anyone only while **Allow apps data from apps they don't administer** is on |
| Downloads (ZIP, HTML) | Anyone who can view the recording |

## Recordings that start logged out

With **Allow recordings that start before login** on, a user in **Who can upload** can capture the server side of a
recording that starts logged out (login page, SSO redirects, anonymous forms):

1. Logged in, they click **Prepare a recording that starts logged out** in the popup. The server gives the extension a
   one-time token, valid for the minutes in **Before-login recordings expire after (minutes)** (15 by default); it stores only the token's SHA-256 hash.
2. They log out and start recording. The token opens the capture. It belongs to the user who prepared it, so the
   normal rules in [Who sees what](#who-sees-what) apply, and the server-data choices (quick settings) and their
   permissions are the ones from step 1.
3. The extension pings and stops the capture with a secret it got at the start. It works also when they log in
   as another user during the recording (e.g. a test account through SSO).
4. Uploading still needs a login as the user who prepared it (now or later from the Recordings page). The apps used
   are exported at the upload, not at the stop.

Limits on the one-time tokens and capture secrets:

- A token is accepted only from the BlackBox extension (Allowed Chrome extension IDs), never from a web page; over
  HTTPS when **Allow uploads over HTTP** is off. It is sent in a header, never in a URL.
- A token works once and only opens a capture, or cancels itself (the popup's **Cancel**). The secret only pings or stops that one capture. Neither can read,
  list, upload or export anything.
- Every user, admins too: at most 3 unused tokens each; preparing counts as a start for **Max recordings started per user per
  minute**, and starting with a token counts against **Max recordings in progress per user**.
- Server capture only records requests from the recording tab (the `X-BlackBox-Id` header), logged in or not. Other
  visitors are never captured.

## Credentials in recordings

The extension masks credentials as `****` before a recording is saved anywhere (browser storage, files, the upload),
unless the user may record them unmasked (**Allow recording browser secrets unmasked**) and ticks
**Record browser secrets unmasked** in the popup (unticked by default). Masked:

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

## Sensitive data in System Info

JVM arguments, system properties, the datasource file and pool settings are masked as `****` when:

- **the name** matches a line in **Secret names to mask in System Info** (one name per line; commas also separate). A word matches anywhere in the name; `*`
  matches anything, so `pass*` matches names that start with `pass` and `*key` names that end with `key`. The default
  list, one per line: `password, passwd, passphrase, pwd, secret, token, api*key, access*key, private*key, client*secret, auth*key,
  credential, signing*key, encryption*key, salt, cert, pass*, *pass, _pass_, key*, *key, !wflow.systemkey` (`smtp.pass`,
  `-Dsystem.apiKey`). A line starting with `!` is an exception: a name it matches is never masked, so the default
  list keeps `-Dwflow.systemkey` (Enterprise's system key generator, e.g. `Domain`) visible. An exception covers one
  whole name (`!wflow.systemkey` doesn't keep `wflow.systemkey.password` visible); `*` at an end leaves that end open
  (`!app.*`). An admin can remove words from it; empty
  means the default list. A saved list equal to the default list before `!wflow.systemkey` was added
  (development builds only) counts as the new default; an edited list is kept as it is.
- **the value** looks like a secret, whatever its name: `user:password@` in a URL, `Bearer`/`Basic` tokens, JWTs,
  private keys, Joget-encrypted passwords (`%%%%…%%%%`), AWS, GitHub, Slack, Google and OpenAI-style keys, and
  `name=value` pairs with a secret name inside a longer value (`user=root;pwd=****`).

Nothing is masked when the recording was started with **Record System Info secrets unmasked** in the popup (shown
only when **Allow recording System Info secrets unmasked** is on; unticked by default). The Info tab then shows
**System Info secrets: unmasked** (a recording made with plugin 8.2.2 or older, which masked nothing there, shows
**unmasked (older recording)**). The database password is never recorded: the database and pool settings still mask password names (`pwd`,
`passwd`…) and `user:password@` in the URL. This is separate from **Record browser secrets
unmasked**, which covers the browser's traffic.

**License** (Enterprise only): the License section is kept as System Info shows it, except the system key
(`platform.systemKey`), the license signature (`platform.license.signature`, which is the system key the license was
issued for) and the MAC address (`platform.net.address`), which are masked unless the recording was started with **Record license secrets unmasked**
(shown only when **Allow recording license secrets unmasked** is on; unticked by default). The Info tab then
shows **License secrets: unmasked**. The word list
above doesn't apply to the license, and the two unmasked options are independent.

The lists come from Joget Upgrade Advisor's secret masker. The snapshot is taken at recording start, so a changed
setting applies to recordings started after it.
