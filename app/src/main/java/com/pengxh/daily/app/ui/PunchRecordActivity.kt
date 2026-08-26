package com.pengxh.daily.app.ui

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import com.pengxh.daily.app.R
import com.pengxh.daily.app.UiInsets
import com.pengxh.daily.app.databinding.ActivityPunchRecordBinding
import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.daily.app.sqlite.bean.NotificationBean
import com.pengxh.kt.lite.adapter.NormalRecyclerAdapter
import com.pengxh.kt.lite.adapter.ViewHolder
import com.pengxh.kt.lite.base.KotlinBaseActivity
import com.pengxh.kt.lite.extensions.convertColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

class PunchRecordActivity : KotlinBaseActivity<ActivityPunchRecordBinding>() {

    override fun initViewBinding(): ActivityPunchRecordBinding {
        return ActivityPunchRecordBinding.inflate(layoutInflater)
    }

    override fun setupTopBarLayout() {
        UiInsets.applyStatusBarPadding(this, binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    override fun initOnCreate(savedInstanceState: Bundle?) {
        loadRecords()
    }

    private fun loadRecords() {
        lifecycleScope.launch {
            val end = LocalDate.now().plusDays(1)
            val start = end.minusDays(30)
            val records = withContext(Dispatchers.IO) {
                DatabaseWrapper.loadNoticeRange(start, end)
            }
            // noticeMessage/postTime/packageName 来自 DB（Java bean 平台类型，可能为 NULL），全部空安全访问
            val filtered = records
                .filter { it.noticeMessage?.contains("打卡") == true }
                .reversed().toMutableList()

            if (filtered.isEmpty()) {
                binding.emptyView.visibility = android.view.View.VISIBLE
                binding.recyclerView.visibility = android.view.View.GONE
                return@launch
            }
            binding.emptyView.visibility = android.view.View.GONE
            binding.recyclerView.visibility = android.view.View.VISIBLE

            val adapter = object : NormalRecyclerAdapter<NotificationBean>(
                R.layout.item_punch_record, filtered
            ) {
                override fun convertView(viewHolder: ViewHolder, position: Int, item: NotificationBean) {
                    val msg = item.noticeMessage ?: ""
                    val isSuccess = msg.contains("考勤成功") || msg.contains("打卡成功")
                    val isTimeout = msg.contains("超时")
                    val resultText = when {
                        isSuccess -> "成功"
                        isTimeout -> "超时"
                        else -> "通知"
                    }
                    val resultColor = when {
                        isSuccess -> R.color.md_success.convertColor(this@PunchRecordActivity)
                        isTimeout -> 0xFFFFA000.toInt()
                        else -> R.color.md_onSurfaceVariant.convertColor(this@PunchRecordActivity)
                    }
                    viewHolder.setText(R.id.resultView, resultText)
                    viewHolder.getView<android.widget.TextView>(R.id.resultView)
                        .setTextColor(resultColor)
                    viewHolder.setText(R.id.timeView, item.postTime ?: "--")
                    viewHolder.setText(R.id.appView, formatAppName(item.packageName))
                    viewHolder.setText(R.id.messageView, msg)
                }
            }
            binding.recyclerView.adapter = adapter
        }
    }

    private fun formatAppName(packageName: String?): String {
        return when (packageName) {
            "com.alibaba.android.rimet" -> "钉钉"
            "com.tencent.wework" -> "企业微信"
            "com.ss.android.lark" -> "飞书"
            "com.tencent.mobileqq" -> "QQ"
            "com.tencent.tim" -> "TIM"
            "com.tencent.mm" -> "微信"
            "com.eg.android.AlipayGphone" -> "支付宝"
            "com.seeyon.cmp" -> "致远互联"
            else -> packageName ?: "未知"
        }
    }

    override fun observeRequestState() {}

    override fun initEvent() {}
}