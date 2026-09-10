#!/bin/sh
set -e
# ============================================================
# runner-proxy 容器启动脚本
# 功能：
#   1. 修复 volume 挂载目录的权限（宿主机挂载覆盖镜像内属主）
#   2. 通过 -XX:MaxRAMPercentage 让 JVM 自动按容器内存上限比例分配堆
#   3. 以非 root 用户 shellproxy 启动 Java 进程
# 注意：脚本必须保持 LF 换行；CRLF 会导致 Linux 容器 shebang 解析失败
# ============================================================

# 修复 volume 挂载目录权限（失败不阻塞启动）
# /opt/tools 为 Python/Node 依赖安装卷，挂载后属主被宿主机覆盖，需一并修复
chown -R shellproxy:shellproxy /app/logs /app/.apboa /opt/tools 2>/dev/null || true

# JVM 堆占容器内存上限的百分比，默认 50%
# 剩余内存留给 shell 子进程
HEAP_PERCENTAGE=${SHELLPROXY_JAVA_HEAP_PERCENTAGE:-50.0}

# Compose 为 Python/Node 依赖设置 PATH 时可能覆盖 JRE 镜像自带的 Java PATH。
# 使用 JAVA_HOME 的绝对路径，避免 gosu 切换用户后找不到 Java。
JAVA_BIN="${JAVA_HOME:-/opt/java/openjdk}/bin/java"
if [ ! -x "$JAVA_BIN" ]; then
    JAVA_BIN="$(command -v java 2>/dev/null || true)"
fi
if [ -z "$JAVA_BIN" ] || [ ! -x "$JAVA_BIN" ]; then
    echo "错误：未找到 Java 运行时（JAVA_HOME=${JAVA_HOME:-未设置}）" >&2
    exit 127
fi

echo "runner-proxy JVM MaxRAMPercentage=${HEAP_PERCENTAGE}%"
exec gosu shellproxy "$JAVA_BIN" \
    -XX:MaxRAMPercentage=${HEAP_PERCENTAGE} \
    -XX:InitialRAMPercentage=${HEAP_PERCENTAGE} \
    -XX:+UseContainerSupport \
    -XX:+ExitOnOutOfMemoryError \
    -Djava.io.tmpdir=/tmp \
    -jar /app/app.jar
