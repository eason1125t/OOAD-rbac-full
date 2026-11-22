// 文件职责：声明普通员工最小目录投影和正式组织树节点。
// 协作关系：由ManagementQueryController显式映射；管理员旧字段和CloneFactory原表投影保持独立。
package organization.vo;

/** 目录公开字段白名单，派生hasChildren不通过实体全字段复制。 */
public final class DirectoryVO {
    private DirectoryVO() {
    }

    public record Employee(Long id, String username, String name, Long organizationId) {
    }

    public record OrganizationNode(Long id, String code, String name, Long parentId,
                                   String organizationType, boolean hasChildren) {
    }
}
