#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
GitCode 发布脚本（上传速度对比用，与 Gitee 发布并存，互不影响）。

与 Gitee 的差异：附件上传走「预签名 URL 直传华为云 OBS」——
GET /releases/{tag}/upload_url 拿预签名 URL，客户端 PUT 直传对象存储，
不经过平台网关转发（Gitee 是 POST 到网关再转发，慢）。

流程（每步计时，便于对比）：
  1. 创建/复用 release（POST /releases，tag=dailyTask-gc-{N}，关联默认分支）
  2. GET /releases/{tag}/upload_url?file_name=xxx → 预签名 URL + headers
  3. PUT 直传 APK 到 OBS（计时）→ 上传即完成，OBS 回调 GitCode 登记附件
  4. 生成 .dat（XOR 加密，与 Gitee 脚本同一套）→ contents API 推 updates/v_task.dat

用法：
  python3 scripts/publish_gitcode.py \
      --version-code 2608161439 --version-name abcdef1 \
      --apk app/build/outputs/apk/release/xxx.apk \
      --tag dailyTask-gc-123 --note "更新说明" --force 0 \
      --owner yamleaf --repo DailyTaskUpdate \
      --token <gitcode令牌> --key <加密密钥>

环境变量替代：GITCODE_OWNER / GITCODE_REPO / GITCODE_TOKEN / VERSION_KEY
"""
import argparse
import base64
import hashlib
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.parse
import urllib.request
import urllib.error

API_BASE = "https://api.gitcode.com/api/v5/repos"


# ═══════════════════════ 通用（与 publish_gitee.py 一致）═══════════════════════

def xor_encrypt(data: bytes, key: bytes) -> bytes:
    """逐字节 XOR：与 App 侧 Kotlin 解密一致"""
    return bytes(b ^ key[i % len(key)] for i, b in enumerate(data))


def md5_of(data: bytes) -> str:
    return hashlib.md5(data).hexdigest()


def gc_json(method: str, url: str, body: dict | None = None, form: dict | None = None,
            timeout: int = 30, token: str | None = None):
    """GitCode API（JSON 响应），防御式解析：响应非对象/null 一律归为 {}

    鉴权：统一注入 Authorization: Bearer 头（GitCode 官方首选；POST/PATCH/PUT
    不认 JSON body 里的 access_token，实测仅 Bearer 头能过 403）。
    编码：form 非空 → application/x-www-form-urlencoded（GitCode 创建 release /
    推送文件只认表单，不认 JSON body，否则 400 'body不能为空'）；否则 JSON。
    """
    data = None
    headers = {"User-Agent": "Mozilla/5.0 (DailyTask-CI)", "Accept": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if form is not None:
        data = urllib.parse.urlencode(form).encode("utf-8")
        headers["Content-Type"] = "application/x-www-form-urlencoded"
    elif body is not None:
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


# ═══════════════════════ Release 流程 ═══════════════════════

def get_or_create_release(owner: str, repo: str, token: str, tag: str, name: str, body: str) -> int:
    """复用已存在 tag 的 release，否则创建；返回 release（dict）"""
    tag_enc = urllib.parse.quote(tag, safe="")
    status, data = gc_json("GET", f"{API_BASE}/{owner}/{repo}/releases/tags/{tag_enc}?access_token={token}", token=token)
    if status == 200 and data.get("tag_name"):
        return data
    # GitCode 创建 release 只认表单（form），不认 JSON body（否则 400 'body不能为空'）
    create_form = {
        "tag_name": tag,
        "name": name,
        "body": body,
        "release_status": "pre",  # prerelease
    }
    status, data = gc_json("POST", f"{API_BASE}/{owner}/{repo}/releases", form=create_form, token=token)
    if status in (200, 201) and data.get("tag_name"):
        return data
    raise RuntimeError(f"创建 release 失败({status}): {data}")


def get_upload_url(owner: str, repo: str, tag: str, token: str, file_name: str):
    """获取附件上传预签名 URL + 必须携带的 headers"""
    tag_enc = urllib.parse.quote(tag, safe="")
    fn_enc = urllib.parse.quote(file_name, safe="")
    url = (f"{API_BASE}/{owner}/{repo}/releases/{tag_enc}/upload_url"
           f"?access_token={token}&file_name={fn_enc}")
    status, data = gc_json("GET", url, token=token)
    if status != 200 or not data.get("url"):
        raise RuntimeError(f"获取上传地址失败({status}): {data}")
    return data["url"], data.get("headers", {})


def upload_apk(owner: str, repo: str, tag: str, token: str, file_path: str, attach_name: str) -> float:
    """预签名 URL 直传 OBS；返回耗时秒数"""
    t0 = time.time()
    up_url, headers = get_upload_url(owner, repo, tag, token, attach_name)
    t1 = time.time()
    print(f"  获取预签名 URL: {t1 - t0:.2f}s")

    # curl PUT 直传（headers 原样带上；OBS 预签名 URL 有效期短，尽快上传）
    cmd = ["curl", "-sS", "-X", "PUT", up_url, "--max-time", "600",
           "-H", f"Content-Type: {headers.get('Content-Type', 'application/octet-stream')}"]
    for k, v in headers.items():
        if k.lower() == "content-type":
            continue
        cmd += ["-H", f"{k}: {v}"]
    cmd += ["--data-binary", f"@{file_path}"]
    r = subprocess.run(cmd, capture_output=True, text=True)
    t2 = time.time()
    if r.returncode != 0:
        raise RuntimeError(f"PUT 上传附件失败: {r.stderr or 'curl exit ' + str(r.returncode)}")
    size = os.path.getsize(file_path)
    speed = size / (t2 - t1) / 1024 / 1024 if (t2 - t1) > 0 else 0
    print(f"  PUT 直传 OBS: {t2 - t1:.2f}s（{size / 1024 / 1024:.1f}MB，{speed:.1f} MB/s）")
    return t2 - t0


def upload_data_file(owner: str, repo: str, path: str, content: str, token: str, message: str) -> None:
    """上传/更新仓库文件（contents API：GitCode 需表单编码 + Bearer 头）。

    注意：GitCode 的 contents PUT 要求 sha（不支无 sha 新建），故首次需仓库内
    已存在该文件；本函数对推送失败仅抛异常，由调用方 try/except 容错（不影响
    APK 上传速度对比）。
    """
    sha = None
    status, data = gc_json("GET", f"{API_BASE}/{owner}/{repo}/contents/{path}?access_token={token}", token=token)
    if status == 200 and data.get("sha"):
        sha = data["sha"]
    content_b64 = base64.b64encode(content.encode("utf-8")).decode("ascii")
    form = {
        "content": content_b64,
        "message": message,
        "branch": "master",
    }
    if sha:
        form["sha"] = sha
    status, data = gc_json("PUT", f"{API_BASE}/{owner}/{repo}/contents/{path}", form=form, token=token)
    if status not in (200, 201) or not data.get("content"):
        # 兼容：部分实现返回 commit 字段而非 content
        if status in (200, 201) and (data.get("commit") or data.get("content")):
            return
        raise RuntimeError(f"推送 {path} 失败({status}): {data}")


# ═══════════════════════ 主流程 ═══════════════════════

def main() -> int:
    ap = argparse.ArgumentParser(description="Publish APK + version file to GitCode Release (speed compare)")
    ap.add_argument("--version-code", required=True, help="versionCode（int）")
    ap.add_argument("--version-name", required=True, help="versionName")
    ap.add_argument("--apk", required=True, help="本地 release APK 路径")
    ap.add_argument("--tag", required=True, help="GitCode Release tag（如 dailyTask-gc-123）")
    ap.add_argument("--note", default="常规更新", help="更新说明")
    ap.add_argument("--force", default="0", help="1/true=强制更新")
    ap.add_argument("--owner", default=os.environ.get("GITCODE_OWNER", ""))
    ap.add_argument("--repo", default=os.environ.get("GITCODE_REPO", ""))
    ap.add_argument("--token", default=os.environ.get("GITCODE_TOKEN", ""))
    ap.add_argument("--key", default=os.environ.get("VERSION_KEY", ""))
    args = ap.parse_args()

    if not all([args.owner, args.repo, args.token, args.key]):
        print("错误：owner/repo/token/key 不能为空（参数或环境变量 GITCODE_OWNER/GITCODE_REPO/GITCODE_TOKEN/VERSION_KEY）")
        return 1
    if not shutil.which("curl"):
        print("错误：需要 curl 命令")
        return 1

    apk_bytes = open(args.apk, "rb").read()
    apk_md5 = md5_of(apk_bytes)
    ts = time.strftime("%H:%M:%S")
    print(f"[{ts}] ===== GitCode 发布开始（tag={args.tag}）=====")

    # 1) 创建/复用 release
    t0 = time.time()
    release = get_or_create_release(
        args.owner, args.repo, args.token, args.tag,
        name=f"DailyTask v{args.version_name}",
        body=args.note,
    )
    print(f"  创建/复用 release: {time.time() - t0:.2f}s")

    # 2) 预签名直传 APK（核心对比项）
    attach_name = f"dailyTask_{args.tag}.apk"
    total = upload_apk(args.owner, args.repo, args.tag, args.token, args.apk, attach_name)
    print(f"  APK 附件上传总耗时: {total:.2f}s")

    # 3) 生成 .dat 并推仓库（contents API）
    meta = {
        "v": int(args.version_code),
        "vn": args.version_name,
        "md5": apk_md5,
        "force": args.force.lower() in ("1", "true", "yes"),
        "note": args.note,
    }
    plain = json.dumps(meta, ensure_ascii=False).encode("utf-8")
    cipher = base64.b64encode(xor_encrypt(plain, args.key.encode("utf-8"))).decode("ascii")
    t0 = time.time()
    try:
        upload_data_file(
            args.owner, args.repo, "updates/v_task.dat", cipher, args.token,
            f"chore: update version file v{meta['v']}",
        )
        print(f"  .dat 推仓库: {time.time() - t0:.2f}s")
    except Exception as e:
        print(f"  ⚠ .dat 推送失败（不影响上传速度对比）: {e}")

    print("=" * 56)
    print("GitCode 发布完成（速度对比）")
    print(f"  versionCode : {meta['v']}")
    print(f"  versionName : {meta['vn']}")
    print(f"  Release tag : {args.tag}")
    print(f"  APK 上传    : {total:.2f}s（含取 URL + PUT）")
    print(f"  APK MD5     : {apk_md5}")
    print("=" * 56)
    return 0


if __name__ == "__main__":
    sys.exit(main())
