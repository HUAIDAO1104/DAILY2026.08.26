package com.pengxh.daily.app.ai

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

object LocalCommandPlanner {
    private val outputTime = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun plan(command: String, today: LocalDate = LocalDate.now()): AiActionPlan? {
        val text = command.trim().replace("，", ",").replace("。", "")
        if (text.isBlank()) return null
        val actions = mutableListOf<AiAction>()

        // 任务列表操作
        val times = extractTimes(text)
        when {
            (containsAny(text, "停用任务", "关闭任务") ||
                    (text.contains("任务") && text.contains("停用"))) && times.isNotEmpty() -> {
                times.forEach { actions += AiAction(type = AiActionTypes.UPDATE_TASK, time = it, enabled = false) }
            }
            (containsAny(text, "启用任务", "打开任务") ||
                    (text.contains("任务") && text.contains("启用"))) && times.isNotEmpty() -> {
                times.forEach { actions += AiAction(type = AiActionTypes.UPDATE_TASK, time = it, enabled = true) }
            }
            (text.contains("任务") && containsAny(text, "改名为", "重命名为")) && times.size == 1 -> {
                val name = Regex("(?:改名为|重命名为)\\s*([^,，。]+)").find(text)?.groupValues?.get(1)?.trim()
                if (!name.isNullOrBlank()) actions += AiAction(type = AiActionTypes.UPDATE_TASK, time = times.first(), taskName = name)
            }
            (containsAny(text, "修改任务", "改任务", "改到", "改成", "调整到") ||
                    (text.contains("任务") && text.contains("改"))) && times.size == 2 -> {
                actions += AiAction(
                    type = AiActionTypes.UPDATE_TASK,
                    time = times[0],
                    newTime = times[1]
                )
            }
            (containsAny(text, "删除任务", "删掉任务", "取消任务", "移除任务") ||
                    (text.contains("任务") && containsAny(text, "删除", "删掉", "移除"))) && times.isNotEmpty() -> {
                times.forEach { actions += AiAction(type = AiActionTypes.DELETE_TASK, time = it) }
            }
            (containsAny(text, "添加任务", "新增任务", "加一个任务", "安排打卡", "添加打卡") ||
                    (text.contains("任务") && containsAny(text, "添加", "新增"))) && times.isNotEmpty() -> {
                val taskName = Regex("(?:任务)?(?:叫|命名为|名称(?:设置)?为)\\s*([^,，。]+)")
                    .find(text)?.groupValues?.get(1)?.trim()?.take(30)
                times.forEach {
                    actions += AiAction(type = AiActionTypes.ADD_TASK, time = it, taskName = taskName)
                }
            }
        }

        // 一次性请假 / 销假。允许“取消明天的请假”等自然语序。
        val cancelLeave = text.contains("销假") ||
                Regex("(?:取消|删除|撤销).{0,12}请假").containsMatchIn(text) ||
                Regex("请假.{0,12}(?:取消|删除|撤销)").containsMatchIn(text)
        if (text.contains("请假") && !cancelLeave) {
            val dates = extractDateRange(text, today)
            val period = when {
                text.contains("上午") || text.contains("早上") -> "MORNING"
                text.contains("下午") || text.contains("晚上") -> "AFTERNOON"
                else -> "ALL_DAY"
            }
            actions += AiAction(
                type = AiActionTypes.ADD_LEAVE,
                startDate = dates.first.toString(),
                endDate = dates.second.toString(),
                period = period,
                reason = extractReason(text)
            )
        } else if (cancelLeave && !containsAny(text, "上午", "早上", "下午", "晚上")) {
            val date = extractDateRange(text, today).first
            actions += AiAction(type = AiActionTypes.CANCEL_LEAVE, startDate = date.toString())
        }

        // 工作日与节假日
        if (containsAny(text, "周末不打卡", "周六周日不打卡", "只在工作日打卡", "双休") ||
            (text.contains("周末") && text.contains("不打卡"))) {
            actions += AiAction(type = AiActionTypes.SET_WORKDAYS, workdays = listOf(1, 2, 3, 4, 5))
        } else if (containsAny(text, "每天都打卡", "一周七天打卡")) {
            actions += AiAction(type = AiActionTypes.SET_WORKDAYS, workdays = (1..7).toList())
        }
        when {
            containsAny(text, "节假日不打卡", "法定节假日跳过", "节假日休息") ->
                actions += setting("skip_holiday", "true")
            containsAny(text, "节假日也打卡", "节假日不跳过") ->
                actions += setting("skip_holiday", "false")
        }

        // 常用设置
        when {
            containsAny(text, "关闭随机", "不要随机时间", "取消随机") -> actions += setting("random_enabled", "false")
            containsAny(text, "开启随机", "打开随机时间") -> actions += setting("random_enabled", "true")
        }
        Regex("随机(?:时间|范围)?(?:改成|设置为|设为)?\\s*(\\d{1,3})\\s*分钟").find(text)?.let {
            actions += setting("random_minutes", it.groupValues[1])
        }
        Regex("(?:超时|停留)(?:时间)?(?:改成|设置为|设为)?\\s*(\\d{1,4})\\s*秒").find(text)?.let {
            actions += setting("timeout_seconds", it.groupValues[1])
        }
        Regex("(?:每天)?(\\d{1,2})点(?:重置|刷新)").find(text)?.let {
            actions += setting("reset_hour", it.groupValues[1])
        }
        when {
            containsAny(text, "开启省电", "打开省电") -> actions += setting("power_save", "true")
            containsAny(text, "关闭省电") -> actions += setting("power_save", "false")
        }
        when {
            containsAny(text, "开启自动循环", "打开自动循环", "每天自动执行") -> actions += setting("auto_recycle", "true")
            containsAny(text, "关闭自动循环", "停止自动循环") -> actions += setting("auto_recycle", "false")
        }
        when {
            containsAny(text, "开启手势", "打开手势") -> actions += setting("gesture_enabled", "true")
            containsAny(text, "关闭手势") -> actions += setting("gesture_enabled", "false")
        }
        when {
            containsAny(text, "打卡后返回桌面", "开启返回桌面") -> actions += setting("back_home", "true")
            containsAny(text, "打卡后不要返回桌面", "关闭返回桌面") -> actions += setting("back_home", "false")
        }
        when {
            containsAny(text, "远程打卡返回截图", "开启远程截图") -> actions += setting("remote_capture", "true")
            containsAny(text, "关闭远程截图", "远程打卡不返回截图") -> actions += setting("remote_capture", "false")
        }
        Regex("目标应用(?:改成|设置为|设为|用)\\s*(钉钉|企业微信|飞书|移动办公M3|移动办公m3)").find(text)?.let {
            actions += setting("target_app", it.groupValues[1])
        }
        when {
            containsAny(text, "用截图判断结果", "结果来源改为截图", "结果来源用截图") -> actions += setting("result_source", "截图")
            containsAny(text, "用通知判断结果", "结果来源改为通知", "结果来源用通知") -> actions += setting("result_source", "通知")
        }
        when {
            containsAny(text, "消息渠道改为邮箱", "消息渠道用邮箱", "使用QQ邮箱通知") -> actions += setting("message_channel", "邮箱")
            containsAny(text, "消息渠道改为企业微信", "消息渠道用企业微信", "使用企业微信通知") -> actions += setting("message_channel", "企业微信")
        }
        Regex("远程口令(?:改成|设置为|设为)\\s*([^,，。]+)").find(text)?.let {
            actions += setting("remote_command", it.groupValues[1].trim())
        }
        Regex("消息标题(?:改成|设置为|设为)\\s*([^,，。]+)").find(text)?.let {
            actions += setting("message_title", it.groupValues[1].trim())
        }
        if (containsAny(text, "停止任务", "暂停任务", "停止打卡")) {
            actions += AiAction(type = AiActionTypes.STOP_SCHEDULER)
        }
        if (containsAny(text, "启动任务", "开始任务", "开始打卡")) {
            actions += AiAction(type = AiActionTypes.START_SCHEDULER)
        }
        when {
            containsAny(text, "恢复配置", "恢复备份") -> actions += AiAction(type = AiActionTypes.RESTORE_LATEST_SNAPSHOT)
            containsAny(text, "备份配置", "保存配置", "创建快照") -> actions += AiAction(type = AiActionTypes.CREATE_SNAPSHOT, reason = "AI 手动备份")
        }

        val distinct = actions.distinct()
        // 复合指令必须先停调度器、再修改任务，最后按需重新启动。
        val ordered = distinct.filter { it.type == AiActionTypes.STOP_SCHEDULER } +
                distinct.filter {
                    it.type != AiActionTypes.STOP_SCHEDULER &&
                            it.type != AiActionTypes.START_SCHEDULER
                } + distinct.filter { it.type == AiActionTypes.START_SCHEDULER }
        if (ordered.isEmpty()) return null
        return AiActionPlan(
            summary = "已理解你的要求，准备执行 ${ordered.size} 项更改",
            actions = ordered
        )
    }

    private fun setting(name: String, value: String) = AiAction(
        type = AiActionTypes.SET_SETTING,
        setting = name,
        value = value
    )

    private fun extractTimes(text: String): List<String> {
        val found = mutableListOf<Pair<Int, String>>()
        Regex("(?<!\\d)([01]?\\d|2[0-3])[:：]([0-5]\\d)(?:[:：]([0-5]\\d))?").findAll(text).forEach {
            val hour = it.groupValues[1].toInt()
            val minute = it.groupValues[2].toInt()
            val second = it.groupValues[3].toIntOrNull() ?: 0
            found += it.range.first to LocalTime.of(hour, minute, second).format(outputTime)
        }
        Regex("(上午|早上|下午|晚上)?\\s*(\\d{1,2})\\s*点(?:(半)|(\\d{1,2})\\s*分?)?").findAll(text).forEach {
            val marker = it.groupValues[1]
            var hour = it.groupValues[2].toInt()
            val minute = if (it.groupValues[3] == "半") 30 else it.groupValues[4].toIntOrNull() ?: 0
            if ((marker == "下午" || marker == "晚上") && hour in 1..11) hour += 12
            if ((marker == "上午" || marker == "早上") && hour == 12) hour = 0
            if (hour in 0..23 && minute in 0..59) {
                found += it.range.first to LocalTime.of(hour, minute).format(outputTime)
            }
        }
        return found.sortedBy { it.first }.map { it.second }.distinct()
    }

    private fun extractDateRange(text: String, today: LocalDate): Pair<LocalDate, LocalDate> {
        val mentions = mutableListOf<Pair<Int, LocalDate>>()
        Regex("(?:(\\d{4})[-/.年])?(\\d{1,2})[-/.月](\\d{1,2})(?:日|号)?").findAll(text)
            .forEach {
                val year = it.groupValues[1].toIntOrNull() ?: today.year
                runCatching {
                    LocalDate.of(year, it.groupValues[2].toInt(), it.groupValues[3].toInt())
                }.getOrNull()?.let { date -> mentions += it.range.first to date }
            }

        Regex("后天|明天|今天|今日").findAll(text).forEach {
            val date = when (it.value) {
                "后天" -> today.plusDays(2)
                "明天" -> today.plusDays(1)
                else -> today
            }
            mentions += it.range.first to date
        }

        var inheritedWeekMarker = ""
        Regex("(下|本|这)?(?:周|星期)([一二三四五六日天])").findAll(text).forEach { match ->
            val explicitMarker = match.groupValues[1]
            if (explicitMarker.isNotBlank()) inheritedWeekMarker = explicitMarker
            val marker = explicitMarker.ifBlank { inheritedWeekMarker }
            val value = "一二三四五六日天".indexOf(match.groupValues[2])
                .let { if (it >= 7) 7 else it + 1 }
            val day = DayOfWeek.of(value)
            val date = when (marker) {
                "下" -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    .plusWeeks(1).with(TemporalAdjusters.nextOrSame(day))
                "本", "这" -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    .with(TemporalAdjusters.nextOrSame(day))
                else -> today.with(TemporalAdjusters.nextOrSame(day))
            }
            mentions += match.range.first to date
        }

        val dates = mentions.sortedBy { it.first }.map { it.second }
        if (dates.isEmpty()) return today to today
        return dates.first() to dates.last()
    }

    private fun extractReason(text: String): String {
        return Regex("(?:因为|原因是)([^,，。]+)").find(text)?.groupValues?.get(1)?.take(40) ?: "请假"
    }

    private fun containsAny(text: String, vararg values: String) = values.any(text::contains)
}
