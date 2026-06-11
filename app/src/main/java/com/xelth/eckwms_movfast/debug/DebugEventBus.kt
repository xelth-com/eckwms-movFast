package com.xelth.eckwms_movfast.debug

import android.content.Intent
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class DebugEventBus {

    private val _events = MutableSharedFlow<Intent>(
        replay = 0,
        extraBufferCapacity = 10
    )

    val events: SharedFlow<Intent> = _events.asSharedFlow()

    fun emitEvent(intent: Intent) {
        val command = intent.getStringExtra(DebugAction.EXTRA_COMMAND)
        Log.d(DebugAction.DEBUG_TAG, "DebugEventBus emitting event: $command")

        val success = _events.tryEmit(intent)
        if (!success) {
            Log.w(DebugAction.DEBUG_TAG, "Failed to emit debug event: buffer full")
        }
    }
}
