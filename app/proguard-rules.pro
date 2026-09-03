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
-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ============================================================================
# DailyTask 混淆（R8）保护规则
# ----------------------------------------------------------------------------
# 说明：release 已 minifyEnabled=true，本文件规则已生效。混淆后堆栈保留行号，
# 可用 build/outputs/mapping/release/mapping.txt 做 retrace 还原。启用后务必
# 在真机做完整回归（见文件末尾清单）。
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
# 远程控制 MQTT 代理服务 + 协议类（含 sealed PacketValue，混淆后需保留类名与字段，
# 否则 Gson 反序列化 MqttPacket 时无法还原 PacketValue 子类，导致远程指令静默失效）
-keep public class com.pengxh.daily.app.service.MqttAgentService
-keep class com.pengxh.daily.protocol.** { *; }

# ZXing core（二维码编解码）：保留全部类，避免 R8 裁剪 MultiFormatWriter / 编解码表
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# Paho MQTT client：内部通过反射（Class.forName）加载 SimpleLogger 等日志类，
# 混淆后会抛 MissingResourceException「Error locating the logging class」导致服务创建失败闪退
-keep class org.eclipse.paho.client.mqttv3.** { *; }
-dontwarn org.eclipse.paho.client.mqttv3.**

# reactor-blockhound：依赖（如 reactor-core）携带的 META-INF/services 引用 BlockHoundIntegration，
# 但该类不在运行时 classpath，R8 提示「Unexpected reference to missing service class」。无害，抑制即可。
-dontwarn reactor.blockhound.**
-dontwarn reactor.blockhound.integration.BlockHoundIntegration

# ---- 2) Room：实体、DAO、数据库（保留类与成员，避免字段/方法被混淆或裁剪） ----
-keep class com.pengxh.daily.app.sqlite.bean.** { *; }
-keep class com.pengxh.daily.app.sqlite.dao.** { *; }
-keep class com.pengxh.daily.app.sqlite.DailyTaskDataBase { *; }

# ---- 2.5) JNI native 方法：含 native 方法的类，其「类名」与「native 方法名」都必须
#           保留，否则 R8 重命名后，app 自带 .so 中的 JNI 符号
#           （Java_<原包名>_<原类名>_<原方法名>）无法匹配，运行期抛
#           UnsatisfiedLinkError 导致启动闪退。
#           例：com.pengxh.daily.app.utils.DailyTask.getWatermarkText() 被混淆成 r2.o.a()
#           即触发此问题。keepclasseswithmembers 同时保留类名与成员名。
#           注意：不能用 keepclasseswithmembernames（那只保留成员名，类名仍会被混淆）。 ----
-keepclasseswithmembers class com.pengxh.** {
    native <methods>;
}

# ---- 3) Gson 按类型（含泛型）反序列化的模型：保留类名 + 全部字段（字段即 JSON key） ----
-keep class com.pengxh.daily.app.model.ExportDataModel { *; }
-keep class com.pengxh.daily.app.model.QuestionAnAnswerModel { *; }
# EmailConfigData 承载邮箱配置（发件箱/授权码/收件箱），字段名即 JSON key，
# 必须保留；早期用 kotlin.Triple 导致混淆后 first/second/third 被改名、Gson 无法回填、
# 混淆版导入邮箱配置全部丢失（见 mapping.txt）。
-keep class com.pengxh.daily.app.model.EmailConfigData { *; }
# DailyTaskBean 兼作 Room 实体与 Gson 导出模型，字段名需稳定
-keep class com.pengxh.daily.app.sqlite.bean.DailyTaskBean { *; }

# ---- 3.5) JavaMail / activation（混淆版邮件发送必需）----
# SMTP/IMAP 等传输实现类（com.sun.mail.smtp.SMTPTransport / SMTPSSLTransport 等）
# 仅由 javax.mail 通过 META-INF/services + 反射发现，R8 树摇会将其当作“不可达”删除，
# 导致 Transport.send() 运行时抛出 NoSuchProviderException / 邮件发送失败。
# 本 app 用 smtp.qq.com:465（SSL）→ 依赖 SMTPSSLTransport，必须保留整个实现包。
# 同时保留 META-INF/services 资源（R8 默认不删，此处显式保留更稳）。
-keep class com.sun.mail.** { *; }
# SMTPTransport 的嵌套认证类（LOGIN/PLAIN 认证会实例化）。
# 注意：不能用 com.sun.mail.**$*（** 贪婪会吞掉 $，匹配失败），必须显式写出外层类名。
-keep class com.sun.mail.smtp.SMTPTransport$* { *; }
-keep class com.sun.mail.util.** { *; }
-keep class com.sun.activation.** { *; }
-keep class javax.mail.** { *; }
-keep class javax.activation.** { *; }
# 注：META-INF/services（JavaMail 的 Provider 发现文件）R8 默认不截断，无需额外 -keepresources。

# ---- 3.6) Shizuku（feat_shiziku）：保留 AIDL 接口与运行时类，防止公版混淆不可用 ----
# Shizuku 官方 api/aidl 库自带 consumer 规则为空（13.1.5），R8 会重命名
# IShizukuService/IRemoteProcess 等 AIDL 接口。虽然 Binder 事务靠「descriptor 字符串 +
# 事务码」匹配、类名改动理论仍可通信，但为兼容各 ROM 与混淆行为变化，显式 keep：
#   - moe.shizuku.server.**   AIDL 接口 + Stub/Proxy（asInterface 需稳定类名）
#   - rikka.shizuku.**        Shizuku 单例 / ShizukuRemoteProcess / Provider
#   - dev.rikka.shizuku.provider.**（ShizukuProvider，Manifest 引用，AGP 已 keep，双保险）
-keep class moe.shizuku.** { *; }
-keep class rikka.shizuku.** { *; }
-dontwarn moe.shizuku.**
-dontwarn rikka.shizuku.**

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
