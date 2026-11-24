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
        val id = extras.getString("run")
        if (savedInstanceState != null || id == null) {
            finish()
            return
        }
        val map = extras.keySet().filter { extras.getString(it) != null }.associateWith { extras.getString(it)!! }
        lifecycleScope.launch {
            val answer = extras.getString("answer")?.toIntOrNull()
            val result = when {
                answer != null -> {
                    Runner.answer(id, answer)
                    CheckStore.get(id)
                }
                extras.getString("undo") == "true" -> Runner.undo(id, this@AutomationActivity, map)
                else -> Runner.run(id, this@AutomationActivity, map)
            }
            val status = result?.status ?: CheckStatus.NOT_RUN
            Log.i(VaultApp.LOG_TAG, "AUTOMATION $id ${status.name} — ${result?.summary}")
            result?.evidence?.forEach { e -> e.lines().forEach { Log.i(VaultApp.LOG_TAG, "AUTOMATION   · $it") } }
            result?.question?.let { Log.i(VaultApp.LOG_TAG, "AUTOMATION   ? $it") }
            finish()
        }
    }
}
