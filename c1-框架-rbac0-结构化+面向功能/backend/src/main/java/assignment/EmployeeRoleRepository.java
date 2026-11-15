package assignment;

import java.util.List;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 对 employee_role 提供 Spring Data JPA 数据访问。 */
public interface EmployeeRoleRepository extends JpaRepository<EmployeeRole, EmployeeRole.Key> {
    /** 调岗前确认员工没有角色关系。 */
    boolean existsByEmployeeId(Long employeeId);

    /** 删除角色前检查员工分配。 */
    boolean existsByRoleId(Long roleId);

    /** 角色权限变化时校验所有已有使用者的组织范围。 */
    @Query("""
            select distinct employee.organizationId from Employee employee, EmployeeRole link
            where employee.id = link.employeeId and link.roleId = :roleId
            """)
    List<Long> findAssignedOrganizationIds(Long roleId);

    /** 查询当前直接角色是否至少有一条路径授予目标权限。 */
    @Query("""
            select case when count(employeeRole) > 0 then true else false end
            from EmployeeRole employeeRole
            where employeeRole.employeeId = :employeeId and exists (
                select 1 from RolePermission rolePermission
                where rolePermission.roleId = employeeRole.roleId and rolePermission.permissionId = :permissionId
            )
            """)
    boolean hasPermission(@Param("employeeId") Long employeeId, @Param("permissionId") Long permissionId);

    /** 查找同一角色同时满足所有已重新开放范围的授权路径。 */
    @Query("""
            select case when count(employeeRole) > 0 then true else false end
            from EmployeeRole employeeRole, RolePermission link
            where employeeRole.employeeId = :employeeId and link.roleId = employeeRole.roleId
              and link.permissionId = :permissionId and (
                  select count(regrant) from PermissionRegrant regrant
                  where regrant.roleId = link.roleId and regrant.permissionId = :permissionId
                    and regrant.organizationId in :reopened
              ) = :requiredCount
            """)
    boolean hasRegrantedPermission(Long employeeId, Long permissionId, Set<Long> reopened, long requiredCount);
}
