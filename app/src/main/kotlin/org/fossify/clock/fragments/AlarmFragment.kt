package org.fossify.clock.fragments

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import org.fossify.clock.activities.MainActivity
import org.fossify.clock.activities.SimpleActivity
import org.fossify.clock.adapters.AlarmsAdapter
import org.fossify.clock.R
import org.fossify.clock.databinding.FragmentAlarmBinding
import org.fossify.clock.dialogs.ChangeAlarmSortDialog
import org.fossify.clock.dialogs.CreateFolderDialog
import org.fossify.clock.dialogs.EditAlarmDialog
import org.fossify.clock.dialogs.SelectFolderDialog
import org.fossify.clock.extensions.alarmController
import org.fossify.clock.extensions.cancelAlarmClock
import org.fossify.clock.extensions.config
import org.fossify.clock.extensions.createNewAlarm
import org.fossify.clock.extensions.dbHelper
import org.fossify.clock.extensions.firstDayOrder
import org.fossify.clock.extensions.getAlarmHeroInfo
import org.fossify.clock.extensions.handleFullScreenNotificationsPermission
import org.fossify.clock.extensions.updateWidgets
import org.fossify.clock.helpers.DEFAULT_ALARM_MINUTES
import org.fossify.clock.helpers.SORT_BY_ALARM_TIME
import org.fossify.clock.helpers.SORT_BY_DATE_AND_TIME
import org.fossify.clock.helpers.UPCOMING_ALARM_NOTIFICATION_BASE
import org.fossify.clock.helpers.getTomorrowBit
import org.fossify.clock.interfaces.ToggleAlarmInterface
import org.fossify.clock.models.Alarm
import org.fossify.clock.models.AlarmEvent
import org.fossify.clock.models.AlarmFolder
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.notificationManager
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.helpers.SORT_BY_CUSTOM
import org.fossify.commons.helpers.SORT_BY_DATE_CREATED
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.AlarmSound
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

class AlarmFragment : Fragment(), ToggleAlarmInterface {
    companion object {
        private const val HERO_REFRESH_INTERVAL = 60_000L // refresh every 60 seconds
    }

    private var items = ArrayList<Any>()
    private var currentEditAlarmDialog: EditAlarmDialog? = null

    private lateinit var binding: FragmentAlarmBinding
    private val heroRefreshHandler = Handler(Looper.getMainLooper())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentAlarmBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EventBus.getDefault().register(this)
    }

    override fun onDestroy() {
        EventBus.getDefault().unregister(this)
        heroRefreshHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        setupViews()
        startHeroRefresh()
    }

    override fun onPause() {
        super.onPause()
        heroRefreshHandler.removeCallbacksAndMessages(null)
    }

    fun showSortingDialog() {
        ChangeAlarmSortDialog(activity as SimpleActivity) {
            setupAlarms()
        }
    }

    private fun setupViews() {
        binding.apply {
            requireContext().updateTextColors(alarmFragment)
            alarmFab.setOnClickListener {
                val newAlarm = root.context.createNewAlarm(DEFAULT_ALARM_MINUTES, 0)
                newAlarm.isEnabled = true
                newAlarm.days = getTomorrowBit()
                openEditAlarm(newAlarm)
            }
        }

        setupAlarms()
        updateAlarmHero()
    }

    // --- Samsung-style Alarm Hero ---

    private fun startHeroRefresh() {
        heroRefreshHandler.removeCallbacksAndMessages(null)
        heroRefreshHandler.postDelayed(object : Runnable {
            override fun run() {
                updateAlarmHero()
                heroRefreshHandler.postDelayed(this, HERO_REFRESH_INTERVAL)
            }
        }, HERO_REFRESH_INTERVAL)
    }

    private fun updateAlarmHero() {
        val safeContext = context ?: return
        safeContext.getAlarmHeroInfo { heroInfo ->
            val hasAlarm = heroInfo.countdownText.isNotEmpty()

            binding.apply {
                alarmHero.beVisibleIf(true)
                if (hasAlarm) {
                    alarmHeroCountdown.text = heroInfo.countdownText
                    alarmHeroDatetime.text = heroInfo.dateTimeText
                    alarmHeroDatetime.beVisibleIf(true)
                } else {
                    alarmHeroCountdown.text = getString(R.string.no_alarms_found)
                    alarmHeroDatetime.beVisibleIf(false)
                }
                alarmHeroCountdown.setTextColor(safeContext.getProperTextColor())
                alarmHeroDatetime.setTextColor(safeContext.getProperTextColor())
            }
        }
    }

    // --- Alarm list (folders + uncategorized alarms) ---

    private fun getSortedAlarms(callback: (items: ArrayList<Any>) -> Unit) {
        val safeContext = context ?: return
        ensureBackgroundThread {
            val allAlarms = context?.dbHelper?.getAlarms() ?: return@ensureBackgroundThread
            val folders = context?.dbHelper?.getFolders() ?: emptyList()
            val uncategorizedAlarms = allAlarms.filter { it.folderId == null }

            // Sort folders by creation time (oldest first)
            val sortedFolders = folders.sortedBy { it.createdAt }

            // Sort uncategorized alarms by selected sort mode
            val sortedUncategorized = when (safeContext.config.alarmSort) {
                SORT_BY_ALARM_TIME -> uncategorizedAlarms.sortedBy { it.timeInMinutes }
                SORT_BY_DATE_CREATED -> uncategorizedAlarms.sortedBy { it.id }
                SORT_BY_DATE_AND_TIME -> uncategorizedAlarms.sortedWith(compareBy<Alarm> {
                    safeContext.firstDayOrder(it.days)
                }.thenBy {
                    it.timeInMinutes
                })

                SORT_BY_CUSTOM -> {
                    val customAlarmsSortOrderString = safeContext.config.alarmsCustomSorting
                    if (customAlarmsSortOrderString.isNullOrEmpty()) {
                        uncategorizedAlarms.sortedBy { it.id }
                    } else {
                        val customAlarmsSortOrder: List<Int> =
                            customAlarmsSortOrderString.split(", ").map { it.toInt() }
                        val alarmsIdValueMap = uncategorizedAlarms.associateBy { it.id }
                        val sorted = customAlarmsSortOrder.mapNotNull { alarmsIdValueMap[it] }
                        sorted + uncategorizedAlarms.filter { it !in sorted }
                    }
                }

                else -> uncategorizedAlarms
            }

            // Merge: folders first, then uncategorized alarms
            val result = ArrayList<Any>(sortedFolders.size + sortedUncategorized.size)
            result.addAll(sortedFolders)
            result.addAll(sortedUncategorized)

            activity?.runOnUiThread {
                callback(result)
            }
        }
    }

    private fun setupAlarms() {
        getSortedAlarms { sortedItems ->
            items = sortedItems
            val safeActivity = activity as? SimpleActivity ?: return@getSortedAlarms

            // Compute per-folder alarm counts from all alarms (not just uncategorized)
            val allAlarms = safeActivity.dbHelper.getAlarms()
            val folderCounts = allAlarms
                .filter { it.folderId != null }
                .groupBy { it.folderId!! }
                .mapValues { it.value.size }

            var currAdapter = binding.alarmsList.adapter as? AlarmsAdapter
            if (currAdapter == null) {
                currAdapter = AlarmsAdapter(
                    activity = safeActivity,
                    items = items,
                    toggleAlarmInterface = this,
                    recyclerView = binding.alarmsList,
                    itemClick = { clicked ->
                        when (clicked) {
                            is Alarm -> openEditAlarm(clicked)
                        }
                    },
                    folderClick = { folder ->
                        openFolder(folder)
                    }
                ).apply {
                    binding.alarmsList.adapter = this
                    folderAlarmCounts = folderCounts
                    // Wire up "Move to folder" action
                    onMoveToFolderRequested = { selectedAlarms ->
                        showMoveToFolderDialog(selectedAlarms)
                    }
                }
            } else {
                currAdapter.apply {
                    folderAlarmCounts = folderCounts
                    updatePrimaryColor()
                    updateBackgroundColor(safeActivity.getProperBackgroundColor())
                    updateTextColor(safeActivity.getProperTextColor())
                    updateItems(items)
                }
            }

            // Placeholder: show only if no folders AND no uncategorized alarms
            val hasContent = items.isNotEmpty()
            binding.alarmsPlaceholder.beVisibleIf(!hasContent)
        }
    }

    private fun openFolder(folder: AlarmFolder) {
        val activity = activity as? MainActivity ?: return
        activity.openFolderFragment(folder.id, folder.name)
    }

    private fun showMoveToFolderDialog(alarms: List<Alarm>) {
        val safeActivity = activity as? SimpleActivity ?: return
        SelectFolderDialog(
            activity = safeActivity,
            onFolderSelected = { folderId ->
                safeActivity.dbHelper.moveAlarmsToFolder(alarms.map { it.id }, folderId)
                setupAlarms()
                updateAlarmHero()
            },
            onCreateNewFolder = {
                CreateFolderDialog(safeActivity) {
                    // After creating folder, move alarms into it
                    val newFolderId = safeActivity.dbHelper.insertFolder(it)
                    if (newFolderId != -1) {
                        safeActivity.dbHelper.moveAlarmsToFolder(alarms.map { it.id }, newFolderId)
                    }
                    setupAlarms()
                    updateAlarmHero()
                }
            }
        )
    }

    private fun openEditAlarm(alarm: Alarm) {
        currentEditAlarmDialog = EditAlarmDialog(activity as SimpleActivity, alarm) {
            alarm.id = it
            currentEditAlarmDialog = null

            // Dismiss stale upcoming-alarm notification — the alarm's time/days may have changed.
            requireContext().notificationManager.cancel(UPCOMING_ALARM_NOTIFICATION_BASE + alarm.id)

            setupAlarms()
            updateAlarmHero()
            checkAlarmState(alarm)
        }
    }

    override fun alarmToggled(id: Int, isEnabled: Boolean) {
        (activity as SimpleActivity).handleFullScreenNotificationsPermission { granted ->
            if (granted) {
                if (requireContext().dbHelper.updateAlarmEnabledState(id, isEnabled)) {
                    val alarm = items.filterIsInstance<Alarm>().firstOrNull { it.id == id }
                        ?: return@handleFullScreenNotificationsPermission
                    alarm.isEnabled = isEnabled
                    checkAlarmState(alarm)
                    if (!alarm.isEnabled) {
                        requireContext().notificationManager.cancel(
                            UPCOMING_ALARM_NOTIFICATION_BASE + alarm.id
                        )
                    }
                    if (!alarm.isEnabled && alarm.oneShot) {
                        requireContext().dbHelper.deleteAlarms(arrayListOf(alarm))
                        setupAlarms()
                    }
                    updateAlarmHero()
                } else {
                    requireActivity().toast(org.fossify.commons.R.string.unknown_error_occurred)
                }
                requireContext().updateWidgets()
            } else {
                setupAlarms()
            }
        }
    }

    private fun checkAlarmState(alarm: Alarm) {
        val activity = activity as? MainActivity ?: return
        if (alarm.isEnabled) {
            activity.alarmController.scheduleNextOccurrence(alarm = alarm, showToasts = true)
        } else {
            activity.cancelAlarmClock(alarm)
        }
        activity.updateClockTabAlarm()
    }

    fun updateAlarmSound(alarmSound: AlarmSound) {
        currentEditAlarmDialog?.updateSelectedAlarmSound(alarmSound)
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onMessageEvent(@Suppress("unused") event: AlarmEvent.Refresh) {
        setupAlarms()
        updateAlarmHero()
    }
}
