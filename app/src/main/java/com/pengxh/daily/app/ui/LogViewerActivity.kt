package com.pengxh.daily.app.ui

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.pengxh.daily.app.R
import com.pengxh.daily.app.UiInsets
import com.pengxh.daily.app.databinding.ActivityLogViewerBinding
import com.pengxh.daily.app.utils.LogFileManager
import com.pengxh.daily.app.utils.LogLevel
import com.pengxh.kt.lite.adapter.NormalRecyclerAdapter
import com.pengxh.kt.lite.adapter.ViewHolder
import com.pengxh.kt.lite.base.KotlinBaseActivity
import com.pengxh.kt.lite.extensions.convertColor
import com.pengxh.kt.lite.extensions.show
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LogEntry(val time: String, val level: LogLevel, val message: String)

class LogViewerActivity : KotlinBaseActivity<ActivityLogViewerBinding>() {

    private var allLogs: MutableList<LogEntry> = mutableListOf()
    private var currentFilter: LogLevel = LogLevel.D

    override fun initViewBinding(): ActivityLogViewerBinding {
        return ActivityLogViewerBinding.inflate(layoutInflater)
    }

    override fun setupTopBarLayout() {
        UiInsets.applyStatusBarPadding(this, binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_clear -> {
                    loadLogs()
                    "已刷新".show(this)
                }
            }
            true
        }
    }

    override fun initOnCreate(savedInstanceState: Bundle?) {
        setupFilterChips()
        loadLogs()
    }

    private fun setupFilterChips() {
        binding.filterGroup.setOnCheckedStateChangeListener { _, _ ->
            val checkedId = binding.filterGroup.checkedChipId
            currentFilter = when (checkedId) {
                R.id.filterInfo -> LogLevel.I
                R.id.filterWarning -> LogLevel.W
                R.id.filterError -> LogLevel.E
                R.id.filterAction -> LogLevel.A
                else -> LogLevel.D
            }
            applyFilter()
        }
    }

    private fun loadLogs() {
        lifecycleScope.launch {
            try {
                val raw = withContext(Dispatchers.IO) {
                    LogFileManager.readLogContent(1000)
                }
                allLogs = parseLogs(raw)
                applyFilter()
            } catch (e: Exception) {
                Log.e("LogViewer", "load logs failed", e)
                "加载日志失败: ${e.message}".show(this@LogViewerActivity)
            }
        }
    }

    private fun parseLogs(raw: String): MutableList<LogEntry> {
        if (raw.isBlank()) return mutableListOf()
        return raw.lines().filter { it.isNotBlank() }.mapNotNull { line ->
            try {
                if (line.length < 19) return@mapNotNull null
                val time = line.substring(0, 19)
                val levelMatch = Regex("""\[([D,I,W,E,A])\]""").find(line) ?: return@mapNotNull null
                val level = when (levelMatch.groupValues[1]) {
                    "D" -> LogLevel.D
                    "I" -> LogLevel.I
                    "W" -> LogLevel.W
                    "E" -> LogLevel.E
                    "A" -> LogLevel.A
                    else -> return@mapNotNull null
                }
                val msgStart = levelMatch.range.last + 1
                val message = line.substring(msgStart.coerceAtMost(line.length)).trim()
                LogEntry(time, level, message)
            } catch (e: Exception) {
                null
            }
        }.toMutableList()
    }

    private fun applyFilter() {
        try {
            val filtered = if (currentFilter == LogLevel.D) {
                allLogs
            } else {
                allLogs.filter { it.level == currentFilter }.toMutableList()
            }

            if (filtered.isEmpty()) {
                binding.emptyView.visibility = View.VISIBLE
                binding.recyclerView.visibility = View.GONE
                return
            }
            binding.emptyView.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE

            val adapter = object : NormalRecyclerAdapter<LogEntry>(R.layout.item_log_entry, filtered) {
                override fun convertView(viewHolder: ViewHolder, position: Int, item: LogEntry) {
                    try {
                        val levelColor = when (item.level) {
                            LogLevel.E -> R.color.md_error.convertColor(this@LogViewerActivity)
                            LogLevel.W -> 0xFFFFA000.toInt()
                            LogLevel.A -> R.color.md_primary.convertColor(this@LogViewerActivity)
                            else -> R.color.md_onSurfaceVariant.convertColor(this@LogViewerActivity)
                        }
                        val levelText = when (item.level) {
                            LogLevel.D -> "D"
                            LogLevel.I -> "I"
                            LogLevel.W -> "W"
                            LogLevel.E -> "E"
                            LogLevel.A -> "A"
                        }
                        viewHolder.setText(R.id.timeView, item.time)
                            .setText(R.id.messageView, item.message)
                            .setText(R.id.levelView, levelText)
                        viewHolder.getView<TextView>(R.id.levelView).setTextColor(levelColor)
                    } catch (e: Exception) {
                        Log.e("LogViewer", "convert view failed", e)
                    }
                }
            }
            binding.recyclerView.adapter = adapter
        } catch (e: Exception) {
            Log.e("LogViewer", "apply filter failed", e)
        }
    }

    override fun observeRequestState() {}

    override fun initEvent() {}
}