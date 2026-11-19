// 维护同归属角色的继承边，复用 C1 分配规则检查新增授权。
package rolepermission;

import assignment.AssignmentService;
import authorization.AdministratorScope;
import authorization.AuthorizationService;
import contract.BusinessAccessException;
import contract.BusinessAccessException.Reason;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(isolation = Isolation.READ_COMMITTED)
public class RoleInheritanceService {
    private final RoleInheritanceStore hierarchy;
    private final RbacRoleRepository roles;
    private final AuthorizationService authorization;
    private final AssignmentService assignments;

    /** 串行化继承与分配写入，快照、校验和保存位于同一个事务。 */
    public void change(Long actor, Long seniorId, Long juniorId, boolean add) {
        AdministratorScope scope = authorization.assignmentScope(actor, AuthorizationService.ROLE_PERMISSION_WRITE)
                .orElseThrow(() -> new BusinessAccessException(Reason.PERMISSION_DENIED));
        hierarchy.lockWrites();
        RbacRole senior = role(seniorId);
        RbacRole junior = seniorId.equals(juniorId) ? senior : role(juniorId);
        assignments.requireManaged(scope, senior.getOwnerOrganizationId());
        assignments.requireManaged(scope, junior.getOwnerOrganizationId());
        RoleInheritanceKey key = new RoleInheritanceKey(seniorId, juniorId);
        if (!add) {
            hierarchy.remove(key);
            return;
        }
        if (!Objects.equals(senior.getOwnerOrganizationId(), junior.getOwnerOrganizationId())) {
            throw new BusinessAccessException(Reason.ROLE_INHERITANCE_SCOPE_MISMATCH);
        }
        if (seniorId.equals(juniorId)) {
            throw new BusinessAccessException(Reason.SELF_ROLE_INHERITANCE);
        }
        if (hierarchy.contains(key)) {
            return;
        }
        RoleInheritanceGraph before = hierarchy.snapshot();
        if (before.wouldFormCycle(seniorId, juniorId)) {
            throw new BusinessAccessException(Reason.ROLE_INHERITANCE_CYCLE);
        }
        assignments.requireInheritanceGrant(scope, senior, juniorId, before);
        hierarchy.add(key);
    }

    private RbacRole role(Long id) {
        return roles.findById(id).orElseThrow(() -> new BusinessAccessException(Reason.RESOURCE_NOT_FOUND));
    }
}
