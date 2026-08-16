#!/usr/bin/env bash
# -*- coding: utf-8 -*-
#
# 本地一键发版（避开 GitHub CI 跨境上传 Gitee 慢——CI 实测 >9min，本地 ~30s）
#
# 流程：自动找最新 release APK → aapt dump versionCode/versionName →
#       自动生成 tag（dailyTask-yyMMdd-HHmm）→ 调 publish_gitee.py
#       （git 单提交重建：clone → 替换 APK/.dat → force push，历史零累积）
#
# 用法：
#   GITEE_TOKEN=<写权限令牌> bash scripts/publish_local.sh --note "更新说明"
#   可选：--force 1（强制更新）、--tag 自定义、--key 加密密钥（默认与 App 内置一致）
#
# 令牌说明：GITEE_TOKEN 可用 App 内置令牌（UpdateChecker.kt 中 even/odd 交错
# 复原，实测有写权限）或专用写权限令牌；.dat 密钥默认 DailyTaskUpdateKey2026!
# （与 App 内置一致），如需改必须同步改 App。
set -euo pipefail

NOTE="常规更新"
FORCE="0"
TAG=""
TOKEN="${GITEE_TOKEN:-}"
KEY="${VERSION_KEY:-DailyTaskUpdateKey2026!}"
OWNER="yamleaf"
REPO="DailyTaskUpdate"

usage() {
  echo "用法: GITEE_TOKEN=<写权限令牌> bash scripts/publish_local.sh [--note 说明] [--force 1] [--tag 自定义] [--token 令牌] [--key 密钥]"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --note)   NOTE="$2";   shift 2 ;;
    --force)  FORCE="$2";  shift 2 ;;
    --tag)    TAG="$2";    shift 2 ;;
    --token)  TOKEN="$2";  shift 2 ;;
    --key)    KEY="$2";    shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "未知参数: $1"; usage; exit 1 ;;
  esac
done

[[ -n "$TOKEN" ]] || { echo "错误：需要 GITEE_TOKEN（环境变量或 --token）——可用 App 内置令牌（UpdateChecker.kt 复原）或专用写权限令牌"; exit 1; }

# 1) 最新 release APK
APK=$(ls -t app/build/outputs/apk/release/*.apk 2>/dev/null | head -1)
[[ -n "$APK" ]] || { echo "错误：未找到 release APK（先本地编译 assembleRelease）"; exit 1; }

# 2) 定位 aapt（Android SDK build-tools）
AAPT=""
for cand in "$ANDROID_HOME"/build-tools/*/aapt* "$ANDROID_SDK_ROOT"/build-tools/*/aapt* \
           /e/Ai_Tools/Android/Sdk/build-tools/*/aapt* /c/Ai_Tools/Android/Sdk/build-tools/*/aapt*; do
  [ -f "$cand" ] && AAPT="$cand" && break
done
[[ -n "$AAPT" ]] || { echo "错误：未找到 aapt（检查 ANDROID_HOME 或 SDK 路径）"; exit 1; }

# 3) dump 版本号
BADGING=$("$AAPT" dump badging "$APK")
VC=$(echo "$BADGING" | grep -oP "versionCode='\K[0-9]+" | head -1)
VN=$(echo "$BADGING" | grep -oP "versionName='\K[^']+" | head -1)
TAG="${TAG:-dailyTask-$(date +%y%m%d-%H%M)}"

echo "=== 本地发版 ==="
echo "  APK   : $APK"
echo "  版本  : $VC ($VN)"
echo "  tag   : $TAG"
echo "  note  : $NOTE"
echo "  force : $FORCE"
echo "================="

python3 scripts/publish_gitee.py \
  --version-code "$VC" \
  --version-name "$VN" \
  --apk "$APK" \
  --tag "$TAG" \
  --note "$NOTE" \
  --force "$FORCE" \
  --owner "$OWNER" \
  --repo "$REPO" \
  --token "$TOKEN" \
  --key "$KEY"
