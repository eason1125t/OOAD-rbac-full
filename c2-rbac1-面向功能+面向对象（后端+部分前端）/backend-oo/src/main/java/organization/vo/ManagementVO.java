// 文件职责：集中声明员工、角色、权限和关系查询的公开响应。
// 协作关系：由管理 Controller 通过 CloneFactory 或显式派生映射生成。
package organization.vo;

import java.util.List;

/** 管理端目录和关联查询的公开响应。 */
public final class ManagementVO {
    /** 禁止实例化仅提供静态规则或嵌套模型的工具容器。 */
    private ManagementVO() {
    }

    /** 按主键升序返回 1 基分页。 */
    public record Page<T>(List<T> items, int page, int pageSize, long total) {
    }

    /** 员工信息和已遮盖的电话。 */
    public record Employee(Long id, String username, String name, Long organizationId, String sourceBranchName,
                           String sourceDepartmentName, String jobTitle, String phone, boolean enabled) {
    }

    /** 角色定义与归属。 */
    public record Role(Long id, String name, Long ownerOrganizationId) {
    }

    /** 运行时权限及系统归属。 */
    public record Permission(Long id, String code, String name, Long systemId, Long ownerOrganizationId) {
    }
}
