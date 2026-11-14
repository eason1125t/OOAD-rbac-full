package cn.edu.ooad.rbac.infrastructure.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** administrator_scope 表的 JPA 映射，供初始化和后续业务查询使用。 */
@Entity
@Table(name = "administrator_scope")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdministratorScope {
    @Id
    private Long employeeId;
    private String scopeType;
    private Long organizationId;

    /** 构建待保存记录，由数据库维护审计时间。 */
    public AdministratorScope(Long employeeId, String scopeType, Long organizationId) {
        this.employeeId = employeeId;
        this.scopeType = scopeType;
        this.organizationId = organizationId;
    }
}
