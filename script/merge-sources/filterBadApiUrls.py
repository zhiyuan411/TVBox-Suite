#! /usr/bin/env python3
# filterBadApiUrls.py
# 校验 tv.json 中每个 "api" 字段 URL 的可用性（并发），并将不可用的 api 置空。
# 与旧版 filterBadApiUrls.sh 行为一致：仅做可用性（DNS / 连接 / 状态码）校验，
# 不校验返回内容格式（目标内容未必是 JSON）。
#
# 用法：
#   ./filterBadApiUrls.py [tv.json 路径]
#   路径缺省为 ../../web/tv.json（与 mergeSources 输出位置一致）。

import os
import sys
import time
import json

import urlcheck
from progress import summarize_bad, logs_dir, fmt_duration


SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
DEFAULT_TV_JSON = os.path.join(SCRIPT_DIR, '..', '..', 'web', 'tv.json')


# ================= 头部常量区 =================
FILTER_WORKERS = 50     # 可用性并发数（可高于 mergeSources 抓取并发）
CONNECT_TIMEOUT = urlcheck.CONNECT_TIMEOUT
READ_TIMEOUT = urlcheck.READ_TIMEOUT
# =============================================


def collect_api_refs(obj, out):
    """
    递归收集所有 key == 'api' 且值为 http(s) 字符串的引用。
    out: list of (container_dict, key)
    """
    if isinstance(obj, dict):
        for k, v in obj.items():
            if k == 'api' and isinstance(v, str) and v.startswith(('http://', 'https://')):
                out.append((obj, k))
            else:
                collect_api_refs(v, out)
    elif isinstance(obj, list):
        for item in obj:
            collect_api_refs(item, out)


DETAIL_DIR = logs_dir(os.path.join(SCRIPT_DIR, 'logs'))


def main():
    tv_json = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_TV_JSON
    if not os.path.exists(tv_json):
        print(f"[filterBadApiUrls] 文件不存在: {tv_json}")
        sys.exit(1)

    with open(tv_json, 'r', encoding='utf-8') as f:
        data = json.load(f)

    api_refs = []
    collect_api_refs(data, api_refs)
    urls = list(dict.fromkeys(container[key] for container, key in api_refs))

    # 同一 URL 可能出现在多处，建立 url -> [refs] 映射以便统一置空
    url_to_refs = {}
    for container, key in api_refs:
        url_to_refs.setdefault(container[key], []).append((container, key))

    print(f"[filterBadApiUrls] 共发现 {len(urls)} 个 api URL，"
          f"开始并发可用性检查（workers={FILTER_WORKERS}）...")
    t0 = time.time()
    detail = os.path.join(DETAIL_DIR, 'filterBadApiUrls.detail.json')
    results = urlcheck.check_urls(
        urls, workers=FILTER_WORKERS,
        connect_timeout=CONNECT_TIMEOUT, read_timeout=READ_TIMEOUT,
        log_prefix="[filterBadApiUrls] 校验api", detail_path=detail)

    bad = [(u, results[u][1]) for u, (ok, _r) in results.items() if not ok]
    print(f"[filterBadApiUrls] 不可用 api 数: {len(bad)}")
    if bad:
        print(f"[filterBadApiUrls] 不可用列表:\n  " + summarize_bad(bad))

    # 将不可用 api 置空（与原 awk 行为一致：保留条目，仅清空失效链接）
    for u, _r in bad:
        for container, key in url_to_refs[u]:
            container[key] = ""

    with open(tv_json, 'w', encoding='utf-8') as f:
        json.dump(data, f, indent=4, ensure_ascii=False)
    elapsed = time.time() - t0
    print(f"[filterBadApiUrls] 总结: 检查 {len(urls)} 个, 不可用 {len(bad)} 个, "
          f"耗时 {fmt_duration(elapsed)}, 已写回 {tv_json}")
    print(f"[filterBadApiUrls] 详情日志: {detail}")


if __name__ == '__main__':
    main()
