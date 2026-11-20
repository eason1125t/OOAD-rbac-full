// 文件职责：声明继承边的组合主键和相等性。
// 协作关系：连接 RoleInheritance JPA 映射与继承存储的幂等增删。
package rolepermission;

import java.io.Serializable;

/** 声明继承边的组合主键和相等性，连接 RoleInheritance JPA 映射与继承存储的幂等增删。 */
public record RoleInheritanceKey(Long seniorRoleId, Long juniorRoleId) implements Serializable {
}
