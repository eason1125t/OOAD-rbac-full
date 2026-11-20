// 文件职责：事务化导入课程原表并准备幂等演示数据。
// 协作关系：协作 WorkbookReader 与各 JPA 仓储，为登录和业务演示准备基础记录。
package initialization;

import assignment.EmployeeRole;
import assignment.EmployeeRoleRepository;
import assignment.OrganizationPermission;
import assignment.OrganizationPermissionRepository;
import assignment.RolePermission;
import assignment.RolePermissionRepository;
import authorization.AdministratorScope;
import authorization.AdministratorScopeRepository;
import initialization.WorkbookReader.SourceRow;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import organization.Employee;
import organization.EmployeeRepository;
import organization.Organization;
import organization.OrganizationRepository;
import rolepermission.CatalogEntry;
import rolepermission.CatalogEntryRepository;
import rolepermission.Permission;
import rolepermission.PermissionRepository;
import rolepermission.RbacRole;
import rolepermission.RbacRoleRepository;
import rolepermission.RoleInheritanceKey;
import rolepermission.RoleInheritanceStore;
import simulation.BusinessRequestService;
import simulation.BusinessSystem;
import simulation.BusinessSystemRepository;
import simulation.SimulatedRequest;
import simulation.SimulatedRequestRepository;

/** C2 初始化用例，原子导入课程工作簿，幂等补齐演示场景并保留人工授权变更。 */
@Service
@RequiredArgsConstructor
public class InitializationService {
    private final WorkbookReader reader;
    private final OrganizationRepository organizations;
    private final EmployeeRepository employees;
    private final CatalogEntryRepository catalog;
    private final RbacRoleRepository roles;
    private final RoleInheritanceStore inheritance;
    private final BusinessSystemRepository systems;
    private final PermissionRepository permissions;
    private final EmployeeRoleRepository employeeRoles;
    private final RolePermissionRepository rolePermissions;
    private final AdministratorScopeRepository scopes;
    private final OrganizationPermissionRepository organizationPermissions;
    private final SimulatedRequestRepository simulatedRequests;

    /** 导入统计，只包含数量。 */
    public record ImportResult(int employees, int organizations, int catalogEntries, long pending) {
    }

    /** 导入三份原表。相同记录可重跑，内容不一致时回滚整批写入。 */
    @Transactional(rollbackFor = IOException.class)
    public ImportResult importWorkbooks(Path directory) throws IOException {
        List<SourceRow> staff = reader.read(directory.resolve("市政公司员工信息表.xlsx"), "员工信息", 0,
                List.of("姓名", "用户名", "分公司", "部门", "岗位", "电话号码"), false);
        List<SourceRow> units = reader.read(directory.resolve("市政公司部门信息表.xlsx"), "部门信息", 0,
                List.of("部门名称", "部门经理", "上级部门"), false);
        List<SourceRow> entries = reader.read(directory.resolve("市政公司应用软件功能权限清单(1).xlsx"),
                "市政公司应用软件功能权限清单", 1,
                List.of("软件系统", "功能模块", "功能点", "功能说明", "系统管理员", "公司领导", "部门经理", "项目经理", "普通员工"),
                true);

        Map<String, Organization> byName = importOrganizations(units, staff);
        Map<String, Employee> byUsername = employees.findAll().stream()
                .collect(Collectors.toMap(Employee::getUsername, Function.identity()));
        List<Employee> newEmployees = new ArrayList<>();
        long pending = 0;
        for (SourceRow row : staff) {
            Long organizationId = resolveEmployeeOrganization(byName, row.value(2), row.value(3));
            if (organizationId == null) {
                pending++;
            }
            Employee candidate = new Employee(row.required(1), row.required(0), organizationId,
                    row.value(2), row.value(3), row.value(4), row.value(5));
            Employee previous = byUsername.putIfAbsent(candidate.getUsername(), candidate);
            if (previous == null) {
                newEmployees.add(candidate);
            } else {
                requireSame(employeeValues(previous), employeeValues(candidate), "员工信息", row.number());
            }
        }
        employees.saveAll(newEmployees);
        importCatalog(entries);
        return new ImportResult(staff.size(), units.size(), entries.size(), pending);
    }

    /** 先导入公司，再按原表上级查找部门，避免依赖源表顺序。 */
    private Map<String, Organization> importOrganizations(List<SourceRow> rows, List<SourceRow> staff) {
        java.util.Set<String> branches = staff.stream().map(row -> row.value(2))
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, SourceRow> index = new LinkedHashMap<>();
        for (SourceRow row : rows) {
            if (index.putIfAbsent(row.required(0), row) != null) {
                throw new IllegalArgumentException("部门信息第 " + row.number() + " 行名称重复");
            }
        }
        Map<String, Organization> byName = new LinkedHashMap<>();
        for (SourceRow row : rows) {
            String parent = row.value(2);
            if (parent != null && !index.containsKey(parent)) {
                throw new IllegalArgumentException("部门信息第 " + row.number() + " 行上级不存在");
            }
            // 原表分公司挂在总公司下，授权模型把各分公司作为各自范围的根。
            if (parent == null || branches.contains(row.required(0))) {
                saveSourceOrganization(row, "COMPANY", null, byName);
            }
        }
        for (SourceRow row : rows) {
            if (!byName.containsKey(row.required(0))) {
                Organization parent = byName.get(row.value(2));
                if (parent == null || !"COMPANY".equals(parent.getOrganizationType())) {
                    throw new IllegalArgumentException("部门信息第 " + row.number() + " 行上级不是公司");
                }
                saveSourceOrganization(row, "DEPARTMENT", parent.getId(), byName);
            }
        }
        return byName;
    }

    /** 以稳定的来源键保存目录，原上级名称与授权上级分别保留。 */
    private void saveSourceOrganization(SourceRow row, String type, Long parentId, Map<String, Organization> index) {
        String name = row.required(0);
        String code = "xlsx-" + UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
        Organization candidate = new Organization(code, name, type, parentId, row.value(1), row.value(2));
        Organization saved = organizations.findByCode(code).orElseGet(() -> organizations.save(candidate));
        requireSame(organizationValues(saved), organizationValues(candidate), "部门信息", row.number());
        index.put(name, saved);
    }

    /** 仅在公司和部门能唯一匹配时建立正式归属。 */
    private Long resolveEmployeeOrganization(Map<String, Organization> index, String branch, String department) {
        Organization company = index.get(branch);
        if (company == null || !"COMPANY".equals(company.getOrganizationType())) {
            return null;
        }
        if (department == null) {
            return company.getId();
        }
        List<Organization> matches = Arrays.asList(index.get(department), index.get(branch + "-" + department))
                .stream().filter(Objects::nonNull).filter(unit -> Objects.equals(unit.getParentId(), company.getId()))
                .distinct().toList();
        return matches.size() == 1 ? matches.getFirst().getId() : null;
    }

    /** 用系统、模块、功能点作为清单的来源键，保留全部动作原文。 */
    private void importCatalog(List<SourceRow> rows) {
        /** 以工作簿的系统、模块和功能三列识别重复条目。 */
        record FeatureKey(String system, String module, String feature) {
        }
        Map<FeatureKey, CatalogEntry> index = catalog.findAll().stream()
                .collect(Collectors.toMap(entry -> new FeatureKey(entry.getSystemName(), entry.getModuleName(),
                        entry.getFeatureName()), Function.identity()));
        List<CatalogEntry> additions = new ArrayList<>();
        for (SourceRow row : rows) {
            CatalogEntry candidate = new CatalogEntry(row.required(0), row.required(1), row.required(2), row.required(3),
                    row.value(4), row.value(5), row.value(6), row.value(7), row.value(8));
            FeatureKey key = new FeatureKey(candidate.getSystemName(), candidate.getModuleName(), candidate.getFeatureName());
            CatalogEntry previous = index.putIfAbsent(key, candidate);
            if (previous == null) {
                additions.add(candidate);
            } else {
                requireSame(catalogValues(previous), catalogValues(candidate), "功能权限清单", row.number());
            }
        }
        catalog.saveAll(additions);
    }

    /** 对比源字段，不把员工信息或原权限文本写入异常。 */
    private void requireSame(List<?> existing, List<?> incoming, String source, int row) {
        if (!existing.equals(incoming)) {
            throw new IllegalArgumentException(source + "第 " + row + " 行与已有数据冲突");
        }
    }

    /** 按原表字段构造员工对比快照，幂等重导入时不比较生成 ID。 */
    private List<?> employeeValues(Employee item) {
        return Arrays.asList(item.getName(), item.getOrganizationId(), item.getSourceBranchName(),
                item.getSourceDepartmentName(), item.getJobTitle(), item.getPhone());
    }

    /** 按来源层级构造组织对比快照，用于识别冲突导入。 */
    private List<?> organizationValues(Organization item) {
        return Arrays.asList(item.getName(), item.getOrganizationType(), item.getParentId(),
                item.getManagerName(), item.getSourceParentName());
    }

    /** 按原表列构造清单对比快照，保留空值及动作文本。 */
    private List<?> catalogValues(CatalogEntry item) {
        return Arrays.asList(item.getDescription(), item.getAdministratorCell(), item.getExecutiveCell(),
                item.getDepartmentManagerCell(), item.getProjectManagerCell(), item.getEmployeeCell());
    }

    /** 建立跨公司、跨部门和系统隔离的合成数据。已存在的演示标记保留现场授权变更。 */
    @Transactional
    public void initializeDemo() {
        inheritance.lockWrites();
        if (organizations.findByCode("demo-company-a").isEmpty()) {
            initializeDemoBaseline();
        }
        initializeDemoRequests();
        initializeDemoRequestRoles();
        initializeDemoRoleScenarios();
        createDemoReadRole("demo-company-app", List.of("company-a-admin"));
        createDemoReadRole("demo-engineering-app", List.of("company-a-admin", "engineering-admin", "engineering-employee",
                "engineering-requester", "engineering-inherited-approver"));
        createDemoReadRole("demo-finance-app", List.of("company-a-admin", "finance-employee"));
        createDemoReadRole("demo-company-b-app", List.of("company-b-employee"));
    }

    /** 补齐演示组织、员工、系统和初始授权；已有用户策略不在此重置。 */
    private void initializeDemoBaseline() {
        Organization company = organizations.save(new Organization("demo-company-a", "示例甲公司", "COMPANY", null, null, null));
        Organization other = organizations.save(new Organization("demo-company-b", "示例乙公司", "COMPANY", null, null, null));
        Organization engineering = organizations.save(new Organization("demo-engineering", "示例工程部", "DEPARTMENT",
                company.getId(), null, null));
        Organization finance = organizations.save(new Organization("demo-finance", "示例财务部", "DEPARTMENT",
                company.getId(), null, null));
        Employee root = employees.save(new Employee("super-admin", "示例超级管理员", company.getId(), null, null, null, null));
        Employee companyAdmin = employees.save(new Employee("company-a-admin", "示例公司管理员", company.getId(),
                null, null, null, null));
        Employee departmentAdmin = employees.save(new Employee("engineering-admin", "示例部门管理员", engineering.getId(),
                null, null, null, null));
        Employee employee = employees.save(new Employee("engineering-employee", "示例工程员工", engineering.getId(),
                null, null, null, null));
        scopes.saveAll(List.of(new AdministratorScope(root.getId(), "GLOBAL", null),
                new AdministratorScope(companyAdmin.getId(), "ORGANIZATION", company.getId()),
                new AdministratorScope(departmentAdmin.getId(), "ORGANIZATION", engineering.getId())));
        BusinessSystem companySystem = systems.save(new BusinessSystem("demo-company-app", "示例公司申请系统", company.getId(), true));
        BusinessSystem departmentSystem = systems.save(new BusinessSystem("demo-engineering-app", "示例工程申请系统",
                engineering.getId(), true));
        systems.saveAll(List.of(new BusinessSystem("demo-finance-app", "示例财务申请系统", finance.getId(), true),
                new BusinessSystem("demo-company-b-app", "示例乙公司申请系统", other.getId(), true)));
        Permission companyApproval = permissions.save(new Permission(BusinessRequestService.APPROVE_PERMISSION, "公司申请审批",
                companySystem.getId(), null));
        Permission departmentApproval = permissions.save(new Permission(BusinessRequestService.APPROVE_PERMISSION, "工程申请审批",
                departmentSystem.getId(), null));
        RbacRole approver = roles.save(new RbacRole("示例工程审批员", engineering.getId()));
        employeeRoles.save(new EmployeeRole(employee.getId(), approver.getId()));
        rolePermissions.save(new RolePermission(approver.getId(), departmentApproval.getId()));
        organizationPermissions.saveAll(List.of(
                new OrganizationPermission(engineering.getId(), departmentApproval.getId(), "OPEN"),
                new OrganizationPermission(finance.getId(), companyApproval.getId(), "DISABLED")));
    }

    /** 新增合成账号时建立最小授权，重复初始化保留角色和继承边的测试改动。 */
    private void initializeDemoRoleScenarios() {
        BusinessSystem engineering = systems.findByCode("demo-engineering-app").orElseThrow();
        Long engineeringId = engineering.getOwnerOrganizationId();
        if (employees.findByUsername("engineering-requester").isEmpty()) {
            Employee applicant = employees.save(new Employee("engineering-requester", "示例工程申请人",
                    engineeringId, null, null, null, null));
            RbacRole role = roles.save(new RbacRole("示例工程申请人", engineeringId));
            employeeRoles.save(new EmployeeRole(applicant.getId(), role.getId()));
            for (String code : List.of("request:create", "request:update", "request:withdraw", "request:delete")) {
                Permission permission = permissions.findBySystemIdAndCode(engineering.getId(), code).orElseThrow();
                rolePermissions.save(new RolePermission(role.getId(), permission.getId()));
            }
        }
        if (employees.findByUsername("engineering-inherited-approver").isEmpty()) {
            Employee approver = employees.save(new Employee("engineering-inherited-approver", "示例工程继承审批人",
                    engineeringId, null, null, null, null));
            RbacRole senior = roles.save(new RbacRole("示例继承审批主管", engineeringId));
            RbacRole source = roles.save(new RbacRole("示例继承审批来源", engineeringId));
            Permission approval = permissions.findBySystemIdAndCode(engineering.getId(), BusinessRequestService.APPROVE_PERMISSION)
                    .orElseThrow();
            employeeRoles.save(new EmployeeRole(approver.getId(), senior.getId()));
            rolePermissions.save(new RolePermission(source.getId(), approval.getId()));
            inheritance.add(new RoleInheritanceKey(senior.getId(), source.getId()));
        }
        seedInheritedTestRole(engineeringId);
        if (employees.findByUsername("company-b-employee").isEmpty()) {
            Long otherCompanyId = organizations.findByCode("demo-company-b").orElseThrow().getId();
            employees.save(new Employee("company-b-employee", "示例乙公司员工", otherCompanyId, null, null, null, null));
        }
    }

    /** 为权限管理页准备一个无直接授权的角色，重跑初始化保留之后的边变更。 */
    private void seedInheritedTestRole(Long engineeringId) {
        String name = "测试工程主管";
        if (roles.findByNameAndOwnerOrganizationId(name, engineeringId).isPresent()) {
            return;
        }
        roles.findByNameAndOwnerOrganizationId("示例继承审批来源", engineeringId).ifPresent(source -> {
            RbacRole testRole = roles.save(new RbacRole(name, engineeringId));
            inheritance.add(new RoleInheritanceKey(testRole.getId(), source.getId()));
        });
    }

    /** 演示账号使用独立角色，重复初始化保留已经调整过的授撤权。 */
    private void initializeDemoRequestRoles() {
        BusinessSystem finance = systems.findByCode("demo-finance-app").orElseThrow();
        if (employees.findByUsername("finance-employee").isEmpty()) {
            employees.save(new Employee("finance-employee", "示例财务员工", finance.getOwnerOrganizationId(),
                    null, null, null, null));
        }
        createDemoRequestRole("demo-company-app", "company-a-admin");
        createDemoRequestRole("demo-engineering-app", "engineering-employee");
        createDemoRequestRole("demo-finance-app", "finance-employee");
    }

    /** 为指定演示系统和员工补齐申请写动作及直接角色关系。 */
    private void createDemoRequestRole(String systemCode, String username) {
        BusinessSystem system = systems.findByCode(systemCode).orElseThrow();
        Long organizationId = system.getOwnerOrganizationId();
        String roleName = "示例申请经办员";
        if (roles.findByNameAndOwnerOrganizationId(roleName, organizationId).isPresent()) {
            return;
        }
        Employee employee = employees.findByUsername(username).orElseThrow();
        RbacRole role = roles.save(new RbacRole(roleName, organizationId));
        employeeRoles.save(new EmployeeRole(employee.getId(), role.getId()));
        for (String code : BusinessRequestService.REQUEST_ACTIONS.keySet()) {
            Permission definition = permissions.findBySystemIdAndCode(system.getId(), code).orElseThrow();
            rolePermissions.save(new RolePermission(role.getId(), definition.getId()));
        }
    }

    /** 独立查看角色只在首次创建时授权，重跑保留人工撤权。 */
    private void createDemoReadRole(String systemCode, List<String> usernames) {
        BusinessSystem system = systems.findByCode(systemCode).orElseThrow();
        if (roles.findByNameAndOwnerOrganizationId("示例申请查看员", system.getOwnerOrganizationId()).isPresent()) {
            return;
        }
        RbacRole viewer = roles.save(new RbacRole("示例申请查看员", system.getOwnerOrganizationId()));
        Permission read = permissions.findBySystemIdAndCode(system.getId(), BusinessRequestService.READ_PERMISSION).orElseThrow();
        rolePermissions.save(new RolePermission(viewer.getId(), read.getId()));
        for (String username : usernames) {
            Employee employee = employees.findByUsername(username).orElseThrow();
            employeeRoles.save(new EmployeeRole(employee.getId(), viewer.getId()));
        }
    }

    /** 为既有或新建的演示系统准备申请，重复运行保留申请及审批结果。 */
    private void initializeDemoRequests() {
        for (String systemCode : List.of("demo-company-app", "demo-engineering-app", "demo-finance-app", "demo-company-b-app")) {
            systems.findByCode(systemCode).ifPresent(system -> {
                if (permissions.findBySystemIdAndCode(system.getId(), BusinessRequestService.READ_PERMISSION).isEmpty()) {
                    permissions.save(new Permission(BusinessRequestService.READ_PERMISSION, "查看申请", system.getId(), null));
                }
                BusinessRequestService.REQUEST_ACTIONS.forEach((code, name) -> {
                    if (permissions.findBySystemIdAndCode(system.getId(), code).isEmpty()) {
                        permissions.save(new Permission(code, name, system.getId(), null));
                    }
                });
                if (!simulatedRequests.existsBySystemId(system.getId())) {
                    List<SimulatedRequest> fixtures = new ArrayList<>();
                    for (int number = 1; number <= 6; number++) {
                        fixtures.add(new SimulatedRequest(system.getId(), "示例申请 " + number));
                    }
                    simulatedRequests.saveAll(fixtures);
                }
            });
        }
    }
}
