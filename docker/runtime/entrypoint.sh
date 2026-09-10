#!/bin/sh
set -e
# ============================================================
# runner-runtime 容器启动脚本
# 功能：
#   1. 以 root 身份直接启动 Java 进程，容器内拥有完整文件系统权限
#      （可随意创建文件、下载依赖，安全边界由容器隔离承担）
#   2. 通过 -XX:MaxRAMPercentage 让 JVM 自动按容器内存上限比例分配堆
# 注意：脚本必须保持 LF 换行；CRLF 会导致 Linux 容器 shebang 解析失败
# ============================================================

# JVM 堆占容器内存上限的百分比，默认 75%
# -XX:+UseContainerSupport（Java 10+ 默认开启）让 JVM 自动读取 cgroup 限制
HEAP_PERCENTAGE=${RUNTIME_JAVA_HEAP_PERCENTAGE:-75.0}

# Compose 为 Python/Node 依赖设置 PATH 时可能覆盖 JRE 镜像自带的 Java PATH。
# 使用 JAVA_HOME 的绝对路径，避免容器启动时出现 “java: executable file not found”。
JAVA_BIN="${JAVA_HOME:-/opt/java/openjdk}/bin/java"
if [ ! -x "$JAVA_BIN" ]; then
    JAVA_BIN="$(command -v java 2>/dev/null || true)"
fi
if [ -z "$JAVA_BIN" ] || [ ! -x "$JAVA_BIN" ]; then
    echo "错误：未找到 Java 运行时（JAVA_HOME=${JAVA_HOME:-未设置}）" >&2
    exit 127
fi

echo "runner-runtime JVM MaxRAMPercentage=${HEAP_PERCENTAGE}%"
exec "$JAVA_BIN" \
    -XX:MaxRAMPercentage=${HEAP_PERCENTAGE} \
    -XX:InitialRAMPercentage=${HEAP_PERCENTAGE} \
    -XX:+UseG1GC \
    -XX:+UseContainerSupport \
    -XX:+ExitOnOutOfMemoryError \
    -jar /app/app.jar
