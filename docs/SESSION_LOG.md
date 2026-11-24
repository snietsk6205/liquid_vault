# Session log

One entry per session, newest at the bottom: what was asked, what was done, known limits, questions, and the exact
next step.

## Session 1 — 2026-11-24 · Phase 0 (proof)

**Asked** (the full brief is `docs/BRIEF.md`): "Write docs/BRIEF.md and docs/PLAN.md, set up the project and CI, then do
Phase 0 only and stop at its gate." Load /liquid-android and /github-fast-ci and follow them; copy the kit and the CI
from liquid_launcher without modifying it; signing "with the liquid_launcher keystore (same secret names; tell me if any
are missing)".

**Read first**: Insular's source and docs (manual setup, profile-owner receiver, Shuttle, installer-session clone,
owner app-ops, File Shuttle = the picker's Work tab), the Shizuku client API 13.1.5 sources, Shevery's server and
Shevery-API constants (how its server finds clients and where it sends its binder), AOSP 15 sources (the profile
owner's app-op carve-out, `MODIFY_QUIET_MODE`'s protection level, public cross-profile APIs, default work-profile
intent filters since Android 13).

| # | Point | State | What |
|---|---|---|---|
| 1 | Skills loaded, house style | Done | Modules, kit, CHANGELOG/DECISIONS/session log, CI from the skills. |
| 2 | `docs/BRIEF.md`, `docs/PLAN.md` | Done | Brief verbatim; plan with the architecture, every Phase 0 check and the later phases. |
| 3 | Project setup | Done | `app`, `core/common` (21 JVM unit tests), `core/designsystem` (the kit from launcher 0.16.0). Builds locally (release + R8) and the tests pass. |
| 4 | CI | Done; emulator test runs | `build.yml` from the skill + a two-part emulator test on Android 14 (`ci/smoke.sh`: `vault` = Android's setup, channel, policies, bridges, pause, reboot, destroy; `shell` = official Shizuku + the app's shell path). Med approved the first held runs; results below ("Emulator runs"). |
| 5 | Signing | Done; **secrets missing** | The launcher has no signing secrets and its committed key is public (both repos are public). The workflow signs from four new secrets and lists the names it finds; without them it builds `…-test-key.apk` and refuses to publish (D4, Q1). |
| 6 | Phase 0 Diagnostics | Done | 36 checks across both copies, PASS/FAIL/SKIPPED/NEEDS YOU, evidence per check, Copy report (merged across the channel), typed confirmations. |
| 7 | Shell via Shevery | Done; emulator PASS with the official Shizuku server | Two doors (Shevery's own client API and the official Shizuku one); S1 reports which one Shevery used. Shevery itself: on the phone. |
| 8 | Create the Vault | Done; emulator PASS both ways | V1 Android's setup (offline, D18; finishes when the Vault appears, D22); V2 the shell way with every command logged; refusal while any work profile exists. |
| 9 | Owner policy, clone, storage | Done; emulator PASS (F1/F2/F4/F5 in run 10) | P1; C1–C4; F1–F8 including app-ops set by the profile owner without shell. |
| 10 | The bridge | Done; emulator PASS for the channel and both bridges' self-tests | Channel (L0/L1); bridge A (Work tab) and B ("Liquid Vault" root); access log; B3 refusal. The picker itself: on the phone. |
| 11 | Pause/resume, reboot, destroy | Done; emulator PASS | Q0–Q2, R1–R4 (a real reboot with the shell off), D1 (refused without the typed phrase, then PASS). |
| 12 | `TESTING.md` § Phase 0 | Done | 30 steps with fish/adb commands and taps. |
| 13 | Release 0.1.0 | **Not done** | Blocked by the signing secrets: CI found **none** of the four in this repository (Q1). |

**Emulator runs** (GitHub Actions, Android 14 `google_apis` x86_64; what each run found, and the fix):

| Run | Found | Fixed by |
|---|---|---|
| 4 | `dpm set-profile-owner --name` → "Unknown option"; Android's setup said "Can't set up device" (no role holder) | D19 (no `--name`), D18 (`ALLOW_OFFLINE`) |
| 5 | Channel intent found no activity; CrossProfileApps said no; F8's undo never reported; the launcher's "isn't responding" dialog hid the setup screens | D20 (filter directions), D21, F8 finishes at the next start, the test taps Wait |
| 7 | V1 made the Vault but never got Android's result, so it waited forever; no app to clone | D22; the test takes an app out of the Vault first |
| 8 | **Vault part all green.** Shell part: F7 read "allow" back as "no entry"; one V3 screen never started (CI race) | F6/F7 read Android's default mode; the test restarts a check whose screen did not start |

Proven on stock Android 14, so a FAIL on the phone points at MagicOS: Android's setup (offline) and the shell way both
make the Vault; the official Shizuku client connects, asks permission and runs commands as uid 2000; the channel
connects both ways with no shell (forwarded intent) and survives a reboot; both bridges' providers serve the test
folder; the "block screenshots" policy; the profile owner's own app-ops (All files for the Vault copy, no shell);
`install-existing` by the shell (C2) and system-app enable (C4), while the owner's own `installExistingPackage` is
refused (C1, as Android documents); shell app-ops (F6); pause/resume; boot records on both sides after a real reboot
with the shell off; destroy through the channel; the typed confirmation refuses a wrong phrase; no crash.

**Known limits**
- The system picker's screens (bridge A's Work tab, bridge B's "Liquid Vault" root) cannot be driven in CI; the
  emulator test reads the same providers through self-tests. The picker flow is TESTING.md steps 19–23.
- Phase 0's allowed list is per copy and coarse (Liquid Vault + the system picker + chosen packages); Phase 2 replaces it.

**Questions for Med** (`docs/QUESTIONS.md`): Q1 the signing key (recommended: a new private key as four secrets),
Q2 keep the legacy Shizuku permission until S1 shows which door Shevery uses, Q3 prefer bridge B if it works, Q4 hide
the Vault copy's icon in Phase 1.

**Exact next step**: when Med has added the four signing secrets (Q1): publish 0.1.0 with a `[release]` commit (only
that commit carries the marker) or the workflow's "Publish a release" button, and give Med the release link. Then wait
for Med's Phase 0 report (TESTING.md § Phase 0); record each result in `DECISIONS.md` (which mechanisms work on
MagicOS) before starting Phase 1.
