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
  GH_PROXIES           可选：GitHub 资产下载镜像候选（逗号分隔，默认 gh.ddlc.top,gh-proxy.com,
                       ghfast.top,mirror.ghproxy.com；兼容旧 GH_PROXY 单值；置空则直连）
  MY_GITEE_OWNER       可选：覆盖 Gitee 仓库 owner（默认 yamleaf；勿用 GITEE_ 前缀，系统保留）
  MY_GITEE_REPO        可选：覆盖 Gitee 仓库名（默认 DailyTaskUpdate）

国内镜像策略（统一走镜像源）：
  pip 依赖    → 清华 TUNA（流水线命令已指定 -i https://pypi.tuna.tsinghua.edu.cn/simple）
  GitHub 资产 → GH_PROXIES 多镜像候选逐个尝试（失败快速切换，直连兜底）

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
    print("错误：需要 requests（国内镜像安装：pip install -i https://pypi.tuna.tsinghua.edu.cn/simple requests）")
    sys.exit(1)
try:
    import py7zr
except ImportError:
    print("错误：需要 py7zr（国内镜像安装：pip install -i https://pypi.tuna.tsinghua.edu.cn/simple py7zr，用于解压加密 7z）")
    sys.exit(1)

# 关键：Gitee Go 管道下 stdout 默认块缓冲，print 不实时刷出会让人以为"卡死"。
# 强制行缓冲 + 统一日志函数（时间戳 + flush）。
try:
    sys.stdout.reconfigure(line_buffering=True)
except Exception:
    pass


def log(msg: str) -> None:
    """带时间戳的日志，flush 保证实时输出"""
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


def log_t(msg: str, t0: float) -> float:
    """带耗时日志：打印 msg（含距 t0 秒数），返回当前时刻作为新起点"""
    now = time.time()
    log(f"{msg}（耗时 {now - t0:.1f}s）")
    return now

GITHUB_API = "https://api.github.com"
GITEE_API = "https://gitee.com/api/v5/repos"

GH_REPO = os.environ.get("GH_REPO", "yamleaf/DailyTask")
# 注意：Gitee Go 保留 GITEE_ 前缀给系统变量注入，直接用 GITEE_OWNER/GITEE_REPO
# 会被系统同名变量覆盖（实测导致 API 404 Not Found Project）。
# 因此自定义环境变量一律用 MY_ 前缀；仅当需要覆盖默认仓库时配置。
GITEE_OWNER = os.environ.get("MY_GITEE_OWNER") or "yamleaf"
GITEE_REPO = os.environ.get("MY_GITEE_REPO") or "DailyTaskUpdate"
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
# 下载镜像：GitHub 资产 CDN（objects.githubusercontent.com）国内访问慢/超时。
# 第三方免费镜像不稳定（实测 gh-proxy.com 时通时超时），故用多候选逐个尝试：
#   2026-08-16 国内实测：gh.ddlc.top 首字节 1.3s ✅ / gh-proxy.com 2.7s ✅ /
#   ghfast.top、mirror.ghproxy.com、github.moeyy.xyz 超时 ❌
# GH_PROXIES 环境变量可覆盖（逗号分隔）；兼容旧 GH_PROXY 单值；置空则直连。
_DEFAULT_PROXIES = (
    "https://gh.ddlc.top/,"      # 实测最快
    "https://gh-proxy.com/,"     # 实测可用
    "https://ghfast.top/,"       # 兜底候选
    "https://mirror.ghproxy.com/,"  # 兜底候选
)
GH_PROXIES = [p for p in
              (os.environ.get("GH_PROXIES") or os.environ.get("GH_PROXY") or _DEFAULT_PROXIES)
              .split(",") if p.strip()]


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


def _stream_download(src: str, dest: str, total: int = 0) -> None:
    """单源流式下载：每 2MB 打进度；连接超时 10s、读超时 45s（快速失败切换下一源）"""
    t0 = time.time()
    with requests.get(src, headers=gh_headers(), stream=True, timeout=(10, 45)) as r:
        if r.status_code != 200:
            raise RuntimeError(f"HTTP {r.status_code}")
        got = 0
        last_report = 0
        with open(dest, "wb") as f:
            for chunk in r.iter_content(65536):
                f.write(chunk)
                got += len(chunk)
                if got - last_report >= 2 * 1024 * 1024:
                    last_report = got
                    pct = f" / {total // 1024}KB" if total else ""
                    log(f"  下载中… {got // 1024}KB{pct}（{got / 1024 / 1024 / max(time.time() - t0, 0.01):.1f}MB/s）")
        log(f"  下载完成：{got // 1024}KB，耗时 {time.time() - t0:.1f}s")


def download_asset(url: str, dest: str, total: int = 0) -> None:
    """下载资产：依次尝试多镜像候选（GH_PROXIES），全部失败后直连兜底"""
    candidates = [(f"镜像{i + 1} {p}", p.rstrip("/") + "/" + url) for i, p in enumerate(GH_PROXIES)]
    candidates.append(("直连", url))
    last_err = None
    for label, src in candidates:
        log(f"  下载源：{label}")
        try:
            _stream_download(src, dest, total)
            return
        except Exception as e:
            last_err = e
            log(f"  {label} 失败：{type(e).__name__}: {str(e)[:120]}，切换下一源")
            if os.path.exists(dest):
                try:
                    os.remove(dest)
                except Exception:
                    pass
    raise RuntimeError(f"所有下载源均失败：{last_err}")


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
    raise RuntimeError(f"创建 Gitee release 失败({r.status_code}): {r.text[:300]}（URL={GITEE_API}/{GITEE_OWNER}/{GITEE_REPO}/releases）")


def gitee_upload_attachment(release_id: int, apk_path: str) -> int:
    """multipart 上传 APK 到 Gitee release 附件，返回 file_id"""
    url = f"{GITEE_API}/{GITEE_OWNER}/{GITEE_REPO}/releases/{release_id}/attach_files"
    name = os.path.basename(apk_path)
    size_kb = os.path.getsize(apk_path) // 1024
    log(f"  开始上传附件 {name}（{size_kb}KB → Gitee release #{release_id}）")
    t0 = time.time()
    with open(apk_path, "rb") as f:
        r = requests.post(url, data={"access_token": GITEE_TOKEN},
                          files={"file": (name, f, "application/vnd.android.package-archive")},
                          timeout=600)
    d = r.json() if r.headers.get("content-type", "").startswith("application/json") else {}
    if r.status_code not in (200, 201) or not d.get("id"):
        raise RuntimeError(f"上传附件失败({r.status_code}): {r.text[:300]}")
    log(f"  附件上传完成：{size_kb}KB，耗时 {time.time() - t0:.1f}s")
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
                log(f"  已清理旧 release {rel.get('tag_name')}（创建于 {created}）")
                removed += 1
            else:
                log(f"  清理失败 {rel.get('tag_name')}（HTTP {dr.status_code}，Gitee 可能不支持删除，忽略）")
    log(f"清理完成：检查 {len(rels)} 个 release，删除 {removed} 个（阈值 {KEEP_DAYS} 天）")


def main() -> int:
    t_start = time.time()
    # 诊断：打印各候选 token 变量注入情况（值掩码，便于确认 Gitee Go 变量配置）
    for name in ("MY_GITEE_TOKEN", "PUBLISH_TOKEN", "API_TOKEN"):
        v = os.environ.get(name, "")
        log(f"[env] {name}: {('*' * min(len(v), 8)) + ('...' if v else '')} (len={len(v)})")
    # 诊断：打印实际使用的仓库与目标 URL（Gitee Go 可能注入 GITEE_* 系统变量）
    log(f"[env] 目标仓库 : {GITEE_OWNER}/{GITEE_REPO} → {GITEE_API}/{GITEE_OWNER}/{GITEE_REPO}")
    if not GITEE_TOKEN:
        log("错误：所有候选 token 变量均为空（MY_GITEE_TOKEN/PUBLISH_TOKEN/API_TOKEN）——Gitee Go 流水线→变量管理配置并保存")
        return 1
    if not ENC_PASS:
        log("错误：需要环境变量 APK_ENCRYPT_PASSWORD（7z 解压密码）")
        return 1

    log("=" * 56)
    log("Gitee Go 同步开始")
    log("=" * 56)
    t0 = time.time()

    # 1) 拉 GitHub 最新 release
    log("① 拉取 GitHub 最新 release…")
    rel = fetch_gh_latest_release()
    t0 = log_t("GitHub release 已获取", t0)
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
    log(f"GitHub release : {tag}（v{version_code} / {version_name}）")
    log(f"update note    : {note}")

    # 2) 找并下载资产（.7z 优先，兼容 .apk）
    asset = next((a for a in rel.get("assets", [])
                  if a.get("name", "").endswith((".7z", ".apk"))), None)
    if not asset:
        log(f"错误：GitHub release {tag} 无 .7z/.apk 资产")
        return 1
    work = "sync_work"
    os.makedirs(work, exist_ok=True)
    asset_path = os.path.join(work, asset["name"])
    log(f"② 下载资产    : {asset['name']}（{asset.get('size', 0) // 1024}KB）")
    download_asset(asset["browser_download_url"], asset_path, total=asset.get("size", 0))
    t0 = log_t("资产下载完成", t0)

    # 3) 解压（.7z 加密 → 明文 APK；.apk 直接用）
    apk_path = asset_path
    if asset["name"].endswith(".7z"):
        log(f"③ 解压 7z（AES-256 密码解压）…")
        with py7zr.SevenZipFile(asset_path, "r", password=ENC_PASS) as z:
            z.extractall(path=work)
        apks = glob.glob(os.path.join(work, "**", "*.apk"), recursive=True)
        if not apks:
            log("错误：解压后未找到 APK")
            return 1
        apk_path = apks[0]
        log(f"   7z 解压成功 : {os.path.basename(apk_path)}")
    t0 = log_t("解压完成", t0)

    apk_bytes = open(apk_path, "rb").read()
    apk_md5 = hashlib.md5(apk_bytes).hexdigest()
    log(f"APK           : {os.path.basename(apk_path)}（{len(apk_bytes)} bytes，md5={apk_md5[:8]}...）")

    # 4) 传 Gitee release
    log(f"④ 同步到 Gitee：创建/复用 release {tag}…")
    release_id = gitee_get_or_create_release(tag, f"DailyTask v{version_name}", note)
    t0 = log_t(f"   Gitee release 就绪（id={release_id}）", t0)
    file_id = gitee_upload_attachment(release_id, apk_path)
    t0 = log_t("附件上传完成", t0)
    dl_url = f"{GITEE_API}/{GITEE_OWNER}/{GITEE_REPO}/releases/{release_id}/attach_files/{file_id}/download"
    log(f"   下载端点    : {dl_url}")

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
    log("⑤ 推送版本文件 updates/v_task.dat…")
    gitee_push_file("updates/v_task.dat", cipher, f"chore: gitee go sync v{version_code}")
    t0 = log_t(f".dat 已推送     : updates/v_task.dat（v{version_code}）", t0)

    # 6) 清理旧 release
    log(f"⑥ 清理超过 {KEEP_DAYS} 天的旧 release…")
    cleanup_old_releases()

    log("=" * 56)
    log(f"同步完成（Gitee Go）总耗时 {time.time() - t_start:.1f}s")
    log(f"  versionCode : {version_code}")
    log(f"  versionName : {version_name}")
    log(f"  Release tag : {tag}")
    log(f"  APK MD5     : {apk_md5}")
    log(f"  note        : {note}")
    log("=" * 56)
    return 0


if __name__ == "__main__":
    sys.exit(main())
