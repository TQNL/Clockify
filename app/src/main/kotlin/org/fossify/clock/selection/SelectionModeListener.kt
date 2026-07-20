package org.fossify.clock.selection

/**
 * Bridges selection-mode lifecycle from [org.fossify.clock.adapters.AlarmsAdapter]
 * to the owning fragment so it can transform the screen (hide toolbar, hero, tabs;
 * show selection header + bottom action bar).
 */
interface SelectionModeListener {
    /** Long-press triggered the ActionMode — the fragment should enter selection UI. */
    fun onSelectionModeStarted()

    /** The selection set changed (item toggled, select all, etc.). */
    fun onSelectionModeChanged(snapshot: SelectionSnapshot)

    /** ActionMode finished — the fragment should restore normal UI. */
    fun onSelectionModeEnded()
}
