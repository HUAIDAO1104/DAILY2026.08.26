package com.pengxh.daily.app.sqlite

import com.pengxh.daily.app.DailyTaskApplication
import com.pengxh.daily.app.sqlite.bean.DailyTaskBean
import com.pengxh.daily.app.sqlite.bean.LeaveRecordBean
import com.pengxh.daily.app.sqlite.bean.NotificationBean
import com.pengxh.daily.app.sqlite.bean.ExecutionRecordBean
import java.time.LocalDate

object DatabaseWrapper {
    private val dailyTaskDao by lazy { DailyTaskApplication.get().dataBase.dailyTaskDao() }

    suspend fun loadAllTask(): MutableList<DailyTaskBean> {
        return dailyTaskDao.loadAll()
    }

    suspend fun isTaskTimeExist(time: String): Boolean {
        return dailyTaskDao.queryTaskByTime(time) > 0
    }

    suspend fun updateTask(bean: DailyTaskBean) {
        dailyTaskDao.update(bean)
    }

    suspend fun findTaskById(id: Int): DailyTaskBean? = dailyTaskDao.findById(id)

    suspend fun findTaskByTime(time: String): DailyTaskBean? = dailyTaskDao.findByTime(time)

    suspend fun deleteTask(bean: DailyTaskBean) {
        dailyTaskDao.delete(bean)
    }

    suspend fun deleteTaskById(id: Int) {
        dailyTaskDao.deleteById(id)
    }

    suspend fun insert(bean: DailyTaskBean) {
        dailyTaskDao.insert(bean)
    }

    suspend fun replaceAllTasks(tasks: List<DailyTaskBean>) {
        dailyTaskDao.deleteAll()
        tasks.forEach {
            it.id = 0
            dailyTaskDao.insert(it)
        }
    }

    /*****************************************************************************************/
    private val leaveDao by lazy { DailyTaskApplication.get().dataBase.leaveRecordDao() }

    suspend fun loadAllLeaves(): MutableList<LeaveRecordBean> {
        return leaveDao.loadAll()
    }

    suspend fun loadLeavesForDate(date: LocalDate): MutableList<LeaveRecordBean> {
        return leaveDao.loadForDate(date.toString())
    }

    suspend fun insertLeave(bean: LeaveRecordBean): Long {
        return leaveDao.insert(bean)
    }

    suspend fun deleteLeaveById(id: Int) {
        leaveDao.deleteById(id)
    }

    suspend fun deleteLeavesForDate(date: LocalDate): Int {
        return leaveDao.deleteForDate(date.toString())
    }

    suspend fun replaceAllLeaves(leaves: List<LeaveRecordBean>) {
        leaveDao.deleteAll()
        leaves.forEach {
            it.id = 0
            leaveDao.insert(it)
        }
    }

    /*****************************************************************************************/
    private val noticeDao by lazy { DailyTaskApplication.get().dataBase.noticeDao() }

    suspend fun loadCurrentDayNotice(): MutableList<NotificationBean> {
        return noticeDao.loadCurrentDayNotice("${LocalDate.now()}")
    }

    suspend fun insertNotice(bean: NotificationBean) {
        noticeDao.insert(bean)
    }

    /*****************************************************************************************/
    private val executionRecordDao by lazy { DailyTaskApplication.get().dataBase.executionRecordDao() }

    suspend fun loadAllExecutionRecords(): MutableList<ExecutionRecordBean> {
        return executionRecordDao.loadAll().toMutableList()
    }

    suspend fun loadExecutionRecordsForDate(date: LocalDate): MutableList<ExecutionRecordBean> {
        return executionRecordDao.loadForDate(date.toString()).toMutableList()
    }

    suspend fun findExecutionRecordById(id: Int): ExecutionRecordBean? {
        return executionRecordDao.findById(id)
    }

    suspend fun insertExecutionRecord(bean: ExecutionRecordBean): Long {
        return executionRecordDao.insert(bean)
    }

    suspend fun clearExecutionRecords() {
        executionRecordDao.deleteAll()
    }
}
