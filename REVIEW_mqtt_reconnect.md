# 被控端未提交修改审查：MQTT 重连链路重构

- **审查对象**：`KeepAliveReceiver.kt` + `MqttAgentService.kt` 的未提交 diff（54+/31-）
- **审查时间**：2026-08-14
- **Paho 版本**：`org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5`
- **结论**：方向正确（用 Paho `reconnect()` 替代裸 `connect()`，避免饿死自动重连循环）；发现 **1 个重要行为回归（P1）+ 1 个并发竞态（P2）+ 2 个低风险点（P2/P3）**，均非崩溃级，可修可不修，但建议按文末清单处理后再提交。

---

## 一、改动概述

| 文件 | 改动 |
|---|---|
| `KeepAliveReceiver.kt` | `ensureServicesAlive()` 中新增 `MqttAgentService.triggerReconnectIfNeeded()`：服务在跑但 MQTT 断开时，由复活/预热/到点闹钟补一次进程内重连 |
| `MqttAgentService.kt` | ① 新增 `triggerReconnectIfNeeded()`；② 新增 `reconnectJob` + `launchReconnectOnce()` 统一去重入口；③ `reconnect()` 由阻塞 `connect(connectOptions)` 改为非阻塞 `mqttClient?.reconnect()`；④ 断线 3s 延迟、网络恢复、手动按钮三处触发源全部收敛到 `launchReconnectOnce()` |

设计意图（代码注释已自证）：`MqttAsyncClient.reconnect()` = `stopReconnectCycle() + attemptReconnect()`，与 Paho 自动重连是"合作式"关系；成功走 `connectComplete(reconnect=true)` 统一订阅/上线/置位，失败由 Paho 内部 `MqttReconnectActionListener` 续排退避循环。

经核对 **Paho 1.2.5 源码行为与注释一致**：`reconnect()` 在 CONNECTING/DISCONNECTING/CLOSED 状态仅回调 token listener（此处为 null，静默），不抛异常；正常路径非阻塞立即返回；`attemptReconnect` 走 `connectBG` 成功后会触发 `MqttCallbackExtended.connectComplete(reconnect=true)`。链路成立。

---

## 二、发现的问题

### P1｜手动重连失败不再排 Android 复活闹钟兜底（行为回归）

**现状**：
- 旧 `reconnect()`：`connect()` 阻塞（最长 10s），失败抛异常 → `catch` → `onDisconnected() + scheduleResurrectWithBackoff()` → 排指数退避的 **Android 闹钟**（30s/30s/30s/120s/…/15min），闹钟在进程被杀后也能拉起服务。
- 新 `reconnect()`：`mqttClient?.reconnect()` **非阻塞立即返回，同步路径基本不抛异常**（Paho 1.2.5 对异常状态只回调 null listener），`catch` 分支近乎不可达。失败由 Paho 内部续排**进程内**循环，**不排 Android 闹钟**。

**结果**：`scheduleResurrectWithBackoff()` 现在只在 `connectionLost` 和首次 `connect()` 失败两条路径上执行；**`triggerReconnectIfNeeded` / 网络恢复 / 手动按钮三条路径的重连失败不再有任何闹钟兜底**。

**影响评估**：
- 进程存活时：Paho 循环 + 15min 心跳 + 网络回调三重兜底，恢复速度可接受。
- 极端场景：服务实例存活、但 Paho 循环停摆（正是注释里担心的"饿死"镜像场景——`stopReconnectCycle()` 后被抢占、listener 续排失败）→ 下一次兜底要等 15min 心跳，比原来的指数退避闹钟（最快 30s）慢得多；进程被杀时依赖 START_STICKY 重启后 initMqtt 的阻塞 connect 失败再排闹钟，兜底仍在但更间接。

**建议**：`launchReconnectOnce` 的 job 结束后检查 `!_connected`，补一次 `scheduleResurrectWithBackoff()`（放在 job 内 `reconnect()` 之后的 finally/尾部，天然被 `reconnectJob` 去重，不会与 connectionLost 重复排）。

### P2｜`launchReconnectOnce` 检查-赋值非原子（并发竞态）

```kotlin
if (reconnectJob?.isActive == true) return
reconnectJob = scope.launch { ... }
```

两个步骤间无同步。触发源分布在多个线程：`NetworkCallback.onAvailable`（系统线程）、`connectionLost` 3s 协程（IO）、`KeepAliveReceiver`（主线程）、手动按钮（主线程）。并发通过检查后可能创建两个 job → 两次 `reconnect()`。

**影响**：Paho 对 CONNECTING 状态静默处理，不会崩溃/不会饿死循环，但会产生一次无效连接尝试、状态归因混乱（后完成的 job 覆盖先完成的引用）。

**建议**：用 `synchronized(reconnectLock)` 或 `Mutex` 包裹检查+赋值（成本极低）。

### P2｜`triggerReconnectIfNeeded` / `launchReconnectOnce` 缺 MQTT 开关守卫

- `ensureServicesAlive()` 只挡了 `isPaused()`，**没查 `MQTT_ENABLED_KEY`**；`triggerReconnectIfNeeded` 和 `launchReconnectOnce` 也没查。
- 场景：用户刚关闭 MQTT 开关 → `stopService` 排队 → `onDestroy`（主线程异步执行）执行前存在一个短窗口，此时心跳/闹钟触发 `triggerReconnectIfNeeded` → `instance` 仍在、`_connected=false` → 发起一次**真实连接尝试**，短暂违背"关闭零耗电"承诺。窗口为毫秒~秒级，概率低、影响小。
- 附带：broker 未配置（`mqttClient == null`）时，每次心跳都会空转一次 `reconnect()` 无操作，浪费极小但可顺手挡掉。

**建议**：`launchReconnectOnce` 开头加两行守卫：
```kotlin
if (!SaveKeyValues.loadBoolean(Constant.MQTT_ENABLED_KEY, false)) return
if (mqttClient == null) return
```

### P3｜onDestroy 与 in-flight 重连的回调窗口（存量问题，改动使其概率略增）

`onDestroy` 中 `stateListener = null` 但未取消 Paho 的连接线程（独立线程不受 `scope.cancel()` 影响）。若重连恰好成功，`connectComplete` 回调仍会执行 `onConnected() → updateNotification()`，此时服务已销毁、前台通知已移除，`notify(1001, …)` 会**重新弹出一条普通通知残留**。

旧代码同样存在该窗口（阻塞 connect 线程与 disconnect 竞争），非本次引入；但重连触发源变多后概率略增。可选项：`onDestroy` 里置 `_connected = false` 后，在 `updateNotification()` 内对"服务已销毁"场景做保护，或重连前检查 `instance === this`。

---

## 三、确认无问题的点

- **连接成功链路完整**：`connectComplete(reconnect=true)` → `onConnected()`（重置 `resurrectAttempt`、`cancelResurrect()`）+ 订阅 cmd/pair + 发布 online，与既有 Paho 自动重连共用同一链路，已由线上功能验证过该回调。
- **无重连风暴**：`ensureServicesAlive` 8s 去抖、网络恢复 8s 去抖、`launchReconnectOnce` job 去重、复活/救援闹钟同 PendingIntent 替换，多层收敛。
- **暂停/关闭后台自启时的守卫齐全**：`scheduleResurrectWithBackoff` 内部检查 `isKeepAliveEnabled()`，`pauseAllServices` 会取消救援闹钟并清 `instance`。
- **首次连接从未成功的场景**：`conOptions` 已由 `initMqtt` 设置并保留，`reconnect()` 走 else 分支正常连接，能恢复到与首次 connect 相同的状态。
- **编译层面**：companion object 访问私有成员 `launchReconnectOnce`/`cancelResurrect` 合法（Kotlin 允许），无类型问题。
- **注释质量**：`reconnect()` 上方大段注释解释了"为何用 reconnect() 而非 connect()"，对防止后人改回 `connect()` 回归（2e138c2 教训）很有价值，建议保留。

---

## 四、建议处理清单（按优先级）

> **修复状态（2026-08-14）：1-4 全部已实施**，见 `MqttAgentService.kt` 未提交 diff。变更点：
> 1. P1：`launchReconnectOnce` 的 job `finally` 中，`!_connected && mqttClient?.isConnected != true` 时补 `scheduleResurrectWithBackoff()`（误排由 `onConnected` 的 `cancelResurrect` 收敛）。
> 2. P2：新增 `reconnectLock`，`reconnectJob` 检查+赋值用 `synchronized` 包裹。
> 3. P2：`launchReconnectOnce` 开头增加 `MQTT_ENABLED_KEY` 与 `mqttClient == null` 双重守卫。
> 4. P3：`connectionLost` / `connectComplete` 回调开头增加 `instance !== this@MqttAgentService` 拦截；`instance` 补 `@Volatile` 保证跨线程可见。

1. **【已修复】补闹钟兜底**：`launchReconnectOnce` 的 job 尾部，`reconnect()` 完成后若 `!_connected` 则 `scheduleResurrectWithBackoff()`（恢复"重连失败→指数退避闹钟"的旧行为）。
2. **【已修复】加锁去竞态**：`reconnectJob` 检查+赋值用 `synchronized`/`Mutex` 包裹。
3. **【已修复】补开关守卫**：`launchReconnectOnce` 开头检查 `MQTT_ENABLED_KEY` 与 `mqttClient != null`。
4. **【已修复】处理 onDestroy 回调窗口**：重连成功回调前校验 `instance === this`。

若时间紧，1 是唯一值得在提交前处理的行为回归；2/3 属健壮性优化，可随下一版。
