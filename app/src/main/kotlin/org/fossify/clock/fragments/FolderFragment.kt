package org.fossify.clock.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import org.fossify.clock.R
import org.fossify.clock.activities.SimpleActivity
import org.fossify.clock.adapters.AlarmsAdapter
import org.fossify.clock.databinding.FragmentFolderBinding
import org.fossify.clock.dialogs.EditAlarmDialog
import org.fossify.clock.extensions.config
import org.fossify.clock.extensions.createNewAlarm
import org.fossify.clock.extensions.dbHelper
import org.fossify.clock.extensions.getFormattedTime
import org.fossify.clock.helpers.DEFAULT_ALARM_MINUTES
import org.fossify.clock.helpers.UPCOMING_ALARM_NOTIFICATION_BASE
import org.fossify.clock.helpers.getTomorrowBit
import org.fossify.clock.interfaces.ToggleAlarmInterface
import org.fossify.clock.models.Alarm
import org.fossify.clock.models.AlarmEvent
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.notificationManager
import org.fossify.commons.extensions.updateTextColors
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

class FolderFragment(
    private val folderId: Int,
    private val folderName: String,
) : Fragment(), ToggleAlarmInterface {

    private lateinit var binding: FragmentFolderBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EventBus.getDefault().register(this)
    }

    override fun onDestroy() {
        EventBus.getDefault().unregister(this)
        super.onDestroy()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentFolderBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        setupViews()
    }

    fun getFolderId() = folderId

    fun getFolderName() = folderName

    private fun setupViews() {
        binding.apply {
            requireContext().updateTextColors(folderFragment)
            folderFab.setOnClickListener {
                val newAlarm = root.context.createNewAlarm(DEFAULT_ALARM_MINUTES, 0)
                newAlarm.isEnabled = true
                newAlarm.days = getTomorrowBit()
                newAlarm.folderId = folderId
                openEditAlarm(newAlarm)
            }
        }
        setupAlarms()
    }

    private fun setupAlarms() {
        val safeContext = context ?: return
        val folderAlarms = safeContext.dbHelper.getAlarmsInFolder(folderId)

        binding.apply {
            val currAdapter = folderAlarmsList.adapter as? AlarmsAdapter
            if (currAdapter == null) {
                val adapter = AlarmsAdapter(
                    activity = safeContext as SimpleActivity,
                    items = ArrayList(folderAlarms),
                    toggleAlarmInterface = this@FolderFragment,
                    recyclerView = folderAlarmsList,
                    itemClick = { clicked ->
                        if (clicked is Alarm) openEditAlarm(clicked)
                    },
                    folderClick = {},
                    folderAlarmCounts = emptyMap(),
                )
                folderAlarmsList.adapter = adapter
            } else {
                currAdapter.updateItems(ArrayList(folderAlarms))
            }

            folderPlaceholder.beVisibleIf(folderAlarms.isEmpty())
        }
    }

    private fun openEditAlarm(alarm: Alarm) {
        val activity = activity as? SimpleActivity ?: return
        EditAlarmDialog(activity, alarm) {
            alarm.id = it
            requireContext().notificationManager.cancel(UPCOMING_ALARM_NOTIFICATION_BASE + alarm.id)
            setupAlarms()
            EventBus.getDefault().post(AlarmEvent.Refresh)
        }
    }

    override fun alarmToggled(id: Int, isEnabled: Boolean) {
        val safeContext = context ?: return
        if (safeContext.dbHelper.updateAlarmEnabledState(id, isEnabled)) {
            if (!isEnabled) {
                safeContext.notificationManager.cancel(UPCOMING_ALARM_NOTIFICATION_BASE + id)
            }
            setupAlarms()
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onMessageEvent(@Suppress("unused") event: AlarmEvent.Refresh) {
        setupAlarms()
    }
}
