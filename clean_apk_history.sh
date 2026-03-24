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
echo -e "   1. 此脚本将从历史中删除文件，但${GREEN}不会删除当前文件夹里的 apk${NC}。"
echo -e "   2. 此操作会改变历史 Commit ID，推送后团队成员需重新 Clone。"
echo ""

# 1. 检查是否在 git 仓库根目录
if [ ! -d ".git" ]; then
    echo -e "${RED}错误：未找到 .git 目录，请在 Git 仓库根目录下执行此脚本。${NC}"
    exit 1
fi
echo -e "${GREEN}[1/7] 检查 Git 仓库：通过${NC}"

# 2. 检查工作区是否干净
if ! git diff-index --quiet HEAD --; then
    echo -e "${RED}错误：工作区有未提交的修改，请先 commit 后再运行。${NC}"
    exit 1
fi
echo -e "${GREEN}[2/7] 检查工作区状态：干净${NC}"

# 3. 【新增】检查本地提交是否已推送到远程
CURRENT_BRANCH=$(git branch --show-current)
if [ -z "$CURRENT_BRANCH" ]; then
    echo -e "${RED}错误：当前处于 detached HEAD 状态，请切换到具体分支。${NC}"
    exit 1
fi

# 3.1 检查是否有上游分支
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
echo -e "${GREEN}[3/7] 检查远程同步状态：本地与远程一致${NC}"

# 4. 检查 git-filter-repo 是否安装
if ! command -v git-filter-repo &> /dev/null; then
    echo -e "${RED}错误：未找到 git-filter-repo，请先运行 'brew install git-filter-repo'${NC}"
    exit 1
fi
echo -e "${GREEN}[4/7] 检查 git-filter-repo：已安装${NC}"

# 5. 构建命令参数并展示待删文件
FILTER_ARGS=""
echo ""
echo -e "即将从历史中彻底删除以下文件："
for file in "${FILES_TO_DELETE[@]}"; do
    FILTER_ARGS="$FILTER_ARGS --path $file"
    echo -e "  - ${RED}$file${NC}"
done
echo ""

# 6. 第一次确认：是否开始清理历史
read -p "⚠️  你是否已完整备份仓库？确认开始清理历史吗？(输入 YES 确认): " confirm
if [ "$confirm" != "YES" ]; then
    echo -e "${YELLOW}操作已取消。${NC}"
    exit 0
fi

# 执行清理
echo ""
echo -e "${GREEN}[5/7] 正在执行 git-filter-repo 清理历史...${NC}"
# shellcheck disable=SC2086
git filter-repo $FILTER_ARGS --invert-paths --force

if [ $? -ne 0 ]; then
    echo -e "${RED}历史清理过程中发生错误。${NC}"
    exit 1
fi

echo -e "${GREEN}[6/7] 历史清理完成！${NC}"
echo ""
echo -e "${YELLOW}仓库清理前后大小对比：${NC}"
du -sh .git

# 7. 第二次确认：是否强制推送到远程
echo ""
echo -e "${YELLOW}========================================${NC}"
echo -e "${YELLOW}下一步：强制推送到远程仓库${NC}"
echo -e "${YELLOW}========================================${NC}"
echo -e "这将覆盖远程仓库的历史，${RED}无法撤销${NC}。"
read -p "⚠️  确认要立即强制推送到 '$REMOTE_NAME' 吗？(输入 PUSH_NOW 确认): " push_confirm

if [ "$push_confirm" == "PUSH_NOW" ]; then
    echo ""
    echo -e "${GREEN}正在强制推送所有分支到远程...${NC}"
    git push $REMOTE_NAME --force --all
    
    echo -e "${GREEN}正在强制推送所有标签到远程...${NC}"
    git push $REMOTE_NAME --force --tags
    
    echo ""
    echo -e "${GREEN}[7/7] 全部完成！${NC}"
    echo -e "请通知团队成员删除旧仓库，重新 git clone。"
else
    echo ""
    echo -e "${YELLOW}跳过推送步骤。${NC}"
    echo -e "你稍后可以手动执行以下命令推送："
    echo "   git push $REMOTE_NAME --force --all"
    echo "   git push $REMOTE_NAME --force --tags"
fi
