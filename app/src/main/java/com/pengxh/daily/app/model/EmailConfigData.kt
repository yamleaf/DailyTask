package com.pengxh.daily.app.model

/**
 * 配置导出/导入中的邮箱配置载体。
 *
 * 注意：早期版本用 kotlin.Triple<String,String,String> 承载，但 R8 混淆会把
 * kotlin.Triple 的 first/second/third 字段重命名（见 mapping.txt：first->g 等），
 * 导致 Gson 反序列化时无法回填，混淆版导入后邮箱配置（发件箱/收件箱/授权码）全部丢失。
 * 改用本独立类并显式 keep，字段名在混淆后保持稳定，且 JSON 结构（emailConfig:{first,second,third}）
 * 与旧版导出文件兼容。
 */
class EmailConfigData {
    var first: String? = null
    var second: String? = null
    var third: String? = null
}
