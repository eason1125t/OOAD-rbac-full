package cn.edu.ooad.rbac.infrastructure.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** employee 表的 JPA 映射，供初始化和后续业务查询使用。 */
@Entity
@Table(name = "employee")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Employee {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String username;
    private String name;
    private Long organizationId;
    private String sourceBranchName;
    private String sourceDepartmentName;
    private String jobTitle;
    private String phone;
    private boolean enabled = true;

    /** 构建待保存记录，由数据库维护审计时间。 */
    public Employee(String username, String name, Long organizationId, String sourceBranchName, String sourceDepartmentName, String jobTitle, String phone) {
        this.username = username;
        this.name = name;
        this.organizationId = organizationId;
        this.sourceBranchName = sourceBranchName;
        this.sourceDepartmentName = sourceDepartmentName;
        this.jobTitle = jobTitle;
        this.phone = phone;
    }

    /** 更新可维护资料，保留用户名及原表归属字段。 */
    public void updateProfile(String name, Long organizationId, String jobTitle, String phone) {
        if (name != null) {
            this.name = name;
        }
        if (organizationId != null) {
            this.organizationId = organizationId;
        }
        if (jobTitle != null) {
            this.jobTitle = jobTitle.isBlank() ? null : jobTitle;
        }
        if (phone != null) {
            this.phone = phone.isBlank() ? null : phone;
        }
    }

    /** 停用员工，保留历史分配和账号引用。 */
    public void disable() {
        enabled = false;
    }
}
