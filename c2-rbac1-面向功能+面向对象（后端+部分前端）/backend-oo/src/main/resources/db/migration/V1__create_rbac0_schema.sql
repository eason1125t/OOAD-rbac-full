-- C1 初始结构，对应 docs/数据库设计.adoc 中的概念模型和逻辑表。
-- Flyway 连接到目标数据库后执行，演示和 Excel 数据由独立的初始化流程写入。

CREATE TABLE organization (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    organization_type VARCHAR(16) NOT NULL,
    parent_id BIGINT NULL,
    manager_name VARCHAR(128) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_organization_code UNIQUE (code),
    CONSTRAINT ck_organization_parent CHECK (
        (organization_type = 'COMPANY' AND parent_id IS NULL)
        OR (organization_type = 'DEPARTMENT' AND parent_id IS NOT NULL)
    ),
    CONSTRAINT fk_organization_parent FOREIGN KEY (parent_id) REFERENCES organization (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE employee (
    id BIGINT NOT NULL AUTO_INCREMENT,
    username VARCHAR(128) NOT NULL,
    name VARCHAR(128) NOT NULL,
    organization_id BIGINT NULL,
    source_branch_name VARCHAR(128) NULL,
    source_department_name VARCHAR(128) NULL,
    job_title VARCHAR(128) NULL,
    phone VARCHAR(32) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_employee_username UNIQUE (username),
    CONSTRAINT fk_employee_organization FOREIGN KEY (organization_id) REFERENCES organization (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE rbac_role (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(128) NOT NULL,
    owner_organization_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_role_scope_name ((COALESCE(owner_organization_id, 0)), name),
    CONSTRAINT fk_role_owner FOREIGN KEY (owner_organization_id) REFERENCES organization (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE business_system (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    owner_organization_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_business_system_code UNIQUE (code),
    CONSTRAINT fk_business_system_owner FOREIGN KEY (owner_organization_id) REFERENCES organization (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE permission (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(128) NOT NULL,
    name VARCHAR(128) NOT NULL,
    system_id BIGINT NULL,
    owner_organization_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_permission_system_code ((COALESCE(system_id, 0)), code),
    CONSTRAINT fk_permission_system FOREIGN KEY (system_id) REFERENCES business_system (id),
    CONSTRAINT fk_permission_owner FOREIGN KEY (owner_organization_id) REFERENCES organization (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE employee_role (
    employee_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (employee_id, role_id),
    CONSTRAINT fk_employee_role_employee FOREIGN KEY (employee_id) REFERENCES employee (id),
    CONSTRAINT fk_employee_role_role FOREIGN KEY (role_id) REFERENCES rbac_role (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE role_permission (
    role_id BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (role_id, permission_id),
    CONSTRAINT fk_role_permission_role FOREIGN KEY (role_id) REFERENCES rbac_role (id),
    CONSTRAINT fk_role_permission_permission FOREIGN KEY (permission_id) REFERENCES permission (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE organization_permission (
    organization_id BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (organization_id, permission_id),
    CONSTRAINT ck_organization_permission_status CHECK (status IN ('OPEN', 'DISABLED')),
    CONSTRAINT fk_organization_permission_org FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_organization_permission_perm FOREIGN KEY (permission_id) REFERENCES permission (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE administrator_scope (
    employee_id BIGINT NOT NULL,
    scope_type VARCHAR(16) NOT NULL,
    organization_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (employee_id),
    CONSTRAINT ck_administrator_scope CHECK (
        (scope_type = 'GLOBAL' AND organization_id IS NULL)
        OR (scope_type = 'ORGANIZATION' AND organization_id IS NOT NULL)
    ),
    CONSTRAINT fk_administrator_employee FOREIGN KEY (employee_id) REFERENCES employee (id),
    CONSTRAINT fk_administrator_organization FOREIGN KEY (organization_id) REFERENCES organization (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE catalog_entry (
    id BIGINT NOT NULL AUTO_INCREMENT,
    system_name VARCHAR(128) NOT NULL,
    module_name VARCHAR(128) NOT NULL,
    feature_name VARCHAR(128) NOT NULL,
    description TEXT NOT NULL,
    administrator_cell VARCHAR(128) NULL,
    executive_cell VARCHAR(128) NULL,
    department_manager_cell VARCHAR(128) NULL,
    project_manager_cell VARCHAR(128) NULL,
    employee_cell VARCHAR(128) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_catalog_feature UNIQUE (system_name, module_name, feature_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
