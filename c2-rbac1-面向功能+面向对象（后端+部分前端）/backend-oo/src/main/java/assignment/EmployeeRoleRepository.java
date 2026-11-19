package assignment;

import java.util.List;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** 对 employee_role 提供 Spring Data JPA 数据访问。 */
public interface EmployeeRoleRepository extends JpaRepository<EmployeeRole, EmployeeRole.Key> {
    /** 调岗前确认员工没有角色关系。 */
    boolean existsByEmployeeId(Long employeeId);

    /** 删除角色前检查员工分配。 */
    boolean existsByRoleId(Long roleId);

    /** 查询授权计算的直接角色入口。 */
    @Query("select link.roleId from EmployeeRole link where link.employeeId = :employeeId")
    Set<Long> findDirectRoleIds(Long employeeId);

    /** 反向展开后统一检查这些角色实际分配到的组织。 */
    @Query("""
            select distinct employee.organizationId from Employee employee, EmployeeRole link
            where employee.id = link.employeeId and link.roleId in :roleIds
            """)
    List<Long> findAssignedOrganizationIdsForRoles(Set<Long> roleIds);
}
