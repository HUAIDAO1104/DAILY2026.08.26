package com.pengxh.daily.app.ui

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.gson.JsonObject
import com.pengxh.daily.app.R
import com.pengxh.daily.app.databinding.ActivityMessageChannelBinding
import com.pengxh.daily.app.utils.ConfigStore
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.DailyTaskDialogs
import com.pengxh.daily.app.utils.MessageDispatcher
import com.pengxh.kt.lite.base.KotlinBaseActivity
import com.pengxh.kt.lite.extensions.dp2px
import com.pengxh.kt.lite.extensions.isEmail
import com.pengxh.kt.lite.extensions.show
import com.pengxh.kt.lite.utils.LoadingDialog
import com.pengxh.kt.lite.utils.SaveKeyValues

class MessageChannelActivity : KotlinBaseActivity<ActivityMessageChannelBinding>() {

    private val context = this
    private var selectedChannel = 1

    override fun initViewBinding(): ActivityMessageChannelBinding {
        return ActivityMessageChannelBinding.inflate(layoutInflater)
    }

    override fun setupTopBarLayout() {
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbar) { view, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.setPadding(0, statusBarHeight, 0, 0)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.channelBottomBar) { view, insets ->
            val navigationBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            view.setPadding(
                19.dp2px(this),
                14.dp2px(this),
                19.dp2px(this),
                16.dp2px(this) + navigationBarHeight
            )
            insets
        }
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    override fun initOnCreate(savedInstanceState: Bundle?) {
        val title = SaveKeyValues.loadString(Constant.MESSAGE_TITLE_KEY, "打卡结果通知")
        binding.messageTitleView.setText(title)

        val key = SaveKeyValues.loadString(Constant.WX_WEB_HOOK_KEY, "")
        if (!key.isBlank()) {
            binding.wxKeyView.setText(key)
        }

        val obj = ConfigStore.get().load(Constant.EMAIL_CONFIG_KEY)
        if (!obj.isEmpty) {
            val outbox = obj.get("outbox").asString
            val authCode = obj.get("authCode").asString
            val inbox = obj.get("inbox").asString
            binding.emailSendAddressView.setText(if (outbox.contains("@qq.com")) outbox.dropLast(7) else outbox)
            binding.emailSendCodeView.setText(authCode)
            binding.emailInboxView.setText(inbox)
        }

        selectedChannel = SaveKeyValues.loadInt(Constant.MSG_CHANNEL_KEY, 1)
            .takeIf { it == 0 || it == 1 } ?: 1
        selectChannel(selectedChannel)
        updateConfigurationStates()
    }

    override fun observeRequestState() {

    }

    override fun initEvent() {
        binding.wechatSelector.setOnClickListener { selectChannel(1) }
        binding.emailSelector.setOnClickListener { selectChannel(0) }
        binding.saveChannelButton.setOnClickListener { saveCurrentChannel(showSuccess = true) }

        binding.sendWxButton.setOnClickListener {
            if (!saveCurrentChannel(showSuccess = false)) return@setOnClickListener

            DailyTaskDialogs.showConfirm(
                this,
                "发送测试消息？",
                "将向已配置的企业微信群机器人发送一条测试消息。",
                "发送",
                cancelable = false
            ) { sendTestMessage() }
        }

        binding.sendEmailButton.setOnClickListener {
            if (!saveCurrentChannel(showSuccess = false)) return@setOnClickListener
            sendTestEmail()
        }
    }

    private fun selectChannel(channel: Int) {
        selectedChannel = channel
        val isWechat = channel == 1
        binding.wechatSection.visibility = if (isWechat) View.VISIBLE else View.GONE
        binding.emailSection.visibility = if (isWechat) View.GONE else View.VISIBLE
        binding.wechatSelector.setBackgroundResource(
            if (isWechat) R.drawable.bg_channel_selected else R.drawable.bg_channel_unselected
        )
        binding.emailSelector.setBackgroundResource(
            if (isWechat) R.drawable.bg_channel_unselected else R.drawable.bg_channel_selected
        )
        binding.wechatSelectorTitle.setTextColor(
            getColor(if (isWechat) R.color.text_primary_dark else R.color.text_secondary_dark)
        )
        binding.emailSelectorTitle.setTextColor(
            getColor(if (isWechat) R.color.text_secondary_dark else R.color.text_primary_dark)
        )
        updateConfigurationStates()
    }

    private fun updateConfigurationStates() {
        val wxConfigured = binding.wxKeyView.text?.toString()?.isNotBlank() == true
        val emailConfigured = !ConfigStore.get().load(Constant.EMAIL_CONFIG_KEY).isEmpty
        binding.wechatSelectorState.text = if (wxConfigured) "已配置" else "群机器人"
        binding.emailSelectorState.text = if (emailConfigured) "已配置" else "SMTP"
        binding.wechatSelectorState.setTextColor(
            getColor(if (selectedChannel == 1) R.color.accent_red else R.color.text_tertiary_dark)
        )
        binding.emailSelectorState.setTextColor(
            getColor(if (selectedChannel == 0) R.color.accent_red else R.color.text_tertiary_dark)
        )
    }

    private fun saveCurrentChannel(showSuccess: Boolean): Boolean {
        val title = binding.messageTitleView.text?.toString()?.trim().orEmpty()
            .ifBlank { "打卡结果通知" }
        SaveKeyValues.saveString(Constant.MESSAGE_TITLE_KEY, title)

        val saved = if (selectedChannel == 1) {
            val key = binding.wxKeyView.text?.toString()?.trim().orEmpty()
            if (key.isBlank()) {
                binding.wxKeyView.shakeIfEmpty()
                "请填写企业微信 Webhook key".show(this)
                false
            } else {
                SaveKeyValues.saveString(Constant.WX_WEB_HOOK_KEY, key)
                true
            }
        } else {
            saveEmailConfiguration()
        }
        if (!saved) return false

        SaveKeyValues.saveInt(Constant.MSG_CHANNEL_KEY, selectedChannel)
        updateConfigurationStates()
        if (showSuccess) "通知设置已保存".show(this)
        return true
    }

    private fun saveEmailConfiguration(): Boolean {
        val address = binding.emailSendAddressView.text?.toString()?.trim().orEmpty()
        if (address.isBlank()) {
            binding.emailSendAddressView.shakeIfEmpty()
            "请填写发件 QQ 号".show(context)
            return false
        }
        val outbox = if (address.endsWith("@qq.com", ignoreCase = true)) address else "$address@qq.com"
        if (!outbox.isEmail()) {
            "发件邮箱格式不正确".show(context)
            return false
        }

        val authCode = binding.emailSendCodeView.text?.toString()?.trim().orEmpty()
        if (authCode.isBlank()) {
            binding.emailSendCodeView.shakeIfEmpty()
            "请填写 SMTP 授权码".show(context)
            return false
        }

        val inbox = binding.emailInboxView.text?.toString()?.trim().orEmpty()
        if (inbox.isBlank()) {
            binding.emailInboxView.shakeIfEmpty()
            "请填写收件邮箱".show(context)
            return false
        }
        if (!inbox.isEmail()) {
            binding.emailLayout.error = "收件邮箱格式不正确"
            return false
        }
        binding.emailLayout.error = null

        ConfigStore.get().save(
            Constant.EMAIL_CONFIG_KEY,
            JsonObject().apply {
                addProperty("outbox", outbox)
                addProperty("authCode", authCode)
                addProperty("inbox", inbox)
            }
        )
        return true
    }

    private fun sendTestMessage() {
        val message = buildString {
            appendLine("你好！")
            append("这是来自 DailyTask 的测试消息 🎉")
        }
        LoadingDialog.show(this, "消息发送中，请稍后...")
        MessageDispatcher.sendMessage(
            "测试消息", message,
            channelOverride = 1,
            onSuccess = {
                if (isFinishing || isDestroyed) return@sendMessage
                LoadingDialog.dismiss()

                SaveKeyValues.saveString(
                    Constant.MESSAGE_TITLE_KEY, binding.messageTitleView.text.toString().trim()
                )

                SaveKeyValues.saveInt(Constant.MSG_CHANNEL_KEY, 1)
                "发送成功，请在企业微信群中确认".show(this)
                updateConfigurationStates()
            },
            onFailure = {
                if (isFinishing || isDestroyed) return@sendMessage
                LoadingDialog.dismiss()
                it.show(this)
            })
    }

    private fun sendTestEmail() {
        DailyTaskDialogs.showConfirm(
            this,
            "发送测试邮件？",
            "将从已配置的 QQ 邮箱向收件地址发送一封测试邮件。",
            "发送",
            cancelable = false
        ) {
            LoadingDialog.show(context, "邮件发送中，请稍后...")
            MessageDispatcher.sendMessage(
                "邮箱测试", "这是一封测试邮件，不必关注",
                channelOverride = 0,
                onSuccess = {
                    LoadingDialog.dismiss()
                    "发送成功，请注意查收".show(context)

                    SaveKeyValues.saveString(
                        Constant.MESSAGE_TITLE_KEY,
                        binding.messageTitleView.text.toString().trim()
                    )

                    SaveKeyValues.saveInt(Constant.MSG_CHANNEL_KEY, 0)
                    updateConfigurationStates()
                },
                onFailure = {
                    LoadingDialog.dismiss()
                    "发送失败：${it}".show(context)
                })
        }
    }
}
