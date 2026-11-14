# Storage

One folder per recording under `<wflow>/blackbox/<id>/`, no database tables. Server data waiting for its recording's
upload is in `<wflow>/blackbox/pending/` (deleted after **Delete server data not uploaded after**), trace retries in
`<wflow>/blackbox/retry/`. A shared `wflow` folder works for clusters. Back it up like other `wflow` data; deleting a
recording removes its folder.

For tests only: `<wflow>/blackbox/liveness-test.json` (`pingMs`, `lostMs`, `checkMs`, `minLimitMs`, each at least
1000 ms) shortens the liveness timings without a restart; the log warns while it is in use. Delete it afterwards. With
virtual hosts each profile has its own (`<wflow>/app_profiles/<profile>/blackbox/liveness-test.json`). See
[ARCHITECTURE.md](../ARCHITECTURE.md), Storage.
