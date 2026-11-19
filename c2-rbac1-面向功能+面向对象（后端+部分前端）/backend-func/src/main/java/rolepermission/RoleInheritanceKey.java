// 继承边标识，供应用服务、仓储端口与 JPA 组合主键复用。
package rolepermission;

import java.io.Serializable;

public record RoleInheritanceKey(Long seniorRoleId, Long juniorRoleId) implements Serializable {
}
