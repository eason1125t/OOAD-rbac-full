// 文件职责：查询与清理组织范围重授记录。
// 协作关系：服务组织策略变更及角色直接权限撤销。
package assignment;

import java.util.List;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** 按范围清理停用前的重授，供组织策略写入使用。 */
public interface PermissionRegrantRepository extends JpaRepository<PermissionRegrant, PermissionRegrant.Key> {
    /** 查询当前范围已重授的角色关系。 */
    List<PermissionRegrant> findByOrganizationIdAndPermissionId(Long organizationId, Long permissionId);
    /** 判断上级是否已对指定角色和组织显式恢复该动作。 */
    boolean existsByRoleIdAndPermissionIdAndOrganizationIdIn(Long roleId, Long permissionId, Set<Long> organizationIds);

    /** 清除指定组织及下属组织内该动作的重授。 */
    @Modifying
    @Query("""
            delete from PermissionRegrant grant
            where grant.permissionId = :permissionId and grant.organizationId in :organizationIds
            """)
    void deleteInScope(Long permissionId, Set<Long> organizationIds);
}
