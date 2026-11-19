package assignment;

import java.util.List;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;

/** 对 organization_permission 提供 Spring Data JPA 数据访问。 */
public interface OrganizationPermissionRepository extends JpaRepository<OrganizationPermission, OrganizationPermission.Key> {
    /** 删除权限前检查组织策略引用。 */
    boolean existsByPermissionId(Long permissionId);

    /** 任一适用组织停用动作即可阻止审批。 */
    boolean existsByPermissionIdAndOrganizationIdInAndStatus(Long permissionId, Set<Long> organizationIds, String status);

    /** 一次读取适用于本次鉴权的所有组织策略。 */
    List<OrganizationPermission> findByPermissionIdAndOrganizationIdIn(Long permissionId, Set<Long> organizationIds);
}
