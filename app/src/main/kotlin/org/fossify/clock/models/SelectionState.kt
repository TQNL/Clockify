package org.fossify.clock.models

sealed interface SelectionState {
    data object Idle : SelectionState

    data class Active(
        val selectedIds: Set<Int>,
        val selectedTypes: Set<SelectionType>,
    ) : SelectionState
}

enum class SelectionType { ALARM, FOLDER }

enum class SelectionAction {
    TURN_ON,
    TURN_OFF,
    DELETE,
    GROUP,
    MOVE,
    UNGROUP,
}

fun SelectionState.isEmpty(): Boolean = when (this) {
    is SelectionState.Idle -> true
    is SelectionState.Active -> selectedIds.isEmpty()
}

fun SelectionState.count(): Int = when (this) {
    is SelectionState.Idle -> 0
    is SelectionState.Active -> selectedIds.size
}

fun SelectionState.containsAlarm(): Boolean = when (this) {
    is SelectionState.Idle -> false
    is SelectionState.Active -> selectedTypes.contains(SelectionType.ALARM)
}

fun SelectionState.containsFolder(): Boolean = when (this) {
    is SelectionState.Idle -> false
    is SelectionState.Active -> selectedTypes.contains(SelectionType.FOLDER)
}

fun SelectionState.isSingleFolder(): Boolean = when (this) {
    is SelectionState.Idle -> false
    is SelectionState.Active -> selectedIds.size == 1
        && selectedTypes.size == 1
        && selectedTypes.contains(SelectionType.FOLDER)
}

fun SelectionState.isSingleAlarm(): Boolean = when (this) {
    is SelectionState.Idle -> false
    is SelectionState.Active -> selectedIds.size == 1
        && selectedTypes.size == 1
        && selectedTypes.contains(SelectionType.ALARM)
}

fun SelectionState.isAllAlarms(): Boolean = when (this) {
    is SelectionState.Idle -> false
    is SelectionState.Active -> selectedTypes.size == 1
        && selectedTypes.contains(SelectionType.ALARM)
}

fun SelectionState.isAllFolders(): Boolean = when (this) {
    is SelectionState.Idle -> false
    is SelectionState.Active -> selectedTypes.size == 1
        && selectedTypes.contains(SelectionType.FOLDER)
}

/**
 * Computes the set of available [SelectionAction] values based on the current
 * [SelectionState] and whether items are in a folder view.
 */
fun actionMatrix(
    state: SelectionState,
    isFolderView: Boolean = false,
    hasOffAlarms: Boolean = false,
    hasOnAlarms: Boolean = false,
): Set<SelectionAction> {
    if (state.isEmpty()) return emptySet()

    val actions = mutableSetOf<SelectionAction>()
    val hasAlarms = state.containsAlarm()
    val hasFolders = state.containsFolder()

    // DELETE is always available when something is selected
    actions.add(SelectionAction.DELETE)

    if (hasAlarms && !hasFolders) {
        // Only alarms selected
        if (hasOffAlarms) actions.add(SelectionAction.TURN_ON)
        if (hasOnAlarms) actions.add(SelectionAction.TURN_OFF)
        actions.add(SelectionAction.GROUP)
        actions.add(SelectionAction.MOVE)
    } else if (hasAlarms && hasFolders) {
        // Mixed selection — only actions that work on both
        actions.add(SelectionAction.MOVE)
    } else if (hasFolders && !hasAlarms) {
        // Only folders selected
        actions.add(SelectionAction.UNGROUP)
        // Ungroup should imply "delete folder, keep alarms"
    }

    return actions
}
