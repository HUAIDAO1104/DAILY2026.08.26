package com.pengxh.daily.app.ui

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.pengxh.daily.app.R
import com.pengxh.daily.app.ai.AiConfigStore
import com.pengxh.daily.app.ai.AiChatTurn
import com.pengxh.daily.app.ai.AiModelOption
import com.pengxh.daily.app.ai.AiPlanner
import com.pengxh.daily.app.ai.DailyTaskOperations
import com.pengxh.daily.app.ai.ValidatedAiPlan
import com.pengxh.daily.app.databinding.ActivityAiAssistantBinding
import com.pengxh.daily.app.widget.AiCompanionView
import com.pengxh.kt.lite.base.KotlinBaseActivity
import com.pengxh.kt.lite.extensions.dp2px
import com.pengxh.kt.lite.extensions.show
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AiAssistantActivity : KotlinBaseActivity<ActivityAiAssistantBinding>() {
    private val configStore by lazy { AiConfigStore(this) }
    private val planner by lazy { AiPlanner(configStore) }
    private val operations by lazy { DailyTaskOperations(this) }
    private var isBusy = false
    private val conversationHistory = mutableListOf<AiChatTurn>()

    override fun initViewBinding() = ActivityAiAssistantBinding.inflate(layoutInflater)

    override fun observeRequestState() = Unit

    override fun setupTopBarLayout() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbar) { view, insets ->
            view.setPadding(0, insets.getInsets(WindowInsetsCompat.Type.statusBars()).top, 8.dp2px(this), 0)
            insets
        }
        val baseBottom = 14.dp2px(this)
        fun applyBottomInset(insets: WindowInsetsCompat) {
            val navigation = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            binding.composerContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = baseBottom + maxOf(navigation, keyboard)
            }
            binding.assistantPresenceCard.visibility = if (keyboard > 0) View.GONE else View.VISIBLE
            binding.suggestionScroll.visibility = if (keyboard > 0) View.GONE else View.VISIBLE
            if (keyboard > 0) scrollToBottom()
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.assistantContent) { _, insets ->
            applyBottomInset(insets)
            insets
        }
        ViewCompat.setWindowInsetsAnimationCallback(
            binding.assistantContent,
            object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: MutableList<WindowInsetsAnimationCompat>
                ): WindowInsetsCompat {
                    applyBottomInset(insets)
                    return insets
                }
            }
        )
        binding.closeButton.setOnClickListener { finish() }
    }

    override fun initOnCreate(savedInstanceState: Bundle?) {
        overridePendingTransition(R.anim.ai_enter, R.anim.stay)
        setAssistantState(AiCompanionView.State.IDLE)
        val welcome = "你可以直接告诉我要做什么，也可以自然地问我任务和设置问题。我会结合上下文理解，并在修改前列出完整计划。\n\n例如：‘明天下午请假’、‘把 8 点和 9 点任务都推迟半小时’。"
        addMessage(
            welcome,
            fromUser = false
        )
        conversationHistory += AiChatTurn("assistant", welcome)
    }

    override fun initEvent() {
        binding.sendButton.setOnClickListener { submitPrompt() }
        binding.promptInput.doAfterTextChanged { refreshSendAction() }
        binding.promptInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitPrompt()
                true
            } else false
        }
        binding.aiConfigButton.setOnClickListener { showConfigDialog() }
        binding.leaveSuggestion.setOnClickListener { sendSuggestion("明天全天请假，不执行打卡任务") }
        binding.weekendSuggestion.setOnClickListener { sendSuggestion("周末和法定节假日不打卡") }
        binding.randomSuggestion.setOnClickListener { sendSuggestion("关闭随机时间") }
        binding.promptInput.setOnFocusChangeListener { _, focused ->
            if (focused) {
                setAssistantState(AiCompanionView.State.AWARE)
                scrollToBottom()
            } else if (!isBusy) {
                setAssistantState(AiCompanionView.State.IDLE)
            }
        }
        binding.assistantPresenceCard.setOnClickListener {
            binding.promptInput.requestFocus()
            setAssistantState(AiCompanionView.State.AWARE)
        }
        binding.assistantOrb.isClickable = true
        binding.assistantOrb.setOnClickListener {
            binding.promptInput.requestFocus()
            setAssistantState(AiCompanionView.State.AWARE)
        }
        refreshSendAction()
    }

    private fun sendSuggestion(text: String) {
        binding.promptInput.setText(text)
        submitPrompt()
    }

    private fun submitPrompt() {
        val command = binding.promptInput.text?.toString()?.trim().orEmpty()
        if (command.isBlank() || isBusy) return
        binding.sendButton.animate()
            .translationX(3.dp2px(this).toFloat())
            .translationY((-3).dp2px(this).toFloat())
            .scaleX(0.88f)
            .scaleY(0.88f)
            .setDuration(90L)
            .withEndAction {
                binding.sendButton.animate()
                    .translationX(0f)
                    .translationY(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(130L)
                    .start()
            }
            .start()
        binding.promptInput.text?.clear()
        addMessage(command, fromUser = true)
        val historySnapshot = conversationHistory.takeLast(10)
        conversationHistory += AiChatTurn("user", command)
        setLoading(true)
        lifecycleScope.launch {
            try {
                val state = withContext(Dispatchers.IO) { operations.buildStateJson() }
                val plan = planner.createPlan(command, state, historySnapshot)
                if (plan.actions.isEmpty()) {
                    setAssistantState(AiCompanionView.State.SPEAKING)
                    val reply = plan.reply.ifBlank { "我还不能确定你想修改什么，可以换一种更具体的说法。" }
                    addMessage(reply, false)
                    conversationHistory += AiChatTurn("assistant", reply)
                    celebrateAssistant()
                } else {
                    val validated = operations.validate(plan)
                    addPlanCard(validated)
                    conversationHistory += AiChatTurn(
                        "assistant",
                        buildString {
                            append(validated.summary)
                            validated.previews.forEachIndexed { index, preview ->
                                append("\n${index + 1}. $preview")
                            }
                        }
                    )
                    setAssistantState(AiCompanionView.State.AWARE)
                }
            } catch (e: Exception) {
                val error = e.message ?: "处理失败，请稍后重试"
                addMessage(error, false)
                conversationHistory += AiChatTurn("assistant", error)
                showAssistantError()
            } finally {
                setLoading(false)
            }
        }
    }

    private fun addPlanCard(plan: ValidatedAiPlan) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14.dp2px(this@AiAssistantActivity), 13.dp2px(this@AiAssistantActivity), 14.dp2px(this@AiAssistantActivity), 13.dp2px(this@AiAssistantActivity))
            setBackgroundResource(R.drawable.bg_glass_card_small)
        }
        card.addView(TextView(this).apply {
            text = plan.summary
            setTextColor(getColor(R.color.ai_text_warm))
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        plan.previews.forEachIndexed { index, preview ->
            card.addView(TextView(this).apply {
                text = "${index + 1}. $preview"
                setTextColor(getColor(R.color.text_secondary_dark))
                textSize = 14f
                setPadding(0, 9.dp2px(this@AiAssistantActivity), 0, 0)
            })
        }
        card.addView(TextView(this).apply {
            text = "执行前会自动保存本机配置快照"
            setTextColor(getColor(R.color.text_tertiary_dark))
            textSize = 12f
            setPadding(0, 11.dp2px(this@AiAssistantActivity), 0, 0)
        })
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        lateinit var confirm: MaterialButton
        val cancel = MaterialButton(this).apply {
            text = "取消"
            setTextColor(getColor(R.color.text_secondary_dark))
            backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.surface_elevated_dark))
            cornerRadius = 12.dp2px(this@AiAssistantActivity)
            setOnClickListener {
                card.alpha = 0.5f
                isEnabled = false
                confirm.isEnabled = false
                addMessage("已取消，没有修改任何内容。", false)
                conversationHistory += AiChatTurn("assistant", "用户取消了上一项操作计划，没有执行修改。")
            }
        }
        confirm = MaterialButton(this).apply {
            text = if (plan.requiresDangerConfirmation) "继续核对" else "确认执行"
            setTextColor(getColor(R.color.white))
            backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.accent_red_deep))
            cornerRadius = 12.dp2px(this@AiAssistantActivity)
            setOnClickListener {
                card.alpha = 0.5f
                cancel.isEnabled = false
                isEnabled = false
                if (plan.requiresDangerConfirmation) addDangerConfirmation(plan) else executePlan(plan)
            }
        }
        actions.addView(cancel, LinearLayout.LayoutParams(0, 44.dp2px(this), 1f).apply { topMargin = 12.dp2px(this@AiAssistantActivity) })
        actions.addView(confirm, LinearLayout.LayoutParams(0, 44.dp2px(this), 1f).apply { topMargin = 12.dp2px(this@AiAssistantActivity); marginStart = 8.dp2px(this@AiAssistantActivity) })
        card.addView(actions)
        binding.conversationLayout.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 4.dp2px(this@AiAssistantActivity)
            bottomMargin = 10.dp2px(this@AiAssistantActivity)
        })
        scrollToBottom()
    }

    private fun addDangerConfirmation(plan: ValidatedAiPlan) {
        val warning = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14.dp2px(this@AiAssistantActivity), 13.dp2px(this@AiAssistantActivity), 14.dp2px(this@AiAssistantActivity), 13.dp2px(this@AiAssistantActivity))
            setBackgroundResource(R.drawable.bg_glass_card_small)
        }
        warning.addView(TextView(this).apply {
            text = "再次确认"
            setTextColor(getColor(R.color.warning_amber))
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        warning.addView(TextView(this).apply {
            text = "计划包含删除或恢复操作。执行前仍会创建快照，可在配置备份中恢复。"
            setTextColor(getColor(R.color.text_secondary_dark))
            textSize = 14f
            setPadding(0, 8.dp2px(this@AiAssistantActivity), 0, 0)
        })
        val execute = MaterialButton(this).apply {
            text = "确认并执行"
            setTextColor(getColor(R.color.white))
            backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.accent_red_deep))
            cornerRadius = 12.dp2px(this@AiAssistantActivity)
            setOnClickListener { isEnabled = false; warning.alpha = 0.5f; executePlan(plan) }
        }
        warning.addView(execute, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 44.dp2px(this)).apply { topMargin = 12.dp2px(this@AiAssistantActivity) })
        binding.conversationLayout.addView(warning, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 10.dp2px(this@AiAssistantActivity) })
        scrollToBottom()
    }

    private fun executePlan(plan: ValidatedAiPlan) {
        setLoading(true)
        lifecycleScope.launch {
            try {
                val results = withContext(Dispatchers.IO) { operations.execute(plan) }
                val reply = "操作完成\n${results.joinToString("\n") { "• $it" }}"
                addMessage(reply, false)
                conversationHistory += AiChatTurn("assistant", reply)
                celebrateAssistant()
            } catch (e: Exception) {
                val reply = "执行中止：${e.message ?: "未知错误"}\n已完成的操作可能已生效，可通过配置快照恢复。"
                addMessage(reply, false)
                conversationHistory += AiChatTurn("assistant", reply)
                showAssistantError()
            } finally {
                setLoading(false)
            }
        }
    }

    private fun showConfigDialog() {
        val inflationParent = FrameLayout(this)
        val view = layoutInflater.inflate(R.layout.dialog_ai_config, inflationParent, false)
        val endpoint = view.findViewById<TextView>(R.id.endpointValueView)
        val apiKeyLayout = view.findViewById<TextInputLayout>(R.id.apiKeyLayout)
        val apiKey = view.findViewById<TextInputEditText>(R.id.apiKeyInput)
        val loadModels = view.findViewById<MaterialButton>(R.id.loadModelsButton)
        val loading = view.findViewById<CircularProgressIndicator>(R.id.modelLoadingIndicator)
        val connectionStatus = view.findViewById<TextView>(R.id.connectionStatusView)
        val modelSelector = view.findViewById<View>(R.id.modelSelector)
        val modelValue = view.findViewById<TextView>(R.id.modelValueView)
        val modelMeta = view.findViewById<TextView>(R.id.modelMetaView)
        val modelExpandIcon = view.findViewById<ImageView>(R.id.modelExpandIcon)
        val modelCatalog = view.findViewById<LinearLayout>(R.id.modelCatalog)
        val modelCount = view.findViewById<TextView>(R.id.modelCountView)
        val modelSearch = view.findViewById<TextInputEditText>(R.id.modelSearchInput)
        val modelRecycler = view.findViewById<RecyclerView>(R.id.modelRecyclerView)
        val configScroll = view.findViewById<androidx.core.widget.NestedScrollView>(R.id.configScrollView)
        val saveButton = view.findViewById<MaterialButton>(R.id.saveConfigButton)
        val existing = configStore.load()
        endpoint.text = AiConfigStore.FIXED_BASE_URL
        if (existing.apiKey.isNotBlank()) apiKey.hint = "已安全保存 · 输入新密钥可替换"

        var allModels = emptyList<AiModelOption>()
        var selectedModel = existing.model
        var newKeyVerified = false
        var loadingModels = false
        lateinit var adapter: ModelAdapter

        fun updateSelectedModel(option: AiModelOption? = allModels.firstOrNull { it.id == selectedModel }) {
            modelValue.text = selectedModel.ifBlank { "尚未选择" }
            modelMeta.text = when {
                selectedModel.isBlank() -> "读取列表后选择，不需要手动填写"
                option == null -> "已保存的模型 · 读取列表可校验是否仍可用"
                !option.supportsOpenAi -> "此模型未声明支持当前接口"
                option.owner.isBlank() -> "OpenAI 兼容模型"
                else -> "${option.owner} · OpenAI 兼容"
            }
            adapter.setSelectedModel(selectedModel)
        }

        fun showCatalog(show: Boolean) {
            modelCatalog.visibility = if (show) View.VISIBLE else View.GONE
            modelExpandIcon.rotation = if (show) 180f else 0f
            if (show) configScroll.post { configScroll.smoothScrollTo(0, modelSelector.bottom) }
        }

        fun filterModels(query: String) {
            val normalized = query.trim().lowercase()
            val filtered = if (normalized.isBlank()) allModels else allModels.filter {
                it.id.lowercase().contains(normalized) || it.owner.lowercase().contains(normalized)
            }
            modelCount.text = if (filtered.size == allModels.size) "${allModels.size} 个" else "${filtered.size} / ${allModels.size}"
            adapter.submitList(filtered, selectedModel)
        }

        adapter = ModelAdapter { option ->
            selectedModel = option.id
            updateSelectedModel(option)
            connectionStatus.text = "已选择 ${option.id}"
            connectionStatus.setTextColor(ContextCompat.getColor(this, R.color.success_green))
        }
        modelRecycler.adapter = adapter
        updateSelectedModel()
        modelSearch.doAfterTextChanged { filterModels(it?.toString().orEmpty()) }

        val dialog = BottomSheetDialog(this)
        dialog.setContentView(view)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.setOnShowListener {
            val sheet = dialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.apply {
                setBackgroundColor(Color.TRANSPARENT)
                layoutParams.height = (resources.displayMetrics.heightPixels * 0.94f).toInt()
                requestLayout()
            }
            sheet?.let {
                BottomSheetBehavior.from(it).apply {
                    state = BottomSheetBehavior.STATE_EXPANDED
                    skipCollapsed = true
                    isDraggable = true
                }
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(view) { sheet, insets ->
            sheet.setPadding(0, 0, 0, insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom)
            insets
        }

        fun fetchModels(expandWhenReady: Boolean) {
            if (loadingModels) return
            val enteredKey = apiKey.text?.toString()?.trim().orEmpty()
            val effectiveKey = enteredKey.ifBlank { existing.apiKey }
            if (effectiveKey.isBlank()) {
                apiKeyLayout.error = "请先填写 API Key"
                apiKey.requestFocus()
                return
            }
            apiKeyLayout.error = null
            loadingModels = true
            loadModels.isEnabled = false
            loading.visibility = View.VISIBLE
            connectionStatus.text = "正在连接接口并读取模型…"
            connectionStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary_dark))
            lifecycleScope.launch {
                try {
                    val result = planner.fetchAvailableModels(effectiveKey)
                    if (!dialog.isShowing) return@launch
                    allModels = result
                    newKeyVerified = enteredKey.isNotBlank()
                    val matched = allModels.firstOrNull { it.id == selectedModel && it.supportsOpenAi }
                    val recommended = matched
                        ?: allModels.firstOrNull { it.id.equals("qwen3-max", ignoreCase = true) && it.supportsOpenAi }
                        ?: allModels.firstOrNull { it.supportsOpenAi }
                        ?: error("模型列表中没有支持 OpenAI 兼容接口的模型")
                    selectedModel = recommended.id
                    updateSelectedModel(recommended)
                    filterModels(modelSearch.text?.toString().orEmpty())
                    connectionStatus.text = "连接成功，已读取 ${allModels.size} 个模型"
                    connectionStatus.setTextColor(ContextCompat.getColor(this@AiAssistantActivity, R.color.success_green))
                    if (expandWhenReady) showCatalog(true)
                } catch (e: Exception) {
                    if (!dialog.isShowing) return@launch
                    connectionStatus.text = e.message ?: "模型列表读取失败"
                    connectionStatus.setTextColor(ContextCompat.getColor(this@AiAssistantActivity, R.color.accent_red))
                    showCatalog(false)
                } finally {
                    loadingModels = false
                    loadModels.isEnabled = true
                    loading.visibility = View.GONE
                }
            }
        }

        apiKey.doAfterTextChanged {
            if (!it.isNullOrBlank()) {
                newKeyVerified = false
                connectionStatus.text = "密钥已更改，请重新验证并读取模型"
                connectionStatus.setTextColor(ContextCompat.getColor(this, R.color.warning_amber))
            }
        }
        loadModels.setOnClickListener { fetchModels(expandWhenReady = true) }
        modelSelector.setOnClickListener {
            if (allModels.isEmpty()) fetchModels(expandWhenReady = true) else showCatalog(modelCatalog.visibility != View.VISIBLE)
        }
        view.findViewById<View>(R.id.closeConfigButton).setOnClickListener { dialog.dismiss() }
        saveButton.setOnClickListener {
            val enteredKey = apiKey.text?.toString()?.trim().orEmpty()
            if (enteredKey.isNotBlank() && !newKeyVerified) {
                apiKeyLayout.error = "请先验证新密钥并读取模型"
                connectionStatus.text = "验证通过后才能保存新密钥"
                connectionStatus.setTextColor(ContextCompat.getColor(this, R.color.warning_amber))
                return@setOnClickListener
            }
            if (selectedModel.isBlank()) {
                "请先读取并选择模型".show(this)
                return@setOnClickListener
            }
            if (allModels.firstOrNull { it.id == selectedModel }?.supportsOpenAi == false) {
                "当前模型不支持 OpenAI 兼容接口，请选择其他模型".show(this)
                return@setOnClickListener
            }
            if (enteredKey.isBlank() && existing.apiKey.isBlank()) {
                apiKeyLayout.error = "请填写并验证 API Key"
                return@setOnClickListener
            }
            configStore.save(selectedModel, enteredKey.takeIf { it.isNotBlank() })
            dialog.dismiss()
            "模型设置已保存".show(this)
        }
        dialog.show()
        if (existing.apiKey.isNotBlank()) view.post { fetchModels(expandWhenReady = false) }
    }

    private fun addMessage(message: String, fromUser: Boolean) {
        val textView = TextView(this).apply {
            text = message
            setTextColor(getColor(R.color.text_primary_dark))
            textSize = 15f
            setLineSpacing(0f, 1.18f)
            setPadding(14.dp2px(this@AiAssistantActivity), 11.dp2px(this@AiAssistantActivity), 14.dp2px(this@AiAssistantActivity), 11.dp2px(this@AiAssistantActivity))
            setBackgroundResource(if (fromUser) R.drawable.bg_message_user else R.drawable.bg_message_assistant)
        }
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = if (fromUser) Gravity.END else Gravity.START
            topMargin = 8.dp2px(this@AiAssistantActivity)
            marginStart = if (fromUser) 48.dp2px(this@AiAssistantActivity) else 0
            marginEnd = if (fromUser) 0 else 36.dp2px(this@AiAssistantActivity)
        }
        binding.conversationLayout.addView(textView, params)
        scrollToBottom()
    }

    private fun setLoading(loading: Boolean) {
        isBusy = loading
        refreshSendAction()
        if (loading) setAssistantState(AiCompanionView.State.THINKING)
    }

    private fun refreshSendAction() {
        val enabled = !isBusy && !binding.promptInput.text.isNullOrBlank()
        binding.sendButton.isEnabled = enabled
        binding.sendButton.isClickable = enabled
        binding.sendButton.alpha = if (enabled) 1f else 0.38f
    }

    private fun scrollToBottom() {
        binding.conversationScrollView.post {
            binding.conversationScrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun setAssistantState(state: AiCompanionView.State) {
        binding.assistantOrb.setState(state)
        binding.headerOrb.setState(state)
        val copy = when (state) {
            AiCompanionView.State.IDLE -> "在这儿，随时可以开始" to "告诉我你想修改的任务、请假或设置"
            AiCompanionView.State.AWARE -> "我在听" to "把想做的事直接说出来就好"
            AiCompanionView.State.SLEEPING -> "暂时休息中" to "点一下小球即可唤醒"
            AiCompanionView.State.LISTENING -> "正在听你说" to "说完后我会先整理操作步骤"
            AiCompanionView.State.TRANSCRIBING -> "正在识别" to "马上把内容整理成可执行操作"
            AiCompanionView.State.THINKING -> "正在整理操作" to "我会先给你核对，再执行修改"
            AiCompanionView.State.SPEAKING -> "正在回答" to "结果和需要确认的步骤都在下面"
            AiCompanionView.State.SUCCESS -> "已经处理好啦" to "修改结果已保存到本机"
            AiCompanionView.State.INTERRUPTED -> "操作已暂停" to "没有写入任何未经确认的修改"
            AiCompanionView.State.ERROR -> "刚才没有接住" to "检查模型设置或换一种说法再试试"
        }
        binding.assistantStateView.text = copy.first
        binding.assistantStateDetailView.text = copy.second
    }

    private fun celebrateAssistant() {
        setAssistantState(AiCompanionView.State.SUCCESS)
        binding.assistantOrb.postDelayed({
            if (!isBusy && binding.assistantOrb.state == AiCompanionView.State.SUCCESS) {
                setAssistantState(AiCompanionView.State.IDLE)
            }
        }, 2600L)
    }

    private fun showAssistantError() {
        setAssistantState(AiCompanionView.State.ERROR)
        binding.assistantOrb.postDelayed({
            if (!isBusy && binding.assistantOrb.state == AiCompanionView.State.ERROR) {
                setAssistantState(AiCompanionView.State.IDLE)
            }
        }, 3200L)
    }

    private class ModelAdapter(
        private val onSelected: (AiModelOption) -> Unit
    ) : RecyclerView.Adapter<ModelAdapter.ModelViewHolder>() {
        private var models = emptyList<AiModelOption>()
        private var selectedModel = ""

        fun submitList(items: List<AiModelOption>, selected: String) {
            models = items
            selectedModel = selected
            notifyDataSetChanged()
        }

        fun setSelectedModel(selected: String) {
            val old = models.indexOfFirst { it.id == selectedModel }
            selectedModel = selected
            val current = models.indexOfFirst { it.id == selectedModel }
            if (old >= 0) notifyItemChanged(old)
            if (current >= 0) notifyItemChanged(current)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ModelViewHolder {
            return ModelViewHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.item_ai_model, parent, false)
            )
        }

        override fun getItemCount() = models.size

        override fun onBindViewHolder(holder: ModelViewHolder, position: Int) {
            val model = models[position]
            holder.name.text = model.id
            holder.owner.text = when {
                !model.supportsOpenAi -> "不支持当前调用接口"
                model.owner.isBlank() -> "OpenAI 兼容"
                else -> "${model.owner} · OpenAI 兼容"
            }
            holder.selected.visibility = if (model.id == selectedModel) View.VISIBLE else View.GONE
            holder.itemView.alpha = if (model.supportsOpenAi) 1f else 0.45f
            holder.itemView.isEnabled = model.supportsOpenAi
            holder.itemView.contentDescription = "模型 ${model.id}${if (model.id == selectedModel) "，已选择" else ""}${if (!model.supportsOpenAi) "，不支持当前接口" else ""}"
            holder.itemView.setOnClickListener(if (model.supportsOpenAi) View.OnClickListener { onSelected(model) } else null)
        }

        class ModelViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.modelNameView)
            val owner: TextView = view.findViewById(R.id.modelOwnerView)
            val selected: ImageView = view.findViewById(R.id.modelSelectedIcon)
        }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.stay, R.anim.ai_exit)
    }
}
