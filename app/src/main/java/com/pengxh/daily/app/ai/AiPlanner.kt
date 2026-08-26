package com.pengxh.daily.app.ai

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.LocalDate
import java.util.concurrent.TimeUnit

class AiPlanner(private val configStore: AiConfigStore) {
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun createPlan(command: String, stateJson: String): AiActionPlan {
        LocalCommandPlanner.plan(command)?.let { return it }
        val config = configStore.load()
        check(config.isConfigured) {
            "这句话需要在线 AI 理解。请先点右上角设置模型；添加任务、请假、开关随机时间等常用命令可直接离线执行。"
        }
        return requestRemotePlan(config, command, stateJson)
    }

    private suspend fun requestRemotePlan(
        config: AiServiceConfig,
        command: String,
        stateJson: String
    ): AiActionPlan = withContext(Dispatchers.IO) {
        val requestJson = JsonObject().apply {
            addProperty("model", config.model)
            addProperty("temperature", 0)
            add("messages", gson.toJsonTree(listOf(
                mapOf("role" to "system", "content" to systemPrompt(stateJson)),
                mapOf("role" to "user", "content" to command)
            )))
        }
        val request = Request.Builder()
            .url("${config.baseUrl}/chat/completions")
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val raw = response.body.string()
            check(response.isSuccessful) { "AI 服务请求失败（HTTP ${response.code}）" }
            val root = JsonParser.parseString(raw).asJsonObject
            val content = root.getAsJsonArray("choices")
                ?.firstOrNull()?.asJsonObject
                ?.getAsJsonObject("message")
                ?.get("content")?.asString
                ?: error("AI 服务没有返回可解析内容")
            val clean = content.trim()
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()
            gson.fromJson(clean, AiActionPlan::class.java) ?: error("AI 返回的操作计划为空")
        }
    }

    private fun systemPrompt(stateJson: String) = """
        你是 DailyTask 的操作规划器，不是聊天机器人。今天是 ${LocalDate.now()}。
        只输出一个 JSON 对象，不要 Markdown，不要解释。结构：
        {"summary":"简短中文摘要","reply":"无操作时的说明","actions":[{...}]}

        action.type 仅允许：
        ADD_TASK(time,taskName?,enabled?), UPDATE_TASK(id 或 time,newTime?,taskName?,enabled?), DELETE_TASK(id 或 time),
        ADD_LEAVE(startDate,endDate,period,reason), CANCEL_LEAVE(id 或 startDate),
        SET_SETTING(setting,value), SET_WORKDAYS(workdays), START_SCHEDULER,
        STOP_SCHEDULER, CREATE_SNAPSHOT(reason), RESTORE_LATEST_SNAPSHOT。

        时间必须是 HH:mm:ss，日期必须是 yyyy-MM-dd。period 仅 ALL_DAY/MORNING/AFTERNOON。
        setting 仅：reset_hour, timeout_seconds, random_enabled, random_minutes,
        skip_holiday, auto_recycle, power_save, back_home, gesture_enabled,
        target_app, result_source, message_channel, message_title, remote_command,
        remote_capture。布尔值使用 true/false。
        workdays 用 1=周一 到 7=周日的数字数组。
        UPDATE_TASK 至少修改时间、名称或启用状态之一。修改或删除任务时优先使用状态中的 id。不要猜不存在的数据。
        如果用户只是提问或意图不明确，actions 返回空数组并在 reply 里解释，不要生成更改。

        当前应用状态：$stateJson
    """.trimIndent()
}
