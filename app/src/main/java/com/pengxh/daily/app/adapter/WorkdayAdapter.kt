package com.pengxh.daily.app.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.pengxh.daily.app.R
import com.pengxh.daily.app.utils.CustomWorkdayManager
import com.pengxh.kt.lite.extensions.convertColor
import com.pengxh.kt.lite.extensions.show
import java.time.DayOfWeek

/**
 * 工作日网格磁贴适配器（借鉴 master，适配 alpha 的 CustomWorkdayManager 排序）。
 *
 * 选中态通过 [View.isSelected] 驱动背景 selector，文字色随选中态切换。
 * 至少保留一天：唯一选中项再点一次不取消，并提示。
 * */
class WorkdayAdapter(
    private val selected: MutableSet<DayOfWeek>,
    private val onSelectInvalid: (() -> Unit)? = null
) : RecyclerView.Adapter<WorkdayAdapter.ViewHolder>() {

    class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val chipView: com.google.android.material.textview.MaterialTextView =
            v.findViewById(R.id.chipView)
    }

    private val days: List<DayOfWeek> = CustomWorkdayManager.getOrderedDays()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_workday_rv_g, parent, false)
        )
    }

    override fun getItemCount(): Int = days.size

    override fun onBindViewHolder(holder: ViewHolder, pos: Int) {
        val day = days[pos]
        val chip = holder.chipView
        chip.text = CustomWorkdayManager.getDayLabel(day)
        bindSelected(chip, day in selected)
        chip.setOnClickListener {
            if (day in selected && selected.size == 1) {
                // 至少保留一天为工作日
                if (onSelectInvalid != null) onSelectInvalid()
                else chip.context.let { ctx -> "至少保留一天为工作日".show(ctx) }
                return@setOnClickListener
            }
            if (day in selected) selected.remove(day) else selected.add(day)
            bindSelected(chip, day in selected)
        }
    }

    private fun bindSelected(chip: com.google.android.material.textview.MaterialTextView, isSelected: Boolean) {
        chip.isSelected = isSelected
        val context = chip.context
        val colorRes = if (isSelected) R.color.md_onPrimaryContainer else R.color.md_onSurfaceVariant
        chip.setTextColor(colorRes.convertColor(context))
    }
}
