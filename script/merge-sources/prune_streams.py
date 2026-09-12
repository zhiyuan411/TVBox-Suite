#! /usr/bin/env python3
# prune_streams.py
# 校验 tv.txt / tv.m3u 中"流地址"的可用性（并发），并直接清理两份产物。
# 仅做 URL 可用性校验（复用 urlcheck），不做内容/格式校验（产物由 mergeSources 生成，格式有效）。
# 清理策略：计算一份"不可用 URL 集合"，然后分别格式感知地重写 tv.txt 与 tv.m3u，
# 保证两份同源产物同时被清理、保持一致。
#
# 用法：
#   ./prune_streams.py [tv.txt 路径] [tv.m3u 路径]
#   路径缺省为 ../../web/tv.txt 与 ../../web/tv.m3u。

import os
import sys
import time

import urlcheck
from progress import logs_dir


SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
DEFAULT_TXT = os.path.join(SCRIPT_DIR, '..', '..', 'web', 'tv.txt')
DEFAULT_M3U = os.path.join(SCRIPT_DIR, '..', '..', 'web', 'tv.m3u')


# ================= 头部常量区 =================
PRUNE_WORKERS = 50      # 可用性并发数（可高于 mergeSources 抓取并发）
CONNECT_TIMEOUT = urlcheck.CONNECT_TIMEOUT
READ_TIMEOUT = urlcheck.READ_TIMEOUT
# =============================================


def extract_http_urls_from_txt(path):
    """从 tv.txt 提取所有 http(s) 流地址（按 '#' 分隔的多 URL 行）。"""
    urls = []
    with open(path, 'r', encoding='utf-8', errors='replace') as f:
        for line in f:
            s = line.strip()
            if not s or s.endswith('#genre#') or s.startswith('#'):
                continue
            ci = s.find(',')
            if ci == -1:
                continue
            urls_part = s[ci + 1:].strip()
            for u in urls_part.split('#'):
                u = u.strip().replace('%23', '#')
                if u.startswith(('http://', 'https://')):
                    urls.append(u)
    return urls


def extract_http_urls_from_m3u(path):
    """从 tv.m3u 提取所有 http(s) 流地址（URL 行）。"""
    urls = []
    with open(path, 'r', encoding='utf-8', errors='replace') as f:
        for line in f:
            s = line.strip()
            if s.startswith(('http://', 'https://')):
                urls.append(s)
    return urls


def rewrite_txt(path, bad):
    """重写 tv.txt：删除含全坏 URL 的频道行；部分坏则仅移除坏 URL。rtmp/rtsp 等保留。"""
    out = []
    removed = 0
    with open(path, 'r', encoding='utf-8', errors='replace') as f:
        for line in f:
            s = line.rstrip('\n')
            st = s.strip()
            if not st or st.endswith('#genre#') or st.startswith('#'):
                out.append(s)
                continue
            ci = st.find(',')
            if ci == -1:
                out.append(s)
                continue
            name = st[:ci].strip()
            urls_part = st[ci + 1:].strip()
            good = []
            for u in urls_part.split('#'):
                u = u.strip()
                if not u:
                    continue
                # 仅对 http(s) 做可用性判定；rtmp/rtsp 等非 http 协议保留
                if u.startswith(('http://', 'https://')) and u in bad:
                    continue
                good.append(u)
            if not good:
                removed += 1
                continue
            # 重新编码 '#' -> '%23'
            encoded = '#'.join(u.replace('#', '%23') for u in good)
            out.append(f"{name},{encoded}")
    with open(path, 'w', encoding='utf-8') as f:
        f.write('\n'.join(out) + '\n')
    return removed


def rewrite_m3u(path, bad):
    """重写 tv.m3u：删除不可用的 http(s) 流地址行及其配对的 #EXTINF 行。"""
    out = []
    removed = 0
    pending_extinf = None
    with open(path, 'r', encoding='utf-8', errors='replace') as f:
        for line in f:
            s = line.rstrip('\n')
            st = s.strip()
            if st.startswith('#EXTINF'):
                pending_extinf = s
            elif st.startswith(('http://', 'https://', 'rtsp://', 'rtmp://')):
                if st.startswith(('http://', 'https://')) and st in bad:
                    # 丢弃该 URL 及其对应的 EXTINF 配对行
                    removed += 1
                    pending_extinf = None
                    continue
                if pending_extinf is not None:
                    out.append(pending_extinf)
                    pending_extinf = None
                out.append(s)
            else:
                if pending_extinf is not None:
                    out.append(pending_extinf)
                    pending_extinf = None
                out.append(s)
    if pending_extinf is not None:
        out.append(pending_extinf)
    with open(path, 'w', encoding='utf-8') as f:
        f.write('\n'.join(out) + '\n')
    return removed


DETAIL_DIR = logs_dir(os.path.join(SCRIPT_DIR, 'logs'))


def main():
    tv_txt = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_TXT
    tv_m3u = sys.argv[2] if len(sys.argv) > 2 else DEFAULT_M3U

    urls = []
    if os.path.exists(tv_txt):
        urls += extract_http_urls_from_txt(tv_txt)
    if os.path.exists(tv_m3u):
        urls += extract_http_urls_from_m3u(tv_m3u)
    urls = list(dict.fromkeys(urls))

    print(f"[prune_streams] 共提取 {len(urls)} 个 http(s) 流地址，"
          f"开始并发可用性检查（workers={PRUNE_WORKERS}）...")
    t0 = time.time()
    detail = os.path.join(DETAIL_DIR, 'prune_streams.detail.json')
    results = urlcheck.check_urls(
        urls, workers=PRUNE_WORKERS,
        connect_timeout=CONNECT_TIMEOUT, read_timeout=READ_TIMEOUT,
        log_prefix="[prune_streams] 校验流地址", detail_path=detail)
    bad = {u for u, (ok, _r) in results.items() if not ok}
    print(f"[prune_streams] 不可用流地址数: {len(bad)}")

    n1 = n2 = 0
    if os.path.exists(tv_txt):
        n1 = rewrite_txt(tv_txt, bad)
    if os.path.exists(tv_m3u):
        n2 = rewrite_m3u(tv_m3u, bad)
    elapsed = time.time() - t0
    print(f"[prune_streams] 总结: 检查 {len(urls)} 个流地址, 不可用 {len(bad)} 个, "
          f"tv.txt 移除 {n1} 行, tv.m3u 移除 {n2} 个, 耗时 {elapsed:.1f}s")
    print(f"[prune_streams] 详情日志: {detail}")


if __name__ == '__main__':
    main()
