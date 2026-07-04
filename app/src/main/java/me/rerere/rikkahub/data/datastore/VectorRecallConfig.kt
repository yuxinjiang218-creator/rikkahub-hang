package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.Serializable

@Serializable
data class VectorRecallConfig(
    val enabled: Boolean = false,
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
)
