package org.fossify.clock.selection

import org.fossify.clock.models.Alarm
import org.fossify.clock.models.AlarmFolder
import org.fossify.clock.models.FolderAlarmCounts

/**
 * Pure, Android-free model of the Samsung-style contextual action bar.
 *
 * The bottom action bar's contents are derived entirely from the *composition*
 * of the current selection (alarms vs folders, enabled state, empty folders)
 * and whether the user is inside a folder view. UI code feeds a
 * [SelectionSnapshot] in and gets back the set of [SelectionAction]s to show,
 * plus their dynamic labels.
 *
 * Keeping this logic here — rather than interleaved with RecyclerView/Menu
 * plumbing — makes the action matrix cheap to test exhaustively and hard to
 * regress. See `SelectionActionsTest` for the full Samsung-feature matrix.
 */

/**
 * The contextual actions Samsung Clock exposes from the alarm/folder selection
 * mode. One enum value per bottom-bar button.
 */
enum class SelectionAction {
    TURN_ON,
    TURN_OFF,
    GROUP,
    MOVE,
    UNGROUP,
    DELETE,
    DELETE_ALL,
}

/**
 * A frozen view of what is selected plus the context needed to decide which
 * actions apply.
 *
 * @param alarms the standalone [Alarm]s currently selected (never includes
 *   alarms that live inside a selected folder — those operate via the folder).
 * @param folders the [AlarmFolder]s currently selected.
 * @param folderCounts enabled/disabled breakdown per folder id, used to derive
 *   a folder's effective on/off state and whether it is empty.
 * @param inFolderView true when the user is inside a folder screen (Samsung
 *   swaps the per-alarm "Delete" action for "Delete all" in this case).
 */
data class SelectionSnapshot(
    val alarms: List<Alarm>,
    val folders: List<AlarmFolder>,
    val folderCounts: Map<Int, FolderAlarmCounts>,
    val inFolderView: Boolean,
) {
    val hasFolder: Boolean get() = folders.isNotEmpty()
    val hasAlarm: Boolean get() = alarms.isNotEmpty()
    val isEmpty: Boolean get() = !hasFolder && !hasAlarm
}

/**
 * Compute the set of actions Samsung Clock would show for [snapshot].
 *
 * Rules (reconstructed from the reference recording — see `selection.txt`):
 *
 * - Nothing selected: no actions. (The header reads "Select alarms.")
 * - A folder is selected: no Group/Move (folders aren't moved into folders in
 *   Samsung; you ungroup or delete them). Ungroup appears iff the folder is
 *   non-empty. Turn on/off reflects the folder's aggregated enabled state.
 * - An alarm is selected: Group and Move are both offered (separate flows).
 * - "Delete all" replaces "Delete" when any folder is in the selection *or*
 *   when the user is inside a folder view (matches the in-folder recording).
 * - Turn on appears iff at least one selected item is currently off; Turn off
 *   appears iff at least one is currently on. With a mix, both can appear
 *   (Samsung shows a single dynamic button, but exposing both lets the UI
 *   pick; in practice a mixed selection surfaces Turn on by convention).
 */
fun computeActions(snapshot: SelectionSnapshot): Set<SelectionAction> {
    if (snapshot.isEmpty) return emptySet()

    val actions = LinkedHashSet<SelectionAction>()

    val anyOn = anyEnabled(snapshot)
    val anyOff = anyDisabled(snapshot)
    if (anyOff) actions += SelectionAction.TURN_ON
    if (anyOn) actions += SelectionAction.TURN_OFF

    // Group/Move only apply to selected alarms (not folders).
    if (snapshot.hasAlarm && !snapshot.hasFolder) {
        actions += SelectionAction.GROUP
        actions += SelectionAction.MOVE
    }

    // Ungroup applies to selected non-empty folders, but only when the
    // selection is folder-only — Samsung drops it once an alarm is also
    // selected (the mixed case is Turn on / Delete all).
    val folderOnly = snapshot.hasFolder && !snapshot.hasAlarm
    if (folderOnly && snapshot.folders.any { !isFolderEmpty(snapshot, it.id) }) {
        actions += SelectionAction.UNGROUP
    }

    // Delete vs Delete all.
    // "Delete all" appears when an alarm is selected *inside* a folder view,
    // or when a folder and an alarm are selected together (the folder's alarms
    // are implicitly in scope). A folder selected alone uses plain "Delete".
    val mixedFolderAndAlarm = snapshot.hasFolder && snapshot.hasAlarm
    if (snapshot.inFolderView || mixedFolderAndAlarm) {
        actions += SelectionAction.DELETE_ALL
    } else {
        actions += SelectionAction.DELETE
    }

    return actions
}

/**
 * Human-readable label for an action in the given context. The [snapshot] is
 * accepted for API symmetry with [computeActions] and so callers don't need a
 * second lookup path; the current labels are static but Turn on/off wording
 * may diverge by locale later.
 */
@Suppress("UNUSED_PARAMETER")
fun actionLabel(action: SelectionAction, snapshot: SelectionSnapshot): String = when (action) {
    SelectionAction.TURN_ON -> "Turn on"
    SelectionAction.TURN_OFF -> "Turn off"
    SelectionAction.GROUP -> "Folder"
    SelectionAction.MOVE -> "Move"
    SelectionAction.UNGROUP -> "Ungroup"
    SelectionAction.DELETE -> "Delete"
    SelectionAction.DELETE_ALL -> "Delete all"
}

/** True if any selected alarm or folder currently has an enabled alarm. */
fun anyEnabled(snapshot: SelectionSnapshot): Boolean {
    if (snapshot.alarms.any { it.isEnabled }) return true
    return snapshot.folders.any { folderCounts(snapshot, it.id).enabled > 0 }
}

/** True if any selected alarm or folder currently has a disabled alarm. */
fun anyDisabled(snapshot: SelectionSnapshot): Boolean {
    if (snapshot.alarms.any { !it.isEnabled }) return true
    return snapshot.folders.any { folderCounts(snapshot, it.id).disabled > 0 }
}

private fun isFolderEmpty(snapshot: SelectionSnapshot, folderId: Int): Boolean =
    folderCounts(snapshot, folderId).total == 0

private fun folderCounts(snapshot: SelectionSnapshot, folderId: Int): FolderAlarmCounts =
    snapshot.folderCounts[folderId] ?: FolderAlarmCounts(enabled = 0, disabled = 0)
