package dev.liquid.vault.channel

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.Process
import dev.liquid.vault.Env
import dev.liquid.vault.admin.Crossing
import dev.liquid.vault.bridge.AccessLog
import dev.liquid.vault.bridge.TestFolder
import dev.liquid.vault.core.common.AccessEntry
import dev.liquid.vault.core.common.Confirm
import dev.liquid.vault.core.common.DebugLog
import dev.liquid.vault.core.common.asEvidence
import dev.liquid.vault.diag.ChannelCalls
import dev.liquid.vault.diag.Prefs
import dev.liquid.vault.diag.Reports
import dev.liquid.vault.diag.Runner
import dev.liquid.vault.id
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.concurrent.thread

/**
 * The channel between the two copies (DECISIONS.md, D8), after Insular's "Shuttle" (Apache-2.0): each copy holds a
 * lasting (persistable) URI permission to the other copy's own [ChannelProvider], `content://<user>@dev.liquid.vault.channel`.
 * Once both are held, either copy can call the other at any time ([call], [open]), also after a reboot, with no shell.
 *
 * The permissions are handed over by a cross-profile activity start, carried in the intent (and in its result):
 * - "forwarded intent": a cross-profile intent filter for our own action, set by the profile owner;
 * - "CrossProfileApps": Android 11's CrossProfileApps.startActivity, allowed by the profile owner for the Vault copy
 *   (it carries the Vault's permission only: Android does not return results across it).
 */
object Channel {
    const val ACTION_TO_PERSONAL = "dev.liquid.vault.action.CHANNEL_TO_PERSONAL"
    const val ACTION_TO_VAULT = "dev.liquid.vault.action.CHANNEL_TO_VAULT"
    const val EXTRA_VIA = "dev.liquid.vault.extra.VIA"
    const val VIA_FORWARDED = "forwarded intent"
    const val VIA_CROSS_PROFILE_APPS = "CrossProfileApps"
    const val REQUEST_CODE = 7201

    const val GRANT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION

    val authority: String get() = "${Env.app.packageName}.channel"

    fun uriFor(userId: Int): Uri = Uri.parse("content://$userId@$authority")

    /** The user of the other copy: the Vault's from the personal side, the personal user's from the Vault side. */
    fun otherUserId(): Int? = (if (Env.isVaultSide) Env.personalUser() else Env.vaultUser())?.id

    /** Sets the profile owner's filters that let our two channel actions cross (one each way). Vault side only. */
    fun addCrossProfileFilters(dpm: DevicePolicyManager) {
        dpm.addCrossProfileIntentFilter(Env.admin, Crossing.filter(ACTION_TO_VAULT, Intent.CATEGORY_DEFAULT), Crossing.PERSONAL_TO_VAULT)
        dpm.addCrossProfileIntentFilter(Env.admin, Crossing.filter(ACTION_TO_PERSONAL, Intent.CATEGORY_DEFAULT), Crossing.VAULT_TO_PERSONAL)
    }

    /** This copy holds the permission to call the other copy. */
    fun ready(): Boolean {
        val other = otherUserId() ?: return false
        return Env.app.checkUriPermission(uriFor(other), Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun call(method: String, arg: String? = null, extras: Bundle? = null): Bundle? {
        val other = otherUserId() ?: error("there is no other copy (no Vault yet?)")
        return Env.app.contentResolver.call(uriFor(other), method, arg, extras)
    }

    /** Opens a file the other copy serves (the bridge's test folder), read-only. */
    fun open(path: String): ParcelFileDescriptor? {
        val other = otherUserId() ?: error("there is no other copy")
        val builder = uriFor(other).buildUpon().appendPath("test")
        path.split('/').filter { it.isNotEmpty() }.forEach { builder.appendPath(it) }
        val uri = builder.build()
        return Env.app.contentResolver.openFileDescriptor(uri, "r")
    }

    /** The intent that carries this copy's permission across. */
    fun grantIntent(action: String, via: String): Intent = Intent(action).apply {
        addCategory(Intent.CATEGORY_DEFAULT)
        clipData = ClipData.newRawUri("Liquid Vault channel", uriFor(Env.myUserId))
        addFlags(GRANT_FLAGS)
        putExtra(EXTRA_VIA, via)
    }

    /** The result that carries this copy's permission back. */
    fun grantResult(): Intent = Intent().apply {
        clipData = ClipData.newRawUri("Liquid Vault channel", uriFor(Env.myUserId))
        addFlags(GRANT_FLAGS)
    }

    private fun isChannelUri(uri: Uri?): Boolean {
        if (uri == null || uri.scheme != "content") return false
        val auth = uri.authority ?: return false
        val m = Regex("^(\\d+)@(.+)$").find(auth) ?: return false
        return m.groupValues[2] == authority && m.groupValues[1].toInt() != Env.myUserId
    }

    /** Takes (keeps) the other copy's permission from an intent or a result. Returns what happened, for the log. */
    fun takeGrant(context: Context, intent: Intent?, how: String): String {
        val uri = intent?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri ?: intent?.data
        if (!isChannelUri(uri)) return "no channel permission in it (${uri ?: "nothing"})".also { DebugLog.error("Channel", "$how: $it") }
        return try {
            context.contentResolver.takePersistableUriPermission(
                uri!!,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            Prefs.of(context).edit().putString(Prefs.CHANNEL_VIA, how).putLong(Prefs.CHANNEL_AT, System.currentTimeMillis()).apply()
            "kept the permission for $uri ($how)".also { DebugLog.event("Channel", it) }
        } catch (t: Throwable) {
            "could not keep the permission for $uri: ${t.asEvidence()}".also { DebugLog.error("Channel", it) }
        }
    }

    /** Vault side: hands our permission to the personal copy through CrossProfileApps (no result comes back). */
    fun pushWithCrossProfileApps(activity: Activity): String {
        val personal = Env.personalUser() ?: return "no personal user found"
        val cpa = Env.crossProfile
        if (!cpa.canInteractAcrossProfiles()) return "CrossProfileApps says this copy may not work across profiles (canInteractAcrossProfiles = false)"
        val intent = grantIntent(ACTION_TO_PERSONAL, VIA_CROSS_PROFILE_APPS)
            .setClassName(activity.packageName, ChannelToPersonalActivity::class.java.name)
        cpa.startActivity(intent, personal, activity)
        return "sent through CrossProfileApps.startActivity to user ${personal.id}"
    }

    /** Either side: hands our permission across through the profile owner's filter, expecting the other's in the result. */
    fun pushWithForwardedIntent(activity: Activity): String {
        val action = if (Env.isVaultSide) ACTION_TO_PERSONAL else ACTION_TO_VAULT
        @Suppress("DEPRECATION")
        activity.startActivityForResult(grantIntent(action, VIA_FORWARDED), REQUEST_CODE)
        return "sent $action through the cross-profile filter"
    }
}

/**
 * The provider each copy serves to the other copy only. It answers small calls (ping, report, check runs for the other
 * side, the bridge's folder listing) and opens the test folder's files for bridge B.
 */
class ChannelProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    /** Android's uids: user id × 100 000 + app id (UserHandle.PER_USER_RANGE, not public). */
    private val PER_USER_RANGE = 100_000

    private fun enforceCaller(): String {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return "this copy"
        val pkg = callingPackage
        val sameApp = uid % PER_USER_RANGE == Process.myUid() % PER_USER_RANGE
        if (pkg != context?.packageName || !sameApp) {
            DebugLog.error("Channel", "refused a channel call from $pkg (uid $uid)")
            throw SecurityException("Liquid Vault's channel only answers Liquid Vault (asked by $pkg)")
        }
        return "the other copy (user ${uid / 100_000})"
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val context = context ?: return null
        Env.init(context)
        val who = enforceCaller()
        return when (method) {
            "hello" -> {
                Prefs.of(context).edit().putString(Prefs.CHANNEL_HELLO, "$arg at ${System.currentTimeMillis()}").apply()
                DebugLog.event("Channel", "$who said hello ($arg)")
                ping(context)
            }
            "ping" -> ping(context)
            "report" -> Bundle().apply { putString("text", Reports.build(context, includeOtherSide = false)) }
            "run" -> Bundle().apply { putString("json", ChannelCalls.runHeadless(context, arg ?: "", extras)) }
            "bridgeList" -> TestFolder.listForChannel(arg ?: "")
            "isStorageManager" -> Bundle().apply { putBoolean("value", Environment.isExternalStorageManager()) }
            // P2 from the personal copy: read, lift ("lift") or put back ("block") "no debugging features" as the owner.
            "shellAccess" -> {
                if (!Env.isVaultSide) throw IllegalStateException("only the Vault copy owns the Vault")
                val result = runBlocking {
                    if (arg == "block") Runner.undo("P2", null) else Runner.run("P2", null, if (arg == "lift") mapOf("lift" to "true") else emptyMap())
                }
                Bundle().apply {
                    val undoLine = result.evidence.lastOrNull { it.startsWith("undo:") }
                    putString("summary", if (arg == "block" && undoLine != null) undoLine.removePrefix("undo: ") else result.summary)
                    putStringArrayList("lines", ArrayList(result.evidence))
                }
            }
            "destroy" -> {
                if (!Confirm.matches(arg ?: "", Confirm.DESTROY_VAULT)) throw SecurityException("the typed confirmation does not match")
                ChannelCalls.destroyFromChannel(context)
            }
            else -> throw IllegalArgumentException("unknown channel call $method")
        }
    }

    private fun ping(context: Context): Bundle = Bundle().apply {
        putString("side", Env.side().id)
        putInt("user", Env.myUserId)
        putString("version", "${Env.versionName()} (build ${Env.versionCode()})")
        putBoolean("profileOwner", Env.isVaultSide)
        putBoolean("channelReady", Channel.ready())
        putBoolean("shellAlive", dev.liquid.vault.shell.Shell.alive)
        putLong("setupDoneAt", Prefs.of(context).getLong(Prefs.SETUP_DONE_AT, 0))
        putLong("now", System.currentTimeMillis())
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val context = context ?: return null
        Env.init(context)
        enforceCaller()
        val segments = uri.pathSegments
        require(segments.firstOrNull() == "test") { "only the test folder is served" }
        val relative = segments.drop(1).joinToString("/")
        if (mode != "r") {
            AccessLog.add(AccessEntry(System.currentTimeMillis(), "channel", callingPackage, Binder.getCallingUid() / 100_000, "open ($mode)", relative, false, "Phase 0 serves the test folder read-only"))
            throw SecurityException("Phase 0 serves the test folder read-only")
        }
        val file: File = TestFolder.resolve(relative) ?: throw java.io.FileNotFoundException(relative)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

/**
 * Where the other copy's channel permission arrives. Each copy enables only the activity for its own side, so an intent
 * sent across resolves to the other copy (SideComponents). It keeps the permission, answers with its own in the result,
 * and says hello back so the sender knows it worked.
 */
abstract class ChannelActivity : Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        Env.init(this)
        val via = intent.getStringExtra(Channel.EXTRA_VIA) ?: "an unknown way"
        Channel.takeGrant(this, intent, "the other copy sent it ($via)")
        setResult(RESULT_OK, Channel.grantResult())
        val app = applicationContext
        thread(name = "channel-hello") {
            runCatching { Channel.call("hello", "the ${Env.side().label.lowercase()} received the permission ($via)") }
                .onFailure { DebugLog.error("Channel", "could not say hello back: ${it.asEvidence()}") }
            app.sendBroadcast(Intent(ACTION_CHANNEL_CHANGED).setPackage(app.packageName))
        }
        finish()
    }

    companion object {
        const val ACTION_CHANNEL_CHANGED = "dev.liquid.vault.action.CHANNEL_CHANGED"
    }
}

/** Runs in the personal copy: the Vault copy's permission arrives here. */
class ChannelToPersonalActivity : ChannelActivity()

/** Runs in the Vault copy: the personal copy's permission arrives here. */
class ChannelToVaultActivity : ChannelActivity()
