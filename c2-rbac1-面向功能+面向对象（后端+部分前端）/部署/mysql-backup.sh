#!/bin/sh
# 功能：在 MySQL 所在节点导出两库及迁移历史；只读备份，不覆盖已有文件。
# 用法：sh mysql-backup.sh <明确的MySQL容器ID> <新备份文件.sql>
set -eu
test "$#" -eq 2 || { echo 'Usage: mysql-backup.sh CONTAINER NEW_BACKUP.sql' >&2; exit 1; }
umask 077
set -C
docker exec "$1" sh -c 'MYSQL_PWD=$(cat /run/secrets/mysql_root_password); export MYSQL_PWD; exec mysqldump -uroot --single-transaction --routines --events --databases ooad_rbac1_func ooad_rbac1_oo' > "$2"
echo 'Backup completed; keep the SQL private and test restoration in an isolated database.'
