// 文件职责：统一普通员工和管理员的目录读取范围。
// 协作关系：复用可信员工与管理员资格，供目录查询和原管理查询使用；不改变业务系统准入或写入规则。
package organization;

import authentication.CurrentEmployeeService;
import authorization.AdministratorScope;
import authorization.AuthorizationService;
import contract.BusinessAccessException;
import contract.BusinessAccessException.Reason;
import java.util.HashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 可见目录与管理员查询共用组织范围，不把可进入业务系统等同于能读取其全部员工。 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DirectoryVisibilityService {
    private final CurrentEmployeeService currentEmployees;
    private final AuthorizationService authorization;
    private final OrganizationRepository organizations;

    /** 当前身份可以读取的最小目录；可信ID由HTTP身份适配层传入。 */
    public Scope forCurrentEmployee(Long actor) {
        return scope(actor, false);
    }

    /** 原管理查询仍要求管理员资格，不因目录向普通员工开放而放宽。 */
    public Scope forAdministrator(Long actor) {
        return scope(actor, true);
    }

    private Scope scope(Long actor, boolean administratorRequired) {
        Employee employee = currentEmployees.requireActive(actor);
        AdministratorScope admin = authorization.assignmentScope(actor, AuthorizationService.MANAGEMENT_READ)
                .orElse(null);
        if (admin == null) {
            if (administratorRequired) {
                throw new BusinessAccessException(Reason.PERMISSION_DENIED, "无权查看该管理数据");
            }
            Set<Long> own = Set.of(employee.getOrganizationId());
            return new Scope(false, false, own, own);
        }
        if ("GLOBAL".equals(admin.getScopeType())) {
            return new Scope(true, true, Set.of(), Set.of());
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
        return new Scope(true, false, Set.copyOf(visible), Set.copyOf(policyIds));
    }

    /** ids筛目录/员工，policyIds只供原管理权限目录使用，两者不能互换。 */
    public record Scope(boolean administrator, boolean global, Set<Long> ids, Set<Long> policyIds) {
        public boolean contains(Long id) {
            return global || ids.contains(id);
        }
    }
}
