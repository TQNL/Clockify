package org.fossify.clock.dialogs

import android.text.InputFilter
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import org.fossify.clock.R
import org.fossify.clock.activities.SimpleActivity
import org.fossify.clock.helpers.FOLDER_NAME_MAX_LENGTH
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.value

class RenameFolderDialog(
    val activity: SimpleActivity,
    currentName: String,
    val callback: (name: String) -> Unit,
) {
    init {
        val editText = EditText(activity).apply {
            setText(currentName)
            setSelection(currentName.length)
            filters = arrayOf(InputFilter.LengthFilter(FOLDER_NAME_MAX_LENGTH))
        }

        activity.getAlertDialogBuilder()
            .setTitle(R.string.rename_folder)
            .setView(editText)
            .setPositiveButton(org.fossify.commons.R.string.ok) { _, _ ->
                val name = editText.value.trim()
                if (name.isNotEmpty()) {
                    callback(name)
                } else {
                    activity.toast(org.fossify.commons.R.string.unknown_error_occurred)
                }
            }
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(editText, this)
            }
    }
}
