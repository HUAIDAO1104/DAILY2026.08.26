package com.pengxh.daily.app.ui

import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.pengxh.daily.app.databinding.ActivityTaskEditorBinding
import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.daily.app.sqlite.bean.DailyTaskBean
import com.pengxh.daily.app.utils.DailyTaskDialogs
import com.pengxh.daily.app.utils.displayName
import com.pengxh.kt.lite.base.KotlinBaseActivity
import com.pengxh.kt.lite.extensions.show
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class TaskEditorActivity : KotlinBaseActivity<ActivityTaskEditorBinding>() {
    companion object { const val EXTRA_ID = "task_id" }
    private val formatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    private var task: DailyTaskBean? = null
    private var selectedTime = LocalTime.of(8, 50)

    override fun initViewBinding() = ActivityTaskEditorBinding.inflate(layoutInflater)
    override fun observeRequestState() = Unit
    override fun setupTopBarLayout() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbar) { view, insets -> view.setPadding(0, insets.getInsets(WindowInsetsCompat.Type.statusBars()).top, 0, 0); insets }
        binding.toolbar.setNavigationOnClickListener { finish() }
    }
    override fun initOnCreate(savedInstanceState: Bundle?) {
        val id = intent.getIntExtra(EXTRA_ID, -1)
        binding.toolbar.title = if (id < 0) "新增任务" else "编辑任务"
        if (id >= 0) lifecycleScope.launch {
            task = withContext(Dispatchers.IO) { DatabaseWrapper.findTaskById(id) }
            task?.let {
                selectedTime = runCatching { LocalTime.parse(it.time) }.getOrDefault(selectedTime)
                binding.nameInput.setText(it.displayName())
                binding.enabledSwitch.isChecked = it.isEnabled
                binding.deleteButton.visibility = View.VISIBLE
                renderTime()
            }
        } else renderTime()
    }
    override fun initEvent() {
        binding.timeLayout.setOnClickListener {
            val picker = MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_24H)
                .setInputMode(MaterialTimePicker.INPUT_MODE_CLOCK)
                .setHour(selectedTime.hour)
                .setMinute(selectedTime.minute)
                .setTitleText("选择计划时间")
                .setPositiveButtonText("确定")
                .setNegativeButtonText("取消")
                .build()
            picker.addOnPositiveButtonClickListener {
                selectedTime = LocalTime.of(picker.hour, picker.minute)
                renderTime()
            }
            picker.show(supportFragmentManager, "task_time_picker")
        }
        binding.saveButton.setOnClickListener { saveTask() }
        binding.deleteButton.setOnClickListener {
            val current = task ?: return@setOnClickListener
            DailyTaskDialogs.showConfirm(
                this,
                "删除这个任务？",
                "删除后不会再执行，历史记录仍会保留。",
                "删除"
            ) {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { DatabaseWrapper.deleteTask(current) }
                    finish()
                }
            }
        }
    }
    private fun renderTime() { binding.timeView.text = selectedTime.format(DateTimeFormatter.ofPattern("HH:mm")) }
    private fun saveTask() {
        val name = binding.nameInput.text?.toString()?.trim().orEmpty()
        val time = selectedTime.withSecond(0).format(formatter)
        lifecycleScope.launch {
            val duplicate = withContext(Dispatchers.IO) { DatabaseWrapper.findTaskByTime(time) }
            if (duplicate != null && duplicate.id != task?.id) { "$time 的任务已经存在".show(this@TaskEditorActivity); return@launch }
            val value = task ?: DailyTaskBean()
            value.time = time
            value.name = name.ifBlank { null }
            value.isEnabled = binding.enabledSwitch.isChecked
            withContext(Dispatchers.IO) { if (value.id == 0) DatabaseWrapper.insert(value) else DatabaseWrapper.updateTask(value) }
            "任务已保存".show(this@TaskEditorActivity)
            finish()
        }
    }
}
