#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Gitee 仓库文件（contents API）覆盖上传测速。

背景：Gitee release 附件上传（curl multipart）实测极慢（6.6MB 卡 14 分钟）。
本脚本测「直接把 APK 覆盖上传到仓库文件路径」（contents PUT）的速度，
用于评估是否可替代 release 附件、或是否需要维持手动上传方案。

用法（CI 或本地）：
  python3 scripts/test_gitee_upload_speed.py \
      --file app/build/outputs/apk/release/xxx.apk \
      --path updates/_speedtest.bin \
      --owner yamleaf --repo DailyTaskUpdate --token <写权限令牌>

环境变量替代：GITEE_OWNER / GITEE_REPO / GITEE_TOKEN
清理测试文件：
  python3 scripts/test_gitee_upload_speed.py --delete-only \
      --path updates/_speedtest.bin \
      --owner yamleaf --repo DailyTaskUpdate --token <写权限令牌>
"""
import argparse
import base64
import json
import os
import sys
import time
import urllib.parse
import urllib.request
import urllib.error

API_BASE = "https://gitee.com/api/v5/repos"


def gitee_json(method: str, url: str, body: dict | None = None, timeout: int = 600):
    """Gitee API（JSON 请求/响应），防御式解析"""
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


def delete_file(owner: str, repo: str, path: str, token: str) -> None:
    """删除仓库文件（contents API DELETE），需 sha"""
    path_enc = urllib.parse.quote(path, safe="/")
    status, data = gitee_json("GET", f"{API_BASE}/{owner}/{repo}/contents/{path_enc}?access_token={token}")
    if status != 200 or not data.get("sha"):
        print(f"清理：{path} 不存在或无需删除（HTTP {status}）")
        return
    body = {"access_token": token, "message": "speed test cleanup", "branch": "master", "sha": data["sha"]}
    status, data = gitee_json("DELETE", f"{API_BASE}/{owner}/{repo}/contents/{path_enc}", body)
    print(f"清理：{path} 删除结果 HTTP {status}" + (f"（{data.get('message', '')}）" if status != 200 else ""))


def main() -> int:
    ap = argparse.ArgumentParser(description="Gitee contents API 覆盖上传测速")
    ap.add_argument("--file", help="要上传的本地文件（如 APK）")
    ap.add_argument("--path", default="updates/_speedtest.bin", help="仓库目标路径")
    ap.add_argument("--delete-only", action="store_true", help="仅清理测试文件")
    ap.add_argument("--owner", default=os.environ.get("GITEE_OWNER", ""))
    ap.add_argument("--repo", default=os.environ.get("GITEE_REPO", ""))
    ap.add_argument("--token", default=os.environ.get("GITEE_TOKEN", ""))
    args = ap.parse_args()

    if not all([args.owner, args.repo, args.token]):
        print("错误：owner/repo/token 不能为空（参数或环境变量 GITEE_OWNER/GITEE_REPO/GITEE_TOKEN）")
        return 1
    if args.delete_only:
        delete_file(args.owner, args.repo, args.path, args.token)
        return 0
    if not args.file or not os.path.isfile(args.file):
        print(f"错误：文件不存在 {args.file}")
        return 1

    size = os.path.getsize(args.file)
    path_enc = urllib.parse.quote(args.path, safe="/")
    print("=" * 56)
    print(f"Gitee contents 覆盖上传测速：{args.file}（{size} bytes, {size/1024/1024:.1f}MB）")
    print(f"  目标: {args.owner}/{args.repo} {args.path}（branch=master）")
    print("=" * 56)

    # 1) GET sha（已存在则 PUT 需带 sha，模拟真实覆盖场景）
    t0 = time.time()
    status, data = gitee_json("GET", f"{API_BASE}/{args.owner}/{args.repo}/contents/{path_enc}?access_token={args.token}")
    sha = data.get("sha") if status == 200 else None
    print(f"① GET 查 sha    : {time.time()-t0:.2f}s（HTTP {status}，{'存在，将覆盖' if sha else '不存在，将新建'}）")

    # 2) base64 编码（本地，不计网络）
    t0 = time.time()
    content_b64 = base64.b64encode(open(args.file, "rb").read()).decode("ascii")
    enc_time = time.time() - t0
    print(f"② base64 编码   : {enc_time:.2f}s（{len(content_b64)} chars）")

    # 3) 覆盖/新建上传（核心耗时；Gitee：已存在=PUT 带 sha，不存在=POST 新建；timeout 600s 防误判）
    method = "PUT" if sha else "POST"
    body = {"access_token": args.token, "content": content_b64, "message": "speed test", "branch": "master"}
    if sha:
        body["sha"] = sha
    t0 = time.time()
    status, data = gitee_json(method, f"{API_BASE}/{args.owner}/{args.repo}/contents/{path_enc}", body)
    dt = time.time() - t0
    print(f"③ {'PUT 覆盖' if sha else 'POST 新建'}上传: {dt:.2f}s（HTTP {status}）")
    if status in (200, 201):
        speed = size / 1024 / 1024 / dt if dt > 0 else float("inf")
        print(f"   → {size/1024/1024:.1f}MB / {dt:.2f}s = {speed:.2f} MB/s")
        print(f"   → 结论：{'可以接受' if dt < 60 else '偏慢'}{'（<60s，可做自动化通道）' if dt < 60 else '（>60s，建议维持手动上传）'}")
        print(f"   清理: python3 scripts/test_gitee_upload_speed.py --delete-only --path {args.path} --owner {args.owner} --repo {args.repo} --token <token>")
    else:
        print(f"   失败: {data}")
    print("=" * 56)
    return 0


if __name__ == "__main__":
    sys.exit(main())
