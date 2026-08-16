#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Gitee 发布脚本（GitHub 手动触发编译时同步到 Gitee 私密仓库）。

发布方式：git 单提交重建（每次发版直接丢弃历史）——
  clone 仓库（--depth 1）→ 替换 updates/dailyTask.apk + updates/v_task.dat →
  commit → force push（HEAD:master）。仓库历史恒为 1 个 commit，
  大小 = 当前快照，永不累积（Gitee 免费单仓 500MB 无忧，无需任何瘦身任务）。

职责：
  1. 创建/复用 Gitee 私密仓库 Release（tag + note，供网页查看历史版本）
  2. 生成版本元数据 JSON，用「XOR + Base64」简单加密成 .dat（App 内置同密钥解密）
  3. git 单提交推送明文 APK（updates/dailyTask.apk）+ .dat（updates/v_task.dat）；
     .dat 里 apk 字段 = 固定 raw URL，App 下载拼 access_token；明文 APK 直接安装

方案演进：release 附件上传极慢（14min 卡死）→ GitCode OBS 也慢（437s）→
仓库文件 contents API 快（8s）但 git 历史累积 → 最终 git 单提交重建（历史零累积）。

加解密约定（App 侧 UpdateChecker.kt 必须与此对称）：
  - .dat：Base64(XOR(json, key))，存储文本为 cipher
  - APK：明文直装，无加密

用法（CI 或本地）：
  python3 scripts/publish_gitee.py \
      --version-code 2608161439 --version-name abcdef1 \
      --apk app/build/outputs/apk/release/xxx.apk \
      --tag dailyTask-123 --note "更新说明" --force 0 \
      --owner yamleaf --repo <私密仓库> \
      --token <写权限令牌> --key <加密密钥>

环境变量替代：GITEE_OWNER / GITEE_REPO / GITEE_TOKEN / VERSION_KEY
"""
import argparse
import base64
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.parse
import urllib.request
import urllib.error

API_BASE = "https://gitee.com/api/v5/repos"


# ═══════════════════════ 通用 ═══════════════════════

def xor_encrypt(data: bytes, key: bytes) -> bytes:
    """逐字节 XOR：与 App 侧 Kotlin 解密一致"""
    return bytes(b ^ key[i % len(key)] for i, b in enumerate(data))


def md5_of(data: bytes) -> str:
    return hashlib.md5(data).hexdigest()


def gitee_json(method: str, url: str, body: dict | None = None, timeout: int = 30):
    """Gitee API（JSON 请求/响应），防御式解析：body 为空/非对象/null 一律归为 {}"""
    data = None
    headers = {"User-Agent": "Mozilla/5.0 (DailyTask-CI)"}
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json;charset=UTF-8"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8", "replace")
            try:
                parsed = json.loads(raw)
                return resp.status, parsed if isinstance(parsed, dict) else {}
            except Exception:
                # 非 JSON（如 null / 空串）：Gitee 对不存在的 tag 查 releases/tags 会返回 200 + null
                return resp.status, {}
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")
        try:
            parsed = json.loads(detail)
            return e.code, parsed if isinstance(parsed, dict) else {}
        except Exception:
            return e.code, {"message": detail[:300]}
    except urllib.error.URLError as e:
        return -1, {"message": f"网络错误: {e.reason}"}


def get_or_create_release(owner: str, repo: str, token: str, tag: str, name: str, body: str, ref: str = "master") -> int:
    """复用已存在 tag 的 release，否则创建；返回 release_id（带 tag 已存在兜底）。
    ref 为 release 关联的分支/commit：git 单提交方案下统一关联 master（仓库最新快照）"""
    tag_enc = urllib.parse.quote(tag, safe="")
    url = f"{API_BASE}/{owner}/{repo}/releases/tags/{tag_enc}?access_token={token}"
    status, data = gitee_json("GET", url)
    if status == 200 and data.get("id"):
        return int(data["id"])
    create_body = {
        "access_token": token,
        "tag_name": tag,
        "name": name,
        "body": body,
        "target_commitish": ref,
        "prerelease": True,
    }
    status, data = gitee_json("POST", f"{API_BASE}/{owner}/{repo}/releases", create_body)
    if status in (200, 201) and data.get("id"):
        return int(data["id"])
    if status == -1:
        raise RuntimeError(f"网络错误，创建 release 失败: {data}")
    list_status, list_data = gitee_json(
        "GET", f"{API_BASE}/{owner}/{repo}/releases?access_token={token}&per_page=100"
    )
    if isinstance(list_data, list):
        for r in list_data:
            if isinstance(r, dict) and r.get("tag_name") == tag and r.get("id"):
                return int(r["id"])
    raise RuntimeError(f"创建 release 失败({status}): {data}")


def run_git(args: list[str], cwd: str | None = None) -> None:
    """执行 git 命令，失败抛异常（含 stderr）"""
    r = subprocess.run(["git"] + args, cwd=cwd, capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(f"git {' '.join(args[:3])} 失败: {(r.stderr or r.stdout).strip()[:300]}")


def git_single_commit_push(owner: str, repo: str, token: str, apk_bytes: bytes,
                           dat_text: str, apk_path: str, dat_path: str, commit_msg: str) -> float:
    """git 单提交重建推送：clone → 替换 APK/.dat → commit → force push。
    仓库历史恒为 1 个 commit（大小=当前快照），每次发版直接丢弃历史。返回推送耗时（秒）。"""
    clone_url = f"https://{owner}:{token}@gitee.com/{owner}/{repo}.git"
    work_dir = tempfile.mkdtemp(prefix="dt_upd_")
    t0 = time.time()
    try:
        # 1) clone（--depth 1：只需最新快照；仓库本身单提交，实测 ~4s）
        run_git(["clone", "--depth", "1", clone_url, work_dir])
        # 2) 替换文件（目录可能不存在则创建）
        os.makedirs(os.path.join(work_dir, os.path.dirname(apk_path) or "."), exist_ok=True)
        os.makedirs(os.path.join(work_dir, os.path.dirname(dat_path) or "."), exist_ok=True)
        with open(os.path.join(work_dir, apk_path), "wb") as f:
            f.write(apk_bytes)
        with open(os.path.join(work_dir, dat_path), "w", encoding="utf-8") as f:
            f.write(dat_text)
        # 3) add + commit（身份内联，不依赖全局配置）
        run_git(["add", "-A"], cwd=work_dir)
        run_git(["-c", "user.name=yamleaf", "-c", "user.email=li00ya@163.com",
                 "commit", "-m", commit_msg], cwd=work_dir)
        # 4) force push：HEAD 直接覆盖远程 master（历史归零）
        run_git(["push", "-f", "origin", "HEAD:master"], cwd=work_dir)
        return time.time() - t0
    finally:
        shutil.rmtree(work_dir, ignore_errors=True)


# ═══════════════════════ 主流程 ═══════════════════════

def main() -> int:
    ap = argparse.ArgumentParser(description="Publish APK + version file to Gitee (git single-commit)")
    ap.add_argument("--version-code", required=True, help="versionCode（int）")
    ap.add_argument("--version-name", required=True, help="versionName")
    ap.add_argument("--apk", required=True, help="本地 release APK 路径")
    ap.add_argument("--tag", required=True, help="Gitee Release tag（如 dailyTask-123）")
    ap.add_argument("--note", default="常规更新", help="更新说明")
    ap.add_argument("--force", default="0", help="1/true=强制更新")
    ap.add_argument("--owner", default=os.environ.get("GITEE_OWNER", ""))
    ap.add_argument("--repo", default=os.environ.get("GITEE_REPO", ""))
    ap.add_argument("--token", default=os.environ.get("GITEE_TOKEN", ""))
    ap.add_argument("--key", default=os.environ.get("VERSION_KEY", ""))
    args = ap.parse_args()

    if not all([args.owner, args.repo, args.token, args.key]):
        print("错误：owner/repo/token/key 不能为空（参数或环境变量 GITEE_OWNER/GITEE_REPO/GITEE_TOKEN/VERSION_KEY）")
        return 1

    apk_bytes = open(args.apk, "rb").read()
    apk_md5 = md5_of(apk_bytes)

    # 1) 创建/复用 Gitee Release（记录版本信息与更新说明；git 单提交方案统一关联 master）
    release_id = get_or_create_release(
        args.owner, args.repo, args.token, args.tag,
        name=f"DailyTask v{args.version_name}",
        body=args.note,
        ref="master",
    )
    print(f"Release 已创建/复用（id={release_id}, tag={args.tag}）")

    # 2) 生成版本元数据并加密成 .dat（apk=固定 raw URL，App 拼 access_token 下载）
    apk_path = "updates/dailyTask.apk"
    meta = {
        "v": int(args.version_code),
        "vn": args.version_name,
        "apk": f"{API_BASE}/{args.owner}/{args.repo}/raw/{apk_path}?ref=master",
        "md5": apk_md5,
        "force": args.force.lower() in ("1", "true", "yes"),
        "note": args.note,
        "tag": args.tag,
    }
    plain = json.dumps(meta, ensure_ascii=False).encode("utf-8")
    cipher = base64.b64encode(xor_encrypt(plain, args.key.encode("utf-8"))).decode("ascii")

    # 3) git 单提交推送（APK + .dat 一起 force push，历史直接归零）
    push_time = git_single_commit_push(
        args.owner, args.repo, args.token, apk_bytes, cipher,
        apk_path, "updates/v_task.dat",
        f"chore: update v{meta['v']}",
    )
    print(f"git 单提交推送 : {push_time:.2f}s（{len(apk_bytes)} bytes APK + .dat，历史已归零）")

    print("=" * 56)
    print("发布成功（Gitee 私密仓库，git 单提交，历史零累积）")
    print(f"  versionCode : {meta['v']}")
    print(f"  versionName : {meta['vn']}")
    print(f"  Release tag : {args.tag}")
    print(f"  APK 推送    : {push_time:.2f}s（{apk_path}）")
    print(f"  APK MD5     : {apk_md5}")
    print(f"  force       : {meta['force']}")
    print(f"  .dat 拉取   : {API_BASE}/{args.owner}/{args.repo}/raw/updates/v_task.dat（App 拼 token）")
    print(f"  APK 下载    : {meta['apk']}（App 拼 access_token）")
    print("  说明        : %s" % meta["note"])
    print("=" * 56)
    return 0


if __name__ == "__main__":
    sys.exit(main())
