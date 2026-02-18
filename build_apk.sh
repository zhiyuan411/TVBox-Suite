#!/bin/bash

# 项目根目录
PROJECT_ROOT=$(pwd)

# 输出目录
OUTPUT_DIR="$PROJECT_ROOT/app/build/outputs/apk"

# Web 目录
WEB_DIR="$PROJECT_ROOT/web"

echo "===================================="
echo "TVBox-Suite 编译打包脚本"
echo "===================================="
echo "项目根目录: $PROJECT_ROOT"
echo "输出目录: $OUTPUT_DIR"
echo "Web 目录: $WEB_DIR"
echo "===================================="

# 检查当前 Java 环境
echo "当前 Java 版本:"
java -version

echo "===================================="
echo "支持的 JDK 版本范围: Java 11-17"
echo "注意: Gradle 构建过程会使用项目配置的 toolchain JDK 17"
echo "===================================="

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

# 检查是否需要执行清理
if [ "$1" == "--clean" ]; then
    echo -e "\n1. 清理之前的构建..."
    $GRADLE_CMD clean
    
    if [ $? -ne 0 ]; then
        echo "清理构建失败，请检查错误信息"
        exit 1
    fi
else
    echo -e "\n1. 跳过清理，执行增量构建..."
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
    
    # 确保 web 目录存在
    mkdir -p "$WEB_DIR"
    
    # 复制文件并强制覆盖目标
    echo -e "\n4. 复制 APK 文件到 web 目录..."
    
    # 复制 arm64GenericNormal 产物到 web/tvbox.apk
    ARM64_JAVA_APK="$OUTPUT_DIR/arm64GenericNormal/debug/TVBox_debug-arm64-generic-java.apk"
    if [ -f "$ARM64_JAVA_APK" ]; then
        cp -f "$ARM64_JAVA_APK" "$WEB_DIR/tvbox.apk"
        echo "已复制 arm64GenericNormal 产物到 web/tvbox.apk"
    else
        echo "警告: arm64GenericNormal 产物不存在"
    fi
    
    # 复制 armeabiGenericNormal 产物到 web/tvbox-old.apk
    ARMEABI_JAVA_APK="$OUTPUT_DIR/armeabiGenericNormal/debug/TVBox_debug-armeabi-generic-java.apk"
    if [ -f "$ARMEABI_JAVA_APK" ]; then
        cp -f "$ARMEABI_JAVA_APK" "$WEB_DIR/tvbox-old.apk"
        echo "已复制 armeabiGenericNormal 产物到 web/tvbox-old.apk"
    else
        echo "警告: armeabiGenericNormal 产物不存在"
    fi
    
    # 复制 arm64GenericPython 产物到 web/tvbox-py.apk
    ARM64_PYTHON_APK="$OUTPUT_DIR/arm64GenericPython/debug/TVBox_debug-arm64-generic-python.apk"
    if [ -f "$ARM64_PYTHON_APK" ]; then
        cp -f "$ARM64_PYTHON_APK" "$WEB_DIR/tvbox-py.apk"
        echo "已复制 arm64GenericPython 产物到 web/tvbox-py.apk"
    else
        echo "警告: arm64GenericPython 产物不存在"
    fi
    
    # 复制 armeabiGenericPython 产物到 web/tvbox-py-old.apk
    ARMEABI_PYTHON_APK="$OUTPUT_DIR/armeabiGenericPython/debug/TVBox_debug-armeabi-generic-python.apk"
    if [ -f "$ARMEABI_PYTHON_APK" ]; then
        cp -f "$ARMEABI_PYTHON_APK" "$WEB_DIR/tvbox-py-old.apk"
        echo "已复制 armeabiGenericPython 产物到 web/tvbox-py-old.apk"
    else
        echo "警告: armeabiGenericPython 产物不存在"
    fi
    
    echo -e "\n5. 操作完成!"
    echo "你可以在以下目录找到生成的 APK 文件:"
    echo "$OUTPUT_DIR"
    echo "已复制到 web 目录的文件:"
    ls -la "$WEB_DIR" | grep "tvbox"
else
    echo "错误: APK 输出目录不存在"
    exit 1
fi

echo "===================================="
echo "编译打包脚本执行完成"
echo "===================================="