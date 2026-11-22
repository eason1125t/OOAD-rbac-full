// 文件职责：约束部门原表目录与正式树的筛选参数及组合。
// 协作关系：由Controller校验字段，DirectoryQueryService校验模式并构造范围内数据库查询。
package organization.dto;

import contract.BusinessAccessException;
import contract.BusinessAccessException.Reason;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 缺省保留原目录；显式tree才能使用层级条件。 */
public record DepartmentDirectoryQueryDTO(@Pattern(regexp = "directory|tree") String view,
                                         @Positive Long parentId, Boolean rootsOnly, @Size(max = 128) String q) {
    public boolean tree() {
        return "tree".equals(view);
    }

    /** rootsOnly=false也是显式条件，不能和parentId并用或用于directory。 */
    public void validateMode() {
        boolean hierarchyFilter = parentId != null || rootsOnly != null;
        if ((!tree() && hierarchyFilter) || (parentId != null && rootsOnly != null)) {
            throw new BusinessAccessException(Reason.VALIDATION_FAILED);
        }
    }
}
