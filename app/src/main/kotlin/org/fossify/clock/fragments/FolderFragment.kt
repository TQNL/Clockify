package org.fossify.clock.fragments

import android.os.Bundle
import org.fossify.clock.extensions.config
import org.fossify.clock.extensions.dbHelper
import org.fossify.clock.extensions.firstDayOrder
import org.fossify.clock.helpers.SORT_BY_ALARM_TIME
import org.fossify.clock.helpers.SORT_BY_DATE_AND_TIME
import org.fossify.clock.models.Alarm
import org.fossify.clock.models.AlarmFolder
import org.fossify.clock.models.FolderAlarmCounts
import org.fossify.commons.helpers.SORT_BY_CUSTOM
import org.fossify.commons.helpers.SORT_BY_DATE_CREATED

/**
 * Thin subclass of [AlarmFragment] that shows alarms inside a single folder.
 *
 * Overrides four extension points — data source, FAB alarm creation,
 * item-click routing, and view-mode detection. All other behaviour
 * (adapter wiring, edit dialog, toggle/schedule logic, EventBus refresh,
 * folder-scoped hero countdown) is inherited from [AlarmFragment].
 *
 * [AlarmFragment.updateAlarmHero] passes [AlarmFragment.folderId] to
 * [org.fossify.clock.extensions.getAlarmHeroInfo], which filters to only
 * this folder's enabled alarms and skips the global system alarm clock
 * (avoiding cross-folder snooze pollution).
 */
class FolderFragment : AlarmFragment {

    constructor() : super()  // Required for Fragment instantiation

    companion object {
        fun newInstance(folderId: Int, folderName: String): FolderFragment {
            return FolderFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_FOLDER_ID, folderId)
                    putString(ARG_FOLDER_NAME, folderName)
                }
            }
        }
    }

    val displayName: String
        get() = folderName.orEmpty()

    // -- extension-point overrides ---------------------------------------------

    override fun isFolderView(): Boolean = true

    override fun loadAlarmItems(
        callback: (items: ArrayList<Any>, folders: List<AlarmFolder>, folderCounts: Map<Int, FolderAlarmCounts>) -> Unit
    ) {
        val safeContext = context ?: return
        val fid = folderId ?: return
        val folderAlarms = safeContext.dbHelper.getAlarmsInFolder(fid)
        val sorted = when (safeContext.config.alarmSort) {
            SORT_BY_ALARM_TIME -> folderAlarms.sortedBy { it.timeInMinutes }
            SORT_BY_DATE_CREATED -> folderAlarms.sortedBy { it.id }
            SORT_BY_DATE_AND_TIME -> folderAlarms.sortedWith(compareBy<Alarm> {
                safeContext.firstDayOrder(it.days)
            }.thenBy {
                it.timeInMinutes
            })
            SORT_BY_CUSTOM -> {
                val customOrder = safeContext.config.getFolderAlarmsCustomSorting(fid)
                    ?: safeContext.config.alarmsCustomSorting // fallback to global
                if (customOrder.isEmpty()) {
                    folderAlarms.sortedBy { it.id }
                } else {
                    val orderIds = customOrder.split(", ").mapNotNull { it.toIntOrNull() }
                    val idMap = folderAlarms.associateBy { it.id }
                    val ordered = orderIds.mapNotNull { idMap[it] }
                    ordered + folderAlarms.filter { it !in ordered }
                }
            }
            else -> folderAlarms
        }
        callback(ArrayList(sorted), emptyList(), emptyMap())
    }

    override fun createFabAlarm(): Alarm {
        val alarm = super.createFabAlarm()
        folderId?.let { alarm.folderId = it }
        return alarm
    }

    override fun onAlarmItemClick(item: Any) {
        // Inside a folder, the list contains only Alarms — no folder navigation.
        if (item is Alarm) openEditAlarm(item)
    }
}
