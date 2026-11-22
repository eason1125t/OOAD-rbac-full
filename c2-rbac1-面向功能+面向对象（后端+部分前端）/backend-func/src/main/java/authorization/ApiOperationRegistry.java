// 文件职责：读取、校验并幂等补齐两表中的申请 API 权限映射。
// 协作关系：查询用例读取映射，初始化用例补齐四个演示系统；实际业务不依赖映射表判权。
package authorization;

import contract.BusinessAccessException;
import contract.BusinessAccessException.Reason;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import simulation.RequestOperation;

/** 映射仅允许规范定义的 ALL 组合，损坏配置拒绝查询，不静默放行或修复。 */
@Repository
@RequiredArgsConstructor
public class ApiOperationRegistry {
    private final JdbcTemplate jdbc;

    /** 先区分不存在，再校验非空、权限引用、系统归属与规范定义。 */
    public Set<String> requiredPermissions(Long systemId, String operationId) {
        if (!exists(systemId, operationId)) {
            throw new BusinessAccessException(Reason.API_NOT_FOUND);
        }
        RequestOperation definition = RequestOperation.find(operationId)
                .orElseThrow(ApiOperationRegistry::invalidMapping);
        List<Requirement> rows = jdbc.query("""
                SELECT p.system_id, p.code
                FROM api_operation_permission link
                LEFT JOIN permission p ON p.id = link.permission_id
                WHERE link.system_id = ? AND link.operation_id = ?
                """, (row, number) -> new Requirement(row.getObject("system_id", Long.class), row.getString("code")),
                systemId, operationId);
        Set<String> codes = new HashSet<>();
        for (Requirement row : rows) {
            if (!systemId.equals(row.systemId()) || row.code() == null || !codes.add(row.code())) {
                throw invalidMapping();
            }
        }
        if (!codes.equals(Set.copyOf(definition.requiredPermissions()))) {
            throw invalidMapping();
        }
        return Set.copyOf(codes);
    }

    /** 初始化持有既有继承写锁；只补新操作，已有配置损坏时回滚而非覆盖。 */
    public void initializeDemoMappings() {
        for (String systemCode : RequestOperation.DEMO_SYSTEMS) {
            List<Long> systemIds = jdbc.queryForList("SELECT id FROM business_system WHERE code = ?", Long.class, systemCode);
            for (Long systemId : systemIds) {
                for (RequestOperation operation : RequestOperation.values()) {
                    if (!exists(systemId, operation.operationId())) {
                        jdbc.update("INSERT INTO api_operation (system_id, operation_id) VALUES (?, ?)",
                                systemId, operation.operationId());
                        for (String code : operation.requiredPermissions()) {
                            int inserted = jdbc.update("""
                                    INSERT INTO api_operation_permission (system_id, operation_id, permission_id)
                                    SELECT ?, ?, id FROM permission WHERE system_id = ? AND code = ?
                                    """, systemId, operation.operationId(), systemId, code);
                            if (inserted != 1) {
                                throw invalidMapping();
                            }
                        }
                    }
                    requiredPermissions(systemId, operation.operationId());
                }
            }
        }
    }

    /** 管理维护在删除权限前检查映射引用，返回资源冲突而不是数据库故障。 */
    public boolean referencesPermission(Long permissionId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM api_operation_permission WHERE permission_id = ?)",
                Boolean.class, permissionId));
    }

    private boolean exists(Long systemId, String operationId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM api_operation WHERE system_id = ? AND operation_id = ?)",
                Boolean.class, systemId, operationId));
    }

    private static DataRetrievalFailureException invalidMapping() {
        return new DataRetrievalFailureException("API permission mapping is inconsistent");
    }

    private record Requirement(Long systemId, String code) {
    }
}
