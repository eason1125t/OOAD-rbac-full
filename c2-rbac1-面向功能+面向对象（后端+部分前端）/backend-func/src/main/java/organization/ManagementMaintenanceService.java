// 文件职责：维护 C2 员工、角色及权限定义并保护已有引用。
// 协作关系：协作 AuthorizationService、继承写锁和各实体仓储。
package organization;

import assignment.EmployeeRoleRepository;
import assignment.OrganizationPermissionRepository;
import assignment.RolePermissionRepository;
import authorization.AdministratorScope;
import authorization.AdministratorScopeRepository;
import authorization.AuthorizationService;
import contract.BusinessAccessException.Reason;
import contract.BusinessAccessException;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import rolepermission.Permission;
import rolepermission.PermissionRepository;
import rolepermission.RbacRole;
import rolepermission.RbacRoleRepository;
import rolepermission.RoleInheritanceStore;
import simulation.BusinessSystem;
import simulation.BusinessSystemRepository;

/** 维护 C2 员工、角色及权限定义，与分配和继承写入共用锁及引用保护。 */
@Service
@RequiredArgsConstructor
@Transactional(isolation = Isolation.READ_COMMITTED)
public class ManagementMaintenanceService {
    private final AuthorizationService authorization;
    private final AdministratorScopeRepository administratorScopes;
    private final EmployeeRepository employees;
    private final EmployeeRoleRepository employeeRoles;
    private final OrganizationRepository organizations;
    private final BusinessSystemRepository systems;
    private final RbacRoleRepository roles;
    private final RoleInheritanceStore hierarchy;
    private final PermissionRepository permissions;
    private final RolePermissionRepository rolePermissions;
    private final OrganizationPermissionRepository organizationPermissions;

    /** 创建有明确归属的员工资料，账号凭据由登录模块维护。 */
    public Employee createEmployee(Long actor, String username, String name, Long organizationId, String jobTitle,
                                   String phone) {
        requireManaged(scope(actor), organization(organizationId));
        try {
            return employees.save(new Employee(username, name, organizationId, null, null, jobTitle, phone));
        } catch (DataIntegrityViolationException failure) {
            throw conflict("用户名已存在");
        }
    }

    /** 变更归属前检查两端范围及现有角色和管理员配置。 */
    public Employee updateEmployee(Long actor, Long id, String name, Long organizationId, String jobTitle, String phone) {
        AdministratorScope scope = scope(actor);
        Employee employee = lockedEmployee(id);
        AdministratorScope targetAdmin = requireEmployeeAccess(scope, employee);
        if (organizationId != null && !Objects.equals(organizationId, employee.getOrganizationId())) {
            requireManaged(scope, organization(organizationId));
            if (targetAdmin != null || employeeRoles.existsByEmployeeId(id)) {
                throw new BusinessAccessException(Reason.ASSIGNMENT_CONFLICT, "调岗前请先解除角色和管理员配置");
            }
        }
        employee.updateProfile(name, organizationId, jobTitle, phone);
        return employee;
    }

    /** 停用在下一次受保护请求生效，重复停用幂等。 */
    public void disableEmployee(Long actor, Long id) {
        AdministratorScope scope = scope(actor);
        Employee employee = lockedEmployee(id);
        requireEmployeeAccess(scope, employee);
        employee.disable();
    }

    /** 创建本范围或由超级管理员维护的共享角色。 */
    public RbacRole createRole(Long actor, String name, Long organizationId) {
        requireManaged(scope(actor), organization(organizationId));
        try {
            return roles.save(new RbacRole(name, organizationId));
        } catch (DataIntegrityViolationException failure) {
            throw conflict("相同组织范围内的角色名称已存在");
        }
    }

    /** 更新角色名称，数据库保证范围内唯一。 */
    public RbacRole renameRole(Long actor, Long id, String name) {
        AdministratorScope scope = scope(actor);
        RbacRole role = lockedRole(id);
        requireManaged(scope, organization(role.getOwnerOrganizationId()));
        role.rename(name);
        try {
            return roles.saveAndFlush(role);
        } catch (DataIntegrityViolationException failure) {
            throw conflict("相同组织范围内的角色名称已存在");
        }
    }

    /** 员工、权限分配或任一方向继承边仍引用角色时拒绝删除。 */
    public void deleteRole(Long actor, Long id) {
        AdministratorScope scope = scope(actor);
        hierarchy.lockWrites();
        RbacRole role = lockedRole(id);
        requireManaged(scope, organization(role.getOwnerOrganizationId()));
        if (employeeRoles.existsByRoleId(id) || rolePermissions.existsByRoleId(id)) {
            throw conflict("请先解除角色的员工和权限分配");
        }
        if (hierarchy.references(id)) {
            throw conflict("请先解除该角色的继承关系");
        }
        roles.delete(role);
    }

    /** 局部管理员只能在自己管理的系统内创建本范围权限。 */
    public Permission createPermission(Long actor, String code, String name, Long systemId, Long organizationId) {
        AdministratorScope scope = scope(actor);
        requireManaged(scope, organization(organizationId));
        if (systemId != null) {
            BusinessSystem system = systems.findById(systemId).orElseThrow(ManagementMaintenanceService::missing);
            requireManaged(scope, organization(system.getOwnerOrganizationId()));
            if (organizationId != null && !contains(organization(organizationId), system.getOwnerOrganizationId())) {
                throw conflict("权限归属须位于业务系统范围内");
            }
        } else if (!isGlobal(scope)) {
            throw denied();
        }
        try {
            return permissions.save(new Permission(code, name, systemId, organizationId));
        } catch (DataIntegrityViolationException failure) {
            throw conflict("该系统中的权限码已存在");
        }
    }

    /** 更新权限展示名称，保留权限身份和现有授权。 */
    public Permission renamePermission(Long actor, Long id, String name) {
        AdministratorScope scope = scope(actor);
        Permission permission = lockedPermission(id);
        requireManaged(scope, organization(permission.getOwnerOrganizationId()));
        permission.rename(name);
        return permission;
    }

    /** 角色分配或组织策略仍引用权限时拒绝物理删除。 */
    public void deletePermission(Long actor, Long id) {
        AdministratorScope scope = scope(actor);
        Permission permission = lockedPermission(id);
        requireManaged(scope, organization(permission.getOwnerOrganizationId()));
        if (rolePermissions.existsByPermissionId(id) || organizationPermissions.existsByPermissionId(id)) {
            throw conflict("该权限仍被角色分配或组织策略引用");
        }
        permissions.delete(permission);
    }

    /** 局部管理员不能通过员工维护影响其范围外的管理员。 */
    private AdministratorScope requireEmployeeAccess(AdministratorScope scope, Employee employee) {
        requireManaged(scope, organization(employee.getOrganizationId()));
        AdministratorScope target = administratorScopes.findById(employee.getId()).orElse(null);
        if (target != null) {
            requireManaged(scope, organization(target.getOrganizationId()));
        }
        return target;
    }

    /** 事务内以最新管理员配置复核写入资格。 */
    private AdministratorScope scope(Long actor) {
        return authorization.assignmentScope(actor, AuthorizationService.MANAGEMENT_WRITE)
                .orElseThrow(ManagementMaintenanceService::denied);
    }

    /** 超级管理员维护共享定义，局部管理员限其组织范围。 */
    private void requireManaged(AdministratorScope scope, Organization target) {
        if (!isGlobal(scope) && (target == null || !contains(target, scope.getOrganizationId()))) {
            throw denied();
        }
    }

    /** C1 管理范围覆盖组织自身和直属部门。 */
    private boolean contains(Organization target, Long scopeId) {
        return Objects.equals(target.getId(), scopeId) || Objects.equals(target.getParentId(), scopeId);
    }

    /** 全局范围来源于数据库管理员记录。 */
    private boolean isGlobal(AdministratorScope scope) {
        return "GLOBAL".equals(scope.getScopeType());
    }

    /** 查找正式归属，空值保留待匹配或全局的语义。 */
    private Organization organization(Long id) {
        return id == null ? null : organizations.findById(id).orElseThrow(ManagementMaintenanceService::missing);
    }

    /** 锁定员工行，角色分配使用相同的锁。 */
    private Employee lockedEmployee(Long id) {
        return employees.findLockedById(id).orElseThrow(ManagementMaintenanceService::missing);
    }

    /** 锁定角色行，确保删除和授予有一致的顺序。 */
    private RbacRole lockedRole(Long id) {
        return roles.findLockedById(id).orElseThrow(ManagementMaintenanceService::missing);
    }

    /** 锁定权限行，使引用检查和组织策略写入相互协调。 */
    private Permission lockedPermission(Long id) {
        return permissions.findLockedById(id).orElseThrow(ManagementMaintenanceService::missing);
    }

    /** 统一维护接口的范围错误。 */
    private static BusinessAccessException denied() {
        return new BusinessAccessException(Reason.PERMISSION_DENIED, "无权维护该资源");
    }

    /** 统一资源不存在的响应。 */
    private static BusinessAccessException missing() {
        return new BusinessAccessException(Reason.RESOURCE_NOT_FOUND, "员工、组织、系统、角色或权限不存在");
    }

    /** 将资源唯一键和引用冲突转换为契约错误。 */
    private static BusinessAccessException conflict(String message) {
        return new BusinessAccessException(Reason.RESOURCE_CONFLICT, message);
    }
}
