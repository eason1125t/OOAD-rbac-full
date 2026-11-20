// 文件职责：映射业务系统目录、组织归属及默认入口标志。
// 协作关系：供工作区导航、授权上下文和申请归属查询。
package simulation;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** business_system 表的 JPA 映射，供初始化和后续业务查询使用。 */
@Entity
@Table(name = "business_system")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BusinessSystem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String code;
    private String name;
    private Long ownerOrganizationId;
    private boolean defaultWorkspace;

    /** 构建待保存记录，由数据库维护审计时间。 */
    public BusinessSystem(String code, String name, Long ownerOrganizationId) {
        this(code, name, ownerOrganizationId, false);
    }

    /** 构建业务系统，可将其配置为所属组织的默认工作区。 */
    public BusinessSystem(String code, String name, Long ownerOrganizationId, boolean defaultWorkspace) {
        this.code = code;
        this.name = name;
        this.ownerOrganizationId = ownerOrganizationId;
        this.defaultWorkspace = defaultWorkspace;
    }
}
