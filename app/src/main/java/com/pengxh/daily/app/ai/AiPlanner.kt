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

    suspend fun createPlan(
        command: String,
        stateJson: String,
        history: List<AiChatTurn> = emptyList()
    ): AiActionPlan {
        val deterministicPlan = LocalCommandPlanner.plan(command)
        val config = configStore.load()
        if (!config.isConfigured) {
            return deterministicPlan ?: error(
                "这句话需要在线 AI 理解。请先点右上角设置模型；添加任务、请假、开关随机时间等常用命令仍可离线执行。"
            )
        }
        val remotePlan = requestRemotePlan(config, command, stateJson, history)
        return mergeDeterministicRequirements(remotePlan, deterministicPlan)
    }

    suspend fun fetchAvailableModels(apiKeyOverride: String? = null): List<AiModelOption> =
        withContext(Dispatchers.IO) {
            val apiKey = apiKeyOverride?.trim().takeUnless { it.isNullOrEmpty() }
                ?: configStore.load().apiKey
            check(apiKey.isNotBlank()) { "请先填写 API Key" }
            val request = Request.Builder()
                .url("${AiConfigStore.FIXED_BASE_URL}/models")
                .header("Authorization", "Bearer $apiKey")
                .header("Accept", "application/json")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                val raw = response.body.string()
                check(response.isSuccessful) { responseError(response.code, raw) }
                parseAvailableModels(raw)
            }
        }

    private suspend fun requestRemotePlan(
        config: AiServiceConfig,
        command: String,
        stateJson: String,
        history: List<AiChatTurn>
    ): AiActionPlan = withContext(Dispatchers.IO) {
        val messages = mutableListOf<Map<String, String>>()
        messages += mapOf("role" to "system", "content" to systemPrompt(stateJson))
        history.takeLast(10).forEach { turn ->
            if (turn.role == "user" || turn.role == "assistant") {
                messages += mapOf("role" to turn.role, "content" to turn.content.take(2_000))
            }
        }
        messages += mapOf("role" to "user", "content" to command)
        val requestJson = JsonObject().apply {
            addProperty("model", config.model)
            addProperty("temperature", 0)
            addProperty("max_tokens", 4096)
            add("messages", gson.toJsonTree(messages))
        }
        val request = Request.Builder()
            .url("${config.baseUrl}/chat/completions")
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val raw = response.body.string()
            check(response.isSuccessful) { responseError(response.code, raw) }
            val root = JsonParser.parseString(raw).asJsonObject
            val contentElement = root.getAsJsonArray("choices")
                ?.firstOrNull()?.asJsonObject
                ?.getAsJsonObject("message")
                ?.get("content")
                ?: error("AI 服务没有返回可解析内容")
            val content = extractMessageContent(contentElement)
            val clean = content.trim()
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()
            gson.fromJson(extractJsonObject(clean), AiActionPlan::class.java)
                ?: error("AI 返回的操作计划为空")
        }
    }

    private fun responseError(code: Int, raw: String): String {
        val serviceMessage = runCatching {
            val root = JsonParser.parseString(raw).asJsonObject
            root.getAsJsonObject("error")?.get("message")?.asString
                ?: root.get("message")?.asString
        }.getOrNull()?.trim().orEmpty()
        return if (serviceMessage.isBlank()) {
            "AI 服务请求失败（HTTP $code）"
        } else {
            "AI 服务请求失败：$serviceMessage"
        }
    }

    private fun systemPrompt(stateJson: String) = """
        你是 DailyTask 的 AI 操作助手。你既可以自然、简洁地回答用户关于当前任务和设置的问题，也可以把自然语言转换为安全的操作计划。今天是 ${LocalDate.now()}。
        只输出一个 JSON 对象，不要 Markdown，不要解释。结构：
        {"summary":"简短中文摘要","reply":"无操作时的说明","actions":[{...}]}

        完整性是最高优先级：
        1. 先在内部逐句拆分用户的所有独立要求，再逐项生成 action，最后检查是否有遗漏；不要输出思考过程。
        2. 用户一次提到多个时间、多个任务或多个设置时，每一项都必须出现在 actions 中，不能只处理第一项。
        3. 结合最近对话理解“它、那个、再加一个、刚才第二个”等指代，但以用户最新一句为准。
        4. 不要把两个独立设置合并成一个 action，也不要因为操作类型相同而去重不同的任务。
        5. 示例：“添加 8 点、12 点、18 点三个任务并关闭随机时间”应产生 3 个 ADD_TASK 和 1 个 SET_SETTING；“周末和法定节假日不打卡”应同时产生 SET_WORKDAYS 和 SET_SETTING(skip_holiday=true)。

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
        如果用户只是提问，actions 返回空数组，并在 reply 中像正常助手一样直接回答；意图不明确时先追问，不要擅自生成更改。

        当前应用状态：$stateJson
    """.trimIndent()
}

private fun extractMessageContent(element: com.google.gson.JsonElement): String {
    if (element.isJsonPrimitive) return element.asString
    if (element.isJsonArray) {
        return element.asJsonArray.joinToString("") { part ->
            val obj = part.takeIf { it.isJsonObject }?.asJsonObject
            obj?.get("text")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        }.takeIf { it.isNotBlank() } ?: error("AI 服务返回了不支持的内容格式")
    }
    error("AI 服务返回了不支持的内容格式")
}

internal fun extractJsonObject(content: String): String {
    val start = content.indexOf('{')
    val end = content.lastIndexOf('}')
    check(start >= 0 && end > start) { "AI 返回内容中没有完整的操作计划" }
    return content.substring(start, end + 1)
}

internal fun mergeDeterministicRequirements(
    remote: AiActionPlan,
    deterministic: AiActionPlan?
): AiActionPlan {
    if (deterministic == null || deterministic.actions.isEmpty()) return remote
    val merged = remote.actions.toMutableList()
    val matchedIndexes = mutableSetOf<Int>()
    var changed = false
    deterministic.actions.forEach { required ->
        val exactIndex = merged.indices.firstOrNull { index ->
            index !in matchedIndexes && merged[index].sameRequirement(required, allowIdFallback = false)
        }
        val fallbackIndex = exactIndex ?: merged.indices.firstOrNull { index ->
            index !in matchedIndexes && merged[index].sameRequirement(required, allowIdFallback = true)
        }
        if (fallbackIndex == null) {
            merged += required
            matchedIndexes += merged.lastIndex
            changed = true
        } else {
            matchedIndexes += fallbackIndex
            val completed = merged[fallbackIndex].completeWith(required)
            if (completed != merged[fallbackIndex]) {
                merged[fallbackIndex] = completed
                changed = true
            }
        }
    }
    if (!changed) return remote
    return remote.copy(
        summary = "已完整拆解你的要求，准备执行 ${merged.size} 项操作",
        actions = merged
    )
}

private fun AiAction.sameRequirement(required: AiAction, allowIdFallback: Boolean): Boolean {
    if (!type.equals(required.type, ignoreCase = true)) return false
    return when (required.type) {
        AiActionTypes.ADD_TASK -> time == required.time
        AiActionTypes.UPDATE_TASK -> {
            time == required.time || (allowIdFallback && id != null)
        }
        AiActionTypes.DELETE_TASK -> time == required.time || (allowIdFallback && id != null)
        AiActionTypes.ADD_LEAVE -> startDate == required.startDate && endDate == required.endDate &&
                period == required.period
        AiActionTypes.CANCEL_LEAVE -> id != null || startDate == required.startDate
        AiActionTypes.SET_SETTING -> setting == required.setting
        AiActionTypes.SET_WORKDAYS -> true
        else -> true
    }
}

private fun AiAction.completeWith(required: AiAction): AiAction = when (required.type) {
    AiActionTypes.ADD_TASK -> copy(
        taskName = taskName ?: required.taskName,
        enabled = enabled ?: required.enabled
    )
    AiActionTypes.UPDATE_TASK -> copy(
        time = time ?: required.time,
        newTime = required.newTime ?: newTime,
        taskName = required.taskName ?: taskName,
        enabled = required.enabled ?: enabled
    )
    AiActionTypes.SET_SETTING -> copy(value = required.value ?: value)
    AiActionTypes.SET_WORKDAYS -> copy(workdays = required.workdays ?: workdays)
    else -> this
}

data class AiModelOption(
    val id: String,
    val owner: String,
    val supportsOpenAi: Boolean
)

internal fun parseAvailableModels(raw: String): List<AiModelOption> {
    val data = JsonParser.parseString(raw).asJsonObject.getAsJsonArray("data")
        ?: error("接口没有返回模型列表")
    return data.mapNotNull { item ->
        val model = item.asJsonObject
        val id = model.get("id")?.asString?.trim().orEmpty()
        if (id.isBlank()) return@mapNotNull null
        AiModelOption(
            id = id,
            owner = model.get("owned_by")?.asString?.trim().orEmpty(),
            supportsOpenAi = model.getAsJsonArray("supported_endpoint_types")
                ?.any { it.asString.equals("openai", ignoreCase = true) } != false
        )
    }.distinctBy { it.id }.sortedWith(
        compareByDescending<AiModelOption> { it.supportsOpenAi }
            .thenByDescending { it.id.equals("qwen3-max", ignoreCase = true) }
            .thenBy { it.id.lowercase() }
    ).also { check(it.isNotEmpty()) { "接口返回的模型列表为空" } }
}
