// 文件职责：映射高级角色取得低级角色权限的直接继承边。
// 协作关系：由继承存储维护，两个角色 ID 组成关系主键。
package rolepermission;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 高级角色通过该边继承低级角色。 */
@Entity
@Table(name = "role_inheritance")
@IdClass(RoleInheritanceKey.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RoleInheritance {
    @Id
    private Long seniorRoleId;

    @Id
    private Long juniorRoleId;

    RoleInheritance(RoleInheritanceKey key) {
        seniorRoleId = key.seniorRoleId();
        juniorRoleId = key.juniorRoleId();
    }

}
