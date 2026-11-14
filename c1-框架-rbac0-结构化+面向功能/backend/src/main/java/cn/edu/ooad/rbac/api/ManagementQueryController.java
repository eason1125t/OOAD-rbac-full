package cn.edu.ooad.rbac.api;

import cn.edu.ooad.rbac.api.dto.ManagementPageDTO;
import cn.edu.ooad.rbac.api.vo.ManagementVO;
import cn.edu.ooad.rbac.infrastructure.persistence.CatalogEntry;
import cn.edu.ooad.rbac.infrastructure.persistence.Employee;
import cn.edu.ooad.rbac.infrastructure.persistence.Organization;
import cn.edu.ooad.rbac.infrastructure.persistence.Permission;
import cn.edu.ooad.rbac.infrastructure.persistence.RbacRole;
import cn.edu.ooad.rbac.service.ManagementQueryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.data.domain.Page;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 发布管理目录及关联查询，负责分页和字段映射。 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class ManagementQueryController {
    private final ManagementQueryService management;

    /** 查看范围内员工及原表字段。 */
    @GetMapping("/api/employees")
    public ResponseEntity<ManagementVO.Page<ManagementVO.Employee>> employees(@AuthenticationPrincipal Long actor,
            @Valid @ModelAttribute ManagementPageDTO page) {
        return response(management.employees(actor, page.toPageable()),
                ManagementQueryController::employee);
    }

    /** 查询公司目录。 */
    @GetMapping("/api/companies")
    public ResponseEntity<ManagementVO.Page<ManagementVO.Organization>> companies(@AuthenticationPrincipal Long actor,
            @Valid @ModelAttribute ManagementPageDTO page) {
        return response(management.organizations(actor, "COMPANY", page.toPageable()),
                ManagementQueryController::organization);
    }

    /** 查询部门原表三列及正式组织引用。 */
    @GetMapping("/api/departments")
    public ResponseEntity<ManagementVO.Page<ManagementVO.Organization>> departments(@AuthenticationPrincipal Long actor,
            @Valid @ModelAttribute ManagementPageDTO page) {
        return response(management.organizations(actor, "DEPARTMENT", page.toPageable()),
                ManagementQueryController::organization);
    }

    /** 查询角色定义。 */
    @GetMapping("/api/roles")
    public ResponseEntity<ManagementVO.Page<ManagementVO.Role>> roles(@AuthenticationPrincipal Long actor,
            @Valid @ModelAttribute ManagementPageDTO page) {
        return response(management.roles(actor, page.toPageable()),
                ManagementQueryController::role);
    }

    /** 查询运行时权限定义。 */
    @GetMapping("/api/permissions")
    public ResponseEntity<ManagementVO.Page<ManagementVO.Permission>> permissions(@AuthenticationPrincipal Long actor,
            @Valid @ModelAttribute ManagementPageDTO page) {
        return response(management.permissions(actor, page.toPageable()),
                ManagementQueryController::permission);
    }

    /** 查询 Excel 功能权限清单。 */
    @GetMapping("/api/catalog-entries")
    public ResponseEntity<ManagementVO.Page<ManagementVO.Catalog>> catalog(@AuthenticationPrincipal Long actor,
            @Valid @ModelAttribute ManagementPageDTO page) {
        return response(management.catalog(actor, page.toPageable()),
                ManagementQueryController::catalogEntry);
    }

    /** 查询员工已分配角色。 */
    @GetMapping("/api/employees/{employeeId}/roles")
    public ResponseEntity<ManagementVO.Page<ManagementVO.Role>> employeeRoles(@AuthenticationPrincipal Long actor,
            @PathVariable @Positive Long employeeId, @Valid @ModelAttribute ManagementPageDTO page) {
        return response(management.employeeRoles(actor, employeeId, page.toPageable()),
                ManagementQueryController::role);
    }

    /** 查询角色已分配权限。 */
    @GetMapping("/api/roles/{roleId}/permissions")
    public ResponseEntity<ManagementVO.Page<ManagementVO.Permission>> rolePermissions(@AuthenticationPrincipal Long actor,
            @PathVariable @Positive Long roleId, @Valid @ModelAttribute ManagementPageDTO page) {
        return response(management.rolePermissions(actor, roleId, page.toPageable()),
                ManagementQueryController::permission);
    }

    /** 读取组织范围内的员工资料。 */
    @GetMapping("/api/employees/{employeeId}")
    public ResponseEntity<ManagementVO.Employee> employee(@AuthenticationPrincipal Long actor,
            @PathVariable @Positive Long employeeId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(employee(management.employee(actor, employeeId)));
    }

    /** 读取可见角色的详情。 */
    @GetMapping("/api/roles/{roleId}")
    public ResponseEntity<ManagementVO.Role> role(@AuthenticationPrincipal Long actor, @PathVariable @Positive Long roleId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(role(management.role(actor, roleId)));
    }

    /** 读取可见的运行时权限定义。 */
    @GetMapping("/api/permissions/{permissionId}")
    public ResponseEntity<ManagementVO.Permission> permission(@AuthenticationPrincipal Long actor,
            @PathVariable @Positive Long permissionId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(permission(management.permission(actor, permissionId)));
    }

    /** 保留分页元数据和范围内总数，管理数据禁止缓存。 */
    private static <S, T> ResponseEntity<ManagementVO.Page<T>> response(Page<S> result, Function<S, T> map) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new ManagementVO.Page<>(
                result.stream().map(map).toList(), result.getNumber() + 1, result.getSize(), result.getTotalElements()));
    }

    /** 对电话做保守遮盖，较短号码整段隐藏。 */
    static ManagementVO.Employee employee(Employee row) {
        String phone = row.getPhone();
        String masked = phone == null || phone.isBlank() ? null : "****";
        if (phone != null && phone.length() >= 9 && !phone.isBlank()) {
            masked = phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
        }
        return new ManagementVO.Employee(row.getId(), row.getUsername(), row.getName(), row.getOrganizationId(),
                row.getSourceBranchName(), row.getSourceDepartmentName(), row.getJobTitle(), masked, row.isEnabled());
    }

    /** 同时保留部门原表上级和正式组织 ID。 */
    private static ManagementVO.Organization organization(Organization row) {
        return new ManagementVO.Organization(row.getId(), row.getCode(), row.getName(), row.getParentId(),
                row.getManagerName(), row.getSourceParentName());
    }

    /** 映射角色标识与归属。 */
    static ManagementVO.Role role(RbacRole row) {
        return new ManagementVO.Role(row.getId(), row.getName(), row.getOwnerOrganizationId());
    }

    /** 保留系统标识以区分同码权限。 */
    static ManagementVO.Permission permission(Permission row) {
        return new ManagementVO.Permission(row.getId(), row.getCode(), row.getName(), row.getSystemId(),
                row.getOwnerOrganizationId());
    }

    /** 保留 Excel 中的原动作文本和空单元。 */
    private static ManagementVO.Catalog catalogEntry(CatalogEntry row) {
        return new ManagementVO.Catalog(row.getId(), row.getSystemName(), row.getModuleName(), row.getFeatureName(),
                row.getDescription(), row.getAdministratorCell(), row.getExecutiveCell(), row.getDepartmentManagerCell(),
                row.getProjectManagerCell(), row.getEmployeeCell());
    }
}
