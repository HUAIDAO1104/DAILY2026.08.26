package com.pengxh.daily.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiPlannerCompletenessTest {

    @Test
    fun mergesTasksAndSettingsOmittedByRemoteModel() {
        val remote = AiActionPlan(
            summary = "添加任务",
            actions = listOf(AiAction(type = AiActionTypes.ADD_TASK, time = "08:00:00"))
        )
        val deterministic = LocalCommandPlanner.plan(
            "添加8点、12点、18点三个任务，并关闭随机时间"
        )!!

        val merged = mergeDeterministicRequirements(remote, deterministic)

        assertEquals(4, merged.actions.size)
        assertEquals(
            setOf("08:00:00", "12:00:00", "18:00:00"),
            merged.actions.filter { it.type == AiActionTypes.ADD_TASK }.mapNotNull { it.time }.toSet()
        )
        assertTrue(merged.actions.any {
            it.type == AiActionTypes.SET_SETTING &&
                    it.setting == "random_enabled" && it.value == "false"
        })
    }

    @Test
    fun matchesEachIdBasedUpdateOnlyOnceAndCompletesExplicitValues() {
        val remote = AiActionPlan(
            actions = listOf(
                AiAction(type = AiActionTypes.UPDATE_TASK, id = 11, enabled = false),
                AiAction(type = AiActionTypes.UPDATE_TASK, id = 12, enabled = false)
            )
        )
        val deterministic = LocalCommandPlanner.plan("停用8点和9点任务")!!

        val merged = mergeDeterministicRequirements(remote, deterministic)

        assertEquals(2, merged.actions.size)
        assertEquals(setOf("08:00:00", "09:00:00"), merged.actions.mapNotNull { it.time }.toSet())
        assertTrue(merged.actions.all { it.enabled == false })
    }

    @Test
    fun deterministicSettingCorrectsRemoteValue() {
        val remote = AiActionPlan(
            actions = listOf(
                AiAction(
                    type = AiActionTypes.SET_SETTING,
                    setting = "random_enabled",
                    value = "true"
                )
            )
        )
        val deterministic = LocalCommandPlanner.plan("关闭随机时间")!!

        val merged = mergeDeterministicRequirements(remote, deterministic)

        assertEquals("false", merged.actions.single().value)
    }

    @Test
    fun extractsJsonWhenModelAddsThinkingTextOrCodeFence() {
        val content = """
            <think>我需要完整处理三项要求</think>
            ```json
            {"summary":"完成","reply":"","actions":[]}
            ```
        """.trimIndent()

        assertEquals(
            "{\"summary\":\"完成\",\"reply\":\"\",\"actions\":[]}",
            extractJsonObject(content)
        )
    }
}
