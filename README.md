# Liquid Vault

An Android app (`dev.liquid.vault`, GPL-3.0) that owns the phone's work profile — **the Vault**, a separate storage
normal apps cannot see — and gives full control over both sides: access rules between personal apps and the Vault,
clone / freeze / hide / pause apps, per-side VPN, later a file manager and integration with Liquid Launcher. Built for
one phone (HONOR 90 Lite, MagicOS, no root) to replace Insular.

**Status: Phase 0 (proof).** The app is a Diagnostics screen that tests, on the real phone, every mechanism the later
phases will rely on. See `docs/PLAN.md` for the phases and `TESTING.md` for the steps on the phone.

| Read | For |
|---|---|
| `docs/BRIEF.md` | the brief, verbatim |
| `docs/PLAN.md` | the architecture and the phases, kept up to date |
| `DECISIONS.md` | every choice made without asking, with why and the cost to reverse |
| `docs/QUESTIONS.md` | questions for Med, with the answers |
| `docs/SESSION_LOG.md` | what each session did, and the exact next step |
| `TESTING.md` | what to test on the phone, per phase |
| `CHANGELOG.md` | what changed, per version (also the release notes and the app's What's new) |

## Build

```bash
./gradlew testDebugUnitTest :app:assembleRelease
```

Release builds are signed only in CI, with the key in the repository's secrets (never committed; `DECISIONS.md` D4).
A local release build uses the debug key.

## Credits

See `NOTICE`: ideas and parts adapted from Insular / Island (Apache-2.0), the Shizuku client API (Apache-2.0) used as a
library, interoperability with Shevery (Apache-2.0), and the Liquid kit from Liquid Launcher (WM Keyboard's settings
style, MIT; Kyant0's backdrop, Apache-2.0).
