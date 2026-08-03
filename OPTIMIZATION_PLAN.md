# DailyTask × DailyController 双端优化完整方案
### （走读审查 + UI 现代化合并版 · 2026-08-01）

> 状态：方案已定，**暂不实施**。待后续确认后再按「七、执行顺序」分阶段动手。
> 关联文档：`REVIEW.md`（功能线 Review 路线图）、`CHANGELOG.md`。

---

## 〇、总览

| 维度 | 被控端 DailyTask（`alpha`） | 控制端 DailyController（`master`） |
|---|---|---|
| 核心原则 | 关闭后零耗电、连接后耗电最小化 | 前台收发 MQTT、后台零连接/零网络/零计时 |
| 工作流 | A. 架构耗电修复 · C. UI 全量现代化 | B. 体验与耗电修复 |
| 已交付 | Thread 化 `initMqtt` 修 ANR、Paho proguard keep、`normalizeBroker` 双端对齐 | M3 主题/暗色、Room v2、release jks、GitHub Actions、README/License |

**本轮决策（已确认）**：① 被控端不引入深色模式；② 被控端全量 18 个 layout 现代化；③ 主色 = 靛蓝 `#4F6EFF` + Android 12+ DynamicColors，低版本回退靛蓝。

---

## 一、Part A — 被控端架构与耗电修复

### A1. `START_STICKY` 开关守卫（P0，违背「关闭零耗电」）
- **问题**：`MqttAgentService.onStartCommand`（`MqttAgentService.kt:669`）返回 `START_STICKY`。用户关闭 `MQTT_ENABLED=false` 后，若系统因内存回收重启进程/服务，`onStartCommand` 会被重新调用并绕过开关继续连接 → 承诺破防。
- **方案**：进入即校验开关与身份；不满足 → `stopSelf()` + `stopForeground(STOP_FOREGROUND_REMOVE)` + 返回 `START_NOT_STICKY`；仅开关开启时才返回 `START_STICKY`。同时 `RuntimeStateApplier`/绑定校验逻辑前移到开关守卫之前。
- **验证**：`adb shell am set-inactive` / `am force-stop` 后观察服务是否复活连接（logcat `onStartCommand` 标记）。

### A2. 复活闹钟退避（P1）
- **问题**：`scheduleResurrect()` 用 `setExactAndAllowWhileIdle`，连接失败时以 60s/30s 精确唤醒重试，无退避 → 失败态下持续耗电（违背最小化）。
- **方案**：指数退避 `2^n × 60s`（上限 15min）；仅「最近一次曾连接成功过」的短暂失联用快速重试（30s×3）；系统 `AlarmManager` 尽量用 `set`/`setAndAllowWhileIdle` 而非精确触发；连接成功后重置退避计数。

### A3. 快照 TTL 缓存（P1）
- **问题**：`RemoteSnapshot.kt` 构建快照时全量查 DB（15 天日历 + 任务 + 历史），控制端每 15s 拉取一次 → 反复空跑查询耗 CPU/IO。
- **方案**：内存 + 磁盘双级 TTL 缓存（如 30s）；`onSuccess` 回执时标记脏数据，仅任务/状态变更后失效重建；快照字段向后兼容（原字段名不变，仅增新字段，控制端低版本可忽略）。

### A4. `recentRids` 集合清理（P2）
- **问题**：`recentRids` 无界增长（防重放集合），长运行内存渐增。
- **方案**：定长环形（如最近 200 条）+ 过期时间（>24h 清理），改 `LinkedHashMap`/`ArrayDeque`。

### A5. 敏感信息加密（P2）
- **问题**：broker 密码 / sessionSecret 以 SharedPreferences 明文存储。
- **方案**：迁移至 lite 模块已有 `SecureStorage`（EncryptedSharedPreferences），写新读旧兼容，首启迁移后删除明文 key。

### A6. 已交付项（存档）
`initMqtt()` 移后台线程修复 ANR（catch 放宽为 `Exception`）、`proguard-rules.pro` 增 Paho keep、`normalizeBroker()` 补 `ssl://`（8883/8884/8886）。

---

## 二、Part B — 控制端体验与耗电修复

### B1. `DeviceControlActivity` 主线程 connect ANR（P0）
- **问题**：`onCreate` 主线程同步 `connect()`（10s 超时阻塞）→ ANR（与被控端此前同源，控制端未修）。
- **方案**：`connect()` 移 IO/后台线程 + 状态回调切回主线程刷新 UI；启动阶段显示「正在连接…」占位而非阻塞；与 A1 一样加连接态幂等保护。

### B2. 后台静默：`onStop/onPause` 断连（P0，后台零耗电）
- **问题**：控制端无 `onStop`/`onPause` 断连；退后台后 15s 周期刷新 + MQTT 长连接继续 → 额外耗电。
- **方案**：`onStop()` → `disconnect()` 并取消周期刷新/心跳计时；`onStart()` → 按需重连。**前置确认**：Paho `isAutomaticReconnect` 需在后台静默期置 `false`（待核对当前实现，若为 true 需改），保证 onStop 后无任何后台连接。所有定时任务（15s 刷新、16s 心跳）绑定 Activity 生命周期，`onStop` 全部取消。

### B3. Toast 轰炸与空跑治理（P1）
- **问题**：离线/未返回快照时 16s 一次 Toast 轰炸；快照刷新在无设备快照时仍空跑。
- **方案**：Toast 改为「首次 + 状态变化」各一次，重复事件只更新状态 UI 不再弹；快照刷新无设备配对/无快照时不发起请求；周期刷新与 MQTT 连接态联动（断连即暂停）。

### B4. 未配对 / 离线态界面（P1）
- **方案**：`MainActivity` 设备行三态圆点（配对在线 / 配对离线 / 未配对）补齐；`DeviceControlActivity` 顶部离线横幅（「设备离线，数据可能不是最新」+ 恢复在线自动隐藏）；未配对态引导到扫描/手动绑定页；断连时控制按钮禁用 + 置灰。

### B5. 功能丰富化（耗电≈0，纯 UI）
- 设备连接质量指示、最近指令回执结果展示、设备电池曲线复用被控端 `BatteryHistory` 快照字段——均为静态渲染，无新增后台任务。

---

## 三、Part C — 被控端 UI 全量现代化（18 个 layout）

### C1. 设计令牌层（S1）
- **colors.xml**：`theme_color #3399FF→#4F6EFF`；新增 `on_primary / primary_container / on_primary_container / surface / on_surface / surface_variant / on_surface_variant / outline / outline_variant / error / success / white_70`。映射：`#F1F1F1/#F5F5F5→surface_variant`、`#888/#ABABAB/#B0B0B0→on_surface_variant`、`#ECEEF2→outline_variant`、`#B3FFFFFF→white_70`、`red→error`、`ios_green→success`。
- **themes.xml**：补 M3 语义色 + `materialAlertDialogTheme` + `bottomSheetDialogTheme` + shapeAppearance 圆角体系（small4/medium8/large16/xl20/full28）+ `windowLightStatusBar`。
- **dimens.xml**：补 `dp_2/6/10/20/28`、`radius_*`、`sp_30/56`、`size_28/36/80`、`toolbarHeight`；`borderThickness 1px→1dp`。
- **styles.xml**：新增 `Widget.DailyTask.Card`（圆角 16/elevation 2/stroke 0）。
- **Application**：`DynamicColors.applyToActivitiesIfAvailable(this)` 一行。

### C2. 硬编码清扫（S2，grep 驱动）
`#ECEEF2`×27、`#F1F1F1/#F5F5F5`、`#888/#ABABAB/#B0B0B0`、`1px`、魔数尺寸 → 全量收编令牌/dimens；`bg_solid_layout_white_16` → MaterialCardView；`bg_button_*_24` 伪按钮 → MaterialButton。

### C3. 逐页改造（S3）
| 文件 | 要点 |
|---|---|
| `activity_settings.xml`（1042 行） | 分段 LinearLayout 伪卡片 → 单张 MaterialCardView(20dp)；1px `#ECEEF2`→1dp `outline_variant`；行水波 `selectableItemBackground`；图标底/tint 令牌化 |
| `activity_remote_control.xml`（821 行） | 12 处分隔线/track 令牌化；**新增状态 Hero 卡**（在线/离线点 + 最后心跳 + 「关闭零耗电」说明）；开关关闭相关行置灰+副文案 |
| `activity_main.xml` | `#B3FFFFFF→white_70`；`56sp→dims`；按钮色→success |
| `activity_task_config.xml` | 分隔线令牌化；红字→error；魔数收编 |
| `activity_message_channel.xml` | 1px→token；`80dp/36dp`→dims |
| `activity_command.xml` / `activity_question_and_answer.xml` | 列表项卡化（见 item） |
| `bottom_sheet_layout_select_time.xml` | 边距 dims 化 |
| `dialog_custom_app_manager.xml` | 伪按钮→真 MaterialButton；M3 对话框圆角 |
| `dialog_custom_app_item / dialog_app_picker / dialog_app_picker_item` | 杂色令牌化 |
| `item_daily_task_rv_l.xml` | `#F5F5F5→surface_variant`；`30sp→dims`；行间距 |
| `item_command_rv_l / item_q_a_rv_l / item_task_rv_g` | 卡化 + 令牌化；CardView→MaterialCardView |
| `item_app_rv_l.xml` | `size_28` |
| `window_floating.xml` | 悬浮窗保留 CardView，颜色令牌化 |

### C4. 功能丰富化（零耗电增量）
Hero 状态卡、开关关闭置灰、行水波、空态复用——全静态 UI，无新增定时器/轮询/网络。

---

## 四、边界（不触碰）

- 不改打卡 / 通知监听（NotificationMonitorService）/ 任务调度（TaskScheduler）/ `MqttAgentService` / `RuntimeStateApplier` / `RemoteSnapshot` / `Protocol` 的业务逻辑。
- 保留全部现有 view id；Kotlin 仅 Hero 卡相关 Activity 补少量赋值（状态源复用已存的 RuntimeStateApplier 快照，不加新状态源）。
- 不引入 values-night / 深色模式。
- 控制端后台静默期 Paho `isAutomaticReconnect` 置 false 需实施时先核对现网实现再落。

---

## 五、验证

1. `gradlew :app:assembleDebug`（双端）编译通过、无资源引用残留。
2. 安装 `emulator-5554`（`com.pengxh.daily.app` / `com.yample.daily.controller`）。
3. UI：`adb shell screencap -p` + `adb pull` 逐页截图核对 18 页 + 对话框；Hero 卡开关态切换验证。
4. 耗电：`dumpsys battery`/`dumpsys alarm` 对比开关前后精确闹钟数；被控端开关关闭后 `force-stop` + 重启进程观察服务不复活；控制端退后台 `logcat` 确认无连接/无轮询。
5. 回归冒烟：打卡、通知、任务调度、远程指令收发正常。

---

## 六、Git 提交策略

- **DailyTask** → 临时分支 `feat_remote_dev`（`alpha` 主分支不动），按 A→C 分阶段提交。
- **DailyController** → `master` 直接提交（沿用 `39c0034` 惯例）。
- 提交信息对齐仓库风格。

---

## 七、执行顺序

1. **Part A（被控端耗电修复）**：A1 开关守卫 → A2 退避 → A3 快照 TTL → A4/A5。
2. **Part B（控制端）**：B1 ANR → B2 onStop 静默断连（先核对 isAutomaticReconnect）→ B3/B4/B5。
3. **Part C（被控端 UI）**：C1 令牌层 → C2 清扫 → C3 逐页（settings → remote_control → 其余）→ C4。
4. 双端验证 + 冒烟 + 截图。

**剩余待办（实施时确认）**：控制端 Paho `isAutomaticReconnect` 当前值；Hero 卡文案与心跳来源字段；`SecureStorage` 迁移兼容细节。
