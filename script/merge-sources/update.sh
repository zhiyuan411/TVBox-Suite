#!/bin/bash
LOCK_FILE="/tmp/update_script.lock"
WARN_COUNT=0

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"; }

if [ -f "$LOCK_FILE" ]; then
    log "错误：更新脚本已在执行中，禁止重复运行！"
    exit 1
fi
touch "$LOCK_FILE"
trap 'rm -f "$LOCK_FILE"; log "==== 锁文件已清理 ===="' EXIT

# 核心：必须成功
./mergeSources.4.0.py input.txt ../../web/tv.json ../../web/tv.m3u ../../web/tv.txt || {
    log "[FATAL] mergeSources 失败，终止执行"
    exit 1
}

# 可容忍失败，计数
./filterBadApiUrls.py ../../web/tv.json || {
    log "[WARN] filterBadApiUrls 异常，跳过"
    WARN_COUNT=$((WARN_COUNT + 1))
}

./prune_streams.py ../../web/tv.txt ../../web/tv.m3u || {
    log "[WARN] prune_streams 异常，跳过"
    WARN_COUNT=$((WARN_COUNT + 1))
}

sed -i -e 's@[^"]*https://raw.githubusercontent.com@https://rawgithubusercontent.cnfaq.cn@' \
       -e 's@"jiexiUrl"@"playUrl"@' ../../web/tv.json 2>/dev/null || true

~/TVBox-Suite/script/random-sites/randomSites.py || {
    log "[WARN] randomSites 异常"
    WARN_COUNT=$((WARN_COUNT + 1))
}

log "==== 更新任务执行完毕 ===="
if [ "$WARN_COUNT" -gt 0 ]; then
    log "[NOTICE] 本次执行存在 ${WARN_COUNT} 个警告降级"
    exit 2
fi
exit 0
