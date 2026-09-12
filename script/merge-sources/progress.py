#! /usr/bin/env python3
# progress.py
# 轻量进度 / ETA / 详情日志工具，供各脚本复用。
# 仅负责"日志输出"，不介入任何功能执行逻辑。
#   - Progress        : 周期性打印 [当前/总数 百分比] 已用Xs 预计剩余Ys
#   - write_detail_json: 将每条校验结果写入独立 JSON 文件（避免控制台刷屏）
#   - summarize_bad   : 把不可用列表压缩成适合控制台的摘要文本

import time
import sys
import json
import os


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
        msg = (f"{self.prefix} [{self.done}/{self.total} {pct:.0f}%] "
               f"已用 {elapsed:.1f}s 预计剩余 {remain:.1f}s")
        print(msg, file=self.stream, flush=True)

    def finish(self):
        elapsed = time.time() - self.start
        print(f"{self.prefix} 完成 {self.done}/{self.total} 总耗时 {elapsed:.1f}s",
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
