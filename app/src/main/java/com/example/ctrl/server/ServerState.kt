package com.example.ctrl.server

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class ServerStatus(
    val running: Boolean,
    val host: String,
    val port: Int,
    val lastError: String?,
) {
    companion object {
        fun stopped(port: Int) = ServerStatus(running = false, host = "0.0.0.0", port = port, lastError = null)
    }
}

object ServerState {
    private val _status = MutableStateFlow(ServerStatus.stopped(port = 8787))
    val status: StateFlow<ServerStatus> = _status

    fun update(status: ServerStatus) {
        _status.value = status
    }
}
