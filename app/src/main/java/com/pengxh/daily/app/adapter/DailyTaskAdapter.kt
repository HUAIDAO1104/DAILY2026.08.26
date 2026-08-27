package com.pengxh.daily.app.adapter

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.pengxh.daily.app.R
import com.pengxh.daily.app.sqlite.bean.DailyTaskBean
import com.pengxh.daily.app.utils.displayName
import com.pengxh.kt.lite.adapter.ViewHolder
import com.pengxh.kt.lite.extensions.convertColor

@SuppressLint("NotifyDataSetChanged")
class DailyTaskAdapter(private val dataBeans: MutableList<DailyTaskBean>) :
    RecyclerView.Adapter<ViewHolder>() {

    var mPosition = -1
    private var actualTime = "--:--:--"
    private var onItemClickListener: OnItemClickListener? = null

    fun updateCurrentTaskState(position: Int) {
        val oldPosition = mPosition
        mPosition = position
        if (oldPosition >= 0 && oldPosition < dataBeans.size) notifyItemChanged(oldPosition)
        if (position >= 0 && position < dataBeans.size) notifyItemChanged(position)
    }

    fun updateCurrentTaskState(position: Int, actualTime: String) {
        if (position < 0 || position >= dataBeans.size) return
        val oldPosition = mPosition
        mPosition = position
        this.actualTime = actualTime
        if (oldPosition >= 0 && oldPosition < dataBeans.size && oldPosition != position) {
            notifyItemChanged(oldPosition)
        }
        notifyItemChanged(position)
    }

    override fun getItemCount(): Int = dataBeans.size

    override fun getItemId(position: Int): Long = position.toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val itemView = LayoutInflater.from(parent.context).inflate(
            R.layout.item_daily_task_rv_l, parent, false
        )
        return ViewHolder(itemView)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val taskBean = dataBeans[position]
        holder.setText(R.id.taskTimeView, taskBean.time.take(5))
        holder.setText(R.id.taskNameView, taskBean.displayName())
        val taskMarkView = holder.getView<ImageView>(R.id.taskMarkView)
        if (position == mPosition) {
            holder.itemView.isSelected = true
            val context = holder.itemView.context
            holder.setText(R.id.actualTimeView, "正在等待执行结果 · $actualTime")
                .setTextColor(R.id.actualTimeView, R.color.accent_red.convertColor(context))
                .setTextColor(R.id.taskTimeView, R.color.accent_red.convertColor(context))
            taskMarkView.setImageResource(R.drawable.ic_task_running_modern)
            taskMarkView.contentDescription = "任务正在执行"
            taskMarkView.setBackgroundResource(R.drawable.bg_circle_red_soft)
        } else {
            holder.itemView.isSelected = false
            holder.setText(R.id.actualTimeView, if (taskBean.isEnabled) "随机时间 · 点击编辑" else "已停用 · 点击编辑")
                .setTextColor(R.id.actualTimeView, R.color.text_secondary_dark.convertColor(holder.itemView.context))
                .setTextColor(R.id.taskTimeView, R.color.text_primary_dark.convertColor(holder.itemView.context))
            taskMarkView.setImageResource(if (taskBean.isEnabled) 0 else R.drawable.ic_task_paused_modern)
            taskMarkView.contentDescription = if (taskBean.isEnabled) "任务已启用" else "任务已停用"
            taskMarkView.setBackgroundResource(R.drawable.bg_circle_outline)
        }

        holder.itemView.setOnClickListener {
            onItemClickListener?.onItemClick(position)
        }

        holder.itemView.setOnLongClickListener {
            onItemClickListener?.onItemLongClick(position)
            return@setOnLongClickListener true
        }
    }

    fun refresh(newRows: MutableList<DailyTaskBean>) {
        dataBeans.clear()
        dataBeans.addAll(newRows)
        notifyDataSetChanged()
    }

    interface OnItemClickListener {
        fun onItemClick(position: Int)

        fun onItemLongClick(position: Int)
    }

    fun setOnItemClickListener(listener: OnItemClickListener) {
        this.onItemClickListener = listener
    }
}
