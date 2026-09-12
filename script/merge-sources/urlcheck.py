#! /usr/bin/env python3
# urlcheck.py
# 通用 URL 可用性并发检查模块。
# 被 filterBadApiUrls.py（校验 tv.json 的 api）与 prune_streams.py（校验 tv.txt/tv.m3u 流地址）复用。
# 仅做"最上层"可用性校验：DNS 可解析 / TCP 可连接 / HTTP 状态码正常。
# 不下载、不解析、不校验返回内容格式（目标内容未必是 JSON）。

import socket
import warnings
import concurrent.futures
import urllib.parse

import requests

from progress import Progress, write_detail_json

# 抑制 verify=False 产生的 InsecureRequestWarning
try:
    from urllib3.exceptions import InsecureRequestWarning
    warnings.simplefilter('ignore', InsecureRequestWarning)
except Exception:
    pass


# ================= 可调常量（头部常量区） =================
CONNECT_TIMEOUT = 3    # TCP 连接超时（秒）——IP 黑洞/防火墙丢包的最坏等待封顶
READ_TIMEOUT = 5       # 读取超时（秒）
DEFAULT_WORKERS = 50   # 可用性检查默认并发数（高于 mergeSources 抓取并发）
# ========================================================


def parse_host_port(url):
    """从 URL 解析出 (host, port, scheme)，解析失败返回 (None, None, None)。"""
    try:
        p = urllib.parse.urlparse(url)
    except Exception:
        return None, None, None
    if not p.hostname:
        return None, None, None
    port = p.port or (443 if p.scheme == 'https' else 80)
    return p.hostname, port, p.scheme


def dns_resolves(host):
    """DNS 快失败：可解析返回 True，否则 False（几乎零等待）。"""
    try:
        socket.getaddrinfo(host, None)
        return True
    except Exception:
        return False


def check_one(url, connect_timeout=CONNECT_TIMEOUT, read_timeout=READ_TIMEOUT):
    """
    检查单个 URL 的可用性。
    返回 (url, ok:bool, reason:str)。
    仅判断：DNS / 连接 / 状态码，不下载也不校验内容。
    """
    try:
        host, _port, _scheme = parse_host_port(url)
    except Exception:
        return (url, False, 'BAD_URL')
    if not host:
        return (url, False, 'NO_HOST')

    # 1) DNS 快失败
    if not dns_resolves(host):
        return (url, False, 'DNS_FAIL')

    # 2) 连接 + 状态码（仅释放连接，不读取/解析响应体）
    try:
        r = requests.get(
            url,
            timeout=(connect_timeout, read_timeout),
            allow_redirects=True,
            verify=False,
            stream=True,
        )
        try:
            next(r.iter_content(1024), b'')  # 释放连接，不消费内容
        except Exception:
            pass
        r.close()
        # 可用性定义：能连通且返回 2xx 或 3xx（3xx 含 -L 会跟随的重定向）
        if r.status_code == 200 or 300 <= r.status_code < 400:
            return (url, True, 'OK')
        return (url, False, f'HTTP_{r.status_code}')
    except requests.exceptions.ConnectTimeout:
        return (url, False, 'CONN_TIMEOUT')
    except requests.exceptions.ReadTimeout:
        return (url, False, 'READ_TIMEOUT')
    except requests.exceptions.SSLError:
        return (url, False, 'SSL_ERROR')
    except requests.exceptions.ConnectionError:
        return (url, False, 'CONN_ERROR')
    except Exception as e:
        return (url, False, f'ERR_{type(e).__name__}')


def check_urls(urls, workers=DEFAULT_WORKERS,
               connect_timeout=CONNECT_TIMEOUT, read_timeout=READ_TIMEOUT,
               log_prefix="", detail_path=None, progress=True):
    """
    并发检查一批 URL。
    返回 dict: url -> (ok:bool, reason:str)。URL 自动去重（保序）。

    log_prefix  : 进度日志前缀
    detail_path : 若给定，将每条结果(含不可用原因)写入该 JSON 文件
    progress    : 是否打印 [当前/总数 已用 预计剩余] 进度日志
    """
    urls = list(dict.fromkeys(urls))  # 去重且保持首次出现顺序
    results = {}
    if not urls:
        return results
    records = []
    pg = Progress(len(urls), prefix=log_prefix, interval=2.0) if progress else None
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as ex:
        futs = {ex.submit(check_one, u, connect_timeout, read_timeout): u for u in urls}
        for f in concurrent.futures.as_completed(futs):
            u, ok, reason = f.result()
            results[u] = (ok, reason)
            records.append({"url": u, "ok": ok, "reason": reason})
            if pg:
                pg.update(1)
    if pg:
        pg.finish()
    if detail_path:
        write_detail_json(detail_path, records)
    return results


def bad_urls(urls, workers=DEFAULT_WORKERS,
             connect_timeout=CONNECT_TIMEOUT, read_timeout=READ_TIMEOUT):
    """返回不可用的 URL 集合（可用性校验专用）。"""
    res = check_urls(urls, workers=workers,
                     connect_timeout=connect_timeout, read_timeout=read_timeout)
    return {u for u, (ok, _r) in res.items() if not ok}
