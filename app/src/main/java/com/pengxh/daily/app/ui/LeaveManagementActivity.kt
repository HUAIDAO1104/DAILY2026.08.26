package com.pengxh.daily.app.ui

import android.os.Bundle
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pengxh.daily.app.R
import com.pengxh.daily.app.databinding.ActivityLeaveManagementBinding
import com.pengxh.daily.app.sqlite.bean.LeaveRecordBean
import com.pengxh.daily.app.utils.DailyTaskDialogs
import com.pengxh.daily.app.utils.LeaveManager
import com.pengxh.daily.app.utils.LeavePeriod
import com.pengxh.kt.lite.base.KotlinBaseActivity
import com.pengxh.kt.lite.extensions.dp2px
import com.pengxh.kt.lite.extensions.show
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class LeaveManagementActivity : KotlinBaseActivity<ActivityLeaveManagementBinding>() {
    override fun initViewBinding() = ActivityLeaveManagementBinding.inflate(layoutInflater)
    override fun observeRequestState() = Unit

    override fun setupTopBarLayout() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbar) { view, insets ->
            view.setPadding(0, insets.getInsets(WindowInsetsCompat.Type.statusBars()).top, 0, 0)
            insets
        }
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    override fun initOnCreate(savedInstanceState: Bundle?) {
        refreshLeaves()
    }

    override fun initEvent() {
        binding.addLeaveButton.setOnClickListener { showAddLeaveDialog() }
        binding.todayLeaveButton.setOnClickListener {
            DailyTaskDialogs.showConfirm(
                this,
                "今天全天请假？",
                "今天剩余的打卡任务将自动跳过。",
                "确认请假"
            ) { addQuickLeave(LocalDate.now()) }
        }
    }

    private fun addQuickLeave(date: LocalDate) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                LeaveManager.addLeave(date, date, LeavePeriod.ALL_DAY, "临时请假")
            }
            "请假已保存".show(this@LeaveManagementActivity)
            refreshLeaves()
        }
    }

    private fun showAddLeaveDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_add_leave, null)
        val startView = view.findViewById<TextView>(R.id.startDateView)
        val endView = view.findViewById<TextView>(R.id.endDateView)
        val periodGroup = view.findViewById<RadioGroup>(R.id.periodGroup)
        val reasonInput = view.findViewById<EditText>(R.id.reasonInput)
        var startDate = LocalDate.now()
        var endDate = LocalDate.now()

        fun updateDates() {
            startView.text = startDate.toString()
            endView.text = endDate.toString()
        }
        fun pickDate(initial: LocalDate, onPicked: (LocalDate) -> Unit) {
            val initialMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            val picker = MaterialDatePicker.Builder.datePicker()
                .setTitleText("选择日期")
                .setSelection(initialMillis)
                .setPositiveButtonText("确定")
                .setNegativeButtonText("取消")
                .build()
            picker.addOnPositiveButtonClickListener { millis ->
                onPicked(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
            }
            picker.show(supportFragmentManager, "leave_date_picker")
        }
        updateDates()
        startView.setOnClickListener {
            pickDate(startDate) {
                startDate = it
                if (endDate.isBefore(startDate)) endDate = startDate
                updateDates()
            }
        }
        endView.setOnClickListener {
            pickDate(endDate) {
                if (it.isBefore(startDate)) {
                    "结束日期不能早于开始日期".show(this)
                } else {
                    endDate = it
                    updateDates()
                }
            }
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("新增请假")
            .setView(view)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()
        dialog.setOnShowListener {
            DailyTaskDialogs.style(dialog)
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val period = when (periodGroup.checkedRadioButtonId) {
                    R.id.morningRadio -> LeavePeriod.MORNING
                    R.id.afternoonRadio -> LeavePeriod.AFTERNOON
                    else -> LeavePeriod.ALL_DAY
                }
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        LeaveManager.addLeave(
                            startDate,
                            endDate,
                            period,
                            reasonInput.text?.toString().orEmpty()
                        )
                    }
                    dialog.dismiss()
                    refreshLeaves()
                }
            }
        }
        dialog.show()
    }

    private fun refreshLeaves() {
        lifecycleScope.launch {
            val leaves = withContext(Dispatchers.IO) { LeaveManager.loadAll() }
            renderLeaves(leaves)
        }
    }

    private fun renderLeaves(leaves: List<LeaveRecordBean>) {
        binding.leaveListLayout.removeAllViews()
        if (leaves.isEmpty()) {
            binding.leaveListLayout.addView(TextView(this).apply {
                text = "还没有请假记录"
                setTextColor(getColor(R.color.text_secondary_dark))
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, 42.dp2px(this@LeaveManagementActivity), 0, 0)
            })
            return
        }
        leaves.forEach { leave ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(16.dp2px(this@LeaveManagementActivity), 13.dp2px(this@LeaveManagementActivity), 10.dp2px(this@LeaveManagementActivity), 13.dp2px(this@LeaveManagementActivity))
                setBackgroundResource(R.drawable.bg_glass_card_small)
            }
            val dateRange = if (leave.startDate == leave.endDate) leave.startDate else "${leave.startDate} 至 ${leave.endDate}"
            val info = TextView(this).apply {
                text = "$dateRange · ${LeaveManager.periodLabel(leave.period)}\n${leave.reason.ifBlank { "请假" }}"
                setTextColor(getColor(R.color.text_primary_dark))
                textSize = 14f
                setLineSpacing(0f, 1.25f)
            }
            card.addView(info, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val delete = MaterialButton(this).apply {
                text = "删除"
                textSize = 12f
                setTextColor(getColor(R.color.accent_red))
                backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.surface_elevated_dark))
                setOnClickListener { confirmDelete(leave) }
            }
            card.addView(delete, LinearLayout.LayoutParams(72.dp2px(this), 44.dp2px(this)))
            binding.leaveListLayout.addView(card, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8.dp2px(this@LeaveManagementActivity) })
        }
    }

    private fun confirmDelete(leave: LeaveRecordBean) {
        DailyTaskDialogs.showConfirm(
            this,
            "删除请假记录？",
            "删除后，对应日期将重新按照日常规则执行任务。",
            "删除"
        ) {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { LeaveManager.deleteById(leave.id) }
                refreshLeaves()
            }
        }
    }
}
