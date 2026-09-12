#! /usr/bin/env python3
# progress.py
# 轻量进度 / ETA / 详情日志工具，供各脚本复用。
# 仅负责"日志输出"，不介入任何功能执行逻辑。
#   - Progress        : 周期性打印 [当前/总数 百分比] 已用X时X分X秒 预计剩余X时X分X秒
#   - fmt_duration    : 秒数 -> 自适应 时/分/秒 文本（精确到秒）
#   - write_detail_json: 将每条校验结果写入独立 JSON 文件（避免控制台刷屏）
#   - summarize_bad   : 把不可用列表压缩成适合控制台的摘要文本

import time
import sys
import json
import os
from datetime import datetime


def fmt_duration(seconds):
    """把秒数格式化为自适应的 时/分/秒 文本，精确到秒。
    例：3725 -> 1时2分5秒；65 -> 1分5秒；45 -> 45秒；0.4 -> 0秒
    """
    try:
        total = int(round(float(seconds)))
    except (TypeError, ValueError):
        total = 0
    if total < 0:
        total = 0
    h, rem = divmod(total, 3600)
    m, s = divmod(rem, 60)
    if h > 0:
        return f"{h}时{m}分{s}秒"
    if m > 0:
        return f"{m}分{s}秒"
    return f"{s}秒"


# ================= 详情日志（落盘文件，不输出到控制台） =================
import re as _re

_DETAIL_HANDLE = None
_DETAIL_PATH = None


def _ensure_detail():
    """懒初始化详情日志文件（<cwd>/logs/merge_detail.log，每次运行覆盖）。"""
    global _DETAIL_HANDLE, _DETAIL_PATH
    if _DETAIL_HANDLE is None:
        try:
            d = logs_dir()
            _DETAIL_PATH = os.path.join(d, "merge_detail.log")
            _DETAIL_HANDLE = open(_DETAIL_PATH, "w", encoding="utf-8")
        except Exception:
            _DETAIL_HANDLE = None
            _DETAIL_PATH = None
    return _DETAIL_HANDLE


def detail(msg):
    """把逐条明细写入详情日志文件（不打到控制台）。失败静默。"""
    h = _ensure_detail()
    if h is None:
        return
    try:
        h.write(str(msg).rstrip("\n") + "\n")
        h.flush()
    except Exception:
        pass


def detail_path():
    """返回当前详情日志文件路径（首次 detail() 调用后才非 None）。"""
    return _DETAIL_PATH


def normalize_reason(exc):
    """把 requests/urllib3 异常映射为短原因标签（无需 import requests）。"""
    msg = str(exc) or type(exc).__name__
    if "timed out" in msg or "Timeout" in msg:
        return "超时"
    if "Name or service not known" in msg or "getaddrinfo" in msg \
            or "NameResolutionError" in msg or "nodename nor servname" in msg:
        return "DNS解析失败"
    if "Connection refused" in msg or "Errno 111" in msg:
        return "连接被拒"
    if "Network is unreachable" in msg or "Errno 101" in msg:
        return "网络不可达"
    if "Invalid URL" in msg or "No scheme supplied" in msg:
        return "URL非法"
    if "SSLError" in msg or "CERTIFICATE_VERIFY_FAILED" in msg \
            or "CertificateError" in msg:
        return "SSL错误"
    if "hostname" in msg and "doesn't match" in msg:
        return "SSL错误(主机名不符)"
    if "Connection aborted" in msg or "ConnectionReset" in msg:
        return "连接重置"
    if "NewConnectionError" in msg or "Failed to establish" in msg \
            or "Max retries exceeded" in msg:
        return "连接失败(重试耗尽)"
    m = _re.search(r"(\d{3}) (?:Client|Server) Error", msg)
    if m:
        return f"HTTP {m.group(1)}"
    return "其他"


class Progress:
    def __init__(self, total, prefix="", interval=2.0, stream=sys.stderr):
        self.total = max(int(total), 0)
        self.done = 0
        self.prefix = prefix
        self.interval = interval          # 两次进度刷新的最小间隔（秒）
        self.stream = stream
        self.start = time.time()
        self._last = 0.0

    def update(self, n=1):
        self.done += n
        now = time.time()
        if now - self._last >= self.interval or self.done >= self.total:
            self._report(now)

    def _report(self, now=None):
        now = now if now is not None else time.time()
        self._last = now
        elapsed = now - self.start
        if self.done > 0:
            rate = self.done / elapsed
            remain = (self.total - self.done) / rate if self.total > self.done else 0.0
        else:
            rate = 0.0
            remain = 0.0
        pct = (100.0 * self.done / self.total) if self.total else 100.0
        ts = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        msg = (f"[{ts}] {self.prefix} [{self.done}/{self.total} {pct:.0f}%] "
               f"已用 {fmt_duration(elapsed)} 预计剩余 {fmt_duration(remain)}")
        print(msg, file=self.stream, flush=True)

    def finish(self):
        elapsed = time.time() - self.start
        ts = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        print(f"[{ts}] {self.prefix} 完成 {self.done}/{self.total} 总耗时 {fmt_duration(elapsed)}",
              file=self.stream, flush=True)
        return elapsed


def logs_dir(base=None):
    """返回（并创建）日志目录；默认 <cwd>/logs。"""
    d = base if base else os.path.join(os.getcwd(), 'logs')
    try:
        os.makedirs(d, exist_ok=True)
    except Exception:
        pass
    return d


def write_detail_json(path, records):
    """将校验明细（含不可用原因）写入独立 JSON 文件。失败仅告警不影响主流程。"""
    try:
        with open(path, 'w', encoding='utf-8') as f:
            json.dump(records, f, ensure_ascii=False, indent=2)
        return path
    except Exception as e:
        print(f"[progress] 写入详情日志失败: {e}", file=sys.stderr)
        return None


def summarize_bad(bad_list, max_show=20):
    """
    把 [(url, reason), ...] 压缩为控制台摘要文本，避免刷屏。
    超过 max_show 时仅展示前若干条并引导查看 JSON 详情日志。
    """
    n = len(bad_list)
    if n == 0:
        return "无"
    if n <= max_show:
        return "\n  ".join(f"{u} -> {r}" for u, r in bad_list)
    head = "\n  ".join(f"{u} -> {r}" for u, r in bad_list[:max_show])
    return f"{head}\n  ... 共 {n} 个，详见 JSON 详情日志"
