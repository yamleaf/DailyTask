package com.pengxh.daily.app.adapter

import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.pengxh.daily.app.R
import com.pengxh.daily.app.extensions.collapse
import com.pengxh.daily.app.extensions.expand
import com.pengxh.daily.app.extensions.resolveRandomTimeRange
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
            val taskBean = getItem(position)
            bindActualHint(holder, taskBean)
            val state = stateOf(taskBean, position)
            bindStatePill(holder, state)
            bindChainNode(holder, state)
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val taskBean = getItem(position)
        holder.setText(R.id.taskTimeView, taskBean.time)
        // 随机时间窗口展示（开启随机时间时显示 "起始 - 结束"，否则隐藏该行）
        val randomRange = taskBean.resolveRandomTimeRange()
        val randomTimeView = holder.getView<TextView>(R.id.randomTimeView)
        if (randomRange != null) {
            randomTimeView.isVisible = true
            randomTimeView.text = "随机窗口  $randomRange"
        } else {
            randomTimeView.isVisible = false
        }
        // 备注独立成行展示（无备注时隐藏，不再内联拼进时间文本）
        val name = taskBean.name.orEmpty()
        val taskNameView = holder.getView<TextView>(R.id.taskNameView)
        taskNameView.text = name
        taskNameView.isVisible = name.isNotBlank()

        bindActualHint(holder, taskBean)
        val state = stateOf(taskBean, position)
        bindStatePill(holder, state)
        bindChainNode(holder, state)

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

    /**
     * 推导任务状态：执行中 / 待执行 / 中性。
     *
     * 说明：alpha 调度器不回填「已完成/跳过」的 adapter 状态，这里仅用
     * [mPosition]（执行中）与 [pendingActualById]（今日待执行计划，含时间戳）推导：
     *  - 执行中：position 命中 [mPosition]
     *  - 待执行：调度运行中，且该任务在今日待执行计划里、尚未过点
     *  - 中性：其余情况（调度未运行，或该任务不在今日计划内）
     *
     *  刻意不做「已过点」推断：taskBeans 展示的是全部任务，而 pendingActualById
     *  只是今日待执行子集，非今日任务也不在 pending 里，无法据此安全区分
     *  「今日已过点」与「非今日任务」，避免误标。
     * */
    private fun stateOf(taskBean: DailyTaskBean, position: Int): Int = when {
        position == mPosition -> STATE_RUNNING
        schedulerRunning -> {
            val hint = pendingActualById[taskBean.id]
            if (hint != null && hint.millis > System.currentTimeMillis()) STATE_PENDING
            else STATE_IDLE
        }
        else -> STATE_IDLE
    }

    /**
     * 右上角状态药丸：文案 + 柔光底色（借鉴 master，配色对齐 alpha 的 md 色板）
     * */
    private fun bindStatePill(holder: ViewHolder, state: Int) {
        val context = holder.itemView.context
        val stateCard = holder.getView<MaterialCardView>(R.id.stateCardView)
        val stateText = holder.getView<TextView>(R.id.stateTextView)
        when (state) {
            STATE_RUNNING -> {
                stateText.text = "执行中"
                stateText.setTextColor(R.color.md_primary.convertColor(context))
                stateCard.setCardBackgroundColor(R.color.md_primaryContainer.convertColor(context))
            }

            STATE_PENDING -> {
                stateText.text = "待执行"
                stateText.setTextColor(R.color.md_onSurfaceVariant.convertColor(context))
                stateCard.setCardBackgroundColor(R.color.md_surfaceVariant.convertColor(context))
            }

            else -> {
                stateText.text = "-"
                stateText.setTextColor(R.color.md_onSurfaceVariant.convertColor(context))
                stateCard.setCardBackgroundColor(R.color.md_surfaceVariant.convertColor(context))
            }
        }
    }

    /**
     * 链式节点：颜色按状态推导，执行中做呼吸脉冲动画（复用 ItemView 前先取消旧动画）
     * */
    private fun bindChainNode(holder: ViewHolder, state: Int) {
        val dot = holder.getView<View>(R.id.chainDotView)
        val context = holder.itemView.context
        val colorRes = when (state) {
            STATE_RUNNING -> R.color.md_primary
            STATE_PENDING -> R.color.md_outlineVariant
            else -> R.color.md_outlineVariant
        }
        dot.backgroundTintList = ColorStateList.valueOf(colorRes.convertColor(context))
        bindPulse(dot, state == STATE_RUNNING)
    }

    private fun bindPulse(dot: View, running: Boolean) {
        (dot.tag as? ValueAnimator)?.cancel()
        dot.tag = null
        if (!running) {
            dot.scaleX = 1f
            dot.scaleY = 1f
            return
        }
        val animator = ValueAnimator.ofFloat(1f, 1.35f, 1f).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val scale = it.animatedValue as Float
                dot.scaleX = scale
                dot.scaleY = scale
            }
        }
        dot.tag = animator
        animator.start()
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

        private const val STATE_IDLE = 0      // 调度未运行 / 中性
        private const val STATE_RUNNING = 1   // 执行中
        private const val STATE_PENDING = 2   // 待执行

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
