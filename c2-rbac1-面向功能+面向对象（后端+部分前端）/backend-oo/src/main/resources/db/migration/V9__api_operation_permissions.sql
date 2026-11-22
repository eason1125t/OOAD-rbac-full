-- 文件职责：新增固定ALL的API操作及所需权限两表，补齐已有四个演示系统。
-- 协作关系：新库先迁移再由InitializationService补数据；API查询与业务共用RequestOperation定义。
CREATE TABLE api_operation (
    system_id BIGINT NOT NULL,
    operation_id VARCHAR(128) COLLATE utf8mb4_0900_as_cs NOT NULL,
    PRIMARY KEY (system_id, operation_id),
    CONSTRAINT fk_api_operation_system FOREIGN KEY (system_id) REFERENCES business_system (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE TABLE api_operation_permission (
    system_id BIGINT NOT NULL,
    operation_id VARCHAR(128) COLLATE utf8mb4_0900_as_cs NOT NULL,
    permission_id BIGINT NOT NULL,
    PRIMARY KEY (system_id, operation_id, permission_id),
    CONSTRAINT fk_api_requirement_operation FOREIGN KEY (system_id, operation_id)
        REFERENCES api_operation (system_id, operation_id),
    CONSTRAINT fk_api_requirement_permission FOREIGN KEY (permission_id) REFERENCES permission (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

INSERT INTO api_operation (system_id, operation_id)
SELECT app.id, definition.operation_id
FROM business_system app
CROSS JOIN (
    SELECT 'listSystemRequests' AS operation_id
    UNION ALL SELECT 'getRequest'
    UNION ALL SELECT 'createRequest'
    UNION ALL SELECT 'updateRequest'
    UNION ALL SELECT 'withdrawRequest'
    UNION ALL SELECT 'approveRequest'
    UNION ALL SELECT 'rejectRequest'
    UNION ALL SELECT 'deleteRejectedRequest'
) definition
WHERE app.code IN ('demo-company-app', 'demo-engineering-app', 'demo-finance-app', 'demo-company-b-app');

INSERT INTO api_operation_permission (system_id, operation_id, permission_id)
SELECT operation.system_id, operation.operation_id, permission.id
FROM api_operation operation
JOIN (
    SELECT 'listSystemRequests' AS operation_id, 'request:read' AS permission_code
    UNION ALL SELECT 'getRequest', 'request:read'
    UNION ALL SELECT 'createRequest', 'request:create'
    UNION ALL SELECT 'updateRequest', 'request:read'
    UNION ALL SELECT 'updateRequest', 'request:update'
    UNION ALL SELECT 'withdrawRequest', 'request:read'
    UNION ALL SELECT 'withdrawRequest', 'request:withdraw'
    UNION ALL SELECT 'approveRequest', 'request:read'
    UNION ALL SELECT 'approveRequest', 'request:approve'
    UNION ALL SELECT 'rejectRequest', 'request:read'
    UNION ALL SELECT 'rejectRequest', 'request:reject'
    UNION ALL SELECT 'deleteRejectedRequest', 'request:read'
    UNION ALL SELECT 'deleteRejectedRequest', 'request:delete'
) definition ON definition.operation_id = operation.operation_id
JOIN permission ON permission.system_id = operation.system_id AND permission.code = definition.permission_code;
