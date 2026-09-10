ARG NODE_IMAGE=docker.m.daocloud.io/library/node:22
ARG NGINX_IMAGE=docker.m.daocloud.io/library/nginx:alpine
# ============================================================
# 阶段一：Node 构建前端
# ============================================================
# 构建镜像（由 docker-compose.yml args 传入，支持私有仓库）
FROM ${NODE_IMAGE} AS build

WORKDIR /build

# 接收构建参数（由 docker-compose.yml 的 args 传入，值来自 .env）
ARG VITE_APP_BASE_API=/web
ARG VITE_APP_CONTEXT_PATH=/web
ARG VITE_GRAPH_EXPLORER_URL=http://kg.demo.pine.kingsware.cn:6800
ARG VITE_NANWANG_GRAPH_EXPLORER_URL=/web/nanwang-graph/dm8-graph-demo-cn.html?data=sample-graph-data-cn.json&api=/api/nanwang-graph
ARG VITE_NANWANG_GRAPH_EXPLORER_API_BASE=/api/nanwang-graph
ENV VITE_APP_BASE_API=${VITE_APP_BASE_API}
ENV VITE_APP_CONTEXT_PATH=${VITE_APP_CONTEXT_PATH}
ENV VITE_GRAPH_EXPLORER_URL=${VITE_GRAPH_EXPLORER_URL}
ENV VITE_NANWANG_GRAPH_EXPLORER_URL=${VITE_NANWANG_GRAPH_EXPLORER_URL}
ENV VITE_NANWANG_GRAPH_EXPLORER_API_BASE=${VITE_NANWANG_GRAPH_EXPLORER_API_BASE}
ENV COREPACK_NPM_REGISTRY=https://registry.npmmirror.com \
    npm_config_registry=https://registry.npmmirror.com \
    NODE_OPTIONS=--max-old-space-size=4096

# 安装 pnpm
RUN corepack enable && corepack prepare pnpm@9.4.0 --activate

# 复制 NPM 私有仓库配置（在线模式为空壳，离线模式用户编辑）
COPY docker/npm/.npmrc ./

# 复制依赖文件
COPY ui/package.json ui/pnpm-lock.yaml ./

# 安装依赖 — 五层防护彻底规避 Alpine/overlay2 的 EPERM：
RUN pnpm install --frozen-lockfile

# 复制前端源码
COPY ui/ ./

# pnpm9 不支持 pnpm-workspace.yaml 的 allowBuilds 格式，构建前移除避免报错
RUN rm -f pnpm-workspace.yaml

# 构建主应用（输出到 dist-main）
RUN VITE_APP_TARGET=main pnpm run build-only:main

# 构建文档应用（输出到 dist-doc）
RUN VITE_APP_TARGET=doc pnpm run build-only:doc

# ============================================================
# 阶段二：Nginx 运行
# ============================================================
# 运行镜像（由 docker-compose.yml args 传入，支持私有仓库）
FROM ${NGINX_IMAGE}

# 复制构建产物：dist-main 内容映射到 /usr/share/nginx/html/web/
COPY --from=build /build/dist-main /usr/share/nginx/html/web

# 复制构建产物：dist-doc 内容映射到 /usr/share/nginx/html/web/doc
COPY --from=build /build/dist-doc /usr/share/nginx/html/web/doc

# 南网图谱驾驶舱与前端同源提供。API 由 Nginx 通过 Docker 网络代理，
# 浏览器不再访问宿主机 loopback 或固定宿主机 IP。
COPY docs/ontology/dm8-graph-demo-cn.html /usr/share/nginx/html/web/nanwang-graph/dm8-graph-demo-cn.html
COPY docs/ontology/sample-graph-data-cn.json /usr/share/nginx/html/web/nanwang-graph/sample-graph-data-cn.json
COPY --from=build /build/node_modules/echarts/dist/echarts.min.js /usr/share/nginx/html/web/nanwang-graph/echarts.min.js

# 复制 Nginx 配置
COPY docker/nginx/nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
