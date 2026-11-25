# Testing on the phone

One section per phase gate. Do the steps in order; each says what you should see. Commands are for your Linux PC
(fish shell) with the phone on USB (or wireless) debugging; everything else is taps. When you are done, send me the
reports (step 30) and anything that surprised you (a screenshot helps).

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
