-- 账号独立保存密码哈希。业务系统默认项供登录后的工作区导航使用。
CREATE TABLE login_account (
    employee_id BIGINT NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    PRIMARY KEY (employee_id),
    CONSTRAINT fk_login_account_employee FOREIGN KEY (employee_id) REFERENCES employee (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE employee ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE business_system
    ADD COLUMN default_workspace BOOLEAN NOT NULL DEFAULT FALSE,
    ADD UNIQUE KEY uq_business_system_default_owner (
        (CASE WHEN default_workspace THEN owner_organization_id ELSE NULL END)
    );

-- 兼容已执行模块 1 初始化的本地演示库。
UPDATE business_system SET default_workspace = TRUE
WHERE code IN ('demo-company-app', 'demo-engineering-app', 'demo-finance-app', 'demo-company-b-app');
