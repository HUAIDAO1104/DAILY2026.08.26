package com.pengxh.daily.app.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteCommandProtocolTest {
    @Test
    fun extractsLatestCommandFromAggregatedNotification() {
        val body = RemoteCommandProtocol.extractBody(
            "[2条]某人: DT#状态查询\n某人: DT#明天下午请假"
        )

        assertEquals("明天下午请假", body)
    }

    @Test
    fun ignoresMessagesWithoutPrefixOrBody() {
        assertNull(RemoteCommandProtocol.extractBody("普通聊天消息"))
        assertNull(RemoteCommandProtocol.extractBody("某人: DT#   "))
    }

    @Test
    fun onlyTreatsExactFourDigitConfirmationAsControlCommand() {
        assertEquals(
            RemoteControlCommand.Confirm("4821"),
            RemoteCommandProtocol.controlCommand("确认 4821")
        )
        assertEquals(
            RemoteControlCommand.Cancel("4821"),
            RemoteCommandProtocol.controlCommand("放弃4821")
        )
        assertNull(RemoteCommandProtocol.controlCommand("取消明天的请假"))
        assertNull(RemoteCommandProtocol.controlCommand("确认 12345"))
    }

    @Test
    fun deduplicatesRepeatedCallbacksButAllowsIntentionalRetry() {
        val deduplicator = RemoteCommandDeduplicator(
            notificationWindowMillis = 120_000,
            contentWindowMillis = 3_000
        )

        assertTrue(deduplicator.shouldProcess("key-1", "明天下午请假", 10_000))
        assertFalse(deduplicator.shouldProcess("key-1", "明天下午请假", 11_000))
        assertFalse(deduplicator.shouldProcess("key-2", "明天下午请假", 12_000))
        assertTrue(deduplicator.shouldProcess("key-2", "明天下午请假", 14_001))
    }

    @Test
    fun pendingPlanExpiryUsesExactDeadline() {
        val pending = RemotePendingPlan(
            code = "4821",
            sourceCommand = "删除8点任务",
            summary = "删除任务",
            actions = emptyList(),
            previews = emptyList(),
            createdAt = 1_000,
            expiresAt = 6_000
        )

        assertFalse(pending.isExpired(5_999))
        assertTrue(pending.isExpired(6_000))
    }
}
