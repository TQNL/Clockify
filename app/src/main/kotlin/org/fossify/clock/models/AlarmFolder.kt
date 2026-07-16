package org.fossify.clock.models

import androidx.annotation.Keep

@Keep
@kotlinx.serialization.Serializable
data class AlarmFolder(
    val id: Int,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
)

/** Breakdown of alarms inside a folder (for display in the folder row). */
data class FolderAlarmCounts(
    val enabled: Int,
    val disabled: Int,
) {
    val total: Int get() = enabled + disabled
}
