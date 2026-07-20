package org.fossify.clock.dialogs

import org.fossify.clock.R
import org.fossify.clock.activities.SimpleActivity
import org.fossify.commons.dialogs.ConfirmationDialog

/**
 * Confirms ungrouping (dissolving) one or more folders. Samsung shows the
 * reassuring message "Your alarms won't be deleted." with Cancel / Move
 * buttons; the alarms fall back to the ungrouped list.
 */
class UngroupConfirmDialog(
    activity: SimpleActivity,
    onConfirm: () -> Unit,
) {
    init {
        // positive = Move (matches Samsung's confirm verb for ungroup),
        // negative = Cancel (default).
        ConfirmationDialog(
            activity = activity,
            message = activity.getString(R.string.ungroup_warning),
            positive = R.string.move,
        ) {
            onConfirm()
        }
    }
}
