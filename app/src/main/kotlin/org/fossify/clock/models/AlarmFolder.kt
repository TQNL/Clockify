package org.fossify.clock.models

import androidx.annotation.Keep

@Keep
@kotlinx.serialization.Serializable
data class AlarmFolder(
    val id: Int,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
)
