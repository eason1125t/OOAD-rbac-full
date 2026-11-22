// 文件职责：约束员工的直接部门和姓名/账号搜索条件。
// 协作关系：由ManagementQueryController校验，DirectoryQueryService与可见范围共同应用到列表及count。
package organization.dto;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** departmentId只表示DEPARTMENT直接归属，不扩大到后代或公司范围。 */
public record EmployeeDirectoryQueryDTO(@Positive Long departmentId, @Size(max = 128) String q) {
}
