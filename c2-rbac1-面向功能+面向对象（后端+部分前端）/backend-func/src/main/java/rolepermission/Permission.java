// 文件职责：映射按业务系统区分的运行时动作权限。
// 协作关系：由角色权限分配引用，AuthorizationService 按系统和权限码判权。
package rolepermission;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** permission 表的 JPA 映射，供初始化和后续业务查询使用。 */
@Entity
@Table(name = "permission")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Permission {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String code;
    private String name;
    private Long systemId;
    private Long ownerOrganizationId;

    /** 构建待保存记录，由数据库维护审计时间。 */
    public Permission(String code, String name, Long systemId, Long ownerOrganizationId) {
        this.code = code;
        this.name = name;
        this.systemId = systemId;
        this.ownerOrganizationId = ownerOrganizationId;
    }

    /** 更新权限展示名称，保持系统和权限码稳定。 */
    public void rename(String name) {
        this.name = name;
    }
}
