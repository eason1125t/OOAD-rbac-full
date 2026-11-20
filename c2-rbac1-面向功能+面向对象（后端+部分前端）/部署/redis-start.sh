#!/bin/sh
# 功能：把文件凭据写入仅当前用户可读的临时配置，避免密码出现在进程启动参数中。
set -eu
password=$(cat /run/secrets/redis_password)
case "$password" in ''|*[!0-9a-fA-F]*) echo 'Invalid Redis secret format' >&2; exit 1 ;; esac
test "${#password}" -eq 64 || { echo 'Expected a 64-character Redis secret' >&2; exit 1; }
umask 077
printf 'bind 0.0.0.0\nport 6379\nappendonly yes\ndir /data\nrequirepass %s\n' "$password" > /tmp/c2-redis.conf
exec redis-server /tmp/c2-redis.conf
