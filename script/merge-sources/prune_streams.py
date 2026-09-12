#! /usr/bin/env python3
# prune_streams.py
# 校验 tv.txt / tv.m3u 中"流地址"的可用性（并发），并直接清理两份产物。
# 仅做 URL 可用性校验（复用 urlcheck），不做内容/格式校验（产物由 mergeSources 生成，格式有效）。
#
# 清理策略（URL 级判定，保证两份同源产物一致）：
#   1) 先从两份产物中解析出**全部**流地址，统一成"规范形式"后去重；
#   2) 并发校验得到**全局**的可用 / 不可用 URL 集；
#   3) 再用**同一个判定结果**分别重写 tv.txt 与 tv.m3u，并做一致性校验。
#
# 用法：
#   ./prune_streams.py [tv.txt 路径] [tv.m3u 路径]
#   路径缺省为 ../../web/tv.txt 与 ../../web/tv.m3u。

import os
import re
import sys
import time

import urlcheck
from progress import logs_dir, fmt_duration


SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
DEFAULT_TXT = os.path.join(SCRIPT_DIR, '..', '..', 'web', 'tv.txt')
DEFAULT_M3U = os.path.join(SCRIPT_DIR, '..', '..', 'web', 'tv.m3u')


# ================= 头部常量区 =================
PRUNE_WORKERS = 50      # 可用性并发数（可高于 mergeSources 抓取并发）
CONNECT_TIMEOUT = urlcheck.CONNECT_TIMEOUT
READ_TIMEOUT = urlcheck.READ_TIMEOUT
# =============================================


# tv.txt 中"频道名"与"URL 列表"的分隔符。
# [修复] 必须定位 ',http'（等协议）首次出现处，而不是第一个逗号——
# 因为频道名本身可能含逗号（部分订阅源把 tvgid/tvgname/tvglogo/grouptitle 塞进了 name），
# 用第一个逗号切分会把真实频道名粘到首个 URL 上，导致该 URL 漏检 + 漏删。
_URL_SPLIT_RE = re.compile(r',((?:https?|rtmp|rtsp|rtp|rtmps|p2p)://)', re.I)

# tv.m3u 中判定"URL 行"的协议前缀
_URL_LINE_PREFIX = ('http://', 'https://', 'rtmp://', 'rtsp://', 'rtp://', 'rtmps://', 'p2p://')


def canon_url(u):
    """把 URL 统一为"规范形式"：将 tv.txt 中编码的 %23 还原为 #。

    mergeSources 写 tv.txt 时会把 URL 内的 '#' 编码成 '%23'，而写 tv.m3u 时是原样写出的，
    因此同一个 URL 在两份产物里的字面形式可能不同。
    [修复] 提取与比对必须使用同一种形式，否则"提取用解码形式、重写用原始形式"会造成漏删。
    """
    return u.strip().replace('%23', '#')


def split_txt_line(st):
    """拆分 tv.txt 的频道行，返回 (name, urls_part)；非频道行返回 None。"""
    m = _URL_SPLIT_RE.search(st)
    if not m:
        return None
    # m.start() 处是逗号，URL 从 m.start()+1 开始
    return st[:m.start()].strip(), st[m.start() + 1:].strip()


def load_txt(path):
    """解析 tv.txt。

    :return: (items, urls)
        items 每项为 dict：
          {'kind': 'raw',     'text': 原始行}
          {'kind': 'channel', 'name': 频道名前缀, 'urls': [规范形式URL, ...]}
    """
    items = []
    urls = []
    with open(path, 'r', encoding='utf-8', errors='replace') as f:
        for line in f:
            s = line.rstrip('\n')
            st = s.strip()
            if not st or st.endswith('#genre#') or st.startswith('#'):
                items.append({'kind': 'raw', 'text': s})
                continue
            sp = split_txt_line(st)
            if sp is None:
                items.append({'kind': 'raw', 'text': s})
                continue
            name, urls_part = sp
            ch_urls = [canon_url(u) for u in urls_part.split('#') if u.strip()]
            if not ch_urls:
                items.append({'kind': 'raw', 'text': s})
                continue
            items.append({'kind': 'channel', 'name': name, 'urls': ch_urls})
            urls.extend(ch_urls)
    return items, urls


def load_m3u(path):
    """解析 tv.m3u。

    :return: (items, urls)
        items 每项为 dict：
          {'kind': 'raw',   'text': 原始行}
          {'kind': 'entry', 'extinf': 配对的 EXTINF 行(可为 None), 'url': 原始URL, 'canon': 规范形式}
    """
    items = []
    urls = []
    pending = None
    with open(path, 'r', encoding='utf-8', errors='replace') as f:
        for line in f:
            s = line.rstrip('\n')
            st = s.strip()
            if st.startswith('#EXTINF'):
                pending = s
                continue
            if st.lower().startswith(_URL_LINE_PREFIX):
                canon = canon_url(st)
                items.append({'kind': 'entry', 'extinf': pending, 'url': s, 'canon': canon})
                urls.append(canon)
                pending = None
                continue
            if pending is not None:
                items.append({'kind': 'raw', 'text': pending})
                pending = None
            items.append({'kind': 'raw', 'text': s})
    if pending is not None:
        items.append({'kind': 'raw', 'text': pending})
    return items, urls


def rewrite_txt(items, bad):
    """按全局 bad 集重写 tv.txt（URL 级判定）。

    :return: (输出行列表, 删除的流地址数, 因全部地址不可用而整行删除的频道数)
    """
    out = []
    removed_urls = 0
    removed_channels = 0
    for it in items:
        if it['kind'] != 'channel':
            out.append(it['text'])
            continue
        good = [u for u in it['urls'] if u not in bad]
        removed_urls += len(it['urls']) - len(good)
        if not good:
            removed_channels += 1
            continue
        # tv.txt 中 URL 内的 '#' 需编码为 '%23'（与 mergeSources 的写入规则一致）
        out.append(it['name'] + ',' + '#'.join(u.replace('#', '%23') for u in good))
    return out, removed_urls, removed_channels


def rewrite_m3u(items, bad):
    """按全局 bad 集重写 tv.m3u（URL 级判定，同时丢弃配对的 #EXTINF 行）。

    :return: (输出行列表, 删除的流地址数)
    """
    out = []
    removed_urls = 0
    for it in items:
        if it['kind'] != 'entry':
            out.append(it['text'])
            continue
        if it['canon'] in bad:
            removed_urls += 1
            continue      # 丢弃该 URL 及其配对的 EXTINF
        if it['extinf'] is not None:
            out.append(it['extinf'])
        out.append(it['url'])
    return out, removed_urls


DETAIL_DIR = logs_dir(os.path.join(SCRIPT_DIR, 'logs'))


def main():
    tv_txt = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_TXT
    tv_m3u = sys.argv[2] if len(sys.argv) > 2 else DEFAULT_M3U

    # ---------- 1) 解析两份产物，汇总出"全局流地址集"（统一规范形式） ----------
    txt_items = txt_urls = None
    m3u_items = m3u_urls = None
    if os.path.exists(tv_txt):
        txt_items, txt_urls = load_txt(tv_txt)
    if os.path.exists(tv_m3u):
        m3u_items, m3u_urls = load_m3u(tv_m3u)
    urls = list(dict.fromkeys((txt_urls or []) + (m3u_urls or [])))

    print(f"[prune_streams] 共提取 {len(urls)} 个流地址，"
          f"开始并发可用性检查（workers={PRUNE_WORKERS}）...")
    t0 = time.time()
    detail = os.path.join(DETAIL_DIR, 'prune_streams.detail.json')
    results = urlcheck.check_urls(
        urls, workers=PRUNE_WORKERS,
        connect_timeout=CONNECT_TIMEOUT, read_timeout=READ_TIMEOUT,
        log_prefix="[prune_streams] 校验流地址", detail_path=detail)
    bad = {u for u, (ok, _r) in results.items() if not ok}
    print(f"[prune_streams] 不可用流地址数: {len(bad)}")

    # ---------- 2) 用同一个全局判定结果，统一重写两份产物 ----------
    n_txt_urls = n_txt_ch = n_m3u_urls = 0
    if txt_items is not None:
        out_lines, n_txt_urls, n_txt_ch = rewrite_txt(txt_items, bad)
        with open(tv_txt, 'w', encoding='utf-8') as f:
            f.write('\n'.join(out_lines) + '\n')
    if m3u_items is not None:
        out_lines, n_m3u_urls = rewrite_m3u(m3u_items, bad)
        with open(tv_m3u, 'w', encoding='utf-8') as f:
            f.write('\n'.join(out_lines) + '\n')

    elapsed = time.time() - t0
    print(f"[prune_streams] 总结: 检查 {len(urls)} 个流地址, 不可用 {len(bad)} 个, "
          f"tv.txt 删除 {n_txt_urls} 个流地址(其中整行删除频道 {n_txt_ch} 个), "
          f"tv.m3u 删除 {n_m3u_urls} 个流地址, 耗时 {fmt_duration(elapsed)}")
    print(f"[prune_streams] 详情日志: {detail}")

    # ---------- 3) 一致性校验：清理后两份产物应保留同一批流地址 ----------
    if txt_items is not None and m3u_items is not None:
        kept_txt = set()
        for it in txt_items:
            if it['kind'] == 'channel':
                kept_txt.update(u for u in it['urls'] if u not in bad)
        kept_m3u = set()
        for it in m3u_items:
            if it['kind'] == 'entry' and it['canon'] not in bad:
                kept_m3u.add(it['canon'])
        if kept_txt == kept_m3u:
            print(f"[prune_streams] 一致性校验通过：两份产物保留同一批流地址（{len(kept_txt)} 个）")
        else:
            print(f"[prune_streams] 一致性告警：tv.txt 独有 {len(kept_txt - kept_m3u)} 个 / "
                  f"tv.m3u 独有 {len(kept_m3u - kept_txt)} 个"
                  f"（保留 {len(kept_txt)} vs {len(kept_m3u)}），多为两份产物输入时即不同源所致")


if __name__ == '__main__':
    main()
