// 文件职责：映射直接角色与权限的分配关系。
// 协作关系：供 AssignmentService 维护，作为 PermissionRegrant 的权限来源。
package assignment;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** role_permission 表的 JPA 映射，供初始化和后续业务查询使用。 */
@Entity
@Table(name = "role_permission")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@IdClass(RolePermission.Key.class)
public class RolePermission {
    @Id
    private Long roleId;
    @Id
    private Long permissionId;

    /** 构建待保存记录，由数据库维护审计时间。 */
    public RolePermission(Long roleId, Long permissionId) {
        this.roleId = roleId;
        this.permissionId = permissionId;
    }

    /** role_permission 关系两端的组合键。 */
    public record Key(Long roleId, Long permissionId) implements java.io.Serializable {
    }
}
