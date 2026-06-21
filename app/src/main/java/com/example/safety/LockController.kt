package com.example.safety

import com.example.statemachine.RideState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class LockController {
    private val _isLocked = MutableStateFlow(false)
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    /**
     * Determines whether settings should be locked out based on the live ride state
     * and whether the orchestrator/foreground service is running.
     * Locked when ride is running and state is anything other than IDLE.
     */
    fun updateState(state: RideState, isServiceRunning: Boolean) {
        _isLocked.value = isServiceRunning && state != RideState.IDLE
    }

    fun forceUnlock() {
        _isLocked.value = false
    }
}
