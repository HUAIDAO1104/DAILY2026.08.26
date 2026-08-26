package com.pengxh.daily.app.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.github.gzuliyujiang.wheelpicker.widget.TimeWheelLayout
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textview.MaterialTextView
import com.pengxh.daily.app.R
import com.pengxh.daily.app.adapter.DailyTaskAdapter
import com.pengxh.daily.app.databinding.ActivityMainBinding
import com.pengxh.daily.app.extensions.convertToTimeEntity
import com.pengxh.daily.app.service.CaptureImageService
import com.pengxh.daily.app.service.FloatingWindowService
import com.pengxh.daily.app.service.ForegroundRunningService
import com.pengxh.daily.app.service.NotificationMonitorService
import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.daily.app.sqlite.bean.DailyTaskBean
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.AppUpdateInfo
import com.pengxh.daily.app.utils.AppUpdateManager
import com.pengxh.daily.app.utils.FloatingWindowController
import com.pengxh.daily.app.utils.GestureController
import com.pengxh.daily.app.utils.LogFileManager
import com.pengxh.daily.app.utils.LeaveManager
import com.pengxh.daily.app.utils.LeavePeriod
import com.pengxh.daily.app.utils.MaskViewController
import com.pengxh.daily.app.utils.MessageDispatcher
import com.pengxh.daily.app.utils.MonitorEvent
import com.pengxh.daily.app.utils.ProjectionSession
import com.pengxh.daily.app.utils.TaskDataManager
import com.pengxh.daily.app.utils.TaskScheduler
import com.pengxh.daily.app.utils.TipsEvent
import com.pengxh.daily.app.utils.UpdateCheckResult
import com.pengxh.daily.app.utils.displayName
import com.pengxh.kt.lite.base.KotlinBaseActivity
import com.pengxh.kt.lite.extensions.convertColor
import com.pengxh.kt.lite.extensions.dp2px
import com.pengxh.kt.lite.extensions.navigatePageTo
import com.pengxh.kt.lite.extensions.show
import com.pengxh.kt.lite.extensions.toJson
import com.pengxh.kt.lite.utils.SaveKeyValues
import com.pengxh.kt.lite.utils.LoadingDialog
import com.pengxh.kt.lite.widget.dialog.AlertInputDialog
import com.pengxh.kt.lite.widget.dialog.BottomActionSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.LocalTime
import java.util.Date
import java.util.Locale

class MainActivity : KotlinBaseActivity<ActivityMainBinding>() {

    companion object {
        private const val EXTRA_RETURN_FROM_TARGET = "return_from_target_app"
    }

    private val kTag = "MainActivity"
    private val context by lazy { this }
    private val homeDateFormat by lazy { SimpleDateFormat("M月d日 · EEEE", Locale.CHINA) }
    private val dateFormat by lazy { SimpleDateFormat("yyyy-MM-dd", Locale.CHINA) }
    private val permissionContract by lazy { ActivityResultContracts.StartActivityForResult() }
    private val taskDataManager by lazy { TaskDataManager() }

    private val insetsController by lazy {
        WindowCompat.getInsetsController(window, binding.rootView)
    }
    private val maskViewController by lazy { MaskViewController(this, binding, insetsController) }
    private val gestureController by lazy { GestureController(this, maskViewController) }
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private var runningStartedAt = 0L

    private var taskBeans = mutableListOf<DailyTaskBean>()
    private val dailyTaskAdapter by lazy {
        DailyTaskAdapter(taskBeans).apply {
            setOnItemClickListener(object : DailyTaskAdapter.OnItemClickListener {
                override fun onItemClick(position: Int) {
                    itemClick(position)
                }

                override fun onItemLongClick(position: Int) {
                    itemLongClick(position)
                }
            })
        }
    }

    /**
     * 每秒刷新日期与运行计时
     * */
    private val timeUpdateRunnable = object : Runnable {
        override fun run() {
            binding.homeDateView.text = "${homeDateFormat.format(Date())}　${TaskScheduler.getDayFlag()}"
            if (TaskScheduler.isRunning() && runningStartedAt > 0L) {
                val elapsedSeconds = (SystemClock.elapsedRealtime() - runningStartedAt) / 1000
                binding.nextTaskTimeView.text = String.format(
                    Locale.getDefault(), "%02d:%02d", elapsedSeconds / 60, elapsedSeconds % 60
                )
            }
            mainHandler.postDelayed(this, 1000)
        }
    }

    override fun observeRequestState() {

    }

    override fun initViewBinding(): ActivityMainBinding {
        return ActivityMainBinding.inflate(layoutInflater)
    }

    override fun setupTopBarLayout() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbar) { view, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.setPadding(0, statusBarHeight, 0, 0)
            insets
        }

        // 显示时间
        mainHandler.post(timeUpdateRunnable)
        binding.heroCard.setupWith(binding.blurTarget).setBlurRadius(22f).setOverlayColor(
            R.color.glass_surface_soft.convertColor(this)
        )
        BottomNavController.bind(this, binding.root, BottomNavController.Tab.HOME)
    }

    override fun initOnCreate(savedInstanceState: Bundle?) {
        // 加载任务列表
        lifecycleScope.launch {
            taskBeans = withContext(Dispatchers.IO) {
                DatabaseWrapper.loadAllTask()
            }

            Log.d(kTag, "initOnCreate: ${taskBeans.toJson()}")

            if (taskBeans.isEmpty()) {
                binding.recyclerView.visibility = View.GONE
                binding.emptyView.visibility = View.VISIBLE
            } else {
                binding.recyclerView.visibility = View.VISIBLE
                binding.emptyView.visibility = View.GONE
            }

            binding.recyclerView.adapter = dailyTaskAdapter
            dailyTaskAdapter.refresh(taskBeans)
            updateHomeSummary()
            binding.recyclerView.itemAnimator = null
        }

        // 显示悬浮窗
        if (Settings.canDrawOverlays(this)) {
            Intent(this, FloatingWindowService::class.java).apply { startService(this) }
        } else {
            // 悬浮窗权限并显示悬浮窗
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            overlayPermissionLauncher.launch(intent)
        }

        // 前台服务（保活 + 托管 TaskScheduler 协程作用域 + 每日重置）
        Intent(this, ForegroundRunningService::class.java).apply { startForegroundService(this) }

        // ================================================================
        // 每个 lifecycleScope.launch 都是独立的协程，互斥，不能为了省事把协程合并，否则只会执行第一个协程的业务，其他的业务被挂起
        // ================================================================

        // 订阅每日重置时间倒计时
        lifecycleScope.launch {
            ForegroundRunningService.resetTickTime.collect { text ->
                binding.repeatTimeView.text = text
            }
        }

        // 订阅通知监听事件
        lifecycleScope.launch {
            NotificationMonitorService.events.collect { event -> handleMonitorEvent(event) }
        }

        // 订阅调度器运行状态 → 按钮 UI
        lifecycleScope.launch {
            TaskScheduler.isRunning.collectLatest { running ->
                if (running) {
                    if (runningStartedAt == 0L) runningStartedAt = SystemClock.elapsedRealtime()
                    binding.executeTaskButton.backgroundTintList = ColorStateList.valueOf(
                        R.color.accent_red_soft.convertColor(this@MainActivity)
                    )
                    binding.executeTaskButton.setTextColor(R.color.accent_red.convertColor(this@MainActivity))
                    binding.executeTaskButton.text = "●  实时 · 停止"
                } else {
                    runningStartedAt = 0L
                    dailyTaskAdapter.updateCurrentTaskState(-1)
                    binding.executeTaskButton.backgroundTintList = ColorStateList.valueOf(
                        R.color.accent_red_soft.convertColor(this@MainActivity)
                    )
                    binding.executeTaskButton.setTextColor(R.color.accent_red.convertColor(this@MainActivity))
                    binding.executeTaskButton.text = "●  已就绪"
                }
                updateHomeSummary()
            }
        }

        // 订阅超时回主页信号
        lifecycleScope.launch {
            TaskScheduler.returnToApp.collectLatest {
                backToMainActivity()
            }
        }

        // 订阅 TipsEvent → tipsView + adapter 高亮
        lifecycleScope.launch {
            TaskScheduler.tipsEvent.collectLatest { event ->
                when (event) {
                    is TipsEvent.Skip -> {
                        binding.tipsView.text = "今日为休息日或请假日，已跳过任务"
                        binding.tipsView.setTextColor(R.color.ios_green.convertColor(this@MainActivity))
                        MessageDispatcher.sendMessage(
                            "任务跳过通知", "当前为节假日，任务已自动跳过，请注意下次打卡时间"
                        )
                    }

                    is TipsEvent.Executing -> {
                        binding.tipsView.text = "准备执行第 ${event.index} 个任务"
                        binding.tipsView.setTextColor(R.color.theme_color.convertColor(this@MainActivity))
                        dailyTaskAdapter.updateCurrentTaskState(event.index - 1, event.actualTime)
                        binding.heroKickerView.text = "TASK RUNNING"
                        binding.heroTaskTitleView.text = "正在等待任务结果"
                        binding.heroProgressView.text = "第 ${event.index} / ${event.total} 项"

                        val content = buildString {
                            appendLine("准备执行第 ${event.index} 个任务")
                            appendLine("计划时间：${event.plannedTime}")
                            append("实际时间：${event.actualTime}")
                        }
                        MessageDispatcher.sendMessage("任务执行通知", content)
                    }

                    is TipsEvent.Completed -> {
                        dailyTaskAdapter.updateCurrentTaskState(-1)
                        binding.tipsView.text = "今日任务已全部执行完毕，等待下次任务"
                        binding.tipsView.setTextColor(R.color.ios_green.convertColor(this@MainActivity))
                        LogFileManager.writeLog("今日任务已全部执行完毕")
                        MessageDispatcher.sendMessage("任务状态通知", "今日任务已全部执行完毕")
                    }
                }
            }
        }

        // 兜底检查是否有错过的每日重置
        checkMissedReset()
        checkForUpdates(force = false, showNoUpdateMessage = false)
    }

    override fun initEvent() {
        val openAi = View.OnClickListener { navigatePageTo<AiAssistantActivity>() }
        binding.aiAssistantLayout.setOnClickListener(openAi)
        binding.aiTopButton.setOnClickListener(openAi)
        binding.aiPromptLayout.setOnClickListener(openAi)
        binding.addTaskButton.setOnClickListener {
            if (TaskScheduler.isRunning()) {
                "任务进行中，无法添加".show(this)
            } else {
                startActivity(Intent(this, TaskEditorActivity::class.java).putExtra(TaskEditorActivity.EXTRA_ID, -1))
            }
        }
        binding.executeTaskButton.setOnClickListener {
            if (TaskScheduler.isRunning()) {
                doStopTask()
            } else {
                lifecycleScope.launch {
                    val isEmpty = withContext(Dispatchers.IO) {
                        DatabaseWrapper.loadAllTask().isEmpty()
                    }
                    if (isEmpty) {
                        "循环任务启动失败，请先添加任务时间点".show(context)
                        return@launch
                    }
                    TaskScheduler.startTask()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!Settings.canDrawOverlays(this)) {
            "悬浮窗权限未开启，部分功能可能无法正常使用".show(this)
        }
        lifecycleScope.launch {
            taskBeans = withContext(Dispatchers.IO) { DatabaseWrapper.loadAllTask() }
            dailyTaskAdapter.refresh(taskBeans)
            binding.recyclerView.visibility = if (taskBeans.isEmpty()) View.GONE else View.VISIBLE
            binding.emptyView.visibility = if (taskBeans.isEmpty()) View.VISIBLE else View.GONE
            updateHomeSummary()
        }
        if (AppUpdateManager.hasPendingInstall()) {
            AppUpdateManager.installOrRequestPermission(this)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        LogFileManager.writeLog("onNewIntent: $packageName 回到前台")

        if (ProjectionSession.isStateActive()) {
            LogFileManager.writeLog("截屏服务正常：MediaProjection 有效")
        } else {
            LogFileManager.writeLog("截屏服务异常：MediaProjection 已失效")
            if (SaveKeyValues.loadInt(Constant.RESULT_SOURCE_KEY, Constant.DEFAULT_INDEX) == 1) {
                "截屏服务已断开，请重新授权".show(this)
                SaveKeyValues.saveInt(Constant.RESULT_SOURCE_KEY, 0)
            }
        }

        // 只有从目标打卡应用返回时才进入伪装息屏；底部导航回首页不能触发。
        if (intent.getBooleanExtra(EXTRA_RETURN_FROM_TARGET, false) &&
            !maskViewController.isMaskVisible()
        ) {
            maskViewController.showMaskView()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        maskViewController.destroy()
    }

    // ================================================================
    // NotificationMonitorService 状态观察 → UI 更新
    // ================================================================

    /**
     * 根据 MonitorEvent 驱动 UI 变化
     */
    private fun handleMonitorEvent(event: MonitorEvent) {
        when (event) {
            is MonitorEvent.ClockInSuccess -> {
                TaskScheduler.notifyClockIn() // 通知 TaskScheduler：打卡成功，取消超时等待分支
                backToMainActivity()
            }

            is MonitorEvent.StartTaskCommand -> {
                if (!TaskScheduler.isRunning()) {
                    TaskScheduler.startTask()
                }
            }

            is MonitorEvent.StopTaskCommand -> doStopTask()

            is MonitorEvent.ShowMaskCommand -> {
                if (!maskViewController.isMaskVisible()) {
                    maskViewController.showMaskView()
                }
            }

            is MonitorEvent.HideMaskCommand -> {
                if (maskViewController.isMaskVisible()) {
                    maskViewController.hideMaskView()
                }
            }

            is MonitorEvent.AppOpenedForScreenshot -> {
                captureTargetAppAndReturn(
                    countdownSeconds = 10,
                    messageTitle = "截屏状态通知",
                    successMessage = "截图完成",
                    failureMessage = "截图完成，但是无法获取截图"
                )
            }

            is MonitorEvent.AppOpenedForRemoteClockIn -> {
                if (event.returnScreenshot) {
                    captureTargetAppAndReturn(
                        countdownSeconds = event.countdownSeconds,
                        messageTitle = "打卡结果通知",
                        successMessage = "打卡完成，结果见附件",
                        failureMessage = "打卡完成，但是无法获取截图"
                    )
                } else {
                    returnAfterCountdown(event.countdownSeconds)
                }
            }
        }
    }

    private fun returnAfterCountdown(countdownSeconds: Int) {
        lifecycleScope.launch {
            countdownTargetApp(countdownSeconds)
            backToMainActivity()
        }
    }

    /**
     * 远程截屏与远程打卡的公共流程：倒计时 → 截屏 → 返回 → 发送结果。
     */
    private fun captureTargetAppAndReturn(
        countdownSeconds: Int,
        messageTitle: String,
        successMessage: String,
        failureMessage: String
    ) {
        lifecycleScope.launch {
            countdownTargetApp(countdownSeconds)

            // 在返回前等待截屏结果，避免 Activity 生命周期变化导致结果丢失。
            val imagePath = CaptureImageService.requestCaptureScreen().await()
            backToMainActivity()

            if (imagePath.isNullOrEmpty()) {
                MessageDispatcher.sendMessage(messageTitle, failureMessage)
            } else {
                MessageDispatcher.sendAttachmentMessage(
                    messageTitle, successMessage, imagePath
                )
            }
        }
    }

    private suspend fun countdownTargetApp(countdownSeconds: Int) {
        val countdownTarget = SystemClock.elapsedRealtime() + countdownSeconds * 1000L
        while (true) {
            val remaining = countdownTarget - SystemClock.elapsedRealtime()
            if (remaining <= 0) break
            FloatingWindowController.updateTime((remaining / 1000).toInt())
            delay(minOf(1000L, remaining).coerceAtLeast(1))
        }
        FloatingWindowController.hide()
    }

    // ================================================================
    // 用户交互
    // ================================================================

    /**
     * 列表项单击
     * */
    private fun itemClick(position: Int) {
        if (TaskScheduler.isRunning()) {
            "任务进行中，无法修改".show(this)
            return
        }
        startActivity(
            Intent(this, TaskEditorActivity::class.java)
                .putExtra(TaskEditorActivity.EXTRA_ID, taskBeans[position].id)
        )
    }

    /**
     * 列表项长按
     * */
    private fun itemLongClick(position: Int) {
        if (TaskScheduler.isRunning()) {
            "任务进行中，无法删除".show(this)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("删除任务")
            .setMessage("确定要删除这个任务吗？")
            .setCancelable(false) // 禁止点击外部关闭
            .setPositiveButton("确定") { _, _ ->
                try {
                    lifecycleScope.launch {
                        val item = taskBeans[position]
                        withContext(Dispatchers.IO) {
                            DatabaseWrapper.deleteTask(item)
                        }

                        // 为了确保数据一致性，重新从数据库加载数据
                        taskBeans = withContext(Dispatchers.IO) {
                            DatabaseWrapper.loadAllTask()
                        }
                        dailyTaskAdapter.refresh(taskBeans)
                        updateHomeSummary()

                        if (taskBeans.isEmpty()) {
                            binding.recyclerView.visibility = View.GONE
                            binding.emptyView.visibility = View.VISIBLE
                        } else {
                            binding.recyclerView.visibility = View.VISIBLE
                            binding.emptyView.visibility = View.GONE
                        }
                    }
                } catch (e: IndexOutOfBoundsException) {
                    e.printStackTrace()
                }
            }.setNegativeButton("取消", null).show()
    }

    private fun createTask() {
        val view = layoutInflater.inflate(R.layout.bottom_sheet_layout_select_time, null)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(view)
        val titleView = view.findViewById<MaterialTextView>(R.id.titleView)
        titleView.text = "添加任务"
        val timePicker = view.findViewById<TimeWheelLayout>(R.id.timePicker)
        view.findViewById<MaterialButton>(R.id.saveButton).setOnClickListener {
            val time = String.format(
                Locale.getDefault(),
                "%02d:%02d:%02d",
                timePicker.selectedHour,
                timePicker.selectedMinute,
                timePicker.selectedSecond
            )

            lifecycleScope.launch {
                val exist = withContext(Dispatchers.IO) {
                    DatabaseWrapper.isTaskTimeExist(time)
                }
                if (exist) {
                    "任务时间点已存在".show(context)
                    return@launch
                }
                binding.recyclerView.visibility = View.VISIBLE
                binding.emptyView.visibility = View.GONE
                val bean = DailyTaskBean().apply {
                    this.time = time
                }
                withContext(Dispatchers.IO) {
                    DatabaseWrapper.insert(bean)
                }
                taskBeans = withContext(Dispatchers.IO) {
                    DatabaseWrapper.loadAllTask()
                }
                dailyTaskAdapter.refresh(taskBeans)
                updateHomeSummary()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun importTask() {
        AlertInputDialog.Builder()
            .setContext(this)
            .setTitle("导入任务")
            .setHintMessage("请将导出的任务粘贴到这里")
            .setNegativeButton("取消")
            .setPositiveButton("确定")
            .setOnDialogButtonClickListener(object :
                AlertInputDialog.OnDialogButtonClickListener {
                override fun onConfirmClick(value: String) {
                    // 同一个业务，可以使用同一个协程作用域，避免重复创建
                    lifecycleScope.launch {
                        val result = withContext(Dispatchers.IO) {
                            taskDataManager.importTasks(value)
                        }
                        when (result) {
                            is TaskDataManager.ImportResult.Success -> {
                                if (result.count > 0) {
                                    taskBeans = withContext(Dispatchers.IO) {
                                        DatabaseWrapper.loadAllTask()
                                    }
                                    dailyTaskAdapter.refresh(taskBeans)
                                    updateHomeSummary()
                                    binding.recyclerView.visibility = View.VISIBLE
                                    binding.emptyView.visibility = View.GONE
                                }
                                "任务导入成功".show(context)
                            }

                            is TaskDataManager.ImportResult.Error -> {
                                result.message.show(context)
                            }
                        }
                    }
                }

                override fun onCancelClick() {}
            }).build().show()
    }

    private suspend fun updateHomeSummary() {
        val now = LocalTime.now()
        val enabledTasks = taskBeans.filter { it.isEnabled }
        val nextBean = enabledTasks
            .firstOrNull { bean -> runCatching { LocalTime.parse(bean.time).isAfter(now) }.getOrDefault(false) }
        val next = enabledTasks
            .mapNotNull { bean -> runCatching { LocalTime.parse(bean.time) }.getOrNull() }
            .firstOrNull { it.isAfter(now) }

        val periods = withContext(Dispatchers.IO) { LeaveManager.periodsFor(LocalDate.now()) }
        val allDayLeave = LeavePeriod.ALL_DAY in periods ||
                (LeavePeriod.MORNING in periods && LeavePeriod.AFTERNOON in periods)
        val records = withContext(Dispatchers.IO) {
            DatabaseWrapper.loadExecutionRecordsForDate(LocalDate.now())
        }
        val completed = records.count { it.status == com.pengxh.daily.app.utils.ExecutionRecordManager.SUCCESS }
            .coerceAtMost(enabledTasks.size)
        binding.taskCountView.text = if (allDayLeave) "均已跳过" else "$completed / ${enabledTasks.size}"
        binding.heroProgressView.text = if (allDayLeave) "今日任务已暂停" else "今日完成 $completed / ${enabledTasks.size}"

        when {
            allDayLeave -> {
                binding.heroKickerView.text = "REST DAY"
                binding.nextTaskTimeView.text = "今天，\n安心休息。"
                binding.nextTaskTimeView.textSize = 38f
                binding.nextTaskTimeView.letterSpacing = -0.04f
                binding.heroTaskTitleView.text = "全部任务已暂停 · 明天自动恢复"
                binding.tipsView.text = "请假优先级高于日常任务规则"
                binding.repeatTimeView.text = "无需手动开启"
                binding.executeTaskButton.text = "全天请假"
                binding.executeTaskButton.isEnabled = TaskScheduler.isRunning()
                binding.aiInsightNoteView.text = "已识别全天请假，任务将在明天自动恢复"
            }
            TaskScheduler.isRunning() -> {
                binding.heroKickerView.text = "TASK RUNNING"
                binding.nextTaskTimeView.textSize = 52f
                binding.nextTaskTimeView.letterSpacing = -0.06f
                binding.heroTaskTitleView.text = "${nextBean?.displayName() ?: "今日任务"}　等待结果通知"
                binding.repeatTimeView.text = "目标应用运行中"
                binding.executeTaskButton.isEnabled = true
                binding.aiInsightNoteView.text = "目标应用运行正常，正在等待结果通知"
            }
            else -> {
                binding.heroKickerView.text = "NEXT TASK"
                binding.nextTaskTimeView.text = next?.toString()?.take(5) ?: "--:--"
                binding.nextTaskTimeView.textSize = 54f
                binding.nextTaskTimeView.letterSpacing = -0.06f
                val target = when (SaveKeyValues.loadInt(Constant.TARGET_APP_KEY, 0)) {
                    1 -> "企业微信"
                    2 -> "飞书"
                    3 -> "移动办公 M3"
                    else -> "钉钉"
                }
                binding.heroTaskTitleView.text = "${nextBean?.displayName() ?: "今天没有待执行任务"}　$target"
                val range = SaveKeyValues.loadInt(Constant.TIME_RANGE_KEY, Constant.DEFAULT_TIME_RANGE)
                val random = SaveKeyValues.loadBoolean(Constant.RANDOM_TIME_KEY, true)
                binding.repeatTimeView.text = if (next == null) "等待明日任务" else if (random) "随机 ±$range 分钟" else "按计划时间执行"
                binding.tipsView.text = if (next == null) "今天已无待执行时间" else "通知、权限与日期规则已检查"
                binding.executeTaskButton.text = "●  已就绪"
                binding.executeTaskButton.isEnabled = enabledTasks.isNotEmpty()
                binding.aiInsightNoteView.text = if (enabledTasks.isEmpty()) "还没有任务，添加后即可开始" else "权限、网络与日期规则正常，可按时执行"
            }
        }
    }

    private fun checkForUpdates(force: Boolean, showNoUpdateMessage: Boolean) {
        lifecycleScope.launch {
            when (val result = AppUpdateManager.check(this@MainActivity, force)) {
                is UpdateCheckResult.Available -> showUpdateDialog(result.info)
                is UpdateCheckResult.Error -> if (showNoUpdateMessage) result.message.show(this@MainActivity)
                UpdateCheckResult.NoPublishedRelease -> if (showNoUpdateMessage) "暂未发布可下载版本".show(this@MainActivity)
                UpdateCheckResult.UpToDate -> if (showNoUpdateMessage) "当前已是最新版本".show(this@MainActivity)
            }
        }
    }

    private fun showUpdateDialog(info: AppUpdateInfo) {
        MaterialAlertDialogBuilder(this)
            .setTitle("发现新版本 ${info.version}")
            .setMessage(info.notes.ifBlank { "修复问题并改进使用体验。" })
            .setNegativeButton("稍后", null)
            .setPositiveButton("备份并更新") { _, _ ->
                lifecycleScope.launch {
                    LoadingDialog.show(this@MainActivity, "正在下载更新...")
                    try {
                        val apk = AppUpdateManager.download(this@MainActivity, info)
                        LoadingDialog.dismiss()
                        if (!AppUpdateManager.installOrRequestPermission(this@MainActivity, apk)) {
                            "请允许安装更新，返回后会继续".show(this@MainActivity)
                        }
                    } catch (e: Exception) {
                        LoadingDialog.dismiss()
                        (e.message ?: "更新下载失败").show(this@MainActivity)
                    }
                }
            }
            .show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (maskViewController.isMaskVisible()) {
                maskViewController.hideMaskView()
            } else {
                maskViewController.showMaskView()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        ev?.let {
            gestureController.onTouchEvent(it)
        }
        return super.dispatchTouchEvent(ev)
    }

    // ================================================================
    // 辅助方法
    // ================================================================

    private fun doStopTask() {
        if (!TaskScheduler.isRunning()) return
        TaskScheduler.stopTask()
        MessageDispatcher.sendMessage("停止任务通知", "任务停止成功，请及时打开下次任务")
    }

    private fun backToMainActivity() {
        val returnIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_RETURN_FROM_TARGET, true)
        }
        if (SaveKeyValues.loadBoolean(Constant.BACK_TO_HOME_KEY, false)) {
            //模拟点击Home键
            startActivity(Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) })
            lifecycleScope.launch(Dispatchers.IO) {
                delay(1000)
                withContext(Dispatchers.Main) {
                    startActivity(returnIntent)
                }
            }
        } else {
            startActivity(returnIntent)
        }
    }

    /**
     * 兜底检查：覆盖 Alarm 未触发的场景
     * */
    private fun checkMissedReset() {
        val lastResetDate = SaveKeyValues.loadString(Constant.LAST_RESET_DATE_KEY, "")
        val today = dateFormat.format(Date())

        // 今天已重置，跳过（防止重复执行）
        if (lastResetDate == today) {
            return
        }

        // 今天还未重置，执行重置（覆盖 Alarm 未触发的场景）
        LogFileManager.writeLog("检测到今日尚未重置，执行重置操作")
        SaveKeyValues.saveString(Constant.LAST_RESET_DATE_KEY, today)

        if (SaveKeyValues.loadBoolean(Constant.TASK_AUTO_RECYCLE_KEY, true)) {
            TaskScheduler.startTask()
        }
    }

    /**
     * 悬浮窗权限启动器
     * */
    private val overlayPermissionLauncher = registerForActivityResult(permissionContract) {
        if (Settings.canDrawOverlays(this)) {
            Intent(this, FloatingWindowService::class.java).apply {
                startService(this)
            }
        }
    }
}
