#!/bin/bash
# 下载 Sentinel Dashboard JAR
# 用法：cd sentinel && bash download.sh

set -e

TARGET="sentinel-dashboard.jar"
VERSION="1.8.6"
FILENAME="sentinel-dashboard-${VERSION}.jar"

if [ -f "$TARGET" ]; then
    echo "[OK] $TARGET 已存在，跳过下载"
    exit 0
fi

echo "[INFO] 正在下载 Sentinel Dashboard ${VERSION} ..."

# 优先 GitHub Releases，失败则尝试 ghproxy 镜像
URLS=(
    "https://github.com/alibaba/Sentinel/releases/download/${VERSION}/${FILENAME}"
    "https://ghproxy.net/https://github.com/alibaba/Sentinel/releases/download/${VERSION}/${FILENAME}"
)

for url in "${URLS[@]}"; do
    echo "[INFO] 尝试: $url"
    if curl -fSL --connect-timeout 15 -o "$TARGET" "$url" 2>/dev/null; then
        SIZE=$(wc -c < "$TARGET" 2>/dev/null || echo 0)
        if [ "$SIZE" -gt 1000000 ]; then
            echo "[OK] 下载成功 ($(du -h "$TARGET" | cut -f1))"
            exit 0
        fi
        rm -f "$TARGET"
    fi
done

echo "[ERROR] 自动下载失败，请手动下载 ${FILENAME}"
echo "        并重命名为 sentinel-dashboard.jar 放到 sentinel/ 目录下"
echo "        下载地址: https://github.com/alibaba/Sentinel/releases/download/${VERSION}/${FILENAME}"
exit 1
