package com.pengxh.daily.app.remote

import com.pengxh.daily.app.utils.Constant

internal sealed interface RemoteControlCommand {
    data class Confirm(val code: String) : RemoteControlCommand
    data class Cancel(val code: String) : RemoteControlCommand
}

/**
 * 远程消息协议只保留一个必须记忆的前缀，前缀之后既可以是旧口令，也可以是自然语言。
 */
internal object RemoteCommandProtocol {
    private val confirmPattern = Regex("^确认\\s*([0-9]{4})$")
    private val cancelPattern = Regex("^(?:取消|放弃)\\s*([0-9]{4})$")

    fun extractBody(notice: String): String? {
        // 聚合通知通常把最新消息放在最后，因此优先取最后一个 DT#。
        val prefixIndex = notice.lastIndexOf(Constant.COMMAND_PREFIX)
        if (prefixIndex < 0) return null
        return notice.substring(prefixIndex + Constant.COMMAND_PREFIX.length)
            .lineSequence()
            .firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    fun controlCommand(body: String): RemoteControlCommand? {
        confirmPattern.matchEntire(body.trim())?.let {
            return RemoteControlCommand.Confirm(it.groupValues[1])
        }
        cancelPattern.matchEntire(body.trim())?.let {
            return RemoteControlCommand.Cancel(it.groupValues[1])
        }
        return null
    }
}

/**
 * Android 可能针对同一条消息多次回调 onNotificationPosted。
 * 同一通知在较长窗口内只执行一次，同时拦截几秒内不同通知 key 的重复内容。
 */
internal class RemoteCommandDeduplicator(
    private val notificationWindowMillis: Long = 120_000L,
    private val contentWindowMillis: Long = 3_000L,
    private val maxEntries: Int = 96
) {
    private val notificationHistory = linkedMapOf<String, Long>()
    private val contentHistory = linkedMapOf<String, Long>()

    @Synchronized
    fun shouldProcess(
        notificationKey: String,
        body: String,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        prune(notificationHistory, nowMillis - notificationWindowMillis)
        prune(contentHistory, nowMillis - contentWindowMillis)

        val normalizedBody = body.trim()
        val notificationFingerprint = "$notificationKey|$normalizedBody"
        if (notificationHistory.containsKey(notificationFingerprint) ||
            contentHistory.containsKey(normalizedBody)
        ) {
            return false
        }

        notificationHistory[notificationFingerprint] = nowMillis
        contentHistory[normalizedBody] = nowMillis
        trimToLimit(notificationHistory)
        trimToLimit(contentHistory)
        return true
    }

    private fun prune(history: MutableMap<String, Long>, threshold: Long) {
        history.entries.removeAll { it.value < threshold }
    }

    private fun trimToLimit(history: LinkedHashMap<String, Long>) {
        while (history.size > maxEntries) {
            val oldestKey = history.keys.firstOrNull() ?: return
            history.remove(oldestKey)
        }
    }
}
