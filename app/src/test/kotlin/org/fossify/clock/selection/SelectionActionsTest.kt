package org.fossify.clock.selection

import org.fossify.clock.models.Alarm
import org.fossify.clock.models.AlarmFolder
import org.fossify.clock.models.FolderAlarmCounts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exhaustive matrix tests for [computeActions], mirroring the Samsung Clock
 * reference recording (see `selection.txt`). These are JVM-only and run with
 * `./gradlew :app:testDebugUnitTest`.
 */
class SelectionActionsTest {

    private fun alarm(id: Int, enabled: Boolean = false, folderId: Int? = null) =
        Alarm(
            id = id,
            timeInMinutes = 420,
            days = 0,
            isEnabled = enabled,
            vibrate = false,
            soundTitle = "",
            soundUri = "",
            label = "",
            oneShot = false,
            folderId = folderId,
        )

    private fun folder(id: Int, name: String = "F$id") = AlarmFolder(id = id, name = name)

    private fun counts(enabled: Int, disabled: Int) = FolderAlarmCounts(enabled, disabled)

    private fun snapshot(
        alarms: List<Alarm> = emptyList(),
        folders: List<AlarmFolder> = emptyList(),
        folderCounts: Map<Int, FolderAlarmCounts> = emptyMap(),
        inFolderView: Boolean = false,
    ) = SelectionSnapshot(alarms, folders, folderCounts, inFolderView)

    // --- Samsung matrix (rows 1-8 of the catalogue) -----------------------

    /** Row 1: disabled standalone alarm → Turn on · Group · Move · Delete. */
    @Test
    fun disabledStandaloneAlarm_showsTurnOnGroupMoveDelete() {
        val s = snapshot(alarms = listOf(alarm(1, enabled = false)))
        assertEquals(
            setOf(SelectionAction.TURN_ON, SelectionAction.GROUP, SelectionAction.MOVE, SelectionAction.DELETE),
            computeActions(s),
        )
    }

    /** Row 2: enabled standalone alarm → Turn off · Group · Move · Delete. */
    @Test
    fun enabledStandaloneAlarm_showsTurnOffGroupMoveDelete() {
        val s = snapshot(alarms = listOf(alarm(1, enabled = true)))
        assertEquals(
            setOf(SelectionAction.TURN_OFF, SelectionAction.GROUP, SelectionAction.MOVE, SelectionAction.DELETE),
            computeActions(s),
        )
    }

    /** Row 3: non-empty folder with a disabled alarm → Turn on · Ungroup · Delete. */
    @Test
    fun folderWithDisabledAlarm_showsTurnOnUngroupDelete() {
        val s = snapshot(
            folders = listOf(folder(1)),
            folderCounts = mapOf(1 to counts(enabled = 0, disabled = 1)),
        )
        assertEquals(
            setOf(SelectionAction.TURN_ON, SelectionAction.UNGROUP, SelectionAction.DELETE),
            computeActions(s),
        )
    }

    /** Row 4: non-empty folder with an enabled alarm → Turn off · Ungroup · Delete. */
    @Test
    fun folderWithEnabledAlarm_showsTurnOffUngroupDelete() {
        val s = snapshot(
            folders = listOf(folder(1)),
            folderCounts = mapOf(1 to counts(enabled = 1, disabled = 0)),
        )
        assertEquals(
            setOf(SelectionAction.TURN_OFF, SelectionAction.UNGROUP, SelectionAction.DELETE),
            computeActions(s),
        )
    }

    /** Row 5: empty folder → Delete only. */
    @Test
    fun emptyFolder_showsDeleteOnly() {
        val s = snapshot(
            folders = listOf(folder(1)),
            folderCounts = mapOf(1 to counts(enabled = 0, disabled = 0)),
        )
        assertEquals(setOf(SelectionAction.DELETE), computeActions(s))
    }

    /** Row 6: nothing selected → no actions. */
    @Test
    fun emptySelection_returnsNoActions() {
        assertEquals(emptySet<SelectionAction>(), computeActions(snapshot()))
    }

    /** Row 7: alarm inside a folder → Delete all (not Delete). */
    @Test
    fun inFolderAlarm_usesDeleteAllNotDelete() {
        val s = snapshot(
            alarms = listOf(alarm(1, enabled = false)),
            inFolderView = true,
        )
        val actions = computeActions(s)
        assertTrue(actions.contains(SelectionAction.DELETE_ALL))
        assertFalse(actions.contains(SelectionAction.DELETE))
        // Group/Move still present for an alarm inside a folder.
        assertTrue(actions.contains(SelectionAction.GROUP))
        assertTrue(actions.contains(SelectionAction.MOVE))
    }

    /**
     * Row 8 (edge case from the prompt): selecting both a folder and an alarm
     * → Turn on · Delete all. No Group/Move (a folder is selected), no Ungroup
     * is *required* but Samsung omits it for the mixed case; we follow that.
     */
    @Test
    fun mixedFolderAndAlarm_showsTurnOnAndDeleteAll() {
        val s = snapshot(
            alarms = listOf(alarm(1, enabled = false)),
            folders = listOf(folder(2)),
            folderCounts = mapOf(2 to counts(enabled = 0, disabled = 1)),
        )
        val actions = computeActions(s)
        assertTrue(actions.contains(SelectionAction.TURN_ON))
        assertTrue(actions.contains(SelectionAction.DELETE_ALL))
        // Group/Move suppressed because a folder is in the selection.
        assertFalse(actions.contains(SelectionAction.GROUP))
        assertFalse(actions.contains(SelectionAction.MOVE))
        // No plain Delete once Delete all is in play.
        assertFalse(actions.contains(SelectionAction.DELETE))
    }

    /** Mixed enabled/disabled alarms → both Turn on and Turn off. */
    @Test
    fun mixedEnabledAlarms_showsBothTurnOnAndTurnOff() {
        val s = snapshot(alarms = listOf(alarm(1, enabled = true), alarm(2, enabled = false)))
        val actions = computeActions(s)
        assertTrue(actions.contains(SelectionAction.TURN_ON))
        assertTrue(actions.contains(SelectionAction.TURN_OFF))
    }

    /** Folder with a mix of enabled + disabled alarms → both turn actions, no Group/Move. */
    @Test
    fun folderWithMixedAlarms_showsBothTurnActionsAndNoGroupMove() {
        val s = snapshot(
            folders = listOf(folder(1)),
            folderCounts = mapOf(1 to counts(enabled = 1, disabled = 1)),
        )
        val actions = computeActions(s)
        assertTrue(actions.contains(SelectionAction.TURN_ON))
        assertTrue(actions.contains(SelectionAction.TURN_OFF))
        assertFalse(actions.contains(SelectionAction.GROUP))
        assertFalse(actions.contains(SelectionAction.MOVE))
        // Non-empty → Ungroup present.
        assertTrue(actions.contains(SelectionAction.UNGROUP))
    }

    /** Multiple folders, one empty one not → Ungroup appears (at least one non-empty). */
    @Test
    fun multipleFoldersOneEmpty_stillShowsUngroup() {
        val s = snapshot(
            folders = listOf(folder(1), folder(2)),
            folderCounts = mapOf(1 to counts(0, 0), 2 to counts(0, 1)),
        )
        assertTrue(computeActions(s).contains(SelectionAction.UNGROUP))
    }

    /** Top-level alarm uses Delete, not Delete all. */
    @Test
    fun topLevelAlarm_usesDeleteNotDeleteAll() {
        val s = snapshot(alarms = listOf(alarm(1, enabled = true)))
        val actions = computeActions(s)
        assertTrue(actions.contains(SelectionAction.DELETE))
        assertFalse(actions.contains(SelectionAction.DELETE_ALL))
    }
}
