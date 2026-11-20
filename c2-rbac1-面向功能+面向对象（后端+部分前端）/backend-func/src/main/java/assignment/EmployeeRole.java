// 文件职责：映射员工与角色的直接分配关系。
// 协作关系：供 AssignmentService 写入和 AuthorizationService 查询角色根节点。
package assignment;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** employee_role 表的 JPA 映射，供初始化和后续业务查询使用。 */
@Entity
@Table(name = "employee_role")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@IdClass(EmployeeRole.Key.class)
public class EmployeeRole {
    @Id
    private Long employeeId;
    @Id
    private Long roleId;

    /** 构建待保存记录，由数据库维护审计时间。 */
    public EmployeeRole(Long employeeId, Long roleId) {
        this.employeeId = employeeId;
        this.roleId = roleId;
    }

    /** employee_role 关系两端的组合键。 */
    public record Key(Long employeeId, Long roleId) implements java.io.Serializable {
    }
}
