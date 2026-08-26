package com.pengxh.daily.app.ui

import android.os.Bundle
import com.pengxh.daily.app.UiInsets
import com.pengxh.daily.app.R
import com.pengxh.daily.app.databinding.ActivityCommandHistoryBinding
import com.pengxh.daily.app.utils.CommandEntry
import com.pengxh.daily.app.utils.CommandHistoryRecorder
import com.pengxh.kt.lite.adapter.NormalRecyclerAdapter
import com.pengxh.kt.lite.adapter.ViewHolder
import com.pengxh.kt.lite.base.KotlinBaseActivity
import com.pengxh.kt.lite.extensions.show
import com.pengxh.kt.lite.extensions.timestampToCompleteDate

class CommandHistoryActivity : KotlinBaseActivity<ActivityCommandHistoryBinding>() {

    override fun initViewBinding(): ActivityCommandHistoryBinding {
        return ActivityCommandHistoryBinding.inflate(layoutInflater)
    }

    override fun setupTopBarLayout() {
        UiInsets.applyStatusBarPadding(this, binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_clear -> {
                    CommandHistoryRecorder.clear()
                    loadHistory()
                    "指令历史已清空".show(this)
                }
            }
            true
        }
    }

    override fun initOnCreate(savedInstanceState: Bundle?) {
        loadHistory()
    }

    private fun loadHistory() {
        val entries = CommandHistoryRecorder.load().asReversed()
        if (entries.isEmpty()) {
            binding.emptyView.visibility = android.view.View.VISIBLE
            binding.recyclerView.visibility = android.view.View.GONE
            return
        }
        binding.emptyView.visibility = android.view.View.GONE
        binding.recyclerView.visibility = android.view.View.VISIBLE

        val adapter = object : NormalRecyclerAdapter<CommandEntry>(
            R.layout.item_command_history, entries
        ) {
            override fun convertView(viewHolder: ViewHolder, position: Int, item: CommandEntry) {
                viewHolder.setText(R.id.commandView, item.command)
                    .setText(R.id.sourceView, item.source)
                    .setText(R.id.timeView, item.timestamp.timestampToCompleteDate())

                if (item.result.isNotBlank()) {
                    viewHolder.setText(R.id.resultView, "结果: ${item.result}")
                    viewHolder.getView<android.widget.TextView>(R.id.resultView).visibility = android.view.View.VISIBLE
                } else {
                    viewHolder.getView<android.widget.TextView>(R.id.resultView).visibility = android.view.View.GONE
                }
            }
        }
        binding.recyclerView.adapter = adapter
    }

    override fun observeRequestState() {}

    override fun initEvent() {}
}