package cn.edu.ooad.rbac.service;

import cn.edu.ooad.rbac.infrastructure.persistence.BusinessSystem;
import cn.edu.ooad.rbac.infrastructure.persistence.BusinessSystemRepository;
import cn.edu.ooad.rbac.infrastructure.persistence.Organization;
import cn.edu.ooad.rbac.infrastructure.persistence.OrganizationPermission;
import cn.edu.ooad.rbac.infrastructure.persistence.OrganizationPermissionRepository;
import cn.edu.ooad.rbac.infrastructure.persistence.OrganizationRepository;
import cn.edu.ooad.rbac.infrastructure.persistence.Permission;
import cn.edu.ooad.rbac.infrastructure.persistence.PermissionRegrant;
import cn.edu.ooad.rbac.infrastructure.persistence.PermissionRegrantRepository;
import cn.edu.ooad.rbac.infrastructure.persistence.PermissionRepository;
import cn.edu.ooad.rbac.infrastructure.persistence.RbacRole;
import cn.edu.ooad.rbac.infrastructure.persistence.RbacRoleRepository;
import cn.edu.ooad.rbac.infrastructure.persistence.RolePermission;
import cn.edu.ooad.rbac.infrastructure.persistence.RolePermissionRepository;
import cn.edu.ooad.rbac.service.BusinessAccessException.Reason;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 在权限行锁下管理组织停用、重新开放和范围重授。 */
@Service
@RequiredArgsConstructor
public class OrganizationPermissionService {
    private final AuthorizationService authorization;
    private final OrganizationRepository organizations;
    private final BusinessSystemRepository systems;
    private final PermissionRepository permissions;
    private final OrganizationPermissionRepository policies;
    private final PermissionRegrantRepository regrants;
    private final RbacRoleRepository roles;
    private final RolePermissionRepository rolePermissions;

    /** 停用会清理本范围的旧重授，重新开放仅切换策略状态。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void changeDisabled(Long operator, Long organizationId, String systemCode, String code, boolean disabled) {
        requireGlobal(operator);
        Organization organization = findOrganization(organizationId);
        BusinessSystem system = findSystem(systemCode);
        Permission permission = lockPermission(system, code);
        OrganizationPermission.Key key = new OrganizationPermission.Key(organizationId, permission.getId());
        if (disabled) {
            policies.save(new OrganizationPermission(organizationId, permission.getId(), OrganizationPermission.DISABLED));
            Set<Long> affected = new HashSet<>();
            affected.add(organizationId);
            organizations.findByParentId(organization.getId()).forEach(child -> affected.add(child.getId()));
            regrants.deleteInScope(permission.getId(), affected);
        } else if (policies.findById(key).filter(policy -> OrganizationPermission.DISABLED.equals(policy.getStatus())).isPresent()) {
            policies.save(new OrganizationPermission(organizationId, permission.getId(), OrganizationPermission.REOPENED));
        }
    }

    /** 按角色、权限的统一锁顺序重授已有的角色权限。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void regrant(Long operator, Long organizationId, String systemCode, String code, Long roleId) {
        requireGlobal(operator);
        Organization organization = findOrganization(organizationId);
        BusinessSystem system = findSystem(systemCode);
        RbacRole role = roles.findLockedById(roleId).orElseThrow(OrganizationPermissionService::missing);
        Permission permission = lockPermission(system, code);
        Long permissionId = permission.getId();
        if (role.getOwnerOrganizationId() != null) {
            Organization roleOwner = findOrganization(role.getOwnerOrganizationId());
            if (!Objects.equals(organizationId, roleOwner.getId())
                    && !Objects.equals(organizationId, roleOwner.getParentId())
                    && !Objects.equals(organization.getParentId(), roleOwner.getId())) {
                throw new BusinessAccessException(Reason.PERMISSION_DENIED, "角色不在可重授的组织范围内");
            }
        }
        Organization owner = findOrganization(system.getOwnerOrganizationId());
        Set<Long> affected = new HashSet<>();
        includeOrganization(affected, organization);
        includeOrganization(affected, owner);
        boolean reopened = policies.findById(new OrganizationPermission.Key(organizationId, permissionId))
                .filter(policy -> OrganizationPermission.REOPENED.equals(policy.getStatus())).isPresent();
        if (!reopened || !rolePermissions.existsById(new RolePermission.Key(roleId, permissionId))
                || policies.existsByPermissionIdAndOrganizationIdInAndStatus(
                        permissionId, affected, OrganizationPermission.DISABLED)) {
            throw new BusinessAccessException(Reason.ASSIGNMENT_CONFLICT, "当前组织或角色状态不允许重授");
        }
        PermissionRegrant.Key key = new PermissionRegrant.Key(organizationId, roleId, permissionId);
        if (!regrants.existsById(key)) {
            regrants.save(new PermissionRegrant(organizationId, roleId, permissionId));
        }
    }

    /** 在事务内重新确认操作人有全局组织权限管理资格。 */
    private void requireGlobal(Long operator) {
        authorization.assignmentScope(operator, AuthorizationService.ORGANIZATION_PERMISSION_WRITE)
                .orElseThrow(() -> new BusinessAccessException(Reason.PERMISSION_DENIED, "仅超级管理员可以管理组织权限"));
    }

    /** 找到策略作用的组织。 */
    private Organization findOrganization(Long id) {
        return organizations.findById(id).orElseThrow(OrganizationPermissionService::missing);
    }

    /** 用公开编码定位系统。 */
    private BusinessSystem findSystem(String code) {
        return systems.findByCode(code).orElseThrow(OrganizationPermissionService::missing);
    }

    /** 以目标系统下的权限行串行化策略写入。 */
    private Permission lockPermission(BusinessSystem system, String code) {
        return permissions.findLockedBySystemIdAndCode(system.getId(), code)
                .orElseThrow(OrganizationPermissionService::missing);
    }

    /** 收集组织及其上级公司范围。 */
    private void includeOrganization(Set<Long> scope, Organization organization) {
        scope.add(organization.getId());
        if (organization.getParentId() != null) {
            scope.add(organization.getParentId());
        }
    }

    /** 使用统一的资源缺失契约。 */
    private static BusinessAccessException missing() {
        return new BusinessAccessException(Reason.RESOURCE_NOT_FOUND, "组织、系统、权限或角色不存在");
    }
}
