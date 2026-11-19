-- 保留已有申请内容，新增申请人、撤回与驳回状态及逻辑删除标记。
ALTER TABLE simulated_request
    DROP CHECK ck_simulated_request_status,
    ADD COLUMN applicant_id BIGINT NULL,
    ADD COLUMN deleted_at DATETIME(6) NULL,
    ADD CONSTRAINT fk_simulated_request_applicant FOREIGN KEY (applicant_id) REFERENCES employee (id),
    ADD CONSTRAINT ck_simulated_request_state CHECK (status IN ('PENDING', 'APPROVED', 'WITHDRAWN', 'REJECTED')),
    ADD CONSTRAINT ck_simulated_request_deletion CHECK (deleted_at IS NULL OR status = 'REJECTED'),
    ADD INDEX ix_simulated_request_visible (system_id, deleted_at, id);
