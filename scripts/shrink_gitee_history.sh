#!/usr/bin/env bash
# -*- coding: utf-8 -*-
#
# Gitee 仓库历史瘦身（孤儿分支重建单快照 + force push）
#
# 用途：DailyTaskUpdate 这类「只托管更新包」的仓库，每次发版覆盖上传 APK 时
#       git 历史会累积旧 blob（约 6.6MB/版）。免费单仓 500MB ≈ 75 次发版后
#       接近上限。本脚本把历史压缩为单个快照，仓库回到 ~当前文件大小。
#
# 原理：git checkout --orphan 创建无父提交分支 → 保留当前工作区快照 →
#       删除旧分支 → 重命名 → force push 覆盖远程。历史全部丢弃，
#       当前文件内容不变，App 拉取 .dat / APK 完全无感。
#
# 用法：
#   GITEE_TOKEN=<写权限令牌> bash scripts/shrink_gitee_history.sh
#   bash scripts/shrink_gitee_history.sh --repo yamleaf/DailyTaskUpdate --branch master --user yamleaf --yes
#
# 安全提示：本操作 force push 覆盖远程分支，会永久丢弃该仓库全部 git 历史。
# 仅适用于「历史无保留价值」的更新包仓库；运行前请确认。
set -euo pipefail

REPO="yamleaf/DailyTaskUpdate"
BRANCH="master"
GIT_USER="yamleaf"
GIT_EMAIL="li00ya@163.com"
TOKEN="${GITEE_TOKEN:-}"
AUTO_YES=0
WORK_DIR=".shrink_history_tmp"

usage() {
  echo "用法: $0 [--repo owner/repo] [--branch master] [--user 用户名] [--token 令牌] [--yes]"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --repo)    REPO="$2";      shift 2 ;;
    --branch)  BRANCH="$2";    shift 2 ;;
    --user)    GIT_USER="$2";  shift 2 ;;
    --token)   TOKEN="$2";     shift 2 ;;
    --yes)     AUTO_YES=1;     shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "未知参数: $1"; usage; exit 1 ;;
  esac
done

if [[ -z "$TOKEN" ]]; then
  echo "错误：缺少写权限令牌（环境变量 GITEE_TOKEN 或 --token）" >&2
  exit 1
fi

echo "=== Gitee 历史瘦身：$REPO（branch=$BRANCH）==="
echo "⚠ 将 force push 覆盖远程分支，永久丢弃全部 git 历史（当前文件快照保留）"
if [[ "$AUTO_YES" -ne 1 ]]; then
  read -r -p "确认继续？输入 yes 继续: " ans
  [[ "$ans" == "yes" ]] || { echo "已取消"; exit 1; }
fi

# 1) 完整克隆（含历史；私有仓库用 token 认证：先试 oauth2 占位用户名，失败退回真实用户名）
rm -rf "$WORK_DIR"
echo "=== 克隆仓库 ==="
git clone "https://oauth2:${TOKEN}@gitee.com/${REPO}.git" "$WORK_DIR" 2>/dev/null \
  || git clone "https://${GIT_USER}:${TOKEN}@gitee.com/${REPO}.git" "$WORK_DIR"
cd "$WORK_DIR"

# 2) 确认远程目标分支存在
if ! git rev-parse --verify "origin/${BRANCH}" >/dev/null 2>&1; then
  echo "错误：远程分支 ${BRANCH} 不存在" >&2
  exit 1
fi

# 3) 切到目标分支内容（clone 默认分支可能不同）
git checkout -b "$BRANCH" "origin/${BRANCH}" 2>/dev/null || git checkout "$BRANCH"

# 4) 孤儿分支：无父提交，保留当前工作区快照
git checkout --orphan slim
git add -A
git -c user.name="$GIT_USER" -c user.email="$GIT_EMAIL" \
  commit -m "chore: slim history (single snapshot)"

# 5) 删除旧分支、重命名回目标分支名
git branch -D "$BRANCH"
git branch -m "$BRANCH"

# 6) force push（覆盖远程，历史归零）
echo "=== force push（覆盖远程 ${BRANCH}）==="
git push -f origin "$BRANCH"

# 7) 清理
cd ..
rm -rf "$WORK_DIR"
echo "=== 瘦身完成：仓库仅保留当前快照 ==="
