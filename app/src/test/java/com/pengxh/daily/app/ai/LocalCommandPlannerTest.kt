package com.pengxh.daily.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class LocalCommandPlannerTest {
    private val today = LocalDate.of(2026, 8, 26)

    @Test
    fun modifiesTaskTimeWithChineseHalfHour() {
        val plan = LocalCommandPlanner.plan("把8点任务改到8点半", today)

        assertNotNull(plan)
        assertEquals(1, plan!!.actions.size)
        assertEquals(AiActionTypes.UPDATE_TASK, plan.actions[0].type)
        assertEquals("08:00:00", plan.actions[0].time)
        assertEquals("08:30:00", plan.actions[0].newTime)
    }

    @Test
    fun plansWeekendAndHolidaySkippingTogether() {
        val plan = LocalCommandPlanner.plan("周末和法定节假日不打卡", today)

        assertNotNull(plan)
        assertEquals(2, plan!!.actions.size)
        assertEquals(listOf(1, 2, 3, 4, 5), plan.actions[0].workdays)
        assertEquals("skip_holiday", plan.actions[1].setting)
        assertEquals("true", plan.actions[1].value)
    }

    @Test
    fun plansTomorrowAfternoonLeave() {
        val plan = LocalCommandPlanner.plan("明天下午请假，因为外出办事", today)

        assertNotNull(plan)
        val action = plan!!.actions.single()
        assertEquals(AiActionTypes.ADD_LEAVE, action.type)
        assertEquals("2026-08-27", action.startDate)
        assertEquals("AFTERNOON", action.period)
        assertEquals("外出办事", action.reason)
    }

    @Test
    fun ignoresOrdinaryQuestionOffline() {
        assertNull(LocalCommandPlanner.plan("今天状态怎么样", today))
    }

    @Test
    fun addsNamedTaskOffline() {
        val action = LocalCommandPlanner.plan("添加上午9点任务叫上班打卡", today)!!.actions.single()

        assertEquals(AiActionTypes.ADD_TASK, action.type)
        assertEquals("09:00:00", action.time)
        assertEquals("上班打卡", action.taskName)
    }

    @Test
    fun changesTargetAppAndResultSourceOffline() {
        val actions = LocalCommandPlanner.plan("目标应用改成飞书，结果来源用截图", today)!!.actions

        assertEquals(2, actions.size)
        assertEquals("target_app", actions[0].setting)
        assertEquals("飞书", actions[0].value)
        assertEquals("result_source", actions[1].setting)
        assertEquals("截图", actions[1].value)
    }
}
