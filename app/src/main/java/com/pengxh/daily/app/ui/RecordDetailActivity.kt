package com.pengxh.daily.app.ui

import android.os.Bundle
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.pengxh.daily.app.R
import com.pengxh.daily.app.databinding.ActivityRecordDetailBinding
import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.daily.app.utils.ExecutionRecordManager
import com.pengxh.kt.lite.base.KotlinBaseActivity
import com.pengxh.kt.lite.extensions.convertColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class RecordDetailActivity : KotlinBaseActivity<ActivityRecordDetailBinding>() {
    companion object { const val EXTRA_ID = "record_id" }

    override fun initViewBinding() = ActivityRecordDetailBinding.inflate(layoutInflater)
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
        lifecycleScope.launch {
            val record = withContext(Dispatchers.IO) { DatabaseWrapper.findExecutionRecordById(intent.getIntExtra(EXTRA_ID, -1)) }
            if (record == null) {
                finish()
                return@launch
            }
            val success = record.status == ExecutionRecordManager.SUCCESS
            binding.statusView.text = when (record.status) {
                ExecutionRecordManager.SUCCESS -> "●  执行成功"
                ExecutionRecordManager.TIMEOUT -> "●  结果超时"
                else -> "●  已自动跳过"
            }
            binding.statusView.setTextColor((if (success) R.color.success_green else R.color.warning_amber).convertColor(this@RecordDetailActivity))
            binding.taskNameView.text = record.taskName.orEmpty().ifBlank { "任务记录" }
            val date = runCatching { LocalDate.parse(record.date) }.getOrNull()
            binding.dateView.text = date?.format(DateTimeFormatter.ofPattern("yyyy年M月d日")) ?: record.date
            binding.plannedTimelineView.text = "计划时间 ${record.plannedTime.orEmpty().take(5).ifBlank { "—" }}"
            binding.actualTimelineView.text = "实际执行 ${record.actualTime.orEmpty().take(5).ifBlank { "—" }}"
            binding.resultTimelineView.text = if (success) "●　已确认结果" else "●　任务已结束"
            binding.detailView.text = record.detail
            binding.plannedValueView.text = "计划时间　　　　　　　　　${record.plannedTime.orEmpty().take(5).ifBlank { "—" }}"
            binding.actualValueView.text = "实际时间　　　　　　　　　${record.actualTime.orEmpty().take(5).ifBlank { "—" }}"
        }
    }

    override fun initEvent() = Unit
}
