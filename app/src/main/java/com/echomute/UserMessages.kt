package com.echomute

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** One-way channel for background work to surface a short message in the UI. */
internal object UserMessages {
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun show(text: String) {
        _messages.tryEmit(text)
    }
}
