-- 保留部门目录原表的上级名称，供展示和重复导入核对。
-- 分公司的授权范围仍由 organization_type 和 parent_id 确定。
ALTER TABLE organization ADD COLUMN source_parent_name VARCHAR(128) NULL AFTER manager_name;
