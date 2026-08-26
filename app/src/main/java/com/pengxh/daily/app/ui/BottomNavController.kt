package com.pengxh.daily.app.ui

import android.app.Activity
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import com.pengxh.daily.app.R
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView

object BottomNavController {
    enum class Tab { HOME, CALENDAR, RECORDS, SETTINGS }

    fun bind(activity: Activity, root: View, selected: Tab) {
        val active = ContextCompat.getColor(activity, R.color.accent_red)
        val inactive = ContextCompat.getColor(activity, R.color.text_tertiary_dark)
        val items = listOf(
            NavItem(Tab.HOME, root.findViewById(R.id.navHome), root.findViewById(R.id.navHomeIcon), root.findViewById(R.id.navHomeLabel), MainActivity::class.java),
            NavItem(Tab.CALENDAR, root.findViewById(R.id.navCalendar), root.findViewById(R.id.navCalendarIcon), root.findViewById(R.id.navCalendarLabel), CalendarActivity::class.java),
            NavItem(Tab.RECORDS, root.findViewById(R.id.navRecords), root.findViewById(R.id.navRecordsIcon), root.findViewById(R.id.navRecordsLabel), ExecutionRecordsActivity::class.java),
            NavItem(Tab.SETTINGS, root.findViewById(R.id.navSettings), root.findViewById(R.id.navSettingsIcon), root.findViewById(R.id.navSettingsLabel), SettingsActivity::class.java)
        )
        items.forEach { item ->
            val color = if (item.tab == selected) active else inactive
            item.icon.setColorFilter(color)
            item.label.setTextColor(color)
            item.label.setTypeface(null, if (item.tab == selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            item.container.setOnClickListener {
                if (item.tab == selected) return@setOnClickListener
                activity.startActivity(
                    Intent(activity, item.destination).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                )
                activity.overridePendingTransition(0, 0)
            }
        }

        val nav = root.findViewById<View>(R.id.bottomNavContainer)
        val blurTarget = root.findViewById<BlurTarget?>(R.id.blurTarget)
        if (nav is BlurView && blurTarget != null) {
            nav.setupWith(blurTarget)
                .setBlurRadius(24f)
                .setOverlayColor(ContextCompat.getColor(activity, R.color.glass_surface_soft))
        }
        val initialBottom = (nav.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
        ViewCompat.setOnApplyWindowInsetsListener(nav) { view, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            view.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin = initialBottom + bottom }
            insets
        }
    }

    private data class NavItem(
        val tab: Tab,
        val container: View,
        val icon: ImageView,
        val label: TextView,
        val destination: Class<out Activity>
    )
}
