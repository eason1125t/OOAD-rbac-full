// 文件职责：映射角色名称及所属组织。
// 协作关系：由管理维护创建重命名，被分配、继承和实时授权共同引用。
package rolepermission;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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
