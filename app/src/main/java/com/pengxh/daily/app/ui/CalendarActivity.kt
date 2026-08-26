package com.pengxh.daily.app.ui

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.widget.GridLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.pengxh.daily.app.R
import com.pengxh.daily.app.databinding.ActivityCalendarBinding
import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.daily.app.sqlite.bean.LeaveRecordBean
import com.pengxh.daily.app.utils.ChinaHolidayManager
import com.pengxh.daily.app.utils.CustomWorkdayManager
import com.pengxh.daily.app.utils.LeaveManager
import com.pengxh.daily.app.utils.LeavePeriod
import com.pengxh.kt.lite.base.KotlinBaseActivity
import com.pengxh.kt.lite.extensions.convertColor
import com.pengxh.kt.lite.extensions.dp2px
import com.pengxh.kt.lite.extensions.navigatePageTo
import com.pengxh.kt.lite.extensions.show
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth

class CalendarActivity : KotlinBaseActivity<ActivityCalendarBinding>() {
    private var displayedMonth = YearMonth.now()
    private var selectedDate = LocalDate.now()
    private var leaves: List<LeaveRecordBean> = emptyList()

    override fun initViewBinding() = ActivityCalendarBinding.inflate(layoutInflater)
    override fun observeRequestState() = Unit

    override fun setupTopBarLayout() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbar) { view, insets ->
            view.setPadding(0, insets.getInsets(WindowInsetsCompat.Type.statusBars()).top, 0, 0)
            insets
        }
        BottomNavController.bind(this, binding.root, BottomNavController.Tab.CALENDAR)
    }

    override fun initOnCreate(savedInstanceState: Bundle?) {
        loadAndRender()
    }

    override fun initEvent() {
        binding.previousMonthButton.setOnClickListener {
            displayedMonth = displayedMonth.minusMonths(1)
            selectedDate = displayedMonth.atDay(1)
            loadAndRender()
        }
        binding.nextMonthButton.setOnClickListener {
            displayedMonth = displayedMonth.plusMonths(1)
            selectedDate = displayedMonth.atDay(1)
            loadAndRender()
        }
        binding.addLeaveButton.setOnClickListener { navigatePageTo<LeaveManagementActivity>() }
        binding.holidayRulesButton.setOnClickListener { navigatePageTo<HolidayRulesActivity>() }
        binding.toggleLeaveButton.setOnClickListener {
            lifecycleScope.launch {
                val existing = withContext(Dispatchers.IO) { DatabaseWrapper.loadLeavesForDate(selectedDate) }
                if (existing.isNotEmpty()) {
                    withContext(Dispatchers.IO) { LeaveManager.cancelForDate(selectedDate) }
                    "已恢复正常打卡".show(this@CalendarActivity)
                } else {
                    withContext(Dispatchers.IO) {
                        LeaveManager.addLeave(selectedDate, selectedDate, LeavePeriod.ALL_DAY, "日历设置")
                    }
                    "已设为请假日".show(this@CalendarActivity)
                }
                loadAndRender()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadAndRender()
    }

    private fun loadAndRender() {
        lifecycleScope.launch {
            leaves = withContext(Dispatchers.IO) { DatabaseWrapper.loadAllLeaves() }
            renderCalendar()
            renderSelectedDate()
        }
    }

    private fun renderCalendar() {
        binding.monthView.text = "${displayedMonth.monthValue}月"
        binding.yearView.text = displayedMonth.year.toString()
        binding.calendarGrid.removeAllViews()
        val first = displayedMonth.atDay(1)
        val offset = first.dayOfWeek.value % 7
        val start = first.minusDays(offset.toLong())
        repeat(42) { index ->
            val date = start.plusDays(index.toLong())
            val cell = TextView(this).apply {
                text = date.dayOfMonth.toString()
                gravity = Gravity.CENTER
                textSize = 12f
                setTextColor(
                    when {
                        date == selectedDate -> R.color.white.convertColor(this@CalendarActivity)
                        date.month != displayedMonth.month -> R.color.text_tertiary_dark.convertColor(this@CalendarActivity)
                        else -> R.color.text_primary_dark.convertColor(this@CalendarActivity)
                    }
                )
                val hasLeave = leaves.any { record ->
                    runCatching {
                        !date.isBefore(LocalDate.parse(record.startDate)) && !date.isAfter(LocalDate.parse(record.endDate))
                    }.getOrDefault(false)
                }
                when {
                    date == selectedDate -> setBackgroundResource(R.drawable.bg_calendar_selected)
                    hasLeave -> setBackgroundResource(R.drawable.bg_calendar_leave)
                    else -> background = null
                }
                setOnClickListener {
                    selectedDate = date
                    if (date.month != displayedMonth.month) displayedMonth = YearMonth.from(date)
                    renderCalendar()
                    renderSelectedDate()
                }
            }
            val size = 40.dp2px(this)
            val params = GridLayout.LayoutParams(
                GridLayout.spec(index / 7, 1f), GridLayout.spec(index % 7, 1f)
            ).apply {
                width = 0
                height = size
                setMargins(4.dp2px(this@CalendarActivity), 4.dp2px(this@CalendarActivity), 4.dp2px(this@CalendarActivity), 4.dp2px(this@CalendarActivity))
            }
            binding.calendarGrid.addView(cell, params)
        }
    }

    private fun renderSelectedDate() {
        val hasLeave = leaves.any { record ->
            runCatching {
                !selectedDate.isBefore(LocalDate.parse(record.startDate)) && !selectedDate.isAfter(LocalDate.parse(record.endDate))
            }.getOrDefault(false)
        }
        val weekend = CustomWorkdayManager.isWeekdayRestDay(selectedDate)
        val holiday = ChinaHolidayManager.isHoliday(selectedDate)
        val workday = ChinaHolidayManager.isWorkday(selectedDate)
        binding.selectedDateView.text = "${selectedDate.monthValue}月${selectedDate.dayOfMonth}日"
        when {
            hasLeave -> {
                binding.selectedStatusView.text = "请假"
                binding.selectedStatusView.setTextColor(R.color.warning_amber.convertColor(this))
                binding.selectedNoteView.text = "全天不打卡，次日按照规则自动恢复"
                binding.toggleLeaveButton.text = "取消请假"
                binding.toggleLeaveButton.backgroundTintList = ColorStateList.valueOf(R.color.surface_elevated_dark.convertColor(this))
            }
            workday -> showNormal("补班日", "官方调休补班规则生效，将正常执行任务")
            holiday || weekend -> {
                binding.selectedStatusView.text = if (holiday) "节假日" else "休息"
                binding.selectedStatusView.setTextColor(R.color.warning_amber.convertColor(this))
                binding.selectedNoteView.text = if (holiday) "按照法定节假日规则自动跳过" else "按照固定周末规则自动跳过"
                binding.toggleLeaveButton.text = "仍设为请假"
                binding.toggleLeaveButton.backgroundTintList = ColorStateList.valueOf(R.color.accent_red.convertColor(this))
            }
            else -> showNormal("正常打卡", "将执行当天所有已启用任务")
        }
    }

    private fun showNormal(status: String, note: String) {
        binding.selectedStatusView.text = status
        binding.selectedStatusView.setTextColor(R.color.success_green.convertColor(this))
        binding.selectedNoteView.text = note
        binding.toggleLeaveButton.text = "设为请假"
        binding.toggleLeaveButton.backgroundTintList = ColorStateList.valueOf(R.color.accent_red.convertColor(this))
    }
}
