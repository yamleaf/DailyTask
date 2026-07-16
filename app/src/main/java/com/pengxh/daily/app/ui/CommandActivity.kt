package com.pengxh.daily.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.card.MaterialCardView
import com.pengxh.daily.app.R
import com.pengxh.daily.app.databinding.ActivityCommandBinding
import com.pengxh.daily.app.utils.StatusReporter
import com.pengxh.kt.lite.adapter.NormalRecyclerAdapter
import com.pengxh.kt.lite.adapter.ViewHolder
import com.pengxh.kt.lite.base.KotlinBaseActivity
import com.pengxh.kt.lite.extensions.convertColor
import com.pengxh.kt.lite.extensions.show

class CommandActivity : KotlinBaseActivity<ActivityCommandBinding>() {

    private val context = this
    private val list = StatusReporter.remoteCommands.map { (cmd, desc) ->
        val hasFeedback = when (cmd) {
            "DT#执行任务", "DT#息屏", "DT#亮屏" -> false
            else -> true
        }
        Triple(cmd, desc, hasFeedback)
    }.toMutableList()
    private val clipboard by lazy { getSystemService(ClipboardManager::class.java) }

    override fun initViewBinding(): ActivityCommandBinding {
        return ActivityCommandBinding.inflate(layoutInflater)
    }

    override fun setupTopBarLayout() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbar) { view, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.setPadding(0, statusBarHeight, 0, 0)
            insets
        }
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    override fun initOnCreate(savedInstanceState: Bundle?) {
        val adapter = object : NormalRecyclerAdapter<Triple<String, String, Boolean>>(
            R.layout.item_command_rv_l, list
        ) {
            override fun convertView(
                viewHolder: ViewHolder, position: Int, item: Triple<String, String, Boolean>
            ) {
                viewHolder.setText(R.id.commandView, item.first)
                    .setText(R.id.descView, item.second)
                    .setText(R.id.flagView, if (item.third) "有反馈" else "无反馈")

                val cardView = viewHolder.getView<MaterialCardView>(R.id.cardView)
                if (item.third) {
                    cardView.setCardBackgroundColor(R.color.ios_green.convertColor(context))
                } else {
                    cardView.setCardBackgroundColor(R.color.orange.convertColor(context))
                }
            }
        }
        binding.recyclerView.adapter = adapter
        adapter.setOnItemClickedListener(object :
            NormalRecyclerAdapter.OnItemClickedListener<Triple<String, String, Boolean>> {
            override fun onItemClicked(position: Int, item: Triple<String, String, Boolean>) {
                val cipData = ClipData.newPlainText("RemoteCommand", item.first)
                clipboard.setPrimaryClip(cipData)
                "指令「${item.first}」已复制".show(context)
            }
        })
    }

    override fun observeRequestState() {

    }

    override fun initEvent() {

    }
}