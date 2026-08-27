package com.pengxh.daily.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.pengxh.daily.app.adapter.DailyTaskAdapter
import com.pengxh.daily.app.databinding.ActivityTaskManagementBinding
import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.daily.app.sqlite.bean.DailyTaskBean
import com.pengxh.daily.app.utils.DailyTaskDialogs
import com.pengxh.daily.app.utils.TaskScheduler
import com.pengxh.kt.lite.base.KotlinBaseActivity
import com.pengxh.kt.lite.extensions.navigatePageTo
import com.pengxh.kt.lite.extensions.show
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TaskManagementActivity : KotlinBaseActivity<ActivityTaskManagementBinding>() {
    private val tasks = mutableListOf<DailyTaskBean>()
    private val adapter = DailyTaskAdapter(tasks).apply {
        setOnItemClickListener(object : DailyTaskAdapter.OnItemClickListener {
            override fun onItemClick(position: Int) = openEditor(tasks[position].id)
            override fun onItemLongClick(position: Int) = confirmDelete(tasks[position])
        })
    }

    override fun initViewBinding() = ActivityTaskManagementBinding.inflate(layoutInflater)
    override fun observeRequestState() = Unit
    override fun setupTopBarLayout() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbar) { view, insets -> view.setPadding(0, insets.getInsets(WindowInsetsCompat.Type.statusBars()).top, 0, 0); insets }
        val baseBottomPadding = binding.contentContainer.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.contentContainer) { view, insets ->
            val navigationBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, baseBottomPadding + navigationBar)
            insets
        }
        binding.toolbar.setNavigationOnClickListener { finish() }
    }
    override fun initOnCreate(savedInstanceState: Bundle?) { binding.recyclerView.adapter = adapter; loadTasks() }
    override fun initEvent() {
        binding.addButton.setOnClickListener { openEditor(-1) }
        binding.globalConfigButton.setOnClickListener { navigatePageTo<TaskConfigActivity>() }
    }
    override fun onResume() { super.onResume(); loadTasks() }

    private fun loadTasks() {
        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) { DatabaseWrapper.loadAllTask() }
            adapter.refresh(rows)
            binding.emptyView.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        }
    }
    private fun openEditor(id: Int) {
        if (TaskScheduler.isRunning()) { "任务运行中，请先停止任务".show(this); return }
        startActivity(Intent(this, TaskEditorActivity::class.java).putExtra(TaskEditorActivity.EXTRA_ID, id))
    }
    private fun confirmDelete(task: DailyTaskBean) {
        if (TaskScheduler.isRunning()) { "任务运行中，请先停止任务".show(this); return }
        DailyTaskDialogs.showConfirm(
            this,
            "删除这个任务？",
            "删除后不会再执行，历史记录仍会保留。",
            "删除"
        ) {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { DatabaseWrapper.deleteTask(task) }
                loadTasks()
            }
        }
    }
}
