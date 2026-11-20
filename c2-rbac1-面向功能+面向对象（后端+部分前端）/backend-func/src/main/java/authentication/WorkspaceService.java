// 文件职责：根据员工及管理员范围计算可访问系统和默认工作区。
// 协作关系：复用组织、系统与管理员仓储，为登录导航及授权提供同一准入规则。
package authentication;

import authorization.AdministratorScope;
import authorization.AdministratorScopeRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import organization.Employee;
import organization.EmployeeRepository;
import organization.Organization;
import organization.OrganizationRepository;
import simulation.BusinessSystem;
import simulation.BusinessSystemRepository;

/** 按员工归属和管理员范围生成当前身份、系统列表及默认工作区。 */
@Service
@RequiredArgsConstructor
public class WorkspaceService {
    private final EmployeeRepository employees;
    private final AdministratorScopeRepository scopes;
    private final OrganizationRepository organizations;
    private final BusinessSystemRepository systems;

    /** 对外可用的员工信息。 */
    public record EmployeeIdentity(Long id, String username, String name, Long organizationId) {
    }

    /** 同源可进入的工作区。 */
    public record Workspace(String key, String type, String name, String path) {
    }

    /** 当前身份及导航选择。 */
    public record CurrentSession(EmployeeIdentity employee, List<Workspace> workspaces,
                                 String defaultWorkspaceKey) {
    }

    /** 可进入业务系统的信息。 */
    public record SystemInfo(Long id, String code, String name, Long ownerOrganizationId, String ownerOrganizationName) {
    }

    /** 通过当前员工记录检查会话是否仍有效。 */
    @Transactional(readOnly = true)
    public Optional<Employee> findActiveEmployee(Long employeeId) {
        return employees.findById(employeeId).filter(WorkspaceService::canSignIn);
    }

    /** 已停用或尚无组织归属的员工不可登录。 */
    public static boolean canSignIn(Employee employee) {
        return employee.isEnabled() && employee.getOrganizationId() != null;
    }

    /** 生成登录或刷新身份时的导航。 */
    @Transactional(readOnly = true)
    public CurrentSession current(Employee employee) {
        Context context = context(employee);
        List<BusinessSystem> visible = accessibleSystems(context);
        List<Workspace> workspaces = new ArrayList<>();
        if (context.administrator()) {
            workspaces.add(new Workspace("admin", "ADMIN", "权限管理平台", "/admin/"));
        }
        for (BusinessSystem system : visible) {
            workspaces.add(new Workspace(workspaceKey(system), "BUSINESS", system.getName(),
                    "/business/" + system.getCode() + "/"));
        }

        String defaultKey = context.administrator() ? "admin" : defaultSystem(visible, context.organization());
        EmployeeIdentity identity = new EmployeeIdentity(employee.getId(), employee.getUsername(), employee.getName(),
                employee.getOrganizationId());
        return new CurrentSession(identity, List.copyOf(workspaces), defaultKey);
    }

    /** 列出当前员工可进入的业务系统。 */
    @Transactional(readOnly = true)
    public List<SystemInfo> listSystems(Employee employee) {
        return accessibleSystems(context(employee)).stream().map(system -> {
            Organization owner = organizations.findById(system.getOwnerOrganizationId()).orElseThrow();
            return new SystemInfo(system.getId(), system.getCode(), system.getName(), owner.getId(), owner.getName());
        }).toList();
    }

    /** 管理员按配置范围准入，普通员工按其所属组织准入。 */
    public Context context(Employee employee) {
        Organization organization = organizations.findById(employee.getOrganizationId()).orElseThrow();
        Set<Long> owners = new HashSet<>();
        AdministratorScope scope = scopes.findById(employee.getId()).orElse(null);
        boolean global = scope != null && "GLOBAL".equals(scope.getScopeType());
        if (scope == null) {
            includeOrganization(organization, owners);
        } else if (!global) {
            Organization managed = organizations.findById(scope.getOrganizationId()).orElseThrow();
            includeOrganization(managed, owners);
            if ("COMPANY".equals(managed.getOrganizationType())) {
                organizations.findByParentId(managed.getId()).forEach(child -> owners.add(child.getId()));
            }
        }
        return new Context(organization, scope != null, global, Set.copyOf(owners));
    }

    /** 员工及部门管理员可访问部门所在公司的系统。 */
    private void includeOrganization(Organization organization, Set<Long> owners) {
        owners.add(organization.getId());
        if (organization.getParentId() != null) {
            owners.add(organization.getParentId());
        }
    }

    /** 按稳定编码排序，便于两个前端和 Apifox 对照。 */
    private List<BusinessSystem> accessibleSystems(Context context) {
        return context.global()
                ? systems.findAllByOrderByCodeAsc()
                : systems.findByOwnerOrganizationIdInOrderByCodeAsc(context.ownerIds());
    }

    /** 优先选择本组织的默认项，随后尝试上级公司。 */
    private String defaultSystem(List<BusinessSystem> visible, Organization organization) {
        return defaultFor(visible, organization.getId())
                .or(() -> defaultFor(visible, organization.getParentId()))
                .map(this::workspaceKey).orElse(null);
    }

    /** 仅从可进入的系统中查找指定组织的默认项。 */
    private Optional<BusinessSystem> defaultFor(List<BusinessSystem> visible, Long organizationId) {
        return visible.stream().filter(BusinessSystem::isDefaultWorkspace)
                .filter(system -> Objects.equals(system.getOwnerOrganizationId(), organizationId)).findFirst();
    }

    /** 拼接与工作区契约一致的稳定键。 */
    private String workspaceKey(BusinessSystem system) {
        return "business:" + system.getCode();
    }

    /** 一次查询得到的组织和管理范围。 */
    public record Context(Organization organization, boolean administrator, boolean global, Set<Long> ownerIds) {
        /** 供实时授权与工作区导航共用的系统准入范围。 */
        public boolean canEnter(BusinessSystem system) {
            return global || ownerIds.contains(system.getOwnerOrganizationId());
        }
    }
}
