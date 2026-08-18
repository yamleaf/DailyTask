package com.pengxh.daily.app.utils

import com.pengxh.kt.lite.utils.SaveKeyValues
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 运行时配置（省电 / 强制伪息屏等），可被 Service / Activity 订阅热更新。
 */
object AppRuntimeConfig {

    private val _powerSaveMode = MutableStateFlow(false)
    val powerSaveMode = _powerSaveMode.asStateFlow()

    private val _forcePseudoMask = MutableStateFlow(false)
    val forcePseudoMask = _forcePseudoMask.asStateFlow()

    private val _desktopPetEnabled = MutableStateFlow(Constant.DESKTOP_PET_ENABLED_DEFAULT)
    val desktopPetEnabled = _desktopPetEnabled.asStateFlow()

    private val _screenMode = MutableStateFlow(Constant.SCREEN_MODE_DEFAULT)
    val screenMode = _screenMode.asStateFlow()

    private val _keepAliveMode = MutableStateFlow(Constant.KEEPALIVE_MODE_DEFAULT)
    val keepAliveMode = _keepAliveMode.asStateFlow()

    fun isPowerSaveMode(): Boolean = _powerSaveMode.value

    fun isForcePseudoMask(): Boolean = _forcePseudoMask.value

    fun isDesktopPetEnabled(): Boolean = _desktopPetEnabled.value

    fun getScreenMode(): Int = _screenMode.value

    /** 息屏保活模式：0=auto（起步 ALARM，按掉线升级 ALARM→CPU）/ 1=固定 alarm / 2=固定 cpu */
    fun getKeepAliveMode(): Int = _keepAliveMode.value

    fun setPowerSaveMode(enabled: Boolean) {
        SaveKeyValues.saveBoolean(Constant.POWER_SAVE_MODE_KEY, enabled)
        _powerSaveMode.value = enabled
    }

    fun setForcePseudoMask(enabled: Boolean) {
        SaveKeyValues.saveBoolean(Constant.FORCE_PSEUDO_MASK_KEY, enabled)
        _forcePseudoMask.value = enabled
        if (!enabled) {
            IdlePseudoMaskController.onForcePseudoMaskDisabled()
        }
    }

    fun setDesktopPetEnabled(enabled: Boolean) {
        SaveKeyValues.saveBoolean(Constant.DESKTOP_PET_ENABLED_KEY, enabled)
        _desktopPetEnabled.value = enabled
    }

    fun setScreenMode(mode: Int) {
        val v = mode.coerceIn(Constant.SCREEN_MODE_PSEUDO, Constant.SCREEN_MODE_KEEP_ON)
        SaveKeyValues.saveInt(Constant.SCREEN_MODE_KEY, v)
        _screenMode.value = v
    }

    fun setKeepAliveMode(mode: Int) {
        val v = mode.coerceIn(Constant.KEEPALIVE_MODE_AUTO, Constant.KEEPALIVE_MODE_CPU)
        SaveKeyValues.saveInt(Constant.MQTT_KEEPALIVE_MODE_KEY, v)
        _keepAliveMode.value = v
    }

    /**
     * 伪息屏关闭时，前台是否应按「屏幕模式 0」做无操作超时盖蒙层。
     * 伪息屏开启时由既有前后台伪息屏逻辑接管，不走此判断。
     */
    fun shouldForegroundIdleMaskWhenPseudoOff(): Boolean =
        !isForcePseudoMask() && getScreenMode() == Constant.SCREEN_MODE_PSEUDO

    /** 伪息屏关闭时，前台是否应 KEEP_SCREEN_ON（模式 0/2）；模式 1 允许系统自然灭屏 */
    fun shouldKeepScreenOnWhenPseudoOff(): Boolean =
        !isForcePseudoMask() && getScreenMode() != Constant.SCREEN_MODE_OFF

    fun refreshFromStore() {
        _powerSaveMode.value =
            SaveKeyValues.loadBoolean(Constant.POWER_SAVE_MODE_KEY, false)
        _forcePseudoMask.value =
            SaveKeyValues.loadBoolean(Constant.FORCE_PSEUDO_MASK_KEY, false)
        _desktopPetEnabled.value = SaveKeyValues.loadBoolean(
            Constant.DESKTOP_PET_ENABLED_KEY,
            Constant.DESKTOP_PET_ENABLED_DEFAULT
        )
        _screenMode.value = SaveKeyValues.loadInt(
            Constant.SCREEN_MODE_KEY,
            Constant.SCREEN_MODE_DEFAULT
        ).coerceIn(Constant.SCREEN_MODE_PSEUDO, Constant.SCREEN_MODE_KEEP_ON)
        _keepAliveMode.value = SaveKeyValues.loadInt(
            Constant.MQTT_KEEPALIVE_MODE_KEY,
            Constant.KEEPALIVE_MODE_DEFAULT
        ).coerceIn(Constant.KEEPALIVE_MODE_AUTO, Constant.KEEPALIVE_MODE_CPU)
    }
}
