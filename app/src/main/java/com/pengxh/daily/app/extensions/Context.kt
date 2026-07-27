package com.pengxh.daily.app.extensions

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.pengxh.daily.app.ui.MainActivity
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.LogFileManager
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
        e.printStackTrace()
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

    // 跳转目标应用
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
        onOpened?.invoke()
    } else {
        TaskScheduler.requestStopDueToError("未找到目标应用的 Launcher Activity，包名：$targetApp")
    }
}

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
        LogFileManager.writeLog("拉起主界面失败（蒙层仍可能已通过悬浮窗显示）: ${e.message}")
    }
}
