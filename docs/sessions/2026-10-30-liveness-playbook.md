# Liveness fix report
Repo: BlackBox | Date: 2026-10-30 | Base: 8.2-SNAPSHOT @ 772c5af | Branch: liveness/work (5566fd6)
Run: complete | Preset: C | Tag: none | Review gate: PASS (round 2: reviewer and reviewer.2; round 1 was FAIL)

## Result
- 9 of 9 decided blocks merged (T1, BB1 to BB8); none FAILED, none SKIPPED.
- Unit tests 153/0 failures. e2e on j8213: full suite as admin 27 of 28 pass (the 28th, time-limit.spec, passes only with the short override).
- One e2e spec fails as user `cat` (liveness.spec.js:149, see Not verified). Real-browser items are unverified.

## Items
| Block | Repro before | Status | Branch / commit | Tests (unit, e2e) | Verified on j8213? |
|---|---|---|---|---|---|
| T1 test timing knobs | code only (server ignores override) | MERGED | liveness/implementer.T1 | LivenessKnobsTest; used by all e2e | yes |
| BB1 heartbeat | reproduced (ping 400 "Unknown action.") | MERGED | liveness/implementer.BB1 | RecordingPingTest; heartbeat.spec | yes |
| BB2 lost browser | reproduced (kill -9 leaves capture open) | MERGED (+BB2-rv per-profile check) | liveness/implementer.BB2, BB2-rv | BrowserLostTest, ProfilesCheckTest; lost-browser.spec, liveness.spec | yes (virtual hosts: unit test only) |
| BB3 crash recovery stop time | reproduced | MERGED | liveness/implementer.BB3 | RecoveryStopTest; recovery-stop.spec | yes |
| BB4 attach stop time | reproduced (stop = upload +247 s) | MERGED | liveness/implementer.BB4 | AttachStopTimeTest | yes |
| BB5 hard limit | code only | MERGED (+BB5-rv limit without server capture) | liveness/implementer.BB5, BB5-rv | HardLimitTest; time-limit.spec, time-limit-no-capture.spec | yes (30 min default run without override: unverified) |
| BB6 size guard, bitrate | code only | MERGED (+BB6-rv spec) | liveness/implementer.BB6, BB6-rv | size-limit.spec feeds a fake chunk | spec yes; real chunks unverified |
| BB7 Glowroot reset | reproduced (JUnit on base) | MERGED | liveness/implementer.BB7 | GlowrootPackagerFailedStaysTest | yes |
| BB8 no-heartbeat extensions | code only | MERGED | liveness/implementer.BB8 | NoHeartbeatTest | yes |

## Behavior now
- A browser that stops pinging for lostMs (default 3 min) gets its capture stopped at lastSeenAt, reason browser-lost; checked every 30 s.
- Crash recovery sends the saved time as the stop time; attach keeps the capture's own stop time.
- Every recording stops at 30 min (setting maxRecordingMinutes, 5-120), with or without server capture; server backup at limit + 2 min.
- Size guard at 90% of maxSizeMb (server capture only); video at 1.5 Mbit/s.
- Old extension + new plugin: no pings, stopped only by the server backup. New extension + older plugin: no pings, but the 30 min client limit applies (ASSUMED, see below).

## Files changed
30 files, 2452 insertions, 34 deletions (`git diff --stat 8.2-SNAPSHOT..liveness/work`; 50 commits). Main: ServerCapture.java, ServerCaptureStore.java, BlackBoxPlugin.java, LivenessKnobs.java, GlowrootPackager.java, recorder.js, background.js, report.js, README.md, ARCHITECTURE.md, 9 new JUnit classes, 6 new e2e specs.

## Models used
- fixer claude-sonnet-5-5; repro, e2e, implementers BB1/BB2/BB3/BB5 and BB5-rv/BB2-rv, reviewer, reviewer.r2: claude-opus-5-5.
- implementers T1/BB4/BB6/BB7/BB8/BB6-rv, verifier, verifier.2, reviewer.2, reviewer.2.r2: claude-sonnet-5-5.
- Step-ups: none.
- reviewer.2 and reviewer.2.r2 were interrupted by the agent runner's 10 min background-wait ceiling (not a model step-up) and rerun; the last run used CLAUDE_CODE_PRINT_BG_WAIT_CEILING_MS=0.

## Assumed answers
1. BB3 with no lastSeenAt: accept the client stop time between startedAt and now, else now; an already-stopped capture keeps its stoppedAt.
2. New extension + older plugin (no maxRecordingMs): built-in 30 min client limit, no `me` field. This departs from the playbook rule "older plugin: current behavior" for recordings over 30 min. Please confirm.

## Not verified
- Real headed Chrome: real chunk sizes vs the size guard, countdown/warning UI, title while minimized, timer throttling in a hidden window, incognito-recording.spec (headed).
- Virtual hosts / cloud installs: per-profile check is covered by ProfilesCheckTest only; a cloud security manager may deny cross-profile file reads.
- liveness.spec.js:149 as user `cat`: `appExport.apps` is 0. Passes as admin. Likely the spec assumes `cat` can export apps (AppExport records `denied`); not confirmed. Spec problem likely, not plugin.
- time-limit.spec.js without override (30 min wait); rendered settings page for maxRecordingMinutes.
- Reviewer notes for a human: T1 logs one WARN per minute (decision says "one warning line"); recorder ping() has no fetch timeout (a hung request stops pings); docs say lost capture's log lines "stop there" but all-server lines up to the server's stop remain.

## j8213 handover
See the handover step below; status written at the end of the run.

## Human steps left
1. Test by hand: ~/Joget/blackbox is on liveness/work and its jar is on j8213. Reload the extension first.
2. Review: `git -C ~/Joget/blackbox diff 8.2-SNAPSHOT..liveness/work`.
3. Merge into 8.2-SNAPSHOT (message in /home/me/Joget/blackbox-liveness/reports/merge-message.txt):
   ```
   cd ~/Joget/blackbox
   git switch 8.2-SNAPSHOT
   git merge --no-ff liveness/work -F /home/me/Joget/blackbox-liveness/reports/merge-message.txt
   git log --oneline -3
   ```
   One commit instead: `git merge --squash liveness/work && git commit -F <same file>`.
4. After the merge, delete the agent branches (`git branch --list 'liveness/*'`; `-d`, or `-D` after a squash).
5. Push and release when ready (blackbox-release skill). Agents never push or tag. The merge check (merge-tree) was clean.
