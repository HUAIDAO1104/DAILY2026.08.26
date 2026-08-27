package com.pengxh.daily.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pengxh.daily.app.R
import com.pengxh.daily.app.databinding.ActivityTaskConfigBinding
import com.pengxh.daily.app.extensions.isApplicationExist
import com.pengxh.daily.app.model.ExportDataModel
import com.pengxh.daily.app.service.ForegroundRunningService
import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.daily.app.sqlite.bean.DailyTaskBean
import com.pengxh.daily.app.utils.ConfigStore
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.CustomWorkdayManager
import com.pengxh.daily.app.utils.DailyTaskDialogs
import com.pengxh.daily.app.utils.FloatingWindowController
import com.pengxh.kt.lite.base.KotlinBaseActivity
import com.pengxh.kt.lite.extensions.show
import com.pengxh.kt.lite.extensions.toJson
import com.pengxh.kt.lite.utils.SaveKeyValues
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek

class TaskConfigActivity : KotlinBaseActivity<ActivityTaskConfigBinding>() {

    private val kTag = "TaskConfigActivity"
    private val context = this
    private val hourOptions = listOf("00:00", "01:00", "02:00", "03:00", "04:00", "05:00", "06:00", "自定义时间")
    private val timeoutOptions = listOf("15 秒", "30 秒", "45 秒", "自定义时长")
    private val timeoutValues = listOf(15, 30, 45)
    private val shareOptions = listOf("QQ", "微信", "TIM", "支付宝", "复制到剪贴板")
    private val clipboard by lazy { getSystemService(ClipboardManager::class.java) }

    override fun initViewBinding(): ActivityTaskConfigBinding {
        return ActivityTaskConfigBinding.inflate(layoutInflater)
    }

    override fun observeRequestState() {

    }

    override fun setupTopBarLayout() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbar) { view, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.setPadding(0, statusBarHeight, 0, 0)
            insets
        }
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    override fun initOnCreate(savedInstanceState: Bundle?) {
        val hour = SaveKeyValues.loadInt(Constant.RESET_TIME_KEY, Constant.DEFAULT_RESET_HOUR)
        binding.resetTimeView.text = "每天${hour}点"

        val time = SaveKeyValues.loadInt(Constant.STAY_OVERTIME_KEY, Constant.DEFAULT_OVER_TIME)
        binding.timeoutTextView.text = "${time}s"

        binding.keyTextView.text = SaveKeyValues.loadString(Constant.REMOTE_COMMAND_KEY, "打卡")

        updateCustomWorkdaySummary(CustomWorkdayManager.loadWorkdays())

        binding.autoTaskSwitch.isChecked =
            SaveKeyValues.loadBoolean(Constant.TASK_AUTO_RECYCLE_KEY, true)

        binding.skipHolidaySwitch.isChecked =
            SaveKeyValues.loadBoolean(Constant.SKIP_HOLIDAY_KEY, true)

        val needRandom = SaveKeyValues.loadBoolean(Constant.RANDOM_TIME_KEY, true)
        binding.randomTimeSwitch.isChecked = needRandom
        if (needRandom) {
            binding.minuteRangeLayout.visibility = View.VISIBLE
            val value = SaveKeyValues.loadInt(Constant.TIME_RANGE_KEY, Constant.DEFAULT_TIME_RANGE)
            binding.minuteRangeView.text = "${value}分钟"
        } else {
            binding.minuteRangeLayout.visibility = View.GONE
        }
    }

    override fun initEvent() {
        binding.resetTimeLayout.setOnClickListener {
            val current = SaveKeyValues.loadInt(Constant.RESET_TIME_KEY, Constant.DEFAULT_RESET_HOUR)
            DailyTaskDialogs.showChoice(
                this,
                "每天几点重置任务",
                hourOptions,
                current.takeIf { it in 0..6 } ?: hourOptions.lastIndex
            ) { setHourByPosition(it) }
        }

        binding.timeoutLayout.setOnClickListener {
            val current = SaveKeyValues.loadInt(Constant.STAY_OVERTIME_KEY, Constant.DEFAULT_OVER_TIME)
            DailyTaskDialogs.showChoice(
                this,
                "等待结果的时长",
                timeoutOptions,
                timeoutValues.indexOf(current).takeIf { it >= 0 } ?: timeoutOptions.lastIndex
            ) { setTimeByPosition(it) }
        }

        binding.keyLayout.setOnClickListener {
            DailyTaskDialogs.showTextInput(
                context = this,
                title = "设置打卡口令",
                label = "口令",
                description = "收到包含该口令的远程消息后执行打卡。",
                initialValue = SaveKeyValues.loadString(Constant.REMOTE_COMMAND_KEY, "打卡"),
                validator = { if (it.isBlank()) "口令不能为空" else null }
            ) { value ->
                SaveKeyValues.saveString(Constant.REMOTE_COMMAND_KEY, value)
                binding.keyTextView.text = value
            }
        }

        binding.workdayLayout.setOnClickListener {
            showWorkdaySelector()
        }

        binding.randomTimeSwitch.setOnCheckedChangeListener { _, isChecked ->
            SaveKeyValues.saveBoolean(Constant.RANDOM_TIME_KEY, isChecked)
            if (isChecked) {
                binding.minuteRangeLayout.visibility = View.VISIBLE
                val value =
                    SaveKeyValues.loadInt(Constant.TIME_RANGE_KEY, Constant.DEFAULT_TIME_RANGE)
                binding.minuteRangeView.text = "${value}分钟"
            } else {
                binding.minuteRangeLayout.visibility = View.GONE
            }
        }

        binding.skipHolidaySwitch.setOnCheckedChangeListener { _, isChecked ->
            SaveKeyValues.saveBoolean(Constant.SKIP_HOLIDAY_KEY, isChecked)
        }

        binding.minuteRangeLayout.setOnClickListener {
            DailyTaskDialogs.showTextInput(
                context = this,
                title = "随机时间范围",
                label = "分钟",
                description = "任务会在计划时间前后这个范围内随机执行。",
                initialValue = SaveKeyValues.loadInt(
                    Constant.TIME_RANGE_KEY,
                    Constant.DEFAULT_TIME_RANGE
                ).toString(),
                inputType = InputType.TYPE_CLASS_NUMBER,
                validator = { value ->
                    when {
                        value.toIntOrNull() == null -> "请输入整数分钟"
                        value.toInt() < 0 -> "不能小于 0 分钟"
                        else -> null
                    }
                }
            ) { updateRandomMinuteRange(it.toInt()) }
        }

        binding.exportLayout.setOnClickListener {
            val exportData = ExportDataModel()

            // Int
            exportData.resetTime =
                SaveKeyValues.loadInt(Constant.RESET_TIME_KEY, Constant.DEFAULT_RESET_HOUR)
            exportData.overtime =
                SaveKeyValues.loadInt(Constant.STAY_OVERTIME_KEY, Constant.DEFAULT_OVER_TIME)
            exportData.timeRange =
                SaveKeyValues.loadInt(Constant.TIME_RANGE_KEY, Constant.DEFAULT_TIME_RANGE)
            exportData.msgChannel =
                SaveKeyValues.loadInt(Constant.MSG_CHANNEL_KEY, Constant.DEFAULT_INDEX)
            exportData.targetApp = SaveKeyValues.loadInt(Constant.TARGET_APP_KEY, 0)

            // String
            exportData.remoteCommand = SaveKeyValues.loadString(Constant.REMOTE_COMMAND_KEY, "打卡")
            exportData.msgTitle =
                SaveKeyValues.loadString(Constant.MESSAGE_TITLE_KEY, "打卡结果通知")
            exportData.wxKey = SaveKeyValues.loadString(Constant.WX_WEB_HOOK_KEY, "")
            exportData.customWorkdays = CustomWorkdayManager.serializeWorkdays(
                CustomWorkdayManager.loadWorkdays()
            )

            // Boolean
            exportData.isDetectGesture =
                SaveKeyValues.loadBoolean(Constant.GESTURE_DETECTOR_KEY, true)
            exportData.isBackToHome = SaveKeyValues.loadBoolean(Constant.BACK_TO_HOME_KEY, false)
            exportData.isAutoRecycle =
                SaveKeyValues.loadBoolean(Constant.TASK_AUTO_RECYCLE_KEY, true)
            exportData.isRandomTime = SaveKeyValues.loadBoolean(Constant.RANDOM_TIME_KEY, true)
            exportData.isSkipHoliday = SaveKeyValues.loadBoolean(Constant.SKIP_HOLIDAY_KEY, true)
            exportData.isSavePower =
                SaveKeyValues.loadBoolean(Constant.POWER_SAVE_MODE_KEY, false)

            // EmailConfig
            val obj = ConfigStore.get().load(Constant.EMAIL_CONFIG_KEY)
            if (!obj.isEmpty) {
                val outbox = obj.get("outbox").asString
                val authCode = obj.get("authCode").asString
                val inbox = obj.get("inbox").asString
                exportData.emailConfig = Triple(outbox, authCode, inbox)
            }

            // TaskBeans
            lifecycleScope.launch {
                val taskBeans = withContext(Dispatchers.IO) {
                    DatabaseWrapper.loadAllTask()
                }
                if (taskBeans.isNotEmpty()) {
                    exportData.tasks = taskBeans
                } else {
                    exportData.tasks = ArrayList<DailyTaskBean>()
                }

                val json = exportData.toJson()
                Log.d(kTag, json)

                // 分享
                DailyTaskDialogs.showChoice(
                    this@TaskConfigActivity,
                    "导出到",
                    shareOptions
                ) { position ->
                    when (position) {
                        0 -> shareTextTo(Constant.QQ, "QQ", json)
                        1 -> shareTextTo(Constant.WECHAT, "微信", json)
                        2 -> shareTextTo(Constant.TIM, "TIM", json)
                        3 -> shareTextTo(Constant.ZFB, "支付宝", json)
                        4 -> {
                            val cipData = ClipData.newPlainText("TaskConfig", json)
                            clipboard.setPrimaryClip(cipData)
                            "已复制到剪贴板".show(context)
                        }
                    }
                }
            }
        }
    }

    private fun showWorkdaySelector() {
        val orderedDays = CustomWorkdayManager.getOrderedDays()
        val selectedDays = CustomWorkdayManager.loadWorkdays().toMutableSet()
        val labels = orderedDays.map { CustomWorkdayManager.getDayLabel(it) }.toTypedArray()
        val checkedItems = orderedDays.map { it in selectedDays }.toBooleanArray()

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("选择工作日")
            .setMultiChoiceItems(labels, checkedItems) { _, which, isChecked ->
                val day = orderedDays[which]
                if (isChecked) {
                    selectedDays.add(day)
                } else {
                    selectedDays.remove(day)
                }
            }
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()
        dialog.setOnShowListener {
            DailyTaskDialogs.style(dialog)
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (selectedDays.isEmpty()) {
                    "至少保留一天为工作日".show(this)
                    return@setOnClickListener
                }

                val normalized = orderedDays.filter { it in selectedDays }.toSet()
                CustomWorkdayManager.saveWorkdays(normalized)
                updateCustomWorkdaySummary(normalized)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun updateCustomWorkdaySummary(workdays: Set<DayOfWeek>) {
        binding.workdayValueView.text = CustomWorkdayManager.formatWorkdays(workdays)
    }

    private fun setHourByPosition(position: Int) {
        if (position == hourOptions.lastIndex) {
            DailyTaskDialogs.showTextInput(
                context = this,
                title = "自定义重置时间",
                label = "小时（0–23）",
                description = "每天到这个整点后，任务状态会进入新的一天。",
                initialValue = SaveKeyValues.loadInt(
                    Constant.RESET_TIME_KEY,
                    Constant.DEFAULT_RESET_HOUR
                ).toString(),
                inputType = InputType.TYPE_CLASS_NUMBER,
                validator = { value ->
                    val hour = value.toIntOrNull()
                    if (hour == null || hour !in 0..23) "请输入 0–23 之间的整数" else null
                }
            ) { updateResetHour(it.toInt()) }
        } else {
            updateResetHour(position)
        }
    }

    private fun updateResetHour(hour: Int) {
        if (hour !in 0..23) {
            "重置时间必须在0到23点之间".show(this)
            return
        }
        binding.resetTimeView.text = "每天${hour}点"
        setTaskResetTime(hour)
    }

    private fun setTaskResetTime(hour: Int) {
        SaveKeyValues.saveInt(Constant.RESET_TIME_KEY, hour)
        // 通知 Service 更新倒计时显示
        ForegroundRunningService.emitResetTaskTime()
    }

    private fun setTimeByPosition(position: Int) {
        if (position == timeoutOptions.lastIndex) {
            DailyTaskDialogs.showTextInput(
                context = this,
                title = "自定义等待时长",
                label = "秒数",
                description = "打开目标应用后，最长等待结果通知的时间。",
                initialValue = SaveKeyValues.loadInt(
                    Constant.STAY_OVERTIME_KEY,
                    Constant.DEFAULT_OVER_TIME
                ).toString(),
                inputType = InputType.TYPE_CLASS_NUMBER,
                validator = { value ->
                    if ((value.toIntOrNull() ?: 0) <= 0) "请输入大于 0 的整数" else null
                }
            ) { updateTimeout(it.toInt()) }
        } else {
            updateTimeout(timeoutValues[position])
        }
    }

    private fun updateTimeout(time: Int) {
        if (time <= 0) {
            "超时时间必须大于0秒".show(this)
            return
        }
        binding.timeoutTextView.text = "${time}s"
        SaveKeyValues.saveInt(Constant.STAY_OVERTIME_KEY, time)
        FloatingWindowController.setOvertime(time)
    }

    private fun shareTextTo(packageName: String, appName: String, text: String) {
        if (!isApplicationExist(packageName)) {
            "请先安装${appName}".show(this)
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            setPackage(packageName)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            "分享失败".show(this)
        }
    }

    private fun updateRandomMinuteRange(value: Int) {
        if (value < 0) {
            "随机时间范围不能小于0分钟".show(this)
            return
        }
        binding.minuteRangeView.text = "${value}分钟"
        SaveKeyValues.saveInt(Constant.TIME_RANGE_KEY, value)
    }
}
