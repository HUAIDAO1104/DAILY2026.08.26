package com.pengxh.daily.app.remote

import android.content.Context
import com.pengxh.daily.app.ai.AiActionPlan
import com.pengxh.daily.app.ai.AiConfigStore
import com.pengxh.daily.app.ai.AiPlanner
import com.pengxh.daily.app.ai.DailyTaskOperations
import com.pengxh.daily.app.ai.LocalCommandPlanner
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 把 DT# 后的自然语言接入与应用内 AI 助手相同的校验和执行链路。
 */
internal class RemoteAiCommandHandler(
    context: Context,
    private val reply: (title: String, content: String) -> Unit
) {
    private val appContext = context.applicationContext
    private val operations = DailyTaskOperations(appContext)
    private val planner = AiPlanner(AiConfigStore(appContext))
    private val pendingStore = RemotePendingPlanStore(appContext)
    private val commandMutex = Mutex()

    suspend fun handle(command: String) = commandMutex.withLock {
        try {
            when (val control = RemoteCommandProtocol.controlCommand(command)) {
                is RemoteControlCommand.Confirm -> confirm(control.code)
                is RemoteControlCommand.Cancel -> cancel(control.code)
                null -> planAndExecute(command)
            }
        } catch (error: Exception) {
            reply(
                "远程操作未执行",
                error.message?.takeIf { it.isNotBlank() } ?: "处理失败，请稍后重试"
            )
        }
    }

    private suspend fun planAndExecute(command: String) {
        if (command == "帮助" || command == "指令帮助") {
            reply("远程控制帮助", helpText())
            return
        }

        // 高频操作优先本机解析：速度更快，也不依赖网络；识别不了再交给在线 AI。
        val localPlan = LocalCommandPlanner.plan(command)
        val plan = localPlan ?: planner.createPlan(command, operations.buildStateJson())
        if (plan.actions.isEmpty()) {
            reply(
                "AI 助手回复",
                plan.reply.ifBlank { "我还不能确定你想做什么，请换一种更具体的说法。" }
            )
            return
        }

        val validated = operations.validate(plan)
        if (validated.requiresDangerConfirmation) {
            val pending = pendingStore.save(command, validated)
            reply(
                "远程操作待确认",
                buildString {
                    appendLine(validated.summary)
                    validated.previews.forEachIndexed { index, preview ->
                        appendLine("${index + 1}. $preview")
                    }
                    appendLine()
                    appendLine("包含删除、销假或恢复操作，尚未执行。")
                    appendLine("5 分钟内回复：DT#确认 ${pending.code}")
                    append("放弃请回复：DT#取消 ${pending.code}")
                }
            )
            return
        }

        execute(validated.summary, validated.previews) {
            operations.execute(validated)
        }
    }

    private suspend fun confirm(code: String) {
        val pending = pendingStore.take(code)
        if (pending == null) {
            reply("确认失败", "确认码不正确、已使用或已超过 5 分钟，请重新发送原指令。")
            return
        }

        // 确认期间应用状态可能已经变化，执行前按当前状态重新校验一次。
        val validated = operations.validate(
            AiActionPlan(summary = pending.summary, actions = pending.actions)
        )
        execute(validated.summary, validated.previews) {
            operations.execute(validated)
        }
    }

    private fun cancel(code: String) {
        if (pendingStore.cancel(code)) {
            reply("远程操作已取消", "没有修改任何任务或设置。")
        } else {
            reply("取消失败", "确认码不正确、已使用或已超过 5 分钟。")
        }
    }

    private suspend fun execute(
        summary: String,
        previews: List<String>,
        action: suspend () -> List<String>
    ) {
        try {
            val results = action()
            reply(
                "远程操作完成",
                buildString {
                    appendLine(summary)
                    results.forEachIndexed { index, result -> appendLine("✓ ${index + 1}. $result") }
                    append("修改前的配置已自动保存在本机快照中。")
                }
            )
        } catch (error: Exception) {
            reply(
                "远程操作执行中止",
                buildString {
                    appendLine(error.message ?: "未知错误")
                    if (previews.isNotEmpty()) {
                        appendLine("原计划：")
                        previews.forEachIndexed { index, preview -> appendLine("${index + 1}. $preview") }
                    }
                    append("部分操作可能已经生效，可在配置备份中恢复。")
                }
            )
        }
    }

    private fun helpText() = """
        只需发送“DT#”加自然语言，例如：
        • DT#把 8 点任务改到 8 点半
        • DT#明天下午请假，因为外出
        • DT#取消明天的请假
        • DT#周末和法定节假日不打卡
        • DT#关闭随机时间，超时改为 45 秒
        • DT#添加上午 9 点和下午 6 点两个任务
        • DT#先暂停任务，把 8 点任务改到 8 点半，然后启动任务

        删除任务、销假和恢复备份会返回 4 位确认码；其他安全修改会在自动备份后直接执行。
        原有固定口令仍可使用：执行任务、终止任务、状态查询、考勤记录、息屏、亮屏、截屏。
    """.trimIndent()
}
