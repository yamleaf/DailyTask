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
import com.pengxh.daily.app.utils.EmailManager
import com.pengxh.daily.app.utils.LogFileManager
import com.pengxh.daily.app.utils.MessageDispatcher
import com.pengxh.kt.lite.utils.SaveKeyValues
import java.io.File
import java.io.IOException


/**
 * @author: Pengxh
 * @email: 290677893@qq.com
 * @date: 2019/12/25 13:19
 */
class DailyTaskApplication : Application() {

    companion object {
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
    }

    lateinit var dataBase: DailyTaskDataBase

    override fun onCreate() {
        super.onCreate()
        // C1：Android 12+ 启用 Material You 动态配色；低版本回退到 themes.xml 中的靛蓝主色
        DynamicColors.applyToActivitiesIfAvailable(this)
        initApplication(this)
        SaveKeyValues.initialize(this)
        AppRuntimeConfig.refreshFromStore()
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
            .fallbackToDestructiveMigration(true)
            .build()

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                resumedActivities.add(activity.localClassName)
                isAppForeground = true
            }

            override fun onActivityPaused(activity: Activity) {
                resumedActivities.remove(activity.localClassName)
                // 仅当本应用没有任何 Activity 处于 resumed 时，才算真正离开前台。
                // 两个 Activity 切换（如 MainActivity → SettingsActivity）会先 pause 再 resume，
                // 此时集合仍非空，不会误判为「离开本软件」。
                if (resumedActivities.isEmpty()) {
                    isAppForeground = false
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {
                resumedActivities.remove(activity.localClassName)
            }
        })
    }

    /** 处于 resumed 状态的本应用 Activity 集合（用于判断应用整体是否在前台） */
    private val resumedActivities = mutableSetOf<String>()
}
