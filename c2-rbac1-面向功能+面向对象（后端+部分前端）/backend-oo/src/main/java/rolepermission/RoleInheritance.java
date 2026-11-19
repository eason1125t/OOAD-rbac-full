// 直接继承边的 JPA 映射，与 Flyway 的组合主键保持一致。
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
