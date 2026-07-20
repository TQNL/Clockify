package org.fossify.clock.adapters

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
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
import org.fossify.clock.models.SelectionState
import org.fossify.clock.models.SelectionType
import org.fossify.commons.adapters.MyRecyclerViewAdapter
import org.fossify.clock.models.FolderAlarmCounts
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
    private val folderClick: (AlarmFolder) -> Unit,
    private var folderAlarmCounts: Map<Int, FolderAlarmCounts>,
    /** Non-null when this adapter is inside a folder view — routes manual ordering to a per-folder config key. */
    private val folderId: Int? = null,
    /** Callback when selection state changes (custom Samsung-style selection). */
    private val onSelectionChanged: ((SelectionState) -> Unit)? = null,
    /** Callback when delete is triggered from selection mode. */
    private val onDeleteSelected: (() -> Unit)? = null,
    /** Called when item long-pressed to enter selection mode. */
    private val onItemLongPressed: ((Any, Int) -> Unit)? = null,
) : MyRecyclerViewAdapter(activity, recyclerView, itemClick), ItemTouchHelperContract {

    companion object {
        private const val TYPE_ALARM = 0
        private const val TYPE_FOLDER = 1

        /**
         * Selection key offset to avoid collision between folder IDs and alarm IDs.
         * Folders get key = -(folderId + OFFSET), alarms use their id directly.
         */
        const val FOLDER_KEY_OFFSET = 1000000
    }

    private var startReorderDragListener: StartReorderDragListener
    private var isCustomSelectionMode = false

    /** Track selected keys when the base class CAB clears them during dismiss. */
    private val savedSelectedKeys = HashSet<Int>()
    private val mainHandler = Handler(Looper.getMainLooper())

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

    // Suppress CAB — we handle selection ourselves.
    override fun getActionMenuId() = R.menu.cab_alarms

    override fun prepareActionMode(menu: Menu) {}

    override fun actionItemPressed(id: Int) {
        // CAB should never appear in custom selection mode
        if (!isCustomSelectionMode && selectedKeys.isNotEmpty() && id == R.id.cab_delete) {
            deleteItems()
        }
    }

    override fun getItemViewType(position: Int) = when (items.getOrNull(position)) {
        is AlarmFolder -> TYPE_FOLDER
        else -> TYPE_ALARM
    }

    override fun getSelectableItemCount() = items.size

    override fun getIsItemSelectable(position: Int) = true

    override fun getItemSelectionKey(position: Int): Int? {
        val item = items.getOrNull(position)
        return when (item) {
            is Alarm -> item.id
            is AlarmFolder -> -(item.id + FOLDER_KEY_OFFSET)
            else -> null
        }
    }

    override fun getItemKeyPosition(key: Int) = items.indexOfFirst { item ->
        when (item) {
            is Alarm -> item.id == key
            is AlarmFolder -> -(item.id + FOLDER_KEY_OFFSET) == key
            else -> false
        }
    }

    /**
     * The base class started a CAB via long-press. We immediately redirect to
     * our custom selection mode: save keys after [android.view.ActionMode.finish]
     * destroys the CAB, then restore them in [onActionModeDestroyed].
     *
     * Timing: [viewLongClicked] calls [startActionMode] first (which triggers
     * this callback), then adds the key to [selectedKeys] via [toggleItemSelection].
     * We post the dismissal so it runs AFTER the key is added.
     */
    override fun onActionModeCreated() {
        if (!isCustomSelectionMode) {
            isCustomSelectionMode = true
            mainHandler.post {
                savedSelectedKeys.clear()
                savedSelectedKeys.addAll(selectedKeys)
                finishActMode()
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onActionModeDestroyed() {
        if (isCustomSelectionMode && savedSelectedKeys.isNotEmpty()) {
            // The base class just cleared selectedKeys — restore them
            selectedKeys.clear()
            selectedKeys.addAll(savedSelectedKeys)
            savedSelectedKeys.clear()
            notifyDataSetChanged()
            notifySelectionChanged()
        } else if (!isCustomSelectionMode) {
            notifyDataSetChanged()
        }
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
                    setupFolderView(itemView, item, holder)
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

    /**
     * Toggle the custom selection mode (Samsung-style checkboxes) on/off.
     */
    fun setSelectionMode(isActive: Boolean) {
        isCustomSelectionMode = isActive
        if (!isActive) {
            selectedKeys.clear()
        }
        notifyDataSetChanged()
    }

    fun isInSelectionMode(): Boolean = isCustomSelectionMode

    /**
     * Toggle selection for an item by its selection key.
     */
    fun toggleItemSelection(key: Int) {
        if (selectedKeys.contains(key)) {
            selectedKeys.remove(key)
        } else {
            selectedKeys.add(key)
        }
        notifyDataSetChanged()
        notifySelectionChanged()
    }

    /**
     * Start selection mode with a single item selected.
     */
    fun startSelectionWithItem(key: Int) {
        isCustomSelectionMode = true
        selectedKeys.clear()
        selectedKeys.add(key)
        notifyDataSetChanged()
        notifySelectionChanged()
    }

    /**
     * Select all currently visible, selectable items.
     */
    fun selectAllVisible() {
        for (i in items.indices) {
            val key = getItemSelectionKey(i) ?: continue
            selectedKeys.add(key)
        }
        notifyDataSetChanged()
        notifySelectionChanged()
    }

    /**
     * Get the selection key for an item at a given position.
     */
    fun getSelectionKeyForPosition(position: Int): Int? = getItemSelectionKey(position)

    /**
     * Get all currently selected items with their types.
     */
    fun getSelectedItemsWithTypes(): List<Pair<SelectionType, Any>> {
        val result = mutableListOf<Pair<SelectionType, Any>>()
        for (item in items) {
            when (item) {
                is Alarm -> {
                    if (selectedKeys.contains(item.id)) {
                        result.add(SelectionType.ALARM to item)
                    }
                }
                is AlarmFolder -> {
                    val key = -(item.id + FOLDER_KEY_OFFSET)
                    if (selectedKeys.contains(key)) {
                        result.add(SelectionType.FOLDER to item)
                    }
                }
            }
        }
        return result
    }

    fun getSelectedAlarmIds(): List<Int> {
        return items.filterIsInstance<Alarm>()
            .filter { selectedKeys.contains(it.id) }
            .map { it.id }
    }

    fun getSelectedFolderIds(): List<Int> {
        return items.filterIsInstance<AlarmFolder>()
            .filter { selectedKeys.contains(-(it.id + FOLDER_KEY_OFFSET)) }
            .map { it.id }
    }

    fun hasOffAlarmsSelected(): Boolean {
        return items.filterIsInstance<Alarm>()
            .any { selectedKeys.contains(it.id) && !it.isEnabled }
    }

    fun hasOnAlarmsSelected(): Boolean {
        return items.filterIsInstance<Alarm>()
            .any { selectedKeys.contains(it.id) && it.isEnabled }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun updateItems(
        newItems: ArrayList<Any>,
        newFolderAlarmCounts: Map<Int, FolderAlarmCounts> = folderAlarmCounts,
    ) {
        items = newItems
        folderAlarmCounts = newFolderAlarmCounts
        notifyDataSetChanged()
        if (!isCustomSelectionMode) {
            finishActMode()
        }
    }

    private fun deleteItems() {
        val alarmsToRemove = ArrayList<Alarm>()
        val positions = getSelectedItemPositions()
        getSelectedItems().forEach {
            alarmsToRemove.add(it)
        }

        items = ArrayList(items.filter { it !in alarmsToRemove })
        removeSelectedItems(positions)
        activity.dbHelper.deleteAlarms(alarmsToRemove)
        EventBus.getDefault().post(AlarmEvent.Refresh)
    }

    private fun getSelectedItems(): ArrayList<Alarm> {
        return items.filterIsInstance<Alarm>().filter {
            selectedKeys.contains(it.id)
        } as ArrayList<Alarm>
    }

    fun notifySelectionChanged() {
        val selectedIds = selectedKeys.toSet()
        val selectedTypes = items.filter { item ->
            val key = when (item) {
                is Alarm -> item.id
                is AlarmFolder -> -(item.id + FOLDER_KEY_OFFSET)
                else -> return@filter false
            }
            selectedKeys.contains(key)
        }.map { item ->
            when (item) {
                is Alarm -> SelectionType.ALARM
                is AlarmFolder -> SelectionType.FOLDER
                else -> null
            }
        }.filterNotNull().toSet()

        val state = if (selectedIds.isEmpty()) {
            SelectionState.Idle
        } else {
            SelectionState.Active(selectedIds, selectedTypes)
        }
        onSelectionChanged?.invoke(state)
    }

    private fun setupFolderView(view: View, folder: AlarmFolder, holder: ViewHolder) {
        val key = -(folder.id + FOLDER_KEY_OFFSET)
        val isSelected = selectedKeys.contains(key)
        ItemFolderBinding.bind(view).apply {
            folderName.text = folder.name
            val counts = folderAlarmCounts[folder.id]
            folderCount.text = if (counts != null) {
                "${counts.enabled} on · ${counts.disabled} off"
            } else {
                "0"
            }

            // Selection checkbox
            selectionCheckbox.isChecked = isSelected
            selectionCheckbox.beVisibleIf(isCustomSelectionMode)

            folderHolder.isSelected = isSelected
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupView(view: View, alarm: Alarm, holder: ViewHolder) {
        val isSelected = selectedKeys.contains(alarm.id)
        ItemAlarmBinding.bind(view).apply {
            alarmHolder.isSelected = isSelected

            // Selection checkbox
            selectionCheckbox.isChecked = isSelected
            selectionCheckbox.beVisibleIf(isCustomSelectionMode)

            alarmDragHandle.beVisibleIf(selectedKeys.isNotEmpty() && !isCustomSelectionMode)
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
            alarmDays.setTextColor(textColor)

            alarmLabel.text = alarm.label
            alarmLabel.setTextColor(textColor)
            alarmLabel.beVisibleIf(alarm.label.isNotEmpty())

            alarmSwitch.isChecked = alarm.isEnabled
            alarmSwitch.setColors(textColor, properPrimaryColor, backgroundColor)
            alarmSwitch.isEnabled = !isCustomSelectionMode
            alarmSwitch.alpha = if (isCustomSelectionMode) 0.4f else 1.0f
            alarmSwitch.setOnClickListener {
                if (!isCustomSelectionMode) {
                    toggleAlarm(binding = this, alarm = alarm)
                }
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
