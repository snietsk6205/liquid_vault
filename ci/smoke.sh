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
  local from line="" status start=$SECONDS try clear
  from=$(wc -l < "$LOG")
  for try in 1 2; do
    # A screen started at the same moment as another app screen can stay unstarted behind it (run 8: the Vault copy's
    # first channel connection came to the front): the second try clears the app's task first.
    clear=""; [ "$try" = 2 ] && clear="--activity-clear-task"
    adb shell am start --user "$user" $clear -n "$PKG/.automation.AutomationActivity" --es run "$id" "$@" > /dev/null 2>&1
    check_started "$user" "$id" "$from" 10 && break
    [ "$try" = 1 ] && note "check $id (user $user): its screen did not start within 10 s; starting it again"
  done
  start=$SECONDS
  while [ $((SECONDS - start)) -lt "$secs" ]; do
    line=$(tail -n +$((from + 1)) "$LOG" | grep -m1 "AUTOMATION u$user $id ") && break
    sleep 0.5
  done
  if [ -z "$line" ]; then
    LAST_STATUS=""
    note_wait "$start" 1 "check $id (user $user)"
    fail "$id (user $user): no result within $secs s"
    look "no-result-$id-u$user" 25
    return 1
  fi
  note_wait "$start" 0 "check $id (user $user)"
  status=$(printf '%s' "$line" | sed -E "s/.*AUTOMATION u$user $id ([A-Z_]+) .*/\1/")
  LAST_STATUS=$status
  echo "     ${line#*AUTOMATION }" | cut -c1-260
  tail -n +$((from + 1)) "$LOG" | grep "AUTOMATION   " | head -14 | sed -E 's/.*AUTOMATION   /         /' | cut -c1-220
  if printf '%s' "$status" | grep -qE "^($want)$"; then pass "$id (user $user): $status"; else fail "$id (user $user): $status, expected $want"; fi
}

# run_act <user> <act> <want: OK|FAIL regex> <seconds> [am extras…] : a Phase 1 action through AutomationActivity (the
# same code the screens use); it logs "AUTOMATION-ACT u<user> <act> <OK|FAIL> — <words>", kept in LAST_ACT.
run_act() {
  local user=$1 act=$2 want=$3 secs=$4
  shift 4
  local from line="" status start
  from=$(wc -l < "$LOG")
  adb shell am start --user "$user" -n "$PKG/.automation.AutomationActivity" --es act "$act" "$@" > /dev/null 2>&1
  start=$SECONDS
  while [ $((SECONDS - start)) -lt "$secs" ]; do
    line=$(tail -n +$((from + 1)) "$LOG" | grep -m1 "AUTOMATION-ACT u$user $act ") && break
    sleep 0.5
  done
  if [ -z "$line" ]; then
    LAST_ACT=""
    note_wait "$start" 1 "act $act (user $user)"
    fail "act $act $* (user $user): no answer within $secs s"
    look "no-act-$act-u$user" 25
    return 1
  fi
  note_wait "$start" 0 "act $act (user $user)"
  LAST_ACT=${line#*AUTOMATION-ACT }
  echo "     $LAST_ACT" | cut -c1-240
  status=$(printf '%s' "$line" | sed -E "s/.*AUTOMATION-ACT u$user $act (OK|FAIL) .*/\1/")
  if printf '%s' "$status" | grep -qE "^($want)$"; then pass "act $act $* (user $user): $status"; else fail "act $act $* (user $user): $status, expected $want"; fi
}

# pkg_flag <user> <pkg> <flag> : the value of hidden, suspended, installed (true/false) or enabled (0 default,
# 1 on, 3 turned off by the user) on dumpsys package's line for the user.
pkg_flag() {
  adb shell dumpsys package "$2" 2>/dev/null | tr -d '\r' | grep -m1 -E "^ +User $1: " | grep -oE " $3=[^ ]+" | head -1 | cut -d= -f2
}

# expect_flag <user> <pkg> <flag> <value> <what> : checks what Android itself says about the app.
expect_flag() {
  local v
  v=$(pkg_flag "$1" "$2" "$3")
  if [ "$v" = "$4" ]; then pass "$5 ($3=$v)"; else fail "$5 ($3=${v:-?}, expected $4)"; fi
}

# The Vault copy's launcher entries in the Vault (Q4: none by default).
vault_icon_count() {
  adb shell cmd package query-activities --components --user "$VU" -a android.intent.action.MAIN -c android.intent.category.LAUNCHER 2>/dev/null \
    | tr -d '\r' | grep -c "^[[:space:]]*$PKG/"
}

vault_icon_shown() { [ "$(vault_icon_count)" -ge 1 ]; }

# The test app (built by ci/make_test_apk.sh, standing for any app you downloaded) is installed in the Vault.
test_app_in_vault() { [ "$(pkg_flag "$VU" dev.liquid.vault.testapp installed)" = "true" ]; }

# probe <user> <op> <uri> [text] : the test app, as any app would, lists, reads or writes a URI it was lent (D60);
# LAST_PROBE gets its answer ("PROBE u0 read OK ..." or "PROBE u0 read FAIL SecurityException: ...").
probe() {
  local user=$1 op=$2 uri=$3 text=${4:-} before now i
  before=$(grep -c "PROBE u$user $op " "$LOG" 2>/dev/null || true)
  if [ -n "$text" ]; then
    adb shell "am start --user $user -W -n dev.liquid.vault.testapp/.Probe --es op $op --es uri '$uri' --es text '$text'" >/dev/null 2>&1
  else
    adb shell "am start --user $user -W -n dev.liquid.vault.testapp/.Probe --es op $op --es uri '$uri'" >/dev/null 2>&1
  fi
  for i in $(seq 1 40); do
    now=$(grep -c "PROBE u$user $op " "$LOG" 2>/dev/null || true)
    if [ "${now:-0}" -gt "${before:-0}" ]; then
      LAST_PROBE=$(grep "PROBE u$user $op " "$LOG" | tail -1 | sed -E 's/.*(PROBE .*)/\1/')
      return 0
    fi
    sleep 0.5
  done
  LAST_PROBE="(no answer from the test app)"
}

# expect_probe <what> <OK|FAIL> [text] : checks the test app's last answer.
expect_probe() {
  if printf '%s' "$LAST_PROBE" | grep -qE "^PROBE u[0-9]+ [a-z]+ $2" && { [ -z "${3:-}" ] || printf '%s' "$LAST_PROBE" | grep -qF "$3"; }; then
    pass "$1"
  else
    fail "$1 (the app got: $LAST_PROBE; expected $2${3:+ with \"$3\"})"
  fi
}

# all_files_mode <user> <pkg> : the app's All files app-op as appops prints it; "default" when it has none set.
all_files_mode() {
  local m
  m=$(adb shell appops get --user "$1" "$2" MANAGE_EXTERNAL_STORAGE 2>/dev/null | tr -d '\r' | grep -m1 "^MANAGE_EXTERNAL_STORAGE:" | sed -E 's/^MANAGE_EXTERNAL_STORAGE: ([a-z]+).*/\1/')
  echo "${m:-default}"
}

expect_all_files() {
  local m
  m=$(all_files_mode "$1" "$2")
  if [ "$m" = "$3" ]; then pass "$4 (All files: $m)"; else fail "$4 (All files: $m, expected $3)"; fi
}

# uri_of <key> : a URI from the last bridge-grant act ("children=<uri> file=<uri>").
uri_of() { printf '%s' "$LAST_ACT" | grep -oE "$1=[^ ]+" | head -1 | cut -d= -f2-; }

# check_started <user> <id> <from line> <seconds> : AutomationActivity logs "AUTOMATION-START u<user> <id>" when it runs.
check_started() {
  local i
  for i in $(seq 1 $(($4 * 2))); do
    tail -n +$(($3 + 1)) "$LOG" | grep -qE "AUTOMATION-START u$1 $2([[:space:]]|$)" && return 0
    sleep 0.5
  done
  return 1
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

# Launchable apps in the Vault, one package per line.
vault_launchables() {
  adb shell cmd package query-activities --components --user "$VU" -a android.intent.action.MAIN -c android.intent.category.LAUNCHER 2>/dev/null \
    | tr -d '\r' | sed -n 's|^[[:space:]]*\([A-Za-z0-9_.][A-Za-z0-9_.]*\)/.*|\1|p' | sort -u \
    | grep -v -E "^($PKG|com.android.settings|com.android.documentsui|com.google.android.documentsui|com.google.android.apps.nexuslauncher|com.android.launcher3)$"
}

# A launchable Vault app that asks for photo access (F1/F2 can show the policy with it), else one that asks for storage,
# else nothing.
storage_app_in_vault() {
  local p apps
  apps=$(vault_launchables)
  for want in "android.permission.READ_MEDIA_IMAGES" "android.permission.READ_EXTERNAL_STORAGE"; do
    for p in $apps; do
      if adb shell dumpsys package "$p" 2>/dev/null | grep -q "$want"; then echo "$p"; return; fi
    done
  done
}

# until_unlocked <user> <seconds> : waits until Android reports the user running and unlocked (after a boot or a resume).
until_unlocked() {
  wait_until "$2" sh -c "adb shell am get-started-user-state $1 2>/dev/null | grep -q RUNNING_UNLOCKED"
}

# The shell's view of the Vault: adb is refused there while the Vault has "no debugging features" (the phone's C2 error).
shell_reaches_vault() {
  adb shell pm list packages --user "$VU" "$PKG" 2>&1 | tr -d '\r' > "$OUT/shell-reach.txt"
  grep -q "^package:$PKG\$" "$OUT/shell-reach.txt"
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
  end=$((SECONDS + 240)); i=0; started=$SECONDS; v1_done=""; idle=0
  while [ "$SECONDS" -lt "$end" ]; do
    if [ -z "$v1_done" ] && tail -n +$((from + 1)) "$LOG" | grep -q "AUTOMATION u0 V1 "; then v1_done=$SECONDS; idle=0; fi
    # V1 answers as soon as the Vault exists; a person then finishes Android's last screens, which opens the Vault copy
    # and connects the channel. Do the same: stop when the channel is connected or no setup screen is left.
    if [ -n "$v1_done" ]; then
      if grep -q "kept the permission for content://0@" "$LOG" || [ "$idle" -ge 4 ] || [ $((SECONDS - v1_done)) -gt 60 ]; then break; fi
    fi
    i=$((i + 1))
    dump "v1-$i"
    dismiss_anr "v1-$i"
    tapped=""
    for t in "Accept & continue" "Accept and continue" "Agree and continue" "Continue" "Next" "Agree" "I agree" "Accept" "Got it" "OK" "Done"; do
      if on_screen "v1-$i" "$t" exact; then tap_text "v1-$i" "$t" exact && tapped=$t; break; fi
    done
    if [ -n "$tapped" ]; then echo "     setup screen $i: tapped \"$tapped\""; idle=0; else idle=$((idle + 1)); fi
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

  section "3b. Shell access to the Vault (P2)"
  # Stock Android puts no "no debugging features" on a new work profile (the phone's MagicOS did: C2). The owner puts
  # it on (asked by the personal copy through the channel, as adb cannot start the Vault copy once it is on), adb is
  # then refused in the Vault with the phone's exact error, and the owner lifts it again.
  run_check "$VU" P2 PASS 30
  if shell_reaches_vault; then pass "adb reaches the Vault"; else fail "adb reaches the Vault"; cat "$OUT/shell-reach.txt" | head -3; fi
  run_check 0 P2 SKIPPED 40 --es vault block
  if ! shell_reaches_vault && grep -q "Shell does not have permission to access user $VU" "$OUT/shell-reach.txt"; then
    pass "with \"no debugging features\" on, Android refuses adb in the Vault (the phone's C2 error)"
  else
    fail "with \"no debugging features\" on, Android refuses adb in the Vault (the phone's C2 error)"; head -3 "$OUT/shell-reach.txt"
  fi
  run_check 0 P2 SKIPPED 40 --es vault lift
  if shell_reaches_vault; then pass "after \"Let the shell in\", adb reaches the Vault again"; else fail "after \"Let the shell in\", adb reaches the Vault again"; head -3 "$OUT/shell-reach.txt"; fi
  run_check "$VU" P2 PASS 30

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
  fi
  FAPP=$(storage_app_in_vault)
  if [ -n "$FAPP" ]; then
    note "Vault app for the storage checks: $FAPP (it asks for storage or media access)"
    # An app built for Android 13+ that asks for no photo permission cannot show the policy: F1 says SKIPPED (0.1.1).
    run_check "$VU" F1 "NEEDS_YOU|SKIPPED" 30 --es vaultApp "$FAPP"
    if [ "$LAST_STATUS" = "NEEDS_YOU" ]; then
      run_check "$VU" F1 PASS 20 --es answer 0
      run_check "$VU" F2 NEEDS_YOU 30
      run_check "$VU" F2 "NEEDS_YOU|PASS" 30 --es undo true
    fi
    run_check "$VU" F3 "FAIL|PASS" 30
  elif [ -n "$CLONE" ]; then
    note "no Vault app asks for storage or media access: F1 says SKIPPED; F4/F5 use $CLONE"
    run_check "$VU" F1 SKIPPED 30 --es vaultApp "$CLONE"
    run_check "$VU" F3 "FAIL|PASS|SKIPPED" 30 --es vaultApp "$CLONE"
  fi
  if [ -n "$FAPP$CLONE" ]; then
    # The owner's app-ops (no shell): the test op tied to no permission, and the ops of permissions the app does not
    # ask for, stay changed on stock Android; ops of permissions it asks for may go back (not counted).
    run_check "$VU" F4 PASS 30
    run_check "$VU" F5 "PASS|INFO" 30
    run_check "$VU" F5 "PASS|INFO" 30 --es undo true
  fi

  section "4b. Phase 1: the Vault's apps, lock, VPN and icon (the owner, through the channel)"
  if [ -n "$CLONE" ]; then
    run_act 0 vault-apps OK 30 --es pkg "$CLONE"
    run_act 0 vault-app OK 30 --es do freeze --es pkg "$CLONE"
    expect_flag "$VU" "$CLONE" hidden true "freeze hides the app in the Vault"
    run_act 0 vault-apps OK 30 --es pkg "$CLONE"
    if printf '%s' "$LAST_ACT" | grep -q "frozen=true"; then pass "the Vault's list still shows it, frozen"; else fail "the Vault's list still shows it, frozen"; fi
    run_act 0 vault-app OK 30 --es do unfreeze --es pkg "$CLONE"
    expect_flag "$VU" "$CLONE" hidden false "unfreeze brings it back"
    run_act 0 vault-app OK 30 --es do suspend --es pkg "$CLONE"
    expect_flag "$VU" "$CLONE" suspended true "suspend greys it out"
    run_act 0 vault-app OK 30 --es do unsuspend --es pkg "$CLONE"
    expect_flag "$VU" "$CLONE" suspended false "unsuspend"
  fi
  run_act 0 vault-lock OK 30
  run_act 0 vault-vpn OK 30
  run_act 0 vault-state OK 30
  if printf '%s' "$LAST_ACT" | grep -q "setupVersion=3"; then pass "the Vault's setup is version 3 (Work tab closed, All files for the bridge)"; else fail "the Vault's setup is version 3"; fi
  if [ "$(vault_icon_count)" = "0" ]; then pass "the Vault copy has no icon in the Work tab by default"; else fail "the Vault copy has no icon in the Work tab by default"; fi
  run_act 0 vault-icon OK 30 --es on true
  if wait_until 10 vault_icon_shown; then
    pass "its icon can be shown"
  else
    fail "its icon can be shown"
  fi
  run_act 0 vault-icon OK 30 --es on false
  # No shell in this part: a personal freeze waits for it, and the opposite action cancels it.
  run_act 0 personal OK 30 --es do FREEZE --es pkg com.android.camera2
  if printf '%s' "$LAST_ACT" | grep -q "waits for the shell"; then pass "with no shell, a personal freeze waits"; else fail "with no shell, a personal freeze waits"; fi
  run_act 0 personal OK 30 --es do UNFREEZE --es pkg com.android.camera2
  if printf '%s' "$LAST_ACT" | grep -q "waiting: 0"; then pass "unfreeze cancels the waiting freeze"; else fail "unfreeze cancels the waiting freeze"; fi

  # Copying an app you downloaded (not built into the phone) without the shell (0.2.0 step 12, D55): the Vault copy
  # cannot see an app that is only on the personal side, so the personal copy names its APK files; Android asks first.
  TEST_APK="$OUT/testapp.apk"
  TPKG=dev.liquid.vault.testapp
  if bash ci/make_test_apk.sh "$TEST_APK"; then
    adb install --user 0 -r "$TEST_APK" | tr -d '\r' | tail -1
    expect_flag "$VU" "$TPKG" installed false "the downloaded test app is only on the personal side"
    run_act 0 copy OK 60 --es pkg "$TPKG"
    wait_screen copy-ask "Install" 40 exact
    if tap_text copy-ask "Install" exact; then pass "Android asks in the Vault to install it"; else fail "Android asks in the Vault to install it"; look copy-ask; fi
    if wait_until 60 test_app_in_vault; then pass "the downloaded app is in the Vault (installed=true)"; else fail "the downloaded app is in the Vault"; look copy-after; fi
    wait_log "Vault action: done" 20 || true
    adb uninstall "$TPKG" | tr -d '\r' | tail -1
  else
    fail "a test app for the copy without the shell (ci/make_test_apk.sh)"
  fi

  section "5. The bridges (self-tests, no picker)"
  run_check "$VU" B0 PASS 30
  run_check 0 B2 PASS 40 --es selftest true

  section "5b. Phase 2: access rules (the bridge both ways, a Vault app's own files)"
  TPKG=dev.liquid.vault.testapp
  adb install -r "$OUT/testapp.apk" | tr -d '\r' | tail -1
  if ! adb shell pm list packages --user "$VU" "$TPKG" | grep -q "$TPKG"; then adb shell pm install-existing --user "$VU" "$TPKG" | tr -d '\r' | tail -1; fi
  # The personal copy serves your personal files to Vault apps: it needs All files on the personal side (on the phone
  # you allow it in Android's settings; here adb does). The Vault copy gave it to itself in the Vault (setup 3).
  adb shell appops set --user 0 "$PKG" MANAGE_EXTERNAL_STORAGE allow
  adb shell "mkdir -p '/sdcard/Documents/LV shared' && echo 'Hello from the personal side.' > '/sdcard/Documents/LV shared/hello.txt'"

  # A personal app reaching the Vault's files (bridge B in the personal copy).
  run_act 0 bridge-grant OK 30 --es side personal --es pkg "$TPKG" --es folder "Documents/Liquid Vault test" --es file hello.txt
  B_LIST=$(uri_of children)
  B_FILE=$(uri_of file)
  probe 0 list "$B_LIST"
  expect_probe "a personal app with no rule is refused the Vault's files" FAIL "Liquid Vault refused"
  run_act 0 rule OK 30 --es pkg "$TPKG" --es place personal --es reach FOLDERS --es folders "Documents/Liquid Vault test"
  probe 0 list "$B_LIST"
  expect_probe "with a folder rule it lists that Vault folder" OK "hello.txt"
  probe 0 read "$B_FILE"
  expect_probe "and reads a Vault file" OK "Hello from the Vault"
  probe 0 write "$B_FILE" "changed by an app"
  expect_probe "but may not change it (read-only)" FAIL "may only read"
  run_act 0 rule OK 30 --es pkg "$TPKG" --es place personal --es reach FOLDERS --es folders "Documents/Liquid Vault test" --es write true
  probe 0 write "$B_FILE" "Written through the bridge."
  expect_probe "read-write: it changes the Vault file" OK "wrote"
  probe 0 read "$B_FILE"
  expect_probe "and the Vault file says so" OK "Written through the bridge."
  run_act 0 rule OK 30 --es pkg "$TPKG" --es place personal --es reach FOLDERS --es folders "Pictures"
  probe 0 read "$B_FILE"
  expect_probe "outside its chosen folders it is refused" FAIL "only its chosen folders"
  run_act 0 rule OK 30 --es pkg "$TPKG" --es place personal --es reach NONE

  # A Vault app reaching your personal files (the Vault copy's bridge, served by the personal copy).
  run_act 0 bridge-grant OK 30 --es side vault --es pkg "$TPKG" --es folder "Documents/LV shared" --es file hello.txt
  V_LIST=$(uri_of children)
  V_FILE=$(uri_of file)
  probe "$VU" list "$V_LIST"
  expect_probe "a Vault app with no rule is refused your personal files" FAIL "Liquid Vault refused"
  run_act 0 rule OK 30 --es pkg "$TPKG" --es place vault --es reach ALL
  probe "$VU" list "$V_LIST"
  expect_probe "with an all-files rule it lists the personal folder" OK "hello.txt"
  probe "$VU" read "$V_FILE"
  expect_probe "and reads your file" OK "Hello from the personal side"
  probe "$VU" write "$V_FILE" "changed by a Vault app"
  expect_probe "but may not change it (read-only)" FAIL "may only read"
  run_act 0 rule OK 30 --es pkg "$TPKG" --es place vault --es reach NONE

  run_act 0 access-log OK 30 --es pkg "$TPKG"
  if printf '%s' "$LAST_ACT" | grep -qE "$TPKG: [1-9][0-9]* allowed, [1-9][0-9]* refused"; then
    pass "both copies logged the app's allowed and refused calls"
  else
    fail "both copies logged the app's allowed and refused calls"
  fi

  # A Vault app's own files, set by the owner (D57): All files by the owner's app-op.
  OAPP=${FAPP:-$CLONE}
  if [ -n "$OAPP" ]; then
    run_act 0 rule OK 30 --es pkg "$OAPP" --es place vault --es own ALL
    expect_all_files "$VU" "$OAPP" allow "own files of a Vault app: All files"
    run_act 0 rule OK 30 --es pkg "$OAPP" --es place vault --es own NONE
    expect_all_files "$VU" "$OAPP" deny "own files of a Vault app: No files"
    run_act 0 rule OK 30 --es pkg "$OAPP" --es place vault --es own ANDROID
    expect_all_files "$VU" "$OAPP" default "own files of a Vault app: back to Android"
  else
    note "no Vault app to set own files on"
  fi
  adb uninstall "$TPKG" | tr -d '\r' | tail -1

  # The reboot comes before pause and resume (0.1.1): run 13 rebooted a second after a resume, and after that boot
  # Android showed "Blocked by work policy" for the Vault copy (its apps had stayed suspended by the platform).
  section "6. Reboot"
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
  until_unlocked "$VU" 120 || note "user $VU is not running unlocked yet: $(adb shell am get-started-user-state "$VU" 2>&1 | tr -d '\r')"
  run_check "$VU" R1 PASS 60
  run_check "$VU" R2 PASS 30
  run_check 0 R3 "PASS|SKIPPED" 40
  run_check 0 R4 PASS 20

  section "7. Pause and resume"
  run_check 0 Q1 PASS 40
  run_check 0 Q2 PASS 60
  # After a resume the Vault's apps must open again (the Vault copy answers a check started in the Vault).
  until_unlocked "$VU" 90 || note "user $VU is not running unlocked yet after the resume"
  run_check "$VU" L1 PASS 60

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
  wait_log "kept the permission for content://0@" 60 && pass "the Vault copy holds the personal copy's channel permission" || note "the channel did not connect by itself"
  run_check "$VU" V3 PASS 30
  run_check 0 V3 PASS 30
  run_check "$VU" L0 "PASS|FAIL" 40
  if ! tail -n 30 "$LOG" | grep -q "AUTOMATION u$VU L0 PASS"; then
    run_check "$VU" L0 PASS 60 --es connect forwarded
  fi
  run_check 0 L0 PASS 40
  run_check "$VU" S1 "PASS|INFO" 20
  # Stock Android: the shell reaches a work profile made by the shell (no "no debugging features").
  run_check 0 P2 PASS 60

  section "4. Clone and app-ops with the shell"
  CLONE=$(missing_in_vault)
  if [ -z "$CLONE" ]; then
    # Every launchable app is already in the Vault (on the emulator the new profile gets all of them): take one out of
    # the Vault so the shell can clone it back.
    # Prefer one that asks for storage or media access, so F6/F7 change app-ops it uses.
    CLONE=$(storage_app_in_vault)
    [ -z "$CLONE" ] && CLONE=$(vault_launchables | head -1)
    if [ -n "$CLONE" ]; then
      note "every app is already in the Vault: taking $CLONE out of it first"
      adb shell pm uninstall --user "$VU" "$CLONE" | tr -d '\r' | tail -1
    fi
  fi
  note "app to clone: ${CLONE:-none found}"
  if [ -n "$CLONE" ]; then
    run_check 0 C2 PASS 60 --es clone "$CLONE"
  fi

  section "4b. Phase 1: personal apps and copying, with the shell"
  # The shell may not `pm hide` (it lacks MANAGE_USERS, run 15): a personal app is frozen by turning it off.
  run_act 0 personal OK 60 --es do FREEZE --es pkg com.android.camera2
  expect_flag 0 com.android.camera2 enabled 3 "the shell freezes (turns off) a personal app"
  run_act 0 personal-apps OK 30 --es pkg com.android.camera2
  if printf '%s' "$LAST_ACT" | grep -q "frozen=true"; then pass "the Apps list shows it as frozen"; else fail "the Apps list shows it as frozen"; fi
  run_act 0 personal OK 60 --es do UNFREEZE --es pkg com.android.camera2
  expect_flag 0 com.android.camera2 enabled 1 "and unfreezes it"
  run_act 0 personal-apps OK 30 --es pkg com.android.camera2
  if printf '%s' "$LAST_ACT" | grep -q "frozen=false"; then pass "the Apps list shows it back"; else fail "the Apps list shows it back"; fi
  run_act 0 personal OK 60 --es do SUSPEND --es pkg com.android.camera2
  expect_flag 0 com.android.camera2 suspended true "the shell suspends a personal app"
  run_act 0 personal OK 60 --es do UNSUSPEND --es pkg com.android.camera2
  expect_flag 0 com.android.camera2 suspended false "and unsuspends it"
  # Phase 2: a personal app's own files, through the shell (D57).
  run_act 0 rule OK 60 --es pkg com.android.camera2 --es place personal --es own ALL
  expect_all_files 0 com.android.camera2 allow "own files of a personal app: All files (the shell's app-op)"
  run_act 0 rule OK 60 --es pkg com.android.camera2 --es place personal --es own ANDROID
  expect_all_files 0 com.android.camera2 default "own files of a personal app: back to Android"
  COPY=$(vault_launchables | head -1)
  if [ -n "$COPY" ]; then
    note "copying $COPY back into the Vault with the shell, after taking it out"
    adb shell pm uninstall --user "$VU" "$COPY" | tr -d '\r' | tail -1
    run_act 0 copy OK 60 --es pkg "$COPY"
    if printf '%s' "$LAST_ACT" | grep -q "silently"; then pass "the copy went through the shell (silent)"; else fail "the copy went through the shell (silent)"; fi
    expect_flag "$VU" "$COPY" installed true "the app is in the Vault again"
  fi

  section "5. Destroy the Vault"
  run_check 0 D1 PASS 90 --es confirm DESTROY_VAULT
  if [ -z "$(vault_user)" ]; then pass "no work profile left"; else fail "no work profile left"; fi
fi

finish_smoke "$PART"
