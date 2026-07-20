package org.fossify.clock.fragments

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import org.fossify.clock.R
import org.fossify.clock.activities.MainActivity
import org.fossify.clock.activities.SimpleActivity
import org.fossify.clock.adapters.AlarmsAdapter
import org.fossify.clock.databinding.FragmentAlarmBinding
import org.fossify.clock.dialogs.ChangeAlarmSortDialog
import org.fossify.clock.dialogs.CreateFolderDialog
import org.fossify.clock.dialogs.EditAlarmDialog
import org.fossify.clock.dialogs.MoveDestinationSheet
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
import org.fossify.clock.models.SelectionAction
import org.fossify.clock.models.SelectionState
import org.fossify.clock.models.SelectionType
import org.fossify.clock.models.actionMatrix
import org.fossify.clock.models.containsAlarm
import org.fossify.clock.models.containsFolder
import org.fossify.clock.models.count
import org.fossify.clock.models.isEmpty
import org.fossify.clock.models.actionMatrix
import org.fossify.commons.dialogs.ConfirmationDialog
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

    // Selection state (Samsung-style)
    protected var selectionState: SelectionState = SelectionState.Idle

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
        if (selectionState !is SelectionState.Idle) {
            // In selection mode — toggle selection on this item
            toggleItemSelection(item)
            return
        }
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
        setupSelectionHeader()
    }

    private fun setupSelectionHeader() {
        binding.apply {
            selectionAllBtn.setOnClickListener { selectAllItems() }
            selectionCancelBtn.setOnClickListener { exitSelectionMode() }
        }
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
        // Don't show hero during selection mode
        if (isSelectionActive()) {
            binding.alarmHero.visibility = View.GONE
            return
        }
        safeContext.getAlarmHeroInfo(folderId = folderId) { heroInfo ->
            // Check again inside callback — by the time getAlarmHeroInfo completes,
            // selection mode may have been entered (async race condition).
            if (isSelectionActive()) {
                binding.alarmHero.visibility = View.GONE
                return@getAlarmHeroInfo
            }

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
                    onSelectionChanged = { state ->
                        selectionState = state
                        if (state is SelectionState.Idle) {
                            exitSelectionMode()
                        } else {
                            enterSelectionMode()
                        }
                        updateSelectionUI()
                    },
                    onItemLongPressed = { item, position ->
                        val adapter = binding.alarmsList.adapter as? AlarmsAdapter ?: return@AlarmsAdapter
                        val key = getSelectionKeyForItem(item) ?: return@AlarmsAdapter
                        adapter.startSelectionWithItem(key)
                    },
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
                // Re-apply selection mode if it was active
                if (selectionState !is SelectionState.Idle) {
                    currAdapter.setSelectionMode(true)
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

    // -----------------------------------------------------------------
    // Selection Mode (Samsung-style)
    // -----------------------------------------------------------------

    private fun isSelectionActive(): Boolean = selectionState !is SelectionState.Idle

    protected fun toggleItemSelection(item: Any) {
        val adapter = binding.alarmsList.adapter as? AlarmsAdapter ?: return
        val key = getSelectionKeyForItem(item) ?: return
        adapter.toggleItemSelection(key)
        updateSelectionUI()
    }

    private fun getSelectionKeyForItem(item: Any): Int? {
        return when (item) {
            is Alarm -> item.id
            is AlarmFolder -> -(item.id + AlarmsAdapter.FOLDER_KEY_OFFSET)
            else -> null
        }
    }

    private fun enterSelectionMode() {
        binding.apply {
            alarmHero.visibility = View.GONE
            alarmFab.visibility = View.GONE
            selectionHeader.visibility = View.VISIBLE
            selectionBottomBar.visibility = View.VISIBLE
        }
        (binding.alarmsList.adapter as? AlarmsAdapter)?.setSelectionMode(true)
    }

    private fun exitSelectionMode() {
        selectionState = SelectionState.Idle
        binding.apply {
            alarmHero.visibility = View.VISIBLE
            alarmFab.visibility = View.VISIBLE
            selectionHeader.visibility = View.GONE
            selectionBottomBar.visibility = View.GONE
        }
        (binding.alarmsList.adapter as? AlarmsAdapter)?.setSelectionMode(false)
        updateAlarmHero()
    }

    private fun updateSelectionUI() {
        val adapter = binding.alarmsList.adapter as? AlarmsAdapter ?: return
        val state = selectionState

        // Update header count text
        val count = state.count()
        binding.selectionCountText.text = if (count > 0) {
            getString(R.string.selected_count, count)
        } else {
            getString(R.string.select_alarms)
        }

        // Compute action matrix
        val hasOffAlarms = adapter.hasOffAlarmsSelected()
        val hasOnAlarms = adapter.hasOnAlarmsSelected()
        val availableActions = actionMatrix(
            state = state,
            isFolderView = isFolderView(),
            hasOffAlarms = hasOffAlarms,
            hasOnAlarms = hasOnAlarms,
        )

        // Map actions to bottom bar buttons (up to 4)
        val actionButtons = listOf(
            binding.selectionAction1,
            binding.selectionAction2,
            binding.selectionAction3,
            binding.selectionAction4,
        )

        // Show first N available actions, hide the rest
        val actionList = availableActions.toList().take(4)
        actionButtons.forEachIndexed { index, button ->
            if (index < actionList.size) {
                val action = actionList[index]
                button.text = getActionLabel(action)
                button.visibility = View.VISIBLE
                button.setOnClickListener { handleSelectionAction(action) }
            } else {
                button.visibility = View.GONE
                button.setOnClickListener(null)
            }
        }
    }

    private fun getActionLabel(action: SelectionAction): String = when (action) {
        SelectionAction.TURN_ON -> getString(R.string.turn_on)
        SelectionAction.TURN_OFF -> getString(R.string.turn_off)
        SelectionAction.DELETE -> getString(R.string.delete_selected)
        SelectionAction.GROUP -> getString(R.string.group_selected)
        SelectionAction.MOVE -> getString(R.string.move_selected)
        SelectionAction.UNGROUP -> getString(R.string.ungroup_selected)
    }

    private fun selectAllItems() {
        val adapter = binding.alarmsList.adapter as? AlarmsAdapter ?: return
        adapter.selectAllVisible()
        updateSelectionUI()
    }

    private fun handleSelectionAction(action: SelectionAction) {
        val adapter = binding.alarmsList.adapter as? AlarmsAdapter ?: return
        val db = context?.dbHelper ?: return

        when (action) {
            SelectionAction.TURN_ON -> {
                val ids = adapter.getSelectedAlarmIds()
                if (ids.isNotEmpty()) {
                    db.updateAlarmsEnabledState(ids, true)
                    exitSelectionMode()
                    EventBus.getDefault().post(AlarmEvent.Refresh)
                }
            }

            SelectionAction.TURN_OFF -> {
                val ids = adapter.getSelectedAlarmIds()
                if (ids.isNotEmpty()) {
                    db.updateAlarmsEnabledState(ids, false)
                    exitSelectionMode()
                    EventBus.getDefault().post(AlarmEvent.Refresh)
                }
            }

            SelectionAction.DELETE -> {
                val alarmCount = adapter.getSelectedAlarmIds().size
                val folderCount = adapter.getSelectedFolderIds().size
                val totalCount = alarmCount + folderCount

                val message = if (folderCount > 0 && alarmCount == 0) {
                    getString(R.string.delete_folder_description)
                } else if (folderCount > 0 && alarmCount > 0) {
                    getString(R.string.alarms_deleted, totalCount)
                } else {
                    getString(R.string.alarms_deleted, totalCount)
                }

                ConfirmationDialog(
                    activity = activity as SimpleActivity,
                    message = message,
                    positive = org.fossify.commons.R.string.delete,
                    negative = org.fossify.commons.R.string.cancel,
                ) {
                    performDelete(adapter)
                }
            }

            SelectionAction.GROUP -> {
                val alarmIds = adapter.getSelectedAlarmIds()
                if (alarmIds.isEmpty()) return
                CreateFolderDialog(activity as SimpleActivity) { name ->
                    val newFolderId = db.insertFolder(name)
                    db.moveAlarmsToFolder(alarmIds, newFolderId)
                    requireActivity().toast(R.string.alarms_moved)
                    exitSelectionMode()
                    EventBus.getDefault().post(AlarmEvent.Refresh)
                }
            }

            SelectionAction.MOVE -> {
                val alarmIds = adapter.getSelectedAlarmIds()
                if (alarmIds.isEmpty()) return

                MoveDestinationSheet(
                    activity = activity as SimpleActivity,
                    onDestinationSelected = { targetFolderId ->
                        db.moveAlarmsToFolder(alarmIds, targetFolderId)
                        requireActivity().toast(R.string.alarms_moved)
                        exitSelectionMode()
                        EventBus.getDefault().post(AlarmEvent.Refresh)
                    },
                )
            }

            SelectionAction.UNGROUP -> {
                val folderIds = adapter.getSelectedFolderIds()
                if (folderIds.isEmpty()) return
                ConfirmationDialog(
                    activity = activity as SimpleActivity,
                    message = getString(R.string.ungroup_confirmation),
                    positive = org.fossify.commons.R.string.delete,
                    negative = org.fossify.commons.R.string.cancel,
                ) {
                    folderIds.forEach { db.deleteFolderAndKeepAlarms(it) }
                    exitSelectionMode()
                    EventBus.getDefault().post(AlarmEvent.Refresh)
                }
            }
        }
    }

    private fun performDelete(adapter: AlarmsAdapter) {
        val alarmIds = adapter.getSelectedAlarmIds()
        val folderIds = adapter.getSelectedFolderIds()
        val db = context?.dbHelper ?: return

        // Delete alarms
        if (alarmIds.isNotEmpty()) {
            val alarmsToDelete = items.filterIsInstance<Alarm>()
                .filter { it.id in alarmIds }
            if (alarmsToDelete.isNotEmpty()) {
                db.deleteAlarms(ArrayList(alarmsToDelete))
            }
        }

        // Ungroup (delete folder keeping alarms)
        folderIds.forEach { db.deleteFolderAndKeepAlarms(it) }

        val totalDeleted = alarmIds.size + folderIds.size
        requireActivity().toast(
            getString(R.string.alarms_deleted, totalDeleted)
        )

        exitSelectionMode()
        EventBus.getDefault().post(AlarmEvent.Refresh)
    }
}
