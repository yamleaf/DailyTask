package com.pengxh.daily.app.shizuku

import android.graphics.Rect

/**
 * uiautomator dump XML 解析（feat_shiziku）：
 * 仅保留「读取节点属性做文本采集」（CODE_CAPTURE 提取短信内容/收件人用），
 * **不提供任何文字→坐标查找**——点击定位统一走高级设置采集的坐标，杜绝 UI 文案与执行行为不一致。
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

    /**
     * 节点标签匹配：uiautomator 属性值里可能带 `>`（如 text="a > b"），
     * 不能用 `<node[^>]*>` 简单截断——这里按「空白 + 属性名="引号值"」成对消费直到标签收尾，
     * 属性值内的 `>` 被引号吸收，不会误截断。
     */
    private val NODE_REGEX = Regex("""<node\b(?:\s+[^\s=>/]+="[^"]*")*\s*/?>""")

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

    /** 取属性值并做 XML 实体解码（&amp;/&lt;/&gt;/&quot;/&apos;/&#10;） */
    private fun attr(tag: String, name: String): String? {
        // 热路径：一屏 dump 200~500 节点 × 每节点 6 次属性读取，若每次 new Regex 会重复编译上千次。
        // 预编译常量 + when 分发（行为与动态 \b$name="..." 完全一致）
        val m = when (name) {
            "text" -> RE_TEXT
            "resource-id" -> RE_RES_ID
            "class" -> RE_CLASS
            "clickable" -> RE_CLICKABLE
            "password" -> RE_PASSWORD
            "bounds" -> RE_BOUNDS_ATTR
            else -> Regex("""\b$name="([^"]*)"""")   // 兜底（当前调用方不会走到）
        }.find(tag) ?: return null
        return decode(m.groupValues[1]).ifBlank { null }
    }

    private val RE_TEXT = Regex("""\btext="([^"]*)"""")
    private val RE_RES_ID = Regex("""\bresource-id="([^"]*)"""")
    private val RE_CLASS = Regex("""\bclass="([^"]*)"""")
    private val RE_CLICKABLE = Regex("""\bclickable="([^"]*)"""")
    private val RE_PASSWORD = Regex("""\bpassword="([^"]*)"""")
    private val RE_BOUNDS_ATTR = Regex("""\bbounds="([^"]*)"""")
    private val RE_BOUNDS = Regex("""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""")

    private fun decode(s: String): String = s
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&#10;", "\n")
        .replace("&amp;", "&")

    private fun parseBounds(s: String?): Rect? {
        if (s.isNullOrBlank()) return null
        val m = RE_BOUNDS.find(s) ?: return null
        val (x1, y1, x2, y2) = m.destructured
        return Rect(x1.toInt(), y1.toInt(), x2.toInt(), y2.toInt())
    }
}
