package com.pengxh.daily.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.pengxh.daily.app.service.ForegroundRunningService
import com.pengxh.daily.app.utils.LogFileManager
import com.pengxh.daily.app.utils.TaskScheduler

/**
 * 在设备重启或应用原位更新后，恢复用户明确开启的任务守护服务。
 */
class TaskRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!TaskScheduler.isDesiredRunning()) {
            LogFileManager.writeLog("收到 ${intent.action}，任务已暂停，不恢复服务")
            return
        }

        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ForegroundRunningService::class.java)
            )
        }.onSuccess {
            LogFileManager.writeLog("收到 ${intent.action}，已请求恢复任务服务")
        }.onFailure {
            LogFileManager.writeLog("恢复任务服务失败：${it.message ?: it.javaClass.simpleName}")
        }
    }
}
