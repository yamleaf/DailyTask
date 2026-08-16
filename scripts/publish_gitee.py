#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Gitee 发布脚本（GitHub 手动触发编译时同步到 Gitee 私密仓库 Release）。

职责：
  1. 把 release APK（明文，不加密）上传到 Gitee 私密仓库的 Release 附件——
     私密仓库 + 只读令牌：仅持令牌者可下载，App 内置只读令牌拼 access_token 拉取
  2. 生成版本元数据 JSON，用「XOR + Base64」简单加密成 .dat（App 内置同密钥解密）
     并推到仓库 updates/v_task.dat（App 用 API v5 raw + access_token 拉取）
  3. .dat 里 apk 字段 = API 附件下载端点
     （/api/v5/repos/{owner}/{repo}/releases/{id}/attach_files/{file_id}/download），
     App 下载时拼 access_token；下载后为明文 APK，直接安装（MD5 校验可选）

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


def gitee_json(method: str, url: str, body: dict | None = None):
    """Gitee API（JSON 请求/响应），防御式解析：body 为空/非对象/null 一律归为 {}"""
    data = None
    headers = {"User-Agent": "Mozilla/5.0 (DailyTask-CI)"}
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json;charset=UTF-8"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
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


def upload_attachment(owner: str, repo: str, release_id: int, token: str, file_path: str, attach_name: str | None = None):
    """multipart 上传附件（curl），返回 (file_id, browser_download_url)。单次最长 10 分钟，失败直接报错不重试"""
    url = f"{API_BASE}/{owner}/{repo}/releases/{release_id}/attach_files"
    cmd = ["curl", "-sS", "-X", "POST", url,
           # 单次最大 10 分钟；失败即报错、不重试（Gitee 持续慢时重试无意义且拖时长）
           "--max-time", "600",
           "-F", f"access_token={token}",
           "-F", f"file=@{file_path};filename={attach_name}" if attach_name else f"file=@{file_path}"]
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(f"curl 上传附件失败（超时/网络，未重试）: {r.stderr or 'curl exit ' + str(r.returncode)}")
    try:
        resp = json.loads(r.stdout)
    except Exception:
        resp = {}
    file_id = resp.get("id")
    dl = resp.get("browser_download_url")
    if not dl:
        raise RuntimeError(f"上传附件响应缺少 browser_download_url: {r.stdout[:300]}")
    return file_id, dl


def get_or_create_release(owner: str, repo: str, token: str, tag: str, name: str, body: str, ref: str = "master") -> int:
    """复用已存在 tag 的 release，否则创建；返回 release_id（带 tag 已存在兜底）。
    ref 为 release 关联的 commit SHA（保证 release 指向本次构建的真实代码，而非镜像仓库默认分支的最新提交）"""
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
    # 创建失败（可能 tag 已被占用/GET 未识别）：从 releases 列表按 tag_name 捞
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


def upload_data_file(owner: str, repo: str, path: str, content: str, token: str, message: str) -> None:
    """上传/更新仓库文件（contents API：存在则 PUT 带 sha，否则 POST）"""
    sha = None
    status, data = gitee_json("GET", f"{API_BASE}/{owner}/{repo}/contents/{path}?access_token={token}")
    if status == 200 and data.get("sha"):
        sha = data["sha"]
    content_b64 = base64.b64encode(content.encode("utf-8")).decode("ascii")
    body = {
        "access_token": token,
        "content": content_b64,
        "message": message,
        "branch": "master",
    }
    method = "PUT" if sha else "POST"
    if sha:
        body["sha"] = sha
    status, data = gitee_json(method, f"{API_BASE}/{owner}/{repo}/contents/{path}", body)
    if status not in (200, 201):
        raise RuntimeError(f"上传 {path} 失败({status}): {data}")


# ═══════════════════════ 主流程 ═══════════════════════

def main() -> int:
    ap = argparse.ArgumentParser(description="Publish APK + version file to Gitee Release")
    ap.add_argument("--version-code", required=True, help="versionCode（int）")
    ap.add_argument("--version-name", required=True, help="versionName")
    ap.add_argument("--apk", required=True, help="本地 release APK 路径")
    ap.add_argument("--tag", required=True, help="Gitee Release tag（如 dailyTask-123）")
    ap.add_argument("--ref", default="master", help="release 关联的 commit SHA（保证指向本次构建代码）")
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
    if not shutil.which("curl"):
        print("错误：需要 curl 命令")
        return 1

    apk_bytes = open(args.apk, "rb").read()
    apk_md5 = md5_of(apk_bytes)

    # 0) 校验 release 关联的 commit 是否已同步到 Gitee（GitHub→Gitee 镜像可能滞后）。
    #    已同步 → 精确关联本次构建 SHA；未同步 → 退化为 Gitee 默认分支名，
    #    避免「创建标签失败」400（Gitee 无法在仓库不存在的 commit 上建 tag）
    ref = args.ref
    check_status, _ = gitee_json(
        "GET", f"{API_BASE}/{args.owner}/{args.repo}/commits/{ref}?access_token={args.token}"
    )
    if check_status != 200:
        repo_status, repo_info = gitee_json(
            "GET", f"{API_BASE}/{args.owner}/{args.repo}?access_token={args.token}"
        )
        fallback = repo_info.get("default_branch", "master") if repo_status == 200 else "master"
        print(f"警告：commit {ref[:8]} 尚未同步到 Gitee（镜像滞后），release 关联退化为分支 {fallback}")
        ref = fallback

    # 1) 创建/复用 Gitee Release 并上传明文 APK（私密仓库：附件仅持令牌者可下载）
    release_id = get_or_create_release(
        args.owner, args.repo, args.token, args.tag,
        name=f"DailyTask v{args.version_name}",
        body=args.note,
        ref=ref,
    )
    attach_name = f"dailyTask_{args.tag}.apk"
    file_id, _browser_url = upload_attachment(
        args.owner, args.repo, release_id, args.token, args.apk, attach_name=attach_name
    )
    # App 侧下载 URL：API 附件下载端点（私密仓库需拼 access_token，App 内置令牌）
    dl_url = f"{API_BASE}/{args.owner}/{args.repo}/releases/{release_id}/attach_files/{file_id}/download"
    print(f"明文 APK 已上传 Release（id={release_id}）\n  附件: {attach_name}（{os.path.getsize(args.apk)} bytes）\n  API 下载: {dl_url}")

    # 2) 生成版本元数据并加密成 .dat（enc 字段省略=明文 APK，App 下载后直接安装）
    meta = {
        "v": int(args.version_code),
        "vn": args.version_name,
        "apk": dl_url,
        "md5": apk_md5,
        "force": args.force.lower() in ("1", "true", "yes"),
        "note": args.note,
    }
    plain = json.dumps(meta, ensure_ascii=False).encode("utf-8")
    cipher = base64.b64encode(xor_encrypt(plain, args.key.encode("utf-8"))).decode("ascii")

    # 3) 推 .dat 到仓库（App 用 API raw + token 拉取）
    upload_data_file(
        args.owner, args.repo, "updates/v_task.dat", cipher, args.token,
        f"chore: update version file v{meta['v']}",
    )

    print("=" * 56)
    print("发布成功（Gitee 私密仓库）")
    print(f"  versionCode : {meta['v']}")
    print(f"  versionName : {meta['vn']}")
    print(f"  Release tag : {args.tag}")
    print(f"  APK MD5     : {apk_md5}")
    print(f"  force       : {meta['force']}")
    print(f"  .dat 拉取   : {API_BASE}/{args.owner}/{args.repo}/raw/updates/v_task.dat（App 拼 token）")
    print("  说明        : %s" % meta["note"])
    print("=" * 56)
    return 0


if __name__ == "__main__":
    sys.exit(main())
