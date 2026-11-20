// 文件职责：查询运行时权限和直接或继承授权目录。
// 协作关系：为管理展示、实时授权与分配策略提供查询及权限行锁。
package rolepermission;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/** 对 permission 提供 Spring Data JPA 数据访问。 */
public interface PermissionRepository extends JpaRepository<Permission, Long> {
    String SCOPE = """
            (:global = true or (permission.systemId is not null and (
                exists (select 1 from BusinessSystem system where system.id = permission.systemId
                    and system.ownerOrganizationId in :ids
                    and (permission.ownerOrganizationId is null or permission.ownerOrganizationId in :ids))
                or (exists (select 1 from BusinessSystem system where system.id = permission.systemId
                        and system.ownerOrganizationId in :policyIds)
                    and exists (select 1 from OrganizationPermission policy where policy.permissionId = permission.id
                        and policy.organizationId in :policyIds and policy.status in ('OPEN', 'REOPENED')))
            )))
            """;

    /** 管理目录展示本范围的定义和已配置开放策略的业务权限。 */
    @Query("select permission from Permission permission where " + SCOPE)
    Page<Permission> findVisible(boolean global, Set<Long> ids, Set<Long> policyIds, Pageable pageable);

    /** 权限详情沿用目录的范围条件。 */
    @Query("select permission from Permission permission where permission.id = :id and " + SCOPE)
    Optional<Permission> findVisibleById(Long id, boolean global, Set<Long> ids, Set<Long> policyIds);

    /** 查询角色已有权限，应用同一可见范围。 */
    @Query("select permission from Permission permission where " + SCOPE + """
            and exists (select 1 from RolePermission link where link.roleId = :roleId and link.permissionId = permission.id)
            """)
    Page<Permission> findAssigned(Long roleId, boolean global, Set<Long> ids, Set<Long> policyIds, Pageable pageable);

    /** 按权限聚合可见来源，同一权限经多条继承路径时仍只返回一次。 */
    @Query("select permission from Permission permission where " + SCOPE + """
            and exists (select 1 from RolePermission link
                where link.roleId in :sourceRoles and link.permissionId = permission.id)
            """)
    Page<Permission> findInherited(Set<Long> sourceRoles, boolean global, Set<Long> ids, Set<Long> policyIds,
                                  Pageable pageable);

    /** 用系统与编码共同定位权限，同名权限属于各自系统。 */
    Optional<Permission> findBySystemIdAndCode(Long systemId, String code);

    /** 按系统稳定排序读取动作定义，由授权入口逐项判断当前可用性。 */
    List<Permission> findBySystemIdOrderByIdAsc(Long systemId);

    /** 串行化同一动作的组织策略与分配变更。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Permission> findLockedById(Long id);

    /** 通过系统和动作编码获取策略写入锁。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Permission> findLockedBySystemIdAndCode(Long systemId, String code);

    /** 检查拟分配角色附带的全部权限。 */
    @Query("""
            select permission from Permission permission, RolePermission link
            where permission.id = link.permissionId and link.roleId = :roleId
            """)
    List<Permission> findByRoleId(Long roleId);
}
