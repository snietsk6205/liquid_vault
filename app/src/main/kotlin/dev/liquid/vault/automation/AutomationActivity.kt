package dev.liquid.vault.automation

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import dev.liquid.vault.Env
import dev.liquid.vault.VaultApp
import dev.liquid.vault.core.common.CheckStatus
import dev.liquid.vault.diag.CheckStore
import dev.liquid.vault.diag.Host
import dev.liquid.vault.diag.Prefs
import dev.liquid.vault.diag.Runner
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import dev.liquid.vault.control.ActionQueueStore
import dev.liquid.vault.control.Cloning
import dev.liquid.vault.control.Outcome
import dev.liquid.vault.control.VaultControl
import dev.liquid.vault.core.common.AppRecord
import dev.liquid.vault.core.common.Place
import dev.liquid.vault.core.common.ShellAction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lets the emulator test in CI run checks from adb:
 * `am start -W -n dev.liquid.vault/.automation.AutomationActivity --es run S1` (add `--user <id>` for the Vault copy).
 * Extras: `run` (check id), `undo` (true), `answer` (index), `clone`/`vaultApp` (targets), `confirm` (typed phrase),
 * `connect` (forwarded / cpa). Results go to logcat as "AUTOMATION <id> <STATUS> — <summary>".
 *
 * Guarded by INTERACT_ACROSS_USERS_FULL, which only the shell and the system hold, so no app can start it; anything
 * the shell does here it could already do itself (DECISIONS.md, D13).
 */
class AutomationActivity : ComponentActivity(), Host {
    override val activity get() = this
    private var pending: CompletableDeferred<Pair<Int, Intent?>>? = null
    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        pending?.complete(res.resultCode to res.data)
    }

    override suspend fun startForResult(intent: Intent): Pair<Int, Intent?> = withContext(Dispatchers.Main) {
        val d = CompletableDeferred<Pair<Int, Intent?>>()
        pending = d
        launcher.launch(intent)
        d.await()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Env.init(this)
        CheckStore.load(this)
        val extras = intent.extras ?: Bundle()
        extras.getString("clone")?.let { Prefs.app.edit().putString(Prefs.CLONE_TARGET, it).apply() }
        extras.getString("vaultApp")?.let { Prefs.app.edit().putString(Prefs.VAULT_APP_TARGET, it).apply() }
        val act = extras.getString("act")
        if (savedInstanceState == null && act != null) {
            runAct(act, extras)
            return
        }
        val id = extras.getString("run")
        if (savedInstanceState != null || id == null) {
            finish()
            return
        }
        Log.i(VaultApp.LOG_TAG, "AUTOMATION-START u${Env.myUserId} $id")
        // adb cannot pass spaces easily: "DESTROY_VAULT" stands for "DESTROY VAULT".
        val map = extras.keySet().filter { extras.getString(it) != null }.associateWith { k -> extras.getString(k)!!.let { if (k == "confirm") it.replace('_', ' ') else it } }
        lifecycleScope.launch {
            val answer = extras.getString("answer")?.toIntOrNull()
            val result = when {
                // Read the saved result without running (for checks whose process Android may kill, like F8).
                extras.getString("peek") == "true" -> CheckStore.get(id)
                answer != null -> {
                    Runner.answer(id, answer)
                    CheckStore.get(id)
                }
                extras.getString("undo") == "true" -> Runner.undo(id, this@AutomationActivity, map)
                else -> Runner.run(id, this@AutomationActivity, map)
            }
            val status = result?.status ?: CheckStatus.NOT_RUN
            Log.i(VaultApp.LOG_TAG, "AUTOMATION u${Env.myUserId} $id ${status.name} — ${result?.summary}")
            result?.evidence?.forEach { e -> e.lines().forEach { Log.i(VaultApp.LOG_TAG, "AUTOMATION   · $it") } }
            result?.question?.let { Log.i(VaultApp.LOG_TAG, "AUTOMATION   ? $it") }
            finish()
        }
    }

    /**
     * Phase 1's actions, through the same code the screens use, for the emulator test. Logs
     * "AUTOMATION-ACT u<user> <act> <OK|FAIL> — <words>". Extras: `do` (what), `pkg`, `on`.
     */
    private fun runAct(act: String, extras: Bundle) {
        Log.i(VaultApp.LOG_TAG, "AUTOMATION-START u${Env.myUserId} act:$act")
        val pkg = extras.getString("pkg")
        val what = extras.getString("do")
        lifecycleScope.launch {
            val outcome: Outcome = runCatching {
                when (act) {
                    "vault-apps" -> VaultControl.apps()?.let { apps ->
                        val one = pkg?.let { p -> apps.firstOrNull { it.pkg == p } }
                        Outcome(true, "${apps.size} apps" + (pkg?.let { " · $it: " + (one?.let { a -> "frozen=${a.frozen} suspended=${a.suspended}" } ?: "not listed") } ?: ""))
                    } ?: Outcome(false, "the Vault copy did not answer")
                    "vault-app" -> VaultControl.appAction(what ?: "", pkg ?: "")
                    "personal" -> {
                        val action = ShellAction.valueOf(what ?: "")
                        val app = AppRecord(pkg ?: "", pkg ?: "", Place.PERSONAL)
                        val o = ActionQueueStore.request(this@AutomationActivity, action, app)
                        o.copy(message = "${o.message} · waiting: ${ActionQueueStore.queue.value.size}")
                    }
                    "queue-apply" -> {
                        ActionQueueStore.applyDue(this@AutomationActivity)
                        Outcome(true, "waiting: ${ActionQueueStore.queue.value.size}")
                    }
                    "copy" -> {
                        val system = runCatching {
                            packageManager.getApplicationInfo(pkg ?: "", PackageManager.MATCH_UNINSTALLED_PACKAGES).flags and ApplicationInfo.FLAG_SYSTEM != 0
                        }.getOrDefault(false)
                        Cloning.copyIntoVault(this@AutomationActivity, AppRecord(pkg ?: "", pkg ?: "", Place.PERSONAL, system = system))
                    }
                    "vault-lock" -> VaultControl.lock()?.let { Outcome(true, "separate=${it.separate} secure=${it.secure} locked=${it.locked}") } ?: Outcome(false, "no answer")
                    "vault-vpn" -> VaultControl.vpn()?.let { Outcome(true, "current=${it.current} apps=${it.apps.size}") } ?: Outcome(false, "no answer")
                    "vault-icon" -> VaultControl.setIconVisible(extras.getString("on") == "true")
                    "vault-state" -> VaultControl.state()?.let { Outcome(true, "shellBlocked=${it.shellBlocked} iconVisible=${it.iconVisible} setupVersion=${it.setupVersion}") } ?: Outcome(false, "no answer")
                    else -> Outcome(false, "unknown act $act")
                }
            }.getOrElse { Outcome(false, it.message ?: it.javaClass.simpleName) }
            Log.i(VaultApp.LOG_TAG, "AUTOMATION-ACT u${Env.myUserId} $act ${if (outcome.ok) "OK" else "FAIL"} — ${outcome.message}")
            finish()
        }
    }
}
