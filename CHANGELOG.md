# Changelog

All notable changes to Liquid Vault, newest first. Each version's section is also its release notes on GitHub.

## 0.1.1 — 2026-11-25

*Fixes for the Phase 0 phone test (what each result meant: `DECISIONS.md` D25–D36).*

### Added
- **P2 Shell access to the Vault.** C2 failed because the Vault has Android's restriction "no debugging features", which
  makes Android refuse every shell command (adb, Shevery) aimed at the Vault. In the Vault copy, P2 shows whether it is
  on and who set it, **Let the shell in** lifts it as the Vault's owner, and **Undo** puts it back. In the personal
  copy, P2 checks with the shell whether the shell reaches the Vault and where the restriction comes from.
- "Choose a Vault app" says what each app asks for, and which storage checks it can show.

### Changed
- **F4–F7 (app-ops)** read every app-op right after the change and again one second later, try a test app-op that is
  tied to no permission, and count only the app-ops that really control something: Android keeps the app-ops of the
  permissions an app asks for in step with those permissions, so changing them alone does nothing. No more "does it see
  your files?" question for these four; **Undo** puts back what was there before the first change (now also for F4/F5).
- **F1/F2** refuse an app that cannot show the result (built for Android 13 or newer and asking for no photo or video
  permission, like Chrome, which reads your files only through the system picker), and ask about the test photo.
- **S1** in the Vault copy says "not needed here" instead of FAIL (the personal copy runs the shell's commands for the
  Vault), and whether the Vault blocks the shell; S2/S3 there say why they skip.
- **C2** says when the Vault blocks the shell, and what to do.
- **B3** explains how an app that is not allowed is caught (when it opens what it picked) and shows what each refused
  app tried.
- **R3** says SKIPPED instead of PASS while there is no folder grant to check yet; **R1** says how long after the boot
  it recorded.

## 0.1.0 — 2026-11-25

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
