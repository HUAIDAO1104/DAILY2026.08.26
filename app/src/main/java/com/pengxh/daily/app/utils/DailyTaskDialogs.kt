package com.pengxh.daily.app.utils

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog as AppCompatAlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputLayout
import com.pengxh.daily.app.R
import kotlin.math.min

object DailyTaskDialogs {
    class UpdateDialogHandle internal constructor(
        private val dialog: Dialog,
        private val progressGroup: LinearLayout,
        private val progressIndicator: LinearProgressIndicator,
        private val progressSource: TextView,
        private val progressPercent: TextView,
        private val progressSize: TextView,
        private val errorView: TextView,
        private val laterButton: MaterialButton,
        private val nowButton: MaterialButton
    ) {
        var isDownloading: Boolean = false
            private set

        fun showDownloading() {
            isDownloading = true
            progressGroup.visibility = View.VISIBLE
            errorView.visibility = View.GONE
            progressIndicator.isIndeterminate = true
            progressSource.text = "正在连接国内加速线路"
            progressPercent.text = "准备中"
            progressSize.text = "优先使用国内下载线路"
            laterButton.isEnabled = false
            laterButton.alpha = 0.42f
            nowButton.isEnabled = false
            nowButton.text = "正在下载"
        }

        fun updateProgress(progress: UpdateDownloadProgress) {
            if (!dialog.isShowing) return
            progressGroup.visibility = View.VISIBLE
            progressSource.text = "${progress.sourceName} · ${progress.sourceIndex}/${progress.sourceCount}"
            if (progress.percent >= 0) {
                progressIndicator.isIndeterminate = false
                progressIndicator.progress = progress.percent
                progressPercent.text = "${progress.percent}%"
                progressSize.text = "${formatBytes(progress.downloadedBytes)} / ${formatBytes(progress.totalBytes)}"
            } else {
                progressIndicator.isIndeterminate = true
                progressPercent.text = if (progress.downloadedBytes > 0L) "下载中" else "连接中"
                progressSize.text = if (progress.downloadedBytes > 0L) {
                    "已下载 ${formatBytes(progress.downloadedBytes)}"
                } else {
                    "正在建立安全连接"
                }
            }
        }

        fun showError(message: String) {
            if (!dialog.isShowing) return
            isDownloading = false
            progressIndicator.isIndeterminate = false
            progressIndicator.progress = 0
            errorView.text = message
            errorView.visibility = View.VISIBLE
            laterButton.isEnabled = true
            laterButton.alpha = 1f
            laterButton.text = "关闭"
            nowButton.isEnabled = true
            nowButton.text = "重新下载"
        }

        fun dismiss() {
            if (dialog.isShowing) dialog.dismiss()
        }

        private fun formatBytes(bytes: Long): String = when {
            bytes < 0L -> "--"
            bytes < 1024L -> "$bytes B"
            bytes < 1024L * 1024L -> String.format("%.1f KB", bytes / 1024f)
            else -> String.format("%.1f MB", bytes / (1024f * 1024f))
        }
    }

    fun showChoice(
        context: Context,
        title: String,
        items: List<String>,
        selectedIndex: Int = -1,
        onSelected: (Int) -> Unit
    ): AppCompatAlertDialog {
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setSingleChoiceItems(items.toTypedArray(), selectedIndex) { current, which ->
                current.dismiss()
                onSelected(which)
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener { style(dialog) }
        dialog.show()
        return dialog
    }

    fun showTextInput(
        context: Context,
        title: String,
        label: String,
        description: String = "",
        initialValue: String = "",
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        maxLines: Int = 1,
        validator: (String) -> String? = { null },
        onConfirm: (String) -> Unit
    ): AppCompatAlertDialog {
        val view = View.inflate(context, R.layout.dialog_text_input, null)
        val descriptionView = view.findViewById<TextView>(R.id.inputDescriptionView)
        val inputLayout = view.findViewById<TextInputLayout>(R.id.inputLayout)
        val inputView = view.findViewById<EditText>(R.id.inputView)
        descriptionView.text = description
        descriptionView.visibility = if (description.isBlank()) View.GONE else View.VISIBLE
        inputLayout.hint = label
        inputView.inputType = inputType
        inputView.maxLines = maxLines
        inputView.minLines = if (maxLines > 1) min(4, maxLines) else 1
        inputView.setText(initialValue)
        inputView.setSelection(inputView.text?.length ?: 0)

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(view)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()
        dialog.setOnShowListener {
            style(dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = inputView.text?.toString()?.trim().orEmpty()
                val error = validator(value)
                inputLayout.error = error
                if (error == null) {
                    onConfirm(value)
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
        return dialog
    }

    fun showConfirm(
        context: Context,
        title: String,
        message: String,
        positiveText: String = "确定",
        cancelable: Boolean = true,
        onConfirm: () -> Unit
    ): AppCompatAlertDialog {
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setMessage(message)
            .setCancelable(cancelable)
            .setNegativeButton("取消", null)
            .setPositiveButton(positiveText) { _, _ -> onConfirm() }
            .create()
        dialog.setOnShowListener { style(dialog) }
        dialog.show()
        return dialog
    }

    fun showUpdate(
        context: Context,
        info: AppUpdateInfo,
        onConfirm: (UpdateDialogHandle) -> Unit
    ): UpdateDialogHandle {
        val view = View.inflate(context, R.layout.dialog_app_update, null)
        view.findViewById<TextView>(R.id.updateTitleView).text = "DailyTask 更新"
        view.findViewById<TextView>(R.id.updateVersionView).text =
            "v${info.version} · ${info.title.ifBlank { "新版本" }}"
        view.findViewById<TextView>(R.id.updateNotesView).text = formatReleaseNotes(info.notes)

        val dialog = Dialog(context).apply {
            setContentView(view)
            setCancelable(false)
            setCanceledOnTouchOutside(false)
        }
        val handle = UpdateDialogHandle(
            dialog = dialog,
            progressGroup = view.findViewById(R.id.updateProgressGroup),
            progressIndicator = view.findViewById(R.id.updateProgressIndicator),
            progressSource = view.findViewById(R.id.updateProgressSourceView),
            progressPercent = view.findViewById(R.id.updateProgressPercentView),
            progressSize = view.findViewById(R.id.updateProgressSizeView),
            errorView = view.findViewById(R.id.updateErrorView),
            laterButton = view.findViewById(R.id.updateLaterButton),
            nowButton = view.findViewById(R.id.updateNowButton)
        )
        view.findViewById<MaterialButton>(R.id.updateLaterButton).setOnClickListener {
            if (!handle.isDownloading) handle.dismiss()
        }
        view.findViewById<MaterialButton>(R.id.updateNowButton).setOnClickListener {
            if (!handle.isDownloading) {
                handle.showDownloading()
                onConfirm(handle)
            }
        }
        dialog.show()
        val density = context.resources.displayMetrics.density
        val screenWidth = context.resources.displayMetrics.widthPixels
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.78f)
            setLayout(
                min(screenWidth - (32 * density).toInt(), (520 * density).toInt()),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        return handle
    }

    fun style(dialog: AppCompatAlertDialog) {
        val density = dialog.context.resources.displayMetrics.density
        val screenWidth = dialog.context.resources.displayMetrics.widthPixels
        val horizontalMargin = (20 * density).toInt()
        val maxWidth = (520 * density).toInt()
        dialog.window?.setLayout(
            min(screenWidth - horizontalMargin * 2, maxWidth),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        dialog.findViewById<TextView>(android.R.id.message)?.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setLineSpacing(4 * density, 1f)
        }
        listOf(
            AlertDialog.BUTTON_NEGATIVE,
            AlertDialog.BUTTON_NEUTRAL,
            AlertDialog.BUTTON_POSITIVE
        ).forEach { buttonId ->
            dialog.getButton(buttonId)?.apply {
                isAllCaps = false
                minHeight = (48 * density).toInt()
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            }
        }
    }

    private fun formatReleaseNotes(raw: String): String {
        val cleaned = raw.lineSequence()
            .map { line ->
                line.trim()
                    .replace(Regex("^#{1,6}\\s*"), "")
                    .replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
                    .replace(Regex("^[*+-]\\s*"), "")
            }
            .filter { it.isNotBlank() }
            .filterNot {
                it.startsWith("http", ignoreCase = true) ||
                    it.startsWith("sha", ignoreCase = true) ||
                    it.contains("下载地址") ||
                    it.contains("checksum", ignoreCase = true)
            }
            .map { it.take(110).trimEnd() }
            .distinct()
            .take(7)
            .toList()
        return if (cleaned.isEmpty()) {
            "• 修复问题并改进使用体验"
        } else {
            cleaned.joinToString("\n") { "• $it" }
        }
    }
}
