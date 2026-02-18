#! /bin/sh

# ===================== 防重复执行逻辑（核心新增） =====================
# 定义锁文件路径（标记文件）
LOCK_FILE="/tmp/update_script.lock"

# 检查锁文件是否存在：存在则说明脚本正在执行，直接退出
if [ -f "$LOCK_FILE" ]; then
    echo "错误：更新脚本已在执行中，禁止重复运行！"
    exit 1
fi

# 创建锁文件（标记脚本开始执行）
touch "$LOCK_FILE"

# 设置陷阱（Trap）：无论脚本是正常结束、异常退出（如Ctrl+C），都删除锁文件
# EXIT 捕获所有退出信号，确保锁文件必清理
trap 'rm -f "$LOCK_FILE"; echo "==== 锁文件已清理 ===="' EXIT
# =====================================================================

# 先备份之前的结果文件，最后会被合并到新结果中
#cp -f ./tv.json ./tv.json.old

# 直播
#./mergeSources.py input.live.txt live.json

# tvbox源
#./mergeSources.1.0.py input.txt tv.1.0.json
#./mergeSources.2.0.py input.txt tv.json
./mergeSources.3.0.py input.txt tv.json tv.m3u

# 去除结果中的api属性的无效url
#./filterBadApiUrls.sh

# 原始内容
#no=1
#prefixes=("." "/" "http")
#rm tv-*.json
#while IFS= read -r line; do
#    #echo "line: $line"
#    for prefix in "${prefixes[@]}"; do
#        if [[ $line == "${prefix}"* ]]; then
#            echo "${no} ${line}"
#            echo "$line" > tmp-input.txt
#            ./mergeSources.2.0.py tmp-input.txt tv-${no}.json
#            ((no++))
#            break
#        fi
#    done
#done < "input.txt"

# 精选站点
#jq 'del(.sites)' tv.json > tv-without-sites.json
#./mergeSources.2.0.py input.special.txt tv.s.json

# 修正github
sed -i -e 's@[^"]*https://raw.githubusercontent.com@https://rawgithubusercontent.cnfaq.cn@' -e 's@"jiexiUrl"@"playUrl"@' ./tv*.json

# 立刻进行一次更新
/home/ecs-user/tvbox-random-sites/randomSites.py

echo "==== 更新任务执行完毕 ===="
