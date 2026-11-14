package cn.edu.ooad.rbac.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/** 对 rbac_role 提供 Spring Data JPA 数据访问。 */
public interface RbacRoleRepository extends JpaRepository<RbacRole, Long> {
    /** 可见角色包含本范围角色和全局共享定义。 */
    Page<RbacRole> findByOwnerOrganizationIdInOrOwnerOrganizationIdIsNull(Set<Long> ids, Pageable pageable);

    /** 查询员工的既有角色，局部管理员可查看范围内和共享角色。 */
    @Query("""
            select role from RbacRole role
            where (:global = true or role.ownerOrganizationId is null or role.ownerOrganizationId in :ids)
              and exists (select 1 from EmployeeRole link where link.employeeId = :employeeId and link.roleId = role.id)
            """)
    Page<RbacRole> findAssigned(Long employeeId, boolean global, Set<Long> ids, Pageable pageable);

    /** 两类分配关联按角色串行写入，确保重复提交和权限检查一致。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RbacRole> findLockedById(Long id);
}
