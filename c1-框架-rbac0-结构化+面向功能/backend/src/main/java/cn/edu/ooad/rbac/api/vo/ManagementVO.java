package cn.edu.ooad.rbac.api.vo;

import java.util.List;

/** 管理端目录和关联查询的公开响应。 */
public final class ManagementVO {
    private ManagementVO() {
    }

    /** 按主键升序返回 1 基分页。 */
    public record Page<T>(List<T> items, int page, int pageSize, long total) {
    }

    /** 员工信息和已遮盖的电话。 */
    public record Employee(Long id, String username, String name, Long organizationId, String sourceBranchName,
                           String sourceDepartmentName, String jobTitle, String phone, boolean enabled) {
    }

    /** 公司及部门目录。 */
    public record Organization(Long id, String code, String name, Long parentId, String managerName,
                               String sourceParentName) {
    }

    /** 角色定义与归属。 */
    public record Role(Long id, String name, Long ownerOrganizationId) {
    }

    /** 运行时权限及系统归属。 */
    public record Permission(Long id, String code, String name, Long systemId, Long ownerOrganizationId) {
    }

    /** 原表功能权限及各角色动作。 */
    public record Catalog(Long id, String systemName, String moduleName, String featureName, String description,
                          String administratorCell, String executiveCell, String departmentManagerCell,
                          String projectManagerCell, String employeeCell) {
    }
}
