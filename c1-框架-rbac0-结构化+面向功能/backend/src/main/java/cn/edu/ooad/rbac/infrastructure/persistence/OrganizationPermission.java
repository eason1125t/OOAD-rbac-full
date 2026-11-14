package cn.edu.ooad.rbac.infrastructure.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.IdClass;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** organization_permission 表的 JPA 映射，供初始化和后续业务查询使用。 */
@Entity
@Table(name = "organization_permission")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@IdClass(OrganizationPermission.Key.class)
public class OrganizationPermission {
    @Id
    private Long organizationId;
    @Id
    private Long permissionId;
    private String status;

    /** 构建待保存记录，由数据库维护审计时间。 */
    public OrganizationPermission(Long organizationId, Long permissionId, String status) {
        this.organizationId = organizationId;
        this.permissionId = permissionId;
        this.status = status;
    }

    /** organization_permission 关系两端的组合键。 */
    public record Key(Long organizationId, Long permissionId) implements java.io.Serializable {}
}
