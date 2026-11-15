package authorization;

import assignment.EmployeeRoleRepository;
import assignment.OrganizationPermission;
import assignment.OrganizationPermissionRepository;
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
import simulation.BusinessSystem;

/** 业务用例的实时鉴权入口，复用工作区范围并查询最新 RBAC0 关系。 */
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
        Organization owner = organizations.findById(system.getOwnerOrganizationId()).orElseThrow();
        Set<Long> affected = new HashSet<>();
        includeScope(affected, owner);
        if (!context.global()) {
            includeScope(affected, context.organization());
        }
        List<OrganizationPermission> policies = organizationPermissions
                .findByPermissionIdAndOrganizationIdIn(permission.getId(), affected);
        if (policies.stream().anyMatch(policy -> OrganizationPermission.DISABLED.equals(policy.getStatus()))) {
            throw new BusinessAccessException(Reason.PERMISSION_DENIED);
        }
        if (!context.global() && !hasEffectiveRole(employeeId, permission.getId(), policies)) {
            throw new BusinessAccessException(Reason.PERMISSION_DENIED);
        }
    }

    /** 同一有效角色路径须满足所有适用范围的显式重授。 */
    private boolean hasEffectiveRole(Long employeeId, Long permissionId, List<OrganizationPermission> policies) {
        Set<Long> reopened = policies.stream()
                .filter(policy -> OrganizationPermission.REOPENED.equals(policy.getStatus()))
                .map(OrganizationPermission::getOrganizationId)
                .collect(Collectors.toSet());
        return reopened.isEmpty() ? employeeRoles.hasPermission(employeeId, permissionId)
                : employeeRoles.hasRegrantedPermission(employeeId, permissionId, reopened, reopened.size());
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
