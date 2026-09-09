#!/usr/bin/env bash
set -euo pipefail

OUT="${1:-/Users/rocket/kingsware/k-acp-kylin-images-amd64}"
mkdir -p "$OUT"

IMAGES=(
  "docker.m.daocloud.io/library/mysql:8.0"
  "docker.m.daocloud.io/library/redis:7-alpine"
  "docker.m.daocloud.io/pgvector/pgvector:pg16"
  "docker.m.daocloud.io/library/maven:3.9-eclipse-temurin-21"
  "docker.m.daocloud.io/library/eclipse-temurin:21-jre"
  "docker.m.daocloud.io/library/node:22"
  "docker.m.daocloud.io/library/nginx:alpine"
  "changchun/dm8:aug"
)

export DOCKER_DEFAULT_PLATFORM=linux/amd64
for img in "${IMAGES[@]}"; do
  echo "=== pulling $img ==="
  docker pull "$img"
  file="${OUT}/$(echo "$img" | sed 's#[/:]#_#g').tar"
  echo "=== saving $file ==="
  docker save -o "$file" "$img"
  ls -lh "$file"
done

echo "DONE: $OUT"
