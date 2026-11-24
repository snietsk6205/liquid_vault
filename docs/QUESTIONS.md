# Questions for Med

Work continues with the default in the last column; the answer changes the design. The recommended option is first.

| # | Question | Default if no answer |
|---|---|---|
| Q1 | **Signing key.** You asked for the launcher's keystore with "the same secret names", but the launcher has **no signing secrets**: its keystore and passwords are committed in `liquid_launcher` (launcher D19: "must be rotated if the repo ever becomes public"), and **both repos are public now**, so anyone can download that key. For the Vault (a profile owner, later a signature-permission API for the launcher) a public key means anyone could sign an app that passes the Vault's signature checks or installs as an update. Options: **(A, recommended)** a new private key, made on your PC, stored only as the four secrets in `liquid_vault`; in Phase 5 the launcher moves to the same key with Android's key rotation (it updates in place and keeps its data). **(B)** the launcher's current key, as secrets (works with today's launcher at once, but it is public). Commands for both are below. | No release until the four secrets exist; CI builds `…-test-key.apk` files that are not published. |
| Q2 | **Legacy Shizuku permission.** Besides Shevery's own client permission, the official Shizuku library asks for `moe.shizuku.manager.permission.API_V23` (requested, never defined). Keep it until check S1 shows which door Shevery uses, then drop the unused one? | Keep both for Phase 0. |
| Q3 | **Bridge A's side effect.** Opening the picker's Work tab to personal apps may also show the Vault's own folders (Internal storage, Images…) to any personal app you pick with; Diagnostics asks what you saw. If bridge B works on your phone, should Phase 2 use only B and keep the Work tab closed? | Yes: prefer B; A only if B fails on MagicOS. |
| Q4 | **The Vault copy's icon** in the Work tab of your launcher: needed in Phase 0 (its Diagnostics). Hide it in Phase 1 (opened from the personal copy instead)? | Hide it in Phase 1, with an option to show it. |

## Q1: the commands (fish shell, on your Linux PC)

You need `keytool` (comes with any JDK: `sudo apt install openjdk-21-jdk-headless` or your distribution's package)
and `gh` logged in (`gh auth login`). Keep the `.jks` file and its passwords somewhere safe (a password manager and a
backup): losing it means no more updates without reinstalling.

**Option A — a new private key (recommended):**

```fish
keytool -genkeypair -v -keystore ~/liquid-release.jks -alias liquid -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=Liquid apps"
```

It asks for a password twice (the store password; use the same one when it asks for the key password, or press Enter
if it offers to reuse it). Then:

```fish
base64 -w0 ~/liquid-release.jks | gh secret set SIGNING_KEYSTORE_BASE64 -R snietsk6205/liquid_vault
```

```fish
gh secret set SIGNING_STORE_PASSWORD -R snietsk6205/liquid_vault
```

```fish
gh secret set SIGNING_KEY_PASSWORD -R snietsk6205/liquid_vault
```

```fish
gh secret set SIGNING_KEY_ALIAS -R snietsk6205/liquid_vault --body liquid
```

(`gh secret set` without `--body` asks you to paste the value, so passwords never land in your shell history.)

**Option B — the launcher's current key** (from your clone of `liquid_launcher`):

```fish
base64 -w0 keystore/liquid-launcher.jks | gh secret set SIGNING_KEYSTORE_BASE64 -R snietsk6205/liquid_vault
```

```fish
gh secret set SIGNING_STORE_PASSWORD -R snietsk6205/liquid_vault --body liquid-launcher
```

```fish
gh secret set SIGNING_KEY_PASSWORD -R snietsk6205/liquid_vault --body liquid-launcher
```

```fish
gh secret set SIGNING_KEY_ALIAS -R snietsk6205/liquid_vault --body liquid
```

Either way, the next CI run prints "Signing: with the release key from the secrets" (and the names, never the values,
of the secrets it found). Then the release can be published: say so, or start it yourself with **Actions › Build › Run
workflow**, branch `claude/liquid-vault-android-gxlze8`, "Publish a release" ticked.
