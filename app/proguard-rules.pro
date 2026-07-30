# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ============================================================================
# DailyTask 混淆（R8）保护规则
# ----------------------------------------------------------------------------
# 说明：当前 app/build.gradle 的 release 仍 minifyEnabled=false，本文件只是
# 「提前准备好」规则。启用混淆前，务必在真机做完整回归（见文件末尾清单）。
#
# 代码分析结果（已排查混淆敏感点）：
#   1) 反射：app 模块未使用 Class.forName / getMethod / getDeclaredMethod
#      （grep 命中的 .invoke() 均为 Kotlin 高阶函数调用，非 Java 反射），
#      故无反射导致的类名/方法名丢失风险。
#   2) 四大组件：AndroidManifest 注册的 Activity/Service/Receiver/Provider
#      由 AGP 自动生成 keep 规则；下面再显式加一层保险。
#   3) Room：@Entity / @Dao / RoomDatabase 由 androidx.room 的 consumer 规则
#      保护；下面再显式 keep 以防万一。
#   4) Gson：ExportDataModel / QuestionAnAnswerModel 按「类型」反序列化，
#      字段名即 JSON key，必须保留类名与全部字段；DailyTaskBean 既作 Room
#      实体也被 Gson 序列化导出，字段名需稳定。
#   5) @JavascriptInterface / Parcelable / Serializable：app 模块均未使用。
#   6) lite 模块：作为 library 通过 consumer proguard 规则（lite/proguard-rules.pro）
#      自行保护，启用 app 混淆时会自动应用，无需在此重复。
# ============================================================================

# ---- 1) 显式保留 Manifest 注册组件（AGP 已自动 keep，此处双保险） ----
-keep public class com.pengxh.daily.app.ui.MainActivity
-keep public class com.pengxh.daily.app.ui.SettingsActivity
-keep public class com.pengxh.daily.app.ui.MessageChannelActivity
-keep public class com.pengxh.daily.app.ui.TaskConfigActivity
-keep public class com.pengxh.daily.app.ui.CommandActivity
-keep public class com.pengxh.daily.app.ui.QuestionAndAnswerActivity
-keep public class com.pengxh.daily.app.service.FloatingWindowService
-keep public class com.pengxh.daily.app.service.ForegroundRunningService
-keep public class com.pengxh.daily.app.service.NotificationMonitorService
-keep public class com.pengxh.daily.app.service.CaptureImageService
-keep public class com.pengxh.daily.app.service.AutoProjectionAccessibilityService
-keep public class com.pengxh.daily.app.service.KeepAliveReceiver
-keep public class com.pengxh.daily.app.service.PackageReplacedReceiver

# ---- 2) Room：实体、DAO、数据库（保留类与成员，避免字段/方法被混淆或裁剪） ----
-keep class com.pengxh.daily.app.sqlite.bean.** { *; }
-keep class com.pengxh.daily.app.sqlite.dao.** { *; }
-keep class com.pengxh.daily.app.sqlite.DailyTaskDataBase { *; }

# ---- 3) Gson 按类型（含泛型）反序列化的模型：保留类名 + 全部字段（字段即 JSON key） ----
-keep class com.pengxh.daily.app.model.ExportDataModel { *; }
-keep class com.pengxh.daily.app.model.QuestionAnAnswerModel { *; }
# DailyTaskBean 兼作 Room 实体与 Gson 导出模型，字段名需稳定
-keep class com.pengxh.daily.app.sqlite.bean.DailyTaskBean { *; }

# ---- 4) 保留注解与泛型签名（Room / Gson 反射与 TypeToken 需要） ----
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses

# ============================================================================
# 启用 release 混淆前的真机回归清单：
#   [ ] 安装「升级前旧版」并写入邮箱授权码等加密数据 → 升级到混淆版后能正常读出
#       （security-crypto 加密格式需向后兼容，见 SecureStorage.kt 的降级逻辑）
#   [ ] 无障碍截屏打卡一次，确认 AutoProjectionAccessibilityService 正常
#   [ ] 通知监听/转移一次，确认 NotificationMonitorService 正常
#   [ ] 导入一次此前导出的任务配置（ExportDataModel / DailyTaskBean JSON），
#       确认 Gson 解析字段未因混淆错位
#   [ ] 启动/保活/开机自启/每日重置闹钟链路（KeepAliveReceiver 等）正常
#   [ ] 日志中有异常时能通过 Logcat 按类名检索到完整堆栈
# ============================================================================
