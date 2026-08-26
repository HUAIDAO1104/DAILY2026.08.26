package com.pengxh.daily.app.ui

import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.pengxh.daily.app.R
import com.pengxh.daily.app.databinding.ActivityHolidayRulesBinding
import com.pengxh.daily.app.utils.ChinaHolidayManager
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.CustomWorkdayManager
import com.pengxh.kt.lite.base.KotlinBaseActivity
import com.pengxh.kt.lite.extensions.convertColor
import com.pengxh.kt.lite.extensions.dp2px
import com.pengxh.kt.lite.extensions.show
import com.pengxh.kt.lite.utils.SaveKeyValues
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class HolidayRulesActivity : KotlinBaseActivity<ActivityHolidayRulesBinding>() {
    override fun initViewBinding() = ActivityHolidayRulesBinding.inflate(layoutInflater)
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
        binding.skipHolidaySwitch.isChecked = SaveKeyValues.loadBoolean(Constant.SKIP_HOLIDAY_KEY, true)
        renderWeekdays()
        lifecycleScope.launch {
            ChinaHolidayManager.syncResult.collectLatest { result ->
                when (result) {
                    is ChinaHolidayManager.SyncResult.Success -> {
                        binding.dataStatusView.text = result.content
                        "节假日数据已更新".show(this@HolidayRulesActivity)
                    }
                    is ChinaHolidayManager.SyncResult.Error -> binding.dataStatusView.text = result.message
                }
            }
        }
    }

    override fun initEvent() {
        binding.skipHolidaySwitch.setOnCheckedChangeListener { _, checked ->
            SaveKeyValues.saveBoolean(Constant.SKIP_HOLIDAY_KEY, checked)
        }
        binding.syncButton.setOnClickListener {
            binding.dataStatusView.text = "正在同步…"
            ChinaHolidayManager.updateChinaHolidayData(force = true)
        }
    }

    private fun renderWeekdays() {
        binding.weekdaysLayout.removeAllViews()
        val workdays = CustomWorkdayManager.loadWorkdays().toMutableSet()
        CustomWorkdayManager.getOrderedDays().forEach { day ->
            val selected = day in workdays
            val chip = TextView(this).apply {
                text = CustomWorkdayManager.getDayLabel(day).removePrefix("周")
                gravity = Gravity.CENTER
                textSize = 12f
                setTextColor((if (selected) R.color.white else R.color.text_secondary_dark).convertColor(this@HolidayRulesActivity))
                setBackgroundResource(if (selected) R.drawable.bg_segment_selected else R.drawable.bg_circle_glass)
                setOnClickListener {
                    if (day in workdays && workdays.size == 1) {
                        "至少保留一天为工作日".show(this@HolidayRulesActivity)
                        return@setOnClickListener
                    }
                    if (!workdays.add(day)) workdays.remove(day)
                    CustomWorkdayManager.saveWorkdays(workdays)
                    renderWeekdays()
                }
            }
            binding.weekdaysLayout.addView(chip, LinearLayout.LayoutParams(0, 42.dp2px(this), 1f).apply {
                marginStart = 3.dp2px(this@HolidayRulesActivity)
                marginEnd = 3.dp2px(this@HolidayRulesActivity)
            })
        }
    }
}
