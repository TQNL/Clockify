package org.fossify.clock.dialogs

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import org.fossify.clock.R
import org.fossify.clock.activities.SimpleActivity
import org.fossify.clock.extensions.dbHelper
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.toast

class SelectFolderDialog(
    val activity: SimpleActivity,
    private val onFolderSelected: (folderId: Int?) -> Unit,
    private val onCreateNewFolder: (() -> Unit)? = null,
) {
    init {
        val folders = activity.dbHelper.getFolders()
        val textColor = activity.getProperTextColor()
        val padding = activity.resources.getDimensionPixelSize(org.fossify.commons.R.dimen.activity_margin)

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, 0)
        }

        val radioGroup = RadioGroup(activity)
        var selectedFolderId: Int? = null

        // "None" option
        val noneButton = RadioButton(activity).apply {
            text = activity.getString(R.string.no_folder)
            setTextColor(textColor)
            id = View.generateViewId()
            setPadding(0, padding / 2, 0, padding / 2)
            setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) selectedFolderId = null
            }
        }
        radioGroup.addView(noneButton)

        folders.forEach { folder ->
            val button = RadioButton(activity).apply {
                text = folder.name
                setTextColor(textColor)
                id = View.generateViewId()
                tag = folder.id
                setPadding(0, padding / 2, 0, padding / 2)
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) selectedFolderId = folder.id
                }
            }
            radioGroup.addView(button)
        }

        container.addView(radioGroup)

        // "Create new folder" button — dismisses this dialog and delegates to caller
        if (onCreateNewFolder != null) {
            val divider = View(activity).apply {
                layoutParams = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    1
                ).apply { topMargin = padding; bottomMargin = padding }
                setBackgroundColor(textColor)
                alpha = 0.15f
            }
            container.addView(divider)

            val createButton = RadioButton(activity).apply {
                text = activity.getString(R.string.create_folder)
                setTextColor(textColor)
                setPadding(0, padding / 2, 0, padding / 2)
                setOnClickListener {
                    onCreateNewFolder()
                }
            }
            container.addView(createButton)
        }

        activity.getAlertDialogBuilder()
            .setTitle(R.string.move_to_folder)
            .setView(container)
            .setPositiveButton(org.fossify.commons.R.string.ok) { _, _ ->
                onFolderSelected(selectedFolderId)
            }
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(container, this)
            }
    }
}
