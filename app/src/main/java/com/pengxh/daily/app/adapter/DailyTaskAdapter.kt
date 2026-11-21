package com.pengxh.daily.app.adapter

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.os.PowerManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
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
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.MaskOverlayHelper
import com.pengxh.daily.app.widget.ChainLineDecoration
import com.pengxh.kt.lite.adapter.ViewHolder
import com.pengxh.kt.lite.extensions.convertColor
import com.pengxh.kt.lite.utils.SaveKeyValues

class DailyTaskAdapter(dataBeans: MutableList<DailyTaskBean>) :
    ListAdapter<DailyTaskBean, ViewHolder>(DIFF_CALLBACK) {

    /** 调度中、仍待执行的任务实际时间（过点后自动不展示） */
    data class PendingActualHint(val displayTime: String, val millis: Long)

    var mPosition = -1
    private var actualTime = "--:--:--"
    private var schedulerRunning = false
    private var pendingActualById: Map<Int, PendingActualHint> = emptyMap()
    private var nextPassTime = Long.MAX_VALUE
    private var onItemClickListener: OnItemClickListener? = null

    /** 随机窗口配置缓存（refresh 时刷新一次，避免每次 bind 都读 SharedPreferences） */
    private var randomTimeEnabled = true
    private var timeRange = Constant.DEFAULT_TIME_RANGE

    init {
        // 防御性拷贝：避免外部后续修改同一 List 实例影响适配器内部状态
        submitList(ArrayList(dataBeans))
    }

    /**
     * 更新今日计划状态（含「实际执行」小字 + 待执行/已过点节点与药丸）。
     * - [running] 为 false 时全部隐藏、状态归中性
     * - [pending] 为今日计划全量（task.id → 实际时间），Adapter 内部按 millis
     *   区分「待执行（未过点）」「已过点」「非今日任务」；小字仅未过点时展示
     */
    fun setActualHintState(running: Boolean, pending: Map<Int, PendingActualHint>) {
        schedulerRunning = running
        pendingActualById = if (running) pending else emptyMap()
        recomputeNextPassTime()
        if (itemCount > 0) {
            notifyItemRangeChanged(0, itemCount, PAYLOAD_ACTUAL_HINT)
        }
    }

    /** 时钟滴答：只在有任务「过点」的瞬间触发一次刷新（避免每秒全量 bind） */
    fun refreshActualHintVisibility() {
        if (!schedulerRunning || pendingActualById.isEmpty() || itemCount == 0) return
        if (System.currentTimeMillis() < nextPassTime) return
        notifyItemRangeChanged(0, itemCount, PAYLOAD_ACTUAL_HINT)
        recomputeNextPassTime()
    }

    /** 重算下一个过点时间（今日计划里未过点的最小 millis；无则 Long.MAX_VALUE 表示不再有任务过点） */
    private fun recomputeNextPassTime() {
        val now = System.currentTimeMillis()
        nextPassTime = pendingActualById.values
            .filter { it.millis > now }
            .minOfOrNull { it.millis } ?: Long.MAX_VALUE
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
            bindStateVisual(holder, stateOf(taskBean, position))
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val taskBean = getItem(position)
        holder.setText(R.id.taskTimeView, taskBean.time)
        // 随机时间窗口展示（开启随机时间时显示 "起始 - 结束"，否则隐藏该行）
        val randomRange = taskBean.resolveRandomTimeRange(randomTimeEnabled, timeRange)
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
        bindStateVisual(holder, state)

        val arrowView = holder.getView<AppCompatImageView>(R.id.arrowView)
        val actualTimeCardView = holder.getView<LinearLayout>(R.id.actualTimeCardView)
        if (position == mPosition) {
            holder.itemView.isSelected = true
            val context = holder.itemView.context
            holder.setText(R.id.actualTimeView, actualTime)
                .setTextColor(R.id.actualTimeView, R.color.md_primary.convertColor(context))
            arrowView.animate().rotation(90f).setDuration(350).start()
            if (!actualTimeCardView.isVisible) {
                actualTimeCardView.expand()
            }
        } else {
            holder.itemView.isSelected = false
            holder.setText(R.id.actualTimeView, "--:--:--")
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
     * 推导任务状态：执行中 / 待执行 / 已过点 / 中性。
     *
     * 说明：alpha 调度器不回填「已完成/跳过」的 adapter 状态，这里用
     * [mPosition]（执行中）与 [pendingActualById]（今日计划全量，含时间戳）推导：
     *  - 执行中：position 命中 [mPosition]
     *  - 待执行：调度运行中，该任务在今日计划里、尚未过点
     *  - 已过点：调度运行中，该任务在今日计划里、已过点（已执行/超时，统一琥珀，不细分完成 vs 超时）
     *  - 中性：其余情况（调度未运行，或该任务不在今日计划内，即非今日任务）
     * */
    private fun stateOf(taskBean: DailyTaskBean, position: Int): Int = when {
        position == mPosition -> STATE_RUNNING
        schedulerRunning -> {
            val hint = pendingActualById[taskBean.id]
            when {
                hint == null -> STATE_IDLE
                hint.millis > System.currentTimeMillis() -> STATE_PENDING
                else -> STATE_PASSED
            }
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

            STATE_PASSED -> {
                stateText.text = "已过点"
                stateText.setTextColor(R.color.md_onWarningContainer.convertColor(context))
                stateCard.setCardBackgroundColor(R.color.md_warningContainer.convertColor(context))
            }

            else -> {
                stateText.text = "-"
                stateText.setTextColor(R.color.md_onSurfaceVariant.convertColor(context))
                stateCard.setCardBackgroundColor(R.color.md_surfaceVariant.convertColor(context))
            }
        }
    }

    /**
     * 链式节点：颜色按状态推导，执行中做「呼吸 + 扩散光圈」脉冲动画（复用 ItemView 前先取消旧动画）
     * */
    private fun bindChainNode(holder: ViewHolder, state: Int) {
        val dot = holder.getView<View>(R.id.chainDotView)
        val ripple = holder.getView<View>(R.id.chainRippleView)
        val context = holder.itemView.context
        val colorRes = when (state) {
            STATE_RUNNING -> R.color.md_primary
            STATE_PENDING -> R.color.md_outlineVariant
            STATE_PASSED -> R.color.md_warning
            else -> R.color.md_outlineVariant
        }
        val color = colorRes.convertColor(context)
        dot.backgroundTintList = ColorStateList.valueOf(color)
        ripple.backgroundTintList = ColorStateList.valueOf(color)
        bindPulse(dot, ripple, state == STATE_RUNNING)
    }

    /**
     * 状态视觉统一更新：状态药丸 + 链式节点 + 卡片高亮 + 计划时间颜色。
     * 供全量 bind 与 payload 刷新（时钟滴答触发「待执行 → 已过点」切换）共用，
     * 保证状态切换时各处视觉一致更新，不残留旧色。
     * */
    private fun bindStateVisual(holder: ViewHolder, state: Int) {
        bindStatePill(holder, state)
        bindChainNode(holder, state)
        bindCardHighlight(holder, state)
        // 连线状态化：把「是否已过点」写入 itemView tag，供 ChainLineDecoration 画琥珀轨迹
        holder.itemView.setTag(ChainLineDecoration.TAG_PASSED, state == STATE_PASSED)
        val timeColorRes = when (state) {
            STATE_RUNNING -> R.color.md_onSurfaceVariant
            STATE_PASSED -> R.color.md_warning
            else -> R.color.md_onSurface
        }
        holder.getView<TextView>(R.id.taskTimeView)
            .setTextColor(timeColorRes.convertColor(holder.itemView.context))
    }

    /** 执行中卡片高亮：主题色描边锁定当前任务（方向 2） */
    private fun bindCardHighlight(holder: ViewHolder, state: Int) {
        val card = holder.getView<MaterialCardView>(R.id.taskCardView)
        val context = holder.itemView.context
        if (state == STATE_RUNNING) {
            val strokePx = (2f * context.resources.displayMetrics.density).toInt()
            card.strokeWidth = strokePx
            card.strokeColor = R.color.md_primary.convertColor(context)
        } else {
            card.strokeWidth = 0
        }
    }

    /**
     * 执行中节点脉冲：核心圆点呼吸（1→1.3→1）+ 外围光圈扩散渐隐（雷达波纹）。
     *
     * - 复用 ItemView 前先取消旧动画，避免动画叠加导致越扩越大；
     * - 亮屏 + 运行中 + 动画已在跑 → 直接返回，避免时钟每秒 payload 刷新反复重启导致不连贯；
     * - 息屏 / 伪息屏 → 不启动动画（省电）；亮屏后由下一次 payload 刷新重新拉起。
     * */
    private fun bindPulse(dot: View, ripple: View, running: Boolean) {
        val existing = dot.tag as? ValueAnimator
        val screenOn = isScreenActuallyOn(dot.context)
        if (running && screenOn && existing != null && existing.isRunning) {
            return
        }
        existing?.cancel()
        dot.tag = null
        (ripple.tag as? ValueAnimator)?.cancel()
        ripple.tag = null
        dot.scaleX = 1f
        dot.scaleY = 1f
        ripple.alpha = 0f
        ripple.scaleX = 1f
        ripple.scaleY = 1f
        if (!running || !screenOn) return
        // 呼吸：核心圆点轻微放大缩小
        val breath = ValueAnimator.ofFloat(1f, 1.3f, 1f).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val scale = it.animatedValue as Float
                dot.scaleX = scale
                dot.scaleY = scale
            }
        }
        dot.tag = breath
        breath.start()
        // 扩散光圈：从核心尺寸外扩到 2.6 倍，同时透明度 0.45→0 渐隐，循环形成雷达波纹
        val rippleAnim = ValueAnimator.ofFloat(1f, 2.6f).apply {
            duration = 1600
            repeatCount = ValueAnimator.INFINITE
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val progress = it.animatedValue as Float
                ripple.scaleX = progress
                ripple.scaleY = progress
                ripple.alpha = ((1f - (progress - 1f) / 1.6f) * 0.45f).coerceIn(0f, 1f)
            }
        }
        ripple.tag = rippleAnim
        rippleAnim.start()
    }

    /** 真正亮屏可见：物理屏幕交互中 且 无伪息屏黑蒙层覆盖 */
    private fun isScreenActuallyOn(context: Context): Boolean {
        val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val interactive = pm?.isInteractive ?: true
        return interactive && !MaskOverlayHelper.isShowing()
    }

    fun refresh(newRows: MutableList<DailyTaskBean>) {
        // 随机窗口配置：列表数据变化时刷新一次缓存，避免每次 bind 都读 SharedPreferences
        randomTimeEnabled = SaveKeyValues.loadBoolean(Constant.RANDOM_TIME_KEY, true)
        timeRange = SaveKeyValues.loadInt(Constant.TIME_RANGE_KEY, Constant.DEFAULT_TIME_RANGE)
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

        private const val STATE_IDLE = 0      // 调度未运行 / 非今日任务 / 中性
        private const val STATE_RUNNING = 1   // 执行中
        private const val STATE_PENDING = 2   // 待执行
        private const val STATE_PASSED = 3    // 已过点（已执行/超时，琥珀）

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
