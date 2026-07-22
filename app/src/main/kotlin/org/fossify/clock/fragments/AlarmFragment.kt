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
import org.fossify.clock.dialogs.SelectFolderDialog
import org.fossify.clock.dialogs.UngroupConfirmDialog
import org.fossify.clock.selection.SelectionActionHandler
import org.fossify.clock.selection.SelectionModeListener
import org.fossify.clock.selection.SelectionSnapshot
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

open class AlarmFragment : Fragment(), ToggleAlarmInterface, SelectionActionHandler {
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

    internal lateinit var binding: FragmentAlarmBinding
    private val heroRefreshHandler = Handler(Looper.getMainLooper())

    /** Samsung-style selection mode state. */
    private var isInSelectionMode = false
    private var heroWasVisibleBeforeSelection = false

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

    /**
     * Turn every alarm in the current view (folder or main list) on/off.
     * Samsung-style "Turn all on / Turn all off" folder-level control.
     */
    fun turnAllAlarms(enabled: Boolean) {
        val alarms = items.filterIsInstance<Alarm>()
        if (alarms.isEmpty()) return
        applyEnabledState(alarms, enabled = enabled)
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
        if (isInSelectionMode) return
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
                    folderAlarmCounts = folderCounts,
                    folderId = folderId,
                    selectionActionHandler = this,
                ).apply {
                    binding.alarmsList.adapter = this
                    selectionModeListener = this@AlarmFragment.selectionModeListener
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

    // ---------------------------------------------------------------
    // Samsung-style selection mode — screen transformation
    // ---------------------------------------------------------------

    private val selectionModeListener = object : SelectionModeListener {
        override fun onSelectionModeStarted() = enterSelectionMode()
        override fun onSelectionModeChanged(snapshot: SelectionSnapshot) = updateSelectionUI(snapshot)
        override fun onSelectionModeEnded() = exitSelectionMode()
    }

    private fun enterSelectionMode() {
        isInSelectionMode = true
        heroWasVisibleBeforeSelection = binding.alarmHero.visibility == View.VISIBLE

        // Transform hero: show selection bar, hide countdown content
        binding.alarmHero.visibility = if (heroWasVisibleBeforeSelection || !isFolderView()) View.VISIBLE else View.GONE
        binding.heroSelectionBar.visibility = View.VISIBLE
        binding.alarmHeroCountdown.visibility = View.GONE
        binding.alarmHeroDatetime.visibility = View.GONE
        binding.alarmHeroDivider.visibility = View.GONE
        binding.alarmFab.visibility = View.GONE

        // Main tab: replace bottom tabs with action buttons; folder view: toolbar-based
        if (!isFolderView()) {
            (activity as? MainActivity)?.enterSelectionTabMode(this)
        }

        wireHeroSelection()
    }

    private fun exitSelectionMode() {
        isInSelectionMode = false

        // Restore hero to normal mode
        binding.heroSelectionBar.visibility = View.GONE
        if (heroWasVisibleBeforeSelection) {
            binding.alarmHeroCountdown.visibility = View.VISIBLE
            binding.alarmHeroDatetime.visibility = View.VISIBLE
            binding.alarmHeroDivider.visibility = View.VISIBLE
        } else {
            binding.alarmHero.visibility = View.GONE
        }
        binding.alarmFab.visibility = View.GONE

        // Restore navigation tabs
        if (!isFolderView()) {
            (activity as? MainActivity)?.exitSelectionTabMode()
        }
    }

    private fun wireHeroSelection() {
        binding.heroSelectAll.setOnClickListener {
            val adapter = binding.alarmsList.adapter as? AlarmsAdapter ?: return@setOnClickListener
            if (adapter.getSelectedKeysSet().size == adapter.getSelectableItemCount()) {
                adapter.deselectAll()
            } else {
                adapter.selectAllItems()
            }
        }
        binding.heroCancel.setOnClickListener {
            (binding.alarmsList.adapter as? AlarmsAdapter)?.exitSelectionMode()
        }
    }

    private fun updateSelectionUI(snapshot: SelectionSnapshot) {
        val count = snapshot.alarms.size + snapshot.folders.size

        // Update hero selection bar title
        binding.heroSelectionTitle.text = if (count == 0) {
            requireContext().getString(R.string.select_alarms)
        } else {
            requireContext().getString(R.string.n_selected, count)
        }

        // Update select-all visual state
        val adapter = binding.alarmsList.adapter as? AlarmsAdapter
        val totalSelectable = adapter?.getSelectableItemCount() ?: 0
        val allSelected = count == totalSelectable && count > 0
        binding.heroSelectAll.isSelected = allSelected
        binding.heroSelectAll.setImageResource(
            if (allSelected) R.drawable.ic_selection_check_circle_filled
            else R.drawable.ic_selection_check_circle_outline
        )

        // Tell MainActivity to rebuild action tabs
        (activity as? MainActivity)?.updateSelectionTabs(snapshot)
    }

    // -----------------------------------------------------------------
    // SelectionActionHandler — Samsung-style contextual actions
    // -----------------------------------------------------------------

    override fun turnOn(alarms: List<Alarm>, folders: List<AlarmFolder>) {
        applyEnabledState(expandSelection(alarms, folders), enabled = true)
    }

    override fun turnOff(alarms: List<Alarm>, folders: List<AlarmFolder>) {
        applyEnabledState(expandSelection(alarms, folders), enabled = false)
    }

    override fun group(alarms: List<Alarm>) {
        if (alarms.isEmpty()) return
        val safeActivity = activity as? SimpleActivity ?: return
        SelectFolderDialog(
            activity = safeActivity,
            titleId = R.string.folder,
            currentFolderId = alarms.firstOrNull()?.folderId,
        ) { folderId ->
            ensureBackgroundThread {
                alarms.forEach { alarm ->
                    if (alarm.folderId != folderId) {
                        requireContext().dbHelper.moveAlarmToFolder(alarm.id, folderId)
                    }
                }
                EventBus.getDefault().post(AlarmEvent.Refresh)
                activity?.runOnUiThread {
                    requireActivity().toast(R.string.alarms_moved)
                }
            }
        }
    }

    override fun move(alarms: List<Alarm>) {
        if (alarms.isEmpty()) return
        val safeActivity = activity as? SimpleActivity ?: return
        SelectFolderDialog(
            activity = safeActivity,
            titleId = R.string.select_folder,
            currentFolderId = alarms.firstOrNull()?.folderId,
        ) { folderId ->
            ensureBackgroundThread {
                alarms.forEach { alarm ->
                    if (alarm.folderId != folderId) {
                        requireContext().dbHelper.moveAlarmToFolder(alarm.id, folderId)
                    }
                }
                EventBus.getDefault().post(AlarmEvent.Refresh)
                activity?.runOnUiThread {
                    requireActivity().toast(R.string.alarms_moved)
                }
            }
        }
    }

    override fun ungroup(folders: List<AlarmFolder>) {
        if (folders.isEmpty()) return
        val safeActivity = activity as? SimpleActivity ?: return
        UngroupConfirmDialog(activity = safeActivity) {
            ensureBackgroundThread {
                folders.forEach { folder ->
                    requireContext().dbHelper.deleteFolder(folder.id)
                }
                EventBus.getDefault().post(AlarmEvent.Refresh)
                activity?.runOnUiThread {
                    requireActivity().toast(R.string.alarms_moved)
                }
            }
        }
    }

    override fun delete(alarms: List<Alarm>) {
        if (alarms.isEmpty()) return
        ensureBackgroundThread {
            requireContext().dbHelper.deleteAlarms(ArrayList(alarms))
            EventBus.getDefault().post(AlarmEvent.Refresh)
        }
    }

    override fun deleteAll(alarms: List<Alarm>, folders: List<AlarmFolder>) {
        ensureBackgroundThread {
            val ctx = requireContext()
            if (alarms.isNotEmpty()) ctx.dbHelper.deleteAlarms(ArrayList(alarms))
            folders.forEach { folder -> ctx.dbHelper.deleteFolderAndAlarms(folder.id) }
            EventBus.getDefault().post(AlarmEvent.Refresh)
        }
    }

    /**
     * Expand a selection that may include folders into the flat list of alarms
     * it covers (folder alarms + standalone alarms), de-duplicated by id.
     */
    private fun expandSelection(alarms: List<Alarm>, folders: List<AlarmFolder>): List<Alarm> {
        val ctx = context ?: return alarms
        val folderAlarms = folders.flatMap { ctx.dbHelper.getAlarmsInFolder(it.id) }
        return (alarms + folderAlarms).distinctBy { it.id }
    }

    /**
     * Enable/disable a batch of alarms, rescheduling each and showing a
     * Samsung-style countdown toast when turning on.
     */
    private fun applyEnabledState(alarms: List<Alarm>, enabled: Boolean) {
        val ctx = context ?: return
        val mainActivity = activity as? MainActivity
        ensureBackgroundThread {
            alarms.forEach { alarm ->
                if (alarm.isEnabled == enabled) return@forEach
                ctx.dbHelper.updateAlarmEnabledState(alarm.id, enabled)
                alarm.isEnabled = enabled
                if (enabled) {
                    mainActivity?.alarmController?.scheduleNextOccurrence(alarm, showToasts = false)
                } else {
                    mainActivity?.cancelAlarmClock(alarm)
                    ctx.notificationManager.cancel(UPCOMING_ALARM_NOTIFICATION_BASE + alarm.id)
                }
            }
            EventBus.getDefault().post(AlarmEvent.Refresh)
            if (enabled) {
                // Toast the soonest alarm countdown, Samsung-style.
                ctx.getAlarmHeroInfo(folderId = folderId) { heroInfo ->
                    if (heroInfo.countdownText.isNotEmpty()) {
                        activity?.runOnUiThread {
                            requireActivity().toast(heroInfo.countdownText)
                        }
                    }
                }
            }
            mainActivity?.updateClockTabAlarm()
            ctx.updateWidgets()
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onMessageEvent(@Suppress("unused") event: AlarmEvent.Refresh) {
        setupAlarms()
        updateAlarmHero()
    }
}
