package com.pengxh.daily.app.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.button.MaterialButton
import com.pengxh.daily.app.R
import com.pengxh.daily.app.ai.AiConfigStore
import com.pengxh.daily.app.ai.AiPlanner
import com.pengxh.daily.app.ai.DailyTaskOperations
import com.pengxh.daily.app.ai.ValidatedAiPlan
import com.pengxh.daily.app.databinding.ActivityAiAssistantBinding
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
        startOrbAnimation(binding.headerOrb)
        addMessage(
            "你可以直接告诉我要做什么。我会先列出具体修改，只有你确认后才会执行。\n\n例如：‘明天下午请假’、‘把 8 点任务改到 8 点半’。",
            fromUser = false
        )
    }

    override fun initEvent() {
        binding.sendButton.setOnClickListener { submitPrompt() }
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
        binding.promptInput.setOnFocusChangeListener { _, focused -> if (focused) scrollToBottom() }
    }

    private fun sendSuggestion(text: String) {
        binding.promptInput.setText(text)
        submitPrompt()
    }

    private fun submitPrompt() {
        val command = binding.promptInput.text?.toString()?.trim().orEmpty()
        if (command.isBlank() || !binding.sendButton.isEnabled) return
        binding.promptInput.text?.clear()
        addMessage(command, fromUser = true)
        setLoading(true)
        lifecycleScope.launch {
            try {
                val state = withContext(Dispatchers.IO) { operations.buildStateJson() }
                val plan = planner.createPlan(command, state)
                if (plan.actions.isEmpty()) {
                    addMessage(plan.reply.ifBlank { "这句话没有包含明确的修改操作。你可以说得更具体一些。" }, false)
                } else {
                    val validated = operations.validate(plan)
                    addPlanCard(validated)
                }
            } catch (e: Exception) {
                addMessage(e.message ?: "处理失败，请稍后重试", false)
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
            setTextColor(getColor(R.color.text_primary_dark))
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        plan.previews.forEachIndexed { index, preview ->
            card.addView(TextView(this).apply {
                text = "${index + 1}. $preview"
                setTextColor(getColor(R.color.text_secondary_dark))
                textSize = 11f
                setPadding(0, 9.dp2px(this@AiAssistantActivity), 0, 0)
            })
        }
        card.addView(TextView(this).apply {
            text = "执行前会自动保存本机配置快照"
            setTextColor(getColor(R.color.text_tertiary_dark))
            textSize = 9f
            setPadding(0, 11.dp2px(this@AiAssistantActivity), 0, 0)
        })
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val cancel = MaterialButton(this).apply {
            text = "取消"
            setTextColor(getColor(R.color.text_secondary_dark))
            backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.surface_elevated_dark))
            cornerRadius = 12.dp2px(this@AiAssistantActivity)
            setOnClickListener {
                card.alpha = 0.5f
                isEnabled = false
                addMessage("已取消，没有修改任何内容。", false)
            }
        }
        val confirm = MaterialButton(this).apply {
            text = if (plan.requiresDangerConfirmation) "继续核对" else "确认执行"
            setTextColor(getColor(R.color.white))
            backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.accent_red))
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
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        warning.addView(TextView(this).apply {
            text = "计划包含删除或恢复操作。执行前仍会创建快照，可在配置备份中恢复。"
            setTextColor(getColor(R.color.text_secondary_dark))
            textSize = 11f
            setPadding(0, 8.dp2px(this@AiAssistantActivity), 0, 0)
        })
        val execute = MaterialButton(this).apply {
            text = "确认并执行"
            setTextColor(getColor(R.color.white))
            backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.accent_red))
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
                addMessage("操作完成\n${results.joinToString("\n") { "• $it" }}", false)
            } catch (e: Exception) {
                addMessage("执行中止：${e.message ?: "未知错误"}\n已完成的操作可能已生效，可通过配置快照恢复。", false)
            } finally {
                setLoading(false)
            }
        }
    }

    private fun showConfigDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_ai_config, null)
        val baseUrl = view.findViewById<EditText>(R.id.baseUrlInput)
        val model = view.findViewById<EditText>(R.id.modelInput)
        val apiKey = view.findViewById<EditText>(R.id.apiKeyInput)
        val existing = configStore.load()
        baseUrl.setText(existing.baseUrl)
        model.setText(existing.model)
        if (existing.apiKey.isNotBlank()) apiKey.hint = "已安全保存，留空保持不变"

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("AI 模型设置")
            .setView(view)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val url = baseUrl.text?.toString()?.trim().orEmpty()
                val modelName = model.text?.toString()?.trim().orEmpty()
                if (url.isNotBlank() && !url.startsWith("https://")) {
                    "接口地址必须使用 HTTPS".show(this)
                    return@setOnClickListener
                }
                configStore.save(url, modelName, apiKey.text?.toString()?.takeIf { it.isNotBlank() })
                dialog.dismiss()
                "模型设置已保存".show(this)
            }
        }
        dialog.show()
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
        binding.sendButton.isEnabled = !loading
        binding.sendButton.alpha = if (loading) 0.45f else 1f
        binding.promptInput.isEnabled = !loading
    }

    private fun scrollToBottom() {
        binding.conversationScrollView.post {
            binding.conversationScrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun startOrbAnimation(view: View) {
        view.animate().scaleX(1.08f).scaleY(1.08f).alpha(0.88f).setDuration(1400L)
            .withEndAction {
                view.animate().scaleX(0.96f).scaleY(0.96f).alpha(1f).setDuration(1400L)
                    .withEndAction { startOrbAnimation(view) }.start()
            }.start()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.stay, R.anim.ai_exit)
    }
}
