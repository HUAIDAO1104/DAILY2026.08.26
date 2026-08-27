package com.pengxh.daily.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.pengxh.daily.app.R
import com.pengxh.daily.app.databinding.ActivityExecutionRecordsBinding
import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.daily.app.sqlite.bean.ExecutionRecordBean
import com.pengxh.daily.app.utils.ExecutionRecordManager
import com.pengxh.kt.lite.base.KotlinBaseActivity
import com.pengxh.kt.lite.extensions.convertColor
import com.pengxh.kt.lite.extensions.dp2px
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class ExecutionRecordsActivity : KotlinBaseActivity<ActivityExecutionRecordsBinding>() {
    private enum class Filter { ALL, SUCCESS, OTHER }
    private var filter = Filter.ALL
    private var records: List<ExecutionRecordBean> = emptyList()

    override fun initViewBinding() = ActivityExecutionRecordsBinding.inflate(layoutInflater)
    override fun observeRequestState() = Unit

    override fun setupTopBarLayout() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbar) { view, insets ->
            view.setPadding(0, insets.getInsets(WindowInsetsCompat.Type.statusBars()).top, 0, 0)
            insets
        }
        BottomNavController.bind(this, binding.root, BottomNavController.Tab.RECORDS)
    }

    override fun initOnCreate(savedInstanceState: Bundle?) = loadRecords()

    override fun initEvent() {
        binding.filterAllButton.setOnClickListener { selectFilter(Filter.ALL) }
        binding.filterSuccessButton.setOnClickListener { selectFilter(Filter.SUCCESS) }
        binding.filterSkippedButton.setOnClickListener { selectFilter(Filter.OTHER) }
    }

    override fun onResume() {
        super.onResume()
        loadRecords()
    }

    private fun selectFilter(value: Filter) {
        filter = value
        val selected = listOf(binding.filterAllButton, binding.filterSuccessButton, binding.filterSkippedButton)
        selected.forEachIndexed { index, view ->
            val active = index == value.ordinal
            view.setBackgroundResource(if (active) R.drawable.bg_segment_selected else android.R.color.transparent)
            view.setTextColor((if (active) R.color.white else R.color.text_secondary_dark).convertColor(this))
        }
        renderRecords()
    }

    private fun loadRecords() {
        lifecycleScope.launch {
            records = withContext(Dispatchers.IO) { DatabaseWrapper.loadAllExecutionRecords() }
            renderRecords()
        }
    }

    private fun renderRecords() {
        binding.recordsListLayout.removeAllViews()
        val visible = records.filter {
            when (filter) {
                Filter.ALL -> true
                Filter.SUCCESS -> it.status == ExecutionRecordManager.SUCCESS
                Filter.OTHER -> it.status != ExecutionRecordManager.SUCCESS
            }
        }
        binding.emptyState.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
        visible.groupBy { it.date.orEmpty() }.forEach { (date, rows) ->
            binding.recordsListLayout.addView(dateHeader(date))
            val group = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_glass_group)
            }
            rows.forEachIndexed { index, row ->
                group.addView(recordRow(row))
                if (index < rows.lastIndex) group.addView(View(this).apply {
                    setBackgroundColor(R.color.divider_dark.convertColor(this@ExecutionRecordsActivity))
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1.dp2px(this)).apply {
                    marginStart = 55.dp2px(this@ExecutionRecordsActivity)
                    marginEnd = 15.dp2px(this@ExecutionRecordsActivity)
                })
            }
            binding.recordsListLayout.addView(group, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun dateHeader(raw: String) = TextView(this).apply {
        val date = runCatching { LocalDate.parse(raw) }.getOrNull()
        text = when (date) {
            LocalDate.now() -> "今天 · ${date.monthValue}月${date.dayOfMonth}日"
            LocalDate.now().minusDays(1) -> "昨天 · ${date.monthValue}月${date.dayOfMonth}日"
            null -> raw
            else -> "${date.monthValue}月${date.dayOfMonth}日"
        }
        setTextColor(R.color.text_tertiary_dark.convertColor(this@ExecutionRecordsActivity))
        textSize = 12f
        setPadding(4.dp2px(this@ExecutionRecordsActivity), 18.dp2px(this@ExecutionRecordsActivity), 0, 8.dp2px(this@ExecutionRecordsActivity))
    }

    private fun recordRow(record: ExecutionRecordBean) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(14.dp2px(this@ExecutionRecordsActivity), 0, 12.dp2px(this@ExecutionRecordsActivity), 0)
        minimumHeight = 72.dp2px(this@ExecutionRecordsActivity)
        val success = record.status == ExecutionRecordManager.SUCCESS
        val mark = TextView(this@ExecutionRecordsActivity).apply {
            gravity = Gravity.CENTER
            text = when (record.status) {
                ExecutionRecordManager.SUCCESS -> "✓"
                ExecutionRecordManager.TIMEOUT -> "!"
                else -> "—"
            }
            setTextColor((if (success) R.color.success_green else R.color.warning_amber).convertColor(this@ExecutionRecordsActivity))
            setBackgroundResource(if (success) R.drawable.bg_circle_success else R.drawable.bg_circle_outline)
        }
        addView(mark, LinearLayout.LayoutParams(28.dp2px(this@ExecutionRecordsActivity), 28.dp2px(this@ExecutionRecordsActivity)))
        val copy = LinearLayout(this@ExecutionRecordsActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@ExecutionRecordsActivity).apply {
                text = record.taskName.orEmpty().ifBlank { "任务记录" }
                setTextColor(R.color.text_primary_dark.convertColor(this@ExecutionRecordsActivity))
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            addView(TextView(this@ExecutionRecordsActivity).apply {
                text = listOf(record.actualTime.orEmpty().take(5), record.detail.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")
                setTextColor(R.color.text_secondary_dark.convertColor(this@ExecutionRecordsActivity))
                textSize = 13f
                maxLines = 2
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 4.dp2px(this@ExecutionRecordsActivity) })
        }
        addView(copy, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 12.dp2px(this@ExecutionRecordsActivity) })
        addView(ImageView(this@ExecutionRecordsActivity).apply { setImageResource(R.drawable.ic_chevron_right_modern) }, LinearLayout.LayoutParams(18.dp2px(this@ExecutionRecordsActivity), 18.dp2px(this@ExecutionRecordsActivity)))
        setOnClickListener {
            startActivity(Intent(this@ExecutionRecordsActivity, RecordDetailActivity::class.java).putExtra(RecordDetailActivity.EXTRA_ID, record.id))
        }
    }
}
