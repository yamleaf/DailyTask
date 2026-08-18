# 检查更新（Gitee 公开镜像 + 加密公开）配置指南

App「设置 → 关于 → 检查更新」从 Gitee 公开镜像仓库拉取**加密**的版本文件，对比后下载**加密的 APK**，App 内置密钥解密后安装。
链路：`GitHub Actions 手动触发（Build Alpha）→ 编译 → AES-256 加密 APK 传 Gitee Release → 生成 XOR 加密 v_task.dat 推仓库 → App 拉取解密对比 → 下载密文 APK → AES 解密 → MD5 校验 → 安装`。

**公开但不可直接用**：`.dat` 是 Base64(XOR) 密文，APK 是 AES-256-CBC 密文（密钥内置 App），下载下来都是乱码，不能直接安装。

## 一次性配置（首次）

### 1. Gitee 侧（约 3 分钟）
1. 确认公开镜像仓库存在（如 `https://gitee.com/yamleaf/DailyTask`，即 GitHub DailyTask 的开源镜像）
2. 头像 → 设置 → 私人令牌 → 生成新令牌，勾选 `projects` 权限（需要**写权限**才能推 Release/文件），复制保存

### 2. 修改代码配置（2 处占位符）
1. `.github/workflows/build-develop.yml` 顶部 job env：
   ```yaml
   GITEE_OWNER: '你的Gitee用户名'   # 如 yamleaf
   GITEE_REPO: '你的版本仓库名'     # 如 DailyTask（公开镜像）
   ```
2. `app/.../utils/UpdateChecker.kt`：
   ```kotlin
   GITEE_OWNER = "你的Gitee用户名"
   GITEE_REPO = "你的版本仓库名"
   ```
   > App 侧**无需内置令牌**（公开仓库 raw/附件免认证）；密钥 `VERSION_KEY` 默认 `DailyTaskUpdateKey2026!`，如更换需同步改 App 内常量 + GitHub Secret。

### 3. GitHub Secrets（DailyTask 仓库）
Settings → Secrets and variables → Actions → New repository secret：
| Secret | 值 |
|---|---|
| `GITEE_TOKEN` | Gitee 私人令牌（写权限） |
| `VERSION_KEY` | 加密密钥（与 App 内置一致，默认 `DailyTaskUpdateKey2026!`） |

### 4. 验证令牌（可选）
```bash
curl -s "https://gitee.com/api/v5/user?access_token=你的令牌"
# 返回你的用户信息即有效
```

## 日常发版流程（每次发布）

1. 代码提交并推送
2. GitHub 仓库 → **Actions** → **Build Alpha** → **Run workflow**（手动触发）
3. 填写：`note`（更新说明，多行请用 `\n` 分隔，如 `修复A\n修复B`）、`force`（是否强制）
4. 该工作流会自动：编译 debug+release → 上传 GitHub 构建产物 → 7z 加密包推 GitHub Release（人工分发）→ **aapt 读版本号 → AES 加密 release APK → 传 Gitee Release → 生成 .dat → 推 Gitee 仓库**
5. 等待 5~15 分钟，检查：
   - Gitee 仓库 Release 里出现 `dailyTask_<tag>.apk.enc`（AES 密文）
   - Gitee 仓库 `updates/v_task.dat` 已更新
6. 真机验证：被控端设置 → 关于 → **检查更新** → 弹窗 → 下载 → 自动解密安装

> 注：alpha 构建的 versionCode 随构建时间单调递增，检查更新依赖它判断新旧；正式版号（`BUILD_FLAVOR=release`）不会走本发布路径。

## 本地跑发布脚本（调试用，不经 CI）

```bash
export GITEE_OWNER=用户名 GITEE_REPO=仓库名 GITEE_TOKEN=令牌 VERSION_KEY=密钥
python3 scripts/publish_gitee.py \
  --version-code 2608169999 --version-name local-test \
  --apk app/build/outputs/apk/release/xxx.apk \
  --tag dailyTask-local --note "本地测试" --force 0
```

## 常见问题

- **App 提示「检查更新失败」**：查 logcat `UpdateChecker` 日志；确认 `GITEE_OWNER/GITEE_REPO` 填对、`.dat` 在仓库 `updates/v_task.dat`
- **一直显示「已是最新版本」**：确认 CI 用 alpha 构建（versionCode 时间戳递增），新包 versionCode > 已装包
- **下载后无法安装**：先确认系统「安装未知应用」已对本 App 授权；再查 logcat 是否有「解密/校验失败」（密钥不一致或 .dat 的 md5 与包不符）
- **别人能下载我的 APK 吗**：能下载，但是 AES-256 密文（`*.apk.enc`），没有内置密钥无法使用；`.dat` 也是 XOR 密文
- **改密钥**：改 `UpdateChecker.kt` 的 `VERSION_KEY` + GitHub Secret `VERSION_KEY`，两端必须一致，否则解密失败
