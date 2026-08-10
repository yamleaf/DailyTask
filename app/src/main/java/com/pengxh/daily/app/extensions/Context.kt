package com.pengxh.daily.app.extensions

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.pengxh.daily.app.DailyTaskApplication
import com.pengxh.daily.app.ui.MainActivity
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.FloatingWindowController
import com.pengxh.daily.app.utils.LogFileManager
import com.pengxh.daily.app.utils.RomDetector
import com.pengxh.daily.app.utils.TaskScheduler
import com.pengxh.kt.lite.extensions.show

/**
 * 检测通知监听服务是否被授权
 * */
fun Context.notificationEnable(): Boolean {
    val packages = NotificationManagerCompat.getEnabledListenerPackages(this)
    return packages.contains(packageName)
}

/**
 * 检测「自启动」权限是否已授予（开机自启/后台常驻），按厂商分派：
 * - 小米 MIUI/HyperOS：自定义 op 10008（自启动），allow=已授予；
 * - 华为/OPPO/vivo：无公开 appops，返回 null（由用户手动确认）；
 * - 原生 Android：RECEIVE_BOOT_COMPLETED 已声明即视为允许，返回 null（无需独立引导）。
 * 返回 null 表示「无公开检测渠道」，调用方需结合厂商自行降级处理。
 */
fun Context.isAutostartGranted(): Boolean? {
    if (!RomDetector.isMiui()) return null
    return try {
        val appOps = getSystemService(AppOpsManager::class.java) ?: return null
        val method = AppOpsManager::class.java.getMethod(
            "checkOpNoThrow",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java
        )
        val result = method.invoke(appOps, 10008, Process.myUid(), packageName) as? Int
        result == AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) {
        null
    }
}

/**
 * 判断指定包名的应用是否存在
 */
fun Context.isApplicationExist(packageName: String): Boolean {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            packageManager.getPackageInfo(packageName, 0)
        }
        true
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(javaClass.simpleName, "查询已安装应用失败", e)
            false
        }
}

/**
 * 打开指定包名的 apk，之后执行回调
 *
 * @param onOpened 目标 App 成功打开后执行的回调（如启动超时计时器）
 */
fun Context.openApplication(onOpened: (() -> Unit)? = null) {
    val targetApp = Constant.getTargetApp()
    Log.d("Ex-Context", "openApplication: $targetApp")
    if (!isApplicationExist(targetApp)) {
        "未安装指定的目标软件，无法执行任务".show(this)
        TaskScheduler.requestStopDueToError("未安装指定的目标软件，无法执行任务")
        return
    }

    // 后台启动限制（Android 10+ / MIUI 后台弹出界面）：从后台直接启动目标 App 可能被系统拦截。
    // 需在系统权限页为 DailyTask 开启「后台弹出界面」/「后台启动其它应用」后方可正常拉起。
    // 注：曾尝试「预拉起 MainActivity 获取可见窗口豁免」方案，但 MIUI 的 DeviceGuard 连 MainActivity
    // 自身的后台启动都会拦截，豁免无效，且会引入界面闪现与延迟，故回退为直接启动。
    fun startTarget() {
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            setPackage(targetApp)
        }
        val activities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            packageManager.queryIntentActivities(intent, 0)
        }
        if (activities.isNotEmpty()) {
            val info = activities.first()
            intent.component = ComponentName(info.activityInfo.packageName, info.activityInfo.name)
            startActivity(intent)
            // 被控端主动跳到目标 App：开启悬浮窗倒计时会话（统一收口，所有跳转自动覆盖，无需逐个调用方接入）
            FloatingWindowController.startFloatSession()
            onOpened?.invoke()
        } else {
            TaskScheduler.requestStopDueToError("未找到目标应用的 Launcher Activity，包名：$targetApp")
        }
    }

    if (isAppInForeground()) {
        startTarget()
        return
    }
    // 后台：直接启动目标 App（需已授权「后台弹出界面」）
    try {
        startTarget()
    } catch (e: Exception) {
        LogFileManager.error("openApplication 后台启动目标 App 失败: ${e.message}")
        TaskScheduler.requestStopDueToError("启动目标 App 失败，请检查「后台弹出界面」权限: ${e.message}")
    }
}

/**
 * 判断本应用当前是否在前台（存在可见 Activity）。
 * 用于后台启动限制规避：在前台时后台拉起其它应用才被系统允许。
 * 复用 DailyTaskApplication 的 Activity 生命周期统计，比 appTasks.taskInfo.topActivity
 * 可靠——Home 键隐藏后 task 栈顶仍是 MainActivity，但应用已不在前台。
 */
private fun Context.isAppInForeground(): Boolean = DailyTaskApplication.isAppForeground

/**
 * 将本应用（DailyTask）主界面拉到前台，并可选同步伪息屏蒙层状态。
 *
 * 仅用于「强制伪息屏」路径：先跳回 DailyTask，再由 MainActivity 显示伪息屏蒙层，
 * 避免在其它 App 上直接盖黑屏蒙层（可能打断其它 App 正常使用）。
 * 注意：远程打卡复原路径（NotificationMonitorService.bringMainActivityForMask）保持独立实现，
 * 切勿复用本扩展，避免两条带 EXTRA_MASK_COMMAND 的通道互相干扰导致打卡异常。
 *
 * 依赖应用已获得 SYSTEM_ALERT_WINDOW（悬浮窗）权限，后台拉起主界面才不会被系统拦截。
 *
 * @param showMask true=拉起后显示伪息屏蒙层，false=拉起并退出蒙层
 */
fun Context.bringDailyTaskToFront(showMask: Boolean = false) {
    try {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
            putExtra(Constant.EXTRA_MASK_COMMAND, if (showMask) 1 else 0)
        }
        startActivity(intent)
    } catch (e: Exception) {
        Log.w("Ex-Context", "bringDailyTaskToFront failed: ${e.message}")
        LogFileManager.error("拉起主界面失败（蒙层仍可能已通过悬浮窗显示）: ${e.message}")
    }
}
