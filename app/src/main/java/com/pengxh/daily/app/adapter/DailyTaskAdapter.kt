package com.pengxh.daily.app.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.pengxh.daily.app.R
import com.pengxh.daily.app.extensions.collapse
import com.pengxh.daily.app.extensions.expand
import com.pengxh.daily.app.sqlite.bean.DailyTaskBean
import com.pengxh.kt.lite.adapter.ViewHolder
import com.pengxh.kt.lite.extensions.convertColor

class DailyTaskAdapter(dataBeans: MutableList<DailyTaskBean>) :
    ListAdapter<DailyTaskBean, ViewHolder>(DIFF_CALLBACK) {

    /** 调度中、仍待执行的任务实际时间（过点后自动不展示） */
    data class PendingActualHint(val displayTime: String, val millis: Long)

    var mPosition = -1
    private var actualTime = "--:--:--"
    private var schedulerRunning = false
    private var pendingActualById: Map<Int, PendingActualHint> = emptyMap()
    private var onItemClickListener: OnItemClickListener? = null

    init {
        // 防御性拷贝：避免外部后续修改同一 List 实例影响适配器内部状态
        submitList(ArrayList(dataBeans))
    }

    /**
     * 更新「实际执行 HH:mm:ss」小字状态。
     * - [running] 为 false 时全部隐藏
     * - [pending] 仅含待执行任务；已过点/已执行的不要放进来
     */
    fun setActualHintState(running: Boolean, pending: Map<Int, PendingActualHint>) {
        schedulerRunning = running
        pendingActualById = if (running) pending else emptyMap()
        if (itemCount > 0) {
            notifyItemRangeChanged(0, itemCount, PAYLOAD_ACTUAL_HINT)
        }
    }

    /** 时钟滴答：已过点的待执行项自动隐藏小字（不重建整行） */
    fun refreshActualHintVisibility() {
        if (!schedulerRunning || pendingActualById.isEmpty() || itemCount == 0) return
        notifyItemRangeChanged(0, itemCount, PAYLOAD_ACTUAL_HINT)
    }

    fun updateCurrentTaskState(position: Int) {
        val oldPosition = mPosition
        mPosition = position
        if (oldPosition in 0 until itemCount) notifyItemChanged(oldPosition)
        if (position in 0 until itemCount) notifyItemChanged(position)
    }

    fun updateCurrentTaskState(position: Int, actualTime: String) {
        if (position < 0 || position >= itemCount) return
        val oldPosition = mPosition
        mPosition = position
        this.actualTime = actualTime
        if (oldPosition in 0 until itemCount && oldPosition != position) {
            notifyItemChanged(oldPosition)
        }
        notifyItemChanged(position)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val itemView = LayoutInflater.from(parent.context).inflate(
            R.layout.item_daily_task_rv_l, parent, false
        )
        return ViewHolder(itemView)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_ACTUAL_HINT)) {
            bindActualHint(holder, getItem(position))
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val taskBean = getItem(position)
        holder.setText(R.id.taskTimeView, taskBean.time)
        // 备注独立成行展示（无备注时隐藏，不再内联拼进时间文本）
        val name = taskBean.name.orEmpty()
        val taskNameView = holder.getView<TextView>(R.id.taskNameView)
        taskNameView.text = name
        taskNameView.isVisible = name.isNotBlank()
        bindActualHint(holder, taskBean)
        val arrowView = holder.getView<AppCompatImageView>(R.id.arrowView)
        val actualTimeCardView = holder.getView<LinearLayout>(R.id.actualTimeCardView)
        if (position == mPosition) {
            holder.itemView.isSelected = true
            val context = holder.itemView.context
            holder.setText(R.id.actualTimeView, actualTime)
                .setTextColor(R.id.actualTimeView, R.color.md_primary.convertColor(context))
                .setTextColor(R.id.taskTimeView, R.color.md_onSurfaceVariant.convertColor(context))
            arrowView.animate().rotation(90f).setDuration(350).start()
            if (!actualTimeCardView.isVisible) {
                actualTimeCardView.expand()
            }
        } else {
            holder.itemView.isSelected = false
            holder.setText(R.id.actualTimeView, "--:--:--")
                .setTextColor(
                    R.id.taskTimeView,
                    R.color.md_onSurface.convertColor(holder.itemView.context)
                )
            arrowView.animate().rotation(0f).setDuration(350).start()
            if (actualTimeCardView.isVisible) {
                actualTimeCardView.collapse()
            }
        }

        // 用 bindingAdapterPosition + 当前列表项，避免闭包捕获过期 position / 与 taskBeans 脱节
        holder.itemView.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
            onItemClickListener?.onItemClick(getItem(pos))
        }

        holder.itemView.setOnLongClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnLongClickListener true
            onItemClickListener?.onItemLongClick(getItem(pos))
            true
        }
    }

    fun refresh(newRows: MutableList<DailyTaskBean>) {
        // ListAdapter 内部基于 DiffUtil 计算增量，仅刷新变化的 item，自动获得增/删/移动动画
        submitList(ArrayList(newRows))
    }

    interface OnItemClickListener {
        fun onItemClick(item: DailyTaskBean)

        fun onItemLongClick(item: DailyTaskBean)
    }

    fun setOnItemClickListener(listener: OnItemClickListener) {
        this.onItemClickListener = listener
    }

    private fun bindActualHint(holder: ViewHolder, taskBean: DailyTaskBean) {
        val actualHintView = holder.getView<TextView>(R.id.taskActualHintView)
        val hint = pendingActualById[taskBean.id]
        if (schedulerRunning && hint != null && hint.millis > System.currentTimeMillis()) {
            actualHintView.isVisible = true
            actualHintView.text = "实际执行  ${hint.displayTime}"
        } else {
            actualHintView.isVisible = false
        }
    }

    companion object {
        private const val PAYLOAD_ACTUAL_HINT = "actual_hint"

        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<DailyTaskBean>() {
            override fun areItemsTheSame(old: DailyTaskBean, new: DailyTaskBean): Boolean {
                // 以主键 id 作为稳定标识，决定 item 是否同一对象（决定复用/动画）
                return old.id == new.id
            }

            override fun areContentsTheSame(old: DailyTaskBean, new: DailyTaskBean): Boolean {
                // 内容比对：界面展示 time + name；选择态/actualTime 由适配器状态驱动，不在此比较
                return old.time == new.time && old.name == new.name
            }
        }
    }
}
