package organization;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import organization.dto.ManagementWriteDTO;
import organization.vo.ManagementVO;
import rolepermission.Permission;
import rolepermission.RbacRole;

/** 发布员工、角色和权限写入接口，复用目录的公开响应。 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class ManagementMaintenanceController {
    private final ManagementMaintenanceService maintenance;

    /** 创建员工资料并返回资源位置。 */
    @PostMapping("/api/employees")
    public ResponseEntity<ManagementVO.Employee> createEmployee(@AuthenticationPrincipal Long actor,
            @Valid @RequestBody ManagementWriteDTO.EmployeeCreate input) {
        Employee employee = maintenance.createEmployee(actor, input.username(), input.name(), input.organizationId(),
                input.jobTitle(), input.phone());
        return created("/api/employees", employee.getId(), ManagementQueryController.employee(employee));
    }

    /** 更新已有员工的资料及可管理的组织归属。 */
    @PatchMapping("/api/employees/{employeeId}")
    public ResponseEntity<ManagementVO.Employee> updateEmployee(@AuthenticationPrincipal Long actor,
            @PathVariable @Positive Long employeeId, @Valid @RequestBody ManagementWriteDTO.EmployeePatch input) {
        Employee employee = maintenance.updateEmployee(actor, employeeId, input.name(), input.organizationId(),
                input.jobTitle(), input.phone());
        return updated(ManagementQueryController.employee(employee));
    }

    /** 停用已有员工。 */
    @DeleteMapping("/api/employees/{employeeId}")
    public ResponseEntity<Void> disableEmployee(@AuthenticationPrincipal Long actor, @PathVariable @Positive Long employeeId) {
        maintenance.disableEmployee(actor, employeeId);
        return ResponseEntity.noContent().build();
    }

    /** 新增角色定义。 */
    @PostMapping("/api/roles")
    public ResponseEntity<ManagementVO.Role> createRole(@AuthenticationPrincipal Long actor,
            @Valid @RequestBody ManagementWriteDTO.RoleCreate input) {
        RbacRole role = maintenance.createRole(actor, input.name(), input.ownerOrganizationId());
        return created("/api/roles", role.getId(), ManagementQueryController.role(role));
    }

    /** 更新角色展示名称。 */
    @PatchMapping("/api/roles/{roleId}")
    public ResponseEntity<ManagementVO.Role> renameRole(@AuthenticationPrincipal Long actor,
            @PathVariable @Positive Long roleId, @Valid @RequestBody ManagementWriteDTO.Rename input) {
        return updated(ManagementQueryController.role(maintenance.renameRole(actor, roleId, input.name())));
    }

    /** 删除已解除关联的角色。 */
    @DeleteMapping("/api/roles/{roleId}")
    public ResponseEntity<Void> deleteRole(@AuthenticationPrincipal Long actor, @PathVariable @Positive Long roleId) {
        maintenance.deleteRole(actor, roleId);
        return ResponseEntity.noContent().build();
    }

    /** 新增运行时权限定义。 */
    @PostMapping("/api/permissions")
    public ResponseEntity<ManagementVO.Permission> createPermission(@AuthenticationPrincipal Long actor,
            @Valid @RequestBody ManagementWriteDTO.PermissionCreate input) {
        Permission permission = maintenance.createPermission(actor, input.code(), input.name(), input.systemId(),
                input.ownerOrganizationId());
        return created("/api/permissions", permission.getId(), ManagementQueryController.permission(permission));
    }

    /** 更新权限展示名称。 */
    @PatchMapping("/api/permissions/{permissionId}")
    public ResponseEntity<ManagementVO.Permission> renamePermission(@AuthenticationPrincipal Long actor,
            @PathVariable @Positive Long permissionId, @Valid @RequestBody ManagementWriteDTO.Rename input) {
        return updated(ManagementQueryController.permission(maintenance.renamePermission(actor, permissionId, input.name())));
    }

    /** 删除已解除角色和组织引用的权限定义。 */
    @DeleteMapping("/api/permissions/{permissionId}")
    public ResponseEntity<Void> deletePermission(@AuthenticationPrincipal Long actor, @PathVariable @Positive Long permissionId) {
        maintenance.deletePermission(actor, permissionId);
        return ResponseEntity.noContent().build();
    }

    /** 构造带位置的创建响应。 */
    private static <T> ResponseEntity<T> created(String collection, Long id, T item) {
        return ResponseEntity.created(URI.create(collection + "/" + id)).cacheControl(CacheControl.noStore()).body(item);
    }

    /** 维护响应中可能含员工资料，禁止缓存。 */
    private static <T> ResponseEntity<T> updated(T item) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(item);
    }
}
