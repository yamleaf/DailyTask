package com.pengxh.daily.app.utils

import android.content.Context
import android.util.Log

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import com.pengxh.daily.app.model.ExportDataModel
import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.daily.app.sqlite.bean.DailyTaskBean
import com.pengxh.kt.lite.utils.SaveKeyValues
import com.yample.mqttprotocol.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TaskDataManager() {

    private val gson by lazy { Gson() }
    private val taskTimePattern by lazy {
        Regex("""^([01]\d|2[0-3]):[0-5]\d:[0-5]\d$""")
    }

    suspend fun importTasks(json: String, context: Context): ImportResult {
        return try {
            val type = object : TypeToken<ExportDataModel>() {}.type
            val config = gson.fromJson<ExportDataModel>(json, type)

            saveConfiguration(config, context)

            // 解密并自动填充邮箱授权码（导出时以 AES 加密写入文件，避免明文/脱敏泄露）
            val encryptedAuth = config.emailAuthEncrypted
            if (!encryptedAuth.isNullOrBlank()) {
                val decrypted = ConfigCipher.decrypt(encryptedAuth)
                if (decrypted.isNotBlank()) {
                    EmailSecureConfig.saveAuthCode(decrypted)
                }
            }

            val importedTasks = mutableListOf<DailyTaskBean>()
            for (task in config.tasks.orEmpty()) {
                val taskTime = task.time
                if (!isValidTaskTime(taskTime)) continue

                // 跳过已存在的任务时间点
                if (!DatabaseWrapper.isTaskTimeExist(taskTime)) {
                    task.id = 0
                    DatabaseWrapper.insert(task)
                    importedTasks.add(task)
                }
            }

            // 广播通知前台各页即时刷新（任务列表/设置/远程），并置位标记
            // 兜底：页面未注册接收器时由各自 onResume 消费一次
            ConfigImportSignal.notifyRemoteChanged(context)
            ImportResult.Success(importedTasks.size)
        } catch (e: JsonSyntaxException) {
            Log.e(javaClass.simpleName, "导入任务异常", e)
            ImportResult.Error("导入失败，请确认导入的是正确的任务数据")
        } catch (e: Exception) {
            Log.e(javaClass.simpleName, "导入任务异常", e)
            ImportResult.Error("导入失败：${e.message}")
        }
    }

    private suspend fun saveConfiguration(config: ExportDataModel, context: Context) {
        SaveKeyValues.saveInt(Constant.RESET_TIME_KEY, config.resetTime.coerceIn(0, 23))
        SaveKeyValues.saveInt(
            Constant.STAY_OVERTIME_KEY,
            config.overtime.takeIf { it > 0 } ?: Constant.DEFAULT_OVER_TIME
        )
        SaveKeyValues.saveInt(
            Constant.TIME_RANGE_KEY,
            config.timeRange.coerceAtLeast(Constant.DEFAULT_TIME_RANGE)
        )
        SaveKeyValues.saveInt(Constant.MSG_CHANNEL_KEY, config.msgChannel.coerceIn(0, 1))

        // 目标应用：优先还原自定义包名；否则按内置索引
        val customPkg = config.targetAppPackage
        val isBuiltIn = Constant.getBuiltInTargets().any { it.first == customPkg }
        if (!customPkg.isNullOrBlank() && !isBuiltIn) {
            val custom = Constant.getCustomTargetApps().toMutableList()
            if (!custom.contains(customPkg)) custom.add(customPkg)
            SaveKeyValues.saveString(Constant.CUSTOM_TARGET_APPS_KEY, custom.joinToString(","))
            SaveKeyValues.saveInt(Constant.TARGET_APP_KEY, Constant.CUSTOM_TARGET_INDEX)
            SaveKeyValues.saveString(Constant.CUSTOM_TARGET_SELECTED_KEY, customPkg)
        } else {
            SaveKeyValues.saveInt(Constant.TARGET_APP_KEY, config.targetApp.coerceIn(0, 3))
            SaveKeyValues.saveString(Constant.CUSTOM_TARGET_SELECTED_KEY, "")
        }

        SaveKeyValues.saveString(
            Constant.REMOTE_COMMAND_KEY,
            config.remoteCommand?.takeIf { it.isNotBlank() } ?: "打卡"
        )
        SaveKeyValues.saveString(
            Constant.MESSAGE_TITLE_KEY,
            config.msgTitle?.takeIf { it.isNotBlank() } ?: "打卡结果通知"
        )
        SaveKeyValues.saveString(Constant.WX_WEB_HOOK_KEY, config.wxKey ?: "")
        val workdays = config.customWorkdays
            ?.takeIf { it.isNotBlank() }
            ?.let {
                CustomWorkdayManager.serializeWorkdays(
                    CustomWorkdayManager.loadWorkdaysFromRaw(it)
                )
            }
            ?: CustomWorkdayManager.serializeWorkdays(
                CustomWorkdayManager.getOrderedDays().take(5).toSet()
            )
        SaveKeyValues.saveString(Constant.CUSTOM_WORKDAYS_KEY, workdays)

        SaveKeyValues.saveBoolean(Constant.GESTURE_DETECTOR_KEY, config.isDetectGesture)
        SaveKeyValues.saveBoolean(Constant.BACK_TO_HOME_KEY, config.isBackToHome)
        SaveKeyValues.saveBoolean(Constant.TASK_AUTO_RECYCLE_KEY, config.isAutoRecycle)
        SaveKeyValues.saveBoolean(Constant.RANDOM_TIME_KEY, config.isRandomTime)
        SaveKeyValues.saveBoolean(Constant.SKIP_HOLIDAY_KEY, config.isSkipHoliday)
        SaveKeyValues.saveBoolean(Constant.POWER_SAVE_MODE_KEY, config.isSavePower)

        // v2 扩展字段：旧配置文件缺失时为 null，跳过以保留本机值
        config.resultSource?.let {
            SaveKeyValues.saveInt(Constant.RESULT_SOURCE_KEY, it.coerceIn(0, 2))
        }
        config.accessibilityFeedbackMode?.let {
            SaveKeyValues.saveInt(Constant.ACCESSIBILITY_FEEDBACK_MODE_KEY, it.coerceIn(0, 1))
        }
        config.punchResultKeywords?.let {
            SaveKeyValues.saveString(Constant.PUNCH_RESULT_KEYWORDS_KEY, it)
        }
        config.notificationTransfer?.let {
            SaveKeyValues.saveBoolean(Constant.NOTIFICATION_TRANSFER_KEY, it)
        }
        config.screenMode?.let { AppRuntimeConfig.setScreenMode(it) }
        config.keepAliveEnabled?.let {
            SaveKeyValues.saveBoolean(Constant.KEEP_ALIVE_ENABLED_KEY, it)
        }
        config.keepAliveMode?.let { AppRuntimeConfig.setKeepAliveMode(it) }
        config.forcePseudoMask?.let { AppRuntimeConfig.setForcePseudoMask(it) }
        config.idlePseudoMaskTimeout?.let {
            SaveKeyValues.saveInt(
                Constant.IDLE_PSEUDO_MASK_TIMEOUT_KEY,
                it.coerceIn(10, 3600)
            )
        }
        config.pseudoMaskNoClock?.let {
            SaveKeyValues.saveBoolean(Constant.PSEUDO_MASK_NO_CLOCK_KEY, it)
        }
        config.lowBatteryThreshold?.let {
            SaveKeyValues.saveInt(
                Constant.LOW_BATTERY_THRESHOLD_KEY,
                it.coerceIn(10, 80)
            )
        }
        config.batterySmartAlertEnabled?.let {
            SaveKeyValues.saveBoolean(Constant.BATTERY_SMART_ALERT_ENABLED_KEY, it)
        }
        config.desktopPetEnabled?.let { AppRuntimeConfig.setDesktopPetEnabled(it) }
        config.logEnabled?.let {
            SaveKeyValues.saveBoolean(Constant.LOG_ENABLED_KEY, it)
        }
        config.themeMode?.let {
            // setMode 内部走 AppCompatDelegate.setDefaultNightMode → Activity.recreate，
            // 必须在主线程调用（导入流程运行在 IO 线程）
            withContext(Dispatchers.Main) {
                ThemeManager.setMode(context, it.coerceIn(0, 2))
            }
        }
        AppRuntimeConfig.refreshFromStore()

        // v3 扩展：远程页连接信息（非空才覆盖，避免旧文件空值清掉本机配置；
        // 密码/AppSecret 为 AES 密文，解密后写入 Keystore 加密存储）
        config.mqttBroker?.takeIf { it.isNotBlank() }?.let {
            SaveKeyValues.saveString(Constant.MQTT_BROKER_KEY, it)
        }
        config.mqttUser?.takeIf { it.isNotBlank() }?.let {
            SaveKeyValues.saveString(Constant.MQTT_USER_KEY, it)
        }
        config.mqttPassEncrypted?.takeIf { it.isNotBlank() }?.let {
            val pass = ConfigCipher.decrypt(it)
            if (pass.isNotBlank()) MqttSecureConfig.savePass(pass)
        }
        config.deviceId?.takeIf { it.isNotBlank() }?.let {
            SaveKeyValues.saveString(Constant.DEVICE_ID_KEY, it)
        }
        config.ctlUser?.takeIf { it.isNotBlank() }?.let {
            SaveKeyValues.saveString(Constant.MQTT_CTL_USER_KEY, it)
        }
        config.apiUrl?.takeIf { it.isNotBlank() }?.let {
            SaveKeyValues.saveString(Constant.MQTT_SERVERLESS_API_URL_KEY, it)
        }
        config.apiAppId?.takeIf { it.isNotBlank() }?.let {
            SaveKeyValues.saveString(Constant.MQTT_SERVERLESS_API_APP_ID_KEY, it)
        }
        config.apiAppSecretEncrypted?.takeIf { it.isNotBlank() }?.let {
            val secret = ConfigCipher.decrypt(it)
            if (secret.isNotBlank()) ServerlessApiSecureConfig.saveSecret(secret)
        }

        val email = config.emailConfig
        val outbox = email?.first
        val authCode = email?.second
        val inbox = email?.third
        if (email != null &&
            !outbox.isNullOrBlank() &&
            !inbox.isNullOrBlank()
        ) {
            val cacheObj = JsonObject().apply {
                addProperty("outbox", outbox)
                addProperty("inbox", inbox)
            }
            ConfigStore.get().save(Constant.EMAIL_CONFIG_KEY, cacheObj)
            // 仅当导入的授权码为真实值（非脱敏掩码）时写入加密存储，避免覆盖真实授权码
            if (!authCode.isNullOrBlank() && !EmailSecureConfig.isMasked(authCode)) {
                EmailSecureConfig.saveAuthCode(authCode)
            }
        }
    }

    private fun isValidTaskTime(time: String?): Boolean {
        return !time.isNullOrBlank() && taskTimePattern.matches(time)
    }

    sealed class ImportResult {
        /** 导入成功，count 为成功导入的任务数量 */
        data class Success(val count: Int) : ImportResult()

        /** 导入失败，message 为错误信息 */
        data class Error(val message: String) : ImportResult()
    }
}
