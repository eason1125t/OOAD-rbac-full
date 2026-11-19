// 统一实时授权入口，按系统和组织策略判定直接与继承权限。
package authorization;

import assignment.EmployeeRoleRepository;
import assignment.OrganizationPermission;
import assignment.OrganizationPermissionRepository;
import assignment.RolePermissionRepository;
import authentication.WorkspaceService;
import contract.BusinessAccessException;
import contract.BusinessAccessException.Reason;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import organization.Employee;
import organization.Organization;
import organization.OrganizationRepository;
import rolepermission.Permission;
import rolepermission.PermissionRepository;
import rolepermission.RoleInheritanceStore;
import simulation.BusinessSystem;

/** 业务用例的实时鉴权入口，复用工作区范围并查询最新直接分配及继承关系。 */
@Service
@RequiredArgsConstructor
public class AuthorizationService {
    public static final String EMPLOYEE_ROLE_WRITE = "employee-role:write";
    public static final String ROLE_PERMISSION_WRITE = "role-permission:write";
    public static final String MANAGEMENT_READ = "management:read";
    public static final String MANAGEMENT_WRITE = "management:write";
    public static final String ORGANIZATION_PERMISSION_WRITE = "organization-permission:write";

    private final WorkspaceService workspaces;
    private final AdministratorScopeRepository administratorScopes;
    private final OrganizationRepository organizations;
    private final PermissionRepository permissions;
    private final OrganizationPermissionRepository organizationPermissions;
    private final EmployeeRoleRepository employeeRoles;
    private final RolePermissionRepository rolePermissions;
    private final RoleInheritanceStore hierarchy;

    /** C1 分配管理动作基于管理员范围，组织权限策略只允许全局管理员。 */
    public Optional<AdministratorScope> assignmentScope(Long employeeId, String action) {
        if (ORGANIZATION_PERMISSION_WRITE.equals(action)) {
            return administratorScopes.findById(employeeId).filter(scope -> "GLOBAL".equals(scope.getScopeType()));
        }
        if (!EMPLOYEE_ROLE_WRITE.equals(action) && !ROLE_PERMISSION_WRITE.equals(action)
                && !MANAGEMENT_READ.equals(action) && !MANAGEMENT_WRITE.equals(action)) {
            return Optional.empty();
        }
        return administratorScopes.findById(employeeId);
    }

    /** 查询申请列表时只校验系统准入。 */
    public void requireSystemAccess(Long employeeId, BusinessSystem system) {
        systemContext(employeeId, system);
    }

    /** 动作必须属于目标系统，适用的组织停用早于超级管理员及角色判权。 */
    public void requireBusinessPermission(Long employeeId, BusinessSystem system, String code) {
        WorkspaceService.Context context = systemContext(employeeId, system);
        Permission permission = permissions.findBySystemIdAndCode(system.getId(), code)
                .orElseThrow(() -> new BusinessAccessException(Reason.PERMISSION_DENIED));
        if (!isAllowed(permission.getId(), permissionContext(employeeId, system, context))) {
            throw new BusinessAccessException(Reason.PERMISSION_DENIED);
        }
    }

    /** 逐动作使用与业务入口相同的判定，结果按权限 ID 升序。 */
    public List<Permission> effectivePermissions(Long employeeId, BusinessSystem system) {
        WorkspaceService.Context context = systemContext(employeeId, system);
        PermissionContext target = permissionContext(employeeId, system, context);
        return permissions.findBySystemIdOrderByIdAsc(system.getId()).stream()
                .filter(permission -> isAllowed(permission.getId(), target)).toList();
    }

    private PermissionContext permissionContext(Long employeeId, BusinessSystem system,
                                                WorkspaceService.Context context) {
        Organization owner = organizations.findById(system.getOwnerOrganizationId()).orElseThrow();
        Set<Long> affected = new HashSet<>();
        includeScope(affected, owner);
        if (context.global()) {
            return new PermissionContext(true, affected, Set.of());
        }
        includeScope(affected, context.organization());
        Set<Long> roots = employeeRoles.findDirectRoleIds(employeeId);
        Set<Long> sources = roots.isEmpty() ? Set.of() : hierarchy.snapshot().inheritedRoles(roots);
        return new PermissionContext(false, affected, sources);
    }

    private boolean isAllowed(Long permissionId, PermissionContext target) {
        List<OrganizationPermission> policies = organizationPermissions
                .findByPermissionIdAndOrganizationIdIn(permissionId, target.organizations());
        if (policies.stream().anyMatch(policy -> OrganizationPermission.DISABLED.equals(policy.getStatus()))) {
            return false;
        }
        if (target.global()) {
            return true;
        }
        if (target.sources().isEmpty()) {
            return false;
        }
        Set<Long> reopened = policies.stream()
                .filter(policy -> OrganizationPermission.REOPENED.equals(policy.getStatus()))
                .map(OrganizationPermission::getOrganizationId)
                .collect(Collectors.toSet());
        return reopened.isEmpty()
                ? rolePermissions.existsByPermissionIdAndRoleIdIn(permissionId, target.sources())
                : rolePermissions.hasRegrantedSource(permissionId, target.sources(), reopened, reopened.size());
    }

    private record PermissionContext(boolean global, Set<Long> organizations, Set<Long> sources) {
    }

    /** 系统准入基于服务端身份及组织范围，与登录导航一致。 */
    private WorkspaceService.Context systemContext(Long employeeId, BusinessSystem system) {
        Employee employee = workspaces.findActiveEmployee(employeeId)
                .orElseThrow(() -> new BusinessAccessException(Reason.AUTHENTICATION_REQUIRED));
        WorkspaceService.Context context = workspaces.context(employee);
        if (!context.canEnter(system)) {
            throw new BusinessAccessException(Reason.SYSTEM_ACCESS_DENIED);
        }
        return context;
    }

    /** 用部门及其公司匹配停用范围。 */
    private void includeScope(Set<Long> scope, Organization organization) {
        scope.add(organization.getId());
        if (organization.getParentId() != null) {
            scope.add(organization.getParentId());
        }
    }
}
