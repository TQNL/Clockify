package org.fossify.clock.dialogs

import android.widget.RadioButton
import android.widget.RadioGroup
import org.fossify.clock.R
import org.fossify.clock.activities.SimpleActivity
import org.fossify.clock.extensions.dbHelper
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.setupDialogStuff

class MoveDestinationSheet(
    private val activity: SimpleActivity,
    private val onDestinationSelected: (targetFolderId: Int?) -> Unit,
) {
    init {
        val folders = activity.dbHelper.getFolders()
        val uncategorizedCount = activity.dbHelper.getUncategorizedAlarmCount()
        val textColor = activity.getProperTextColor()

        val radioGroup = RadioGroup(activity)

        // Add "Alarms (Uncategorized)" option — id 0 means null target
        addRadioItem(
            radioGroup = radioGroup,
            itemId =0,
            title = activity.getString(R.string.move_destination_uncategorized),
            isChecked = true,
            textColor = textColor,
        )

        // Add each folder
        folders.forEach { folder ->
            addRadioItem(
                radioGroup = radioGroup,
                itemId =folder.id,
                title = folder.name,
                isChecked = false,
                textColor = textColor,
            )
        }

        activity.getAlertDialogBuilder()
            .setTitle(R.string.move_destination_title)
            .setView(radioGroup)
            .setPositiveButton(R.string.move_button) { _, _ ->
                val checkedId = radioGroup.checkedRadioButtonId
                val targetFolderId: Int? = if (checkedId <= 0) null else checkedId
                onDestinationSelected(targetFolderId)
            }
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(radioGroup, this)
            }
    }

    private fun addRadioItem(
        radioGroup: RadioGroup,
        itemId: Int,
        title: String,
        isChecked: Boolean,
        textColor: Int,
    ) {
        val radioButton = RadioButton(radioGroup.context).apply {
            setId(itemId)
            text = title
            setChecked(isChecked)
            setTextColor(textColor)
        }
        radioGroup.addView(radioButton)
    }
}
