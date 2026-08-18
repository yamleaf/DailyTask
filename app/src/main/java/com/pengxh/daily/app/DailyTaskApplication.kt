package com.pengxh.daily.app

import android.util.Log

import android.app.Activity
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.os.Bundle
import android.os.Environment
import androidx.room.Room.databaseBuilder
import com.google.android.material.color.DynamicColors
import com.pengxh.daily.app.sqlite.DailyTaskDataBase
import com.pengxh.daily.app.utils.AppRuntimeConfig
import com.pengxh.daily.app.utils.ConfigStore
import com.pengxh.daily.app.utils.IdlePseudoMaskController
import com.pengxh.kt.lite.base.ForegroundIdleBridge
import com.yample.mqttprotocol.dialog.DialogIdleBridge
import com.pengxh.daily.app.utils.EmailManager
import com.pengxh.daily.app.utils.LogFileManager
import com.pengxh.daily.app.utils.MessageDispatcher
import com.pengxh.kt.lite.utils.SaveKeyValues
import com.yample.mqttprotocol.ThemeManager
import java.io.File
import java.io.IOException


/**
 * @author: Pengxh
 * @email: 290677893@qq.com
 * @date: 2019/12/25 13:19
 */
class DailyTaskApplication : Application() {

    companion object {
        /** v1→v2：daily_task_table 增加 name 列（任务名称/备注）。
         * 注意：实体字段为可空 String（无 @NonNull），迁移必须与之一致——用可空列、无默认值，
         * 否则 Room 校验（Expected notNull=false / Found notNull=true）失败导致启动闪退。 */
        private val MIGRATION_1_2 = androidx.room.migration.Migration(1, 2) { db ->
            db.execSQL("ALTER TABLE daily_task_table ADD COLUMN name TEXT")
        }

        private lateinit var application: DailyTaskApplication

        fun get(): DailyTaskApplication = application

        internal fun initApplication(app: DailyTaskApplication) {
            application = app
        }

        /**
         * 本应用是否有任意 Activity 正处于前台（resumed）。
         * 「强制伪息屏」用它来判定用户是否真的离开了 DailyTask——
         * 仅当本应用完全不在前台（用户在其它 App / 桌面）时才允许触发蒙层。
         * 避免仅以 MainActivity 的 onPause/onResume 判断，导致停留在设置页等其它
         * 本应用页面时也被误判为「离开本软件」而误触发回桌面+跳回。
         */
        @Volatile
        var isAppForeground = false
            private set

        /**
         * 本进程启动的墙钟时间戳（ms）。onCreate 写入；进程被杀重启后重新计算。
         * RemoteSnapshot.appRunningMinutes 据此计算「进程已运行时长」，供控制端展示。
         */
        @Volatile
        var processStartAtMs = 0L
    }

    lateinit var dataBase: DailyTaskDataBase

    override fun onCreate() {
        super.onCreate()
        // 主题开关：冷启动时恢复用户选择的 深色/浅色/跟随系统
        ThemeManager.apply(this)
        // C1：Android 12+ 启用 Material You 动态配色；低版本回退到 themes.xml 中的靛蓝主色
        DynamicColors.applyToActivitiesIfAvailable(this)
        initApplication(this)
        processStartAtMs = System.currentTimeMillis()
        SaveKeyValues.initialize(this)
        AppRuntimeConfig.refreshFromStore()
        // 前台「无操作」自动进入伪息屏：由基类（KotlinBaseActivity）经桥接器回调，
        // 统一驱动所有前台页面（任务页 / 远程页 / 设置页等），延迟复用「息屏分组」配置。
        ForegroundIdleBridge.onResume = { IdlePseudoMaskController.startIdleMask(it) }
        ForegroundIdleBridge.onPause = { IdlePseudoMaskController.stopIdleMask() }
        ForegroundIdleBridge.onUserInteraction = { IdlePseudoMaskController.notifyUserActivity(it) }
        // 弹窗是独立 Window，触摸不经过 Activity；经桥接器把弹窗内触摸重置到前台无操作计时，
        // 保证「弹窗内操作不盖屏、弹窗内无操作超时照常进伪息屏」。
        DialogIdleBridge.onInteraction = { IdlePseudoMaskController.notifyUserActivity(it) }
        MessageDispatcher.initialize(this)
        EmailManager.initialize(this)
        LogFileManager.initLogFile(this)

        val dir = File(this.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "")
        val file = File(dir.toString() + File.separator + "DailyTaskConfig.json")
        if (!file.exists()) {
            try {
                file.createNewFile()
            } catch (e: IOException) {
                Log.e(javaClass.simpleName, "创建配置文件失败", e)
            }
        }
        ConfigStore.init(file.absolutePath)

        dataBase = databaseBuilder(this, DailyTaskDataBase::class.java, "DailyTask.db")
            // v1→v2：任务表新增 name 列（多任务命名），用正式 Migration 保留既有任务数据
            .addMigrations(MIGRATION_1_2)
            .fallbackToDestructiveMigration(true)
            .build()

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                resumedActivities.add(activity.localClassName)
            }

            override fun onActivityPaused(activity: Activity) {
                resumedActivities.remove(activity.localClassName)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

            override fun onActivityStarted(activity: Activity) {
                startedActivityCount++
                // 计数从 0→1：本应用真正进入前台（首次启动或从后台返回）。
                // 切换页面（A→B）时计数会先 +1 再 -1，中间不会归零，
                // 因此不会误触发「离开本软件」→ 不会误启动后台倒计时/保亮（bug #1 根因）。
                if (startedActivityCount == 1) {
                    isAppForeground = true
                    IdlePseudoMaskController.onAppForegrounded(activity.applicationContext)
                }
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
                // 计数归零：本应用最后一个 Activity 已停止，用户真正离开了 DailyTask。
                if (startedActivityCount == 0) {
                    isAppForeground = false
                    IdlePseudoMaskController.onAppBackgrounded(activity.applicationContext)
                }
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {
                resumedActivities.remove(activity.localClassName)
            }
        })
    }

    /** 处于 resumed 状态的本应用 Activity 集合（兜底/调试用；前台判定以 startedActivityCount 为准） */
    private val resumedActivities = mutableSetOf<String>()

    /**
     * 前台 Activity 计数：onActivityStarted +1、onActivityStopped -1。
     * 仅当计数归零（最后一个 Activity 停止）才判定为「离开本软件」，
     * 用于驱动 IdlePseudoMaskController 的后台倒计时与透明保亮。
     * 切换页面（任务↔设置↔远程）不会让计数中间归零，从根本避免
     * 「停留在设置页被误判为离开本软件」(bug #1)。
     */
    private var startedActivityCount = 0
}
