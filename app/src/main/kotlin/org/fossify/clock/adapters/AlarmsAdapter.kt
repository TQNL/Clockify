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
    private var items: List<Any>,
    private val toggleAlarmInterface: ToggleAlarmInterface,
    recyclerView: MyRecyclerView,
    itemClick: (Any) -> Unit,
    private val folderClick: ((AlarmFolder) -> Unit)? = null,
    private val isFolderContext: Boolean = false,
) : MyRecyclerViewAdapter(activity, recyclerView, itemClick), ItemTouchHelperContract {

    companion object {
        const val TYPE_FOLDER = 0
        const val TYPE_ALARM = 1
    }

    private var startReorderDragListener: StartReorderDragListener

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

    override fun prepareActionMode(menu: Menu) {
        if (isFolderContext) {
            // In folder context: show "Remove from folder" instead of "Move to folder"
            menu.findItem(R.id.cab_move_to_folder)?.let {
                it.setTitle(R.string.remove_from_folder)
                it.isVisible = true
            }
        } else {
            // On home screen: show "Move to folder" only for uncategorized alarms
            val hasUncategorized = selectedKeys.any { key ->
                (items.firstOrNull { it is Alarm && it.id == key } as? Alarm)?.folderId == null
            }
            menu.findItem(R.id.cab_move_to_folder)?.let {
                it.setTitle(R.string.move_to_folder)
                it.isVisible = hasUncategorized
            }
        }
    }

    override fun actionItemPressed(id: Int) {
        if (selectedKeys.isEmpty()) return

        when (id) {
            R.id.cab_delete -> deleteItems()
            R.id.cab_move_to_folder -> {
                // Delegate to external handler via callback pattern
                onMoveToFolderRequested?.invoke(getSelectedAlarms())
            }
        }
    }

    override fun getSelectableItemCount() = items.count { it is Alarm }

    override fun getIsItemSelectable(position: Int) = items.getOrNull(position) is Alarm

    override fun getItemSelectionKey(position: Int): Int? {
        val item = items.getOrNull(position)
        return if (item is Alarm) item.id else null
    }

    override fun getItemKeyPosition(key: Int) = items.indexOfFirst {
        it is Alarm && it.id == key
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is AlarmFolder -> TYPE_FOLDER
            else -> TYPE_ALARM
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onActionModeCreated() {
        notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onActionModeDestroyed() {
        notifyDataSetChanged()
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
                    allowLongClick = false
                ) { itemView, _ ->
                    setupFolderView(itemView, item)
                }
            }
            is Alarm -> {
                holder.bindView(
                    any = item,
                    allowSingleClick = true,
                    allowLongClick = true
                ) { itemView, _ ->
                    setupView(itemView, item, holder)
                }
            }
        }
        bindViewHolder(holder)
    }

    override fun getItemCount() = items.size

    @SuppressLint("NotifyDataSetChanged")
    fun updateItems(newItems: List<Any>) {
        items = newItems
        notifyDataSetChanged()
        finishActMode()
    }

    private fun deleteItems() {
        val alarmsToRemove = ArrayList<Alarm>()
        val positions = getSelectedItemPositions()
        getSelectedItems().forEach {
            alarmsToRemove.add(it)
        }

        items = items.filter { it !in alarmsToRemove }
        removeSelectedItems(positions)
        activity.dbHelper.deleteAlarms(alarmsToRemove)
        EventBus.getDefault().post(AlarmEvent.Refresh)
    }

    private fun getSelectedItems(): ArrayList<Alarm> {
        return items.filterIsInstance<Alarm>().filter {
            selectedKeys.contains(it.id)
        } as ArrayList<Alarm>
    }

    private fun getSelectedAlarms(): List<Alarm> {
        return items.filterIsInstance<Alarm>().filter {
            selectedKeys.contains(it.id)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupView(view: View, alarm: Alarm, holder: ViewHolder) {
        val isSelected = selectedKeys.contains(alarm.id)
        ItemAlarmBinding.bind(view).apply {
            alarmHolder.isSelected = isSelected
            alarmDragHandle.beVisibleIf(selectedKeys.isNotEmpty())
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
            alarmSwitch.setOnClickListener {
                toggleAlarm(binding = this, alarm = alarm)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupFolderView(view: View, folder: AlarmFolder) {
        ItemFolderBinding.bind(view).apply {
            folderName.text = folder.name
            folderName.setTextColor(textColor)
            folderIcon.applyColorFilter(properPrimaryColor)
            folderChevron.applyColorFilter(textColor)

            val alarmCount = folderAlarmCounts[folder.id] ?: 0
            val countText = when {
                alarmCount == 0 -> activity.getString(R.string.no_alarms)
                alarmCount == 1 -> activity.getString(R.string.n_alarm, alarmCount)
                else -> activity.getString(R.string.n_alarms, alarmCount)
            }
            folderAlarmCount.text = countText
            folderAlarmCount.setTextColor(textColor)

            folderHolder.setOnClickListener {
                folderClick?.invoke(folder)
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
        items = mutableItems
        notifyItemMoved(fromPosition, toPosition)
        saveAlarmsCustomOrder(mutableItems.filterIsInstance<Alarm>() as ArrayList<Alarm>)
        if (activity.config.alarmSort != SORT_BY_CUSTOM) {
            activity.config.alarmSort = SORT_BY_CUSTOM
        }
    }

    private fun saveAlarmsCustomOrder(alarms: ArrayList<Alarm>) {
        val alarmsCustomSortingIds = alarms.map { it.id }
        activity.config.alarmsCustomSorting = alarmsCustomSortingIds.joinToString { it.toString() }
    }

    // External handlers set by the fragment
    var onMoveToFolderRequested: ((List<Alarm>) -> Unit)? = null
    var folderAlarmCounts: Map<Int, Int> = emptyMap()
}
