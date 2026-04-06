#!/bin/bash

# ================= 配置区 =================
# 要删除的文件列表（相对于仓库根目录）
FILES_TO_DELETE=(
    "web/tvbox-old.apk"
    "web/tvbox-old2.apk"
    "web/tvbox.apk"
    "web/tvbox2.apk"
)
# 远程仓库名称（通常是 origin）
REMOTE_NAME="origin"
# 上游仓库名称
UPSTREAM_NAME="upstream"
# 上游仓库分支
UPSTREAM_BRANCH="main"
# 默认上游仓库地址
DEFAULT_UPSTREAM_URL="git@github.com:takagen99/Box.git"
# 固定临时备份目录
BACKUP_DIR="/tmp/apk_backup"
# =========================================

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${YELLOW}========================================${NC}"
echo -e "${YELLOW}Git 历史大文件清理脚本${NC}"
echo -e "${YELLOW}========================================${NC}"
echo ""
echo -e "📌 重要说明："
echo -e "   1. 此脚本将${GREEN}先备份当前APK，清理完历史后再恢复${NC}。"
echo -e "   2. 此操作会改变历史 Commit ID，推送后团队成员需重新 Clone。"
echo -e "   3. 执行前${GREEN}必须已合并上游仓库${NC}，否则脚本无法运行！${NC}"
echo ""

# 1. 检查是否在 git 仓库根目录
if [ ! -d ".git" ]; then
    echo -e "${RED}错误：未找到 .git 目录，请在 Git 仓库根目录下执行此脚本。${NC}"
    exit 1
fi
echo -e "${GREEN}[1/16] 检查 Git 仓库：通过${NC}"

# 2. 检查工作区是否干净
if ! git diff-index --quiet HEAD --; then
    echo -e "${RED}错误：工作区有未提交的修改，请先 commit 后再运行。${NC}"
    exit 1
fi
echo -e "${GREEN}[2/16] 检查工作区状态：干净${NC}"

# 3. 检查本地提交是否已推送到远程
CURRENT_BRANCH=$(git branch --show-current)
if [ -z "$CURRENT_BRANCH" ]; then
    echo -e "${RED}错误：当前处于 detached HEAD 状态，请切换到具体分支。${NC}"
    exit 1
fi

# 3.1 检查是否有上游分支 (origin)
if ! git rev-parse --verify @{u} &> /dev/null; then
    echo -e "${RED}错误：当前分支 '$CURRENT_BRANCH' 未设置远程上游分支。${NC}"
    echo -e "${YELLOW}提示：请先执行 'git push -u $REMOTE_NAME $CURRENT_BRANCH' 推送并设置上游。${NC}"
    exit 1
fi

# 3.2 检查本地是否领先于远程
LOCAL_COMMIT=$(git rev-parse HEAD)
REMOTE_COMMIT=$(git rev-parse @{u})

if [ "$LOCAL_COMMIT" != "$REMOTE_COMMIT" ]; then
    echo -e "${RED}错误：本地分支 '$CURRENT_BRANCH' 领先于远程，存在未推送的提交。${NC}"
    echo -e "${YELLOW}提示：请先执行 'git push' 将所有提交推送到远程备份。${NC}"
    exit 1
fi
echo -e "${GREEN}[3/16] 检查远程同步状态：本地与远程一致${NC}"

# 4. 检查 git-filter-repo 是否安装
if ! command -v git-filter-repo &> /dev/null; then
    echo -e "${RED}错误：未找到 git-filter-repo，请先运行 'brew install git-filter-repo'${NC}"
    exit 1
fi
echo -e "${GREEN}[4/16] 检查 git-filter-repo：已安装${NC}"

# ==============================================================================
# 【要求1】前置检查：验证是否已经合并过上游仓库，未合并则退出
# ==============================================================================
echo -e "${GREEN}[5/16] 检查是否已合并上游仓库...${NC}"
if ! git remote get-url "$UPSTREAM_NAME" &>/dev/null; then
    echo -e "${RED}错误：未配置 upstream 上游仓库！${NC}"
    echo -e "${YELLOW}请先配置上游仓库并完成合并，再执行此脚本${NC}"
    exit 1
fi

# 获取上游最新代码
git fetch "$UPSTREAM_NAME" &>/dev/null
# 检查本地是否已合并上游主分支
UPSTREAM_COMMIT=$(git rev-parse "$UPSTREAM_NAME/$UPSTREAM_BRANCH")
if ! git merge-base --is-ancestor "$UPSTREAM_COMMIT" HEAD; then
    echo -e "${RED}错误：未合并上游仓库最新代码！${NC}"
    echo -e "${YELLOW}请先执行 ./sync_upstream.sh 合并上游，再运行此脚本${NC}"
    exit 1
fi
echo -e "${GREEN}[5/16] 上游仓库已合并，检查通过${NC}"

# 6. 准备备份目录
echo -e "${GREEN}[6/16] 准备备份目录...${NC}"
if [ -d "$BACKUP_DIR" ]; then
    echo -e "${YELLOW}  检测到旧备份目录，正在删除...${NC}"
    rm -rf "$BACKUP_DIR"
fi
mkdir -p "$BACKUP_DIR"
echo -e "${GREEN}[6/16] 备份目录已创建: $BACKUP_DIR${NC}"

# 7. 备份当前的 APK 文件
echo -e "${GREEN}[7/16] 正在备份当前APK文件...${NC}"
backup_success=true

for file in "${FILES_TO_DELETE[@]}"; do
    if [ -f "$file" ]; then
        mkdir -p "$BACKUP_DIR/$(dirname "$file")"
        cp "$file" "$BACKUP_DIR/$file"
        echo -e "  ✅ 已备份: ${GREEN}$file${NC}"
    else
        echo -e "  ⚠️  跳过: ${YELLOW}文件 $file 不存在于当前工作区${NC}"
    fi
done
echo -e "${GREEN}[7/16] 备份完成${NC}"

# 8. 保存远程仓库 URL (Origin + Upstream)
echo -e "${GREEN}[8/16] 正在保存远程仓库地址...${NC}"
REMOTE_URL=$(git remote get-url $REMOTE_NAME)
if [ -z "$REMOTE_URL" ]; then
    echo -e "${RED}错误：无法获取远程仓库 '$REMOTE_NAME' 的 URL。${NC}"
    exit 1
fi
echo -e "${GREEN}[8/16] 已保存 $REMOTE_NAME 地址: $REMOTE_URL${NC}"

SAVED_UPSTREAM_URL=""
if git remote get-url $UPSTREAM_NAME &> /dev/null; then
    SAVED_UPSTREAM_URL=$(git remote get-url $UPSTREAM_NAME)
    echo -e "${GREEN}[8/16] 检测并保存现有 $UPSTREAM_NAME 地址: $SAVED_UPSTREAM_URL${NC}"
else
    echo -e "${YELLOW}[8/16] 未检测到现有 $UPSTREAM_NAME，将使用默认地址配置${NC}"
fi

# 9. 构建命令参数并展示待删文件
FILTER_ARGS=""
echo ""
echo -e "即将从历史中彻底删除以下文件："
for file in "${FILES_TO_DELETE[@]}"; do
    FILTER_ARGS="$FILTER_ARGS --path $file"
    echo -e "  - ${RED}$file${NC}"
done
echo ""

# 10. 执行清理
echo -e "${GREEN}[9/16] 正在执行 git-filter-repo 清理历史...${NC}"
git filter-repo $FILTER_ARGS --invert-paths --force

if [ $? -ne 0 ]; then
    echo -e "${RED}历史清理过程中发生错误。${NC}"
    exit 1
fi

# 11. 恢复远程仓库配置
echo -e "${GREEN}[10/16] 历史清理完成！正在恢复远程仓库配置...${NC}"
if ! git remote get-url $REMOTE_NAME &> /dev/null; then
    git remote add $REMOTE_NAME $REMOTE_URL
fi

if ! git remote get-url $UPSTREAM_NAME &> /dev/null; then
    if [ -n "$SAVED_UPSTREAM_URL" ]; then
        git remote add $UPSTREAM_NAME "$SAVED_UPSTREAM_URL"
    else
        git remote add $UPSTREAM_NAME "$DEFAULT_UPSTREAM_URL"
    fi
fi
echo -e "${GREEN}[10/16] 远程仓库配置恢复完成${NC}"

# 12. 恢复APK文件
echo -e "${GREEN}[11/16] 正在将APK从备份目录恢复...${NC}"
for file in "${FILES_TO_DELETE[@]}"; do
    if [ -f "$BACKUP_DIR/$file" ]; then
        mkdir -p "$(dirname "$file")"
        cp "$BACKUP_DIR/$file" "$file"
        echo -e "  ✅ 已恢复: ${GREEN}$file${NC}"
    fi
done
echo -e "${GREEN}[11/16] APK文件已恢复${NC}"

# 13. 清理备份目录
echo -e "${GREEN}[12/16] 清理备份目录...${NC}"
rm -rf "$BACKUP_DIR"
echo -e "${GREEN}[12/16] 备份目录已删除${NC}"

# 14. 强制推送 + 恢复分支关联
echo ""
read -p "⚠️  确认强制推送覆盖远程仓库？(输入 Y 确认): " push_confirm
if [ "$push_confirm" != "Y" ] && [ "$push_confirm" != "y" ]; then
    echo -e "${YELLOW}已取消推送，脚本退出${NC}"
    exit 0
fi

echo -e "${GREEN}[13/16] 强制推送分支和标签...${NC}"
git push $REMOTE_NAME --force --all
git push $REMOTE_NAME --force --tags
git branch --set-upstream-to=$REMOTE_NAME/$CURRENT_BRANCH $CURRENT_BRANCH
echo -e "${GREEN}[13/16] 推送完成，分支关联已恢复${NC}"

# 15. 添加并提交APK文件
echo -e "${GREEN}[14/16] 提交恢复的APK文件...${NC}"
files_to_add=()
for file in "${FILES_TO_DELETE[@]}"; do
    if [ -f "$file" ]; then
        git add "$file"
        files_to_add+=("$file")
    fi
done
if [ ${#files_to_add[@]} -gt 0 ]; then
    git commit -m "chore: 保留最新APK文件 (历史清理完成)"
    git push $REMOTE_NAME "$CURRENT_BRANCH"
    echo -e "${GREEN}[14/16] APK提交并推送成功${NC}"
else
    echo -e "${YELLOW}[14/16] 无APK文件需要提交${NC}"
fi

# ==============================================================================
# 【要求2】自动合并上游仓库，冲突全部使用自己的版本 (已修复)
# ==============================================================================
echo -e "${GREEN}[15/16] 自动合并上游仓库（保留本地版本）...${NC}"
git fetch $UPSTREAM_NAME

# 尝试合并
git merge -X ours --allow-unrelated-histories $UPSTREAM_NAME/$UPSTREAM_BRANCH -m "chore: 合并上游仓库，冲突保留本地版本"

if [ $? -eq 0 ]; then
    echo -e "${GREEN}[15/16] 上游合并完成，无冲突/冲突已自动解决${NC}"
else
    echo -e "${YELLOW}[15/16] 检测到冲突（大概率为 Add/Add 树冲突），正在强制使用本地版本覆盖...${NC}"
    
    # 强制检出所有冲突文件的本地版本 (ours)
    # 注意："-- ." 代表当前目录下所有文件
    git checkout --ours -- .
    
    # 标记所有冲突为已解决 (git add 所有未合并的文件)
    git add -u
    
    # 完成合并提交
    git commit -m "chore: 合并上游仓库，强制保留本地版本 (含自动修复 Add/Add 冲突)"
    
    echo -e "${GREEN}[15/16] 冲突已强制解决，已保留本地版本${NC}"
fi

# 推送最终结果
git push $REMOTE_NAME "$CURRENT_BRANCH"
echo -e "${GREEN}[16/16] 合并结果推送成功${NC}"

echo ""
echo -e "${GREEN}🎉 脚本全部执行完成！${NC}"
echo -e "✅ 历史清理完成 | ✅ APK已恢复 | ✅ 上游已合并（保留本地）"
