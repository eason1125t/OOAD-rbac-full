-- 业务模拟申请归属一个系统，审批服务按该归属鉴权。
CREATE TABLE simulated_request (
    id BIGINT NOT NULL AUTO_INCREMENT,
    system_id BIGINT NOT NULL,
    title VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY ix_simulated_request_system_id (system_id, id),
    CONSTRAINT fk_simulated_request_system FOREIGN KEY (system_id) REFERENCES business_system (id),
    CONSTRAINT ck_simulated_request_status CHECK (status IN ('PENDING', 'APPROVED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
