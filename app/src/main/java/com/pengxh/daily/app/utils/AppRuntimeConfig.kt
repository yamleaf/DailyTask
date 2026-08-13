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

    fun isPowerSaveMode(): Boolean = _powerSaveMode.value

    fun isForcePseudoMask(): Boolean = _forcePseudoMask.value

    fun isDesktopPetEnabled(): Boolean = _desktopPetEnabled.value

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

    fun refreshFromStore() {
        _powerSaveMode.value =
            SaveKeyValues.loadBoolean(Constant.POWER_SAVE_MODE_KEY, false)
        _forcePseudoMask.value =
            SaveKeyValues.loadBoolean(Constant.FORCE_PSEUDO_MASK_KEY, false)
        _desktopPetEnabled.value = SaveKeyValues.loadBoolean(
            Constant.DESKTOP_PET_ENABLED_KEY,
            Constant.DESKTOP_PET_ENABLED_DEFAULT
        )
    }
}
