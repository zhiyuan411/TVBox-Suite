#!/bin/bash

# 项目根目录
PROJECT_ROOT=$(pwd)

# 输出目录
OUTPUT_DIR="$PROJECT_ROOT/app/build/outputs/apk"

echo "===================================="
echo "TVBox-Suite 编译打包脚本"
echo "===================================="
echo "项目根目录: $PROJECT_ROOT"
echo "输出目录: $OUTPUT_DIR"
echo "===================================="

# 使用 JDK 17 来启动 Gradle，因为 Gradle 7.5 不支持 Java 21
# 构建过程中会使用项目配置的 toolchain JDK 17
JDK17_PATH="/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home"
if [ -d "$JDK17_PATH" ]; then
    export JAVA_HOME="$JDK17_PATH"
    echo "使用 Oracle JDK 17 环境启动 Gradle: $JAVA_HOME"
else
    echo "错误: Oracle JDK 17 环境不存在，请检查路径"
    exit 1
fi

# 显示当前 Java 版本
JAVA_VERSION=$($JAVA_HOME/bin/java -version 2>&1 | awk -F '["_]' '/version/ {print $2}')
echo "当前 Java 版本: $JAVA_VERSION"
echo "注意: Gradle 构建过程会使用项目配置的工具链 JDK 17"


# 检查是否存在 gradle wrapper
if [ -f "$PROJECT_ROOT/gradlew" ]; then
    # 为 gradlew 添加执行权限
    chmod +x "$PROJECT_ROOT/gradlew"
    GRADLE_CMD="$PROJECT_ROOT/gradlew"
    echo "使用项目的 gradle wrapper"
else
    GRADLE_CMD="gradle"
    echo "使用系统安装的 gradle"
fi

# 显示当前 Java 版本
echo "当前 Java 版本:"
$JAVA_HOME/bin/java -version

# 清理之前的构建
echo -e "\n1. 清理之前的构建..."
$GRADLE_CMD clean

if [ $? -ne 0 ]; then
    echo "清理构建失败，请检查错误信息"
    exit 1
fi

# 编译并构建 debug 版本的 APK
echo -e "\n2. 编译并构建 debug 版本的 APK..."
$GRADLE_CMD assembleDebug

if [ $? -ne 0 ]; then
    echo "编译构建失败，请检查错误信息"
    exit 1
fi

# 显示构建结果
echo -e "\n3. 构建结果:"
echo "===================================="

# 检查 APK 文件是否生成
if [ -d "$OUTPUT_DIR" ]; then
    echo "生成的 APK 文件:"
    find "$OUTPUT_DIR" -name "*.apk" -type f | sort
    
    echo -e "\n4. 操作完成!"
    echo "你可以在以下目录找到生成的 APK 文件:"
    echo "$OUTPUT_DIR"
else
    echo "错误: APK 输出目录不存在"
    exit 1
fi

echo "===================================="
echo "编译打包脚本执行完成"
echo "===================================="