# Changelog

All notable changes to Liquid Vault, newest first. Each version's section is also its release notes on GitHub.

## 0.1.0 — 2026-11-24

### Added
- **Diagnostics** (Phase 0, the proof build). One screen in each copy of the app: the personal copy (your normal
  apps) and the Vault copy (inside the work profile, badged icon in the Work tab). Every check says PASS, FAIL,
  SKIPPED (with the reason, e.g. "needs shell"), NEEDS YOU (look at something and answer) or NOT RUN, with the
  commands it ran and what it read back. **Copy report** copies everything, including the other copy's report when
  the two copies can talk.
- Shell through Shevery with the official Shizuku API: shows which way Shevery connected (its own client API or the
  Shizuku one), asks Shevery for permission, runs commands as the shell user.
- Creating the Vault: Android's own setup first; the shell way (Insular's manual setup) as a fallback, every command
  logged. Refused while any work profile already exists.
- Profile owner checks: a harmless policy on and off; cloning an app four ways; storage, media and All files access
  for a Vault app by policy, by the owner's app-ops (no shell) and by the shell's app-ops.
- The channel between the two copies (Insular's Shuttle idea) and two bridges that open a Vault test folder from the
  personal side through the system picker, with a lasting folder grant, an allowed list and an access log.
- Pause and resume the Vault (after you grant MODIFY_QUIET_MODE with adb), a reboot test, and Destroy the Vault after
  you type DESTROY VAULT.
