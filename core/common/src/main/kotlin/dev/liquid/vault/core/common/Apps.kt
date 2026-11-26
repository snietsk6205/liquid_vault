package dev.liquid.vault.core.common

import kotlinx.serialization.Serializable
import java.text.Collator
import java.util.Locale

/** Which side of the phone an app is on: your normal apps, or the Vault (the work profile). */
@Serializable
enum class Place(val label: String) {
    PERSONAL("Personal"),
    VAULT("Vault"),
}

/**
 * One app on one side, as the Apps screen shows it (Phase 1). [frozen]: Android's "hidden" state (the app is gone from
 * launchers and cannot run; its data stays, D45). [suspended]: the icon stays, greyed, and the app cannot open.
 */
@Serializable
data class AppRecord(
    val pkg: String,
    val label: String,
    val place: Place,
    /** Built into the phone (it can be switched on in the Vault without copying it, D34). */
    val system: Boolean = false,
    val frozen: Boolean = false,
    val suspended: Boolean = false,
    /** It has an activity a launcher shows (frozen apps are listed anyway). */
    val launchable: Boolean = true,
)

/** A row of the Apps screen: the app, and whether the other side has it too. */
data class AppRow(val app: AppRecord, val onOtherSide: Boolean) {
    /** A short line under the name, in plain words: "Frozen", "In the Vault too", … */
    val status: String
        get() = buildList {
            if (app.frozen) add("Frozen")
            if (app.suspended) add("Suspended")
            if (onOtherSide) add(if (app.place == Place.PERSONAL) "In the Vault too" else "Also on the personal side")
            if (app.place == Place.VAULT && app.system && !onOtherSide) add("Built into the phone")
        }.joinToString(" · ")
}

object AppLists {
    private val collator: Collator = Collator.getInstance(Locale.ROOT).apply { strength = Collator.PRIMARY }

    /**
     * The rows for one tab: that side's apps, sorted by name, filtered by [query] (name or package, any case), each
     * marked when the other side has the same app.
     */
    fun rows(place: Place, personal: List<AppRecord>, vault: List<AppRecord>, query: String = ""): List<AppRow> {
        val mine = if (place == Place.PERSONAL) personal else vault
        val other = (if (place == Place.PERSONAL) vault else personal).map { it.pkg }.toSet()
        val q = query.trim()
        return mine
            .asSequence()
            .distinctBy { it.pkg }
            .filter { q.isEmpty() || it.label.contains(q, ignoreCase = true) || it.pkg.contains(q, ignoreCase = true) }
            .sortedWith { a, b -> collator.compare(a.label, b.label).takeIf { it != 0 } ?: a.pkg.compareTo(b.pkg) }
            .map { AppRow(it, it.pkg in other) }
            .toList()
    }
}
