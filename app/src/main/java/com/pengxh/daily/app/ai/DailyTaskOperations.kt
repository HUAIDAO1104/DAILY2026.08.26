package com.pengxh.daily.app.ai

import android.content.Context
import com.google.gson.Gson
import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.daily.app.sqlite.bean.DailyTaskBean
import com.pengxh.daily.app.utils.ConfigSnapshotManager
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.CustomWorkdayManager
import com.pengxh.daily.app.utils.LeaveManager
import com.pengxh.daily.app.utils.LeavePeriod
import com.pengxh.daily.app.utils.TaskScheduler
import com.pengxh.kt.lite.utils.SaveKeyValues
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.io.File
import com.pengxh.daily.app.utils.displayName

class DailyTaskOperations(private val context: Context) {
    private val gson = Gson()
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val supportedTypes = setOf(
        AiActionTypes.ADD_TASK,
        AiActionTypes.UPDATE_TASK,
        AiActionTypes.DELETE_TASK,
        AiActionTypes.ADD_LEAVE,
        AiActionTypes.CANCEL_LEAVE,
        AiActionTypes.SET_SETTING,
        AiActionTypes.SET_WORKDAYS,
        AiActionTypes.START_SCHEDULER,
        AiActionTypes.STOP_SCHEDULER,
        AiActionTypes.CREATE_SNAPSHOT,
        AiActionTypes.RESTORE_LATEST_SNAPSHOT
    )
    private val settingNames = mapOf(
        "reset_hour" to "每日重置时间",
        "timeout_seconds" to "打卡超时时间",
        "random_enabled" to "随机时间",
        "random_minutes" to "随机范围",
        "skip_holiday" to "节假日跳过",
        "auto_recycle" to "每日自动循环",
        "power_save" to "省电模式",
        "back_home" to "打卡后返回桌面",
        "gesture_enabled" to "手势辅助",
        "target_app" to "目标应用",
        "result_source" to "结果来源",
        "message_channel" to "消息渠道",
        "message_title" to "消息标题",
        "remote_command" to "远程口令",
        "remote_capture" to "远程打卡返回截图"
    )

    suspend fun buildStateJson(): String {
        val tasks = DatabaseWrapper.loadAllTask().map {
            mapOf("id" to it.id, "time" to it.time, "name" to it.displayName(), "enabled" to it.isEnabled)
        }
        val leaves = DatabaseWrapper.loadAllLeaves().map {
            mapOf(
                "id" to it.id,
                "startDate" to it.startDate,
                "endDate" to it.endDate,
                "period" to it.period,
                "reason" to it.reason
            )
        }
        val state = linkedMapOf<String, Any>(
            "today" to LocalDate.now().toString(),
            "tasks" to tasks,
            "leaves" to leaves,
            "schedulerRunning" to TaskScheduler.isRunning(),
            "schedulerDesiredRunning" to TaskScheduler.isDesiredRunning(),
            "settings" to currentSettings(),
            "workdays" to CustomWorkdayManager.loadWorkdays().map { it.value }.sorted()
        )
        TaskScheduler.getLastStopInfo()?.let { stopInfo ->
            state["schedulerLastStop"] = mapOf(
                "reason" to stopInfo.reason.name,
                "description" to stopInfo.reason.description,
                "detail" to stopInfo.detail,
                "timestamp" to stopInfo.timestamp
            )
        }
        return gson.toJson(state)
    }

    suspend fun validate(plan: AiActionPlan): ValidatedAiPlan {
        require(plan.actions.isNotEmpty()) { plan.reply.ifBlank { "没有识别出可执行操作" } }
        require(plan.actions.size <= 12) { "一次最多执行 12 个操作，请拆分后再试" }
        require(
            plan.actions.none { it.type.equals(AiActionTypes.RESTORE_LATEST_SNAPSHOT, true) } ||
                    plan.actions.size == 1
        ) { "恢复配置必须作为单独操作执行" }

        var runningAfterPreviousActions = TaskScheduler.isRunning()
        val normalized = mutableListOf<AiAction>()
        val previews = mutableListOf<String>()
        var danger = false

        plan.actions.forEachIndexed { index, raw ->
            val type = raw.type.trim().uppercase()
            require(type in supportedTypes) { "第 ${index + 1} 项操作不受支持：$type" }

            if (type == AiActionTypes.STOP_SCHEDULER) runningAfterPreviousActions = false
            if (type == AiActionTypes.START_SCHEDULER) runningAfterPreviousActions = true
            if (type in setOf(
                    AiActionTypes.ADD_TASK,
                    AiActionTypes.UPDATE_TASK,
                    AiActionTypes.DELETE_TASK
                ) && runningAfterPreviousActions
            ) {
                error("任务正在运行；请让 AI 先停止任务，再修改任务列表")
            }

            val action = normalize(raw.copy(type = type))
            normalized += action
            previews += preview(action)
            if (type in setOf(
                    AiActionTypes.DELETE_TASK,
                    AiActionTypes.CANCEL_LEAVE,
                    AiActionTypes.RESTORE_LATEST_SNAPSHOT
                )
            ) danger = true
        }

        return ValidatedAiPlan(
            summary = plan.summary.ifBlank { "准备执行 ${normalized.size} 项操作" }.take(120),
            actions = normalized,
            previews = previews,
            requiresDangerConfirmation = danger
        )
    }

    suspend fun execute(plan: ValidatedAiPlan): List<String> {
        val restoreTarget = if (plan.actions.singleOrNull()?.type == AiActionTypes.RESTORE_LATEST_SNAPSHOT) {
            ConfigSnapshotManager.list(context).firstOrNull()
                ?: error("本机还没有配置快照")
        } else null
        if (restoreTarget == null) {
            ConfigSnapshotManager.create(context, "AI 操作前自动备份")
        }
        val results = mutableListOf<String>()
        for (action in plan.actions) {
            results += executeOne(action, restoreTarget)
        }
        return results
    }

    private fun normalize(action: AiAction): AiAction {
        return when (action.type) {
            AiActionTypes.ADD_TASK -> action.copy(
                time = normalizeTime(action.time),
                taskName = action.taskName?.trim()?.take(30),
                enabled = action.enabled ?: true
            )
            AiActionTypes.UPDATE_TASK -> {
                require(action.id != null || !action.time.isNullOrBlank()) { "修改任务需要 id 或原时间" }
                require(!action.newTime.isNullOrBlank() || !action.taskName.isNullOrBlank() || action.enabled != null) {
                    "修改任务至少需要新的时间、名称或启用状态"
                }
                action.copy(
                    time = action.time?.let(::normalizeTime),
                    newTime = action.newTime?.let(::normalizeTime),
                    taskName = action.taskName?.trim()?.take(30)
                )
            }
            AiActionTypes.DELETE_TASK -> {
                require(action.id != null || !action.time.isNullOrBlank()) { "删除任务需要 id 或时间" }
                action.copy(time = action.time?.let(::normalizeTime))
            }
            AiActionTypes.ADD_LEAVE -> {
                val start = parseDate(action.startDate, "请假开始日期")
                val end = action.endDate?.let { parseDate(it, "请假结束日期") } ?: start
                require(!end.isBefore(start)) { "请假结束日期不能早于开始日期" }
                val period = parsePeriod(action.period)
                action.copy(
                    startDate = start.toString(),
                    endDate = end.toString(),
                    period = period.name,
                    reason = action.reason.orEmpty().ifBlank { "请假" }.take(80)
                )
            }
            AiActionTypes.CANCEL_LEAVE -> {
                require(action.id != null || !action.startDate.isNullOrBlank()) { "取消请假需要记录 id 或日期" }
                action.copy(startDate = action.startDate?.let { parseDate(it, "取消请假日期").toString() })
            }
            AiActionTypes.SET_SETTING -> {
                val setting = action.setting?.trim()?.lowercase().orEmpty()
                require(setting in settingNames) { "不允许修改设置：$setting" }
                val value = normalizeSettingValue(setting, action.value)
                action.copy(setting = setting, value = value)
            }
            AiActionTypes.SET_WORKDAYS -> {
                val values = action.workdays.orEmpty().distinct().sorted()
                require(values.isNotEmpty() && values.all { it in 1..7 }) { "工作日必须是 1（周一）到 7（周日）" }
                action.copy(workdays = values)
            }
            else -> action
        }
    }

    private fun preview(action: AiAction): String = when (action.type) {
        AiActionTypes.ADD_TASK -> "添加任务：${action.taskName?.takeIf { it.isNotBlank() }?.plus(" · ").orEmpty()}${action.time}"
        AiActionTypes.UPDATE_TASK -> {
            val changes = listOfNotNull(
                action.newTime?.let { "时间 → $it" },
                action.taskName?.let { "名称 → $it" },
                action.enabled?.let { if (it) "启用任务" else "停用任务" }
            ).joinToString("，")
            "修改任务：${action.time ?: "#${action.id}"} · $changes"
        }
        AiActionTypes.DELETE_TASK -> "删除任务：${action.time ?: "#${action.id}"}"
        AiActionTypes.ADD_LEAVE -> {
            val range = if (action.startDate == action.endDate) action.startDate else "${action.startDate} 至 ${action.endDate}"
            "添加请假：$range · ${periodLabel(action.period)} · ${action.reason}"
        }
        AiActionTypes.CANCEL_LEAVE -> "取消请假：${action.startDate ?: "#${action.id}"}"
        AiActionTypes.SET_SETTING -> "修改设置：${settingNames[action.setting]} → ${settingValueLabel(action.setting, action.value)}"
        AiActionTypes.SET_WORKDAYS -> "工作日改为：${action.workdays.orEmpty().joinToString("、") { dayLabel(it) }}"
        AiActionTypes.START_SCHEDULER -> "启动今日任务"
        AiActionTypes.STOP_SCHEDULER -> "停止正在运行的任务"
        AiActionTypes.CREATE_SNAPSHOT -> "创建本机配置快照"
        AiActionTypes.RESTORE_LATEST_SNAPSHOT -> "恢复最近一次本机配置快照"
        else -> action.type
    }

    private suspend fun executeOne(action: AiAction, restoreTarget: File?): String = when (action.type) {
        AiActionTypes.ADD_TASK -> {
            val time = requireNotNull(action.time)
            check(!DatabaseWrapper.isTaskTimeExist(time)) { "$time 的任务已经存在" }
            DatabaseWrapper.insert(DailyTaskBean().apply {
                this.time = time
                this.name = action.taskName?.takeIf { it.isNotBlank() }
                this.isEnabled = action.enabled ?: true
            })
            "已添加 $time 的任务"
        }
        AiActionTypes.UPDATE_TASK -> {
            val task = action.id?.let { DatabaseWrapper.findTaskById(it) }
                ?: action.time?.let { DatabaseWrapper.findTaskByTime(it) }
                ?: error("没有找到要修改的任务")
            action.newTime?.let { newTime ->
                val existing = DatabaseWrapper.findTaskByTime(newTime)
                check(existing == null || existing.id == task.id) { "$newTime 的任务已经存在" }
                task.time = newTime
            }
            action.taskName?.let { task.name = it.takeIf(String::isNotBlank) }
            action.enabled?.let { task.isEnabled = it }
            DatabaseWrapper.updateTask(task)
            "任务已更新：${task.displayName()} · ${task.time.take(5)}"
        }
        AiActionTypes.DELETE_TASK -> {
            val task = action.id?.let { DatabaseWrapper.findTaskById(it) }
                ?: action.time?.let { DatabaseWrapper.findTaskByTime(it) }
                ?: error("没有找到要删除的任务")
            DatabaseWrapper.deleteTask(task)
            "已删除 ${task.time} 的任务"
        }
        AiActionTypes.ADD_LEAVE -> {
            LeaveManager.addLeave(
                LocalDate.parse(action.startDate),
                LocalDate.parse(action.endDate),
                parsePeriod(action.period),
                action.reason.orEmpty()
            )
            "请假已保存"
        }
        AiActionTypes.CANCEL_LEAVE -> {
            if (action.id != null) {
                LeaveManager.deleteById(action.id)
                "请假记录已删除"
            } else {
                val count = LeaveManager.cancelForDate(LocalDate.parse(action.startDate))
                "已取消 $count 条请假记录"
            }
        }
        AiActionTypes.SET_SETTING -> {
            saveSetting(requireNotNull(action.setting), requireNotNull(action.value))
            "${settingNames[action.setting]}已更新"
        }
        AiActionTypes.SET_WORKDAYS -> {
            val workdays = action.workdays.orEmpty().map { DayOfWeek.of(it) }.toSet()
            CustomWorkdayManager.saveWorkdays(workdays)
            "工作日已更新"
        }
        AiActionTypes.START_SCHEDULER -> {
            TaskScheduler.startTask()
            "任务已启动"
        }
        AiActionTypes.STOP_SCHEDULER -> {
            TaskScheduler.stopTask()
            "任务已停止"
        }
        AiActionTypes.CREATE_SNAPSHOT -> {
            val file = ConfigSnapshotManager.create(context, action.reason ?: "手动创建")
            "配置快照已保存：${file.name}"
        }
        AiActionTypes.RESTORE_LATEST_SNAPSHOT -> ConfigSnapshotManager.restoreWithBackup(
            context,
            restoreTarget ?: error("没有可恢复的配置快照")
        )
        else -> error("不支持的操作")
    }

    private fun currentSettings(): Map<String, Any> = linkedMapOf(
        "reset_hour" to SaveKeyValues.loadInt(Constant.RESET_TIME_KEY, Constant.DEFAULT_RESET_HOUR),
        "timeout_seconds" to SaveKeyValues.loadInt(Constant.STAY_OVERTIME_KEY, Constant.DEFAULT_OVER_TIME),
        "random_enabled" to SaveKeyValues.loadBoolean(Constant.RANDOM_TIME_KEY, true),
        "random_minutes" to SaveKeyValues.loadInt(Constant.TIME_RANGE_KEY, Constant.DEFAULT_TIME_RANGE),
        "skip_holiday" to SaveKeyValues.loadBoolean(Constant.SKIP_HOLIDAY_KEY, true),
        "auto_recycle" to SaveKeyValues.loadBoolean(Constant.TASK_AUTO_RECYCLE_KEY, true),
        "power_save" to SaveKeyValues.loadBoolean(Constant.POWER_SAVE_MODE_KEY, false),
        "back_home" to SaveKeyValues.loadBoolean(Constant.BACK_TO_HOME_KEY, false),
        "gesture_enabled" to SaveKeyValues.loadBoolean(Constant.GESTURE_DETECTOR_KEY, true),
        "target_app" to SaveKeyValues.loadInt(Constant.TARGET_APP_KEY, 0),
        "result_source" to SaveKeyValues.loadInt(Constant.RESULT_SOURCE_KEY, Constant.DEFAULT_INDEX),
        "message_channel" to SaveKeyValues.loadInt(Constant.MSG_CHANNEL_KEY, Constant.DEFAULT_INDEX),
        "message_title" to SaveKeyValues.loadString(Constant.MESSAGE_TITLE_KEY, "打卡结果通知"),
        "remote_command" to SaveKeyValues.loadString(Constant.REMOTE_COMMAND_KEY, "打卡"),
        "remote_capture" to SaveKeyValues.loadBoolean(Constant.REMOTE_CLOCK_IN_CAPTURE_KEY, false)
    )

    private fun saveSetting(setting: String, value: String) {
        when (setting) {
            "reset_hour" -> SaveKeyValues.saveInt(Constant.RESET_TIME_KEY, value.toInt())
            "timeout_seconds" -> SaveKeyValues.saveInt(Constant.STAY_OVERTIME_KEY, value.toInt())
            "random_enabled" -> SaveKeyValues.saveBoolean(Constant.RANDOM_TIME_KEY, value.toBooleanStrict())
            "random_minutes" -> SaveKeyValues.saveInt(Constant.TIME_RANGE_KEY, value.toInt())
            "skip_holiday" -> SaveKeyValues.saveBoolean(Constant.SKIP_HOLIDAY_KEY, value.toBooleanStrict())
            "auto_recycle" -> SaveKeyValues.saveBoolean(Constant.TASK_AUTO_RECYCLE_KEY, value.toBooleanStrict())
            "power_save" -> SaveKeyValues.saveBoolean(Constant.POWER_SAVE_MODE_KEY, value.toBooleanStrict())
            "back_home" -> SaveKeyValues.saveBoolean(Constant.BACK_TO_HOME_KEY, value.toBooleanStrict())
            "gesture_enabled" -> SaveKeyValues.saveBoolean(Constant.GESTURE_DETECTOR_KEY, value.toBooleanStrict())
            "target_app" -> SaveKeyValues.saveInt(Constant.TARGET_APP_KEY, value.toInt())
            "result_source" -> SaveKeyValues.saveInt(Constant.RESULT_SOURCE_KEY, value.toInt())
            "message_channel" -> SaveKeyValues.saveInt(Constant.MSG_CHANNEL_KEY, value.toInt())
            "message_title" -> SaveKeyValues.saveString(Constant.MESSAGE_TITLE_KEY, value)
            "remote_command" -> SaveKeyValues.saveString(Constant.REMOTE_COMMAND_KEY, value)
            "remote_capture" -> SaveKeyValues.saveBoolean(Constant.REMOTE_CLOCK_IN_CAPTURE_KEY, value.toBooleanStrict())
        }
    }

    private fun normalizeSettingValue(setting: String, raw: String?): String {
        val value = raw?.trim().orEmpty()
        return when (setting) {
            "reset_hour" -> value.toIntOrNull()?.takeIf { it in 0..23 }?.toString()
                ?: error("重置时间必须是 0 到 23")
            "timeout_seconds" -> value.toIntOrNull()?.takeIf { it in 5..3600 }?.toString()
                ?: error("超时时间必须是 5 到 3600 秒")
            "random_minutes" -> value.toIntOrNull()?.takeIf { it in 0..180 }?.toString()
                ?: error("随机范围必须是 0 到 180 分钟")
            "random_enabled", "skip_holiday", "auto_recycle", "power_save", "back_home", "gesture_enabled", "remote_capture" ->
                parseBoolean(value).toString()
            "target_app" -> when (value.lowercase()) {
                "0", "ding", "dingtalk", "钉钉" -> "0"
                "1", "wework", "企业微信" -> "1"
                "2", "feishu", "lark", "飞书" -> "2"
                "3", "m3", "移动办公m3" -> "3"
                else -> error("目标应用仅支持钉钉、企业微信、飞书、移动办公 M3")
            }
            "result_source" -> when (value.lowercase()) {
                "0", "notification", "通知" -> "0"
                "1", "capture", "screenshot", "截图" -> "1"
                else -> error("结果来源仅支持通知或截图")
            }
            "message_channel" -> when (value.lowercase()) {
                "0", "email", "mail", "邮箱", "qq邮箱" -> "0"
                "1", "wecom", "企业微信", "企业微信机器人" -> "1"
                else -> error("消息渠道仅支持邮箱或企业微信")
            }
            "message_title", "remote_command" -> value.takeIf { it.isNotBlank() }?.take(60)
                ?: error("文本设置不能为空")
            else -> error("不支持的设置")
        }
    }

    private fun normalizeTime(raw: String?): String {
        val value = raw?.trim().orEmpty()
        val expanded = when {
            Regex("^\\d{1,2}:\\d{2}$").matches(value) -> "$value:00"
            Regex("^\\d{1,2}:\\d{2}:\\d{2}$").matches(value) -> value
            else -> error("时间格式应为 HH:mm 或 HH:mm:ss")
        }
        return try {
            LocalTime.parse(expanded, DateTimeFormatter.ofPattern("H:mm:ss")).format(timeFormatter)
        } catch (_: DateTimeParseException) {
            error("时间无效：$value")
        }
    }

    private fun parseDate(raw: String?, label: String): LocalDate {
        val value = raw?.trim().orEmpty()
        val date = runCatching { LocalDate.parse(value) }.getOrNull()
            ?: error("$label 格式应为 yyyy-MM-dd")
        require(date.year in (LocalDate.now().year - 1)..(LocalDate.now().year + 10)) { "$label 超出可用范围" }
        return date
    }

    private fun parsePeriod(raw: String?): LeavePeriod = when (raw?.trim()?.uppercase()) {
        null, "", "ALL_DAY", "全天" -> LeavePeriod.ALL_DAY
        "MORNING", "上午" -> LeavePeriod.MORNING
        "AFTERNOON", "下午" -> LeavePeriod.AFTERNOON
        else -> error("请假时段仅支持全天、上午或下午")
    }

    private fun parseBoolean(raw: String): Boolean = when (raw.lowercase()) {
        "true", "1", "on", "开启", "打开", "是" -> true
        "false", "0", "off", "关闭", "关掉", "否" -> false
        else -> error("开关值应为开启或关闭")
    }

    private fun periodLabel(raw: String?): String = when (parsePeriod(raw)) {
        LeavePeriod.ALL_DAY -> "全天"
        LeavePeriod.MORNING -> "上午"
        LeavePeriod.AFTERNOON -> "下午"
    }

    private fun dayLabel(value: Int) = when (value) {
        1 -> "周一"; 2 -> "周二"; 3 -> "周三"; 4 -> "周四"
        5 -> "周五"; 6 -> "周六"; else -> "周日"
    }

    private fun settingValueLabel(setting: String?, value: String?): String {
        if (value == "true") return "开启"
        if (value == "false") return "关闭"
        if (setting == "target_app") return listOf("钉钉", "企业微信", "飞书", "移动办公 M3")[value?.toIntOrNull()?.coerceIn(0, 3) ?: 0]
        if (setting == "result_source") return if (value == "1") "截图" else "通知"
        if (setting == "message_channel") return if (value == "1") "企业微信" else "邮箱"
        return value.orEmpty()
    }
}
