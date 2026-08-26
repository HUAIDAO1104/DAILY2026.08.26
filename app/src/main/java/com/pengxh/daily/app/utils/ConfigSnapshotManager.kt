package com.pengxh.daily.app.utils

import android.content.Context
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.pengxh.daily.app.BuildConfig
import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.daily.app.sqlite.bean.DailyTaskBean
import com.pengxh.daily.app.sqlite.bean.LeaveRecordBean
import com.pengxh.kt.lite.utils.SaveKeyValues
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ConfigSnapshot(
    val createdAt: Long,
    val sourceVersion: String,
    val reason: String,
    val intSettings: Map<String, Int>,
    val booleanSettings: Map<String, Boolean>,
    val stringSettings: Map<String, String>,
    val fileSettings: Map<String, Map<String, Any>>,
    val tasks: List<DailyTaskBean>,
    val leaves: List<LeaveRecordBean>
)

object ConfigSnapshotManager {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val intDefaults = linkedMapOf(
        Constant.RESET_TIME_KEY to Constant.DEFAULT_RESET_HOUR,
        Constant.STAY_OVERTIME_KEY to Constant.DEFAULT_OVER_TIME,
        Constant.TIME_RANGE_KEY to Constant.DEFAULT_TIME_RANGE,
        Constant.MSG_CHANNEL_KEY to Constant.DEFAULT_INDEX,
        Constant.TARGET_APP_KEY to 0,
        Constant.RESULT_SOURCE_KEY to Constant.DEFAULT_INDEX
    )
    private val booleanDefaults = linkedMapOf(
        Constant.GESTURE_DETECTOR_KEY to true,
        Constant.BACK_TO_HOME_KEY to false,
        Constant.TASK_AUTO_RECYCLE_KEY to true,
        Constant.RANDOM_TIME_KEY to true,
        Constant.SKIP_HOLIDAY_KEY to true,
        Constant.POWER_SAVE_MODE_KEY to false,
        Constant.REMOTE_CLOCK_IN_CAPTURE_KEY to false
    )
    private val stringDefaults = linkedMapOf(
        Constant.REMOTE_COMMAND_KEY to "打卡",
        Constant.MESSAGE_TITLE_KEY to "打卡结果通知",
        Constant.WX_WEB_HOOK_KEY to "",
        Constant.CUSTOM_WORKDAYS_KEY to "1,2,3,4,5",
        Constant.AI_BASE_URL_KEY to "",
        Constant.AI_MODEL_KEY to ""
    )

    suspend fun create(context: Context, reason: String): File = withContext(Dispatchers.IO) {
        val snapshot = ConfigSnapshot(
            createdAt = System.currentTimeMillis(),
            sourceVersion = BuildConfig.VERSION_NAME,
            reason = reason.take(80),
            intSettings = intDefaults.mapValues { (key, default) ->
                SaveKeyValues.loadInt(key, default)
            },
            booleanSettings = booleanDefaults.mapValues { (key, default) ->
                SaveKeyValues.loadBoolean(key, default)
            },
            stringSettings = stringDefaults.mapValues { (key, default) ->
                SaveKeyValues.loadString(key, default)
            },
            fileSettings = ConfigStore.get().loadAll().mapValues { (_, value) ->
                gson.fromJson<Map<String, Any>>(
                    value,
                    object : TypeToken<Map<String, Any>>() {}.type
                )
            },
            tasks = DatabaseWrapper.loadAllTask(),
            leaves = DatabaseWrapper.loadAllLeaves()
        )

        val directory = File(context.filesDir, "config_snapshots").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val finalFile = File(directory, "snapshot_${stamp}.json")
        val tempFile = File(directory, "snapshot_${stamp}.tmp")
        tempFile.writeText(gson.toJson(snapshot))
        check(tempFile.renameTo(finalFile)) { "配置快照保存失败" }

        directory.listFiles { file -> file.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(8)
            ?.forEach { it.delete() }
        finalFile
    }

    fun list(context: Context): List<File> {
        return File(context.filesDir, "config_snapshots")
            .listFiles { file -> file.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
    }

    suspend fun restoreLatest(context: Context): String = withContext(Dispatchers.IO) {
        val file = list(context).firstOrNull() ?: error("本机还没有配置快照")
        restoreFile(file)
    }

    suspend fun restore(context: Context, file: File): String = withContext(Dispatchers.IO) {
        require(file in list(context)) { "配置快照不存在或已失效" }
        restoreFile(file)
    }

    suspend fun restoreWithBackup(context: Context, file: File): String = withContext(Dispatchers.IO) {
        require(file in list(context)) { "配置快照不存在或已失效" }
        val payload = file.readText()
        create(context, "恢复配置前自动备份")
        restorePayload(payload)
    }

    private suspend fun restoreFile(file: File): String {
        return restorePayload(file.readText())
    }

    private suspend fun restorePayload(payload: String): String {
        val snapshot = gson.fromJson(payload, ConfigSnapshot::class.java)
            ?: error("配置快照内容无效")

        snapshot.intSettings.forEach { (key, value) -> SaveKeyValues.saveInt(key, value) }
        snapshot.booleanSettings.forEach { (key, value) -> SaveKeyValues.saveBoolean(key, value) }
        snapshot.stringSettings.forEach { (key, value) -> SaveKeyValues.saveString(key, value) }
        snapshot.fileSettings.forEach { (key, value) ->
            ConfigStore.get().save(key, gson.toJsonTree(value).asJsonObject)
        }
        DatabaseWrapper.replaceAllTasks(snapshot.tasks)
        DatabaseWrapper.replaceAllLeaves(snapshot.leaves)
        return "已恢复 ${snapshot.sourceVersion} 的配置（${snapshot.reason}）"
    }
}
