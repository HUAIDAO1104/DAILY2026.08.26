package com.pengxh.daily.app.ai

data class AiActionPlan(
    val summary: String = "",
    val actions: List<AiAction> = emptyList(),
    val reply: String = ""
)

data class AiAction(
    val type: String = "",
    val id: Int? = null,
    val time: String? = null,
    val newTime: String? = null,
    val taskName: String? = null,
    val enabled: Boolean? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val period: String? = null,
    val reason: String? = null,
    val setting: String? = null,
    val value: String? = null,
    val workdays: List<Int>? = null
)

data class ValidatedAiPlan(
    val summary: String,
    val actions: List<AiAction>,
    val previews: List<String>,
    val requiresDangerConfirmation: Boolean
)

object AiActionTypes {
    const val ADD_TASK = "ADD_TASK"
    const val UPDATE_TASK = "UPDATE_TASK"
    const val DELETE_TASK = "DELETE_TASK"
    const val ADD_LEAVE = "ADD_LEAVE"
    const val CANCEL_LEAVE = "CANCEL_LEAVE"
    const val SET_SETTING = "SET_SETTING"
    const val SET_WORKDAYS = "SET_WORKDAYS"
    const val START_SCHEDULER = "START_SCHEDULER"
    const val STOP_SCHEDULER = "STOP_SCHEDULER"
    const val CREATE_SNAPSHOT = "CREATE_SNAPSHOT"
    const val RESTORE_LATEST_SNAPSHOT = "RESTORE_LATEST_SNAPSHOT"
}
