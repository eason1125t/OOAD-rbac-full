-- 存储直接继承与事务串行化锁，分别供角色层级查询和分配写入使用。
CREATE TABLE role_inheritance (
    senior_role_id BIGINT NOT NULL,
    junior_role_id BIGINT NOT NULL,
    PRIMARY KEY (senior_role_id, junior_role_id),
    KEY ix_role_inheritance_junior (junior_role_id),
    CONSTRAINT fk_role_inheritance_senior FOREIGN KEY (senior_role_id) REFERENCES rbac_role (id),
    CONSTRAINT fk_role_inheritance_junior FOREIGN KEY (junior_role_id) REFERENCES rbac_role (id),
    CONSTRAINT ck_role_inheritance_distinct CHECK (senior_role_id <> junior_role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE role_hierarchy_lock (
    id INT NOT NULL PRIMARY KEY,
    CONSTRAINT ck_role_hierarchy_single_lock CHECK (id = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO role_hierarchy_lock (id) VALUES (1);
