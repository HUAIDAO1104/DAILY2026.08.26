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

    @Test
    fun cancelsLeaveWithNaturalWordOrder() {
        val action = LocalCommandPlanner.plan("取消明天的请假", today)!!.actions.single()

        assertEquals(AiActionTypes.CANCEL_LEAVE, action.type)
        assertEquals("2026-08-27", action.startDate)
    }

    @Test
    fun recognizesShortChineseLeaveCancellation() {
        val action = LocalCommandPlanner.plan("明天销假", today)!!.actions.single()

        assertEquals(AiActionTypes.CANCEL_LEAVE, action.type)
        assertEquals("2026-08-27", action.startDate)
    }

    @Test
    fun plansRelativeLeaveDateRangeInMentionOrder() {
        val action = LocalCommandPlanner.plan("明天到后天请假", today)!!.actions.single()

        assertEquals("2026-08-27", action.startDate)
        assertEquals("2026-08-28", action.endDate)
    }

    @Test
    fun plansInheritedNextWeekDateRange() {
        val action = LocalCommandPlanner.plan("下周一到周三请假", today)!!.actions.single()

        assertEquals("2026-08-31", action.startDate)
        assertEquals("2026-09-02", action.endDate)
    }

    @Test
    fun acceptsChineseDateSuffix() {
        val action = LocalCommandPlanner.plan("8月29号请假", today)!!.actions.single()

        assertEquals("2026-08-29", action.startDate)
    }

    @Test
    fun delegatesPeriodSpecificLeaveCancellationToOnlineAi() {
        assertNull(LocalCommandPlanner.plan("取消明天上午的请假", today))
    }

    @Test
    fun delegatesAmbiguousMultiTaskTimeChangeToOnlineAi() {
        assertNull(LocalCommandPlanner.plan("把8点和9点任务都改到10点", today))
    }

    @Test
    fun addsEveryTimeMentionedInOneCommand() {
        val actions = LocalCommandPlanner.plan("添加上午9点和下午6点两个任务", today)!!.actions

        assertEquals(2, actions.size)
        assertEquals(listOf("09:00:00", "18:00:00"), actions.map { it.time })
    }

    @Test
    fun ordersStopBeforeTaskChangeAndRestartAfterwards() {
        val actions = LocalCommandPlanner.plan(
            "先暂停任务，把8点任务改到8点半，然后启动任务",
            today
        )!!.actions

        assertEquals(
            listOf(
                AiActionTypes.STOP_SCHEDULER,
                AiActionTypes.UPDATE_TASK,
                AiActionTypes.START_SCHEDULER
            ),
            actions.map { it.type }
        )
    }
}
