// 文件职责：映射课程功能权限清单的原始展示字段。
// 协作关系：由原表初始化写入，管理目录只读展示，不直接形成业务授权。
package rolepermission;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** catalog_entry 表的 JPA 映射，供初始化和后续业务查询使用。 */
@Entity
@Table(name = "catalog_entry")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CatalogEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String systemName;
    private String moduleName;
    private String featureName;
    private String description;
    private String administratorCell;
    private String executiveCell;
    private String departmentManagerCell;
    private String projectManagerCell;
    private String employeeCell;

    /** 构建待保存记录，由数据库维护审计时间。 */
    public CatalogEntry(String systemName, String moduleName, String featureName, String description,
                        String administratorCell, String executiveCell, String departmentManagerCell,
                        String projectManagerCell, String employeeCell) {
        this.systemName = systemName;
        this.moduleName = moduleName;
        this.featureName = featureName;
        this.description = description;
        this.administratorCell = administratorCell;
        this.executiveCell = executiveCell;
        this.departmentManagerCell = departmentManagerCell;
        this.projectManagerCell = projectManagerCell;
        this.employeeCell = employeeCell;
    }
}
