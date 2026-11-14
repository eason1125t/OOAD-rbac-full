-- 组织级重新开放要求显式重授，同一角色权限撤销会同步移除对应重授记录。
ALTER TABLE organization_permission DROP CHECK ck_organization_permission_status;
ALTER TABLE organization_permission ADD CONSTRAINT ck_organization_permission_status
    CHECK (status IN ('OPEN', 'DISABLED', 'REOPENED'));

CREATE TABLE organization_permission_regrant (
    organization_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (organization_id, role_id, permission_id),
    CONSTRAINT fk_regrant_organization_permission FOREIGN KEY (organization_id, permission_id)
        REFERENCES organization_permission (organization_id, permission_id),
    CONSTRAINT fk_regrant_role_permission FOREIGN KEY (role_id, permission_id)
        REFERENCES role_permission (role_id, permission_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
