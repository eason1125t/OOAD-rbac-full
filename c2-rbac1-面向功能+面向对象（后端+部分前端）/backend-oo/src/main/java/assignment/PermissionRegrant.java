package assignment;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 记录一个组织范围对既有角色权限关系的显式重授。 */
@Entity
@Table(name = "organization_permission_regrant")
@Getter
@IdClass(PermissionRegrant.Key.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PermissionRegrant {
    @Id
    private Long organizationId;
    @Id
    private Long roleId;
    @Id
    private Long permissionId;

    /** 用组织、角色及权限唯一标识重授。 */
    public PermissionRegrant(Long organizationId, Long roleId, Long permissionId) {
        this.organizationId = organizationId;
        this.roleId = roleId;
        this.permissionId = permissionId;
    }

    /** 权限重授组合主键。 */
    public record Key(Long organizationId, Long roleId, Long permissionId) implements Serializable {
    }
}
