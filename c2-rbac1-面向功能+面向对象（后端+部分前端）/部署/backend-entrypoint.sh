#!/bin/sh
# 功能：读取 Swarm/Compose 挂载凭据；把现有应用参数交给 Java，不输出秘密。
# JWT只传递外部密钥资源位置，不读取或导出私钥内容；非Web初始化不需要JWT密钥。
set -eu
if [ -n "${RBAC_DB_PASSWORD_FILE:-}" ]; then
    RBAC_DB_PASSWORD=$(cat "$RBAC_DB_PASSWORD_FILE")
    export RBAC_DB_PASSWORD
fi
if [ -n "${RBAC_REDIS_PASSWORD_FILE:-}" ]; then
    RBAC_REDIS_PASSWORD=$(cat "$RBAC_REDIS_PASSWORD_FILE")
    export RBAC_REDIS_PASSWORD
fi
: "${RBAC_DB_URL:?Missing RBAC_DB_URL}"
: "${RBAC_DB_USER:?Missing RBAC_DB_USER}"
: "${RBAC_DB_PASSWORD:?Missing database secret}"
: "${RBAC_REDIS_HOST:?Missing RBAC_REDIS_HOST}"
exec java -jar /app/rbac1.jar "$@"
