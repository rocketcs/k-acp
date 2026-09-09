ARG MAVEN_IMAGE=docker.m.daocloud.io/library/maven:3.9-eclipse-temurin-21
ARG JRE_IMAGE=docker.m.daocloud.io/library/eclipse-temurin:21-jre
# ============================================================
# 南网问数 · 语义层 MCP 服务（DM8 直连 + 校验器 + Neo4j 证据子图）
# ============================================================
FROM ${MAVEN_IMAGE} AS build

WORKDIR /build

COPY nanwang-semantic/pom.xml .
COPY docker/maven/settings.xml /root/.m2/settings.xml

# 预下载依赖（缓存 mount 加速后续构建）
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B -ntp dependency:go-offline -DskipTests

COPY nanwang-semantic/src ./src
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B -ntp package -DskipTests

# ============================================================
# 运行镜像
# ============================================================
FROM ${JRE_IMAGE}

WORKDIR /app

RUN apt-get update && \
    apt-get install -y --no-install-recommends curl && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

COPY --from=build /build/target/nanwang-semantic-*.jar app.jar

EXPOSE 8767

ENV TZ=Asia/Shanghai

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
