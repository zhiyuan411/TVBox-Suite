#!/bin/bash

# ================= 配置区 =================
# 上游远程仓库名称
UPSTREAM_NAME="upstream"
# 你的主分支名称
MAIN_BRANCH="main"
# 你的远程仓库名称
ORIGIN_NAME="origin"
# =========================================

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}  自动同步上游仓库脚本${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""

# 1. 检查是否在 git 仓库根目录
if [ ! -d ".git" ]; then
    echo -e "${RED}❌ 错误：未找到 .git 目录，请在 Git 仓库根目录下执行此脚本。${NC}"
    exit 1
fi
echo -e "${GREEN}[1/6] 检查 Git 仓库：通过${NC}"

# 2. 检查工作区是否干净
if ! git diff-index --quiet HEAD --; then
    echo -e "${RED}❌ 错误：工作区有未提交的修改。${NC}"
    echo -e "${YELLOW}   请先 commit 或 stash 你的修改后再运行。${NC}"
    exit 1
fi
echo -e "${GREEN}[2/6] 检查工作区状态：干净${NC}"

# 3. 检查上游仓库是否配置
if ! git remote get-url $UPSTREAM_NAME &> /dev/null; then
    echo -e "${RED}❌ 错误：未找到远程仓库 '$UPSTREAM_NAME'。${NC}"
    echo -e "${YELLOW}   请先运行: git remote add $UPSTREAM_NAME <上游仓库地址>${NC}"
    exit 1
fi
UPSTREAM_URL=$(git remote get-url $UPSTREAM_NAME)
echo -e "${GREEN}[3/6] 检测上游仓库: $UPSTREAM_URL${NC}"

# 4. 获取上游最新代码
echo ""
echo -e "${BLUE}[4/6] 正在从上游获取最新代码 (git fetch)...${NC}"
git fetch $UPSTREAM_NAME
if [ $? -ne 0 ]; then
    echo -e "${RED}❌ 错误：获取上游代码失败，请检查网络连接。${NC}"
    exit 1
fi
echo -e "${GREEN}[4/6] 获取成功${NC}"

# 5. 切换到主分支并合并
echo ""
echo -e "${BLUE}[5/6] 正在切换到 $MAIN_BRANCH 分支并合并...${NC}"

# 确保在主分支上
git checkout $MAIN_BRANCH
if [ $? -ne 0 ]; then
    echo -e "${RED}❌ 错误：切换到 $MAIN_BRANCH 分支失败。${NC}"
    exit 1
fi

# 定义一个函数来处理合并结果和后续流程
handle_merge_result() {
    local merge_exit_code=$1
    if [ $merge_exit_code -eq 0 ]; then
        echo ""
        echo -e "${GREEN}✅ 合并成功！没有冲突。${NC}"

        # 6. 询问是否推送到远程
        echo ""
        read -p "是否立即推送到你的 $ORIGIN_NAME 仓库？(输入 Y 确认): " push_confirm

        if [ "$push_confirm" = "Y" ] || [ "$push_confirm" = "y" ]; then
            echo ""
            echo -e "${BLUE}[6/6] 正在推送...${NC}"
            git push $ORIGIN_NAME $MAIN_BRANCH
            if [ $? -eq 0 ]; then
                echo -e "${GREEN}🎉 全部完成！代码已同步到远程。${NC}"
            else
                echo -e "${RED}❌ 推送失败，请手动检查。${NC}"
            fi
        else
            echo ""
            echo -e "${YELLOW}已跳过推送。你可以稍后手动执行: git push $ORIGIN_NAME $MAIN_BRANCH${NC}"
        fi
    else
        # 合并失败处理
        echo ""
        echo -e "${RED}⚠️  检测到合并冲突！${NC}"
        echo ""
        echo -e "${YELLOW}========================================${NC}"
        echo -e "${YELLOW}  请按以下步骤手动处理：${NC}"
        echo -e "${YELLOW}========================================${NC}"
        echo -e "1. 打开上面标记为 ${RED}both modified${NC} 的文件"
        echo -e "2. 查找冲突标记 (<<<<<<<, =======, >>>>>>>)"
        echo -e "3. 手动编辑保留你需要的代码"
        echo -e "4. 编辑完成后，执行: ${GREEN}git add .${NC}"
        echo -e "5. 然后执行: ${GREEN}git commit -m 'chore: 合并上游变更'${NC}"
        echo -e "6. 最后执行: ${GREEN}git push $ORIGIN_NAME $MAIN_BRANCH${NC}"
        echo ""
        exit 1
    fi
}

# 执行第一次合并尝试
echo -e "${YELLOW}   正在执行: git merge $UPSTREAM_NAME/$MAIN_BRANCH${NC}"

# 将错误输出捕获到变量中，同时保留在终端显示
# 使用临时文件来捕获错误，兼容旧版 bash
MERGE_ERR_TMP=$(mktemp)
git merge $UPSTREAM_NAME/$MAIN_BRANCH 2>&1 | tee "$MERGE_ERR_TMP"
MERGE_EXIT_CODE=${PIPESTATUS[0]}

# 检查是否是因为"不相关历史"导致的失败
if [ $MERGE_EXIT_CODE -ne 0 ]; then
    if grep -q "refusing to merge unrelated histories" "$MERGE_ERR_TMP"; then
        echo ""
        echo -e "${YELLOW}========================================${NC}"
        echo -e "${YELLOW}  检测到特殊情况：历史不相关${NC}"
        echo -e "${YELLOW}========================================${NC}"
        echo -e "这通常是因为之前使用 git-filter-repo 清理过历史导致的。"
        echo ""
        read -p "是否允许合并不相关的历史？(输入 Y 确认，这将执行 git merge --allow-unrelated-histories): " allow_confirm

        if [ "$allow_confirm" = "Y" ] || [ "$allow_confirm" = "y" ]; then
            echo ""
            echo -e "${BLUE}   正在执行: git merge --allow-unrelated-histories $UPSTREAM_NAME/$MAIN_BRANCH${NC}"
            git merge --allow-unrelated-histories $UPSTREAM_NAME/$MAIN_BRANCH
            handle_merge_result $?
        else
            echo ""
            echo -e "${YELLOW}已取消操作。${NC}"
            rm -f "$MERGE_ERR_TMP"
            exit 0
        fi
    else
        # 其他错误，走常规冲突处理
        handle_merge_result $MERGE_EXIT_CODE
    fi
else
    # 第一次合并就成功了
    handle_merge_result 0
fi

# 清理临时文件
rm -f "$MERGE_ERR_TMP"
