package com.pengxh.daily.app.utils

import android.os.Build

/**
 * 国产 ROM / 厂商识别：
 * 不同厂商对「后台弹出界面」「自启动」「电池白名单」权限的实现差异很大，
 * 自检与引导时需按厂商分派检测/跳转逻辑。
 */
object RomDetector {

    private fun manufacturerContains(vararg keywords: String): Boolean {
        val m = Build.MANUFACTURER.lowercase()
        val b = Build.BRAND.lowercase()
        return keywords.any { m.contains(it) || b.contains(it) }
    }

    /** 小米 / 红米 / POCO / 黑鲨（MIUI / HyperOS） */
    fun isMiui(): Boolean =
        manufacturerContains("xiaomi", "redmi", "poco", "blackshark")

    /** 华为（EMUI / HarmonyOS） */
    fun isHuawei(): Boolean =
        manufacturerContains("huawei", "honor") && !isHonor()

    /** 荣耀（MagicOS，华为系） */
    fun isHonor(): Boolean =
        manufacturerContains("honor")

    /** OPPO / 一加 / 真我（ColorOS） */
    fun isOppo(): Boolean =
        manufacturerContains("oppo", "oneplus", "realme")

    /** vivo / iQOO（FuntouchOS / OriginOS） */
    fun isVivo(): Boolean =
        manufacturerContains("vivo", "iqoo")

    /** 厂商展示名 */
    fun displayName(): String = when {
        isHonor() -> "荣耀 MagicOS"
        isHuawei() -> "华为 EMUI/HarmonyOS"
        isOppo() -> "OPPO/一加 ColorOS"
        isVivo() -> "vivo OriginOS"
        isMiui() -> "小米 MIUI/HyperOS"
        else -> "原生/其他 Android"
    }
}
