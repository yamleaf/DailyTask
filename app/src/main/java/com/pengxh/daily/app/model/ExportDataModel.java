package com.pengxh.daily.app.model;

import com.pengxh.daily.app.sqlite.bean.DailyTaskBean;

import java.util.List;

/**
 * 导出数据模型
 */
public class ExportDataModel {
    private int resetTime; // 重置时间
    private int overtime; // 超时时间
    private int timeRange; // 时间范围
    private int msgChannel; // 消息渠道
    private int targetApp; // 目标应用(0-3 内置; 100 自定义)
    private String targetAppPackage; // 目标应用实际包名(自定义目标时用于还原)

    private String remoteCommand; // 口令
    private String msgTitle; // 打卡消息标题
    private String wxKey; // 企业微信消息Key
    private String customWorkdays; // 自定义工作日

    private boolean detectGesture; // 检测手势
    private boolean backToHome; // 返回桌面
    private boolean autoRecycle; // 任务每日自动循环
    private boolean randomTime; // 随机时间
    private boolean skipHoliday; // 跳过节假日
    private boolean savePower; // 省电模式

    private EmailConfigData emailConfig; // 邮箱配置<发件箱、授权码、收件箱>
    /** 加密（可逆）的邮箱授权码：导出时以 AES 加密写入文件，导入时解密后自动填充，
     *  避免在配置文件中以明文/脱敏形式泄露授权码。密钥内置于应用，仅供配置随文件迁移使用。 */
    private String emailAuthEncrypted;
    private List<DailyTaskBean> tasks; // 任务列表

    // ── v2 扩展字段：包装类型，旧配置文件缺失时为 null，导入时跳过以保留本机值 ──
    private Integer resultSource; // 结果来源：0通知监听 1截屏反馈 2无障碍
    private Integer accessibilityFeedbackMode; // 无障碍反馈方式：0截屏 1文本
    private String punchResultKeywords; // 打卡结果关键词（空=默认）
    private Boolean notificationTransfer; // 通知转移开关
    private Integer screenMode; // 屏幕模式：0伪息屏 1息屏 2亮屏
    private Boolean keepAliveEnabled; // 息屏保活开关
    private Integer keepAliveMode; // 保活方式：0自动 1闹钟 2CPU
    private Boolean forcePseudoMask; // 伪息屏开关
    private Integer idlePseudoMaskTimeout; // 伪息屏无操作超时秒数
    private Boolean pseudoMaskNoClock; // 伪息屏隐藏时钟
    private Integer lowBatteryThreshold; // 低电量提醒阈值(%)
    private Boolean batterySmartAlertEnabled; // 电量智能提醒开关
    private Boolean desktopPetEnabled; // 桌宠开关
    private Boolean logEnabled; // 运行日志开关
    private Integer themeMode; // 主题外观：0跟随系统 1浅色 2深色

    // ── v3 扩展字段：远程页连接信息；密码/AppSecret 以 AES 加密写入文件 ──
    private String mqttBroker; // MQTT 服务器
    private String mqttUser; // 被控端用户名
    private String mqttPassEncrypted; // 被控端密码（AES 密文）
    private String deviceId; // 8 位设备 ID
    private String ctlUser; // 控制端用户名
    private String apiUrl; // Serverless API 地址
    private String apiAppId; // Serverless API AppID
    private String apiAppSecretEncrypted; // Serverless API AppSecret（AES 密文）

    public Integer getResultSource() {
        return resultSource;
    }

    public void setResultSource(Integer resultSource) {
        this.resultSource = resultSource;
    }

    public Integer getAccessibilityFeedbackMode() {
        return accessibilityFeedbackMode;
    }

    public void setAccessibilityFeedbackMode(Integer accessibilityFeedbackMode) {
        this.accessibilityFeedbackMode = accessibilityFeedbackMode;
    }

    public String getPunchResultKeywords() {
        return punchResultKeywords;
    }

    public void setPunchResultKeywords(String punchResultKeywords) {
        this.punchResultKeywords = punchResultKeywords;
    }

    public Boolean getNotificationTransfer() {
        return notificationTransfer;
    }

    public void setNotificationTransfer(Boolean notificationTransfer) {
        this.notificationTransfer = notificationTransfer;
    }

    public Integer getScreenMode() {
        return screenMode;
    }

    public void setScreenMode(Integer screenMode) {
        this.screenMode = screenMode;
    }

    public Boolean getKeepAliveEnabled() {
        return keepAliveEnabled;
    }

    public void setKeepAliveEnabled(Boolean keepAliveEnabled) {
        this.keepAliveEnabled = keepAliveEnabled;
    }

    public Integer getKeepAliveMode() {
        return keepAliveMode;
    }

    public void setKeepAliveMode(Integer keepAliveMode) {
        this.keepAliveMode = keepAliveMode;
    }

    public Boolean getForcePseudoMask() {
        return forcePseudoMask;
    }

    public void setForcePseudoMask(Boolean forcePseudoMask) {
        this.forcePseudoMask = forcePseudoMask;
    }

    public Integer getIdlePseudoMaskTimeout() {
        return idlePseudoMaskTimeout;
    }

    public void setIdlePseudoMaskTimeout(Integer idlePseudoMaskTimeout) {
        this.idlePseudoMaskTimeout = idlePseudoMaskTimeout;
    }

    public Boolean getPseudoMaskNoClock() {
        return pseudoMaskNoClock;
    }

    public void setPseudoMaskNoClock(Boolean pseudoMaskNoClock) {
        this.pseudoMaskNoClock = pseudoMaskNoClock;
    }

    public Integer getLowBatteryThreshold() {
        return lowBatteryThreshold;
    }

    public void setLowBatteryThreshold(Integer lowBatteryThreshold) {
        this.lowBatteryThreshold = lowBatteryThreshold;
    }

    public Boolean getBatterySmartAlertEnabled() {
        return batterySmartAlertEnabled;
    }

    public void setBatterySmartAlertEnabled(Boolean batterySmartAlertEnabled) {
        this.batterySmartAlertEnabled = batterySmartAlertEnabled;
    }

    public Boolean getDesktopPetEnabled() {
        return desktopPetEnabled;
    }

    public void setDesktopPetEnabled(Boolean desktopPetEnabled) {
        this.desktopPetEnabled = desktopPetEnabled;
    }

    public Boolean getLogEnabled() {
        return logEnabled;
    }

    public void setLogEnabled(Boolean logEnabled) {
        this.logEnabled = logEnabled;
    }

    public Integer getThemeMode() {
        return themeMode;
    }

    public void setThemeMode(Integer themeMode) {
        this.themeMode = themeMode;
    }

    public String getMqttBroker() {
        return mqttBroker;
    }

    public void setMqttBroker(String mqttBroker) {
        this.mqttBroker = mqttBroker;
    }

    public String getMqttUser() {
        return mqttUser;
    }

    public void setMqttUser(String mqttUser) {
        this.mqttUser = mqttUser;
    }

    public String getMqttPassEncrypted() {
        return mqttPassEncrypted;
    }

    public void setMqttPassEncrypted(String mqttPassEncrypted) {
        this.mqttPassEncrypted = mqttPassEncrypted;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getCtlUser() {
        return ctlUser;
    }

    public void setCtlUser(String ctlUser) {
        this.ctlUser = ctlUser;
    }

    public String getApiUrl() {
        return apiUrl;
    }

    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    public String getApiAppId() {
        return apiAppId;
    }

    public void setApiAppId(String apiAppId) {
        this.apiAppId = apiAppId;
    }

    public String getApiAppSecretEncrypted() {
        return apiAppSecretEncrypted;
    }

    public void setApiAppSecretEncrypted(String apiAppSecretEncrypted) {
        this.apiAppSecretEncrypted = apiAppSecretEncrypted;
    }

    public int getResetTime() {
        return resetTime;
    }

    public void setResetTime(int resetTime) {
        this.resetTime = resetTime;
    }

    public int getOvertime() {
        return overtime;
    }

    public void setOvertime(int overtime) {
        this.overtime = overtime;
    }

    public int getTimeRange() {
        return timeRange;
    }

    public void setTimeRange(int timeRange) {
        this.timeRange = timeRange;
    }

    public int getMsgChannel() {
        return msgChannel;
    }

    public int getTargetApp() {
        return targetApp;
    }

    public void setTargetApp(int targetApp) {
        this.targetApp = targetApp;
    }

    public void setMsgChannel(int msgChannel) {
        this.msgChannel = msgChannel;
    }

    public String getTargetAppPackage() {
        return targetAppPackage;
    }

    public void setTargetAppPackage(String targetAppPackage) {
        this.targetAppPackage = targetAppPackage;
    }

    public String getRemoteCommand() {
        return remoteCommand;
    }

    public void setRemoteCommand(String remoteCommand) {
        this.remoteCommand = remoteCommand;
    }

    public String getMsgTitle() {
        return msgTitle;
    }

    public void setMsgTitle(String msgTitle) {
        this.msgTitle = msgTitle;
    }

    public String getWxKey() {
        return wxKey;
    }

    public void setWxKey(String wxKey) {
        this.wxKey = wxKey;
    }

    public String getCustomWorkdays() {
        return customWorkdays;
    }

    public void setCustomWorkdays(String customWorkdays) {
        this.customWorkdays = customWorkdays;
    }


    public boolean isDetectGesture() {
        return detectGesture;
    }

    public void setDetectGesture(boolean detectGesture) {
        this.detectGesture = detectGesture;
    }

    public boolean isBackToHome() {
        return backToHome;
    }

    public void setBackToHome(boolean backToHome) {
        this.backToHome = backToHome;
    }

    public boolean isAutoRecycle() {
        return autoRecycle;
    }

    public void setAutoRecycle(boolean autoRecycle) {
        this.autoRecycle = autoRecycle;
    }

    public boolean isRandomTime() {
        return randomTime;
    }

    public void setRandomTime(boolean randomTime) {
        this.randomTime = randomTime;
    }

    public boolean isSkipHoliday() {
        return skipHoliday;
    }

    public void setSkipHoliday(boolean skipHoliday) {
        this.skipHoliday = skipHoliday;
    }

    public boolean isSavePower() {
        return savePower;
    }

    public void setSavePower(boolean savePower) {
        this.savePower = savePower;
    }

    public EmailConfigData getEmailConfig() {
        return emailConfig;
    }

    public void setEmailConfig(EmailConfigData emailConfig) {
        this.emailConfig = emailConfig;
    }

    public String getEmailAuthEncrypted() {
        return emailAuthEncrypted;
    }

    public void setEmailAuthEncrypted(String emailAuthEncrypted) {
        this.emailAuthEncrypted = emailAuthEncrypted;
    }

    public List<DailyTaskBean> getTasks() {
        return tasks;
    }

    public void setTasks(List<DailyTaskBean> tasks) {
        this.tasks = tasks;
    }
}
