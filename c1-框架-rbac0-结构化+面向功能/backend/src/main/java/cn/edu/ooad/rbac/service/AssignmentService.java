package cn.edu.ooad.rbac.service;

import cn.edu.ooad.rbac.infrastructure.persistence.AdministratorScope;
import cn.edu.ooad.rbac.infrastructure.persistence.BusinessSystemRepository;
import cn.edu.ooad.rbac.infrastructure.persistence.Employee;
import cn.edu.ooad.rbac.infrastructure.persistence.EmployeeRepository;
import cn.edu.ooad.rbac.infrastructure.persistence.EmployeeRole;
import cn.edu.ooad.rbac.infrastructure.persistence.EmployeeRoleRepository;
import cn.edu.ooad.rbac.infrastructure.persistence.Organization;
import cn.edu.ooad.rbac.infrastructure.persistence.OrganizationPermission;
import cn.edu.ooad.rbac.infrastructure.persistence.OrganizationPermissionRepository;
import cn.edu.ooad.rbac.infrastructure.persistence.OrganizationRepository;
import cn.edu.ooad.rbac.infrastructure.persistence.Permission;
import cn.edu.ooad.rbac.infrastructure.persistence.PermissionRepository;
import cn.edu.ooad.rbac.infrastructure.persistence.PermissionRegrantRepository;
import cn.edu.ooad.rbac.infrastructure.persistence.RbacRole;
import cn.edu.ooad.rbac.infrastructure.persistence.RbacRoleRepository;
import cn.edu.ooad.rbac.infrastructure.persistence.RolePermission;
import cn.edu.ooad.rbac.infrastructure.persistence.RolePermissionRepository;
import cn.edu.ooad.rbac.service.BusinessAccessException.Reason;
import java.util.HashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 管理两类直接分配关系，事务内校验目标和授予范围。 */
@Service
@RequiredArgsConstructor
public class AssignmentService {
    private final AuthorizationService authorization;
    private final EmployeeRepository employees;
    private final RbacRoleRepository roles;
    private final PermissionRepository permissions;
    private final EmployeeRoleRepository employeeRoles;
    private final RolePermissionRepository rolePermissions;
    private final OrganizationRepository organizations;
    private final OrganizationPermissionRepository organizationPermissions;
    private final BusinessSystemRepository systems;
    private final PermissionRegrantRepository regrants;

    /** 维护员工角色关系，并检查角色携带的全部权限。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void changeEmployeeRole(Long operator, Long employeeId, Long roleId, boolean grant) {
        AdministratorScope scope = scope(operator, AuthorizationService.EMPLOYEE_ROLE_WRITE);
        RbacRole role = lockedRole(roleId);
        Employee employee = employees.findLockedById(employeeId)
                .orElseThrow(() -> new BusinessAccessException(Reason.RESOURCE_NOT_FOUND));
        requireManaged(scope, role.getOwnerOrganizationId());
        requireManaged(scope, employee.getOrganizationId());
        Set<Long> target = organizationChain(employee.getOrganizationId());
        if (!isGlobal(scope) && !target.contains(role.getOwnerOrganizationId())) {
            throw denied();
        }
        EmployeeRole.Key key = new EmployeeRole.Key(employeeId, roleId);
        if (!grant) {
            employeeRoles.deleteById(key);
        } else if (!employeeRoles.existsById(key)) {
            if (!WorkspaceService.canSignIn(employee)) {
                throw new BusinessAccessException(Reason.ASSIGNMENT_CONFLICT);
            }
            for (Long permissionId : permissions.findByRoleId(roleId).stream()
                    .map(Permission::getId).sorted().toList()) {
                requireGrantable(scope, roleId, lockedPermission(permissionId), target);
            }
            employeeRoles.save(new EmployeeRole(employeeId, roleId));
        }
    }

    /** 检查角色权限变更不会越过角色归属或已有使用者的范围。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void changeRolePermission(Long operator, Long roleId, Long permissionId, boolean grant) {
        AdministratorScope scope = scope(operator, AuthorizationService.ROLE_PERMISSION_WRITE);
        RbacRole role = lockedRole(roleId);
        Permission permission = lockedPermission(permissionId);
        requireManaged(scope, role.getOwnerOrganizationId());
        Set<Long> members = new HashSet<>(employeeRoles.findAssignedOrganizationIds(roleId));
        for (Long organizationId : members) {
            requireManaged(scope, organizationId);
        }
        RolePermission.Key key = new RolePermission.Key(roleId, permissionId);
        if (!grant) {
            // 局部管理员可清理本级角色上的已有分配，停用不妨碍撤销。
            rolePermissions.deleteById(key);
        } else if (!rolePermissions.existsById(key)) {
            members.add(role.getOwnerOrganizationId());
            for (Long organizationId : members) {
                requireGrantable(scope, roleId, permission, organizationChain(organizationId));
            }
            rolePermissions.save(new RolePermission(roleId, permissionId));
        }
    }

    /** 在事务内重查管理资格。 */
    private AdministratorScope scope(Long employeeId, String action) {
        return authorization.assignmentScope(employeeId, action).orElseThrow(AssignmentService::denied);
    }

    /** 两种关联使用同一角色行作为写入锁，读已提交确保等待后能看到先前写入。 */
    private RbacRole lockedRole(Long roleId) {
        return roles.findLockedById(roleId).orElseThrow(() -> new BusinessAccessException(Reason.RESOURCE_NOT_FOUND));
    }

    /** 在角色锁之后获取权限锁，与组织策略事务同步。 */
    private Permission lockedPermission(Long permissionId) {
        return permissions.findLockedById(permissionId)
                .orElseThrow(() -> new BusinessAccessException(Reason.RESOURCE_NOT_FOUND));
    }

    /** 公司管理本公司及部门，部门只管理本级。空归属仅超级管理员可管理。 */
    private void requireManaged(AdministratorScope scope, Long organizationId) {
        if (!isGlobal(scope) && !organizationChain(organizationId).contains(scope.getOrganizationId())) {
            throw denied();
        }
    }

    /** 检查组织停用及局部管理员可使用的本级或上级开放业务权限。 */
    private void requireGrantable(AdministratorScope scope, Long roleId, Permission permission, Set<Long> target) {
        Long systemOwner = permission.getSystemId() == null ? null
                : systems.findById(permission.getSystemId()).orElseThrow().getOwnerOrganizationId();
        Set<Long> affected = new HashSet<>(target);
        affected.addAll(organizationChain(systemOwner));
        if (!affected.isEmpty() && organizationPermissions.existsByPermissionIdAndOrganizationIdInAndStatus(
                permission.getId(), affected, OrganizationPermission.DISABLED)) {
            throw new BusinessAccessException(Reason.ASSIGNMENT_CONFLICT);
        }
        if (isGlobal(scope)) {
            return;
        }
        if (systemOwner == null || !target.contains(systemOwner)) {
            throw denied();
        }
        Long owner = permission.getOwnerOrganizationId() == null ? systemOwner : permission.getOwnerOrganizationId();
        if (!target.contains(owner)) {
            throw denied();
        }
        if (!organizationChain(owner).contains(scope.getOrganizationId())
                && !organizationPermissions.existsByPermissionIdAndOrganizationIdInAndStatus(
                        permission.getId(), target, OrganizationPermission.OPEN)
                && !regrants.existsByRoleIdAndPermissionIdAndOrganizationIdIn(roleId, permission.getId(), target)) {
            throw denied();
        }
    }

    /** C1 组织模型只有公司及其直接下属部门。 */
    private Set<Long> organizationChain(Long organizationId) {
        if (organizationId == null) {
            return Set.of();
        }
        Organization organization = organizations.findById(organizationId).orElseThrow();
        return organization.getParentId() == null ? Set.of(organizationId)
                : Set.of(organizationId, organization.getParentId());
    }

    /** 全局管理员权限由管理员范围记录决定。 */
    private boolean isGlobal(AdministratorScope scope) {
        return "GLOBAL".equals(scope.getScopeType());
    }

    /** 授权配置统一使用契约拒绝码。 */
    private static BusinessAccessException denied() {
        return new BusinessAccessException(Reason.PERMISSION_DENIED, "无权操作该分配关系");
    }
}
