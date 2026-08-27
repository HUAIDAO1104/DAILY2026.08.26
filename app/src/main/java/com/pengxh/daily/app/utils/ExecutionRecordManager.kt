package com.pengxh.daily.app.utils

import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.daily.app.sqlite.bean.DailyTaskBean
import com.pengxh.daily.app.sqlite.bean.ExecutionRecordBean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime

object ExecutionRecordManager {
    const val SUCCESS = "SUCCESS"
    const val TIMEOUT = "TIMEOUT"
    const val SKIPPED = "SKIPPED"
    const val INFO = "INFO"

    suspend fun recordTask(
        task: DailyTaskBean,
        plannedTime: String,
        actualTime: String?,
        status: String,
        detail: String
    ) = withContext(Dispatchers.IO) {
        DatabaseWrapper.insertExecutionRecord(ExecutionRecordBean().apply {
            date = LocalDate.now().toString()
            taskId = task.id
            taskName = task.displayName()
            this.plannedTime = plannedTime
            this.actualTime = actualTime ?: ""
            this.status = status
            this.detail = detail
            createdAt = System.currentTimeMillis()
        })
    }

    suspend fun recordDay(status: String, title: String, detail: String) = withContext(Dispatchers.IO) {
        DatabaseWrapper.insertExecutionRecord(ExecutionRecordBean().apply {
            date = LocalDate.now().toString()
            taskId = 0
            taskName = title
            plannedTime = ""
            actualTime = LocalTime.now().withNano(0).toString()
            this.status = status
            this.detail = detail
            createdAt = System.currentTimeMillis()
        })
    }
}

fun DailyTaskBean.displayName(): String {
    name?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    val hour = runCatching { LocalTime.parse(time).hour }.getOrDefault(12)
    return when {
        hour < 12 -> "上班打卡"
        hour >= 17 -> "下班打卡"
        else -> "定时打卡"
    }
}
