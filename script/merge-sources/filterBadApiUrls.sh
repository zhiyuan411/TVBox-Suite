#!/bin/bash

# 获取脚本的绝对路径
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

# 定义输入文件和输出文件的路径
INPUT_FILE="$SCRIPT_DIR/tv.json"
INPUT_BAK_FILE="$SCRIPT_DIR/tv.json.bak"
SINGLE_URLS="$SCRIPT_DIR/tmp.single_urls.txt"
URLS_FILE="$SCRIPT_DIR/tmp.urls.txt"
USABLE_URLS="$SCRIPT_DIR/tmp.usable_urls.txt"
UNUSABLE_URLS="$SCRIPT_DIR/tmp.unusable_urls.txt"

# 清空输出文件
> "$SINGLE_URLS"
> "$URLS_FILE"
> "$USABLE_URLS"
> "$UNUSABLE_URLS"

    # extract the markdown link with http...
    cat "$INPUT_FILE" | grep '"api":' | grep -o '"http://[^)]*"' | sed -e 's/^"//' -e 's/"$//' | tee -a "$SINGLE_URLS"
    cat "$INPUT_FILE" | grep '"api":' | grep -o '"https://[^)]*"' | sed -e 's/^"//' -e 's/"$//' | tee -a "$SINGLE_URLS"


# 输出完成信息
echo "URL parsing complete. Single URLs are in $SINGLE_URLS"

# 先对结果进行去重
sort "$SINGLE_URLS" | uniq > "$URLS_FILE"

# 读取输入文件中的每一行URL，开始验证url是否可用
while IFS= read -r line
do
    # 使用curl检查URL是否可用，跟随重定向，忽略SSL证书错误，只输出HTTP状态码
    STATUS_CODE=$(curl --silent --head -L --insecure --write-out '%{http_code}' --connect-timeout 10 --max-time 10  --output /dev/null "$line")

    # 判断状态码是否为200（表示URL可用）
    if [ "$STATUS_CODE" -eq 200 ]; then
        # 如果URL可用，则将其追加到可用URL的文件中
        echo "$line"  | tee -a "$USABLE_URLS"
    else
        # 如果URL不可用，则将其地址追加到不可用URL的文件中
        echo "$line"  | tee -a "$UNUSABLE_URLS"
    fi
done < "$URLS_FILE"

# 输出完成信息
echo "URL checking complete. Usable URLs are in $USABLE_URLS and unusable URLs are in $UNUSABLE_URLS."

# 复制原始输入
cp -f "$INPUT_FILE" "$INPUT_BAK_FILE"

# ============== [修改开始] 使用 AWK 替换原 SED 循环 ==============
# 使用 awk 进行安全的批量字符串替换
# 逻辑：
# 1. 先读取 UNUSABLE_URLS，将 URL 加上引号存入内存
# 2. 逐行读取 INPUT_FILE，若包含坏 URL 则替换为空
# 3. 输出到临时文件，最后原子性覆盖原文件
awk '
# 处理第一个文件（不可用URL列表）
NR == FNR {
    if (length($0) > 0) {
        # 给URL两边加上双引号，作为键存入数组，防止误替换
        bad_map["\"" $0 "\""] = 1
    }
    next
}
# 处理第二个文件（主JSON文件）
{
    line = $0
    # 遍历所有坏URL，执行全局替换
    for (bad_url in bad_map) {
        gsub(bad_url, "\"\"", line)
    }
    print line
}
' "$UNUSABLE_URLS" "$INPUT_FILE" > "$INPUT_FILE.tmp"

# 只有当 awk 命令成功执行时，才用临时文件覆盖原文件
if [ $? -eq 0 ]; then
    mv "$INPUT_FILE.tmp" "$INPUT_FILE"
else
    echo "警告：处理失败，保留原文件"
    rm -f "$INPUT_FILE.tmp"
fi
# ============== [修改结束] ==============
