package org.fossify.clock.dialogs

import org.fossify.clock.R
import org.fossify.clock.activities.SimpleActivity
import org.fossify.clock.extensions.dbHelper
import org.fossify.clock.models.AlarmFolder
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.models.RadioItem

/**
 * Pick a destination for "Move" or "Folder" (Samsung "Group"). Mirrors
 * Samsung's "Select groups" sheet: an "Alarms" row (the ungrouped list, with
 * its count) followed by each folder with its alarm count.
 *
 * @param titleId string resource for the dialog title (Move vs Folder).
 * @param currentFolderId the folder the selected alarms currently live in, if
 *   any. It is still listed (Samsung keeps it) but the caller can warn on a
 *   no-op move; we don't hide it.
 * @param callback receives the chosen folder id, or `null` for the ungrouped
 *   "Alarms" destination.
 */
class SelectFolderDialog(
    private val activity: SimpleActivity,
    private val titleId: Int,
    private val currentFolderId: Int? = null,
    private val callback: (folderId: Int?) -> Unit,
) {
    init {
        val folders = activity.dbHelper.getFolders()
        val allAlarms = activity.dbHelper.getAlarms()
        val ungroupedCount = allAlarms.count { it.folderId == null }

        val items = ArrayList<RadioItem>()
        // Id 0 → ungrouped "Alarms". We store the folder id as the value;
        // 0 means "no folder" (ungrouped), a positive id means that folder.
        items.add(RadioItem(0, activity.getString(R.string.alarms) + " ($ungroupedCount)", 0))
        folders.forEach { folder ->
            val count = allAlarms.count { it.folderId == folder.id }
            items.add(RadioItem(folder.id, "${folder.name} ($count)", folder.id))
        }

        RadioGroupDialog(
            activity = activity,
            items = items,
            checkedItemId = currentFolderId ?: 0,
            titleId = titleId,
        ) { value ->
            // value is the RadioItem.value we stored: 0 for ungrouped, the
            // folder id otherwise. Map 0 → null for the caller.
            val folderId = (value as? Int) ?: 0
            callback(folderId.takeIf { it != 0 })
        }
    }
}
