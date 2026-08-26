package com.pengxh.daily.app.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.JsonParser
import com.pengxh.daily.app.R
import com.pengxh.daily.app.databinding.ActivityBackupBinding
import com.pengxh.daily.app.utils.ConfigSnapshotManager
import com.pengxh.kt.lite.base.KotlinBaseActivity
import com.pengxh.kt.lite.extensions.convertColor
import com.pengxh.kt.lite.extensions.dp2px
import com.pengxh.kt.lite.extensions.show
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackupActivity : KotlinBaseActivity<ActivityBackupBinding>() {
    override fun initViewBinding() = ActivityBackupBinding.inflate(layoutInflater)
    override fun observeRequestState() = Unit

    override fun setupTopBarLayout() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbar) { view, insets ->
            view.setPadding(0, insets.getInsets(WindowInsetsCompat.Type.statusBars()).top, 0, 0)
            insets
        }
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    override fun initOnCreate(savedInstanceState: Bundle?) = renderBackups()

    override fun initEvent() {
        binding.createBackupButton.setOnClickListener {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { ConfigSnapshotManager.create(this@BackupActivity, "手动备份") }
                "当前配置已备份".show(this@BackupActivity)
                renderBackups()
            }
        }
    }

    private fun renderBackups() {
        val files = ConfigSnapshotManager.list(this)
        binding.backupCountView.text = "${files.size} 份"
        binding.emptyView.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        binding.backupListLayout.visibility = if (files.isEmpty()) View.GONE else View.VISIBLE
        binding.backupListLayout.removeAllViews()
        files.forEachIndexed { index, file ->
            binding.backupListLayout.addView(backupRow(file))
            if (index < files.lastIndex) binding.backupListLayout.addView(View(this).apply {
                setBackgroundColor(R.color.divider_dark.convertColor(this@BackupActivity))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1.dp2px(this)).apply {
                marginStart = 16.dp2px(this@BackupActivity)
                marginEnd = 16.dp2px(this@BackupActivity)
            })
        }
    }

    private fun backupRow(file: File): View {
        val root = runCatching { JsonParser.parseString(file.readText()).asJsonObject }.getOrNull()
        val reason = root?.get("reason")?.asString.orEmpty().ifBlank { "配置快照" }
        val version = root?.get("sourceVersion")?.asString.orEmpty()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = 72.dp2px(this@BackupActivity)
            setPadding(16.dp2px(this@BackupActivity), 8.dp2px(this@BackupActivity), 14.dp2px(this@BackupActivity), 8.dp2px(this@BackupActivity))
            addView(TextView(this@BackupActivity).apply {
                text = version.ifBlank { "配置" }
                gravity = Gravity.CENTER
                textSize = 9f
                setTextColor(R.color.accent_red.convertColor(this@BackupActivity))
                setBackgroundResource(R.drawable.bg_circle_red_soft)
            }, LinearLayout.LayoutParams(42.dp2px(this@BackupActivity), 42.dp2px(this@BackupActivity)))
            val copy = LinearLayout(this@BackupActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@BackupActivity).apply {
                    text = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(file.lastModified()))
                    setTextColor(R.color.text_primary_dark.convertColor(this@BackupActivity))
                    textSize = 13f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                })
                addView(TextView(this@BackupActivity).apply {
                    text = reason
                    setTextColor(R.color.text_secondary_dark.convertColor(this@BackupActivity))
                    textSize = 9f
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 4.dp2px(this@BackupActivity) })
            }
            addView(copy, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 12.dp2px(this@BackupActivity) })
            addView(TextView(this@BackupActivity).apply {
                text = "恢复"
                setTextColor(R.color.accent_red.convertColor(this@BackupActivity))
                textSize = 11f
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            setOnClickListener { confirmRestore(file, reason) }
        }
    }

    private fun confirmRestore(file: File, reason: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("恢复这份配置？")
            .setMessage("将恢复“$reason”。恢复前会自动备份当前配置，任务、请假和设置会切换到所选版本。")
            .setNegativeButton("取消", null)
            .setPositiveButton("确认恢复") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        ConfigSnapshotManager.restoreWithBackup(this@BackupActivity, file)
                    }
                    "配置恢复完成".show(this@BackupActivity)
                    renderBackups()
                }
            }.show()
    }
}
