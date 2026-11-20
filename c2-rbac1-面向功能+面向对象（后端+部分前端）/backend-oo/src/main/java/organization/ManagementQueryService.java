// 文件职责：按管理员可见范围分页查询目录和授权关系。
// 协作关系：协作 WorkspaceService、AuthorizationService、继承快照及 JPA 仓储。
package organization;

import authorization.AdministratorScope;
import authorization.AuthorizationService;
import contract.BusinessAccessException.Reason;
import contract.BusinessAccessException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rolepermission.CatalogEntry;
import rolepermission.CatalogEntryRepository;
import rolepermission.Permission;
import rolepermission.PermissionRepository;
import rolepermission.RbacRole;
import rolepermission.RbacRoleRepository;
import rolepermission.RoleInheritanceStore;
import simulation.BusinessSystem;
import simulation.BusinessSystemRepository;

/** 按管理范围查询目录和关系；有效权限先实时计算再分页，其余目录在数据库分页。 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ManagementQueryService {
    private final AuthorizationService authorization;
    private final EmployeeRepository employees;
    private final OrganizationRepository organizations;
    private final RbacRoleRepository roles;
    private final PermissionRepository permissions;
    private final RoleInheritanceStore hierarchy;
    private final CatalogEntryRepository catalog;
    private final BusinessSystemRepository systems;

    /** 超级管理员可核对待匹配员工，局部管理员只看有明确归属的范围内员工。 */
    public Page<Employee> employees(Long operator, Pageable page) {
        Scope scope = scope(operator);
        return scope.global() ? employees.findAll(page) : employees.findByOrganizationIdIn(scope.ids(), page);
    }

    /** 查询可见员工的单条资料。 */
    public Employee employee(Long operator, Long id) {
        return requireEmployeeVisible(id, scope(operator));
    }

    /** 查询可见角色定义。 */
    public RbacRole role(Long operator, Long id) {
        return requireRoleVisible(id, scope(operator));
    }

    /** 单条权限与列表使用相同的可见条件。 */
    public Permission permission(Long operator, Long id) {
        Scope scope = scope(operator);
        if (!permissions.existsById(id)) {
            throw missing();
        }
        return permissions.findVisibleById(id, scope.global(), scope.ids(), scope.policyIds())
                .orElseThrow(ManagementQueryService::denied);
    }

    /** 根据查询的固定组织类型和管理员范围返回目录。 */
    public Page<Organization> organizations(Long operator, String type, Pageable page) {
        Scope scope = scope(operator);
        if ("DEPARTMENT".equals(type)) {
            return scope.global() ? organizations.findDepartmentDirectory(page)
                    : organizations.findDepartmentDirectoryIn(scope.ids(), page);
        }
        return scope.global() ? organizations.findByOrganizationType(type, page)
                : organizations.findByOrganizationTypeAndIdIn(type, scope.ids(), page);
    }

    /** 分页返回可见的运行时角色。 */
    public Page<RbacRole> roles(Long operator, Pageable page) {
        Scope scope = scope(operator);
        return scope.global() ? roles.findAll(page) : roles.findByOwnerOrganizationIdInOrOwnerOrganizationIdIsNull(
                scope.ids(), page);
    }

    /** 分页查看范围内的运行时权限定义。 */
    public Page<Permission> permissions(Long operator, Pageable page) {
        Scope scope = scope(operator);
        return permissions.findVisible(scope.global(), scope.ids(), scope.policyIds(), page);
    }

    /** 原表功能目录供所有管理员对照，不包含员工信息或实际授权关系。 */
    public Page<CatalogEntry> catalog(Long operator, Pageable page) {
        requireAdministrator(operator);
        return catalog.findAll(page);
    }

    /** 确认目标员工在管理范围内，再读取其可见的角色分配。 */
    public Page<RbacRole> employeeRoles(Long operator, Long employeeId, Pageable page) {
        Scope scope = scope(operator);
        requireEmployeeVisible(employeeId, scope);
        return roles.findAssigned(employeeId, scope.global(), scope.ids(), page);
    }

    /** 直接继承角色与角色目录采用同一管理读取范围。 */
    public Page<RbacRole> roleParents(Long operator, Long roleId, Pageable page) {
        requireRoleVisible(roleId, scope(operator));
        return roles.findParents(roleId, page);
    }

    /** 管理员可见范围和目标员工的实际权限分别校验。 */
    public Page<Permission> effectivePermissions(Long operator, Long employeeId, String systemCode, Pageable page) {
        Scope scope = scope(operator);
        requireEmployeeVisible(employeeId, scope);
        BusinessSystem system = systems.findByCode(systemCode)
                .orElseThrow(() -> new BusinessAccessException(Reason.SYSTEM_NOT_FOUND));
        if (!scope.global() && !scope.policyIds().contains(system.getOwnerOrganizationId())) {
            throw denied();
        }
        List<Permission> effective = authorization.effectivePermissions(employeeId, system);
        long offset = page.getOffset();
        if (offset >= effective.size()) {
            return new PageImpl<>(List.of(), page, effective.size());
        }
        int end = (int) Math.min(offset + page.getPageSize(), effective.size());
        return new PageImpl<>(effective.subList((int) offset, end), page, effective.size());
    }

    /** 确认角色可见，再分页返回该角色关联的可见权限。 */
    public Page<Permission> rolePermissions(Long operator, Long roleId, Pageable page) {
        Scope scope = scope(operator);
        requireRoleVisible(roleId, scope);
        return permissions.findAssigned(roleId, scope.global(), scope.ids(), scope.policyIds(), page);
    }

    /** 查询继承来源的配置权限，当前角色的直接分配另行返回。 */
    public Page<Permission> inheritedRolePermissions(Long operator, Long roleId, Pageable page) {
        Scope scope = scope(operator);
        requireRoleVisible(roleId, scope);
        Set<Long> sources = new HashSet<>(hierarchy.snapshot().inheritedRoles(Set.of(roleId)));
        sources.remove(roleId);
        if (sources.isEmpty()) {
            return Page.empty(page);
        }
        return permissions.findInherited(sources, scope.global(), scope.ids(), scope.policyIds(), page);
    }

    /** 关联和详情入口共用员工可见范围。 */
    private Employee requireEmployeeVisible(Long id, Scope scope) {
        Employee employee = employees.findById(id).orElseThrow(ManagementQueryService::missing);
        Long owner = employee.getOrganizationId();
        if (!scope.global() && (owner == null || !scope.ids().contains(owner))) {
            throw denied();
        }
        return employee;
    }

    /** 关联和详情入口共用角色可见范围。 */
    private RbacRole requireRoleVisible(Long id, Scope scope) {
        RbacRole role = roles.findById(id).orElseThrow(ManagementQueryService::missing);
        Long owner = role.getOwnerOrganizationId();
        if (!scope.global() && owner != null && !scope.ids().contains(owner)) {
            throw denied();
        }
        return role;
    }

    /** 读取当前管理员配置，公司范围含其直接下属部门。 */
    private Scope scope(Long operator) {
        AdministratorScope admin = requireAdministrator(operator);
        if ("GLOBAL".equals(admin.getScopeType())) {
            return new Scope(true, Set.of(), Set.of());
        }
        Organization owner = organizations.findById(admin.getOrganizationId()).orElseThrow();
        Set<Long> visible = new HashSet<>();
        visible.add(owner.getId());
        if ("COMPANY".equals(owner.getOrganizationType())) {
            organizations.findByParentId(owner.getId()).forEach(child -> visible.add(child.getId()));
        }
        Set<Long> policyIds = new HashSet<>(visible);
        if (owner.getParentId() != null) {
            policyIds.add(owner.getParentId());
        }
        return new Scope(false, Set.copyOf(visible), Set.copyOf(policyIds));
    }

    /** 管理员资格以本次请求查询的数据库数据为准。 */
    private AdministratorScope requireAdministrator(Long operator) {
        return authorization.assignmentScope(operator, AuthorizationService.MANAGEMENT_READ)
                .orElseThrow(ManagementQueryService::denied);
    }

    /** 返回管理查询权限拒绝。 */
    private static BusinessAccessException denied() {
        return new BusinessAccessException(Reason.PERMISSION_DENIED, "无权查看该管理数据");
    }

    /** 返回管理查询的资源缺失。 */
    private static BusinessAccessException missing() {
        return new BusinessAccessException(Reason.RESOURCE_NOT_FOUND);
    }

    /** 查询所用的组织 ID，分支范围同时用于数据库筛选和总数。 */
    private record Scope(boolean global, Set<Long> ids, Set<Long> policyIds) {
    }
}
