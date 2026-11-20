// 文件职责：返回课程软件功能权限原表的只读字段。
// 协作关系：由管理查询输出，与运行时 Permission 的授权含义分开。
package organization.vo;

import cn.edu.xmu.clonefactory.CopyFrom;
import lombok.Getter;
import lombok.Setter;
import rolepermission.CatalogEntry;

/** Excel 原表功能与角色动作，保留空单元。 */
@Getter
@Setter
@CopyFrom(CatalogEntry.class)
public class CatalogVO {
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
}
