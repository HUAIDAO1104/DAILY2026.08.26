package com.pengxh.daily.app.utils

import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.daily.app.sqlite.bean.LeaveRecordBean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime

enum class LeavePeriod {
    ALL_DAY,
    MORNING,
    AFTERNOON;

    companion object {
        fun fromRaw(value: String?): LeavePeriod {
            return entries.firstOrNull { it.name == value } ?: ALL_DAY
        }
    }
}

object LeaveManager {
    suspend fun addLeave(
        startDate: LocalDate,
        endDate: LocalDate,
        period: LeavePeriod,
        reason: String
    ): Long = withContext(Dispatchers.IO) {
        require(!endDate.isBefore(startDate)) { "结束日期不能早于开始日期" }
        DatabaseWrapper.insertLeave(LeaveRecordBean().apply {
            this.startDate = startDate.toString()
            this.endDate = endDate.toString()
            this.period = period.name
            this.reason = reason.ifBlank { "请假" }.take(80)
            this.createdAt = System.currentTimeMillis()
        })
    }

    suspend fun loadAll(): List<LeaveRecordBean> = withContext(Dispatchers.IO) {
        DatabaseWrapper.loadAllLeaves()
    }

    suspend fun deleteById(id: Int) = withContext(Dispatchers.IO) {
        DatabaseWrapper.deleteLeaveById(id)
    }

    suspend fun cancelForDate(date: LocalDate): Int = withContext(Dispatchers.IO) {
        DatabaseWrapper.deleteLeavesForDate(date)
    }

    suspend fun periodsFor(date: LocalDate): Set<LeavePeriod> = withContext(Dispatchers.IO) {
        DatabaseWrapper.loadLeavesForDate(date)
            .map { LeavePeriod.fromRaw(it.period) }
            .toSet()
    }

    suspend fun isAllDayLeave(date: LocalDate): Boolean {
        return LeavePeriod.ALL_DAY in periodsFor(date)
    }

    fun shouldSkipTime(time: LocalTime, periods: Set<LeavePeriod>): Boolean {
        if (LeavePeriod.ALL_DAY in periods) return true
        if (time.isBefore(LocalTime.NOON) && LeavePeriod.MORNING in periods) return true
        return !time.isBefore(LocalTime.NOON) && LeavePeriod.AFTERNOON in periods
    }

    fun periodLabel(period: String?): String {
        return when (LeavePeriod.fromRaw(period)) {
            LeavePeriod.ALL_DAY -> "全天"
            LeavePeriod.MORNING -> "上午"
            LeavePeriod.AFTERNOON -> "下午"
        }
    }
}
