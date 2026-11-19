// 管理直接角色权限，并查询可达角色是否满足权限和范围重授。
package assignment;

import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** 对 role_permission 提供 Spring Data JPA 数据访问。 */
public interface RolePermissionRepository extends JpaRepository<RolePermission, RolePermission.Key> {
    /** 删除角色前检查其现有权限关系。 */
    boolean existsByRoleId(Long roleId);

    /** 删除权限前检查角色引用。 */
    boolean existsByPermissionId(Long permissionId);

    /** 直接权限来源可来自当前角色，也可来自其低级角色。 */
    boolean existsByPermissionIdAndRoleIdIn(Long permissionId, Set<Long> roleIds);

    /** 必须有一个直接持有权限的角色覆盖全部适用重授。 */
    @Query("""
            select case when count(link) > 0 then true else false end from RolePermission link
            where link.permissionId = :permissionId and link.roleId in :sourceIds and (
                select count(grant) from PermissionRegrant grant
                where grant.roleId = link.roleId and grant.permissionId = link.permissionId
                  and grant.organizationId in :reopened
            ) = :requiredCount
            """)
    boolean hasRegrantedSource(Long permissionId, Set<Long> sourceIds, Set<Long> reopened, long requiredCount);

}
