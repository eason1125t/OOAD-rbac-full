// 文件职责：统一计算目标系统中的实时直接与继承授权。
// 协作关系：协作 CurrentEmployeeService、WorkspaceService、继承快照、权限和组织策略仓储。
package authorization;

import assignment.EmployeeRoleRepository;
import assignment.OrganizationPermission;
import assignment.OrganizationPermissionRepository;
import assignment.RolePermissionRepository;
import authentication.CurrentEmployeeService;
import authentication.WorkspaceService;
import contract.BusinessAccessException.Reason;
import contract.BusinessAccessException;
import java.util.HashSet;
import java.util.Map;
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

    private final CurrentEmployeeService currentEmployees;
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
        return effectivePermissions(currentEmployees.requireActive(employeeId), system);
    }

    /** 自身查询接收可信身份，复用系统准入、继承及组织策略计算，不读取客户端身份。 */
    public List<Permission> effectivePermissions(Employee employee, BusinessSystem system) {
        WorkspaceService.Context context = systemContext(employee, system);
        PermissionContext target = permissionContext(employee.getId(), system, context);
        return effectivePermissions(system, target);
    }

    /** 自身单项检查与实际操作共用isAllowed；未知权限单独返回404而不改变旧业务403。 */
    public boolean hasBusinessPermission(Employee employee, BusinessSystem system, String code) {
        WorkspaceService.Context context = systemContext(employee, system);
        Permission permission = permissions.findBySystemIdAndCode(system.getId(), code)
                .orElseThrow(() -> new BusinessAccessException(Reason.PERMISSION_NOT_FOUND));
        return isAllowed(permission.getId(), permissionContext(employee.getId(), system, context));
    }

    /** 一次图快照用于来源、配置相关性及有效权限，不跨请求缓存。 */
    public SystemAuthorization describe(Employee employee, BusinessSystem system) {
        WorkspaceService.Context context = systemContext(employee, system);
        Set<Long> roots = employeeRoles.findDirectRoleIds(employee.getId());
        if (roots.isEmpty()) {
            return new SystemAuthorization(context.global(), List.of(),
                    effectivePermissions(system, permissionContext(system, context, Set.of())));
        }
        var graph = hierarchy.snapshot();
        Map<Long, Set<Long>> closures = roots.stream().collect(Collectors.toMap(
                root -> root, root -> graph.inheritedRoles(Set.of(root))));
        Set<Long> sources = closures.values().stream().flatMap(Set::stream).collect(Collectors.toSet());
        Set<Long> applicable = new HashSet<>();
        for (Long owner : rolePermissions.findSystemPermissionSourceRoleIds(system.getId(), sources)) {
            applicable.addAll(graph.dependingRoles(owner));
        }
        List<RoleSource> roleSources = sources.stream().sorted().map(roleId -> {
            List<Long> inheritedFrom = roots.stream().filter(root -> !root.equals(roleId))
                    .filter(root -> closures.get(root).contains(roleId)).sorted().toList();
            return new RoleSource(roleId, roots.contains(roleId), inheritedFrom, applicable.contains(roleId));
        }).toList();
        return new SystemAuthorization(context.global(), roleSources,
                effectivePermissions(system, permissionContext(system, context, sources)));
    }

    /** 角色来源描述不包含名称和持久化对象，由查询用例补齐公开字段。 */
    public record RoleSource(Long roleId, boolean direct, List<Long> inheritedFromRoleIds, boolean appliesToSystem) {
    }

    /** 当前系统的授权计算结果，HTTP层使用明确白名单映射。 */
    public record SystemAuthorization(boolean globalAdministrator, List<RoleSource> roles,
                                      List<Permission> effectivePermissions) {
    }

    private List<Permission> effectivePermissions(BusinessSystem system, PermissionContext target) {
        return permissions.findBySystemIdOrderByIdAsc(system.getId()).stream()
                .filter(permission -> isAllowed(permission.getId(), target)).toList();
    }

    /** 汇总系统及员工适用组织，并从直接角色根展开本次授权来源。 */
    private PermissionContext permissionContext(Long employeeId, BusinessSystem system,
                                                WorkspaceService.Context context) {
        Set<Long> roots = context.global() ? Set.of() : employeeRoles.findDirectRoleIds(employeeId);
        Set<Long> sources = roots.isEmpty() ? Set.of() : hierarchy.snapshot().inheritedRoles(roots);
        return permissionContext(system, context, sources);
    }

    /** 全局身份只受系统组织链策略约束，保持既有停用和重授语义。 */
    private PermissionContext permissionContext(BusinessSystem system, WorkspaceService.Context context,
                                                Set<Long> sources) {
        Organization owner = organizations.findById(system.getOwnerOrganizationId()).orElseThrow();
        Set<Long> affected = new HashSet<>();
        includeScope(affected, owner);
        if (!context.global()) {
            includeScope(affected, context.organization());
        }
        return new PermissionContext(context.global(), affected, sources);
    }

    /** 先拒绝停用，再判断全局身份或满足全部重开范围的同一权限来源。 */
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

    /** 本次判权的适用组织和可达直接权限来源，不跨请求缓存。 */
    private record PermissionContext(boolean global, Set<Long> organizations, Set<Long> sources) {
    }

    /** 系统准入基于服务端身份及组织范围，与登录导航一致。 */
    private WorkspaceService.Context systemContext(Long employeeId, BusinessSystem system) {
        return systemContext(currentEmployees.requireActive(employeeId), system);
    }

    /** 接收上层已读取的可信员工，不重复读取请求身份或员工记录。 */
    private WorkspaceService.Context systemContext(Employee employee, BusinessSystem system) {
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
