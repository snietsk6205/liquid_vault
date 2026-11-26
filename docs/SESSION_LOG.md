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
| 8 | **Vault part all green.** Shell part: F7 read "allow" back as "no entry"; one V3 screen never started (CI race) | F6/F7 read Android's default mode; the test restarts a check whose screen did not start (D23) |
| 10 | **Both parts all green**; Android's setup now ends in the Vault copy, which connects the channel by itself. Found: Undo wrote the mode `default` instead of Android's default (`allow`); setting All files on an app that does not ask for it changes nothing | D24 |
| 11 | **Both parts all green**, no check restarted; F7's Undo reads back 6 of 6 (Android's defaults) | — |

Proven on stock Android 14, so a FAIL on the phone points at MagicOS: Android's setup (offline) and the shell way both
make the Vault; the official Shizuku client connects, asks permission and runs commands as uid 2000; the channel
connects both ways with no shell (forwarded intent) and survives a reboot; both bridges' providers serve the test
folder; the "block screenshots" policy; the profile owner's own app-ops (All files for the Vault copy, no shell);
`install-existing` by the shell (C2) and system-app enable (C4), while the owner's own `installExistingPackage` is
refused (C1, as Android documents); shell app-ops (F6); pause/resume; boot records on both sides after a real reboot
with the shell off; destroy through the channel; the typed confirmation refuses a wrong phrase; no crash.

**Known limits**
- F1, F2, F4 and F5 said SKIPPED on the emulator: no app in its image asks for storage or media access. F4/F5 use
  the owner app-op path that F8 proved; F1/F2 are documented profile-owner calls. The phone test covers all four.
- The shell's All files change did not stick for Camera, which does not ask for All files; on the phone, a file app
  that asks for All files shows whether F4–F7 can change it.
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

## Session 2 — 2026-11-25 · Phase 0 report, release 0.1.1, then Phase 1

**Asked**: "Here is my Phase 0 phone test report … go through it with me before you start Phase 1." For each
FAIL/SKIPPED/NOT RUN: test bug, expected Android behaviour or a real MagicOS block, with evidence, recorded in
DECISIONS; fix the test bugs and the C2/F6 cause "if it's what I think" in a small 0.1.1 with TESTING steps for only
the changed checks; stop on a real MagicOS block; otherwise start Phase 1 and stop at its gate. Med's checks to verify:
C2 = `DISALLOW_DEBUGGING_FEATURES` (check with `hasUserRestriction`, see whether clearing it fixes C2 and F6), F6 maybe
the same cause, F1/F4 a flawed test, S1 in the Vault "not applicable", and how rules by calling package refuse an app
when the picker is the caller.

Notes on the report: Med's answers to F2/F5/F7 and the reasons for B3/D1 arrived as the template's placeholders, so they
are treated as unanswered (none changes a conclusion: with Chrome, F2/F5/F7 would read "yes" whatever the policy). The
second copy of the Vault report is cut off; the first copy is complete.

| # | Point | State | What |
|---|---|---|---|
| 1 | Classify every result | Done | `DECISIONS.md` D25–D36, from the report, AOSP 15 sources (UserController, PermissionPolicyService, AppOpsService, ContentProviderHelper, UserRestrictionsUtils, UserTypeFactory, PackageInstallerSession) and Shizuku's server. |
| 2 | Med's C2 theory | Confirmed, with one open part | C2's error is AOSP's shell check for a user with "no debugging features"; stock Android does not set it on a new work profile. Whether the owner can lift it on MagicOS: P2 (new). |
| 3 | Med's F6 theory | Partly | F4 and F6's READ/WRITE/All files lines are Android keeping permission-backed app-ops in step (expected, also seen for the owner, who has no shell restriction); F6's READ_MEDIA lines are unexplained until 0.1.1's re-run (test op + P2). |
| 4 | F1/F4 "test flawed" | Confirmed for F1/F2 (Chrome), corrected for the details | F1/F2 already set the media permissions an app asks for; Chrome asks for none. F4 is expected Android behaviour (D28). |
| 5 | S1 in the Vault | Corrected | Not "Shevery serves only the personal side": the same shell check keeps Shevery out of the Vault (D27). Now INFO "not needed here". |
| 6 | Bridge caller = documentsui | Explained | The picker browses; the app is checked when it opens what it picked (D31). B3 now tells each refused app's story; TESTING uses Gmail. |
| 7 | CI | Fixed | Run 13 (main) failed: after a reboot right after a resume, the Vault copy was "Blocked by work policy". The test reboots first now (D35), and tests P2 end to end (D36). |
| 8 | Release 0.1.1 | See below | |

**Exact next step (0.1.1 part)**: Med runs `TESTING.md` § Phase 0 re-run and sends the report; if P2 says the owner
cannot lift "no debugging features", that is recorded as MagicOS behaviour and Q5 closes as "the shell stays out".

### Session 2, continued — 2026-11-26 · 0.1.1 report, then Phase 1 (release 0.2.0)

**Asked** (after a usage-limit pause): "continue from where you left off"; Med sent the 0.1.1 reports and wrote: "i
don't understand f6 and f7 in personal space, also storage for vault app, the instructions are ambiguous … using the
vault apps seems hard; in the future phases do you plan on making it better?"

| # | Point | State | What |
|---|---|---|---|
| 1 | 0.1.1 release | Done | Run 14: build, both emulator parts (P2 end to end: the owner blocks the shell, adb gets the phone's C2 error, the owner lets it back in) and the release passed. |
| 2 | 0.1.1 phone results | Recorded | D37–D41: "no debugging features" gone by itself; silent clone works; F1/F2 PASS with Aves; F4/F5 really passed (verdict fixed); F6/F7 retired; clearer test steps. |
| 3 | Med's questions | Answered | F6/F7 not needed; the storage section's results explained; Phase 1 replaces Diagnostics with real screens. |
| 4 | Phase 1 | Built | Home, Apps, the Vault's lock, VPN, Settings, What's new; VaultService + VaultActionActivity; waiting-actions queue; boot reminders; Vault copy icon hidden (Q4). 41 JVM unit tests. |
| 5 | Phase 1 emulator test | Shell part passed (run 16) | Run 15: the Vault part passed; the shell part showed the shell may not `pm hide` (MANAGE_USERS). Personal Freeze now turns the app off (`pm disable-user`, D54); the frozen state is read from Android, so apps turned off in Settings show as Frozen too. Run 16: the shell part passed (the shell turns Camera off and on, Android's record says so, the Apps list shows Frozen and back); the Vault part was still running when this was written. 43 JVM unit tests. A call to the Vault copy that its screen no longer waits for now ends quietly instead of logging "could not list the Vault's apps". |
| 6 | Release 0.2.0 | Release commit | CI runs both emulator parts again and publishes only if they pass. |

**Known limits**
- Not tested on stock Android in CI: the installer-session copy and Android's uninstall question (they need taps on
  Android's dialogs), the Vault's own lock (it needs a PIN typed into Android's screen), VPN with a real VPN app.
  These are in TESTING § Phase 1.
- A personal app Android protects, or one another system app turned off, cannot be frozen or unfrozen by the shell; the
  shell's reason is shown.
- B3 (an app refused by the bridge) is still not run on the phone; it belongs to Phase 2.

**Questions for Med**: Q5 (let the shell into the Vault: moot while the phone does not set "no debugging features"),
Q6 (what "hide" means).

**Exact next step**: Med tests 0.2.0 (`TESTING.md` § Phase 1) and sends the report. Then Phase 2 (access rules).
