package com.pengxh.daily.app.utils

import android.content.Context
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import com.pengxh.daily.app.BuildConfig
import com.pengxh.daily.app.extensions.notificationEnable
import com.pengxh.daily.app.service.AutoProjectionAccessibilityService
import com.pengxh.kt.lite.utils.SaveKeyValues
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一键诊断：收集应用版本、设备、权限、配置、电量与最近运行日志，供问题排查导出。
 */
object DiagnosticReporter {

    fun buildReport(context: Context): String {
        val batteryManager = context.getSystemService(BatteryManager::class.java)
        val battery = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val powerManager = context.getSystemService(PowerManager::class.java)
        val systemPowerSave = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            powerManager.isPowerSaveMode
        } else false
        val batteryOptimized = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            !powerManager.isIgnoringBatteryOptimizations(context.packageName)
        } else false

        val targetApp = Constant.getTargetApp()
        val appName = Constant.getAppName(targetApp)
        val resultSource = SaveKeyValues.loadInt(Constant.RESULT_SOURCE_KEY, Constant.DEFAULT_INDEX)
        val resultSourceText = when (resultSource) {
            0 -> "通知监听"
            1 -> "截屏反馈"
            2 -> {
                val mode =
                    if (SaveKeyValues.loadInt(Constant.ACCESSIBILITY_FEEDBACK_MODE_KEY, 0) == 0) "截屏" else "文本"
                "无障碍-${mode}反馈"
            }

            else -> "未配置"
        }
        val channelType = SaveKeyValues.loadInt(Constant.MSG_CHANNEL_KEY, Constant.DEFAULT_INDEX)
        val channelText = when (channelType) {
            0 -> "QQ邮箱"
            1 -> "企业微信"
            else -> "未配置"
        }
        val a11yEnabled = AutoProjectionAccessibilityService.isEnabled(context)
        val a11yReady = AutoProjectionAccessibilityService.canTakeScreenshot(context)

        return buildString {
            appendLine("==================== DailyTask 诊断日志 ====================")
            appendLine()
            appendLine("【基础信息】")
            appendLine("· 生成时间：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())}")
            appendLine("· 应用版本：${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("· 包名：${context.packageName}")
            appendLine()
            appendLine("【设备信息】")
            appendLine("· 厂商/型号：${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("· 系统版本：Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("· 当前电量：${if (battery >= 0) "$battery%" else "未知"}")
            appendLine("· 系统省电模式：${if (systemPowerSave) "开启" else "关闭"}")
            appendLine()
            appendLine("【权限状态】")
            appendLine("· 悬浮窗权限：${if (Settings.canDrawOverlays(context)) "已获取" else "未获取"}")
            appendLine("· 通知监听：${if (context.notificationEnable()) "已授权" else "未授权"}")
            appendLine("· 无障碍服务：启用=$a11yEnabled，截屏能力=${if (a11yReady) "可用(需Android14+)" else "不可用"}")
            appendLine("· 电池优化：${if (batteryOptimized) "未豁免(可能被限制)" else "已豁免"}")
            appendLine()
            appendLine("【当前配置】")
            appendLine("· 目标应用：$appName")
            appendLine("· 结果来源：$resultSourceText")
            appendLine("· 消息渠道：$channelText")
            appendLine("· 每日循环：${if (SaveKeyValues.loadBoolean(Constant.TASK_AUTO_RECYCLE_KEY, true)) "开启" else "关闭"}")
            appendLine("· 跳过节假日：${if (SaveKeyValues.loadBoolean(Constant.SKIP_HOLIDAY_KEY, true)) "开启" else "关闭"}")
            appendLine("· 省电模式：${if (AppRuntimeConfig.isPowerSaveMode()) "开启" else "关闭"}")
            appendLine("· 强制伪息屏：${if (AppRuntimeConfig.isForcePseudoMask()) "开启" else "关闭"}")
            appendLine()
            appendLine("【最近警告/错误（W/E，最近 100 行）】")
            appendLine(LogFileManager.readLogContent(100, LogLevel.W))
            appendLine()
            appendLine("【运行日志（最近 500 行）】")
            appendLine(LogFileManager.readLogContent(500))
        }
    }

    /**
     * 将诊断报告写入外部 Documents 目录，返回文件；失败返回 null。
     */
    fun exportToFile(context: Context): File? {
        return try {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: return null
            val file = File(dir, "diagnostic_${System.currentTimeMillis()}.txt")
            file.writeText(buildReport(context))
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
