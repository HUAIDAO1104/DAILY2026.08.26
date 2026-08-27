package com.pengxh.daily.app.utils

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog as AppCompatAlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import com.pengxh.daily.app.R
import kotlin.math.min

object DailyTaskDialogs {
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
        onConfirm: () -> Unit
    ): AppCompatAlertDialog {
        val view = View.inflate(context, R.layout.dialog_app_update, null)
        view.findViewById<TextView>(R.id.updateVersionView).text =
            info.title.ifBlank { "DailyTask ${info.version}" }
        view.findViewById<TextView>(R.id.updateNotesView).text = formatReleaseNotes(info.notes)

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("发现新版本 ${info.version}")
            .setView(view)
            .setNegativeButton("稍后", null)
            .setPositiveButton("备份并更新") { _, _ -> onConfirm() }
            .create()
        dialog.setOnShowListener { style(dialog) }
        dialog.show()
        return dialog
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
