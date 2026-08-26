package com.pengxh.daily.app

import android.app.Application
import android.os.Environment
import androidx.room.Room.databaseBuilder
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.pengxh.daily.app.sqlite.DailyTaskDataBase
import com.pengxh.daily.app.utils.ConfigSnapshotManager
import com.pengxh.daily.app.utils.ConfigStore
import com.pengxh.daily.app.utils.ChinaHolidayManager
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.LogFileManager
import com.pengxh.daily.app.utils.MessageDispatcher
import com.pengxh.kt.lite.utils.SaveKeyValues
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException


/**
 * @author: Pengxh
 * @email: 290677893@qq.com
 * @date: 2019/12/25 13:19
 */
class DailyTaskApplication : Application() {

    companion object {
        private lateinit var application: DailyTaskApplication

        fun get(): DailyTaskApplication = application

        internal fun initApplication(app: DailyTaskApplication) {
            application = app
        }
    }

    lateinit var dataBase: DailyTaskDataBase
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val migration1To2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `leave_record_table` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `startDate` TEXT,
                    `endDate` TEXT,
                    `period` TEXT,
                    `reason` TEXT,
                    `createdAt` INTEGER NOT NULL
                )""".trimIndent()
            )
        }
    }

    private val migration2To3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `daily_task_table` ADD COLUMN `name` TEXT")
            db.execSQL("ALTER TABLE `daily_task_table` ADD COLUMN `enabled` INTEGER NOT NULL DEFAULT 1")
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `execution_record_table` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `date` TEXT,
                    `taskId` INTEGER NOT NULL,
                    `taskName` TEXT,
                    `plannedTime` TEXT,
                    `actualTime` TEXT,
                    `status` TEXT,
                    `detail` TEXT,
                    `createdAt` INTEGER NOT NULL
                )""".trimIndent()
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        initApplication(this)
        SaveKeyValues.initialize(this)
        MessageDispatcher.initialize(this)
        LogFileManager.initLogFile(this)

        // 初始化配置文件
        val dir = File(this.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "")
        val file = File(dir.toString() + File.separator + "DailyTaskConfig.json")
        if (!file.exists()) {
            try {
                file.createNewFile()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
        ConfigStore.init(file.absolutePath)

        // 启动时优先加载本年度节假日缓存；没有缓存时会在后台自动同步。
        // 这样无需用户先进入设置页，日期跳过规则也能直接生效。
        ChinaHolidayManager.updateChinaHolidayData()

        dataBase = databaseBuilder(this, DailyTaskDataBase::class.java, "DailyTask.db")
            .addMigrations(migration1To2, migration2To3)
            .build()

        applicationScope.launch {
            val previousVersion = SaveKeyValues.loadString(Constant.LAST_APP_VERSION_KEY, "")
            if (previousVersion.isNotBlank() && previousVersion != BuildConfig.VERSION_NAME) {
                runCatching {
                    ConfigSnapshotManager.create(
                        this@DailyTaskApplication,
                        "从 $previousVersion 更新到 ${BuildConfig.VERSION_NAME}"
                    )
                }.onFailure { LogFileManager.writeLog("自动保存更新配置失败：${it.message}") }
            }
            SaveKeyValues.saveString(Constant.LAST_APP_VERSION_KEY, BuildConfig.VERSION_NAME)
        }
    }
}
