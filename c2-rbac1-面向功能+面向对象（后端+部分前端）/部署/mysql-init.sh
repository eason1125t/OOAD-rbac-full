#!/bin/sh
# 功能：首次初始化 MySQL 时建立两库及各自用户；与管理脚本生成的 64 位十六进制 secret 配合。
set -eu
func_password=$(cat /run/secrets/func_db_password)
oo_password=$(cat /run/secrets/oo_db_password)
for value in "$func_password" "$oo_password"; do
    case "$value" in ''|*[!0-9a-fA-F]*) echo 'Invalid database secret format' >&2; exit 1 ;; esac
    test "${#value}" -eq 64 || { echo 'Expected a 64-character database secret' >&2; exit 1; }
done
MYSQL_PWD=$(cat /run/secrets/mysql_root_password)
export MYSQL_PWD
# 密码限定为十六进制，避免初始化 SQL 的引号和反斜杠歧义；不打印 SQL。
mysql --protocol=socket -uroot <<SQL
CREATE DATABASE IF NOT EXISTS ooad_rbac1_func CHARACTER SET utf8mb4;
CREATE DATABASE IF NOT EXISTS ooad_rbac1_oo CHARACTER SET utf8mb4;
CREATE USER IF NOT EXISTS 'rbac_func'@'%' IDENTIFIED BY '$func_password';
CREATE USER IF NOT EXISTS 'rbac_oo'@'%' IDENTIFIED BY '$oo_password';
GRANT ALL PRIVILEGES ON ooad_rbac1_func.* TO 'rbac_func'@'%';
GRANT ALL PRIVILEGES ON ooad_rbac1_oo.* TO 'rbac_oo'@'%';
SQL
