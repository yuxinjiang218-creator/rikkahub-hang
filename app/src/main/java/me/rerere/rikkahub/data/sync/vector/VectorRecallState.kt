package me.rerere.rikkahub.data.sync.vector

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class VectorRecallStatus(
    val handshakeOk: Boolean = false,
    val checking: Boolean = false,
    val lastError: String? = null,
)

class VectorRecallState {
    private val _status = MutableStateFlow(VectorRecallStatus())
    val status: StateFlow<VectorRecallStatus> = _status.asStateFlow()

    val handshakeOk: Boolean
        get() = _status.value.handshakeOk

    fun markChecking() {
        _status.update {
            it.copy(checking = true, lastError = null)
        }
    }

    fun markOk() {
        _status.update {
            it.copy(handshakeOk = true, checking = false, lastError = null)
        }
    }

    fun markFailed(error: String? = null) {
        _status.update {
            it.copy(handshakeOk = false, checking = false, lastError = error)
        }
    }
}
