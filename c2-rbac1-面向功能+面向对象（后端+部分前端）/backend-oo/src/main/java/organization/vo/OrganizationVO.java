// 文件职责：返回组织目录及原表层级展示字段。
// 协作关系：由 ManagementQueryController 映射组织分页结果。
package organization.vo;

import cn.edu.xmu.clonefactory.CopyFrom;
import lombok.Getter;
import lombok.Setter;
import organization.Organization;

/** 公司及部门目录，保留原表上级与正式组织引用。 */
@Getter
@Setter
@CopyFrom(Organization.class)
public class OrganizationVO {
    private Long id;
    private String code;
    private String name;
    private Long parentId;
    private String managerName;
    private String sourceParentName;
}
