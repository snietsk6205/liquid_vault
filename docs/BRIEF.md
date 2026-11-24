# Liquid Vault — full project brief

*Saved verbatim from Med's first message (session 1, 2026-11-24). The plan that follows from it is in
`docs/PLAN.md`; choices made without asking are in `DECISIONS.md`.*

---

Load my skills /liquid-android and /github-fast-ci first and follow them (house
style, Compose kit, DECISIONS/CHANGELOG/session-log habits, CI and release rules).
You may read snietsk6205/liquid_launcher to copy the Compose kit and CI setup; never
modify that repo. Save this brief as docs/BRIEF.md and keep docs/PLAN.md up to date.

## Vision
An Android app (package dev.liquid.vault, GPL-3.0) that replaces Insular on my HONOR
90 Lite (MagicOS, no root). It owns my work profile, "the Vault": a separate storage
normal apps can't see, and gives me full control over BOTH sides:
- access rules: app × side × scope (none / chosen folders / all files) × read-only
  or read-write, for personal apps reaching the Vault and Vault apps reaching
  personal files;
- clone, freeze, hide and pause apps; per-side VPN; everything Insular did that
  I use;
- a full file manager (later), and integration with my launcher dev.liquid.launcher.

## Verified facts (don't re-ask)
- android.software.managed_users present; pm get-max-users = 1 (no secondary users,
  the work profile is the only option; only ONE work profile per device).
- Managed profiles work here: Insular is currently profile owner of user 11. I will
  delete that profile myself before your app creates the Vault.
- HONOR blocks the normal "add work profile" flow for some apps, so provisioning
  may need the shell path.
- I use Shevery (com.hamondev.shevery), a Shizuku fork with its own app ID.
  Compatibility with the official Shizuku API is NOT confirmed — test it.
- MagicOS silently ignores `pm grant` of a permission the app doesn't declare.
- Pausing a work profile on recent Android does not lock its storage.
- You can't reach my phone. Every device step is for me: exact commands for adb on
  my Linux PC (fish shell) or exact taps.

## Architecture decisions (mine — follow them)
1. Own app, not a fork. Read Insular (gitlab.com/secure-system/Insular, Apache-2.0)
   and Shizuku/Shevery (Apache-2.0) and copy proven parts where useful (profile
   setup incl. the manual/ADB path, profile-owner receiver, File Shuttle ideas),
   keeping their license notices. Don't copy Shizuku's name, icon, app ID or
   moe.shizuku.* permissions.
2. Profile owner is the backbone. Do everything possible through
   DevicePolicyManager, which survives reboots. Use the shell (Shizuku/Shevery)
   only where unavoidable: creating the Vault, controlling personal-side apps,
   and whatever the profile owner can't do.
3. The Vault must keep working after a reboot with the shell off. Shell-dependent
   features show "needs shell", and actions queue and apply when it's back.
   Don't build our own ADB client. Add a "Shell status" card; if I grant
   WRITE_SECURE_SETTINGS once, the app may turn Wireless debugging back on at boot
   and prompt me to start Shevery.
4. Access model: "All files" is a system permission and can't be narrowed to a
   folder — it's granted per app, per side. Folder scope and anything crossing
   sides goes through our own DocumentsProvider bridge, which enforces rules by
   calling package and logs every access.
5. Optional "Locked folder" on the personal side, inside the app's private data
   (invisible even to all-files apps), reachable only through our file manager
   and bridge.
6. Optional Vault password (separate work-profile lock); test whether MagicOS
   locks storage with it.
7. The launcher stays a separate app. Same signing key; integration through a
   small API protected by a signature-level permission.

## Phases (each ends at a gate: one release, a TESTING.md section, then STOP and
## wait for my test report before starting the next phase)
0. Proof — a Diagnostics screen with PASS/FAIL/SKIPPED per check plus a "Copy
   report" button:
   shell via Shevery (Shizuku API); create the Vault (provisioning intent first,
   shell fallback: create managed profile, install app into it, set profile
   owner, start user; log every command); profile owner applies and undoes one
   harmless policy; clone one app I pick; deny/allow storage, media and
   MANAGE_EXTERNAL_STORAGE for a Vault app via profile-owner policy AND via
   appops (which works?); THE BRIDGE: one Vault folder opened from the personal
   side by an allowlisted app through the system picker (including a persistent
   folder-tree grant) while a second app is refused — try more than one mechanism
   and record which works on MagicOS in DECISIONS; pause/resume with
   MODIFY_QUIET_MODE (declared, granted by me via adb); reboot test: what still
   works with the shell off; destroy the Vault with typed confirmation.
1. Vault core — create/destroy, app list for both sides, clone, freeze, hide,
   suspend, pause/resume, per-side VPN, Vault password, reboot-safe behaviour,
   shell-status card and action queue.
2. Access rules — rules screen (app × side × scope × rw), presets, enforcement,
   access log, rules backup/export.
3. File manager — reuse the file-system core of zhanghai/MaterialFiles (GPL-3.0);
   browse Personal / Vault / Locked folder; copy and move across sides through
   the bridge; shell mode for raw paths.
4. Design — liquid design from /liquid-android, WM-Keyboard-style settings, live
   previews.
5. Launcher integration — signature-permission API: pause/resume, bridge rules for
   the launcher (wallpapers from Vault folders), hooks so a launcher layout can
   pause or resume the Vault. Vault apps in the drawer need no API (LauncherApps).

## Working rules
- At most 1–2 releases per session, signed with the liquid_launcher keystore
  (same secret names; tell me if any are missing).
- Nothing destructive without an in-app typed confirmation. Before creating the
  Vault, detect any existing work profile and refuse.
- Ask before any big architecture choice not covered above. If MagicOS blocks
  something, stop and show me the evidence instead of building around it.
- End every session with: what you verified, what I must test on the phone, and
  the release link.

## Start now
Write docs/BRIEF.md and docs/PLAN.md, set up the project and CI, then do Phase 0
only and stop at its gate.
