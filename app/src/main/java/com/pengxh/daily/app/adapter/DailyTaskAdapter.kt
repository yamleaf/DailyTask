package com.pengxh.daily.app.adapter

import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.pengxh.daily.app.R
import com.pengxh.daily.app.databinding.ItemDailyTaskRvLBinding
import com.pengxh.daily.app.extensions.resolveRandomTimeRange
import com.pengxh.daily.app.sqlite.bean.DailyTaskBean
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.TaskScheduler
import com.pengxh.daily.app.widget.ChainLineDecoration
import com.pengxh.daily.app.widget.SwipeRevealLayout
import com.pengxh.kt.lite.extensions.convertColor
import com.pengxh.kt.lite.utils.SaveKeyValues

/**
 * 任务列表适配器：一任务一卡片，左滑露出编辑 / 删除，长按弹出任务详情。
 *
 * 视觉与交互对齐 DC（控制端）的任务列表：卡片首行是时间 + 备注 + 状态胶囊，
 * 第二行是随机窗口 / 实际执行两张淡灰小卡；左滑面板是一叠斜放的卡片（层层错位堆叠）。
 *
 * 左滑互斥与 DC 的 TaskRowAdapter 一致（openPosition + openLayoutForPosition），
 * 保证同一时刻只有一张卡片展开；复用视图时 reset() 清除残留位移与堆叠动画。
 */
class DailyTaskAdapter(dataBeans: MutableList<DailyTaskBean>) :
    ListAdapter<DailyTaskBean, DailyTaskAdapter.TaskViewHolder>(DIFF_CALLBACK) {

    /** 调度中、仍待执行的任务实际时间（过点后自动不展示） */
    data class PendingActualHint(val displayTime: String, val millis: Long)

    class TaskViewHolder(val binding: ItemDailyTaskRvLBinding) :
        RecyclerView.ViewHolder(binding.root)

    var mPosition = -1
    private var actualTime = "--:--:--"
    private var schedulerRunning = false
    private var pendingActualById: Map<Int, PendingActualHint> = emptyMap()

    /**
     * 跳过日（节假日/休息日且开启跳过）：DC 快照与状态查询统一把所有今日任务标为 skip，
     * DT 列表这里必须同步，否则会出现「DT 已过点/待执行、DC 全是已跳过」的分歧。
     * 只在 refresh / setActualHintState 时算一次，避免每秒 bind 重复查节假日配置。
     */
    private var todaySkipped = TaskScheduler.isTodaySkipped()

    private var nextPassTime = Long.MAX_VALUE
    private var onItemClickListener: OnItemClickListener? = null

    /** 当前展开操作面板的 item 位置，-1=全部收起 */
    private var openPosition = RecyclerView.NO_POSITION

    /** 由 TaskFragment 注入：position → SwipeRevealLayout 的查找器（避免 Adapter 持有 View 引用） */
    var openLayoutForPosition: ((Int) -> SwipeRevealLayout?)? = null

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
        todaySkipped = TaskScheduler.isTodaySkipped()
        schedulerRunning = running
        pendingActualById = if (running) pending else emptyMap()
        recomputeNextPassTime()
        if (itemCount > 0) {
            notifyItemRangeChanged(0, itemCount, PAYLOAD_ACTUAL_HINT)
        }
    }

    /** 时钟滴答：只在有任务「过点」的瞬间触发一次刷新（避免每秒全量 bind） */
    fun refreshActualHintVisibility() {
        if (todaySkipped || !schedulerRunning || pendingActualById.isEmpty() || itemCount == 0) return
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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TaskViewHolder {
        val binding = ItemDailyTaskRvLBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return TaskViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TaskViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_ACTUAL_HINT)) {
            val taskBean = getItem(position)
            bindActualTime(holder.binding, taskBean, position)
            bindStateVisual(holder, stateOf(taskBean, position))
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: TaskViewHolder, position: Int) {
        val taskBean = getItem(position)
        val b = holder.binding
        val ctx = holder.itemView.context

        b.taskTimeView.text = taskBean.time
        // 备注：无备注时文本留空但控件保留，首行高度与三段布局保持稳定
        b.taskNameView.text = taskBean.name.orEmpty()

        // 随机窗口：开启随机时间时显示 "起始 - 结束"，否则显示「未启用」
        b.randomTimeView.text =
            taskBean.resolveRandomTimeRange(randomTimeEnabled, timeRange) ?: "未启用"

        bindActualTime(b, taskBean, position)
        val state = stateOf(taskBean, position)
        bindStateVisual(holder, state)

        // 用 bindingAdapterPosition + 当前列表项，避免闭包捕获过期 position / 与外部列表脱节
        b.taskCardView.setOnClickListener {
            if (b.swipeRevealLayout.isOpen) {
                // 展开态先收起，避免误触进入编辑
                closeAll()
                return@setOnClickListener
            }
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
            onItemClickListener?.onItemClick(getItem(pos))
        }

        // 长按：任务详情弹层（含复制时间 + 编辑/删除），与左滑的快捷操作分工
        b.taskCardView.setOnLongClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnLongClickListener true
            closeAll()
            holder.itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onItemClickListener?.onItemLongClick(getItem(pos))
            true
        }

        // 左滑按钮：任务进行中不允许编辑/删除（点下去也会被弹窗拦截，这里先把状态表达出来）
        val actionsEnabled = !TaskScheduler.isRunning()
        b.btnActionEdit.isEnabled = actionsEnabled
        b.btnActionDelete.isEnabled = actionsEnabled

        b.btnActionEdit.setOnClickListener {
            closeAll()
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onItemClickListener?.onActionEdit(getItem(pos))
        }
        b.btnActionDelete.setOnClickListener {
            closeAll()
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onItemClickListener?.onActionDelete(getItem(pos))
        }

        // WorkBuddy 式堆叠左滑：进度回调驱动「卡片下沉 + 按钮逐层弹出」
        val density = ctx.resources.displayMetrics.density
        b.swipeRevealLayout.onScrollProgress = { p -> applyStackEffect(b, p, density) }

        // 展开互斥 + 复用复位
        b.swipeRevealLayout.onStateChange = { open ->
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                if (open) {
                    val prev = openPosition
                    if (prev != pos) {
                        if (prev != RecyclerView.NO_POSITION) {
                            openLayoutForPosition?.invoke(prev)?.close()
                        }
                        openPosition = pos
                    }
                } else if (openPosition == pos) {
                    openPosition = RecyclerView.NO_POSITION
                }
            }
        }
        b.swipeRevealLayout.reset()
    }

    /**
     * 层层错位堆叠的左滑效果（纯 View 属性动画，无 animator 资源，R8 下零风险）：
     * - 内容卡片随进度下沉 + 缩小 + 阴影加深，像被"掀开"露出底层；
     * - 删除卡（底层）先抽出：落到右侧、下沉、顺时针倾斜；
     * - 编辑卡（上层）后抽出：落到左侧、上浮、逆时针倾斜，
     *   两卡由重合状态逐渐错位，最终像一叠斜放的卡片，各露一侧可点。
     */
    private fun applyStackEffect(b: ItemDailyTaskRvLBinding, p: Float, density: Float) {
        // 任务进行中不允许编辑/删除：按钮整体置灰。
        // alpha 会被下面的堆叠动画覆盖，所以统一乘 actionAlpha；
        // 现读 TaskScheduler 状态而非 bind 时的快照，避免"任务启动后已展开的卡片仍是亮的"。
        val actionAlpha = if (TaskScheduler.isRunning()) 0.45f else 1f

        // 内容卡片被掀开：只下沉 4dp、缩小到 0.985、阴影 0→2dp（幅度克制，不"冒出"整行）
        b.taskCardView.translationY = STACK_CARD_DROP * density * p
        val cardScale = 1f - STACK_CARD_SHRINK * p
        b.taskCardView.scaleX = cardScale
        b.taskCardView.scaleY = cardScale
        b.taskCardView.cardElevation = STACK_CARD_ELEV * density * p

        // 底层删除卡 0→0.55 抽出；上层编辑卡 0.22→1 抽出（错位有先后）
        val deleteT = easeOut((p / 0.55f).coerceIn(0f, 1f))
        val editT = easeOut(((p - 0.22f) / 0.78f).coerceIn(0f, 1f))
        // 水平错开 66dp、卡宽 72dp → 仅重叠 6dp，两枚文字均完整可见且各自可点
        applyStackedCard(b.btnActionDelete, deleteT, actionAlpha, density,
            targetX = 0f, targetY = STACK_DELETE_DROP_Y, targetRot = STACK_DELETE_ROT)
        applyStackedCard(b.btnActionEdit, editT, actionAlpha, density,
            targetX = -STACK_EDIT_SHIFT_X, targetY = -STACK_EDIT_RISE_Y, targetRot = -STACK_EDIT_ROT)
    }

    /**
     * 单张卡片从"重合态"抽出到目标错位态。
     * t=0：alpha 0、scale 0.6、无位移无旋转；t=1：落到目标位置。
     */
    private fun applyStackedCard(
        view: View, t: Float, actionAlpha: Float, density: Float,
        targetX: Float, targetY: Float, targetRot: Float
    ) {
        view.alpha = actionAlpha * t
        val scale = 0.6f + 0.4f * t
        view.scaleX = scale
        view.scaleY = scale
        view.translationX = targetX * density * t
        view.translationY = targetY * density * t
        view.rotation = targetRot * t
    }

    private fun easeOut(t: Float): Float = 1f - (1f - t) * (1f - t) * (1f - t)

    /**
     * 推导任务状态：执行中 / 待执行 / 已过点 / 中性。
     *
     * 说明：alpha 调度器不回填「已完成/跳过」的 adapter 状态，这里用
     * [mPosition]（执行中）与 [pendingActualById]（今日计划全量，含时间戳）推导：
     *  - 执行中：position 命中 [mPosition]
     *  - 待执行：调度运行中，该任务在今日计划里、尚未过点
     *  - 已过点：调度运行中，该任务在今日计划里、已过点（已执行/超时，统一琥珀，不细分完成 vs 超时）
     *  - 已跳过：跳过日（节假日/休息日且开启跳过），与 DC 快照 taskStatus 一致，全部任务统一跳过
     *  - 中性：其余情况（调度未运行，或该任务不在今日计划内，即非今日任务）
     * */
    private fun stateOf(taskBean: DailyTaskBean, position: Int): Int {
        if (todaySkipped) return STATE_SKIPPED
        return when {
            // position >= 0 守卫：详情弹层按 id 反查可能返回 -1，而 mPosition 空闲时也是 -1，
            // 不加守卫会把「找不到」误判成「执行中」
            position >= 0 && position == mPosition -> STATE_RUNNING
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
    }

    /** 状态胶囊：文案 + 柔光底色（配色对齐 alpha 的 md 色板） */
    private fun bindStatePill(b: ItemDailyTaskRvLBinding, state: Int) {
        val context = b.root.context
        val (label, textColorRes, bgColorRes) = pillStyle(state)
        b.stateTextView.text = label
        b.stateTextView.setTextColor(textColorRes.convertColor(context))
        b.stateCardView.setCardBackgroundColor(bgColorRes.convertColor(context))
    }

    /** 状态胶囊的配色表：列表 bind 与长按详情弹层共用，避免两处色表漂移 */
    private fun pillStyle(state: Int): Triple<String, Int, Int> = when (state) {
        STATE_RUNNING -> Triple("执行中", R.color.md_primary, R.color.md_primaryContainer)
        STATE_PENDING -> Triple(
            "待执行", R.color.md_onSurfaceVariant, R.color.md_surfaceVariant
        )

        STATE_PASSED -> Triple(
            "已过点", R.color.md_onWarningContainer, R.color.md_warningContainer
        )

        STATE_SKIPPED -> Triple(
            "已跳过", R.color.md_onSurfaceVariant, R.color.md_surfaceVariant
        )

        else -> Triple("-", R.color.md_onSurfaceVariant, R.color.md_surfaceVariant)
    }

    /**
     * 供长按详情弹层使用：当前任务的（状态文案，文字色，背景色）。
     * 位置按 id 反查，保证与列表当前状态一致（不经由闭包捕获旧 position）。
     */
    fun stateBadge(item: DailyTaskBean): Triple<String, Int, Int> {
        val position = currentList.indexOfFirst { it.id == item.id }
        return pillStyle(stateOf(item, position))
    }

    /**
     * 状态视觉统一更新：状态药丸 + 卡片高亮 + 计划时间颜色 + 装饰器标记。
     * 供全量 bind 与 payload 刷新（时钟滴答触发「待执行 → 已过点」切换）共用，
     * 保证状态切换时各处视觉一致更新，不残留旧色。
     *
     * 链式节点本体已改由 ChainLineDecoration 绘制（卡片化后不再有独立的节点 View），
     * 这里只把「是否已过点」「是否执行中」写入 itemView 的 keyed tag 供其读取。
     * */
    private fun bindStateVisual(holder: TaskViewHolder, state: Int) {
        val b = holder.binding
        bindStatePill(b, state)
        bindCardHighlight(b, state)
        holder.itemView.setTag(ChainLineDecoration.TAG_PASSED, state == STATE_PASSED)
        holder.itemView.setTag(ChainLineDecoration.TAG_RUNNING, state == STATE_RUNNING)
        val timeColorRes = when (state) {
            STATE_RUNNING -> R.color.md_onSurfaceVariant
            STATE_PASSED -> R.color.md_warning
            STATE_SKIPPED -> R.color.md_onSurfaceVariant
            else -> R.color.md_onSurface
        }
        b.taskTimeView.setTextColor(timeColorRes.convertColor(holder.itemView.context))
    }

    /** 执行中卡片高亮：主题色描边锁定当前任务 */
    private fun bindCardHighlight(b: ItemDailyTaskRvLBinding, state: Int) {
        val context = b.root.context
        if (state == STATE_RUNNING) {
            val strokePx = (2f * context.resources.displayMetrics.density).toInt()
            b.taskCardView.strokeWidth = strokePx
            b.taskCardView.strokeColor = R.color.md_primary.convertColor(context)
        } else {
            b.taskCardView.strokeWidth = (1f * context.resources.displayMetrics.density).toInt()
            b.taskCardView.strokeColor = ContextCompat.getColor(context, R.color.md_outlineVariant)
        }
    }

    /**
     * 实际执行时间：执行中的任务显示当前实际时间；今日计划里未过点的显示预定时间；
     * 其余（非今日 / 已过点 / 跳过日 / 调度未运行）显示占位。
     *
     * 卡片化后不再有「点击展开大号实际时间」的区域，信息合并到右下这枚小卡常驻展示。
     * */
    private fun bindActualTime(b: ItemDailyTaskRvLBinding, taskBean: DailyTaskBean, position: Int) {
        b.actualTimeView.text = resolveActualText(taskBean, position)
    }

    /** 供长按详情弹层复用同一条实际执行时间的推导逻辑 */
    fun actualTimeText(item: DailyTaskBean): String {
        val position = currentList.indexOfFirst { it.id == item.id }
        return resolveActualText(item, position)
    }

    private fun resolveActualText(taskBean: DailyTaskBean, position: Int): String {
        val hint = pendingActualById[taskBean.id]
        return when {
            // 同 stateOf：position 可能是 -1（详情弹层反查失败），需与 mPosition 的 -1 空闲值区分
            position >= 0 && position == mPosition && actualTime != PLACEHOLDER -> actualTime
            todaySkipped -> PLACEHOLDER
            schedulerRunning && hint != null && hint.millis > System.currentTimeMillis() -> hint.displayTime
            else -> PLACEHOLDER
        }
    }

    fun refresh(newRows: MutableList<DailyTaskBean>) {
        // 数据整体替换前先收起展开态，否则 openPosition 会指向旧位置（列表增删/移动后会错位）
        closeAll()
        // 跳过日配置可能变化，列表刷新时重算一次（不在 bind 里反复查）
        todaySkipped = TaskScheduler.isTodaySkipped()
        // 随机窗口配置：列表数据变化时刷新一次缓存，避免每次 bind 都读 SharedPreferences
        randomTimeEnabled = SaveKeyValues.loadBoolean(Constant.RANDOM_TIME_KEY, true)
        timeRange = SaveKeyValues.loadInt(Constant.TIME_RANGE_KEY, Constant.DEFAULT_TIME_RANGE)
        // ListAdapter 内部基于 DiffUtil 计算增量，仅刷新变化的 item，自动获得增/删/移动动画
        submitList(ArrayList(newRows))
    }

    /** 供外部（含数据刷新前）收起所有已展开的操作面板 */
    fun closeAll() {
        if (openPosition == RecyclerView.NO_POSITION) return
        val pos = openPosition
        openPosition = RecyclerView.NO_POSITION
        openLayoutForPosition?.invoke(pos)?.close()
    }

    interface OnItemClickListener {
        /** 点击卡片：修改任务 */
        fun onItemClick(item: DailyTaskBean)

        /** 长按卡片：任务详情弹层 */
        fun onItemLongClick(item: DailyTaskBean)

        /** 左滑「编辑」 */
        fun onActionEdit(item: DailyTaskBean)

        /** 左滑「删除」 */
        fun onActionDelete(item: DailyTaskBean)
    }

    fun setOnItemClickListener(listener: OnItemClickListener) {
        this.onItemClickListener = listener
    }

    companion object {
        private const val PAYLOAD_ACTUAL_HINT = "actual_hint"

        private const val PLACEHOLDER = "--:--:--"

        private const val STATE_IDLE = 0      // 调度未运行 / 非今日任务 / 中性
        private const val STATE_RUNNING = 1   // 执行中
        private const val STATE_PENDING = 2   // 待执行
        private const val STATE_PASSED = 3    // 已过点（已执行/超时，琥珀）
        private const val STATE_SKIPPED = 4   // 跳过日：与 DC 快照 skip 一致（全量灰色）

        // 堆叠效果幅度（单位 dp，运行时乘 density）
        private const val STACK_CARD_DROP = 4f
        private const val STACK_CARD_SHRINK = 0.015f
        private const val STACK_CARD_ELEV = 2f
        // 删除卡：右侧、下沉、顺时针
        private const val STACK_DELETE_DROP_Y = 9f
        private const val STACK_DELETE_ROT = 5f
        // 编辑卡：左侧错位、上浮、逆时针（中心距删除卡 66dp，卡宽 72dp → 仅重叠 6dp）
        private const val STACK_EDIT_SHIFT_X = 66f
        private const val STACK_EDIT_RISE_Y = 7f
        private const val STACK_EDIT_ROT = -4f

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
