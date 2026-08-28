package com.pengxh.daily.app.remote

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.pengxh.daily.app.ai.AiAction
import com.pengxh.daily.app.ai.ValidatedAiPlan
import java.security.SecureRandom

internal data class RemotePendingPlan(
    val code: String,
    val sourceCommand: String,
    val summary: String,
    val actions: List<AiAction>,
    val previews: List<String>,
    val createdAt: Long,
    val expiresAt: Long
) {
    fun isExpired(nowMillis: Long): Boolean = nowMillis >= expiresAt
}

/**
 * 保存少量待确认计划。使用独立 SharedPreferences，避免把短期确认码带进配置快照。
 */
internal class RemotePendingPlanStore(
    context: Context,
    private val nowProvider: () -> Long = System::currentTimeMillis
) {
    companion object {
        const val EXPIRY_MILLIS = 5 * 60 * 1_000L
        private const val PREFERENCE_NAME = "remote_ai_pending_plan"
        private const val PAYLOAD_KEY = "payload"
    }

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCE_NAME,
        Context.MODE_PRIVATE
    )
    private val gson = Gson()
    private val random = SecureRandom()

    @Synchronized
    fun save(sourceCommand: String, plan: ValidatedAiPlan): RemotePendingPlan {
        val now = nowProvider()
        val current = loadAllValid()
        var code: String
        do {
            code = (1_000 + random.nextInt(9_000)).toString()
        } while (current.any { it.code == code })
        val pending = RemotePendingPlan(
            code = code,
            sourceCommand = sourceCommand.take(500),
            summary = plan.summary,
            actions = plan.actions,
            previews = plan.previews,
            createdAt = now,
            expiresAt = now + EXPIRY_MILLIS
        )
        current += pending
        persist(current.takeLast(5))
        return pending
    }

    @Synchronized
    fun take(code: String): RemotePendingPlan? {
        val current = loadAllValid()
        val pending = current.firstOrNull { it.code == code } ?: return null
        persist(current.filterNot { it.code == code })
        return pending
    }

    @Synchronized
    fun cancel(code: String): Boolean {
        val current = loadAllValid()
        if (current.none { it.code == code }) return false
        persist(current.filterNot { it.code == code })
        return true
    }

    private fun loadAllValid(): MutableList<RemotePendingPlan> {
        val raw = preferences.getString(PAYLOAD_KEY, null) ?: return mutableListOf()
        val type = object : TypeToken<List<RemotePendingPlan>>() {}.type
        val parsed = runCatching {
            gson.fromJson<List<RemotePendingPlan>>(raw, type)
        }.getOrNull().orEmpty()
        val valid = parsed.filterNot { it.isExpired(nowProvider()) }.toMutableList()
        if (valid.size != parsed.size) persist(valid)
        return valid
    }

    private fun persist(plans: List<RemotePendingPlan>) {
        val editor = preferences.edit()
        if (plans.isEmpty()) {
            editor.remove(PAYLOAD_KEY)
        } else {
            editor.putString(PAYLOAD_KEY, gson.toJson(plans))
        }
        check(editor.commit()) { "确认计划保存失败" }
    }
}
