package org.fossify.clock.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import org.fossify.clock.R
import org.fossify.clock.activities.MainActivity
import org.fossify.clock.activities.SimpleActivity
import org.fossify.clock.adapters.AlarmsAdapter
import org.fossify.clock.databinding.FragmentFolderBinding
import org.fossify.clock.dialogs.EditAlarmDialog
import org.fossify.clock.dialogs.RenameFolderDialog
import org.fossify.clock.extensions.alarmController
import org.fossify.clock.extensions.cancelAlarmClock
import org.fossify.clock.extensions.createNewAlarm
import org.fossify.clock.extensions.dbHelper
import org.fossify.clock.extensions.handleFullScreenNotificationsPermission
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.clock.extensions.updateWidgets
import org.fossify.clock.helpers.DEFAULT_ALARM_MINUTES
import org.fossify.clock.helpers.UPCOMING_ALARM_NOTIFICATION_BASE
import org.fossify.clock.helpers.getTomorrowBit
import org.fossify.clock.interfaces.ToggleAlarmInterface
import org.fossify.clock.models.Alarm
import org.fossify.clock.models.AlarmEvent
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.notificationManager
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

class FolderFragment : Fragment(), ToggleAlarmInterface {
    companion object {
        const val ARG_FOLDER_ID = "folder_id"
        const val ARG_FOLDER_NAME = "folder_name"

        fun newInstance(folderId: Int, folderName: String): FolderFragment {
            return FolderFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_FOLDER_ID, folderId)
                    putString(ARG_FOLDER_NAME, folderName)
                }
            }
        }
    }

    private var folderId = 0
    private var folderName = ""
    private var alarms = ArrayList<Alarm>()
    private var currentEditAlarmDialog: EditAlarmDialog? = null

    private lateinit var binding: FragmentFolderBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            folderId = it.getInt(ARG_FOLDER_ID)
            folderName = it.getString(ARG_FOLDER_NAME) ?: ""
        }
        EventBus.getDefault().register(this)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentFolderBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbar()
        setupViews()
    }

    override fun onDestroy() {
        EventBus.getDefault().unregister(this)
        super.onDestroy()
    }

    private fun setupToolbar() {
        val safeContext = requireContext()
        val textColor = safeContext.getProperTextColor()

        binding.folderToolbar.apply {
            title = folderName
            setTitleTextColor(textColor)
            setBackgroundColor(safeContext.getProperBackgroundColor())
            navigationIcon?.applyColorFilter(textColor)
            setNavigationOnClickListener {
                (activity as? MainActivity)?.closeFolderFragment()
            }
            inflateMenu(R.menu.menu_folder)

            // Remap the "Move to folder" CAB action to mean "Remove from folder" in this context
            menu.findItem(R.id.rename_folder)?.let { item ->
                // Menu items should use textColor
            }
        }

        binding.folderToolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.rename_folder -> {
                    RenameFolderDialog(
                        activity = activity as SimpleActivity,
                        currentName = folderName
                    ) { newName ->
                        safeContext.dbHelper.updateFolderName(folderId, newName)
                        folderName = newName
                        binding.folderToolbar.title = newName
                        EventBus.getDefault().post(AlarmEvent.Refresh)
                    }
                    true
                }
                R.id.delete_folder -> {
                    deleteFolder()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupViews() {
        val safeContext = requireContext()
        val safeActivity = activity as? SimpleActivity ?: return
        safeContext.updateTextColors(binding.folderFragment)

        binding.folderFab.setOnClickListener {
            val newAlarm = safeContext.createNewAlarm(DEFAULT_ALARM_MINUTES, 0)
            newAlarm.isEnabled = true
            newAlarm.days = getTomorrowBit()
            newAlarm.folderId = folderId
            openEditAlarm(newAlarm)
        }

        setupAlarms()
    }

    private fun setupAlarms() {
        val safeActivity = activity as? SimpleActivity ?: return
        alarms = requireContext().dbHelper.getAlarmsInFolder(folderId) as ArrayList<Alarm>

        // Update toolbar subtitle with alarm count
        val count = alarms.size
        val subtitle = if (count == 1) {
            getString(R.string.n_alarm, count)
        } else {
            getString(R.string.n_alarms, count)
        }
        binding.folderToolbar.subtitle = subtitle
        val textColor = requireContext().getProperTextColor()
        val subduedColor = (textColor and 0x00FFFFFF) or (0xB3 shl 24)
        binding.folderToolbar.setSubtitleTextColor(subduedColor)

        var currAdapter = binding.folderAlarmsList.adapter as? AlarmsAdapter
        if (currAdapter == null) {
            currAdapter = AlarmsAdapter(
                activity = safeActivity,
                items = alarms,
                toggleAlarmInterface = this,
                recyclerView = binding.folderAlarmsList,
                itemClick = { clicked ->
                    if (clicked is Alarm) openEditAlarm(clicked)
                },
                isFolderContext = true
            ).apply {
                binding.folderAlarmsList.adapter = this
                // Repurpose "Move to folder" as "Remove from folder" here
                onMoveToFolderRequested = { selectedAlarms ->
                    removeFromFolder(selectedAlarms)
                }
            }
        } else {
            currAdapter.apply {
                updatePrimaryColor()
                updateBackgroundColor(safeActivity.getProperBackgroundColor())
                updateTextColor(safeActivity.getProperTextColor())
                updateItems(alarms)
            }
        }

        binding.folderAlarmsPlaceholder.beVisibleIf(alarms.isEmpty())
    }

    private fun removeFromFolder(alarms: List<Alarm>) {
        val dbHelper = requireContext().dbHelper
        dbHelper.moveAlarmsToFolder(alarms.map { it.id }, null)
        EventBus.getDefault().post(AlarmEvent.Refresh)
    }

    private fun deleteFolder() {
        ConfirmationDialog(
            activity = activity as SimpleActivity,
            messageId = R.string.delete_folder_description,
            positive = org.fossify.commons.R.string.yes,
            negative = org.fossify.commons.R.string.no
        ) {
            requireContext().dbHelper.deleteFolder(folderId)
            EventBus.getDefault().post(AlarmEvent.Refresh)
            (activity as? MainActivity)?.closeFolderFragment()
        }
    }

    private fun openEditAlarm(alarm: Alarm) {
        currentEditAlarmDialog = EditAlarmDialog(activity as SimpleActivity, alarm) {
            alarm.id = it
            currentEditAlarmDialog = null
            requireContext().notificationManager.cancel(UPCOMING_ALARM_NOTIFICATION_BASE + alarm.id)
            setupAlarms()
            checkAlarmState(alarm)
        }
    }

    override fun alarmToggled(id: Int, isEnabled: Boolean) {
        (activity as SimpleActivity).handleFullScreenNotificationsPermission { granted ->
            if (granted) {
                if (requireContext().dbHelper.updateAlarmEnabledState(id, isEnabled)) {
                    val alarm = alarms.firstOrNull { it.id == id }
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

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onMessageEvent(@Suppress("unused") event: AlarmEvent.Refresh) {
        setupAlarms()
    }
}
