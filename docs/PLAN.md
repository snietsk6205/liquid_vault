# Liquid Vault — plan

*Kept up to date every session. The brief is `docs/BRIEF.md`; choices made without asking are in `DECISIONS.md`;
open questions with their defaults are in `docs/QUESTIONS.md`; what happened each session is in `docs/SESSION_LOG.md`;
what Med tests on the phone is in `TESTING.md`.*

## Where we are

| Phase | State |
|---|---|
| 0. Proof (Diagnostics) | **Phone test done** (0.1.0, Med's report 2026-11-25; every result in `DECISIONS.md` D25–D36). **0.1.1** fixes the test bugs it found and adds P2 (the shell and the Vault); Med re-runs only the changed checks (`TESTING.md` § Phase 0 re-run). Nothing found blocks Phase 1. |
| 1. Vault core | Started 2026-11-25 (session 2), after the Phase 0 report |
| 2. Access rules | Not started |
| 3. File manager | Not started |
| 4. Design | Not started (Phase 0 already uses the kit's settings look) |
| 5. Launcher integration | Not started |

## How the app is built (all phases)

**One APK, two copies.** The same app is installed twice on the phone:

| Copy | Where | Role |
|---|---|---|
| **Personal copy** | your normal apps (user 0) | the control app: creates the Vault, runs the shell checks (Shevery), pauses and resumes the Vault, later hosts the "Vault" folder root on the personal side |
| **Vault copy** | inside the work profile (user 11 or similar), badged icon in the Work tab | the **profile owner**: applies policies, clones apps, sets permissions and app-ops for Vault apps, serves Vault folders through our DocumentsProvider, destroys the Vault |

Each copy shows the same Diagnostics screen with the checks that belong to its side. When the channel between the
two copies works (see "The channel" below), the personal copy's report includes the Vault copy's results.

**Profile owner first, shell only where unavoidable** (brief decision 2). The shell (Shevery, through the Shizuku API)
is used for: creating the Vault when Android's setup is blocked, commands on personal-side apps, and anything the
profile owner cannot do. Everything that must survive a reboot uses `DevicePolicyManager` or Android's own grants.

**Modules** (house style, `DECISIONS.md` D3):

```
app                 Application, activities, receivers, providers, services; the Diagnostics screen
core/common         plain Kotlin, unit-tested on the JVM: check model and report, shell command builders and
                    output parsers (pm, dpm, appops, am), bridge rule decisions, confirmation phrases, debug log
core/designsystem   the Liquid kit copied from Liquid Launcher 0.16.0: WM-Keyboard-style settings UI, glass,
                    icons, theme
```

## Phase 0 — Proof

Goal: find out, on the HONOR 90 Lite with MagicOS, which mechanisms work, before building anything on them.
Every check shows **PASS**, **FAIL**, **SKIPPED** (with the reason, e.g. "needs shell"), **NEEDS YOU** (you must
look at something and answer) or **NOT RUN**. Every check runs inside a try/catch: an exception becomes a FAIL line
with its message, never a crash. Every shell command is logged with its output and exit code. **Copy report**
copies device facts, every check with its evidence, and the debug log.

### The checks

| # | Check | Side | How | What it proves |
|---|---|---|---|---|
| **A** | Device facts | both | build, MagicOS version, `managed_users`, profiles, our install state | context for every other line |
| **S1** | Shell server found | both | binder received from Shevery; which door it used (`.shevery` = Shevery's own client API, `.shizuku` = official Shizuku API); server version, uid (2000 = adb), SELinux context | whether Shevery talks to an app built with the official Shizuku API |
| **S2** | Shell permission | both | `Shizuku.checkSelfPermission()`, "Ask Shevery" button | Shevery's Allow dialog works for us |
| **S3** | Run a command | both | `id`, `getprop` through the server | commands run as the shell user |
| **V0** | No work profile yet | personal | profiles from `UserManager`, `isProvisioningAllowed`, with shell `pm list users` | we refuse to create a second profile |
| **V1** | Create the Vault with Android's setup | personal | `ACTION_PROVISION_MANAGED_PROFILE` with our admin | whether MagicOS lets us use the normal flow |
| **V2** | Create the Vault with the shell | personal | `pm create-user --profileOf 0 --managed Vault`, `pm install-existing --user N`, `dpm set-profile-owner --user N`, `am start-user -w N`, then open the Vault copy to finish setup; each command logged; stops at the first failure | the fallback path (Insular's manual setup) |
| **V3** | The Vault is ours | both | Vault copy: `isProfileOwnerApp`, `isManagedProfile`, setup finished; personal: Vault user visible to `CrossProfileApps` | setup completed |
| **P1** | Harmless policy on and off | Vault | `setScreenCaptureDisabled(true)`, read back, `false`, read back | the profile owner can apply and undo policies |
| **P2** | Shell access to the Vault (0.1.1) | both | Vault: reads "no debugging features" (`DISALLOW_DEBUGGING_FEATURES`), lifts it as owner or puts it back; personal: `pm list packages --user N` and `dumpsys user` through the shell | whether the shell can work in the Vault on MagicOS (C2's failure, D26) |
| **C1** | Clone: profile owner `installExistingPackage` | Vault | expected to be refused (Android allows it only for "affiliated" profiles) | recorded either way |
| **C2** | Clone: shell `pm install-existing --user N <app>` | personal | needs shell | the quick path |
| **C3** | Clone: Vault-side installer | Vault | copies the app's APK files from the personal side into a `PackageInstaller` session in the Vault (Insular's method); may ask you to confirm | the no-shell path |
| **C4** | Clone: system app | Vault | `enableSystemApp` (only for apps built into the phone) | system apps |
| **F1–F2** | Storage and media: deny / allow by profile-owner policy | Vault | `setPermissionGrantState` for the storage and media permissions the app asks for; read back; then **you** open the app and answer "does it see your files?" | whether the policy works on MagicOS |
| **F3** | "All files" by profile-owner policy | Vault | `setPermissionGrantState(MANAGE_EXTERNAL_STORAGE)` | expected to fail (it is an app-op, not a runtime permission) |
| **F4–F5** | Storage, media and All files: deny / allow by **app-ops as profile owner** (no shell) | Vault | Android lets a profile owner change app-ops for apps in its own profile (AOSP `AppOpsService`); read back with `unsafeCheckOpRawNoThrow`; you confirm in the app | if it works, Vault rules never need the shell |
| **F6–F7** | Same by **app-ops through the shell** | personal | `appops set --user N <app> <op> deny/allow`, read back with `appops get` | the shell alternative |
| **F8** | All files on our own Vault copy | Vault | app-op allow, then `Environment.isExternalStorageManager()` | automatic proof without your eyes |
| **L0** | The channel between the two copies | both | see "The channel" | needed by bridge mechanism B and by the merged report |
| **B1** | Bridge A: Work tab of the system picker | both | Vault copy adds cross-profile intent filters for the picker; our Vault-side provider shows only the test folder; app A (allowed) picks it in the Work tab and keeps a persistent tree grant; app B (not allowed) is refused by the provider; every access logged | Insular's File Shuttle way, with our rules on top |
| **B2** | Bridge B: a "Liquid Vault" root on the personal side | both | our personal-side provider shows the Vault test folder in the normal picker and fetches it through the channel; same allow/refuse test | a way that does not open the Work tab to the system's own Vault folders |
| **Q1–Q2** | Pause / resume | personal | `requestQuietModeEnabled` with `MODIFY_QUIET_MODE` (you grant it with adb), read back `isQuietModeEnabled` | the Vault can be paused without the shell |
| **R1–R5** | After a reboot with Shevery off | both | each copy records what it finds at boot; the checks compare: Vault copy started, still profile owner, policies still set, channel and bridge grants still work, pause/resume still works, shell checks SKIPPED | brief decision 3 |
| **D1** | Destroy the Vault | Vault (asked from either side) | you type `DESTROY VAULT`; the Vault copy removes the work profile (`wipeData`); fallback `pm remove-user N` with the shell, same typed phrase | the end of the test cycle |

### The channel between the two copies (L0)

Insular's "Shuttle" idea: each copy holds a **persistent URI permission** to the other copy's own small
`ContentProvider` (`content://<user>@dev.liquid.vault.channel`). Once exchanged, either copy can call the other
(`ContentResolver.call`, `openFile`) at any time, also after a reboot, with no shell. The exchange needs one
cross-profile activity start; two ways are tried and recorded:

1. `CrossProfileApps.startActivity` (Android 11+) after the Vault copy allows us as a cross-profile package
   (`setCrossProfilePackages`) and turns on the `INTERACT_ACROSS_PROFILES` app-op for itself as profile owner;
2. a cross-profile intent filter for our own action, set by the profile owner (works on any Android version).

### The bridge mechanisms (B1, B2)

- Rules are decided by the **calling package** (`ContentProvider.getCallingPackage()`); in Phase 0 the allowlist is
  "the app you chose as app A" (Liquid Vault itself by default). Every access, allowed or refused, is written to an
  access log with time, side, package and folder.
- **B1** opens the Work tab of the system picker for personal apps. Side effect to watch: the Work tab may also show
  the system's own Vault folders (Internal storage, Images…) to *any* personal app you pick with. Diagnostics asks
  you what you saw. The filters are removed again by "Undo".
- **B2** keeps the Work tab closed; personal apps see a "Liquid Vault" root next to Downloads and Drive, served by the
  personal copy, which fetches files through the channel.
- The result (which works on MagicOS, which leaks) goes into `DECISIONS.md` and decides Phase 2's design.

### Phone results (0.1.0)

Summary (details and evidence: `DECISIONS.md` D25–D36):

- **Works on MagicOS:** Android's own setup (V1), owner policies (P1), All files by the owner's app-op (F8), clone by
  installer session (C3, Android asks to confirm), the channel by forwarded intent (L0/L1), both bridges with lasting
  grants (B1/B2), pause/resume without shell (Q1/Q2), both copies start at boot, Shevery through its own client API.
- **Refused as Android documents:** C1 (owner's install-existing), F3 (All files by policy).
- **MagicOS difference:** the Vault has "no debugging features", so the shell cannot reach it (C2, and the Vault copy's
  S1). Whether the owner can lift it: P2 (0.1.1).
- **Test bugs:** F1/F2 used Chrome (cannot show a storage policy); F4–F7 changed app-ops that Android keeps in step with
  the app's permissions; R3 passed with nothing to check; B3 named no app. Fixed in 0.1.1.

### Phase 0 gate

1. Release 0.1.0 (needs the signing secrets, see `docs/QUESTIONS.md` Q1).
2. `TESTING.md` § Phase 0: exact fish/adb commands and taps, in order, ending with the two copied reports.
3. Stop; wait for Med's report. Record every result in `DECISIONS.md` (which mechanisms work on MagicOS).

## Phase 1 — Vault core (after the Phase 0 report)

From the phone results: create with Android's setup (V1), the shell way only as a fallback; clone built-in apps by
switching them on (C4's `enableSystemApp`, nothing copied, D34), other apps with the installer session (C3, Android
asks once per app), and with the shell's `install-existing` (silent) only while the Vault lets the shell in (Q5,
default: closed); storage and media rules for Vault apps by the owner's permission policy, All files by app-op (Phase 2,
D28–D30); bridge B (Q3).

Create/destroy (the winning path from V1/V2), app lists for both sides, clone (winning method), freeze
(`setApplicationHidden`), hide, suspend (`setPackagesSuspended`), pause/resume, per-side VPN (`setAlwaysOnVpnPackage`
in the Vault; personal side through the shell or the system setting), Vault password (separate work challenge, test
whether MagicOS locks storage), reboot-safe behaviour, **Shell status** card, **action queue** for shell-only actions
(applied when the shell is back), optional Wireless-debugging restart at boot with `WRITE_SECURE_SETTINGS`. The Vault
copy's icon is hidden from the Work tab (opened from the personal copy), with an option to show it (Q4).

## Phase 2 — Access rules

Rules screen (app × side × scope none / folders / all files × read-only / read-write), presets, enforcement in the
bridge (the mechanism chosen in Phase 0: bridge B, which keeps the Work tab closed, unless it fails on MagicOS; Q3),
"All files" per app per side (app-op), access log screen, rules
backup/export. Optional Locked folder (inside the personal copy's private data).

## Phase 3 — File manager

Material Files' file-system core (GPL-3.0); Personal / Vault / Locked folder; copy and move across sides through the
bridge; shell mode for raw paths.

## Phase 4 — Design

Liquid design from the kit: glass on the few floating surfaces, wallpaper colours, WM-Keyboard-style settings with
search, reset, backup and live previews.

## Phase 5 — Launcher integration

A signature-level permission API (same signing key: the launcher moves to the Vault's new private key with Android's
key rotation, Q1): pause/resume,
bridge rules for the launcher (wallpapers from Vault folders), hooks for launcher layouts. Vault apps in the
launcher's drawer need no API (`LauncherApps`).
