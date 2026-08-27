package com.pengxh.daily.app.utils

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 悬浮窗控制器
 */
object FloatingWindowController {

    data class State(
        val visible: Boolean = false,
        val seconds: Int = 0
    )

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    fun updateTime(tick: Int) {
        val seconds = tick.coerceAtLeast(0)
        _state.value = State(visible = seconds > 0, seconds = seconds)
    }

    fun setOvertime(seconds: Int) {
        val safeSeconds = seconds.coerceAtLeast(0)
        _state.value = _state.value.copy(seconds = safeSeconds)
    }

    fun show() {
        _state.value = _state.value.copy(visible = true)
    }

    fun hide() {
        _state.value = State()
    }
}
