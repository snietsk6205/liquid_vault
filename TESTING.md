# Testing on the phone

One section per phase gate. Do the steps in order; each says what you should see. Commands are for your Linux PC
(fish shell) with the phone on USB (or wireless) debugging; everything else is taps. When you are done, send me the
reports (step 30) and anything that surprised you (a screenshot helps).

## Phase 1 — The Vault's core (release 0.2.0)

About 30 minutes. Keep your Vault as it is. Each step says what to tap and what you should see; if something looks
different, note the step number and what you saw. You do not need Diagnostics for this test until the last step.

Two words used below: **Freeze** = the app disappears from your apps and cannot run, nothing of it is deleted.
**Suspend** = its icon stays, grey, and it cannot open, nothing is deleted.

### Install

1. Install 0.2.0 over 0.1.1 (the APK from the release page). One install updates both copies:

   ```fish
   adb install -r ~/Downloads/LiquidVault-0.2.0-build*.apk
   ```

2. Open **Liquid Vault** (your normal icon). It opens on **Home** now, with three parts: **The Vault**, **Apps** and
   **The shell (Shevery)**, then **More**. In your launcher's **Work** tab, Liquid Vault's own icon is gone: that is
   on purpose (step 15 brings it back if you want it). If Liquid Vault's icon also left your home screen, add it again
   from the app drawer: its launcher entry has a new name (that is what lets the Vault copy hide its icon).

### The Vault card

3. The card says **Running · N apps** (N = how many apps the Vault has). Tap **Pause**: the card says **Paused** and
   the Work tab's apps turn grey. Tap **Resume**: back to **Running** (Android may ask for your PIN first).

### Vault apps

4. Home › **Vault apps**. You see the Vault's apps with the work badge, a search field, and two tabs at the top:
   **Vault** and **Personal**.
5. Tap **Komikku** (or any app you don't mind). A sheet opens: **Open**, **Freeze**, **Suspend**, **App info**,
   **Remove from the Vault**. Tap **Freeze**: a message says it is frozen, the app vanishes from the Work tab, and in
   the list it is greyed with **Frozen** under its name.
6. Tap it again › **Unfreeze**: it is back in the Work tab.
7. Tap it › **Suspend**: in the Work tab its icon is grey and opening it shows Android's "paused" message. Tap it in
   the list › **Unsuspend**.
8. Tap it › **Open**: it starts (in the Vault).

### Personal apps

9. Switch to the **Personal** tab. Apps that are also in the Vault say **In the Vault too**.
10. With Shevery running: tap an app that is not in the Vault yet (a small one, for example a calculator) › **Copy into
    the Vault**. Expect "copied silently by the shell" and no question from Android; it shows up in the **Vault** tab.
11. The waiting list (the shell off): open Shevery and stop it. In Liquid Vault, Personal tab › that same small app ›
    **Freeze**. Expect "Freeze … waits for the shell". Back on **Home**, the shell part lists it ("Waits for the
    shell"). Start Shevery again, then swipe Liquid Vault away from recents and open it: the list is empty and the
    app is gone from your normal apps (Android's Settings › Apps lists it as disabled: that is how the shell freezes a
    personal app). Apps › Personal › the app (it says **Frozen**) › **Unfreeze**: it is back in your normal apps.

### Copying without the shell

12. With Shevery stopped: Apps › Personal › an app that is not in the Vault and that you downloaded yourself ›
    **Copy into the Vault**. Android asks "Do you want to install this app?" (in the Vault): **Install**. A small card
    says **Done**; the app is in the Vault tab. Start Shevery again afterwards.

### Removing

13. Apps › Vault › the app you copied in step 10 or 12 › **Remove from the Vault**. Type **REMOVE** › **Remove**.
    It leaves the Vault (with the shell: silently; without: Android asks first). Your normal copy stays.

### The Vault's own lock

14. Home › More › **The Vault's lock** › **Set a lock**. Android's screen opens in the Vault: choose a PIN different
    from your phone's (Android may first ask whether to use one lock: choose the Vault's own). Back in Liquid Vault the
    page says **A lock of its own**. Tap **Lock now**, then open a Vault app: it must ask for the Vault's PIN.
    Optional, to see whether MagicOS keeps the Vault's files closed: restart the phone and unlock only the phone (not
    the Vault). Open any app's file picker (for example Gmail › attach file), side menu › **Liquid Vault**: write
    down whether it shows the test folder or nothing. Then unlock the Vault and look again.
    If you don't want the lock: same page › **Change it** › choose **None**.

### Settings and boot

15. Home › More › **Settings**:
    - **Show Liquid Vault in the Work tab**: on → its icon comes back in the Work tab; off → gone again.
    - **Remind me to start Shevery**: turn it on and allow notifications. Restart the phone: after unlocking, a
      notification **Start Shevery** appears; tapping it opens Shevery.
    - Optional: **Turn wireless debugging on** needs a one-time permission first (the page shows the adb command and a
      **Copy the command** button). With it, wireless debugging is on after a restart.

### VPN (only if you use a VPN app)

16. Copy your VPN app into the Vault (step 10), open it in the Vault once and sign in. Home › More › **VPN**: choose it
    › **Apply**. A browser in the Vault now shows the VPN's address (search "what is my IP"); a browser on the personal
    side shows your normal one.

### Report

17. Home › More › **Diagnostics** › **Copy report**, and send it to me with: the step numbers where you saw something
    else than written, and in a few words what still feels hard.

## Phase 0 re-run (release 0.1.1): only the changed checks

About 25 minutes. Keep the Vault you have (no need to destroy or recreate it). Results of 0.1.0 stay in each copy; the
rows you re-run are replaced. What each 0.1.0 result meant is in `DECISIONS.md` D25–D36.

### Install

1. Install 0.1.1 over 0.1.0 (the APK from the release page). One install updates both copies:

   ```fish
   adb install -r ~/Downloads/LiquidVault-0.1.1-build*.apk
   ```

2. Start Shevery. Swipe Liquid Vault away from recents, open the personal copy, then **Open the Vault copy** once and
   go back (so both copies run 0.1.1).

### The shell and the Vault (P2, then C2 and F6/F7 again)

3. Personal copy: **P2** › **Check with the shell**. Expect **FAIL** "the Vault blocks the shell". The row lists what
   `dumpsys user` says about the Vault: the last line tells where "no debugging features" comes from (the profile itself,
   or device policy).

4. Vault copy: **P2** › **Check**. Expect **FAIL**, and a line "set as Liquid Vault's own policy: yes/no". Then tap
   **Let the shell in**:
   - **PASS** "lifted": go on with step 5.
   - **FAIL** "still on after clearing it": MagicOS keeps the shell out of the Vault and the owner cannot change it. That
     is a MagicOS answer: skip steps 5–6, do the rest, and send me the report.

5. Only if P2 passed in step 4. Personal copy:
   - **P2** › **Check with the shell**: PASS "the shell reaches the Vault".
   - **C2**: under **Clone an app**, keep Aves (or choose an app not in the Vault yet) › Run: PASS, and no "install?"
     question this time.
   - **F6** › Run, **F7** › Run, then **F7** › **Undo** (Chrome as the Vault app is fine for these now: they read the
     result back from Android, you answer nothing). Expect PASS on F6 ("the shell's app-ops reach …: … stayed denied").
   - Vault copy (swipe it away from recents and open it again): **S1** › Run. With the shell let in, Shevery may now
     reach the Vault copy too (PASS); INFO "not needed here" is fine.

6. Vault copy: **P2** › **Undo: block the shell again** (until we decide Q5 in `docs/QUESTIONS.md`), then **P2** ›
   **Check**: FAIL "the Vault blocks the shell" again.

### Storage with a gallery (F1/F2) and the owner's app-ops (F4/F5)

7. If Aves is not in the Vault yet: Vault copy › **Clone an app** › **Choose** › Aves › **C3** › Run › Install.

8. Vault copy › **Storage for a Vault app**: tap **Put a test photo in the Vault**, then **Choose** › **Aves** (the list
   now says "asks for photos/videos: good for F1/F2"). Open Aves in the Vault once (badged icon) and allow photos if it
   asks: it should show the purple "Liquid Vault test photo".

9. Vault copy: **F1** › Run, open Aves in the Vault, come back and answer ("No photos now" is the PASS answer). **F2** ›
   Run, open Aves: the test photo is back ("It shows the photo"). **F2** › **Undo**.

10. Vault copy: **F4** › Run (expect PASS: "the owner's app-ops reach Aves …"; the lines say which app-ops went back
    because they follow Aves's permissions), **F5** › Run, **F5** › **Undo**. Optional: if you have a file manager that
    asks for All files access (the list says "asks for All files: good for F4–F7"), clone it with C3 and run F4/F5 with
    it too.

### The refused app (B3)

11. Bridge B: personal copy › open **Gmail** › compose a new mail › attach a file (paper clip › Attach file). In the
    picker, open the side menu (☰) › **Liquid Vault** › **Liquid Vault test** › **hello.txt**. Gmail should fail to
    attach it. Back in the personal copy: **B3** › Run: **PASS** "com.google.android.gm refused when it opened what it
    picked". Discard the draft.

12. The allowed list, the other way round: personal copy › **The bridge** › **Add an app** › Gmail. Attach hello.txt the
    same way: now it works. **B3** › Run: it lists Gmail as refused before and "allowed (on the allowed list)" after.
    Then **Clear the list** (and delete the draft).

13. Bridge A (optional): Vault copy › **B1** › **Open the Work tab**. In Gmail, attach again and switch the picker to
    **Work** › **Liquid Vault test** › hello.txt: refused (the Vault copy's list is its own). **B3** in the personal copy
    shows it "through bridge A". Then Vault copy › **B1** › **Undo: close it**.

### Gaps from the first run

14. Personal copy: **Run safe checks**. **R3** should now read the two folder grants (PASS "channel and 2 folder
    grant(s) work"); it ran before B1/B2 last time.

15. Optional: Vault copy › **Clone an app** › **Choose** an app built into the phone that is not in the Vault yet (for
    example Calculator or Notepad) › **C4** › Run: PASS (switched on, nothing copied). Phase 1 clones built-in apps
    this way (D34).

16. Personal copy: **Copy report**, and send it to me. D1 (destroy) and V2 are not needed now: Phase 1's test covers
    creating and destroying the Vault.

## Phase 0 — Proof (release 0.1.0)

About 45 minutes. The app has two copies, and you will switch between them:

- the **personal copy**: Liquid Vault in your normal apps; its Diagnostics heading is **blue**;
- the **Vault copy**: Liquid Vault inside the Vault (badged icon in your launcher's Work tab); its heading is
  **purple**. Each copy's header has a button that opens the other copy.

Every check is a row: tap it to open it (what it does, what it found, its buttons). **Run safe checks** runs every
check that changes nothing. Statuses: PASS, FAIL, SKIPPED (the reason says why, e.g. "needs shell"), NEEDS YOU (answer
the question in the row), INFO, NOT RUN.

### Before you start

1. **Remove Insular's work profile** (you said you would): Settings › Users & accounts (or Accounts) › Work profile ›
   Remove work profile, or from Insular itself. Check that only your main user is left:

   ```fish
   adb shell pm list users
   ```

   You should see one line, `UserInfo{0:…}`, and no `UserInfo{11:…}`.

2. **Install the app** (the APK from the release page, or from the CI run's artifacts):

   ```fish
   adb install -r ~/Downloads/LiquidVault-0.1.0-build*.apk
   ```

3. **Allow pausing the Vault** (one time; MagicOS ignores it only for undeclared permissions, and this one is
   declared):

   ```fish
   adb shell pm grant dev.liquid.vault android.permission.MODIFY_QUIET_MODE
   ```

4. **Let it start at boot** (needed for the reboot test): Settings › Apps › App launch › Liquid Vault › Manage
   manually › Auto-launch **on** (also Secondary launch and Run in background). After the Vault exists (step 9), do
   the same for the Vault's Liquid Vault if MagicOS lists it separately (badged icon).

### Personal copy, before the Vault

5. Open Liquid Vault. Tap **Run safe checks**. Expect: **A1** INFO, **A2** INFO, **V0** PASS ("no work profile on this
   phone"), **Q0** PASS. **S1** FAIL and S3 SKIPPED are normal if Shevery is not running yet.

6. **Shell**: start Shevery as you usually do. Then swipe Liquid Vault away from recents and open it again (Shevery
   connects to apps when they start). Open **S1** › Run. Expect PASS, and note which door it names: "Shevery's own
   client API" or "the official Shizuku API" (this answers "does Shevery work with the Shizuku API?"). If S1 still
   fails, open Shevery and look at its list of apps; then tell me what it shows.

7. Open **S2** › **Check / ask Shevery**. Shevery shows its Allow dialog: tap Allow (always). Then tap the button again:
   PASS. Open **S3** › Run: PASS ("commands run as the shell user (uid 2000)").

### Create the Vault

8. Open **V1** › **Start Android's setup**. Go through Android's screens (Accept & continue…).
   - If a Vault is made: V1 PASS. The Vault copy opens for a moment to finish its setup, then go back to the personal
     copy (recents).
   - If MagicOS refuses or cancels: V1 FAIL. That is a valid result; go on with step 9.

9. **Only if V1 failed**: open **V2** › **Create with the shell** (Shevery must be running). It takes up to a minute;
   every command and its answer is listed in the row. Expect PASS and the Vault copy opening once (purple heading);
   come back to the personal copy. If V2 stops halfway, it says "the half-made profile (user N) is still there": tap
   **Remove a half-made profile…** and type `REMOVE PROFILE`, then tell me what the row says.

10. Personal copy: **Run safe checks**. Expect **V3** PASS, **V0** SKIPPED ("the Vault already exists"), **L0** PASS
    ("both copies can call each other") and **L1** PASS. If L0 fails, see step 12.

### The Vault copy

11. Tap **Open the Vault copy** in the header (purple heading now). Tap **Run safe checks**. Expect **V3** PASS, **L0**
    PASS, **L1** PASS, **R2** PASS.

12. **Only if L0 failed** on either side: in the Vault copy open **L0** › **Connect (forwarded intent)**; if it still
    fails, **Connect (CrossProfileApps)**; then in the personal copy **L0** › **Connect (forwarded intent)**. Each try
    writes what happened into the row: keep the rows as they are for the report.

13. Vault copy: **P1** › Run: PASS ("block screenshots" on and off). **F8** › Run: PASS (the Vault copy gets All files
    in the Vault); then **Undo**. Android closes an app whose All files access is removed, so the Vault copy may
    vanish on Undo: open it again (Open the Vault copy) and F8 shows the result.

### Clone

14. Personal copy: under **Clone an app**, **Choose** an app you would like in the Vault that shows photos (a gallery
    or a file manager): you will use it for the storage checks. Best is a file manager that asks for **All files
    access**: then the storage checks also show what happens to All files. Open **C2** › Run: PASS (needs Shevery).

15. Vault copy: under **Clone an app**, **Choose** another app (one not in the Vault yet). Run **C1**: FAIL is the
    expected answer ("Android allows it only for affiliated profiles"). Run **C3**: Android may ask "Do you want to
    install this app?" › Install; the row turns PASS a few seconds later. **C4** says SKIPPED unless you picked an app
    built into the phone (then it should PASS).

### Storage for a Vault app

16. Vault copy: under **Storage for a Vault app**, tap **Put a test photo in the Vault**, then **Choose** the gallery or
    file app you cloned in step 14. Open that app in the Vault once and check it shows the test photo (allow its
    permission if it asks).

17. Vault copy, one at a time; after each, open the app in the Vault, look for the test photo, come back and answer the
    question in the row:
    - **F1** (deny by policy) › answer;
    - **F2** (allow by policy) › answer; then **Undo**;
    - **F3** › Run: FAIL is the expected answer (a policy cannot give All files);
    - **F4** (deny by the owner's app-ops, no shell) › answer;
    - **F5** (allow by the owner's app-ops) › answer; then **Undo**.

18. Personal copy: under **Storage for a Vault app**, **Choose** the same Vault app. **F6** (deny with the shell's
    app-ops) › answer; **F7** (allow) › answer; then **Undo**.

### The bridge

19. Vault copy: **B0** › Run: PASS (the test folder exists). **B1** › **Open the Work tab**: PASS.

20. Personal copy: **B1** › **Open the system picker**. In the picker, switch to the **Work** tab, open
    **Liquid Vault test**, tap **Use this folder**, then **Allow**. Back in Diagnostics, answer the question (which
    folders the Work tab showed). Expect PASS with the folder's listing and "hello.txt says: Hello from the Vault."

21. **The refused app**: open another app that can open a folder with the system picker (for example a file manager's
    "add storage" / "open folder"), pick the same **Work › Liquid Vault test** folder. It should show an error or an
    empty folder. Back in the personal copy: **B3** › Run: PASS ("refused access(es) by …").

22. Vault copy: **B1** › **Undo: close it**.

23. Vault copy: **B2** › **Prepare**. Personal copy: **B2** › **Open the system picker**: it opens on **Liquid Vault**
    (if not, pick it in the picker's side menu) › **Use this folder** › **Allow**. Expect PASS. Repeat step 21 with the
    other app and the **Liquid Vault** place, then **B3** again.

### Pause and resume

24. Personal copy: **Q1** › **Pause the Vault**: PASS (the Vault's apps grey out). **Q2** › **Resume the Vault**: PASS
    (Android may ask for your PIN first).

### After a reboot, with the shell off

25. Restart the phone. Unlock it. **Do not start Shevery.** Wait one minute.

26. Personal copy: **Run safe checks**. Expect **R1** PASS, **R3** PASS, **R4** PASS ("the shell is off…"), **L0**/**L1**
    PASS; **S1** FAIL and **S3** SKIPPED ("needs shell") are expected. Run **Q1** and **Q2** again: PASS.

27. Vault copy: **Run safe checks**. Expect **R1**, **R2**, **R3** PASS. If R1 fails on either side ("did not start at
    the last boot"), recheck step 4 and reboot once more.

### Reports and the end

28. Personal copy: **Copy report**, and paste it to me. It includes the Vault copy's report when L0 works; if it says
    the other copy could not be reached, also open the Vault copy and **Copy report** there.

29. Personal copy: **D1** › **Destroy the Vault…** › type `DESTROY VAULT` › Confirm. Expect PASS: the Vault and
    everything in it is gone (check with `adb shell pm list users`).

30. **If V1 worked in step 8**, please also try the shell way once: start Shevery, **V2** › Create with the shell (PASS),
    **V3** and **L0** (PASS), then **D1** again. Then **Copy report** once more and send it too (results are kept between
    runs).

### If something goes wrong

- A crash: send the output of

  ```fish
  adb logcat -d | grep -A30 "FATAL EXCEPTION"
  ```

- A screen that looks wrong: a screenshot. A row that stays RUNNING: open the other copy and back, then copy the report.
