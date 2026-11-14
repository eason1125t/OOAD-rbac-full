package cn.edu.ooad.rbac.infrastructure.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** rbac_role 表的 JPA 映射，供初始化和后续业务查询使用。 */
@Entity
@Table(name = "rbac_role")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RbacRole {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private Long ownerOrganizationId;

    /** 构建待保存记录，由数据库维护审计时间。 */
    public RbacRole(String name, Long ownerOrganizationId) {
        this.name = name;
        this.ownerOrganizationId = ownerOrganizationId;
    }

    /** 更新角色展示名称，保留角色归属和关联。 */
    public void rename(String name) {
        this.name = name;
    }
}
