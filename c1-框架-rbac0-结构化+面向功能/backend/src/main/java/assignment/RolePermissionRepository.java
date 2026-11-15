package assignment;

import org.springframework.data.jpa.repository.JpaRepository;

/** 对 role_permission 提供 Spring Data JPA 数据访问。 */
public interface RolePermissionRepository extends JpaRepository<RolePermission, RolePermission.Key> {
    /** 删除角色前检查其现有权限关系。 */
    boolean existsByRoleId(Long roleId);

    /** 删除权限前检查角色引用。 */
    boolean existsByPermissionId(Long permissionId);
}
