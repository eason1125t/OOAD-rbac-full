package organization.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 维护接口输入，仅声明可写字段，组织范围由 Service 校验。 */
public final class ManagementWriteDTO {
    private ManagementWriteDTO() {
    }

    /** 新增员工资料。 */
    public record EmployeeCreate(@NotBlank @Size(max = 128) String username, @NotBlank @Size(max = 128) String name,
                                 @NotNull @Positive Long organizationId, @Size(max = 128) String jobTitle,
                                 @Size(max = 32) String phone) {
    }

    /** 员工 PATCH，省略字段保持原值，空字符串清除可选的岗位或电话。 */
    public record EmployeePatch(@Size(max = 128) @Pattern(regexp = "(?s).*\\S.*") String name,
                                @Positive Long organizationId, @Size(max = 128) String jobTitle,
                                @Size(max = 32) String phone) {
    }

    /** 新建角色，空归属代表全局共享。 */
    public record RoleCreate(@NotBlank @Size(max = 128) String name, @Positive Long ownerOrganizationId) {
    }

    /** 新建运行时权限，保留系统与权限码作为稳定标识。 */
    public record PermissionCreate(@NotBlank @Size(max = 128) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9:_.-]*") String code,
                                   @NotBlank @Size(max = 128) String name, @Positive Long systemId,
                                   @Positive Long ownerOrganizationId) {
    }

    /** 角色和权限均只允许修改展示名称。 */
    public record Rename(@NotBlank @Size(max = 128) String name) {
    }
}
