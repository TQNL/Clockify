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
import org.fossify.clock.models.FolderAlarmCounts
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

open class AlarmFragment : Fragment(), ToggleAlarmInterface {
    companion object {
        private const val HERO_REFRESH_INTERVAL = 60_000L // refresh every 60 seconds

        // Bundle argument keys — used by FolderFragment subclass
        protected const val ARG_FOLDER_ID = "folder_id"
        protected const val ARG_FOLDER_NAME = "folder_name"
    }

    // Read from arguments; non-null only when this fragment is acting as a folder view.
    protected val folderId: Int?
        get() = arguments?.getInt(ARG_FOLDER_ID, -1)?.takeIf { it != -1 }
    protected val folderName: String?
        get() = arguments?.getString(ARG_FOLDER_NAME)

    protected var items = ArrayList<Any>()
    private var currentEditAlarmDialog: EditAlarmDialog? = null

    private lateinit var binding: FragmentAlarmBinding
    private val heroRefreshHandler = Handler(Looper.getMainLooper())

    // -----------------------------------------------------------------
    // Subclass extension points — override in FolderFragment
    // -----------------------------------------------------------------

    /** Whether this instance is showing alarms inside a folder. */
    protected open fun isFolderView(): Boolean = folderId != null

    /**
     * Load the items (alarms and optionally folders) to display.
     * Default: mixed list of folders + sorted uncategorized alarms (main tab).
     * Override in [FolderFragment] to load only alarms belonging to a folder.
     */
    protected open fun loadAlarmItems(
        callback: (items: ArrayList<Any>, folders: List<AlarmFolder>, folderCounts: Map<Int, FolderAlarmCounts>) -> Unit
    ) {
        getSortedAlarms(callback)
    }

    /**
     * Build a new Alarm for the FAB "+" button.
     * Override in [FolderFragment] to auto-assign [Alarm.folderId].
     */
    protected open fun createFabAlarm(): Alarm {
        val newAlarm = requireContext().createNewAlarm(DEFAULT_ALARM_MINUTES, 0)
        newAlarm.isEnabled = true
        newAlarm.days = getTomorrowBit()
        return newAlarm
    }

    /** Handle a click on an item in the alarm list (alarm or folder). */
    protected open fun onAlarmItemClick(item: Any) {
        when (item) {
            is Alarm -> openEditAlarm(item)
            is AlarmFolder -> (activity as? MainActivity)?.openFolderFragment(item.id, item.name)
        }
    }

    // -----------------------------------------------------------------
    // Fragment lifecycle
    // -----------------------------------------------------------------

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

    // -----------------------------------------------------------------
    // Toolbar actions (main tab only)
    // -----------------------------------------------------------------

    fun showSortingDialog() {
        ChangeAlarmSortDialog(activity as SimpleActivity) {
            setupAlarms()
        }
    }

    fun showCreateFolderDialog() {
        if (isFolderView()) return
        CreateFolderDialog(activity as SimpleActivity) { name ->
            activity?.dbHelper?.insertFolder(name)
            setupAlarms()
        }
    }

    /** Public entry point for the toolbar "+" button. */
    fun addAlarm() {
        openEditAlarm(createFabAlarm())
    }

    // -----------------------------------------------------------------
    // View setup
    // -----------------------------------------------------------------

    private fun setupViews() {
        binding.apply {
            requireContext().updateTextColors(alarmFragment)
            alarmFab.visibility = View.GONE
        }

        setupAlarms()
        updateAlarmHero()
    }

    // --- Samsung-style Alarm Hero (main tab only) ---

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
        safeContext.getAlarmHeroInfo(folderId = folderId) { heroInfo ->
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

    // --- Alarm list (main tab: folders + uncategorized alarms) ---

    private fun getSortedAlarms(callback: (items: ArrayList<Any>, folders: List<AlarmFolder>, folderCounts: Map<Int, FolderAlarmCounts>) -> Unit) {
        val safeContext = context ?: return
        ensureBackgroundThread {
            val allAlarms = context?.dbHelper?.getAlarms() ?: return@ensureBackgroundThread
            val folders = context?.dbHelper?.getFolders() ?: emptyList()
            val uncategorizedAlarms = allAlarms.filter { it.folderId == null }

            val folderCounts = folders.associate { folder ->
                val folderAlarms = allAlarms.filter { it.folderId == folder.id }
                folder.id to FolderAlarmCounts(
                    enabled = folderAlarms.count { it.isEnabled },
                    disabled = folderAlarms.count { !it.isEnabled },
                )
            }

            val sorted = when (safeContext.config.alarmSort) {
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
                            customAlarmsSortOrderString.split(", ").mapNotNull { it.toIntOrNull() }
                        val alarmsIdValueMap = uncategorizedAlarms.associateBy { it.id }
                        val sorted = customAlarmsSortOrder.mapNotNull { alarmsIdValueMap[it] }
                        sorted + uncategorizedAlarms.filter { it !in sorted }
                    }
                }

                else -> uncategorizedAlarms
            }

            // Merge: folders come first, then uncategorized alarms
            val mixed: ArrayList<Any> = ArrayList(folders.toList())
            mixed.addAll(ArrayList(sorted))

            activity?.runOnUiThread {
                callback(mixed, folders, folderCounts)
            }
        }
    }

    // --- Shared adapter wiring (used by both main tab and folder view) ---

    private fun setupAlarms() {
        loadAlarmItems { loadedItems, folders, folderCounts ->
            items = ArrayList(loadedItems)
            val safeActivity = activity as? SimpleActivity ?: return@loadAlarmItems

            var currAdapter = binding.alarmsList.adapter as? AlarmsAdapter
            if (currAdapter == null) {
                currAdapter = AlarmsAdapter(
                    activity = safeActivity,
                    items = loadedItems,
                    toggleAlarmInterface = this,
                    recyclerView = binding.alarmsList,
                    itemClick = { clicked -> onAlarmItemClick(clicked) },
                    folderClick = { folder ->
                        (activity as? MainActivity)?.openFolderFragment(folder.id, folder.name)
                    },
                    folderAlarmCounts = folderCounts,
                    folderId = folderId,
                ).apply {
                    binding.alarmsList.adapter = this
                }
            } else {
                currAdapter.apply {
                    updatePrimaryColor()
                    updateBackgroundColor(safeActivity.getProperBackgroundColor())
                    updateTextColor(safeActivity.getProperTextColor())
                    updateItems(loadedItems, folderCounts)
                }
            }

            val hasContent = items.isNotEmpty()
            binding.alarmsPlaceholder.beVisibleIf(!hasContent)
        }
    }

    // --- Edit / Toggle / Reschedule ---

    protected fun openEditAlarm(alarm: Alarm) {
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
