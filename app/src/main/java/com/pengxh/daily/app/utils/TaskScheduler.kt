package com.pengxh.daily.app.utils

import android.os.SystemClock
import com.pengxh.daily.app.DailyTaskApplication
import com.pengxh.daily.app.extensions.formatTime
import com.pengxh.daily.app.extensions.openApplication
import com.pengxh.daily.app.extensions.resolveExecutionTime
import com.pengxh.daily.app.service.CaptureImageService
import com.pengxh.daily.app.service.ForegroundRunningService
import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.daily.app.sqlite.bean.DailyTaskBean
import com.pengxh.kt.lite.utils.SaveKeyValues
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Calendar

/**
 * 任务调度器
 */
object TaskScheduler {
    enum class StopReason(val description: String) {
        USER_PAUSED("用户主动暂停"),
        SERVICE_RECREATED("任务服务被系统重建"),
        SERVICE_DESTROYED("任务服务被系统停止"),
        FOREGROUND_TIMEOUT("系统限制了后台运行时长"),
        TARGET_APP_ERROR("目标应用启动失败"),
        SCHEDULER_ERROR("任务调度发生异常"),
        UNEXPECTED_COMPLETION("任务调度意外结束")
    }

    data class StopInfo(
        val reason: StopReason,
        val detail: String,
        val timestamp: Long
    )

    /**
     * 调度器是否在运行中
     * */
    private val _isRunning = MutableStateFlow(false)
    val isRunning = _isRunning.asStateFlow()

    private val _lastStopInfo = MutableStateFlow(loadLastStopInfo())
    val lastStopInfo = _lastStopInfo.asStateFlow()

    /**
     * UI 文本事件（tipsView / adapter 高亮），不参与按钮逻辑
     * */
    private val _tipsEvent = MutableSharedFlow<TipsEvent>(extraBufferCapacity = 1)
    val tipsEvent = _tipsEvent.asSharedFlow()

    /**
     * 超时后回到主页信号（TaskScheduler → MainActivity）
     * */
    private val _returnToApp = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)
    val returnToApp = _returnToApp.asSharedFlow()

    private val stateLock = Any()

    @Volatile
    private var scope: CoroutineScope? = null

    @Volatile
    private var job: Job? = null

    /**
     * 打卡信号：外部 notifyClockIn() 触发，解除 select{} 阻塞
     * */
    private var clockInDeferred: CompletableDeferred<Unit>? = null

    private var lastProcessedDate: LocalDate? = null

    /**
     * 由 ForegroundRunningService 调用，注入协程作用域
     */
    fun attach(serviceScope: CoroutineScope) {
        val previousJob = synchronized(stateLock) {
            if (scope === serviceScope) return

            val activeJob = job?.takeIf { !it.isCompleted }
            if (scope != null && activeJob != null) {
                job = null
                _isRunning.value = false
            }
            scope = serviceScope
            activeJob
        }

        if (previousJob != null) {
            recordStop(
                StopReason.SERVICE_RECREATED,
                "检测到新的前台服务实例，迁移任务调度"
            )
            previousJob.cancel(CancellationException("前台服务实例已更换"))
        }
        LogFileManager.writeLog("TaskScheduler 已挂接前台服务")
    }

    /**
     * 前台服务销毁时解除作用域。保留用户的运行意图，等待服务恢复后自动续跑。
     */
    fun detach(
        serviceScope: CoroutineScope,
        reason: StopReason = StopReason.SERVICE_DESTROYED,
        detail: String = reason.description
    ) {
        val activeJob = synchronized(stateLock) {
            if (scope !== serviceScope) return
            scope = null
            val currentJob = job?.takeIf { !it.isCompleted }
            job = null
            _isRunning.value = false
            currentJob
        }

        if (isDesiredRunning()) {
            recordStop(reason, detail)
            activeJob?.cancel(CancellationException(detail))
        }
    }

    fun isRunning(): Boolean {
        return _isRunning.value
    }

    fun isDesiredRunning(): Boolean {
        return SaveKeyValues.loadBoolean(Constant.TASK_DESIRED_RUNNING_KEY, false)
    }

    fun getLastStopInfo(): StopInfo? = _lastStopInfo.value

    /**
     * 启动每日任务调度
     * 时序：防重复 → 检查协程作用域 → 判断周末/节假日 → 构建排程 → 启动核心循环
     */
    fun startTask() {
        SaveKeyValues.saveBoolean(Constant.TASK_DESIRED_RUNNING_KEY, true)
        startIfPossible("用户或自动规则请求启动")
    }

    /**
     * 服务重建、应用回到前台或存活检查时恢复调度器。
     * 只有用户此前明确启动过任务时才会生效，主动暂停不会被误恢复。
     */
    fun restoreIfNeeded(trigger: String): Boolean {
        if (!isDesiredRunning()) return false
        return startIfPossible(trigger)
    }

    private fun startIfPossible(trigger: String): Boolean {
        synchronized(stateLock) {
            if (job?.isCompleted == false) {
                _isRunning.value = true
                return true
            }
        }

        val currentScope = scope
        if (currentScope == null || !currentScope.isActive) {
            LogFileManager.writeLog("TaskScheduler 暂未挂接可用服务，保留启动意图：$trigger")
            return false
        }

        var terminalReasonRecorded = false
        val tempJob = currentScope.launch(start = CoroutineStart.LAZY) {
            try {
                while (isActive) {
                    val today = LocalDate.now()

                    // 今天已经处理过了，不再重复
                    if (lastProcessedDate == today) {
                        LogFileManager.writeLog("今日已处理，等待下一次重置")
                        if (isActive) waitUntilNextReset()
                        continue
                    }

                    if (shouldSkipToday()) {
                        _tipsEvent.emit(TipsEvent.Skip)
                        ForegroundRunningService.emitNotificationText("今日休息，任务已跳过")
                        ExecutionRecordManager.recordDay(
                            ExecutionRecordManager.SKIPPED,
                            "全天任务已跳过",
                            "请假、节假日或固定休息日规则生效"
                        )
                    } else {
                        val schedule = buildTodaySchedule()
                        if (schedule.isEmpty()) {
                            LogFileManager.writeLog("今日没有可执行任务，保持调度并等待下一任务周期")
                            ForegroundRunningService.emitNotificationText(
                                "今日没有可执行任务，调度守护中"
                            )
                        } else {
                            LogFileManager.writeLog("开始执行每日任务，共 ${schedule.size} 个")
                            executeSchedule(schedule)
                        }
                    }

                    lastProcessedDate = today

                    // 今天结束，睡到明天
                    if (isActive) waitUntilNextReset()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val reason = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
                LogFileManager.writeLog("任务调度异常：$reason")
                terminalReasonRecorded = true
                recordStop(StopReason.SCHEDULER_ERROR, reason)
                FloatingWindowController.hide()
                ForegroundRunningService.emitNotificationText("任务启动失败，请打开应用检查")
                _tipsEvent.emit(TipsEvent.Error("任务启动失败，请重试"))
            }
        }

        val installed = synchronized(stateLock) {
            if (job?.isCompleted == false) {
                false
            } else {
                job = tempJob
                _isRunning.value = true
                true
            }
        }
        if (!installed) {
            tempJob.cancel()
            return true
        }

        tempJob.invokeOnCompletion { cause ->
            val wasCurrentJob = synchronized(stateLock) {
                if (job !== tempJob) {
                    false
                } else {
                    job = null
                    _isRunning.value = false
                    true
                }
            }

            if (wasCurrentJob) {
                when {
                    cause is CancellationException -> {
                        LogFileManager.writeLog("任务调度已取消：${cause.message ?: "未提供原因"}")
                    }

                    cause != null -> {
                        recordStop(
                            StopReason.SCHEDULER_ERROR,
                            cause.message ?: cause.javaClass.simpleName
                        )
                    }

                    isDesiredRunning() && !terminalReasonRecorded -> {
                        recordStop(StopReason.UNEXPECTED_COMPLETION, "调度协程在未暂停时结束")
                    }
                }
            }
        }
        LogFileManager.writeLog("任务调度已启动：$trigger")
        tempJob.start()
        return true
    }

    /**
     * 获取当日 flag
     * */
    fun getDayFlag(): String {
        val today = LocalDate.now()
        return when {
            ChinaHolidayManager.isWorkday(today) -> "补班日"
            CustomWorkdayManager.isWeekdayRestDay(today) -> "休息日"
            ChinaHolidayManager.isHoliday(today) -> "节假日"
            else -> "工作日"
        }
    }

    /**
     * 链式任务主循环
     * for 循环保证顺序执行，每个任务经历三个阶段：
     *   阶段1 - delay(到任务时间) + 通知栏秒级倒计时
     *   阶段2 - openApplication() + select{超时|打卡} 竞态等待
     *   阶段3 - 推进到下一个任务（或全部完成 emit Completed）
     */
    private suspend fun CoroutineScope.executeSchedule(schedule: List<ScheduledTask>) {
        var executedCount = 0
        var skippedCount = 0

        for (task in schedule) {
            val now = System.currentTimeMillis()

            // 任务时间已过，跳过
            if (task.actualTimeMillis <= now) {
                skippedCount++
                LogFileManager.writeLog(
                    "第 ${task.displayIndex} 个任务已过期（计划=${task.plannedTime}，" +
                            "实际=${task.actualTime}），跳过"
                )
                ExecutionRecordManager.recordTask(
                    task.task,
                    task.plannedTime,
                    task.actualTime,
                    ExecutionRecordManager.SKIPPED,
                    "任务时间已过，自动跳过"
                )
                continue
            }

            // ====== 阶段 1：倒计时等待 ======
            val delayMs = task.actualTimeMillis - now
            _tipsEvent.emit(
                TipsEvent.Executing(
                    task.displayIndex,
                    schedule.size,
                    task.actualTime,
                    task.plannedTime
                )
            )

            LogFileManager.writeLog(
                "调度第 ${task.displayIndex} 个任务，" +
                        "计划时间=${task.plannedTime}，" +
                        "实际时间=${task.actualTime}，" +
                        "延迟=${delayMs / 1000}s"
            )

            updateCountdownWithNotification(delayMs) { remaining ->
                val seconds = (remaining / 1000).toInt()
                // 更新通知栏
                ForegroundRunningService.emitNotificationText("${seconds.formatTime()}后执行第${task.displayIndex}个任务")
            }

            // ====== 阶段 2：打开目标 App，等待打卡或超时 ======
            val timeoutSeconds = SaveKeyValues.loadInt(
                Constant.STAY_OVERTIME_KEY, Constant.DEFAULT_OVER_TIME
            )

            // 在切换到目标应用前先发布首帧倒计时；悬浮窗服务即使刚重启也能恢复显示。
            FloatingWindowController.updateTime(timeoutSeconds)
            DailyTaskApplication.get().openApplication()

            // Kotlin语法糖——竞态保护：select 只取先完成的分支，另一个自动取消
            var hasCaptured = false
            var captureDeferred: CompletableDeferred<String?>? = null
            val timeoutJob = launch {
                updateCountdownWithNotification(timeoutSeconds * 1000L) { remaining ->
                    val tick = (remaining / 1000).toInt()
                    FloatingWindowController.updateTime(tick)

                    // 最后 5 秒兜底截屏（只触发一次）
                    if (tick <= 5 && !hasCaptured) {
                        val resultSource = SaveKeyValues.loadInt(
                            Constant.RESULT_SOURCE_KEY, Constant.DEFAULT_INDEX
                        )
                        if (resultSource == 1) {
                            hasCaptured = true
                            captureDeferred = CaptureImageService.requestCaptureScreen()
                        }
                    }
                }
            }

            val clockInSuccess = select {
                // 分支 A：超时
                timeoutJob.onJoin { false }

                // 分支 B：打卡成功
                CompletableDeferred<Unit>().also { clockInDeferred = it }.onAwait { true }
            }

            timeoutJob.cancel()
            clockInDeferred = null
            FloatingWindowController.hide()

            // 超时路径——打卡失败，回到主页 + 兜底通知 + 继续下一个任务
            if (!clockInSuccess) {
                _returnToApp.emit(Unit)

                // 发送兜底截图给用户
                if (hasCaptured) {
                    // Deferred 内部已有 3s 超时兜底，await() 不会无限挂起
                    val imagePath = captureDeferred?.await() ?: ""
                    if (imagePath.isNotEmpty()) {
                        MessageDispatcher.sendAttachmentMessage(
                            "任务执行结果通知", "任务执行结果见附件", imagePath
                        )
                    } else {
                        MessageDispatcher.sendMessage("任务执行结果通知", "截屏失败，imagePath 为空")
                    }
                } else {
                    // 通知模式：无截图，纯文本提醒
                    MessageDispatcher.sendMessage(
                        "任务执行结果通知", "任务超时，请手动检查是否打卡成功"
                    )
                }
            }

            ExecutionRecordManager.recordTask(
                task.task,
                task.plannedTime,
                task.actualTime,
                if (clockInSuccess) ExecutionRecordManager.SUCCESS else ExecutionRecordManager.TIMEOUT,
                if (clockInSuccess) "已通过目标应用通知确认" else "等待结果超时，请手动确认"
            )

            // ====== 阶段 3：回到主界面，处理结果 ======
            executedCount++
        }

        // ====== 全部完成 ======
        val message = when {
            executedCount + skippedCount == 0 -> "无任务可供执行"
            executedCount == 0 -> "今日所有任务均已过期，跳过（$skippedCount 个），无需执行"
            skippedCount > 0 -> "今日任务已全部执行完毕（执行 $executedCount 个，跳过 $skippedCount 个）"
            else -> "今日任务已全部执行完毕"
        }
        LogFileManager.writeLog(message)
        ForegroundRunningService.emitNotificationText(message)
    }

    /**
     * 调试用：非 null 时跳过真实计算，直接使用指定秒数
     * 生产环境保持 null
     */
    @Volatile
    var debugWaitSeconds: Long? = null

    /**
     * 等待到下一个每日重置时间
     */
    private suspend fun waitUntilNextReset() {
        val resetHour = SaveKeyValues.loadInt(
            Constant.RESET_TIME_KEY, Constant.DEFAULT_RESET_HOUR
        )

        val waitSeconds = debugWaitSeconds ?: calculateSecondsUntilReset(resetHour)
        if (waitSeconds <= 0L) return  // 防御性代码：防止自旋

        LogFileManager.writeLog("等待 ${waitSeconds}s 后进入下一个任务周期")

        // 只发一次静态通知，不每秒刷新
        _tipsEvent.emit(TipsEvent.Completed)
        ForegroundRunningService.emitNotificationText("今日任务已执行完毕，等待下次任务")

        delay(waitSeconds * 1000)
    }

    /**
     * 打卡成功通知
     * 调用链：NotificationMonitorService.onNotificationPosted()
     *       → MainActivity.onClockInSuccess()
     *       → TaskScheduler.notifyClockIn()
     * 效果：完成 clockInDeferred，select{} 走分支 B，推进到下一个任务
     */
    fun notifyClockIn() {
        clockInDeferred?.complete(Unit)
    }

    fun stopTask() {
        SaveKeyValues.saveBoolean(Constant.TASK_DESIRED_RUNNING_KEY, false)
        recordStop(StopReason.USER_PAUSED, "用户主动暂停任务")
        val activeJob = synchronized(stateLock) {
            val currentJob = job
            job = null
            _isRunning.value = false
            currentJob
        }
        LogFileManager.writeLog("用户停止执行每日任务")
        activeJob?.cancel(CancellationException("用户主动暂停任务"))
        FloatingWindowController.hide()
        ForegroundRunningService.emitNotificationText("为保证程序正常运行，请勿移除此通知")
    }

    /**
     * 因外部错误请求停止（目标 App 未安装、启动失败等）
     * 由 Context.openApplication() 在无法打开目标 App 时调用
     *
     * 与 stopTask() 的区别：
     *   stopTask()     — 用户主动点击"停止"，发消息通知
     *   requestStopDueToError() — 系统错误停止，不发消息通知，只重置调度器
     */
    fun requestStopDueToError(reason: String) {
        LogFileManager.writeLog("因错误请求停止：$reason")
        recordStop(StopReason.TARGET_APP_ERROR, reason)
        val activeJob = synchronized(stateLock) {
            val currentJob = job
            job = null
            _isRunning.value = false
            currentJob
        }
        activeJob?.cancel(CancellationException(reason))
        FloatingWindowController.hide()
    }

    private fun recordStop(reason: StopReason, detail: String) {
        val timestamp = System.currentTimeMillis()
        SaveKeyValues.saveString(Constant.TASK_LAST_STOP_REASON_KEY, reason.name)
        SaveKeyValues.saveString(Constant.TASK_LAST_STOP_DETAIL_KEY, detail.take(160))
        SaveKeyValues.saveLong(Constant.TASK_LAST_STOP_TIME_KEY, timestamp)
        _lastStopInfo.value = StopInfo(reason, detail.take(160), timestamp)
        LogFileManager.writeLog("任务停止原因：${reason.description}；$detail")
    }

    private fun loadLastStopInfo(): StopInfo? {
        val reasonName = SaveKeyValues.loadString(Constant.TASK_LAST_STOP_REASON_KEY, "")
        val reason = runCatching { StopReason.valueOf(reasonName) }.getOrNull() ?: return null
        return StopInfo(
            reason = reason,
            detail = SaveKeyValues.loadString(
                Constant.TASK_LAST_STOP_DETAIL_KEY,
                reason.description
            ),
            timestamp = SaveKeyValues.loadLong(Constant.TASK_LAST_STOP_TIME_KEY, 0L)
        )
    }

    /**
     * 自校准倒计时 tick，支持 UI 回调。
     * 使用 elapsedRealtime 确保休眠唤醒后剩余时间准确。
     */
    private suspend fun CoroutineScope.updateCountdownWithNotification(
        totalMs: Long, onTick: (remainingMs: Long) -> Unit
    ) {
        val target = SystemClock.elapsedRealtime() + totalMs
        while (isActive) {
            val remaining = target - SystemClock.elapsedRealtime()
            if (remaining <= 0) break
            onTick(remaining)
            val step = minOf(1000L, remaining).coerceAtLeast(1)
            delay(step)
        }
    }

    private suspend fun shouldSkipToday(): Boolean {
        val today = LocalDate.now()
        if (LeaveManager.isAllDayLeave(today)) {
            LogFileManager.writeLog("今日已设置全天请假，跳过任务")
            return true
        }

        val skipEnabled = SaveKeyValues.loadBoolean(Constant.SKIP_HOLIDAY_KEY, true)
        if (!skipEnabled) return false

        // 调休补班日（覆盖一切，必须执行）
        if (ChinaHolidayManager.isWorkday(today)) {
            LogFileManager.writeLog("今日为调休补班日，正常执行任务")
            return false
        }

        // 法定节假日 → 跳过
        if (ChinaHolidayManager.isHoliday(today)) {
            LogFileManager.writeLog("今日为法定节假日，跳过任务")
            return true
        }

        // 一周休息日（默认周六日双休，用户可修改）→ 跳过
        if (CustomWorkdayManager.isWeekdayRestDay(today)) {
            LogFileManager.writeLog("今日为休息日，跳过任务")
            return true
        }

        // 其余情况 → 正常执行
        return false
    }

    /**
     * 从数据库加载所有任务，计算出当日实际执行时间，按时间排序
     * */
    private suspend fun buildTodaySchedule(): List<ScheduledTask> {
        val allTasks = withContext(Dispatchers.IO) {
            DatabaseWrapper.loadAllTask()
        }
        if (allTasks.isEmpty()) return emptyList()

        val baseMillis = LocalDate.now()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        val leavePeriods = LeaveManager.periodsFor(LocalDate.now())

        return allTasks.filter { it.isEnabled }.mapNotNull { task ->
            val plannedTime = runCatching { LocalTime.parse(task.time) }.getOrNull()
                ?: return@mapNotNull null
            if (LeaveManager.shouldSkipTime(plannedTime, leavePeriods)) {
                LogFileManager.writeLog("任务 ${task.time} 位于请假时段，已跳过")
                return@mapNotNull null
            }
            val actualTime = task.resolveExecutionTime()
            val timeParts = actualTime.split(":").map { it.toInt() }
            val actualMillis = baseMillis +
                    timeParts[0] * 3_600_000L +
                    timeParts[1] * 60_000L +
                    timeParts[2] * 1_000L
            Triple(task, actualTime, actualMillis)
        }.sortedBy { it.third }.mapIndexed { index, (task, actualTime, actualMillis) ->
            ScheduledTask(task, index + 1, task.time, actualTime, actualMillis)
        }
    }

    /**
     * 计算距离下一次重置还有多少秒
     */
    private fun calculateSecondsUntilReset(resetHour: Int): Long {
        val now = Calendar.getInstance()
        val target = now.clone() as Calendar
        target.set(Calendar.HOUR_OF_DAY, resetHour)
        target.set(Calendar.MINUTE, 0)
        target.set(Calendar.SECOND, 0)
        target.set(Calendar.MILLISECOND, 0)

        if (now.timeInMillis >= target.timeInMillis) {
            target.add(Calendar.DATE, 1)
        }

        return ((target.timeInMillis - now.timeInMillis) / 1000).coerceAtLeast(1)
    }

    private data class ScheduledTask(
        val task: DailyTaskBean,
        val displayIndex: Int,
        val plannedTime: String,
        val actualTime: String,
        val actualTimeMillis: Long
    )
}
