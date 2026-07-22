package org.fossify.clock.adapters

import android.annotation.SuppressLint
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import org.fossify.clock.R
import org.fossify.clock.activities.SimpleActivity
import org.fossify.clock.databinding.ItemAlarmBinding
import org.fossify.clock.databinding.ItemFolderBinding
import org.fossify.clock.extensions.config
import org.fossify.clock.extensions.dbHelper
import org.fossify.clock.extensions.getFormattedTime
import org.fossify.clock.helpers.updateNonRecurringAlarmDay
import org.fossify.clock.interfaces.ToggleAlarmInterface
import org.fossify.clock.models.Alarm
import org.fossify.clock.models.AlarmEvent
import org.fossify.clock.models.AlarmFolder
import org.fossify.clock.models.FolderAlarmCounts
import org.fossify.clock.selection.SelectionAction
import org.fossify.clock.selection.SelectionActionHandler
import org.fossify.clock.selection.SelectionModeListener
import org.fossify.clock.selection.SelectionSnapshot
import org.fossify.clock.selection.actionLabel
import org.fossify.clock.selection.computeActions
import org.fossify.commons.adapters.MyRecyclerViewAdapter
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.move
import org.fossify.commons.helpers.EVERY_DAY_BIT
import org.fossify.commons.helpers.SORT_BY_CUSTOM
import org.fossify.commons.interfaces.ItemMoveCallback
import org.fossify.commons.interfaces.ItemTouchHelperContract
import org.fossify.commons.interfaces.StartReorderDragListener
import org.fossify.commons.views.MyRecyclerView
import org.greenrobot.eventbus.EventBus

class AlarmsAdapter(
    activity: SimpleActivity,
    private var items: ArrayList<Any>,
    private val toggleAlarmInterface: ToggleAlarmInterface,
    recyclerView: MyRecyclerView,
    itemClick: (Any) -> Unit,
    private var folderAlarmCounts: Map<Int, FolderAlarmCounts>,
    /** Non-null when this adapter is inside a folder view — routes manual ordering to a per-folder config key. */
    private val folderId: Int? = null,
    /** Receives Samsung-style selection actions (Turn on, Move, Ungroup, …). */
    private val selectionActionHandler: SelectionActionHandler? = null,
) : MyRecyclerViewAdapter(activity, recyclerView, itemClick), ItemTouchHelperContract {

    companion object {
        private const val TYPE_ALARM = 0
        private const val TYPE_FOLDER = 1

        /**
         * Selection keys for folders are offset by this much so they never
         * collide with alarm ids (which come from a separate table).
         * [keyToFolderId]/[keyToAlarmId] map back.
         */
        private const val FOLDER_KEY_OFFSET = 1_000_000
    }

    private var startReorderDragListener: StartReorderDragListener

    /** Fragment-provided listener for Samsung-style screen transformation. */
    var selectionModeListener: SelectionModeListener? = null

    /** True while an ActionMode (long-press selection) is active. */
    private var isInActionMode = false

    init {
        setupDragListener(true)
        val touchHelper = ItemTouchHelper(ItemMoveCallback(this))
        touchHelper.attachToRecyclerView(recyclerView)

        startReorderDragListener = object : StartReorderDragListener {
            override fun requestDrag(viewHolder: RecyclerView.ViewHolder) {
                touchHelper.startDrag(viewHolder)
            }
        }
    }

    override fun getActionMenuId() = R.menu.cab_alarms

    /**
     * Called by the commons CAB on every selection change. Delegates the
     * action-set decision to the pure [computeActions] function and just
     * shows/hides + relabels the menu items accordingly.
     */
    override fun prepareActionMode(menu: Menu) {
        val snapshot = currentSelectionSnapshot()
        val actions = computeActions(snapshot)
        menu.findItem(R.id.cab_turn_on)?.let {
            it.isVisible = SelectionAction.TURN_ON in actions
            it.title = actionLabel(SelectionAction.TURN_ON, snapshot)
        }
        menu.findItem(R.id.cab_turn_off)?.let {
            it.isVisible = SelectionAction.TURN_OFF in actions
            it.title = actionLabel(SelectionAction.TURN_OFF, snapshot)
        }
        menu.findItem(R.id.cab_group)?.let {
            it.isVisible = SelectionAction.GROUP in actions
        }
        menu.findItem(R.id.cab_move)?.let {
            it.isVisible = SelectionAction.MOVE in actions
        }
        menu.findItem(R.id.cab_ungroup)?.let {
            it.isVisible = SelectionAction.UNGROUP in actions
        }
        menu.findItem(R.id.cab_delete)?.let {
            it.isVisible = SelectionAction.DELETE in actions
        }
        menu.findItem(R.id.cab_delete_all)?.let {
            it.isVisible = SelectionAction.DELETE_ALL in actions
            it.title = actionLabel(SelectionAction.DELETE_ALL, snapshot)
        }

        notifySelectionChanged()
    }

    override fun actionItemPressed(id: Int) {
        if (selectedKeys.isEmpty()) return
        val handler = selectionActionHandler ?: return
        val alarms = selectedAlarms()
        val folders = selectedFolders()
        when (id) {
            R.id.cab_turn_on -> handler.turnOn(alarms, folders)
            R.id.cab_turn_off -> handler.turnOff(alarms, folders)
            R.id.cab_group -> handler.group(alarms)
            R.id.cab_move -> handler.move(alarms)
            R.id.cab_ungroup -> handler.ungroup(folders)
            R.id.cab_delete -> {
                ConfirmationDialog(activity, activity.getString(R.string.delete_alarms_confirmation)) {
                    handler.delete(alarms)
                }
            }
            R.id.cab_delete_all -> {
                ConfirmationDialog(activity, activity.getString(R.string.delete_alarms_confirmation)) {
                    handler.deleteAll(alarms, folders)
                }
            }
        }
    }

    // --- Samsung-style selection support -----------------------------------

    private fun notifySelectionChanged() {
        val snapshot = currentSelectionSnapshot()
        selectionModeListener?.onSelectionModeChanged(snapshot)
    }

    /**
     * Execute a [SelectionAction] from the Samsung-style bottom action bar.
     * Delegates to the [SelectionActionHandler], then finishes the ActionMode.
     */
    fun executeAction(action: SelectionAction) {
        val handler = selectionActionHandler ?: return
        val alarms = selectedAlarms()
        val folders = selectedFolders()
        when (action) {
            SelectionAction.TURN_ON -> handler.turnOn(alarms, folders)
            SelectionAction.TURN_OFF -> handler.turnOff(alarms, folders)
            SelectionAction.GROUP -> handler.group(alarms)
            SelectionAction.MOVE -> handler.move(alarms)
            SelectionAction.UNGROUP -> handler.ungroup(folders)
            SelectionAction.DELETE -> {
                ConfirmationDialog(activity, activity.getString(R.string.delete_alarms_confirmation)) {
                    handler.delete(alarms)
                }
            }
            SelectionAction.DELETE_ALL -> {
                ConfirmationDialog(activity, activity.getString(R.string.delete_alarms_confirmation)) {
                    handler.deleteAll(alarms, folders)
                }
            }
        }
        finishActMode()
    }

    /** Public convenience to exit selection mode from outside. */
    fun exitSelectionMode() {
        finishActMode()
    }

    override fun getItemSelectionKey(position: Int): Int? {
        val item = items.getOrNull(position)
        val key = when (item) {
            is Alarm -> item.id
            is AlarmFolder -> item.id + FOLDER_KEY_OFFSET
            else -> null
        }
        return key
    }

    override fun getIsItemSelectable(position: Int): Boolean {
        val item = items.getOrNull(position)
        val sel = item is Alarm || item is AlarmFolder
        return sel
    }

    /** Select every selectable item in the adapter. */
    fun selectAllItems() {
        for (i in 0 until itemCount) {
            val key = getItemSelectionKey(i)
            if (key != null) {
                selectedKeys.add(key)
            }
        }
        notifyDataSetChanged()
        notifySelectionChanged()
    }

    /** Clear all selections without leaving ActionMode. */
    fun deselectAll() {
        selectedKeys.clear()
        notifyDataSetChanged()
        notifySelectionChanged()
    }

    /** Expose the protected [selectedKeys] set for fragment access. */
    fun getSelectedKeysSet(): LinkedHashSet<Int> = selectedKeys

    override fun getItemViewType(position: Int) = when (items.getOrNull(position)) {
        is AlarmFolder -> TYPE_FOLDER
        else -> TYPE_ALARM
    }

    /**
     * Both alarms and folders participate in selection mode (Samsung lets you
     * long-press either). Folder keys are offset by [FOLDER_KEY_OFFSET].
     */
    override fun getSelectableItemCount() =
        items.count { it is Alarm || it is AlarmFolder }

    override fun getItemKeyPosition(key: Int): Int {
        if (key >= FOLDER_KEY_OFFSET) {
            val folderId = key - FOLDER_KEY_OFFSET
            return items.indexOfFirst { (it as? AlarmFolder)?.id == folderId }
        }
        return items.indexOfFirst { (it as? Alarm)?.id == key }
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onActionModeCreated() {
        isInActionMode = true
        notifyDataSetChanged()
        selectionModeListener?.onSelectionModeStarted()
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onActionModeDestroyed() {
        isInActionMode = false
        notifyDataSetChanged()
        selectionModeListener?.onSelectionModeEnded()
    }

    override fun onRowClear(myViewHolder: ViewHolder?) {}

    override fun onRowSelected(myViewHolder: ViewHolder?) {}

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return when (viewType) {
            TYPE_FOLDER -> createViewHolder(
                ItemFolderBinding.inflate(layoutInflater, parent, false).root
            )
            else -> createViewHolder(
                ItemAlarmBinding.inflate(layoutInflater, parent, false).root
            )
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        when (item) {
            is AlarmFolder -> {
                holder.bindView(
                    any = item,
                    allowSingleClick = true,
                    allowLongClick = true
                ) { itemView, _ ->
                    setupFolderView(itemView, item)
                }
                bindViewHolder(holder)
            }
            is Alarm -> {
                holder.bindView(
                    any = item,
                    allowSingleClick = true,
                    allowLongClick = true
                ) { itemView, _ ->
                    setupView(itemView, item, holder)
                }
                bindViewHolder(holder)
            }
        }
    }

    override fun getItemCount() = items.size

    @SuppressLint("NotifyDataSetChanged")
    fun updateItems(
        newItems: ArrayList<Any>,
        newFolderAlarmCounts: Map<Int, FolderAlarmCounts> = folderAlarmCounts,
    ) {
        items = newItems
        folderAlarmCounts = newFolderAlarmCounts
        notifyDataSetChanged()
        finishActMode()
    }

    // --- Selection helpers ------------------------------------------------

    /** Snapshot of the current selection for [computeActions]. */
    private fun currentSelectionSnapshot(): SelectionSnapshot {
        return SelectionSnapshot(
            alarms = selectedAlarms(),
            folders = selectedFolders(),
            folderCounts = folderAlarmCounts,
            inFolderView = folderId != null,
        )
    }

    private fun selectedAlarms(): List<Alarm> =
        items.filterIsInstance<Alarm>().filter { selectedKeys.contains(it.id) }

    private fun selectedFolders(): List<AlarmFolder> =
        items.filterIsInstance<AlarmFolder>()
            .filter { selectedKeys.contains(it.id + FOLDER_KEY_OFFSET) }

    // --- View binding -----------------------------------------------------

    private fun setupFolderView(view: View, folder: AlarmFolder) {
        val isSelected = selectedKeys.contains(folder.id + FOLDER_KEY_OFFSET)
        ItemFolderBinding.bind(view).apply {
            folderHolder.isSelected = isSelected

            // Selection mode: show check indicator, hide icon and chevron
            val showSelectionUI = isInActionMode
            folderCheckIndicator.beVisibleIf(showSelectionUI)
            folderIcon.beVisibleIf(!showSelectionUI)
            folderChevron.beVisibleIf(!showSelectionUI)
            if (showSelectionUI) {
                folderCheckIndicator.isSelected = isSelected
            }

            folderName.text = folder.name
            val counts = folderAlarmCounts[folder.id]
            folderCount.text = if (counts != null) {
                "${counts.enabled} on · ${counts.disabled} off"
            } else {
                "0"
            }
            // No custom click listener here: folder_holder IS the itemView, so
            // the base ViewHolder's listener handles both normal clicks
            // (forwards to itemClick → openFolderFragment) and selection-mode
            // taps (toggles selection). A child listener would shadow it.
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupView(view: View, alarm: Alarm, holder: ViewHolder) {
        val isSelected = selectedKeys.contains(alarm.id)
        ItemAlarmBinding.bind(view).apply {
            alarmHolder.isSelected = isSelected

            if (isInActionMode) {
                // Selection mode: simplified view — time, date in accent, check indicator only
                alarmCheckIndicator.beVisibleIf(true)
                alarmCheckIndicator.isSelected = isSelected
                alarmSwitch.visibility = View.GONE
                alarmLabel.visibility = View.GONE
                alarmDragHandle.visibility = View.GONE

                // Show day/date line in accent color for non-recurring alarms ("Today"/"Tomorrow"),
                // hide day-of-week chips for recurring alarms.
                if (alarm.isRecurring()) {
                    alarmDays.visibility = View.GONE
                } else {
                    alarmDays.visibility = View.VISIBLE
                    alarmDays.setTextColor(properPrimaryColor)
                }
            } else {
                // Normal mode
                alarmCheckIndicator.visibility = View.GONE
                alarmSwitch.visibility = View.VISIBLE
                alarmDays.visibility = View.VISIBLE
                alarmLabel.beVisibleIf(alarm.label.isNotEmpty())
                alarmDragHandle.beVisibleIf(selectedKeys.isNotEmpty())
                alarmDays.setTextColor(textColor)
            }

            alarmDragHandle.applyColorFilter(textColor)
            alarmDragHandle.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    startReorderDragListener.requestDrag(holder)
                }
                false
            }
            alarmTime.text = activity.getFormattedTime(
                passedSeconds = alarm.timeInMinutes * 60,
                showSeconds = false,
                makeAmPmSmaller = true
            )
            alarmTime.setTextColor(textColor)

            alarmDays.text = getAlarmSelectedDaysString(alarm)

            alarmLabel.text = alarm.label
            alarmLabel.setTextColor(textColor)

            alarmSwitch.isChecked = alarm.isEnabled
            alarmSwitch.setColors(textColor, properPrimaryColor, backgroundColor)
            alarmSwitch.setOnClickListener {
                toggleAlarm(binding = this, alarm = alarm)
            }
        }
    }

    private fun toggleAlarm(binding: ItemAlarmBinding, alarm: Alarm) {
        when {
            alarm.isRecurring() -> {
                if (activity.config.wasAlarmWarningShown) {
                    toggleAlarmInterface.alarmToggled(alarm.id, binding.alarmSwitch.isChecked)
                } else {
                    ConfirmationDialog(
                        activity = activity,
                        messageId = org.fossify.commons.R.string.alarm_warning,
                        positive = org.fossify.commons.R.string.ok,
                        negative = 0
                    ) {
                        activity.config.wasAlarmWarningShown = true
                        toggleAlarmInterface.alarmToggled(alarm.id, binding.alarmSwitch.isChecked)
                    }
                }
            }

            else -> {
                updateNonRecurringAlarmDay(alarm)
                activity.dbHelper.updateAlarm(alarm)
                binding.alarmDays.text = getAlarmSelectedDaysString(
                    alarm = alarm, isEnabled = binding.alarmSwitch.isChecked
                )
                toggleAlarmInterface.alarmToggled(alarm.id, binding.alarmSwitch.isChecked)
            }
        }
    }

    private fun getAlarmSelectedDaysString(
        alarm: Alarm, isEnabled: Boolean = alarm.isEnabled,
    ): String {
        if (alarm.isRecurring()) {
            return if (alarm.days == EVERY_DAY_BIT) {
                activity.getString(org.fossify.commons.R.string.every_day)
            } else {
                getSelectedDaysStringSafe(alarm.days)
            }
        }

        return when {
            !isEnabled -> resources.getString(R.string.not_scheduled)
            alarm.isToday() -> resources.getString(org.fossify.commons.R.string.today)
            else -> resources.getString(org.fossify.commons.R.string.tomorrow)
        }
    }

    /**
     * Safe replacement for commons getSelectedDaysString that avoids
     * ClassCastException on Arrays$ArrayList -> ArrayList cast bug.
     */
    private fun getSelectedDaysStringSafe(days: Int): String {
        val dayBits = listOf(
            org.fossify.commons.helpers.MONDAY_BIT,
            org.fossify.commons.helpers.TUESDAY_BIT,
            org.fossify.commons.helpers.WEDNESDAY_BIT,
            org.fossify.commons.helpers.THURSDAY_BIT,
            org.fossify.commons.helpers.FRIDAY_BIT,
            org.fossify.commons.helpers.SATURDAY_BIT,
            org.fossify.commons.helpers.SUNDAY_BIT,
        )
        val dayLetters = resources.getStringArray(org.fossify.commons.R.array.week_day_letters)
        val selected = dayBits.mapIndexedNotNull { index, bit ->
            if (days and bit != 0) dayLetters.getOrElse(index) { "" } else null
        }
        return selected.joinToString(", ")
    }

    override fun onRowMoved(fromPosition: Int, toPosition: Int) {
        val mutableItems = items.toMutableList()
        mutableItems.move(fromPosition, toPosition)
        items = ArrayList(mutableItems)
        notifyItemMoved(fromPosition, toPosition)
        saveAlarmsCustomOrder(items)
        if (activity.config.alarmSort != SORT_BY_CUSTOM) {
            activity.config.alarmSort = SORT_BY_CUSTOM
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun saveAlarmsCustomOrder(allItems: List<Any>) {
        val alarms = allItems.filterIsInstance<Alarm>() as ArrayList<Alarm>
        val alarmsCustomSortingIds = alarms.map { it.id }
        val orderString = alarmsCustomSortingIds.joinToString { it.toString() }
        if (folderId != null) {
            activity.config.setFolderAlarmsCustomSorting(folderId, orderString)
        } else {
            activity.config.alarmsCustomSorting = orderString
        }
    }
}
