# Apps

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
