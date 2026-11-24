#!/usr/bin/env bash
# Emulator test (Android 14, google_apis), in two parts (see .github/workflows/build.yml):
#   vault  Android's own work-profile setup (V1, tapped through), then everything that needs no shell: Vault setup,
#          channel, owner policy, bridges (self-tests), clone by the owner, All files by owner app-op, pause/resume,
#          a reboot with the boot records, destroy.
#   shell  the official Shizuku server started over adb, then the app's shell path: S1–S3, creating the Vault with the
#          shell (V2), cloning with the shell (C2), app-ops with the shell (F6/F7), destroy.
# Checks run through the app's AutomationActivity (only the shell may start it) and are read back from the app's own
# log ("AUTOMATION u<user> <id> <STATUS> — <summary>"). Waits for conditions, never fixed sleeps for them.
set -u
PART=${1:-vault}
PKG=dev.liquid.vault
OUT=out/smoke/$PART
LOG="$OUT/app.log"
mkdir -p "$OUT"
. ci/smoke-lib.sh

APK=$(ls out/*.apk | head -1)
VU=""

# run_check <user> <id> <expected status regex> <seconds> [am extras…]
run_check() {
  local user=$1 id=$2 want=$3 secs=$4
  shift 4
  local from line="" status start=$SECONDS
  from=$(wc -l < "$LOG")
  adb shell am start --user "$user" -n "$PKG/.automation.AutomationActivity" --es run "$id" "$@" > /dev/null 2>&1
  while [ $((SECONDS - start)) -lt "$secs" ]; do
    line=$(tail -n +$((from + 1)) "$LOG" | grep -m1 "AUTOMATION u$user $id ") && break
    sleep 0.5
  done
  if [ -z "$line" ]; then
    note_wait "$start" 1 "check $id (user $user)"
    fail "$id (user $user): no result within $secs s"
    look "no-result-$id-u$user" 25
    return 1
  fi
  note_wait "$start" 0 "check $id (user $user)"
  status=$(printf '%s' "$line" | sed -E "s/.*AUTOMATION u$user $id ([A-Z_]+) .*/\1/")
  echo "     ${line#*AUTOMATION }" | cut -c1-260
  tail -n +$((from + 1)) "$LOG" | grep "AUTOMATION   " | head -14 | sed -E 's/.*AUTOMATION   /         /' | cut -c1-220
  if printf '%s' "$status" | grep -qE "^($want)$"; then pass "$id (user $user): $status"; else fail "$id (user $user): $status, expected $want"; fi
}

vault_user() {
  adb shell pm list users 2>/dev/null | tr -d '\r' | python3 -c '
import re,sys
for m in re.finditer(r"UserInfo\{(\d+):[^:}]*:([0-9a-fA-F]+)\}", sys.stdin.read()):
    if int(m.group(2), 16) & 0x20: print(m.group(1)); break'
}

# A system app the phone's main user has and the Vault has not, with a launcher icon (something to clone).
missing_in_vault() {
  local p
  comm -23 <(adb shell pm list packages --user 0 | tr -d '\r' | sort) <(adb shell pm list packages --user "$VU" | tr -d '\r' | sort) \
    | sed 's/^package://' | while read -r p; do
      if adb shell cmd package resolve-activity --brief --user 0 -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "$p" 2>/dev/null | tr -d '\r' | grep -q "^$p/"; then
        echo "$p"
        break
      fi
    done
}

# A slow emulator can show "<app> isn't responding": tap Wait (the skill's advice: detect it, dismiss it, note it).
dismiss_anr() {
  if on_screen "$1" "isn't responding"; then
    note "a \"not responding\" dialog was on screen ($(python3 ci/describe_ui.py "$OUT/$1.xml" 1 | head -1 | sed -E 's/ +/ /g' | cut -c1-90)); tapped Wait"
    tap_text "$1" "Wait" exact || true
    sleep 1
    dump "$1"
  fi
}

start_app() {
  adb shell am start -W -n "$PKG/.ui.MainActivity" > /dev/null 2>&1
}

section "0. A calm phone, the app installed"
bash ci/settle.sh 120
adb install -r "$APK" | tail -1
start_app_log LiquidVault
start_app
wait_log "started on the personal side" 20 && pass "the app starts on the personal side" || { fail "the app starts on the personal side"; look 00-start; }

if [ "$PART" = "vault" ]; then
  section "1. Before the Vault"
  run_check 0 A1 INFO 20
  run_check 0 V0 PASS 20
  run_check 0 S1 FAIL 20
  run_check 0 S2 SKIPPED 20
  run_check 0 Q0 FAIL 20
  adb shell pm grant "$PKG" android.permission.MODIFY_QUIET_MODE
  run_check 0 Q0 PASS 20

  section "2. Create the Vault with Android's setup (V1)"
  from=$(wc -l < "$LOG")
  adb shell am start --user 0 -n "$PKG/.automation.AutomationActivity" --es run V1 > /dev/null 2>&1
  end=$((SECONDS + 240)); i=0; started=$SECONDS
  while [ "$SECONDS" -lt "$end" ]; do
    if tail -n +$((from + 1)) "$LOG" | grep -q "AUTOMATION u0 V1 "; then break; fi
    i=$((i + 1))
    dump "v1-$i"
    dismiss_anr "v1-$i"
    tapped=""
    for t in "Accept & continue" "Accept and continue" "Agree and continue" "Continue" "Next" "Agree" "I agree" "Accept" "Got it" "OK" "Done"; do
      if on_screen "v1-$i" "$t" exact; then tap_text "v1-$i" "$t" exact && tapped=$t; break; fi
    done
    [ -n "$tapped" ] && echo "     setup screen $i: tapped \"$tapped\""
    if [ $((i % 10)) -eq 1 ] && [ -z "$tapped" ]; then python3 ci/describe_ui.py "$OUT/v1-$i.xml" 12; fi
    sleep 1.5
  done
  note_wait "$started" 0 "Android's setup"
  echo "     Android's setup said (ManagedProvisioning log):"
  adb logcat -d -s ManagedProvisioning:V 2>/dev/null | tail -25 | sed 's/^/       /' | cut -c1-240
  if tail -n +$((from + 1)) "$LOG" | grep "AUTOMATION u0 V1 " | grep -q " PASS "; then
    pass "V1: Android's setup made the Vault"
  else
    fail "V1: Android's setup made the Vault"
    look v1-end 30
    note "making the Vault by hand with adb so the other checks can run"
    [ -z "$(vault_user)" ] && adb shell pm create-user --profileOf 0 --managed Vault
    VU=$(vault_user)
    if [ -n "$VU" ]; then
      adb shell pm install-existing --user "$VU" "$PKG"
      adb shell dpm set-profile-owner --user "$VU" "$PKG/.admin.VaultAdminReceiver"
      adb shell am start-user -w "$VU"
      adb shell am start --user "$VU" -a dev.liquid.vault.action.FINISH_SETUP -n "$PKG/.ui.MainActivity"
    fi
  fi
  VU=$(vault_user)
  if [ -z "$VU" ]; then fail "a work profile exists after setup"; finish_smoke "$PART"; fi
  note "the Vault is user $VU"
  wait_log "Vault setup ran" 60 && pass "the Vault copy ran its setup" || fail "the Vault copy ran its setup"
  wait_log "kept the permission for content://0@" 60 && pass "the Vault copy holds the personal copy's channel permission" || note "the channel did not connect by itself"

  section "3. The Vault is ours; the channel"
  run_check "$VU" V3 PASS 30
  run_check 0 V3 PASS 30
  run_check 0 V0 SKIPPED 20
  run_check "$VU" L0 "PASS|FAIL" 40
  if ! tail -n 30 "$LOG" | grep -q "AUTOMATION u$VU L0 PASS"; then
    run_check "$VU" L0 PASS 60 --es connect forwarded
  fi
  run_check 0 L0 PASS 40
  run_check 0 L1 PASS 30
  run_check "$VU" L1 PASS 30

  section "4. Profile owner: policy, All files, clone"
  run_check "$VU" P1 PASS 30
  run_check "$VU" F8 PASS 30
  # Android kills the app when its All files access is removed: the result is finished at the next start, so read it.
  adb shell am start --user "$VU" -n "$PKG/.automation.AutomationActivity" --es run F8 --es undo true > /dev/null 2>&1
  wait_log "F8 finished after Android restarted this copy\|AUTOMATION u$VU F8 " 30 || true
  run_check "$VU" F8 PASS 30 --es peek true
  CLONE=$(missing_in_vault)
  note "app to clone: ${CLONE:-none found}"
  if [ -n "$CLONE" ]; then
    run_check "$VU" C1 "FAIL|PASS" 30 --es clone "$CLONE"
    run_check "$VU" C4 "PASS|SKIPPED" 40 --es clone "$CLONE"
    run_check "$VU" F1 "NEEDS_YOU|SKIPPED" 30 --es vaultApp "$CLONE"
    run_check "$VU" F4 "NEEDS_YOU|SKIPPED" 30 --es vaultApp "$CLONE"
    run_check "$VU" F3 "FAIL|PASS|SKIPPED" 30 --es vaultApp "$CLONE"
  fi

  section "5. The bridges (self-tests, no picker)"
  run_check "$VU" B0 PASS 30
  run_check "$VU" B1 PASS 30 --es selftest true
  run_check 0 B2 PASS 40 --es selftest true
  run_check "$VU" B1 PASS 30
  run_check "$VU" B1 PASS 30 --es undo true

  section "6. Pause and resume"
  run_check 0 Q1 PASS 40
  run_check 0 Q2 PASS 60

  section "7. Reboot"
  adb reboot
  adb wait-for-device
  started=$SECONDS
  until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
    [ $((SECONDS - started)) -gt 300 ] && break
    sleep 2
  done
  note_wait "$started" 0 "reboot"
  bash ci/settle.sh 90
  kill "$LOGCAT_PID" 2>/dev/null
  adb logcat -v time -s "LiquidVault:I" >> "$LOG" 2>/dev/null &
  LOGCAT_PID=$!
  adb logcat -d -v time -s "LiquidVault:I" | grep "recorded the boot" | tail -4
  run_check 0 R1 PASS 40
  run_check "$VU" R1 PASS 60
  run_check "$VU" R2 PASS 30
  run_check 0 R3 PASS 40
  run_check 0 R4 PASS 20

  section "8. Destroy the Vault"
  run_check 0 D1 FAIL 20 --es confirm WRONG
  run_check 0 D1 PASS 90 --es confirm DESTROY_VAULT
  if [ -z "$(vault_user)" ]; then pass "no work profile left"; else fail "no work profile left"; fi
fi

if [ "$PART" = "shell" ]; then
  section "1. The official Shizuku server over adb"
  SHIZUKU_APK=$(ls out/shizuku/*.apk | head -1)
  adb install -r "$SHIZUKU_APK" | tail -1
  adb shell monkey -p moe.shizuku.privileged.api -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1
  wait_top shizuku 15 || true
  path=$(adb shell pm path moe.shizuku.privileged.api | tr -d '\r' | sed -n 's/^package://p' | head -1)
  lib="${path%/base.apk}/lib/x86_64/libshizuku.so"
  if adb shell ls "$lib" > /dev/null 2>&1; then
    adb shell "$lib" 2>&1 | tail -3
  else
    wait_until 20 adb shell ls /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh
    adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh 2>&1 | tail -3
  fi
  wait_until 20 sh -c "adb shell ps -A | grep -q shizuku_server" && pass "the Shizuku server runs" || { fail "the Shizuku server runs"; adb shell ps -A | grep -i shizuku; }
  adb shell am force-stop "$PKG"
  start_app
  wait_log "the shell server connected" 30 && pass "Liquid Vault receives the shell server's connection" || fail "Liquid Vault receives the shell server's connection"
  grep "knocked at" "$LOG" | tail -2

  section "2. Shell checks"
  run_check 0 S1 PASS 20
  run_check 0 S2 "FAIL|PASS" 20
  if ! tail -n 20 "$LOG" | grep -q "AUTOMATION u0 S2 PASS"; then
    wait_screen s2-dialog "Allow" 20 || true
    tapped=""
    for t in "Allow all the time" "Always allow" "Allow" "ALLOW"; do
      if tap_text s2-dialog "$t" exact; then tapped=$t; break; fi
    done
    if [ -n "$tapped" ]; then note "tapped \"$tapped\" in Shizuku's dialog"; else look s2-dialog 25; fi
    wait_log "allowed this app" 15 || true
    run_check 0 S2 PASS 20
  fi
  run_check 0 S3 PASS 30
  run_check 0 A2 INFO 30

  section "3. Create the Vault with the shell (V2)"
  run_check 0 V0 PASS 30
  run_check 0 V2 PASS 300
  VU=$(vault_user)
  [ -n "$VU" ] && note "the Vault is user $VU" || { fail "a work profile exists after V2"; finish_smoke "$PART"; }
  wait_log "Vault setup ran" 60 && pass "the Vault copy ran its setup" || fail "the Vault copy ran its setup"
  run_check "$VU" V3 PASS 30
  run_check 0 V3 PASS 30
  run_check "$VU" L0 "PASS|FAIL" 40
  if ! tail -n 30 "$LOG" | grep -q "AUTOMATION u$VU L0 PASS"; then
    run_check "$VU" L0 PASS 60 --es connect forwarded
  fi
  run_check 0 L0 PASS 40
  run_check "$VU" S1 "PASS|FAIL" 20

  section "4. Clone and app-ops with the shell"
  CLONE=$(missing_in_vault)
  if [ -z "$CLONE" ]; then
    # Every launchable app is already in the Vault (on the emulator the new profile gets all of them): take one out of
    # the Vault so the shell can clone it back.
    CLONE=$(adb shell cmd package query-activities --components --user "$VU" -a android.intent.action.MAIN -c android.intent.category.LAUNCHER 2>/dev/null \
      | tr -d '\r' | sed -n 's|^[[:space:]]*\([A-Za-z0-9_.][A-Za-z0-9_.]*\)/.*|\1|p' | sort -u \
      | grep -v -E "^($PKG|com.android.settings|com.android.documentsui|com.google.android.documentsui|com.google.android.apps.nexuslauncher|com.android.launcher3)$" | head -1)
    if [ -n "$CLONE" ]; then
      note "every app is already in the Vault: taking $CLONE out of it first"
      adb shell pm uninstall --user "$VU" "$CLONE" | tr -d '\r' | tail -1
    fi
  fi
  note "app to clone: ${CLONE:-none found}"
  if [ -n "$CLONE" ]; then
    run_check 0 C2 PASS 60 --es clone "$CLONE"
    run_check 0 F6 NEEDS_YOU 90 --es vaultApp "$CLONE"
    run_check 0 F6 PASS 20 --es answer 0
    run_check 0 F7 NEEDS_YOU 90 --es vaultApp "$CLONE"
    run_check 0 F7 "NEEDS_YOU|PASS" 90 --es undo true
  fi

  section "5. Destroy the Vault"
  run_check 0 D1 PASS 90 --es confirm DESTROY_VAULT
  if [ -z "$(vault_user)" ]; then pass "no work profile left"; else fail "no work profile left"; fi
fi

finish_smoke "$PART"
