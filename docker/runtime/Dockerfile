ARG MAVEN_IMAGE=docker.m.daocloud.io/library/maven:3.9-eclipse-temurin-21
ARG JRE_IMAGE=docker.m.daocloud.io/library/eclipse-temurin:21-jre
ARG NODE_IMAGE=docker.m.daocloud.io/library/node:22-bookworm-slim
# ============================================================
# 阶段一：Maven 构建
# ============================================================
# 构建镜像（由 docker-compose args 传入，支持私有仓库）
FROM ${MAVEN_IMAGE} AS build

WORKDIR /build

# 复制根 pom 及各模块 pom（保留目录结构），利用 Docker 缓存层加速
# Maven 解析根 pom 时需要所有 <module> 目录存在，否则报错
COPY pom.xml .
# 一级模块 pom
COPY biz/pom.xml ./biz/
COPY common-base/pom.xml ./common-base/
COPY common/pom.xml ./common/
COPY heartbeat/pom.xml ./heartbeat/
COPY engine/pom.xml ./engine/
COPY gateway/pom.xml ./gateway/
COPY scheduler/pom.xml ./scheduler/
COPY workflow/pom.xml ./workflow/
COPY runner-console/pom.xml ./runner-console/
COPY runner-runtime/pom.xml ./runner-runtime/
COPY runner-proxy/pom.xml ./runner-proxy/
COPY runner-file/pom.xml ./runner-file/
COPY runner-websocket/pom.xml ./runner-websocket/
# 二级模块 pom（biz 子模块）
COPY biz/biz-a2a/pom.xml ./biz/biz-a2a/
COPY biz/biz-account/pom.xml ./biz/biz-account/
COPY biz/biz-agent/pom.xml ./biz/biz-agent/
COPY biz/biz-hook/pom.xml ./biz/biz-hook/
COPY biz/biz-knowledge/pom.xml ./biz/biz-knowledge/
COPY biz/biz-longterm/pom.xml ./biz/biz-longterm/
COPY biz/biz-mcp/pom.xml ./biz/biz-mcp/
COPY biz/biz-model/pom.xml ./biz/biz-model/
COPY biz/biz-params/pom.xml ./biz/biz-params/
COPY biz/biz-prompt/pom.xml ./biz/biz-prompt/
COPY biz/biz-resource/pom.xml ./biz/biz-resource/
COPY biz/biz-sensitive/pom.xml ./biz/biz-sensitive/
COPY biz/biz-sk/pom.xml ./biz/biz-sk/
COPY biz/biz-skill/pom.xml ./biz/biz-skill/
COPY biz/biz-studio/pom.xml ./biz/biz-studio/
COPY biz/biz-tool/pom.xml ./biz/biz-tool/
COPY biz/biz-cache/pom.xml ./biz/biz-cache/
COPY biz/biz-mq/pom.xml ./biz/biz-mq/
COPY biz/biz-datasource/pom.xml ./biz/biz-datasource/
COPY biz/biz-workflow/pom.xml ./biz/biz-workflow/
COPY biz/biz-cache/pom.xml ./biz/biz-cache/
COPY biz/biz-datasource/pom.xml ./biz/biz-datasource/
COPY biz/biz-gateway/pom.xml ./biz/biz-gateway/
COPY biz/biz-mq/pom.xml ./biz/biz-mq/
COPY biz/biz-channel/pom.xml ./biz/biz-channel/
COPY biz/biz-dashboard/pom.xml ./biz/biz-dashboard/

# 复制 Maven 私有仓库配置
COPY docker/maven/settings.xml /root/.m2/settings.xml

# 预下载依赖
RUN mvn dependency:go-offline -pl runner-runtime -am -DskipTests -q

# 复制源码并构建
COPY . .
RUN mvn package -pl runner-runtime -am -DskipTests -q

# 国内 Node.js 运行时，避免运行镜像访问 NodeSource
FROM ${NODE_IMAGE} AS node-runtime

# ============================================================
# 阶段二：运行镜像
# ============================================================
# 运行镜像（由 docker-compose args 传入，支持私有仓库）
FROM ${JRE_IMAGE}

WORKDIR /app

# 仅复制 Node.js、npm 及其运行库，不在运行镜像中执行 NodeSource 安装脚本
COPY --from=node-runtime /usr/local/bin/node /usr/local/bin/node
COPY --from=node-runtime /usr/local/bin/npm /usr/local/bin/npm
COPY --from=node-runtime /usr/local/bin/npx /usr/local/bin/npx
COPY --from=node-runtime /usr/local/lib/node_modules /usr/local/lib/node_modules
RUN ln -sfn /usr/local/lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm && \
    ln -sfn /usr/local/lib/node_modules/npm/bin/npx-cli.js /usr/local/bin/npx

# 以 root 运行：容器内拥有完整文件系统权限（可随意创建文件、下载依赖）
# 安全边界由容器隔离承担；apboa-proxy 仍保持非 root 降权以隔离不可信命令执行

# 安装 Python 3 + Node.js 22（供容器内下载/执行依赖）
RUN set -eux; \
    for source_file in /etc/apt/sources.list /etc/apt/sources.list.d/*; do \
        if [ -f "$source_file" ]; then \
            sed -i \
                -e 's|deb.debian.org|mirrors.aliyun.com|g' \
                -e 's|security.debian.org|mirrors.aliyun.com|g' \
                -e 's|ports.ubuntu.com/ubuntu-ports|mirrors.aliyun.com/ubuntu-ports|g' \
                -e 's|archive.ubuntu.com/ubuntu|mirrors.aliyun.com/ubuntu|g' \
                -e 's|security.ubuntu.com/ubuntu|mirrors.aliyun.com/ubuntu|g' \
                "$source_file"; \
        fi; \
    done; \
    apt-get update && \
    apt-get install -y --no-install-recommends \
        python3 \
        python3-pip \
        python3-venv \
        curl \
        ca-certificates \
    && ln -sf /usr/bin/python3 /usr/bin/python \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

# 验证运行时
RUN python --version && node --version && npm --version

# 复制构建产物
COPY --from=build /build/runner-runtime/target/runner-runtime-*.jar app.jar

# 创建数据目录（logback 相对路径依赖 /app 可写）
RUN mkdir -p /app/data /app/logs /app/.apboa

EXPOSE 3061

ENV TZ=Asia/Shanghai
# 追加 npm-global bin 到 PATH（不覆盖基础镜像的 Java 路径）
ENV PATH="/opt/tools/npm-global/bin:${PATH}"
# ---------- 启动脚本 ----------
# 复制 entrypoint.sh（动态 JVM 堆计算 + root 直接启动）
COPY docker/runtime/entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

ENTRYPOINT ["/app/entrypoint.sh"]
