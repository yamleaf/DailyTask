#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Gitee Go 同步脚本：拉 GitHub 最新 release → 解压 7z → 传 Gitee release → 生成 .dat → 清理旧 release

链路（Gitee Go 国内执行，避开 GitHub Actions 跨境上传慢）：
  GitHub Release（公开，加密 7z）--拉取解压--> Gitee release（私有，明文 APK）--App 下载-->
  .dat（版本信息，XOR 加密）推 Gitee 仓库 updates/v_task.dat，App 拉取比对

依赖环境变量（Gitee Go 流水线变量中配置）：
  MY_GITEE_TOKEN       必填：Gitee 写权限私人令牌（创建 release / 上传附件 / 推 .dat）；
                       Gitee Go 保留 GITEE_ 前缀给系统用，变量名需加 MY_ 前缀
  APK_ENCRYPT_PASSWORD 必填：7z 解压密码（与 GitHub Secret 一致）
  GITHUB_TOKEN         可选：GitHub API 额度提升
  VERSION_KEY          可选：.dat 加密密钥（默认 DailyTaskUpdateKey2026!，与 App 内置一致）
  KEEP_DAYS            可选：清理超过 N 天的旧 Gitee release（默认 180）

GitHub Release 约定（workflow build-develop.yml 生成）：
  tag = dt-{versionCode}；body 含 "- versionName: `xxx`" 和 "- update note: xxx"
"""
import base64
import glob
import hashlib
import json
import os
import re
import sys
import time
import urllib.parse

try:
    import requests
except ImportError:
    print("错误：需要 requests（pip install requests）")
    sys.exit(1)
try:
    import py7zr
except ImportError:
    print("错误：需要 py7zr（pip install py7zr，用于解压加密 7z）")
    sys.exit(1)

GITHUB_API = "https://api.github.com"
GITEE_API = "https://gitee.com/api/v5/repos"

GH_REPO = os.environ.get("GH_REPO", "yamleaf/DailyTask")
GITEE_OWNER = os.environ.get("GITEE_OWNER", "yamleaf")
GITEE_REPO = os.environ.get("GITEE_REPO", "DailyTaskUpdate")
# Gitee Go 限制变量名不能以 GITEE_ 开头；多候选名容错，任配一个即可
GITEE_TOKEN = (os.environ.get("MY_GITEE_TOKEN")
               or os.environ.get("PUBLISH_TOKEN")
               or os.environ.get("API_TOKEN")
               or "")
GH_TOKEN = os.environ.get("GITHUB_TOKEN", "")
ENC_PASS = os.environ.get("APK_ENCRYPT_PASSWORD", "")
VERSION_KEY = os.environ.get("VERSION_KEY", "DailyTaskUpdateKey2026!").encode("utf-8")
KEEP_DAYS = int(os.environ.get("KEEP_DAYS", "180"))
KEEP_LATEST = 5  # 无论如何保留最新 5 个 release（兜底）


def gh_headers():
    h = {"Accept": "application/vnd.github+json", "User-Agent": "DailyTask-GiteeGo"}
    if GH_TOKEN:
        h["Authorization"] = f"Bearer {GH_TOKEN}"
    return h


def xor_encrypt(data: bytes, key: bytes) -> bytes:
    return bytes(b ^ key[i % len(key)] for i, b in enumerate(data))


def fetch_gh_latest_release() -> dict:
    """GitHub 最新正式 release（releases/latest 只认非 draft 非 prerelease；若全是 prerelease 则列列表取最新非 draft）"""
    r = requests.get(f"{GITHUB_API}/repos/{GH_REPO}/releases/latest", headers=gh_headers(), timeout=60)
    if r.status_code == 200:
        return r.json()
    r = requests.get(f"{GITHUB_API}/repos/{GH_REPO}/releases?per_page=20", headers=gh_headers(), timeout=60)
    for rel in r.json():
        if not rel.get("draft") and rel.get("assets"):
            return rel
    raise RuntimeError(f"GitHub 无可用 release（latest HTTP {r.status_code}）")


def download_asset(url: str, dest: str) -> None:
    with requests.get(url, headers=gh_headers(), stream=True, timeout=300) as r:
        if r.status_code != 200:
            raise RuntimeError(f"下载资产失败 HTTP {r.status_code}")
        with open(dest, "wb") as f:
            for chunk in r.iter_content(65536):
                f.write(chunk)


def gitee_get_or_create_release(tag: str, name: str, body: str) -> int:
    url = f"{GITEE_API}/{GITEE_OWNER}/{GITEE_REPO}/releases/tags/{urllib.parse.quote(tag, safe='')}"
    r = requests.get(url, params={"access_token": GITEE_TOKEN}, timeout=60)
    if r.status_code == 200 and r.json().get("id"):
        return int(r.json()["id"])
    r = requests.post(f"{GITEE_API}/{GITEE_OWNER}/{GITEE_REPO}/releases", json={
        "access_token": GITEE_TOKEN, "tag_name": tag, "name": name, "body": body,
        "target_commitish": "master", "prerelease": True,
    }, timeout=60)
    if r.status_code in (200, 201) and r.json().get("id"):
        return int(r.json()["id"])
    # 兜底：从列表按 tag 捞
    r = requests.get(f"{GITEE_API}/{GITEE_OWNER}/{GITEE_REPO}/releases",
                     params={"access_token": GITEE_TOKEN, "per_page": 100}, timeout=60)
    for rel in r.json() if isinstance(r.json(), list) else []:
        if rel.get("tag_name") == tag and rel.get("id"):
            return int(rel["id"])
    raise RuntimeError(f"创建 Gitee release 失败({r.status_code}): {r.text[:300]}")


def gitee_upload_attachment(release_id: int, apk_path: str) -> int:
    """multipart 上传 APK 到 Gitee release 附件，返回 file_id"""
    url = f"{GITEE_API}/{GITEE_OWNER}/{GITEE_REPO}/releases/{release_id}/attach_files"
    name = os.path.basename(apk_path)
    with open(apk_path, "rb") as f:
        r = requests.post(url, data={"access_token": GITEE_TOKEN},
                          files={"file": (name, f, "application/vnd.android.package-archive")},
                          timeout=300)
    d = r.json() if r.headers.get("content-type", "").startswith("application/json") else {}
    if r.status_code not in (200, 201) or not d.get("id"):
        raise RuntimeError(f"上传附件失败({r.status_code}): {r.text[:300]}")
    return int(d["id"])


def gitee_push_file(path: str, content: str, message: str) -> None:
    """contents API 推/更文件（存在 PUT 带 sha，不存在 POST）"""
    url = f"{GITEE_API}/{GITEE_OWNER}/{GITEE_REPO}/contents/{path}"
    r = requests.get(url, params={"access_token": GITEE_TOKEN}, timeout=60)
    sha = r.json().get("sha") if r.status_code == 200 else None
    body = {"access_token": GITEE_TOKEN,
            "content": base64.b64encode(content.encode("utf-8")).decode("ascii"),
            "message": message, "branch": "master"}
    method = "PUT" if sha else "POST"
    if sha:
        body["sha"] = sha
    r = requests.request(method, url, json=body, timeout=120)
    if r.status_code not in (200, 201):
        raise RuntimeError(f"推送 {path} 失败({r.status_code}): {r.text[:300]}")


def cleanup_old_releases() -> None:
    """删除超过 KEEP_DAYS 天的旧 release（保留最新 KEEP_LATEST 个兜底），释放附件额度"""
    r = requests.get(f"{GITEE_API}/{GITEE_OWNER}/{GITEE_REPO}/releases",
                     params={"access_token": GITEE_TOKEN, "per_page": 100}, timeout=60)
    rels = r.json() if isinstance(r.json(), list) else []
    if len(rels) <= KEEP_LATEST:
        return
    cutoff = time.time() - KEEP_DAYS * 86400
    rels.sort(key=lambda x: x.get("created_at", ""))
    removed = 0
    for i, rel in enumerate(rels):
        if i >= len(rels) - KEEP_LATEST:
            break  # 保留最新 N 个
        created = rel.get("created_at", "")
        try:
            ts = time.mktime(time.strptime(created[:19], "%Y-%m-%dT%H:%M:%S"))
        except Exception:
            continue
        if ts < cutoff:
            rid = rel["id"]
            dr = requests.delete(f"{GITEE_API}/{GITEE_OWNER}/{GITEE_REPO}/releases/{rid}",
                                 params={"access_token": GITEE_TOKEN}, timeout=60)
            if dr.status_code in (200, 204):
                print(f"  已清理旧 release {rel.get('tag_name')}（创建于 {created}）")
                removed += 1
            else:
                print(f"  清理失败 {rel.get('tag_name')}（HTTP {dr.status_code}，Gitee 可能不支持删除，忽略）")
    print(f"清理完成：检查 {len(rels)} 个 release，删除 {removed} 个（阈值 {KEEP_DAYS} 天）")


def main() -> int:
    # 诊断：打印各候选 token 变量注入情况（值掩码，便于确认 Gitee Go 变量配置）
    for name in ("MY_GITEE_TOKEN", "PUBLISH_TOKEN", "API_TOKEN"):
        v = os.environ.get(name, "")
        print(f"  [env] {name}: {('*' * min(len(v), 8)) + ('...' if v else '')} (len={len(v)})")
    if not GITEE_TOKEN:
        print("错误：所有候选 token 变量均为空（MY_GITEE_TOKEN/PUBLISH_TOKEN/API_TOKEN）——Gitee Go 流水线→变量管理配置并保存")
        return 1
    if not ENC_PASS:
        print("错误：需要环境变量 APK_ENCRYPT_PASSWORD（7z 解压密码）")
        return 1

    print("=" * 56)
    print("Gitee Go 同步开始")
    print("=" * 56)

    # 1) 拉 GitHub 最新 release
    rel = fetch_gh_latest_release()
    tag = rel.get("tag_name", "")
    m = re.match(r"dt-(\d+)", tag)
    version_code = int(m.group(1)) if m else 0
    body = rel.get("body") or ""
    vm = re.search(r"versionName:\s*`?([^`\s]+)`?", body)
    version_name = vm.group(1) if vm else tag
    # note 可能跨多行：DOTALL 让 . 匹配换行；非贪婪到下一字段（\n- ）或 body 结尾（\Z），
    # 避免贪婪吞掉后续 commit 信息，也避免默认 . 不匹配换行导致截断为第一行
    nm = re.search(r"update note:\s*(.+?)(?=\n- |\Z)", body, re.DOTALL)
    note = nm.group(1).strip() if nm else "常规更新"
    print(f"GitHub release : {tag}（v{version_code} / {version_name}）")

    # 2) 找并下载资产（.7z 优先，兼容 .apk）
    asset = next((a for a in rel.get("assets", [])
                  if a.get("name", "").endswith((".7z", ".apk"))), None)
    if not asset:
        print(f"错误：GitHub release {tag} 无 .7z/.apk 资产")
        return 1
    work = "sync_work"
    os.makedirs(work, exist_ok=True)
    asset_path = os.path.join(work, asset["name"])
    print(f"下载资产      : {asset['name']}（{asset.get('size', 0) // 1024}KB）")
    download_asset(asset["browser_download_url"], asset_path)

    # 3) 解压（.7z 加密 → 明文 APK；.apk 直接用）
    apk_path = asset_path
    if asset["name"].endswith(".7z"):
        with py7zr.SevenZipFile(asset_path, "r", password=ENC_PASS) as z:
            z.extractall(path=work)
        apks = glob.glob(os.path.join(work, "**", "*.apk"), recursive=True)
        if not apks:
            print("错误：解压后未找到 APK")
            return 1
        apk_path = apks[0]
        print(f"7z 解压成功   : {os.path.basename(apk_path)}")

    apk_bytes = open(apk_path, "rb").read()
    apk_md5 = hashlib.md5(apk_bytes).hexdigest()
    print(f"APK           : {os.path.basename(apk_path)}（{len(apk_bytes)} bytes，md5={apk_md5[:8]}...）")

    # 4) 传 Gitee release
    release_id = gitee_get_or_create_release(tag, f"DailyTask v{version_name}", note)
    print(f"Gitee release  : {tag}（id={release_id}）")
    file_id = gitee_upload_attachment(release_id, apk_path)
    dl_url = f"{GITEE_API}/{GITEE_OWNER}/{GITEE_REPO}/releases/{release_id}/attach_files/{file_id}/download"
    print(f"APK 上传完成   : {dl_url}")

    # 5) 生成 .dat（App 用 API raw + token 拉取，apk 字段=附件下载端点拼 token）
    meta = {
        "v": version_code,
        "vn": version_name,
        "apk": dl_url,
        "md5": apk_md5,
        "force": False,
        "note": note,
        "tag": tag,
    }
    plain = json.dumps(meta, ensure_ascii=False).encode("utf-8")
    cipher = base64.b64encode(xor_encrypt(plain, VERSION_KEY)).decode("ascii")
    gitee_push_file("updates/v_task.dat", cipher, f"chore: gitee go sync v{version_code}")
    print(f".dat 已推送     : updates/v_task.dat（v{version_code}）")

    # 6) 清理旧 release
    cleanup_old_releases()

    print("=" * 56)
    print("同步完成（Gitee Go）")
    print(f"  versionCode : {version_code}")
    print(f"  versionName : {version_name}")
    print(f"  Release tag : {tag}")
    print(f"  APK MD5     : {apk_md5}")
    print(f"  note        : {note}")
    print("=" * 56)
    return 0


if __name__ == "__main__":
    sys.exit(main())
