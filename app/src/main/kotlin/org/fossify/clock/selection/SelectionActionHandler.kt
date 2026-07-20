package org.fossify.clock.selection

import org.fossify.clock.models.Alarm
import org.fossify.clock.models.AlarmFolder

/**
 * Bridge between the selection action bar ([AlarmsAdapter]) and the fragment
 * that owns the alarm lifecycle (scheduling, dialogs, toasts).
 *
 * The adapter collects what is selected and decides *which* actions to show
 * via [computeActions]; the fragment decides *how* to execute them. This keeps
 * Android-framework dependencies (dialogs, AlarmController, toasts) out of the
 * adapter and out of the pure [SelectionActions] module.
 *
 * Each callback receives the current selection already split into alarms and
 * folders by the adapter, so the fragment never has to re-derive it.
 */
interface SelectionActionHandler {

    /** User pressed "Turn on" — enable every selected alarm (and every alarm
     *  inside each selected folder). */
    fun turnOn(alarms: List<Alarm>, folders: List<AlarmFolder>)

    /** User pressed "Turn off" — disable every selected alarm/folder alarm. */
    fun turnOff(alarms: List<Alarm>, folders: List<AlarmFolder>)

    /** User pressed "Folder" (Samsung "Group") — assign selected alarms to a
     *  folder chosen by the user. Not called with folders in the selection. */
    fun group(alarms: List<Alarm>)

    /** User pressed "Move" — move selected alarms to a folder (or out to the
     *  ungrouped list) chosen by the user. */
    fun move(alarms: List<Alarm>)

    /** User pressed "Ungroup" — dissolve each selected folder, leaving its
     *  alarms in the ungrouped list. */
    fun ungroup(folders: List<AlarmFolder>)

    /** User pressed "Delete" — delete selected standalone alarms. */
    fun delete(alarms: List<Alarm>)

    /** User pressed "Delete all" — delete selected alarms and, for any
     *  selected folder, the folder and all alarms inside it. */
    fun deleteAll(alarms: List<Alarm>, folders: List<AlarmFolder>)
}
