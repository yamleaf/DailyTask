package com.pengxh.daily.app.shizuku

import android.graphics.Rect

/**
 * uiautomator dump XML 解析（feat_shiziku）：
 * 按 text / resource-id / class 定位节点 bounds，供 ShizukuShell.input tap 使用。
 * 纯函数、无状态，独立于现有无障碍代码。
 */
object UiNodeParser {

    data class Node(
        val text: String?,
        val resourceId: String?,
        val clazz: String?,
        val clickable: Boolean,
        val password: Boolean,
        val bounds: Rect?
    )

    private val NODE_REGEX = Regex("<node[^>]*>")

    fun parse(xml: String?): List<Node> {
        if (xml.isNullOrBlank()) return emptyList()
        return NODE_REGEX.findAll(xml).mapNotNull { m ->
            val tag = m.value
            Node(
                text = attr(tag, "text"),
                resourceId = attr(tag, "resource-id"),
                clazz = attr(tag, "class"),
                clickable = attr(tag, "clickable") == "true",
                password = attr(tag, "password") == "true",
                bounds = parseBounds(attr(tag, "bounds"))
            )
        }.toList()
    }

    private fun attr(tag: String, name: String): String? {
        val m = Regex("""$name="([^"]*)"""").find(tag) ?: return null
        return m.groupValues[1].ifBlank { null }
    }

    private fun parseBounds(s: String?): Rect? {
        if (s.isNullOrBlank()) return null
        val m = Regex("""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""").find(s) ?: return null
        val (x1, y1, x2, y2) = m.destructured
        return Rect(x1.toInt(), y1.toInt(), x2.toInt(), y2.toInt())
    }

    fun center(r: Rect?): Pair<Int, Int>? =
        r?.let { (it.left + it.right) / 2 to (it.top + it.bottom) / 2 }

    /** 按按钮文字查找：text 子串匹配 + 可点击或按钮类，返回第一个命中 bounds */
    fun findButtonByText(nodes: List<Node>, text: String): Rect? {
        if (text.isBlank()) return null
        return nodes.firstOrNull {
            it.text?.contains(text) == true &&
                (it.clickable || it.clazz?.contains("Button") == true || it.clazz?.contains("TextView") == true)
        }?.bounds
    }

    /** 页面上是否存在任一指定按钮文字（用于页面类型判定：登录页 / 验证页） */
    fun hasAnyButtonText(nodes: List<Node>, texts: List<String>): Boolean {
        if (texts.isEmpty()) return false
        return texts.any { findButtonByText(nodes, it) != null }
    }

    /** 找输入框：passwordOnly 时仅匹配密码框；否则密码框优先、退化到第一个 EditText */
    fun findEditText(nodes: List<Node>, passwordOnly: Boolean = false): Rect? {
        val passwordBox = nodes.firstOrNull { it.password && it.clazz?.contains("EditText") == true }?.bounds
        if (passwordOnly) return passwordBox
        return passwordBox ?: nodes.firstOrNull { it.clazz?.contains("EditText") == true }?.bounds
    }
}
