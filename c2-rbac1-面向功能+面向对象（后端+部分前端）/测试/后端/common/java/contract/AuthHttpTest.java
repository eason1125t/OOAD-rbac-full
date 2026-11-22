// 文件职责：两版共同 HTTP 契约及真实依赖回归。
// 协作关系：由各版 Maven 注入版本，共用相同输入和断言，严格限定测试库。
package contract;

import app.RbacApplication;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import assignment.EmployeeRole;
import assignment.EmployeeRoleRepository;
import assignment.OrganizationPermission;
import assignment.OrganizationPermissionRepository;
import assignment.PermissionRegrant;
import assignment.PermissionRegrantRepository;
import assignment.RolePermission;
import assignment.RolePermissionRepository;
import authentication.LoginAccount;
import authentication.LoginAccountRepository;
import authorization.AdministratorScope;
import authorization.AdministratorScopeRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
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
import simulation.BusinessSystem;
import simulation.BusinessSystemRepository;
import simulation.SimulatedRequest;
import simulation.SimulatedRequestRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** 通过 HTTP 验证 C1 登录契约及真实JWT登录状态。 */
@SpringBootTest(classes = RbacApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "RBAC_DB_URL", matches = ".*[/]ooad_rbac1_(?:func|oo)_test(?:[?].*)?")
@EnabledIfEnvironmentVariable(named = "RBAC_REDIS_PORT", matches = "[0-9]+")
@EnabledIf("matchesEdition")
class AuthHttpTest {
    @Autowired private JdbcTemplate jdbc;
    private static final String EDITION = System.getProperty("rbac.test.edition", "missing");
    private static final String CSRF_COOKIE = "RBAC1_" + EDITION.toUpperCase(java.util.Locale.ROOT) + "_CSRF=";
    private static final String NAMESPACE = System.getenv("RBAC_AUTH_NAMESPACE") + ":*";

    /** 只允许选中版本的专用测试库；此条件在启动 Spring 上下文前执行。 */
    static boolean matchesEdition() {
        String url = System.getenv("RBAC_DB_URL");
        return (EDITION.equals("func") || EDITION.equals("oo")) && url != null
                && url.matches(".*[/]ooad_rbac1_" + EDITION + "_test(?:[?].*)?");
    }

    private static final String CSRF = "/api/auth/csrf";
    private static final String LOGIN = "/api/auth/login";
    private static final String ME = "/api/auth/me";
    private static final String LOGOUT = "/api/auth/logout";
    private static final String LOGIN_LOGGER = "rbac0.service.authentication.LoginAuthentication";
    private static final String ACCOUNT_LOGGER = "rbac0.service.authentication.AccountAuthentication";
    private static final String SYSTEMS = "/api/business-systems";
    private static final String ADMIN_EMPLOYEES = "/api/employees";
    private static final String ADMIN_DEPARTMENTS = "/api/departments";
    private static final String REQUESTS = SYSTEMS + "/%s/requests";
    private static final String APPROVE = "/api/requests/%d/approve";
    private static final String EMPLOYEE_ROLE = "/api/employees/%d/roles/%d";
    private static final String ROLE_PERMISSION = "/api/roles/%d/permissions/%d";
    private static final String POLICY = "/api/organizations/%d/business-systems/%s/permissions/request:approve";
    private static final String PASSWORD = "1234";

    @Autowired
    private TestRestTemplate http;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private OrganizationRepository organizations;
    @Autowired
    private EmployeeRepository employees;
    @Autowired
    private LoginAccountRepository accounts;
    @Autowired
    private BusinessSystemRepository systems;
    @Autowired
    private AdministratorScopeRepository scopes;
    @Autowired
    private PasswordEncoder passwords;
    @Autowired
    private SimulatedRequestRepository simulatedRequests;
    @Autowired
    private PermissionRepository permissions;
    @Autowired
    private RbacRoleRepository roles;
    @Autowired
    private EmployeeRoleRepository employeeRoles;
    @Autowired
    private RolePermissionRepository rolePermissions;
    @Autowired
    private OrganizationPermissionRepository organizationPermissions;
    @Autowired
    private PermissionRegrantRepository regrants;
    @Autowired
    private CatalogEntryRepository catalogEntries;

    /** CSRF使用独立Cookie，不创建匿名登录记录或HttpSession。 */
    @Test
    @DisplayName("C1-M2-02 匿名CSRF不建立登录状态")
    void issuesCsrf() {
        Set<String> before = redis.keys(NAMESPACE);
        ResponseEntity<JsonNode> response = http.getForEntity(CSRF, JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().path("token").asText()).isNotBlank();
        assertThat(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE))
                .contains(CSRF_COOKIE, "Path=/", "HttpOnly", "SameSite=Strict").doesNotContain("_SESSION=");
        assertThat(redis.keys(NAMESPACE)).containsExactlyInAnyOrderElementsOf(before);
        assertError(http.postForEntity(LOGIN, Map.of("username", "unused", "password", PASSWORD), JsonNode.class),
                403, "CSRF_INVALID");
    }

    /** 受保护的会话查询统一使用 JSON 错误响应。 */
    @Test
    @DisplayName("C1-M2-01 未登录读取当前身份返回 401")
    void rejectsAnonymousSession() {
        ResponseEntity<JsonNode> response = http.getForEntity(ME, JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody().path("code").asText()).isEqualTo("AUTHENTICATION_REQUIRED");
    }

    /** 同一 Cookie 服务于两个前端，登录轮换和退出都使旧身份不可用。 */
    @Test
    @DisplayName("C1-M2-01 / C1-M2-02 登录轮换会话并使旧令牌失效")
    void rotatesAndEndsSession() {
        String prefix = newPrefix();
        Organization company = organization(prefix + "-co", "COMPANY", null);
        Organization department = organization(prefix + "-dep", "DEPARTMENT", company.getId());
        Organization peer = organization(prefix + "-peer", "DEPARTMENT", company.getId());
        systems.saveAll(List.of(system(prefix + "-company", company), system(prefix + "-department", department),
                system(prefix + "-peer-app", peer)));
        Employee employee = employee(prefix, department.getId());

        Token anonymous = token(null);
        ResponseEntity<JsonNode> loggedIn = login(employee.getUsername(), PASSWORD, anonymous);
        assertThat(loggedIn.getStatusCode().value()).isEqualTo(200);
        assertThat(loggedIn.getBody().path("defaultWorkspaceKey").asText())
                .isEqualTo("business:" + prefix + "-department");
        assertThat(loggedIn.getBody().path("workspaces")).hasSize(2);
        Browser browser = browser(loggedIn);
        assertThat(browser).isNotEqualTo(anonymous.browser());
        assertThat(request(HttpMethod.GET, ME, anonymous.browser(), null, null).getStatusCode().value()).isEqualTo(401);
        assertThat(request(HttpMethod.GET, ME, browser, null, null).getBody().path("employee").path("id").asLong())
                .isEqualTo(employee.getId());
        assertThat(request(HttpMethod.GET, SYSTEMS, browser, null, null).getBody()).hasSize(2);

        assertError(request(HttpMethod.POST, LOGOUT, browser, anonymous.value(), null), 403, "CSRF_INVALID");
        Token refreshed = token(browser);
        assertThat(request(HttpMethod.POST, LOGOUT, browser, refreshed.value(), null).getStatusCode().value())
                .isEqualTo(204);
        assertError(request(HttpMethod.GET, ME, browser, null, null), 401, "AUTHENTICATION_REQUIRED");
    }

    /** 验证会话适配器的关键操作可追踪，且日志不记录请求值及失败堆栈。 */
    @Test
    @DisplayName("C1-M2-01 账号校验与会话操作日志不包含凭据")
    void logsSessionOperationsWithoutCredentials() {
        String prefix = newPrefix();
        Organization company = organization(prefix + "-co", "COMPANY", null);
        Employee employee = employee(prefix, company.getId());
        Token anonymous = token(null);
        String rejectedPassword = prefix + "-invalid";
        Logger logger = (Logger) LoggerFactory.getLogger(LOGIN_LOGGER);
        Logger accountLogger = (Logger) LoggerFactory.getLogger(ACCOUNT_LOGGER);
        Level previousLevel = logger.getLevel();
        Level previousAccountLevel = accountLogger.getLevel();
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.setLevel(Level.DEBUG);
        logger.addAppender(events);
        accountLogger.setLevel(Level.DEBUG);
        accountLogger.addAppender(events);
        try {
            assertError(login(employee.getUsername(), rejectedPassword, anonymous), 401, "INVALID_CREDENTIALS");
            ResponseEntity<JsonNode> loggedIn = login(employee.getUsername(), PASSWORD, anonymous);
            assertThat(loggedIn.getStatusCode().value()).isEqualTo(200);
            Browser loginBrowser = browser(loggedIn);
            Token refreshed = token(loginBrowser);
            assertThat(request(HttpMethod.POST, LOGOUT, loginBrowser, refreshed.value(), null)
                    .getStatusCode().value()).isEqualTo(204);

            List<String> messages = events.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertThat(messages).anyMatch(message -> message.startsWith("verify 失败") && message.endsWith("BadCredentialsException"));
            for (String operation : List.of("verify", "start", "end")) {
                assertThat(messages).contains(operation + " 开始")
                        .anyMatch(message -> message.startsWith(operation + " 完成，耗时 "));
            }
            assertThat(String.join("\n", messages)).doesNotContain(employee.getUsername(), PASSWORD,
                    rejectedPassword, anonymous.value(), refreshed.value(), loginBrowser.accessToken);
            assertThat(events.list).allMatch(event -> event.getThrowableProxy() == null);
        } finally {
            logger.detachAppender(events);
            logger.setLevel(previousLevel);
            accountLogger.detachAppender(events);
            accountLogger.setLevel(previousAccountLevel);
            events.stop();
        }
    }

    /** 验证失败响应及公司管理员和普通员工的不同导航。 */
    @Test
    @DisplayName("C1-M2-01 / C1-M2-07 不可登录账号与工作区回退")
    void enforcesIdentityAndNavigation() {
        String prefix = newPrefix();
        Organization company = organization(prefix + "-co", "COMPANY", null);
        Organization department = organization(prefix + "-dep", "DEPARTMENT", company.getId());
        Organization empty = organization(prefix + "-empty", "COMPANY", null);
        systems.save(system(prefix + "-company", company));
        Employee ordinary = employee(prefix + "-ordinary", department.getId());
        Employee manager = employee(prefix + "-manager", company.getId());
        Employee noWorkspace = employee(prefix + "-empty-user", empty.getId());
        Employee pending = employee(prefix + "-pending", null);
        scopes.save(new AdministratorScope(manager.getId(), "ORGANIZATION", company.getId()));

        assertError(login(pending.getUsername(), PASSWORD, token(null)), 401, "INVALID_CREDENTIALS");
        assertError(login(ordinary.getUsername(), "wrong-password", token(null)), 401, "INVALID_CREDENTIALS");
        assertError(login(prefix + "-missing", PASSWORD, token(null)), 401, "INVALID_CREDENTIALS");
        assertThat(login(ordinary.getUsername(), PASSWORD, token(null)).getBody().path("defaultWorkspaceKey").asText())
                .isEqualTo("business:" + prefix + "-company");
        assertThat(login(manager.getUsername(), PASSWORD, token(null)).getBody().path("defaultWorkspaceKey").asText())
                .isEqualTo("admin");
        assertThat(login(noWorkspace.getUsername(), PASSWORD, token(null)).getBody().path("defaultWorkspaceKey").isNull())
                .isTrue();
    }

    /** 员工停用后，新登录和已有会话都失去访问资格。 */
    @Test
    @DisplayName("C1-M2-01 员工停用后拒绝旧会话和重新登录")
    void rejectsDisabledEmployee() {
        String prefix = newPrefix();
        Organization company = organization(prefix, "COMPANY", null);
        Employee employee = employee(prefix, company.getId());
        ResponseEntity<JsonNode> browserContext = login(employee.getUsername(), PASSWORD, token(null));
        assertThat(browserContext.getStatusCode().value()).isEqualTo(200);
        Browser activeBrowser = browser(browserContext);

        ReflectionTestUtils.setField(employee, "enabled", false);
        employees.save(employee);

        assertError(request(HttpMethod.GET, ME, activeBrowser, null, null), 401, "AUTHENTICATION_REQUIRED");
        assertError(login(employee.getUsername(), PASSWORD, token(null)), 401, "INVALID_CREDENTIALS");
    }

    /** 身份只来自会话；员工失去组织归属后旧会话失效。 */
    @Test
    @DisplayName("ID-S2-07 查询参数不能冒充身份，组织未匹配后拒绝旧会话")
    void trustsSessionIdentityAndRejectsUnmatchedEmployee() {
        String prefix = newPrefix();
        Organization company = organization(prefix, "COMPANY", null);
        Employee employee = employee(prefix + "-current", company.getId());
        Employee other = employee(prefix + "-other", company.getId());
        Browser activeBrowser = browser(login(employee.getUsername(), PASSWORD, token(null)));

        ResponseEntity<JsonNode> current = request(HttpMethod.GET,
                ME + "?employeeId=" + other.getId() + "&username=" + other.getUsername(), activeBrowser, null, null);
        assertThat(current.getStatusCode().value()).isEqualTo(200);
        assertThat(current.getBody().path("employee").path("id").asLong()).isEqualTo(employee.getId());
        assertThat(current.getBody().has("authorization")).isFalse();

        ReflectionTestUtils.setField(employee, "organizationId", null);
        employees.save(employee);

        assertError(request(HttpMethod.GET, ME, activeBrowser, null, null), 401, "AUTHENTICATION_REQUIRED");
        assertError(request(HttpMethod.GET, SYSTEMS, activeBrowser, null, null), 401, "AUTHENTICATION_REQUIRED");
        assertError(login(employee.getUsername(), PASSWORD, token(null)), 401, "INVALID_CREDENTIALS");
    }

    /** 校验组织范围、非法参数和系统及申请不存在时的契约错误。 */
    @Test
    @DisplayName("C1-M2-03 / C1-M2-06 申请路由校验系统范围与资源 ID")
    void validatesRequestRoutes() {
        String prefix = newPrefix();
        Organization company = organization(prefix, "COMPANY", null);
        Organization department = organization(prefix + "-dep", "DEPARTMENT", company.getId());
        Organization peer = organization(prefix + "-peer", "DEPARTMENT", company.getId());
        systems.save(system(prefix + "-app", peer));
        Employee employee = employee(prefix, department.getId());
        Browser browser = browser(login(employee.getUsername(), PASSWORD, token(null)));
        Token csrf = token(browser);

        assertError(request(HttpMethod.GET, REQUESTS.formatted(prefix + "-app"), browser, null, null),
                403, "SYSTEM_ACCESS_DENIED");
        assertError(request(HttpMethod.GET, REQUESTS.formatted(prefix + "-missing"), browser, null, null),
                404, "SYSTEM_NOT_FOUND");
        assertError(request(HttpMethod.GET, REQUESTS.formatted(prefix + "-app") + "?pageSize=101",
                browser, null, null), 400, "VALIDATION_FAILED");
        assertError(request(HttpMethod.POST, APPROVE.formatted(0L), browser, csrf.value(), null),
                400, "VALIDATION_FAILED");
        assertError(request(HttpMethod.POST, APPROVE.formatted(Long.MAX_VALUE), browser, csrf.value(), null),
                404, "REQUEST_NOT_FOUND");
    }

    /** 验证同码隔离、分页、拒绝不写入以及重复审批冲突。 */
    @Test
    @DisplayName("C1-M2-03 / C1-M2-06 同码权限按系统隔离")
    void approvesOnlyInGrantedSystem() {
        String prefix = newPrefix();
        Organization company = organization(prefix, "COMPANY", null);
        Organization department = organization(prefix + "-dep", "DEPARTMENT", company.getId());
        BusinessSystem companyApp = systems.save(system(prefix + "-company-app", company));
        BusinessSystem departmentApp = systems.save(system(prefix + "-department-app", department));
        SimulatedRequest companyRequest = simulatedRequests.save(new SimulatedRequest(companyApp.getId(), "公司申请"));
        SimulatedRequest first = simulatedRequests.save(new SimulatedRequest(departmentApp.getId(), "工程首条"));
        SimulatedRequest second = simulatedRequests.save(new SimulatedRequest(departmentApp.getId(), "工程次条"));
        permissions.save(new Permission("request:approve", "公司审批", companyApp.getId(), null));
        Permission approved = permissions.save(new Permission("request:approve", "工程审批", departmentApp.getId(), null));
        Employee employee = employee(prefix, department.getId());
        RbacRole role = roles.save(new RbacRole(prefix, department.getId()));
        employeeRoles.save(new EmployeeRole(employee.getId(), role.getId()));
        rolePermissions.save(new RolePermission(role.getId(), approved.getId()));
        grantRead(employee, departmentApp);
        permissions.save(new Permission("request:read", "查看申请", companyApp.getId(), null));
        Browser browser = browser(login(employee.getUsername(), PASSWORD, token(null)));
        Token csrf = token(browser);

        ResponseEntity<JsonNode> page = request(HttpMethod.GET,
                REQUESTS.formatted(departmentApp.getCode()) + "?page=2&pageSize=1", browser, null, null);
        assertThat(page.getStatusCode().value()).isEqualTo(200);
        assertThat(page.getBody().path("total").asLong()).isEqualTo(2);
        assertThat(page.getBody().path("items").get(0).path("id").asLong()).isEqualTo(second.getId());

        assertError(approve(companyRequest, csrf), 403, "PERMISSION_DENIED");
        assertThat(simulatedRequests.findById(companyRequest.getId()).orElseThrow().getStatus()).isEqualTo("PENDING");
        assertError(request(HttpMethod.POST, APPROVE.formatted(Long.MAX_VALUE), browser, null, null),
                404, "REQUEST_NOT_FOUND");
        ResponseEntity<JsonNode> result = approve(first, csrf);
        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getBody().path("authorization").asText()).isEqualTo("GRANTED");
        assertThat(result.getBody().path("execution").asText()).isEqualTo("SUCCEEDED");
        assertThat(result.getBody().path("requestId").asLong()).isEqualTo(first.getId());
        assertThat(simulatedRequests.findById(first.getId()).orElseThrow().getStatus()).isEqualTo("APPROVED");
        assertError(approve(first, csrf), 409, "REQUEST_ALREADY_APPROVED");
        rolePermissions.deleteById(new RolePermission.Key(role.getId(), approved.getId()));
        assertError(approve(second, csrf), 403, "PERMISSION_DENIED");
        assertThat(simulatedRequests.findById(second.getId()).orElseThrow().getStatus()).isEqualTo("PENDING");
    }

    /** 验证组织停用优先和全局管理员对其他系统默认有权。 */
    @Test
    @DisplayName("C1-M2-05 组织停用阻止超级管理员审批")
    void appliesOrganizationDisableBeforeGlobalAccess() {
        String prefix = newPrefix();
        Organization company = organization(prefix, "COMPANY", null);
        Organization department = organization(prefix + "-dep", "DEPARTMENT", company.getId());
        Organization other = organization(prefix + "-other", "COMPANY", null);
        BusinessSystem disabled = systems.save(system(prefix + "-disabled", department));
        BusinessSystem available = systems.save(system(prefix + "-available", other));
        Permission permission = permissions.save(new Permission("request:approve", "审批", disabled.getId(), null));
        permissions.save(new Permission("request:approve", "审批", available.getId(), null));
        organizationPermissions.save(new OrganizationPermission(company.getId(), permission.getId(), "DISABLED"));
        SimulatedRequest blocked = simulatedRequests.save(new SimulatedRequest(disabled.getId(), "禁止审批"));
        SimulatedRequest allowed = simulatedRequests.save(new SimulatedRequest(available.getId(), "跨公司审批"));
        Employee admin = employee(prefix, company.getId());
        scopes.save(new AdministratorScope(admin.getId(), "GLOBAL", null));
        permissions.save(new Permission("request:read", "查看申请", disabled.getId(), null));
        permissions.save(new Permission("request:read", "查看申请", available.getId(), null));
        Token csrf = token(browser(login(admin.getUsername(), PASSWORD, token(null))));

        assertError(approve(blocked, csrf), 403, "PERMISSION_DENIED");
        assertThat(approve(allowed, csrf).getStatusCode().value()).isEqualTo(200);
        assertThat(simulatedRequests.findById(blocked.getId()).orElseThrow().getStatus()).isEqualTo("PENDING");
    }

    /** 两个已授权请求竞争同一状态，只有一次业务提交能成功。 */
    @Test
    @DisplayName("C1-M2-06 并发审批只有一次成功")
    void approvesOnlyOnceConcurrently() throws Exception {
        String prefix = newPrefix();
        Organization company = organization(prefix, "COMPANY", null);
        BusinessSystem system = systems.save(system(prefix + "-app", company));
        permissions.save(new Permission("request:approve", "审批", system.getId(), null));
        SimulatedRequest pending = simulatedRequests.save(new SimulatedRequest(system.getId(), "并发审批"));
        Employee admin = employee(prefix, company.getId());
        scopes.save(new AdministratorScope(admin.getId(), "GLOBAL", null));
        permissions.save(new Permission("request:read", "查看申请", system.getId(), null));
        Token firstSession = token(browser(login(admin.getUsername(), PASSWORD, token(null))));
        Token secondSession = token(browser(login(admin.getUsername(), PASSWORD, token(null))));
        CyclicBarrier start = new CyclicBarrier(2);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = executor.submit(() -> {
                start.await(3, TimeUnit.SECONDS);
                return approve(pending, firstSession).getStatusCode().value();
            });
            Future<Integer> second = executor.submit(() -> {
                start.await(3, TimeUnit.SECONDS);
                return approve(pending, secondSession).getStatusCode().value();
            });
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(simulatedRequests.findById(pending.getId()).orElseThrow().getStatus()).isEqualTo("APPROVED");
    }

    /** 使用同一员工会话和令牌发起审批。 */
    private ResponseEntity<JsonNode> approve(SimulatedRequest simulatedRequest, Token csrf) {
        return request(HttpMethod.POST, APPROVE.formatted(simulatedRequest.getId()), csrf.browser(), csrf.value(), null);
    }


    /** 从真实系统会话发起并读取新申请。 */
    @Test
    @DisplayName("C2-11 有权员工发起申请后可在同一系统查询")
    void createsAndReadsOwnPendingRequest() {
        String prefix = newPrefix();
        Organization company = organization(prefix, "COMPANY", null);
        BusinessSystem app = systems.save(system(prefix + "-app", company));
        Employee employee = employee(prefix + "-worker", company.getId());
        RbacRole role = roles.save(new RbacRole(prefix + "-submitter", company.getId()));
        Permission create = permissions.save(new Permission("request:create", "发起", app.getId(), null));
        employeeRoles.save(new EmployeeRole(employee.getId(), role.getId()));
        rolePermissions.save(new RolePermission(role.getId(), create.getId()));
        grantRead(employee, app);
        Token worker = token(browser(login(employee.getUsername(), PASSWORD, token(null))));
        String collection = "/api/business-systems/" + app.getCode() + "/requests";

        ResponseEntity<JsonNode> created = request(HttpMethod.POST, collection, worker.browser(), worker.value(),
                Map.of("title", "  设备维护  "));
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(created.getBody().path("status").asText()).isEqualTo("PENDING");
        assertThat(created.getBody().path("applicantId").asLong()).isEqualTo(employee.getId());
        long requestId = created.getBody().path("id").asLong();
        ResponseEntity<JsonNode> detail = request(HttpMethod.GET, "/api/requests/" + requestId,
                worker.browser(), null, null);
        assertThat(detail.getStatusCode().value()).isEqualTo(200);
        assertThat(detail.getBody().path("title").asText()).isEqualTo("设备维护");
        JsonNode listing = request(HttpMethod.GET, collection, worker.browser(), null, null).getBody();
        assertThat(listing.path("allowedActions").toString()).isEqualTo("[\"request:create\"]");
        assertThat(listing.path("total").asInt())
                .isEqualTo(1);
    }


    /** 系统准入不代表可以读取申请，查看授权和撤权都使用当前数据库关系。 */
    @Test
    @DisplayName("C2-15 申请读取须有目标系统查看权，继承撤权在同一会话生效")
    void requiresReadPermissionForRequests() {
        RequestFixture fixture = lifecycleFixture();
        String detail = createLifecycleRequest(fixture);
        Long organizationId = fixture.system().getOwnerOrganizationId();
        Employee outsider = employee(newPrefix() + "-reader", organizationId);
        RbacRole reader = roles.save(new RbacRole(newPrefix() + "-reader", organizationId));
        employeeRoles.save(new EmployeeRole(outsider.getId(), reader.getId()));
        Permission create = permissions.findBySystemIdAndCode(fixture.system().getId(), "request:create").orElseThrow();
        Permission approve = permissions.findBySystemIdAndCode(fixture.system().getId(), "request:approve").orElseThrow();
        rolePermissions.saveAll(List.of(new RolePermission(reader.getId(), create.getId()),
                new RolePermission(reader.getId(), approve.getId())));
        Token actor = token(browser(login(outsider.getUsername(), PASSWORD, token(null))));
        assertError(request(HttpMethod.GET, fixture.collection(), actor.browser(), null, null), 403, "PERMISSION_DENIED");
        assertError(request(HttpMethod.GET, detail, actor.browser(), null, null), 403, "PERMISSION_DENIED");
        assertError(requestAction(detail + "/approve", actor), 403, "PERMISSION_DENIED");
        assertThat(request(HttpMethod.POST, fixture.collection(), actor.browser(), actor.value(),
                Map.of("title", "只发起不读取")).getStatusCode().value()).isEqualTo(201);

        Permission read = permissions.findBySystemIdAndCode(fixture.system().getId(), "request:read").orElseThrow();
        RbacRole source = roles.save(new RbacRole(newPrefix() + "-source", organizationId));
        rolePermissions.save(new RolePermission(source.getId(), read.getId()));
        Employee admin = employee(newPrefix() + "-admin", organizationId);
        scopes.save(new AdministratorScope(admin.getId(), "GLOBAL", null));
        Token manager = token(browser(login(admin.getUsername(), PASSWORD, token(null))));
        String edge = "/api/roles/%d/parents/%d".formatted(reader.getId(), source.getId());
        assertNoContent(HttpMethod.PUT, edge, manager);
        assertThat(request(HttpMethod.GET, fixture.collection(), actor.browser(), null, null).getStatusCode().value())
                .isEqualTo(200);
        assertThat(request(HttpMethod.GET, detail, actor.browser(), null, null).getStatusCode().value()).isEqualTo(200);
        assertThat(requestAction(detail + "/approve", actor).getStatusCode().value()).isEqualTo(200);
        assertNoContent(HttpMethod.DELETE, edge, manager);
        assertError(request(HttpMethod.GET, fixture.collection(), actor.browser(), null, null), 403, "PERMISSION_DENIED");
        assertError(request(HttpMethod.GET, detail, actor.browser(), null, null), 403, "PERMISSION_DENIED");
    }

    /** 查看权覆盖全部状态和旧记录，系统同名权限、动作权限均不能替代它。 */
    @Test
    @DisplayName("C2-15 只读账号查询全部申请状态，跨系统同码和写操作被拒绝")
    void readsAllStatesOnlyInGrantedSystem() {
        RequestFixture fixture = lifecycleFixture();
        Long owner = fixture.system().getOwnerOrganizationId();
        Employee viewer = employee(newPrefix() + "-viewer", owner);
        RbacRole role = roles.save(new RbacRole(newPrefix() + "-read", owner));
        employeeRoles.save(new EmployeeRole(viewer.getId(), role.getId()));
        Permission read = permissions.findBySystemIdAndCode(fixture.system().getId(), "request:read").orElseThrow();
        rolePermissions.save(new RolePermission(role.getId(), read.getId()));
        Token browserContext = token(browser(login(viewer.getUsername(), PASSWORD, token(null))));
        for (String state : List.of("PENDING", "APPROVED", "WITHDRAWN", "REJECTED")) {
            SimulatedRequest old = new SimulatedRequest(fixture.system().getId(), state);
            ReflectionTestUtils.setField(old, "status", state);
            old = simulatedRequests.save(old);
            String path = "/api/requests/" + old.getId();
            JsonNode detail = request(HttpMethod.GET, path, browserContext.browser(), null, null).getBody();
            assertThat(detail.path("status").asText()).isEqualTo(state);
            assertThat(detail.path("applicantId").isNull()).isTrue();
            assertError(requestAction(path + "/approve", browserContext), 403, "PERMISSION_DENIED");
        }
        JsonNode page = request(HttpMethod.GET, fixture.collection(), browserContext.browser(), null, null).getBody();
        assertThat(page.path("total").asInt()).isEqualTo(4);
        assertThat(page.path("allowedActions").size()).isZero();
        BusinessSystem other = systems.save(new BusinessSystem(newPrefix(), "同公司其他系统", owner));
        permissions.save(new Permission("request:read", "其他查看", other.getId(), null));
        assertError(request(HttpMethod.GET, "/api/business-systems/" + other.getCode() + "/requests",
                browserContext.browser(), null, null), 403, "PERMISSION_DENIED");
        organizationPermissions.save(new OrganizationPermission(owner, read.getId(), "DISABLED"));
        assertError(request(HttpMethod.GET, fixture.collection(), browserContext.browser(), null, null), 403, "PERMISSION_DENIED");
    }

    /** 本人可修改与撤回待审批单，撤回后原单保持终态。 */
    @Test
    @DisplayName("C2-12 本人修改、撤回和状态冲突")
    void updatesAndWithdrawsPendingRequest() {
        RequestFixture fixture = lifecycleFixture();
        String path = createLifecycleRequest(fixture);
        ResponseEntity<JsonNode> changed = request(HttpMethod.PATCH, path, fixture.owner().browser(),
                fixture.owner().value(), Map.of("title", "新标题"));
        assertThat(changed.getStatusCode().value()).isEqualTo(200);
        assertThat(changed.getBody().path("title").asText()).isEqualTo("新标题");
        assertThat(changed.getBody().path("status").asText()).isEqualTo("PENDING");
        assertError(request(HttpMethod.PATCH, path, fixture.reviewer().browser(), fixture.reviewer().value(),
                Map.of("title", "他人标题")), 403, "PERMISSION_DENIED");
        ResponseEntity<JsonNode> withdrawn = requestAction(path + "/withdraw", fixture.owner());
        assertThat(withdrawn.getStatusCode().value()).isEqualTo(200);
        assertThat(withdrawn.getBody().path("status").asText()).isEqualTo("WITHDRAWN");
        assertError(request(HttpMethod.PATCH, path, fixture.owner().browser(), fixture.owner().value(),
                Map.of("title", "撤回后修改")), 409, "REQUEST_STATE_CONFLICT");
        assertError(requestAction(path + "/withdraw", fixture.owner()), 409, "REQUEST_STATE_CONFLICT");
        assertError(requestAction(path + "/approve", fixture.reviewer()), 409, "REQUEST_STATE_CONFLICT");
    }

    /** 仅申请人可删除自己的驳回记录，逻辑删除对列表及详情一致生效。 */
    @Test
    @DisplayName("C2-13 有权限可审核本人申请，只有本人能删除已驳回单")
    void rejectsAndDeletesOnlyOwnRejectedRequest() {
        RequestFixture fixture = lifecycleFixture();
        String path = createLifecycleRequest(fixture);
        assertAssignmentError(HttpMethod.DELETE, path, fixture.owner(), 409, "REQUEST_STATE_CONFLICT");
        ResponseEntity<JsonNode> rejected = requestAction(path + "/reject", fixture.owner());
        assertThat(rejected.getStatusCode().value()).isEqualTo(200);
        assertThat(rejected.getBody().path("status").asText()).isEqualTo("REJECTED");
        assertAssignmentError(HttpMethod.DELETE, path, fixture.reviewer(), 403, "PERMISSION_DENIED");
        assertNoContent(HttpMethod.DELETE, path, fixture.owner());
        assertThat(simulatedRequests.findById(rejected.getBody().path("id").asLong()).orElseThrow().getStatus())
                .isEqualTo("REJECTED");
        assertError(request(HttpMethod.GET, path, fixture.owner().browser(), null, null), 404, "REQUEST_NOT_FOUND");
        assertAssignmentError(HttpMethod.DELETE, path, fixture.owner(), 404, "REQUEST_NOT_FOUND");
        assertThat(request(HttpMethod.GET, fixture.collection(), fixture.owner().browser(), null, null)
                .getBody().path("total").asInt()).isZero();
        String selfApproved = createLifecycleRequest(fixture);
        assertThat(requestAction(selfApproved + "/approve", fixture.owner()).getStatusCode().value()).isEqualTo(200);
        assertError(requestAction(selfApproved + "/approve", fixture.owner()), 409, "REQUEST_ALREADY_APPROVED");
    }

    /** 审批、撤回和驳回使用同一个初始状态约束。 */
    @Test
    @DisplayName("C2-13 待审批单并发迁移只允许一个提交")
    void serializesCompetingRequestTransitions() throws Exception {
        RequestFixture fixture = lifecycleFixture();
        String first = createLifecycleRequest(fixture);
        List<Integer> firstRace = race(() -> assignmentStatus(HttpMethod.POST, first + "/approve", fixture.reviewer()),
                () -> assignmentStatus(HttpMethod.POST, first + "/withdraw", fixture.owner()));
        assertThat(firstRace).containsExactlyInAnyOrder(200, 409);
        assertThat(request(HttpMethod.GET, first, fixture.owner().browser(), null, null).getBody().path("status").asText())
                .isEqualTo(firstRace.get(0) == 200 ? "APPROVED" : "WITHDRAWN");

        String second = createLifecycleRequest(fixture);
        List<Integer> secondRace = race(() -> assignmentStatus(HttpMethod.POST, second + "/reject", fixture.reviewer()),
                () -> assignmentStatus(HttpMethod.POST, second + "/approve", fixture.owner()));
        assertThat(secondRace).containsExactlyInAnyOrder(200, 409);
        assertThat(request(HttpMethod.GET, second, fixture.owner().browser(), null, null).getBody().path("status").asText())
                .isEqualTo(secondRace.get(0) == 200 ? "REJECTED" : "APPROVED");
    }

    /** 请求体验证与角色权限均由接口拒绝，不能挟带服务端管理字段。 */
    @Test
    @DisplayName("C2-11 / C2-12 访问、请求体与 C1 遗留申请兼容")
    void validatesLifecycleInputsAndLegacyOwnership() {
        RequestFixture fixture = lifecycleFixture();
        Token owner = fixture.owner();
        assertError(request(HttpMethod.POST, fixture.collection(), null, null, Map.of("title", "待拒绝")),
                401, "AUTHENTICATION_REQUIRED");
        assertError(request(HttpMethod.POST, fixture.collection(), owner.browser(), owner.value(),
                Map.of("title", "  ")), 400, "VALIDATION_FAILED");
        assertError(request(HttpMethod.POST, fixture.collection(), owner.browser(), owner.value(),
                Map.of("title", "甲".repeat(129))), 400, "VALIDATION_FAILED");
        assertError(request(HttpMethod.POST, fixture.collection(), owner.browser(), owner.value(),
                Map.of("title", "伪造", "applicantId", "1", "status", "APPROVED")), 400, "VALIDATION_FAILED");
        assertError(request(HttpMethod.POST, "/api/business-systems/absent/requests", owner.browser(), owner.value(),
                Map.of("title", "待拒绝")), 404, "SYSTEM_NOT_FOUND");
        assertError(request(HttpMethod.GET, "/api/requests/" + Long.MAX_VALUE, owner.browser(), null, null),
                404, "REQUEST_NOT_FOUND");
        assertError(request(HttpMethod.GET, fixture.collection(), null, null, null), 401, "AUTHENTICATION_REQUIRED");
        Employee noGrant = employee(newPrefix() + "-no-grant", fixture.system().getOwnerOrganizationId());
        Token noGrantToken = token(browser(login(noGrant.getUsername(), PASSWORD, token(null))));
        assertError(request(HttpMethod.POST, fixture.collection(), noGrantToken.browser(), noGrantToken.value(),
                Map.of("title", "无权限")), 403, "PERMISSION_DENIED");

        SimulatedRequest legacy = simulatedRequests.save(new SimulatedRequest(fixture.system().getId(), "遗留申请"));
        String legacyPath = "/api/requests/" + legacy.getId();
        JsonNode body = request(HttpMethod.GET, legacyPath, owner.browser(), null, null).getBody();
        assertThat(body.path("applicantId").isNull()).isTrue();
        assertError(requestAction(legacyPath + "/withdraw", owner), 403, "PERMISSION_DENIED");
        assertThat(requestAction(legacyPath + "/approve", fixture.reviewer()).getStatusCode().value()).isEqualTo(200);
    }

    /** 框架日志之外保留可按动作和申请编号核对的提交结果。 */
    @Test
    @DisplayName("C2-14 Logback 记录发起、修改、读取和失败，不输出标题")
    void recordsRequestActionOutcomes() {
        RequestFixture fixture = lifecycleFixture();
        Logger logger = (Logger) LoggerFactory.getLogger("rbac1." + EDITION + ".request");
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        try {
            String path = createLifecycleRequest(fixture);
            assertThat(request(HttpMethod.GET, path, fixture.owner().browser(), null, null).getStatusCode().value())
                    .isEqualTo(200);
            assertThat(request(HttpMethod.PATCH, path, fixture.owner().browser(), fixture.owner().value(),
                    Map.of("title", "敏感标题-不能写进日志")).getStatusCode().value()).isEqualTo(200);
            assertError(requestAction(path + "/withdraw", fixture.reviewer()), 403, "PERMISSION_DENIED");
            assertThat(requestAction(path + "/withdraw", fixture.owner()).getStatusCode().value()).isEqualTo(200);
            assertError(requestAction(path + "/approve", fixture.reviewer()), 409, "REQUEST_STATE_CONFLICT");
            String id = path.substring(path.lastIndexOf('/') + 1);
            List<String> messages = events.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertThat(messages).anyMatch(line -> line.contains("action=CREATE") && line.contains("outcome=COMMITTED")
                    && line.contains("requestId=" + id));
            assertThat(messages).anyMatch(line -> line.contains("action=DETAIL") && line.contains("outcome=SUCCEEDED"));
            assertThat(messages).anyMatch(line -> line.contains("action=UPDATE") && line.contains("outcome=COMMITTED"));
            assertThat(messages).anyMatch(line -> line.contains("action=WITHDRAW") && line.contains("PERMISSION_DENIED"));
            assertThat(messages).anyMatch(line -> line.contains("action=APPROVE") && line.contains("REQUEST_STATE_CONFLICT"));
            assertThat(messages).noneMatch(line -> line.contains("敏感标题") || line.contains("password=" + PASSWORD));
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
    }

    /** 在同一系统内分别准备申请人与审核账号，避免全局权限掩盖普通角色判断。 */
    private RequestFixture lifecycleFixture() {
        String prefix = newPrefix();
        Organization company = organization(prefix, "COMPANY", null);
        BusinessSystem app = systems.save(system(prefix + "-app", company));
        RbacRole role = roles.save(new RbacRole(prefix + "-actions", company.getId()));
        for (String action : List.of("read", "create", "update", "withdraw", "approve", "reject", "delete")) {
            Permission permission = permissions.save(new Permission("request:" + action, action, app.getId(), null));
            rolePermissions.save(new RolePermission(role.getId(), permission.getId()));
        }
        Employee owner = employee(prefix + "-owner", company.getId());
        Employee reviewer = employee(prefix + "-reviewer", company.getId());
        employeeRoles.saveAll(List.of(new EmployeeRole(owner.getId(), role.getId()),
                new EmployeeRole(reviewer.getId(), role.getId())));
        Token ownerToken = token(browser(login(owner.getUsername(), PASSWORD, token(null))));
        Token reviewerToken = token(browser(login(reviewer.getUsername(), PASSWORD, token(null))));
        return new RequestFixture(app, "/api/business-systems/" + app.getCode() + "/requests", ownerToken, reviewerToken);
    }

    private String createLifecycleRequest(RequestFixture fixture) {
        Token actor = fixture.owner();
        ResponseEntity<JsonNode> response = request(HttpMethod.POST, fixture.collection(), actor.browser(), actor.value(),
                Map.of("title", "演示申请"));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return "/api/requests/" + response.getBody().path("id").asLong();
    }

    private ResponseEntity<JsonNode> requestAction(String endpoint, Token actor) {
        return request(HttpMethod.POST, endpoint, actor.browser(), actor.value(), null);
    }

    /** 为已有行为场景单独配置查看权，避免查看门槛掩盖被测动作。 */
    private void grantRead(Employee employee, BusinessSystem app) {
        Permission read = permissions.findBySystemIdAndCode(app.getId(), "request:read")
                .orElseGet(() -> permissions.save(new Permission("request:read", "查看申请", app.getId(), null)));
        RbacRole viewer = roles.save(new RbacRole(newPrefix() + "-viewer", employee.getOrganizationId()));
        employeeRoles.save(new EmployeeRole(employee.getId(), viewer.getId()));
        rolePermissions.save(new RolePermission(viewer.getId(), read.getId()));
    }
    private record RequestFixture(BusinessSystem system, String collection, Token owner, Token reviewer) {
    }

    /** C2 的继承写入须独立于 C1 的直接角色关系。 */
    @Test
    @DisplayName("C2-04 删除唯一继承路径后原会话下一次审批被拒绝")
    void inheritanceRevocationAppliesToExistingSession() {
        String prefix = newPrefix();
        Organization company = organization(prefix, "COMPANY", null);
        BusinessSystem app = systems.save(system(prefix + "-app", company));
        Permission approval = permissions.save(new Permission("request:approve", "审批", app.getId(), null));
        Employee employee = employee(prefix + "-worker", company.getId());
        Employee admin = employee(prefix + "-admin", company.getId());
        scopes.save(new AdministratorScope(admin.getId(), "GLOBAL", null));
        RbacRole supervisor = roles.save(new RbacRole(prefix, company.getId()));
        RbacRole approver = roles.save(new RbacRole(prefix + "-approver", company.getId()));
        grantRead(employee, app);
        Token manager = token(browser(login(admin.getUsername(), PASSWORD, token(null))));
        Token worker = token(browser(login(employee.getUsername(), PASSWORD, token(null))));
        String parents = "/api/roles/%d/parents".formatted(supervisor.getId());
        String edge = parents + "/" + approver.getId();
        SimulatedRequest first = simulatedRequests.save(new SimulatedRequest(app.getId(), "继承后审批"));
        SimulatedRequest second = simulatedRequests.save(new SimulatedRequest(app.getId(), "删边后拒绝"));

        assertNoContent(HttpMethod.PUT, EMPLOYEE_ROLE.formatted(employee.getId(), supervisor.getId()), manager);
        assertNoContent(HttpMethod.PUT, ROLE_PERMISSION.formatted(approver.getId(), approval.getId()), manager);
        assertError(approve(first, worker), 403, "PERMISSION_DENIED");
        assertNoContent(HttpMethod.PUT, edge, manager);
        assertNoContent(HttpMethod.PUT, edge, manager);
        String inherited = "/api/roles/%d/inherited-permissions?pageSize=1".formatted(supervisor.getId());
        ResponseEntity<JsonNode> inheritedPage = request(HttpMethod.GET, inherited, manager.browser(), null, null);
        assertThat(inheritedPage.getStatusCode().value()).isEqualTo(200);
        assertThat(inheritedPage.getBody().path("total").asInt()).isEqualTo(1);
        assertThat(inheritedPage.getBody().path("items").get(0).path("id").asLong()).isEqualTo(approval.getId());
        assertError(request(HttpMethod.GET, inherited, worker.browser(), null, null), 403, "PERMISSION_DENIED");
        assertThat(request(HttpMethod.GET, parents, manager.browser(), null, null).getBody().path("total").asInt())
                .isEqualTo(1);
        String effective = "/api/employees/%d/effective-permissions?systemCode=%s"
                .formatted(employee.getId(), app.getCode());
        assertThat(request(HttpMethod.GET, effective, manager.browser(), null, null).getBody()
                .path("items").get(0).path("id").asLong()).isEqualTo(approval.getId());
        assertError(request(HttpMethod.PUT, parents + "/" + supervisor.getId(), manager.browser(), manager.value(), null),
                409, "SELF_ROLE_INHERITANCE");
        assertError(request(HttpMethod.PUT, "/api/roles/%d/parents/%d".formatted(approver.getId(), supervisor.getId()),
                manager.browser(), manager.value(), null), 409, "ROLE_INHERITANCE_CYCLE");
        assertThat(approve(first, worker).getStatusCode().value()).isEqualTo(200);
        assertNoContent(HttpMethod.DELETE, edge, manager);
        assertNoContent(HttpMethod.DELETE, edge, manager);
        assertThat(request(HttpMethod.GET, inherited, manager.browser(), null, null)
                .getBody().path("items").size()).isZero();
        assertError(approve(second, worker), 403, "PERMISSION_DENIED");
        assertThat(simulatedRequests.findById(second.getId()).orElseThrow().getStatus()).isEqualTo("PENDING");
    }


    /** 仅锁住新边的两端无法拦住这种四角色闭环。 */
    @Test
    @DisplayName("C2-03 并发提交两条端点互不重叠的边只提交一条")
    void serializesInheritanceWritesWithDisjointEndpoints() throws Exception {
        String prefix = newPrefix();
        Organization company = organization(prefix, "COMPANY", null);
        Employee admin = employee(prefix + "-admin", company.getId());
        scopes.save(new AdministratorScope(admin.getId(), "GLOBAL", null));
        Token manager = token(browser(login(admin.getUsername(), PASSWORD, token(null))));
        RbacRole a = roles.save(new RbacRole(prefix + "-a", company.getId()));
        RbacRole b = roles.save(new RbacRole(prefix + "-b", company.getId()));
        RbacRole c = roles.save(new RbacRole(prefix + "-c", company.getId()));
        RbacRole d = roles.save(new RbacRole(prefix + "-d", company.getId()));
        String edge = "/api/roles/%d/parents/%d";

        assertNoContent(HttpMethod.PUT, edge.formatted(a.getId(), b.getId()), manager);
        assertNoContent(HttpMethod.PUT, edge.formatted(c.getId(), d.getId()), manager);
        assertThat(race(() -> assignmentStatus(HttpMethod.PUT, edge.formatted(b.getId(), c.getId()), manager),
                () -> assignmentStatus(HttpMethod.PUT, edge.formatted(d.getId(), a.getId()), manager)))
                .containsExactlyInAnyOrder(204, 409);
        assertError(request(HttpMethod.DELETE, "/api/roles/" + a.getId(), manager.browser(), manager.value(), null),
                409, "RESOURCE_CONFLICT");
        Organization other = organization(prefix + "-other", "COMPANY", null);
        RbacRole outside = roles.save(new RbacRole(prefix + "-outside", other.getId()));
        assertError(request(HttpMethod.PUT, edge.formatted(a.getId(), outside.getId()),
                manager.browser(), manager.value(), null), 409, "ROLE_INHERITANCE_SCOPE_MISMATCH");
    }

    /** 传递与直接来源要去重，且分散在两角色的重授不能拼成一个授权。 */
    @Test
    @DisplayName("C2-02 / C2-06 重授检查按直接来源且可沿继承取得")
    void matchesReopenedScopesToOneInheritedPermissionSource() {
        String prefix = newPrefix();
        Organization company = organization(prefix, "COMPANY", null);
        Organization department = organization(prefix + "-dept", "DEPARTMENT", company.getId());
        BusinessSystem app = systems.save(system(prefix + "-app", company));
        Permission approval = permissions.save(new Permission("request:approve", "审批", app.getId(), null));
        Employee employee = employee(prefix + "-worker", department.getId());
        Employee admin = employee(prefix + "-admin", company.getId());
        scopes.save(new AdministratorScope(admin.getId(), "GLOBAL", null));
        grantRead(employee, app);
        Token manager = token(browser(login(admin.getUsername(), PASSWORD, token(null))));
        Token worker = token(browser(login(employee.getUsername(), PASSWORD, token(null))));
        RbacRole senior = roles.save(new RbacRole(prefix + "-senior", department.getId()));
        RbacRole middle = roles.save(new RbacRole(prefix + "-middle", department.getId()));
        RbacRole first = roles.save(new RbacRole(prefix + "-first", department.getId()));
        RbacRole second = roles.save(new RbacRole(prefix + "-second", department.getId()));
        String edge = "/api/roles/%d/parents/%d";
        String companyPolicy = POLICY.formatted(company.getId(), app.getCode());
        String departmentPolicy = POLICY.formatted(department.getId(), app.getCode());
        String effective = "/api/employees/%d/effective-permissions?systemCode=%s"
                .formatted(employee.getId(), app.getCode());
        SimulatedRequest openRequest = simulatedRequests.save(new SimulatedRequest(app.getId(), "多路径审批"));
        SimulatedRequest restoredRequest = simulatedRequests.save(new SimulatedRequest(app.getId(), "重授后审批"));

        assertNoContent(HttpMethod.PUT, EMPLOYEE_ROLE.formatted(employee.getId(), senior.getId()), manager);
        assertNoContent(HttpMethod.PUT, ROLE_PERMISSION.formatted(first.getId(), approval.getId()), manager);
        assertNoContent(HttpMethod.PUT, ROLE_PERMISSION.formatted(second.getId(), approval.getId()), manager);
        assertNoContent(HttpMethod.PUT, edge.formatted(senior.getId(), middle.getId()), manager);
        assertNoContent(HttpMethod.PUT, edge.formatted(middle.getId(), first.getId()), manager);
        assertNoContent(HttpMethod.PUT, edge.formatted(senior.getId(), first.getId()), manager);
        assertNoContent(HttpMethod.PUT, edge.formatted(senior.getId(), second.getId()), manager);
        assertThat(request(HttpMethod.GET, effective, manager.browser(), null, null).getBody().path("total").asInt())
                .isEqualTo(2);
        assertNoContent(HttpMethod.DELETE, edge.formatted(senior.getId(), first.getId()), manager);
        assertThat(approve(openRequest, worker).getStatusCode().value()).isEqualTo(200);

        assertNoContent(HttpMethod.PUT, companyPolicy + "/disabled", manager);
        assertNoContent(HttpMethod.PUT, departmentPolicy + "/disabled", manager);
        assertError(approve(restoredRequest, worker), 403, "PERMISSION_DENIED");
        assertNoContent(HttpMethod.DELETE, companyPolicy + "/disabled", manager);
        assertNoContent(HttpMethod.DELETE, departmentPolicy + "/disabled", manager);
        assertNoContent(HttpMethod.PUT, companyPolicy + "/regrants/" + first.getId(), manager);
        assertNoContent(HttpMethod.PUT, departmentPolicy + "/regrants/" + second.getId(), manager);
        assertError(approve(restoredRequest, worker), 403, "PERMISSION_DENIED");
        assertNoContent(HttpMethod.PUT, departmentPolicy + "/regrants/" + first.getId(), manager);
        assertThat(approve(restoredRequest, worker).getStatusCode().value()).isEqualTo(200);
    }


    /** 替换授予入口不能绕过继承图的范围及停用检查。 */
    @Test
    @DisplayName("C2-07 授予继承角色和授予低级角色权限时都检查停用范围")
    void rejectsAssignmentsThatWouldRestoreDisabledInheritedPermission() {
        String prefix = newPrefix();
        Organization company = organization(prefix, "COMPANY", null);
        Organization department = organization(prefix + "-dept", "DEPARTMENT", company.getId());
        Employee employee = employee(prefix + "-worker", department.getId());
        Employee admin = employee(prefix + "-admin", company.getId());
        scopes.save(new AdministratorScope(admin.getId(), "GLOBAL", null));
        Token manager = token(browser(login(admin.getUsername(), PASSWORD, token(null))));
        RbacRole senior = roles.save(new RbacRole(prefix + "-senior", company.getId()));
        RbacRole junior = roles.save(new RbacRole(prefix + "-junior", company.getId()));
        BusinessSystem app = systems.save(system(prefix + "-app", company));
        Permission permission = permissions.save(new Permission("request:approve", "审批", app.getId(), null));
        String edge = "/api/roles/%d/parents/%d".formatted(senior.getId(), junior.getId());
        String member = EMPLOYEE_ROLE.formatted(employee.getId(), senior.getId());
        String grant = ROLE_PERMISSION.formatted(junior.getId(), permission.getId());
        String disabled = POLICY.formatted(department.getId(), app.getCode()) + "/disabled";

        assertNoContent(HttpMethod.PUT, edge, manager);
        assertNoContent(HttpMethod.PUT, member, manager);
        assertNoContent(HttpMethod.PUT, disabled, manager);
        assertAssignmentError(HttpMethod.PUT, grant, manager, 409, "ASSIGNMENT_CONFLICT");
        assertNoContent(HttpMethod.DELETE, member, manager);
        assertNoContent(HttpMethod.PUT, grant, manager);
        assertAssignmentError(HttpMethod.PUT, member, manager, 409, "ASSIGNMENT_CONFLICT");
    }

    /** 使用独立管理员会话修改分配，用同一员工会话观察下一次审批结果。 */
    @Test
    @DisplayName("C1-M2-04 两类授撤权提交后在同一员工会话生效")
    void assignmentChangesApplyToExistingSession() {
        String prefix = newPrefix();
        Organization company = organization(prefix, "COMPANY", null);
        BusinessSystem app = systems.save(system(prefix + "-app", company));
        Permission approval = permissions.save(new Permission("request:approve", "审批", app.getId(), null));
        Employee employee = employee(prefix + "-employee", company.getId());
        Employee admin = employee(prefix + "-admin", company.getId());
        scopes.save(new AdministratorScope(admin.getId(), "GLOBAL", null));
        RbacRole role = roles.save(new RbacRole(prefix, company.getId()));
        RbacRole alternate = roles.save(new RbacRole(prefix + "-alternate", company.getId()));
        grantRead(employee, app);
        Token manager = token(browser(login(admin.getUsername(), PASSWORD, token(null))));
        Token worker = token(browser(login(employee.getUsername(), PASSWORD, token(null))));
        String employeeRole = EMPLOYEE_ROLE.formatted(employee.getId(), role.getId());
        String rolePermission = ROLE_PERMISSION.formatted(role.getId(), approval.getId());
        String alternateEmployeeRole = EMPLOYEE_ROLE.formatted(employee.getId(), alternate.getId());
        String alternateRolePermission = ROLE_PERMISSION.formatted(alternate.getId(), approval.getId());
        SimulatedRequest first = simulatedRequests.save(new SimulatedRequest(app.getId(), "员工角色授予"));
        SimulatedRequest second = simulatedRequests.save(new SimulatedRequest(app.getId(), "角色权限授予"));
        SimulatedRequest third = simulatedRequests.save(new SimulatedRequest(app.getId(), "撤销后仍有路径"));
        SimulatedRequest fourth = simulatedRequests.save(new SimulatedRequest(app.getId(), "撤销最后路径"));

        assertError(approve(first, worker), 403, "PERMISSION_DENIED");
        assertNoContent(HttpMethod.PUT, rolePermission, manager);
        assertNoContent(HttpMethod.PUT, employeeRole, manager);
        assertNoContent(HttpMethod.PUT, employeeRole, manager);
        assertThat(approve(first, worker).getStatusCode().value()).isEqualTo(200);
        assertNoContent(HttpMethod.DELETE, employeeRole, manager);
        assertNoContent(HttpMethod.DELETE, employeeRole, manager);
        assertError(approve(second, worker), 403, "PERMISSION_DENIED");
        assertNoContent(HttpMethod.DELETE, rolePermission, manager);
        assertNoContent(HttpMethod.PUT, employeeRole, manager);
        assertError(approve(second, worker), 403, "PERMISSION_DENIED");
        assertNoContent(HttpMethod.PUT, rolePermission, manager);
        assertNoContent(HttpMethod.PUT, rolePermission, manager);
        assertThat(approve(second, worker).getStatusCode().value()).isEqualTo(200);

        assertNoContent(HttpMethod.PUT, alternateRolePermission, manager);
        assertNoContent(HttpMethod.PUT, alternateEmployeeRole, manager);
        assertNoContent(HttpMethod.DELETE, rolePermission, manager);
        assertNoContent(HttpMethod.DELETE, rolePermission, manager);
        assertThat(approve(third, worker).getStatusCode().value()).isEqualTo(200);
        assertNoContent(HttpMethod.DELETE, alternateRolePermission, manager);
        assertError(approve(fourth, worker), 403, "PERMISSION_DENIED");
        assertThat(simulatedRequests.findById(fourth.getId()).orElseThrow().getStatus()).isEqualTo("PENDING");
    }

    /** 拦截普通员工、跨部门操作、全局共享角色修改以及未开放的上级权限。 */
    @Test
    @DisplayName("C1-M2-05 / C1-09 授撤权要求管理身份、组织范围和上级开放")
    void checksAssignmentScopeAndDelegation() {
        String prefix = newPrefix();
        Organization company = organization(prefix, "COMPANY", null);
        Organization department = organization(prefix + "-dep", "DEPARTMENT", company.getId());
        Organization peer = organization(prefix + "-peer", "DEPARTMENT", company.getId());
        BusinessSystem companyApp = systems.save(system(prefix + "-company-app", company));
        Permission approval = permissions.save(new Permission("request:approve", "审批", companyApp.getId(), null));
        Permission manage = permissions.save(new Permission(prefix + "-management", "管理分配", null, null));
        RbacRole local = roles.save(new RbacRole(prefix, department.getId()));
        RbacRole shared = roles.save(new RbacRole(prefix + "-shared", null));
        Employee departmentAdmin = employee(prefix + "-admin", department.getId());
        scopes.save(new AdministratorScope(departmentAdmin.getId(), "ORGANIZATION", department.getId()));
        Employee companyAdmin = employee(prefix + "-company-admin", company.getId());
        scopes.save(new AdministratorScope(companyAdmin.getId(), "ORGANIZATION", company.getId()));
        Employee member = employee(prefix + "-member", department.getId());
        Employee peerMember = employee(prefix + "-peer-member", peer.getId());
        Token manager = token(browser(login(departmentAdmin.getUsername(), PASSWORD, token(null))));
        Token companyManager = token(browser(login(companyAdmin.getUsername(), PASSWORD, token(null))));
        Token worker = token(browser(login(member.getUsername(), PASSWORD, token(null))));
        String localRole = EMPLOYEE_ROLE.formatted(member.getId(), local.getId());
        String peerRole = EMPLOYEE_ROLE.formatted(peerMember.getId(), local.getId());
        String localPermission = ROLE_PERMISSION.formatted(local.getId(), approval.getId());
        String sharedPermission = ROLE_PERMISSION.formatted(shared.getId(), approval.getId());
        String managerPermission = ROLE_PERMISSION.formatted(local.getId(), manage.getId());

        assertAssignmentError(HttpMethod.PUT, localRole, worker, 403, "PERMISSION_DENIED");
        assertAssignmentError(HttpMethod.PUT, localPermission, worker, 403, "PERMISSION_DENIED");
        assertAssignmentError(HttpMethod.PUT, peerRole, manager, 403, "PERMISSION_DENIED");
        assertAssignmentError(HttpMethod.DELETE, peerRole, manager, 403, "PERMISSION_DENIED");
        assertAssignmentError(HttpMethod.PUT, sharedPermission, manager, 403, "PERMISSION_DENIED");
        assertAssignmentError(HttpMethod.PUT, managerPermission, manager, 403, "PERMISSION_DENIED");
        assertAssignmentError(HttpMethod.PUT, localPermission, manager, 403, "PERMISSION_DENIED");
        assertThat(rolePermissions.existsById(new RolePermission.Key(local.getId(), approval.getId()))).isFalse();

        organizationPermissions.save(new OrganizationPermission(department.getId(), approval.getId(), "OPEN"));
        assertNoContent(HttpMethod.PUT, localPermission, manager);
        assertNoContent(HttpMethod.PUT, localRole, companyManager);
        assertNoContent(HttpMethod.DELETE, localRole, manager);
        assertNoContent(HttpMethod.DELETE, localPermission, manager);

        employeeRoles.save(new EmployeeRole(peerMember.getId(), local.getId()));
        assertAssignmentError(HttpMethod.PUT, localPermission, manager, 403, "PERMISSION_DENIED");
        assertThat(rolePermissions.existsById(new RolePermission.Key(local.getId(), approval.getId()))).isFalse();
    }

    /** 检查非法标识、缺失资源以及禁止建立分配的目标状态。 */
    @Test
    @DisplayName("C1-M2-05 授撤权参数、CSRF 和目标状态错误")
    void rejectsInvalidAssignmentTargets() {
        String prefix = newPrefix();
        Organization company = organization(prefix, "COMPANY", null);
        Employee admin = employee(prefix + "-admin", company.getId());
        scopes.save(new AdministratorScope(admin.getId(), "GLOBAL", null));
        Employee pending = employee(prefix + "-pending", null);
        BusinessSystem app = systems.save(system(prefix + "-app", company));
        RbacRole role = roles.save(new RbacRole(prefix, company.getId()));
        Permission approval = permissions.save(new Permission("request:approve", "审批", app.getId(), null));
        organizationPermissions.save(new OrganizationPermission(company.getId(), approval.getId(), "DISABLED"));
        Token manager = token(browser(login(admin.getUsername(), PASSWORD, token(null))));
        String employeeRole = EMPLOYEE_ROLE.formatted(pending.getId(), role.getId());
        String rolePermission = ROLE_PERMISSION.formatted(role.getId(), approval.getId());

        assertAssignmentError(HttpMethod.PUT, EMPLOYEE_ROLE.formatted(0L, role.getId()), manager,
                400, "VALIDATION_FAILED");
        assertAssignmentError(HttpMethod.DELETE, EMPLOYEE_ROLE.formatted(Long.MAX_VALUE, role.getId()), manager,
                404, "RESOURCE_NOT_FOUND");
        assertAssignmentError(HttpMethod.PUT, ROLE_PERMISSION.formatted(role.getId(), Long.MAX_VALUE), manager,
                404, "RESOURCE_NOT_FOUND");
        assertError(request(HttpMethod.PUT, rolePermission, manager.browser(), null, null), 409, "ASSIGNMENT_CONFLICT");
        assertAssignmentError(HttpMethod.PUT, employeeRole, manager, 409, "ASSIGNMENT_CONFLICT");
        assertAssignmentError(HttpMethod.PUT, rolePermission, manager, 409, "ASSIGNMENT_CONFLICT");
        assertNoContent(HttpMethod.DELETE, employeeRole, manager);
        assertNoContent(HttpMethod.DELETE, rolePermission, manager);
    }

    /** 并发重复 PUT 都返回成功，数据库中仍只有一条关系。 */
    @Test
    @DisplayName("C1-M2-04 并发授予同一员工角色保持幂等")
    void grantsSameRoleConcurrently() throws Exception {
        String prefix = newPrefix();
        Organization company = organization(prefix, "COMPANY", null);
        Employee member = employee(prefix + "-member", company.getId());
        Employee admin = employee(prefix + "-admin", company.getId());
        scopes.save(new AdministratorScope(admin.getId(), "GLOBAL", null));
        RbacRole role = roles.save(new RbacRole(prefix, company.getId()));
        Token firstAdmin = token(browser(login(admin.getUsername(), PASSWORD, token(null))));
        Token secondAdmin = token(browser(login(admin.getUsername(), PASSWORD, token(null))));
        String path = EMPLOYEE_ROLE.formatted(member.getId(), role.getId());
        CyclicBarrier start = new CyclicBarrier(2);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = executor.submit(() -> {
                start.await(3, TimeUnit.SECONDS);
                return request(HttpMethod.PUT, path, firstAdmin.browser(), firstAdmin.value(), null).getStatusCode().value();
            });
            Future<Integer> second = executor.submit(() -> {
                start.await(3, TimeUnit.SECONDS);
                return request(HttpMethod.PUT, path, secondAdmin.browser(), secondAdmin.value(), null).getStatusCode().value();
            });
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactly(204, 204);
        }
        assertThat(employeeRoles.existsById(new EmployeeRole.Key(member.getId(), role.getId()))).isTrue();
    }

    /** 管理请求拒绝时同时核对状态码和错误体。 */
    private void assertAssignmentError(HttpMethod method, String path, Token csrf, int status, String code) {
        assertError(request(method, path, csrf.browser(), csrf.value(), null), status, code);
    }

    /** 验证公司与部门重开后须在同一角色路径上重授，撤销角色权限后重授失效。 */
    @Test
    @DisplayName("C1-13 同会话停用、重新开放和重授")
    void reopensAndRegrantsOnlyExplicitRolePaths() {
        String prefix = newPrefix();
        Organization company = organization(prefix, "COMPANY", null);
        Organization department = organization(prefix + "-dep", "DEPARTMENT", company.getId());
        BusinessSystem app = systems.save(system(prefix + "-app", department));
        Employee admin = employee(prefix + "-admin", company.getId());
        scopes.save(new AdministratorScope(admin.getId(), "GLOBAL", null));
        Employee worker = employee(prefix + "-worker", department.getId());
        RbacRole first = roles.save(new RbacRole(prefix + "-first", department.getId()));
        RbacRole second = roles.save(new RbacRole(prefix + "-second", department.getId()));
        Permission approval = permissions.save(new Permission("request:approve", "审批", app.getId(), null));
        employeeRoles.saveAll(List.of(new EmployeeRole(worker.getId(), first.getId()),
                new EmployeeRole(worker.getId(), second.getId())));
        rolePermissions.saveAll(List.of(new RolePermission(first.getId(), approval.getId()),
                new RolePermission(second.getId(), approval.getId())));
        grantRead(worker, app);
        Token manager = token(browser(login(admin.getUsername(), PASSWORD, token(null))));
        Token employee = token(browser(login(worker.getUsername(), PASSWORD, token(null))));
        String companyPolicy = POLICY.formatted(company.getId(), app.getCode());
        String departmentPolicy = POLICY.formatted(department.getId(), app.getCode());
        SimulatedRequest adminRequest = simulatedRequests.save(new SimulatedRequest(app.getId(), "管理员审批"));
        SimulatedRequest workerRequest = simulatedRequests.save(new SimulatedRequest(app.getId(), "员工审批"));

        assertAssignmentError(HttpMethod.PUT, companyPolicy + "/disabled", employee, 403, "PERMISSION_DENIED");
        assertAssignmentError(HttpMethod.GET, companyPolicy, employee, 403, "PERMISSION_DENIED");
        assertThat(request(HttpMethod.GET, departmentPolicy, manager.browser(), null, null)
                .getBody().path("status").asText()).isEqualTo("OPEN");
        assertNoContent(HttpMethod.PUT, departmentPolicy + "/disabled", manager);
        assertThat(request(HttpMethod.GET, departmentPolicy, manager.browser(), null, null)
                .getBody().path("status").asText()).isEqualTo("DISABLED");
        assertNoContent(HttpMethod.PUT, companyPolicy + "/disabled", manager);
        assertError(approve(adminRequest, manager), 403, "PERMISSION_DENIED");
        assertNoContent(HttpMethod.DELETE, companyPolicy + "/disabled", manager);
        assertError(approve(adminRequest, manager), 403, "PERMISSION_DENIED");
        assertNoContent(HttpMethod.DELETE, departmentPolicy + "/disabled", manager);
        assertNoContent(HttpMethod.DELETE, departmentPolicy + "/disabled", manager);
        assertThat(approve(adminRequest, manager).getStatusCode().value()).isEqualTo(200);
        assertError(approve(workerRequest, employee), 403, "PERMISSION_DENIED");
        assertNoContent(HttpMethod.PUT, companyPolicy + "/regrants/" + first.getId(), manager);
        assertNoContent(HttpMethod.PUT, departmentPolicy + "/regrants/" + second.getId(), manager);
        assertError(approve(workerRequest, employee), 403, "PERMISSION_DENIED");
        assertNoContent(HttpMethod.PUT, departmentPolicy + "/regrants/" + first.getId(), manager);
        assertThat(approve(workerRequest, employee).getStatusCode().value()).isEqualTo(200);

        String relation = ROLE_PERMISSION.formatted(first.getId(), approval.getId());
        assertNoContent(HttpMethod.DELETE, relation, manager);
        assertNoContent(HttpMethod.PUT, relation, manager);
        SimulatedRequest afterRebuild = simulatedRequests.save(new SimulatedRequest(app.getId(), "关系重建"));
        assertError(approve(afterRebuild, employee), 403, "PERMISSION_DENIED");
    }

    /** 共享角色在同公司其他部门保持有效，且管理员被撤权不删除其历史分配。 */
    @Test
    @DisplayName("C1-13 部门停用隔离及管理员撤权后保留已有分配")
    void isolatesDepartmentPolicyAndKeepsAssignments() {
        String prefix = newPrefix();
        Organization company = organization(prefix, "COMPANY", null);
        Organization engineering = organization(prefix + "-eng", "DEPARTMENT", company.getId());
        Organization finance = organization(prefix + "-fin", "DEPARTMENT", company.getId());
        BusinessSystem app = systems.save(system(prefix + "-app", company));
        Employee admin = employee(prefix + "-admin", company.getId());
        Employee operator = employee(prefix + "-operator", company.getId());
        scopes.save(new AdministratorScope(admin.getId(), "GLOBAL", null));
        scopes.save(new AdministratorScope(operator.getId(), "GLOBAL", null));
        Employee engineer = employee(prefix + "-engineer", engineering.getId());
        Employee accountant = employee(prefix + "-accountant", finance.getId());
        RbacRole shared = roles.save(new RbacRole(prefix + "-shared", null));
        Permission approval = permissions.save(new Permission("request:approve", "审批", app.getId(), null));
        grantRead(engineer, app);
        grantRead(accountant, app);
        Token manager = token(browser(login(admin.getUsername(), PASSWORD, token(null))));
        Token grantingAdmin = token(browser(login(operator.getUsername(), PASSWORD, token(null))));
        Token engineerSession = token(browser(login(engineer.getUsername(), PASSWORD, token(null))));
        Token financeSession = token(browser(login(accountant.getUsername(), PASSWORD, token(null))));
        String rolePermission = ROLE_PERMISSION.formatted(shared.getId(), approval.getId());
        assertNoContent(HttpMethod.PUT, rolePermission, grantingAdmin);
        assertNoContent(HttpMethod.PUT, EMPLOYEE_ROLE.formatted(engineer.getId(), shared.getId()), grantingAdmin);
        assertNoContent(HttpMethod.PUT, EMPLOYEE_ROLE.formatted(accountant.getId(), shared.getId()), grantingAdmin);
        scopes.deleteById(operator.getId());
        assertAssignmentError(HttpMethod.DELETE, rolePermission, grantingAdmin, 403, "PERMISSION_DENIED");
        SimulatedRequest beforeDisable = simulatedRequests.save(new SimulatedRequest(app.getId(), "撤权后的原有分配"));
        assertThat(approve(beforeDisable, engineerSession).getStatusCode().value()).isEqualTo(200);

        String policy = POLICY.formatted(engineering.getId(), app.getCode());
        String regrant = policy + "/regrants/" + shared.getId();
        SimulatedRequest engineeringRequest = simulatedRequests.save(new SimulatedRequest(app.getId(), "工程审批"));
        SimulatedRequest financeRequest = simulatedRequests.save(new SimulatedRequest(app.getId(), "财务审批"));
        assertNoContent(HttpMethod.PUT, policy + "/disabled", manager);
        assertError(approve(engineeringRequest, engineerSession), 403, "PERMISSION_DENIED");
        assertThat(approve(financeRequest, financeSession).getStatusCode().value()).isEqualTo(200);
        assertAssignmentError(HttpMethod.PUT, regrant, manager, 409, "ASSIGNMENT_CONFLICT");
        assertNoContent(HttpMethod.DELETE, policy + "/disabled", manager);
        assertNoContent(HttpMethod.PUT, regrant, manager);
        assertNoContent(HttpMethod.PUT, regrant, manager);
        assertThat(approve(engineeringRequest, engineerSession).getStatusCode().value()).isEqualTo(200);
        assertNoContent(HttpMethod.PUT, policy + "/disabled", manager);
        assertNoContent(HttpMethod.DELETE, policy + "/disabled", manager);
        SimulatedRequest afterSecondDisable = simulatedRequests.save(new SimulatedRequest(app.getId(), "再次停用"));
        assertError(approve(afterSecondDisable, engineerSession), 403, "PERMISSION_DENIED");
    }

    /** 并发停用、重开和重授后的数据库状态与后续审批一致。 */
    @Test
    @DisplayName("C1-13 组织策略并发提交不保留失效重授")
    void serializesPolicyChanges() throws Exception {
        String prefix = newPrefix();
        Organization company = organization(prefix, "COMPANY", null);
        BusinessSystem app = systems.save(system(prefix + "-app", company));
        Employee admin = employee(prefix + "-admin", company.getId());
        Employee worker = employee(prefix + "-worker", company.getId());
        scopes.save(new AdministratorScope(admin.getId(), "GLOBAL", null));
        RbacRole role = roles.save(new RbacRole(prefix, company.getId()));
        Permission permission = permissions.save(new Permission("request:approve", "审批", app.getId(), null));
        employeeRoles.save(new EmployeeRole(worker.getId(), role.getId()));
        rolePermissions.save(new RolePermission(role.getId(), permission.getId()));
        grantRead(worker, app);
        Token manager = token(browser(login(admin.getUsername(), PASSWORD, token(null))));
        Token secondManager = token(browser(login(admin.getUsername(), PASSWORD, token(null))));
        Token workerBrowser = token(browser(login(worker.getUsername(), PASSWORD, token(null))));
        String policy = POLICY.formatted(company.getId(), app.getCode());
        String disable = policy + "/disabled";
        String regrant = policy + "/regrants/" + role.getId();
        OrganizationPermission.Key policyKey = new OrganizationPermission.Key(company.getId(), permission.getId());
        PermissionRegrant.Key regrantKey = new PermissionRegrant.Key(company.getId(), role.getId(), permission.getId());
        assertNoContent(HttpMethod.PUT, disable, manager);
        assertNoContent(HttpMethod.DELETE, disable, manager);

        // 重授若先提交，后续停用清除它；若停用先提交，重授必须冲突。
        assertThat(race(() -> assignmentStatus(HttpMethod.PUT, regrant, manager),
                () -> assignmentStatus(HttpMethod.PUT, disable, secondManager))).satisfies(statuses -> {
            assertThat(statuses.get(0)).isIn(204, 409);
            assertThat(statuses.get(1)).isEqualTo(204);
        });
        assertThat(organizationPermissions.findById(policyKey).orElseThrow().getStatus()).isEqualTo("DISABLED");
        assertThat(regrants.existsById(regrantKey)).isFalse();

        // 从停用状态抢占重开，只有重开先提交时重授可以通过。
        List<Integer> reopened = race(() -> assignmentStatus(HttpMethod.PUT, regrant, manager),
                () -> assignmentStatus(HttpMethod.DELETE, disable, secondManager));
        assertThat(reopened.get(0)).isIn(204, 409);
        assertThat(reopened.get(1)).isEqualTo(204);
        assertThat(organizationPermissions.findById(policyKey).orElseThrow().getStatus()).isEqualTo("REOPENED");
        assertThat(regrants.existsById(regrantKey)).isEqualTo(reopened.get(0) == 204);
        SimulatedRequest pending = simulatedRequests.save(new SimulatedRequest(app.getId(), "并发策略验收"));
        assertThat(approve(pending, workerBrowser).getStatusCode().value()).isEqualTo(reopened.get(0) == 204 ? 200 : 403);
    }

    /** 同时释放两个 HTTP 请求，并保留响应顺序用于检查具体操作。 */
    private List<Integer> race(java.util.concurrent.Callable<Integer> firstAction,
                               java.util.concurrent.Callable<Integer> secondAction) throws Exception {
        CyclicBarrier start = new CyclicBarrier(2);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = executor.submit(() -> {
                start.await(3, TimeUnit.SECONDS);
                return firstAction.call();
            });
            Future<Integer> second = executor.submit(() -> {
                start.await(3, TimeUnit.SECONDS);
                return secondAction.call();
            });
            return List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
    }

    /** 返回当前会话执行组织策略写入的 HTTP 状态。 */
    private int assignmentStatus(HttpMethod method, String path, Token token) {
        return request(method, path, token.browser(), token.value(), null).getStatusCode().value();
    }

    /** 管理关系接口的成功响应无正文。 */
    private void assertNoContent(HttpMethod method, String path, Token csrf) {
        ResponseEntity<JsonNode> response = request(method, path, csrf.browser(), csrf.value(), null);
        assertThat(response.getStatusCode().value()).as("%s %s: %s", method, path, response.getBody())
                .isEqualTo(204);
        assertThat(response.getBody()).isNull();
    }

    /** 登录记录TTL到期后，签名仍有效的JWT也不能恢复身份。 */
    @Test
    @DisplayName("C1-M2-01 登录记录到期拒绝旧JWT")
    void expiresLoginRecord() {
        String prefix = newPrefix();
        Organization company = organization(prefix, "COMPANY", null);
        Employee employee = employee(prefix, company.getId());
        Set<String> before = redis.keys(NAMESPACE);
        Browser browser = browser(login(employee.getUsername(), PASSWORD, token(null)));
        List<String> created = redis.keys(NAMESPACE).stream().filter(key -> !before.contains(key)).toList();
        assertThat(created).hasSize(1);
        redis.expire(created.getFirst(), Duration.ofMillis(100));
        await().atMost(Duration.ofSeconds(3)).until(() -> Boolean.FALSE.equals(redis.hasKey(created.getFirst())));
        assertError(request(HttpMethod.GET, ME, browser, null, null), 401, "AUTHENTICATION_REQUIRED");
    }

    /** 公司管理员的员工分页与部门目录只包含其管理范围。 */
    @Test
    @DisplayName("C1-01 / C1-08 管理查询范围、分页总数、电话遮盖")
    void scopesManagementDirectory() {
        String prefix = newPrefix();
        Organization company = organization(prefix, "COMPANY", null);
        Organization department = organizations.save(new Organization(prefix + "-dep", "测试部门", "DEPARTMENT",
                company.getId(), "测试经理", "原表上级"));
        Organization other = organization(prefix + "-other", "COMPANY", null);
        Employee admin = employee(prefix, company.getId());
        scopes.save(new AdministratorScope(admin.getId(), "ORGANIZATION", company.getId()));
        employees.save(new Employee(prefix + "-local", "本部门员工", department.getId(),
                "原分公司", "原部门", "测试岗位", "13800138000"));
        Employee outsider = employee(prefix + "-outside", other.getId());
        Browser adminBrowser = browser(login(admin.getUsername(), PASSWORD, token(null)));
        Browser workerBrowser = browser(login(outsider.getUsername(), PASSWORD, token(null)));

        ResponseEntity<JsonNode> result = request(HttpMethod.GET, ADMIN_EMPLOYEES + "?page=2&pageSize=1",
                adminBrowser, null, null);
        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getBody().path("total").asLong()).isEqualTo(2);
        assertThat(result.getBody().path("items").get(0).path("phone").asText()).isEqualTo("138****8000");
        JsonNode directory = request(HttpMethod.GET, ADMIN_DEPARTMENTS, adminBrowser, null, null).getBody();
        assertThat(directory.path("total").asLong()).isEqualTo(1);
        assertThat(directory.path("items").get(0).path("sourceParentName").asText()).isEqualTo("原表上级");
        ResponseEntity<JsonNode> ordinary = request(HttpMethod.GET, ADMIN_EMPLOYEES, workerBrowser, null, null);
        assertThat(ordinary.getStatusCode().value()).isEqualTo(200);
        assertThat(ordinary.getBody().path("total").asLong()).isEqualTo(1);
        assertThat(ordinary.getBody().path("items").get(0).has("phone")).isFalse();
        assertError(request(HttpMethod.GET, ADMIN_DEPARTMENTS, workerBrowser, null, null), 403, "PERMISSION_DENIED");
    }

    /** 分配查询只返回可见关系，并按现有角色及组织策略变化刷新。 */
    @Test
    @DisplayName("C1-08 / C1-09 关联查询、上级开放权限及越权拒绝")
    void scopesAssignmentQueries() {
        String prefix = newPrefix();
        Organization company = organization(prefix, "COMPANY", null);
        Organization local = organization(prefix + "-dep", "DEPARTMENT", company.getId());
        Organization peer = organization(prefix + "-peer", "DEPARTMENT", company.getId());
        BusinessSystem companyApp = systems.save(system(prefix + "-company-app", company));
        BusinessSystem peerApp = systems.save(system(prefix + "-peer-app", peer));
        Employee admin = employee(prefix + "-admin", local.getId());
        scopes.save(new AdministratorScope(admin.getId(), "ORGANIZATION", local.getId()));
        Employee global = employee(prefix + "-global", company.getId());
        scopes.save(new AdministratorScope(global.getId(), "GLOBAL", null));
        Employee member = employee(prefix + "-member", local.getId());
        Employee outsider = employee(prefix + "-outsider", peer.getId());
        RbacRole localRole = roles.save(new RbacRole(prefix, local.getId()));
        RbacRole peerRole = roles.save(new RbacRole(prefix + "-peer-role", peer.getId()));
        Permission companyApproval = permissions.save(new Permission("request:approve", "公司审批", companyApp.getId(), null));
        Permission peerApproval = permissions.save(new Permission("request:approve", "其他部门审批", peerApp.getId(), null));
        organizationPermissions.save(new OrganizationPermission(company.getId(), companyApproval.getId(), "OPEN"));
        employeeRoles.save(new EmployeeRole(member.getId(), localRole.getId()));
        rolePermissions.saveAll(List.of(new RolePermission(localRole.getId(), companyApproval.getId()),
                new RolePermission(localRole.getId(), peerApproval.getId())));
        Browser adminBrowser = browser(login(admin.getUsername(), PASSWORD, token(null)));
        Browser globalBrowser = browser(login(global.getUsername(), PASSWORD, token(null)));
        String rolePage = "/api/roles/" + localRole.getId() + "/permissions";
        String employeePage = ADMIN_EMPLOYEES + "/" + member.getId() + "/roles";

        JsonNode assigned = request(HttpMethod.GET, rolePage, adminBrowser, null, null).getBody();
        assertThat(assigned.path("total").asLong()).isEqualTo(1);
        assertThat(assigned.path("items").get(0).path("id").asLong()).isEqualTo(companyApproval.getId());
        assertThat(request(HttpMethod.GET, rolePage, globalBrowser, null, null).getBody().path("total").asLong())
                .isEqualTo(2);
        assertThat(request(HttpMethod.GET, employeePage, adminBrowser, null, null).getBody().path("total").asLong())
                .isEqualTo(1);
        assertError(request(HttpMethod.GET, ADMIN_EMPLOYEES + "/" + outsider.getId() + "/roles",
                adminBrowser, null, null), 403, "PERMISSION_DENIED");
        assertError(request(HttpMethod.GET, "/api/roles/" + peerRole.getId() + "/permissions", adminBrowser, null, null),
                403, "PERMISSION_DENIED");
        assertError(request(HttpMethod.GET, "/api/roles/" + Long.MAX_VALUE + "/permissions", adminBrowser, null, null),
                404, "RESOURCE_NOT_FOUND");
        assertError(request(HttpMethod.GET, ADMIN_EMPLOYEES + "?pageSize=101", adminBrowser, null, null),
                400, "VALIDATION_FAILED");
        employeeRoles.deleteById(new EmployeeRole.Key(member.getId(), localRole.getId()));
        assertThat(request(HttpMethod.GET, employeePage, adminBrowser, null, null).getBody().path("total").asLong())
                .isZero();
    }

    /** 其余管理列表沿用组织范围及原表展示口径。 */
    @Test
    @DisplayName("C1-01 / C1-08 公司、角色、权限目录及 Excel 清单")
    void listsManagementReferenceData() {
        String prefix = newPrefix();
        Organization company = organization(prefix, "COMPANY", null);
        BusinessSystem app = systems.save(system(prefix + "-app", company));
        Employee admin = employee(prefix + "-admin", company.getId());
        scopes.save(new AdministratorScope(admin.getId(), "ORGANIZATION", company.getId()));
        RbacRole localRole = roles.save(new RbacRole(prefix, company.getId()));
        Permission permission = permissions.save(new Permission("request:approve", "审批", app.getId(), null));
        CatalogEntry catalog = catalogEntries.save(new CatalogEntry(prefix, "申请", "审批", "说明",
                "全部", null, "查看", null, "无"));
        Browser adminBrowser = browser(login(admin.getUsername(), PASSWORD, token(null)));
        assertThat(request(HttpMethod.GET, "/api/companies", adminBrowser, null, null).getBody().path("total").asLong())
                .isEqualTo(1);
        JsonNode visibleRoles = request(HttpMethod.GET, "/api/roles?pageSize=1", adminBrowser, null, null).getBody();
        long lastRolePage = visibleRoles.path("total").asLong();
        assertThat(request(HttpMethod.GET, "/api/roles?pageSize=1&page=" + lastRolePage, adminBrowser, null, null)
                .getBody().path("items").get(0).path("id").asLong()).isEqualTo(localRole.getId());
        assertThat(request(HttpMethod.GET, "/api/permissions", adminBrowser, null, null).getBody()
                .path("items").get(0).path("id").asLong()).isEqualTo(permission.getId());
        int page = Math.toIntExact(catalogEntries.count());
        JsonNode entry = request(HttpMethod.GET, "/api/catalog-entries?pageSize=1&page=" + page,
                adminBrowser, null, null).getBody().path("items").get(0);
        assertThat(entry.path("id").asLong()).isEqualTo(catalog.getId());
        assertThat(entry.path("administratorCell").asText()).isEqualTo("全部");
        assertThat(entry.path("executiveCell").isNull()).isTrue();
    }

    /** 员工维护须遵守管理范围，停用使原会话失效。 */
    @Test
    @DisplayName("C1-02 / C1-08 员工新增、维护、调岗冲突及停用")
    void maintainsEmployeesWithinScope() {
        String prefix = newPrefix();
        Organization company = organization(prefix, "COMPANY", null);
        Organization local = organization(prefix + "-local", "DEPARTMENT", company.getId());
        Organization peer = organization(prefix + "-peer", "DEPARTMENT", company.getId());
        Employee admin = employee(prefix + "-admin", company.getId());
        Employee worker = employee(prefix + "-worker", local.getId());
        scopes.save(new AdministratorScope(admin.getId(), "ORGANIZATION", company.getId()));
        Token manager = token(browser(login(admin.getUsername(), PASSWORD, token(null))));
        Token workerBrowser = token(browser(login(worker.getUsername(), PASSWORD, token(null))));
        String target = ADMIN_EMPLOYEES + "/" + worker.getId();
        assertThat(request(HttpMethod.POST, ADMIN_EMPLOYEES, manager.browser(), manager.value(),
                Map.of("username", prefix + "-new", "name", "新员工", "organizationId", local.getId().toString()))
                .getStatusCode().value()).isEqualTo(201);
        assertThat(request(HttpMethod.PATCH, target, manager.browser(), manager.value(), Map.of("name", "新姓名"))
                .getBody().path("name").asText()).isEqualTo("新姓名");
        RbacRole role = roles.save(new RbacRole(prefix, local.getId()));
        employeeRoles.save(new EmployeeRole(worker.getId(), role.getId()));
        assertError(request(HttpMethod.PATCH, target, manager.browser(), manager.value(),
                Map.of("organizationId", peer.getId().toString())), 409, "ASSIGNMENT_CONFLICT");
        assertNoContent(HttpMethod.DELETE, EMPLOYEE_ROLE.formatted(worker.getId(), role.getId()), manager);
        assertThat(request(HttpMethod.PATCH, target, manager.browser(), manager.value(),
                Map.of("organizationId", peer.getId().toString())).getBody().path("organizationId").asLong())
                .isEqualTo(peer.getId());
        RbacRole peerRole = roles.save(new RbacRole(prefix, peer.getId()));
        assertNoContent(HttpMethod.PUT, EMPLOYEE_ROLE.formatted(worker.getId(), peerRole.getId()), manager);
        assertNoContent(HttpMethod.DELETE, target, manager);
        assertError(request(HttpMethod.GET, ME, workerBrowser.browser(), null, null), 401, "AUTHENTICATION_REQUIRED");
        assertThat(employeeRoles.existsById(new EmployeeRole.Key(worker.getId(), peerRole.getId()))).isTrue();
    }

    /** 定义维护拒绝越权、重复创建及删除仍被引用的数据。 */
    @Test
    @DisplayName("C1-02 / C1-09 角色权限维护与关联保护")
    void maintainsDefinitionsWithinScope() {
        String prefix = newPrefix();
        Organization company = organization(prefix, "COMPANY", null);
        Organization local = organization(prefix + "-local", "DEPARTMENT", company.getId());
        Organization peer = organization(prefix + "-peer", "DEPARTMENT", company.getId());
        BusinessSystem app = systems.save(system(prefix + "-app", local));
        Employee admin = employee(prefix + "-admin", local.getId());
        scopes.save(new AdministratorScope(admin.getId(), "ORGANIZATION", local.getId()));
        Employee outsider = employee(prefix + "-peer-user", peer.getId());
        scopes.save(new AdministratorScope(outsider.getId(), "ORGANIZATION", peer.getId()));
        Token manager = token(browser(login(admin.getUsername(), PASSWORD, token(null))));
        Token unrelated = token(browser(login(outsider.getUsername(), PASSWORD, token(null))));
        Map<String, String> roleBody = Map.of("name", prefix, "ownerOrganizationId", local.getId().toString());
        Map<String, String> permissionBody = Map.of("code", "request:approve", "name", "审批",
                "systemId", app.getId().toString(), "ownerOrganizationId", local.getId().toString());
        ResponseEntity<JsonNode> createdRole = request(HttpMethod.POST, "/api/roles", manager.browser(),
                manager.value(), roleBody);
        ResponseEntity<JsonNode> createdPermission = request(HttpMethod.POST, "/api/permissions", manager.browser(),
                manager.value(), permissionBody);
        assertThat(createdRole.getStatusCode().value()).isEqualTo(201);
        assertThat(createdPermission.getStatusCode().value()).isEqualTo(201);
        Long roleId = createdRole.getBody().path("id").asLong();
        Long permissionId = createdPermission.getBody().path("id").asLong();
        String role = "/api/roles/" + roleId;
        String permission = "/api/permissions/" + permissionId;
        String assignment = ROLE_PERMISSION.formatted(roleId, permissionId);
        assertError(request(HttpMethod.POST, "/api/roles", manager.browser(), manager.value(), roleBody),
                409, "RESOURCE_CONFLICT");
        assertError(request(HttpMethod.POST, "/api/permissions", manager.browser(), manager.value(), permissionBody),
                409, "RESOURCE_CONFLICT");
        assertError(request(HttpMethod.PATCH, role, unrelated.browser(), unrelated.value(), Map.of("name", "越权")),
                403, "PERMISSION_DENIED");
        assertAssignmentError(HttpMethod.DELETE, permission, unrelated, 403, "PERMISSION_DENIED");
        assertThat(request(HttpMethod.PATCH, permission, manager.browser(), manager.value(), Map.of("name", "审批操作"))
                .getBody().path("code").asText()).isEqualTo("request:approve");
        assertThat(request(HttpMethod.GET, permission, manager.browser(), null, null).getBody().path("name").asText())
                .isEqualTo("审批操作");
        assertNoContent(HttpMethod.PUT, assignment, manager);
        assertAssignmentError(HttpMethod.DELETE, role, manager, 409, "RESOURCE_CONFLICT");
        assertAssignmentError(HttpMethod.DELETE, permission, manager, 409, "RESOURCE_CONFLICT");
        assertNoContent(HttpMethod.DELETE, assignment, manager);
        assertNoContent(HttpMethod.DELETE, role, manager);
        assertNoContent(HttpMethod.DELETE, permission, manager);
        assertError(request(HttpMethod.GET, role, manager.browser(), null, null), 404, "RESOURCE_NOT_FOUND");
    }

    /** 菱形继承去重，直接角色也可以通过其他直接根继承获得。 */
    @Test
    @DisplayName("ID-S3-01 自身角色来源、系统相关性与继承权限去重")
    void currentRoleSources() {
        String prefix = newPrefix();
        Organization company = organization(prefix, "COMPANY", null);
        Organization department = organization(prefix + "-dep", "DEPARTMENT", company.getId());
        BusinessSystem app = systems.save(system(prefix + "-app", department));
        BusinessSystem otherApp = systems.save(system(prefix + "-other", company));
        Permission read = permissions.save(new Permission("request:read", "查看", app.getId(), null));
        Permission otherRead = permissions.save(new Permission("request:read", "其他查看", otherApp.getId(), null));
        Employee worker = employee(prefix + "-worker", department.getId());
        Employee admin = employee(prefix + "-admin", company.getId());
        scopes.save(new AdministratorScope(admin.getId(), "GLOBAL", null));
        RbacRole senior = roles.save(new RbacRole(prefix + "-senior", department.getId()));
        RbacRole left = roles.save(new RbacRole(prefix + "-left", department.getId()));
        RbacRole right = roles.save(new RbacRole(prefix + "-right", department.getId()));
        RbacRole base = roles.save(new RbacRole(prefix + "-base", department.getId()));
        RbacRole unrelated = roles.save(new RbacRole(prefix + "-unrelated", department.getId()));
        rolePermissions.saveAll(List.of(new RolePermission(base.getId(), read.getId()),
                new RolePermission(unrelated.getId(), otherRead.getId())));
        Token manager = token(browser(login(admin.getUsername(), PASSWORD, token(null))));
        for (String edge : List.of(senior.getId() + "/parents/" + left.getId(),
                senior.getId() + "/parents/" + right.getId(), left.getId() + "/parents/" + base.getId(),
                right.getId() + "/parents/" + base.getId())) {
            assertNoContent(HttpMethod.PUT, "/api/roles/" + edge, manager);
        }
        employeeRoles.saveAll(List.of(new EmployeeRole(worker.getId(), senior.getId()),
                new EmployeeRole(worker.getId(), base.getId()), new EmployeeRole(worker.getId(), unrelated.getId())));
        Browser browserContext = browser(login(worker.getUsername(), PASSWORD, token(null)));

        JsonNode result = currentAuthorization(app, browserContext);
        assertThat(result.path("roles")).hasSize(5);
        assertThat(result.path("effectivePermissions")).hasSize(1);
        assertThat(result.path("effectivePermissions").get(0).path("id").asLong()).isEqualTo(read.getId());
        JsonNode source = currentRole(result, base.getId());
        assertThat(source.path("direct").asBoolean()).isTrue();
        assertThat(source.path("inherited").asBoolean()).isTrue();
        assertThat(source.path("inheritedFromRoleIds")).hasSize(1);
        assertThat(source.path("inheritedFromRoleIds").get(0).asLong()).isEqualTo(senior.getId());
        assertThat(currentRole(result, senior.getId()).path("inherited").asBoolean()).isFalse();
        assertThat(currentRole(result, left.getId()).path("appliesToSystem").asBoolean()).isTrue();
        assertThat(currentRole(result, unrelated.getId()).path("appliesToSystem").asBoolean()).isFalse();
        long previous = 0;
        for (JsonNode role : result.path("roles")) {
            assertThat(role.path("id").asLong()).isGreaterThan(previous);
            previous = role.path("id").asLong();
        }
        assertCurrentPermission(app, browserContext, "request:read", true);
    }

    /** 同一会话不能缓存旧授角、继承边或角色权限结果。 */
    @Test
    @DisplayName("ID-S3-02 撤销角色、继承和直接权限后自身查询实时生效")
    void currentRevocations() {
        String prefix = newPrefix();
        Organization company = organization(prefix, "COMPANY", null);
        BusinessSystem app = systems.save(system(prefix + "-app", company));
        Permission read = permissions.save(new Permission("request:read", "查看", app.getId(), null));
        Employee worker = employee(prefix + "-worker", company.getId());
        Employee admin = employee(prefix + "-admin", company.getId());
        scopes.save(new AdministratorScope(admin.getId(), "GLOBAL", null));
        RbacRole senior = roles.save(new RbacRole(prefix + "-senior", company.getId()));
        RbacRole base = roles.save(new RbacRole(prefix + "-base", company.getId()));
        rolePermissions.save(new RolePermission(base.getId(), read.getId()));
        Token manager = token(browser(login(admin.getUsername(), PASSWORD, token(null))));
        String edge = "/api/roles/" + senior.getId() + "/parents/" + base.getId();
        assertNoContent(HttpMethod.PUT, edge, manager);
        employeeRoles.save(new EmployeeRole(worker.getId(), senior.getId()));
        Browser browserContext = browser(login(worker.getUsername(), PASSWORD, token(null)));

        assertCurrentPermission(app, browserContext, "request:read", true);
        employeeRoles.deleteById(new EmployeeRole.Key(worker.getId(), senior.getId()));
        assertCurrentPermission(app, browserContext, "request:read", false);
        assertThat(currentAuthorization(app, browserContext).path("roles")).isEmpty();
        employeeRoles.save(new EmployeeRole(worker.getId(), senior.getId()));
        assertCurrentPermission(app, browserContext, "request:read", true);
        assertNoContent(HttpMethod.DELETE, edge, manager);
        assertCurrentPermission(app, browserContext, "request:read", false);
        assertThat(currentAuthorization(app, browserContext).path("roles")).hasSize(1);
        employeeRoles.save(new EmployeeRole(worker.getId(), base.getId()));
        assertCurrentPermission(app, browserContext, "request:read", true);
        rolePermissions.deleteById(new RolePermission.Key(base.getId(), read.getId()));
        assertCurrentPermission(app, browserContext, "request:read", false);
        assertThat(currentAuthorization(app, browserContext).path("effectivePermissions")).isEmpty();
    }

    /** 配置相关性不等于有效授权；不同角色重授不能拼接。 */
    @Test
    @DisplayName("ID-S3-03 自身列表及单项判断遵守停用和同源重授")
    void currentOrganizationPolicies() {
        String prefix = newPrefix();
        Organization company = organization(prefix, "COMPANY", null);
        Organization department = organization(prefix + "-dep", "DEPARTMENT", company.getId());
        BusinessSystem app = systems.save(system(prefix + "-app", department));
        Permission approve = permissions.save(new Permission("request:approve", "审批", app.getId(), null));
        Employee worker = employee(prefix, department.getId());
        RbacRole first = roles.save(new RbacRole(prefix + "-first", department.getId()));
        RbacRole second = roles.save(new RbacRole(prefix + "-second", department.getId()));
        employeeRoles.saveAll(List.of(new EmployeeRole(worker.getId(), first.getId()),
                new EmployeeRole(worker.getId(), second.getId())));
        rolePermissions.saveAll(List.of(new RolePermission(first.getId(), approve.getId()),
                new RolePermission(second.getId(), approve.getId())));
        Browser browserContext = browser(login(worker.getUsername(), PASSWORD, token(null)));
        assertCurrentPermission(app, browserContext, "request:approve", true);
        organizationPermissions.save(new OrganizationPermission(company.getId(), approve.getId(), "DISABLED"));
        assertCurrentPermission(app, browserContext, "request:approve", false);
        JsonNode denied = currentAuthorization(app, browserContext);
        assertThat(denied.path("effectivePermissions")).isEmpty();
        assertThat(currentRole(denied, first.getId()).path("appliesToSystem").asBoolean()).isTrue();

        organizationPermissions.save(new OrganizationPermission(company.getId(), approve.getId(), "REOPENED"));
        organizationPermissions.save(new OrganizationPermission(department.getId(), approve.getId(), "REOPENED"));
        regrants.save(new PermissionRegrant(company.getId(), first.getId(), approve.getId()));
        regrants.save(new PermissionRegrant(department.getId(), second.getId(), approve.getId()));
        assertCurrentPermission(app, browserContext, "request:approve", false);
        regrants.save(new PermissionRegrant(department.getId(), first.getId(), approve.getId()));
        assertCurrentPermission(app, browserContext, "request:approve", true);
        assertThat(currentAuthorization(app, browserContext).path("effectivePermissions")).hasSize(1);
        regrants.deleteById(new PermissionRegrant.Key(company.getId(), first.getId(), approve.getId()));
        assertCurrentPermission(app, browserContext, "request:approve", false);
    }

    @Test
    @DisplayName("ID-S3-04 全局特权不伪造角色且不能绕过组织停用")
    void currentGlobalPrivilege() {
        String prefix = newPrefix();
        Organization company = organization(prefix, "COMPANY", null);
        BusinessSystem app = systems.save(system(prefix + "-app", company));
        Permission read = permissions.save(new Permission("request:read", "查看", app.getId(), null));
        Employee admin = employee(prefix, company.getId());
        scopes.save(new AdministratorScope(admin.getId(), "GLOBAL", null));
        Browser browserContext = browser(login(admin.getUsername(), PASSWORD, token(null)));

        JsonNode result = currentAuthorization(app, browserContext);
        assertThat(result.path("globalAdministrator").asBoolean()).isTrue();
        assertThat(result.path("roles")).isEmpty();
        assertThat(result.path("effectivePermissions")).hasSize(1);
        assertCurrentPermission(app, browserContext, "request:read", true);
        organizationPermissions.save(new OrganizationPermission(company.getId(), read.getId(), "DISABLED"));
        assertCurrentPermission(app, browserContext, "request:read", false);
        assertThat(currentAuthorization(app, browserContext).path("effectivePermissions")).isEmpty();
    }

    @Test
    @DisplayName("ID-S3-05 自身查询区分未登录、输入非法、未知系统与未知权限")
    void currentInputsAndUnknownTargets() {
        String prefix = newPrefix();
        Organization company = organization(prefix, "COMPANY", null);
        Organization department = organization(prefix + "-dep", "DEPARTMENT", company.getId());
        Organization peer = organization(prefix + "-peer", "DEPARTMENT", company.getId());
        BusinessSystem app = systems.save(system(prefix + "-app", department));
        BusinessSystem forbidden = systems.save(system(prefix + "-peer-app", peer));
        Employee worker = employee(prefix, department.getId());
        Browser browserContext = browser(login(worker.getUsername(), PASSWORD, token(null)));
        String check = "/api/auth/me/permission-check";
        assertError(request(HttpMethod.GET, check, null, null, null), 401, "AUTHENTICATION_REQUIRED");
        assertError(request(HttpMethod.GET, ME + "?systemCode=" + prefix + "-missing", browserContext, null, null),
                404, "SYSTEM_NOT_FOUND");
        assertError(request(HttpMethod.GET, ME + "?systemCode=" + forbidden.getCode(), browserContext, null, null),
                403, "SYSTEM_ACCESS_DENIED");
        assertError(request(HttpMethod.GET, check + "?systemCode=" + forbidden.getCode() + "&permissionCode=missing",
                browserContext, null, null), 403, "SYSTEM_ACCESS_DENIED");
        assertError(request(HttpMethod.GET, check + "?systemCode=" + app.getCode() + "&permissionCode=missing",
                browserContext, null, null), 404, "PERMISSION_NOT_FOUND");
        for (String query : List.of("?systemCode=", "?systemCode= ", "?systemCode=" + "x".repeat(65))) {
            assertError(request(HttpMethod.GET, ME + query, browserContext, null, null), 400, "VALIDATION_FAILED");
        }
        for (String query : List.of("", "?systemCode=" + app.getCode(),
                "?systemCode= &permissionCode=request:read",
                "?systemCode=" + app.getCode() + "&permissionCode= ",
                "?systemCode=" + app.getCode() + "&permissionCode=" + "x".repeat(129))) {
            assertError(request(HttpMethod.GET, check + query, browserContext, null, null), 400, "VALIDATION_FAILED");
        }
    }

    @Test
    @DisplayName("ID-S3-06 登录增加访问凭证，me保留身份字段，指定系统才增加授权")
    void currentCompatibility() {
        String prefix = newPrefix();
        Organization company = organization(prefix, "COMPANY", null);
        BusinessSystem app = systems.save(system(prefix + "-app", company));
        Employee worker = employee(prefix, company.getId());
        ResponseEntity<JsonNode> loggedIn = login(worker.getUsername(), PASSWORD, token(null));
        assertThat(loggedIn.getBody().has("authorization")).isFalse();
        Browser browserContext = browser(loggedIn);
        ResponseEntity<JsonNode> legacy = request(HttpMethod.GET, ME, browserContext, null, null);
        for (String field : List.of("employee", "workspaces", "defaultWorkspaceKey")) {
            assertThat(legacy.getBody().path(field)).isEqualTo(loggedIn.getBody().path(field));
        }
        assertThat(legacy.getBody().has("accessToken")).isFalse();
        assertThat(legacy.getBody().size()).isEqualTo(3);
        assertThat(currentAuthorization(app, browserContext).path("effectivePermissions")).isEmpty();
        assertThat(request(HttpMethod.GET, ME, browserContext, null, null).getBody().has("authorization")).isFalse();
    }

    @Test
    @DisplayName("ID-S3-07 相同权限码按系统隔离且角色配置相关性分别表达")
    void currentSystemIsolation() {
        String prefix = newPrefix();
        Organization company = organization(prefix, "COMPANY", null);
        Organization department = organization(prefix + "-dep", "DEPARTMENT", company.getId());
        BusinessSystem local = systems.save(system(prefix + "-local", department));
        BusinessSystem parent = systems.save(system(prefix + "-parent", company));
        Permission localRead = permissions.save(new Permission("request:read", "本部门查看", local.getId(), null));
        permissions.save(new Permission("request:read", "公司查看", parent.getId(), null));
        Employee worker = employee(prefix, department.getId());
        RbacRole role = roles.save(new RbacRole(prefix, department.getId()));
        employeeRoles.save(new EmployeeRole(worker.getId(), role.getId()));
        rolePermissions.save(new RolePermission(role.getId(), localRead.getId()));
        Browser browserContext = browser(login(worker.getUsername(), PASSWORD, token(null)));

        assertCurrentPermission(local, browserContext, "request:read", true);
        assertCurrentPermission(parent, browserContext, "request:read", false);
        assertThat(currentRole(currentAuthorization(parent, browserContext), role.getId()).path("appliesToSystem").asBoolean())
                .isFalse();
        assertThat(currentAuthorization(parent, browserContext).path("effectivePermissions")).isEmpty();
    }

    @Test
    @DisplayName("ID-S3-08 自查不需要管理员资格，但不能查询或冒充他人授权")
    void currentSelfBoundary() {
        String prefix = newPrefix();
        Organization company = organization(prefix, "COMPANY", null);
        BusinessSystem app = systems.save(system(prefix + "-app", company));
        Permission read = permissions.save(new Permission("request:read", "查看", app.getId(), null));
        Employee worker = employee(prefix + "-worker", company.getId());
        Employee other = employee(prefix + "-other", company.getId());
        RbacRole privateRole = roles.save(new RbacRole(prefix, company.getId()));
        employeeRoles.save(new EmployeeRole(other.getId(), privateRole.getId()));
        rolePermissions.save(new RolePermission(privateRole.getId(), read.getId()));
        Browser browserContext = browser(login(worker.getUsername(), PASSWORD, token(null)));

        ResponseEntity<JsonNode> self = request(HttpMethod.GET,
                ME + "?systemCode=" + app.getCode() + "&employeeId=" + other.getId(), browserContext, null, null);
        assertThat(self.getStatusCode().value()).isEqualTo(200);
        assertThat(self.getBody().path("employee").path("id").asLong()).isEqualTo(worker.getId());
        assertThat(self.getBody().path("authorization").path("roles")).isEmpty();
        assertCurrentPermission(app, browserContext, "request:read", false);
        for (String suffix : List.of("", "/roles", "/effective-permissions?systemCode=" + app.getCode())) {
            assertError(request(HttpMethod.GET, "/api/employees/" + other.getId() + suffix, browserContext, null, null),
                    403, "PERMISSION_DENIED");
        }
    }

    /** 只读取公开HTTP响应，不调用被测授权代码生成期望。 */
    private JsonNode currentAuthorization(BusinessSystem system, Browser browserContext) {
        ResponseEntity<JsonNode> response = request(HttpMethod.GET, ME + "?systemCode=" + system.getCode(),
                browserContext, null, null);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getBody().has("authorization")).isTrue();
        JsonNode result = response.getBody().path("authorization");
        assertThat(result.path("systemCode").asText()).isEqualTo(system.getCode());
        return result;
    }

    private JsonNode currentRole(JsonNode authorization, Long roleId) {
        for (JsonNode role : authorization.path("roles")) {
            if (role.path("id").asLong() == roleId) {
                return role;
            }
        }
        throw new AssertionError("缺少预期角色 " + roleId);
    }

    private void assertCurrentPermission(BusinessSystem system, Browser browserContext, String permission, boolean expected) {
        ResponseEntity<JsonNode> response = request(HttpMethod.GET, "/api/auth/me/permission-check?systemCode="
                + system.getCode() + "&permissionCode=" + permission, browserContext, null, null);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getBody().path("systemCode").asText()).isEqualTo(system.getCode());
        assertThat(response.getBody().path("permissionCode").asText()).isEqualTo(permission);
        assertThat(response.getBody().path("allowed").asBoolean()).isEqualTo(expected);
    }

    @Test
    @DisplayName("ID-S4-01 正式树的可见根、直接子级及hasChildren")
    void directoryTreeLevels() {
        DirectoryFixture f = directoryFixture();
        JsonNode roots = directoryPage("/api/departments?view=tree", f.companyBrowser());
        assertThat(roots.path("total").asLong()).isEqualTo(1);
        assertThat(roots.path("items").get(0).path("id").asLong()).isEqualTo(f.company().getId());
        assertThat(roots.path("items").get(0).path("organizationType").asText()).isEqualTo("COMPANY");
        assertThat(roots.path("items").get(0).path("hasChildren").asBoolean()).isTrue();
        JsonNode children = directoryPage("/api/departments?view=tree&parentId=" + f.company().getId(),
                f.companyBrowser());
        assertThat(children.path("total").asLong()).isEqualTo(2);
        assertThat(children.path("items").get(0).path("id").asLong()).isEqualTo(f.department().getId());
        assertThat(children.path("items").get(0).path("hasChildren").asBoolean()).isFalse();
        JsonNode searched = directoryPage("/api/departments?view=tree&rootsOnly=true&q=" + f.company().getCode(),
                f.companyBrowser());
        assertThat(searched.path("items").get(0).path("hasChildren").asBoolean()).isTrue();
        assertThat(directoryPage("/api/departments?view=tree&parentId=" + f.department().getId(),
                f.companyBrowser()).path("items")).isEmpty();
        assertThat(directoryPage("/api/departments?view=tree&rootsOnly=true&q=" + f.prefix(),
                f.globalBrowser()).path("total").asLong()).isEqualTo(2);
    }

    @Test
    @DisplayName("ID-S4-02 普通员工树不泄露上级或兄弟节点，根保留真实parentId")
    void directoryOrdinaryTree() {
        DirectoryFixture f = directoryFixture();
        JsonNode root = directoryPage("/api/departments?view=tree", f.workerBrowser());
        assertThat(root.path("total").asLong()).isEqualTo(1);
        JsonNode node = root.path("items").get(0);
        assertThat(node.path("id").asLong()).isEqualTo(f.department().getId());
        assertThat(node.path("parentId").asLong()).isEqualTo(f.company().getId());
        assertThat(node.path("hasChildren").asBoolean()).isFalse();
        assertError(request(HttpMethod.GET, "/api/departments", f.workerBrowser(), null, null),
                403, "PERMISSION_DENIED");
        for (Long id : List.of(f.company().getId(), f.peer().getId(), f.foreignCompany().getId())) {
            assertError(request(HttpMethod.GET, "/api/departments?view=tree&parentId=" + id,
                    f.workerBrowser(), null, null), 403, "PERMISSION_DENIED");
        }
        assertThat(directoryPage("/api/departments?view=tree&rootsOnly=false&q=" + f.prefix(),
                f.workerBrowser()).path("total").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("ID-S4-03 员工范围、停用过滤与普通/管理员字段投影")
    void directoryEmployeeVisibility() {
        DirectoryFixture f = directoryFixture();
        String needle = f.prefix() + "-needle";
        employees.save(new Employee(needle + "-a", "可见甲", f.department().getId(), null, null, "岗位", "13800138000"));
        employee(needle + "-b", f.department().getId());
        Employee disabled = employee(needle + "-disabled", f.department().getId());
        disabled.disable();
        employees.save(disabled);
        employee(needle + "-peer", f.peer().getId());
        employee(needle + "-foreign", f.foreignCompany().getId());
        String path = "/api/employees?q=" + needle;
        JsonNode ordinary = directoryPage(path, f.workerBrowser());
        assertThat(ordinary.path("total").asLong()).isEqualTo(2);
        for (JsonNode row : ordinary.path("items")) {
            assertThat(row.size()).isEqualTo(4);
            assertThat(row.has("username")).isTrue();
            assertThat(row.path("organizationId").asLong()).isEqualTo(f.department().getId());
            assertThat(row.has("phone")).isFalse();
            assertThat(row.has("jobTitle")).isFalse();
        }
        JsonNode local = directoryPage(path, f.departmentBrowser());
        assertThat(local.path("total").asLong()).isEqualTo(3);
        assertThat(local.path("items").get(0).path("phone").asText()).isEqualTo("138****8000");
        assertThat(directoryPage(path, f.companyBrowser()).path("total").asLong()).isEqualTo(4);
        assertThat(directoryPage(path, f.globalBrowser()).path("total").asLong()).isEqualTo(5);
    }

    @Test
    @DisplayName("ID-S4-04 部门/搜索/范围同时筛选，分页与count一致")
    void directoryEmployeeSearchAndPaging() {
        DirectoryFixture f = directoryFixture();
        String needle = f.prefix() + "-match";
        Employee first = employee(needle + "-a", f.department().getId());
        Employee second = employee(needle + "-b", f.department().getId());
        employee(needle + "-peer", f.peer().getId());
        employees.save(new Employee(f.prefix() + "-named", "姓名检索甲", f.department().getId(), null, null, null, null));
        String path = "/api/employees?departmentId=" + f.department().getId() + "&q=" + needle + "&pageSize=1";
        JsonNode one = directoryPage(path + "&page=1", f.workerBrowser());
        JsonNode two = directoryPage(path + "&page=2", f.workerBrowser());
        assertThat(one.path("total").asLong()).isEqualTo(2);
        assertThat(two.path("total").asLong()).isEqualTo(2);
        assertThat(one.path("items").get(0).path("id").asLong()).isEqualTo(first.getId());
        assertThat(two.path("items").get(0).path("id").asLong()).isEqualTo(second.getId());
        JsonNode beyond = directoryPage(path + "&page=99", f.workerBrowser());
        assertThat(beyond.path("items")).isEmpty();
        assertThat(beyond.path("total").asLong()).isEqualTo(2);
        assertThat(directoryPage("/api/employees?q=姓名检索", f.workerBrowser()).path("total").asLong()).isEqualTo(1);
        assertThat(directoryPage("/api/employees?q=" + needle + "-absent", f.workerBrowser()).path("total").asLong())
                .isZero();
    }

    @Test
    @DisplayName("ID-S4-05 搜索按字面处理百分号下划线和转义字符，去除首尾空白")
    void directoryLiteralSearch() {
        DirectoryFixture f = directoryFixture();
        for (String symbol : List.of("%", "_", "!", "Z")) {
            employees.save(new Employee(f.prefix() + "-" + symbol, "literal" + symbol + "name",
                    f.department().getId(), null, null, null, null));
            organizations.save(new Organization(f.prefix() + "-" + symbol, "literal" + symbol + "department",
                    "DEPARTMENT", f.company().getId(), null, null));
        }
        for (String symbol : List.of("%", "_", "!")) {
            assertThat(directoryPage("/api/employees?q=" + symbol, f.workerBrowser()).path("total").asLong())
                    .isEqualTo(1);
            assertThat(directoryPage("/api/departments?view=tree&q=" + symbol,
                    f.companyBrowser()).path("total").asLong()).isEqualTo(1);
        }
        assertThat(directoryPage("/api/employees?q=  literal%  ", f.workerBrowser()).path("total").asLong()).isEqualTo(1);
        assertThat(directoryPage("/api/departments?view=tree&q=   ", f.companyBrowser()).path("total").asLong())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("ID-S4-06 参数组合及未知/越界部门错误明确，匿名请求401")
    void directoryInputAndTargetErrors() {
        DirectoryFixture f = directoryFixture();
        for (String path : List.of("/api/employees", "/api/departments?view=tree")) {
            assertError(request(HttpMethod.GET, path, null, null, null), 401, "AUTHENTICATION_REQUIRED");
        }
        for (String query : List.of("view=bad", "view=tree&parentId=0", "rootsOnly=false",
                "view=tree&parentId=" + f.company().getId() + "&rootsOnly=false",
                "view=tree&parentId=" + f.company().getId() + "&rootsOnly=true",
                "view=tree&rootsOnly=bad", "view=tree&q=" + "x".repeat(129), "page=0", "pageSize=101")) {
            assertError(request(HttpMethod.GET, "/api/departments?" + query, f.companyBrowser(), null, null),
                    400, "VALIDATION_FAILED");
        }
        for (String query : List.of("departmentId=0", "departmentId=" + f.company().getId(), "departmentId=bad",
                "q=" + "x".repeat(129), "page=0", "pageSize=101")) {
            assertError(request(HttpMethod.GET, "/api/employees?" + query, f.companyBrowser(), null, null),
                    400, "VALIDATION_FAILED");
        }
        assertError(request(HttpMethod.GET, "/api/departments?view=tree&parentId=" + Long.MAX_VALUE,
                f.companyBrowser(), null, null), 404, "RESOURCE_NOT_FOUND");
        assertError(request(HttpMethod.GET, "/api/employees?departmentId=" + Long.MAX_VALUE,
                f.companyBrowser(), null, null), 404, "RESOURCE_NOT_FOUND");
        assertError(request(HttpMethod.GET, "/api/employees?departmentId=" + f.peer().getId(),
                f.workerBrowser(), null, null), 403, "PERMISSION_DENIED");
    }

    @Test
    @DisplayName("ID-S4-07 撤销管理员范围后同一会话立即改用普通目录边界")
    void directoryAdministratorRevocation() {
        DirectoryFixture f = directoryFixture();
        String needle = f.prefix() + "-revocation";
        employee(needle + "-active", f.department().getId());
        Employee disabled = employee(needle + "-disabled", f.department().getId());
        disabled.disable();
        employees.save(disabled);
        String path = "/api/employees?q=" + needle;
        assertThat(directoryPage(path, f.departmentBrowser()).path("total").asLong()).isEqualTo(2);
        scopes.deleteById(f.departmentAdmin().getId());
        JsonNode ordinary = directoryPage(path, f.departmentBrowser());
        assertThat(ordinary.path("total").asLong()).isEqualTo(1);
        assertThat(ordinary.path("items").get(0).has("phone")).isFalse();
        assertError(request(HttpMethod.GET, "/api/departments", f.departmentBrowser(), null, null),
                403, "PERMISSION_DENIED");
        assertError(request(HttpMethod.GET, "/api/employees/" + f.worker().getId(),
                f.departmentBrowser(), null, null), 403, "PERMISSION_DENIED");
        scopes.save(new AdministratorScope(f.departmentAdmin().getId(), "ORGANIZATION", f.department().getId()));
        assertThat(directoryPage(path, f.departmentBrowser()).path("total").asLong()).isEqualTo(2);
    }

    @Test
    @DisplayName("ID-S4-08 全局未匹配员工可核对，单人资料仍有管理员和范围边界")
    void directoryGlobalAndDetails() {
        DirectoryFixture f = directoryFixture();
        String needle = f.prefix() + "-global";
        Employee pending = employee(needle + "-pending", null);
        Employee active = employee(needle + "-active", f.department().getId());
        Employee disabled = employee(needle + "-disabled", f.department().getId());
        disabled.disable();
        employees.save(disabled);
        String path = "/api/employees?q=" + needle;
        assertThat(directoryPage(path, f.globalBrowser()).path("total").asLong()).isEqualTo(3);
        assertThat(directoryPage(path + "&departmentId=" + f.department().getId(),
                f.globalBrowser()).path("total").asLong()).isEqualTo(2);
        assertThat(directoryPage(path, f.companyBrowser()).path("total").asLong()).isEqualTo(2);
        assertThat(directoryPage(path, f.workerBrowser()).path("total").asLong()).isEqualTo(1);
        assertThat(request(HttpMethod.GET, "/api/employees/" + pending.getId(), f.globalBrowser(), null, null)
                .getStatusCode().value()).isEqualTo(200);
        assertError(request(HttpMethod.GET, "/api/employees/" + active.getId(), f.workerBrowser(), null, null),
                403, "PERMISSION_DENIED");
        assertError(request(HttpMethod.GET, "/api/employees/" + pending.getId(), f.companyBrowser(), null, null),
                403, "PERMISSION_DENIED");
        assertError(request(HttpMethod.GET, "/api/employees/" + Long.MAX_VALUE, f.globalBrowser(), null, null),
                404, "RESOURCE_NOT_FOUND");
    }

    @Test
    @DisplayName("ID-S4-09 原表公司行和原字段保持兼容，显式tree才使用正式投影")
    void directoryLegacyCompatibility() {
        DirectoryFixture f = directoryFixture();
        String needle = f.prefix() + "-compat";
        Organization sourceCompany = organizations.save(new Organization("xlsx-" + needle, needle, "COMPANY",
                null, "原经理", "原上级"));
        Organization child = organizations.save(new Organization(needle + "-dep", needle + "-dep", "DEPARTMENT",
                sourceCompany.getId(), "部门经理", needle));
        organization(needle + "-normal", "COMPANY", null);
        JsonNode directory = directoryPage("/api/departments?q=" + needle, f.globalBrowser());
        assertThat(directory.path("total").asLong()).isEqualTo(2);
        assertThat(directory.path("items").get(0).path("id").asLong()).isEqualTo(sourceCompany.getId());
        assertThat(directory.path("items").get(0).path("sourceParentName").asText()).isEqualTo("原上级");
        assertThat(directory.path("items").get(0).has("hasChildren")).isFalse();
        assertThat(directory.path("items").get(1).path("id").asLong()).isEqualTo(child.getId());
        assertThat(directoryPage("/api/departments?view=tree&q=" + needle, f.globalBrowser())
                .path("total").asLong()).isEqualTo(3);
    }

    @Test
    @DisplayName("ID-S4-10 树分页、范围内平铺和搜索模式的count一致")
    void directoryTreePagingAndCompanyWorker() {
        DirectoryFixture f = directoryFixture();
        String path = "/api/departments?view=tree&parentId=" + f.company().getId() + "&pageSize=1";
        assertThat(directoryPage(path + "&page=1", f.companyBrowser()).path("items").get(0).path("id").asLong())
                .isEqualTo(f.department().getId());
        assertThat(directoryPage(path + "&page=2", f.companyBrowser()).path("items").get(0).path("id").asLong())
                .isEqualTo(f.peer().getId());
        JsonNode empty = directoryPage(path + "&page=3", f.companyBrowser());
        assertThat(empty.path("total").asLong()).isEqualTo(2);
        assertThat(empty.path("items")).isEmpty();
        assertThat(directoryPage("/api/departments?view=tree&rootsOnly=false&pageSize=2",
                f.companyBrowser()).path("total").asLong()).isEqualTo(3);
        assertThat(directoryPage("/api/departments?view=tree&rootsOnly=true&q=" + f.department().getCode(),
                f.companyBrowser()).path("total").asLong()).isZero();

        Employee companyWorker = employee(f.prefix() + "-company-worker", f.company().getId());
        Browser browserContext = browser(login(companyWorker.getUsername(), PASSWORD, token(null)));
        employee(f.prefix() + "-visible-company", f.company().getId());
        employee(f.prefix() + "-visible-department", f.department().getId());
        JsonNode root = directoryPage("/api/departments?view=tree", browserContext);
        assertThat(root.path("items").get(0).path("hasChildren").asBoolean()).isFalse();
        assertThat(directoryPage("/api/employees?q=" + f.prefix() + "-visible", browserContext).path("total").asLong())
                .isEqualTo(1);
    }

    /** 每个场景独立组织和会话；不从被测范围算法生成期望。 */
    private DirectoryFixture directoryFixture() {
        String prefix = newPrefix();
        Organization company = organization(prefix, "COMPANY", null);
        Organization department = organization(prefix + "-dep", "DEPARTMENT", company.getId());
        Organization peer = organization(prefix + "-peer", "DEPARTMENT", company.getId());
        Organization foreign = organization(prefix + "-foreign", "COMPANY", null);
        Employee worker = employee(prefix + "-worker", department.getId());
        Employee localAdmin = employee(prefix + "-department-admin", department.getId());
        Employee companyAdmin = employee(prefix + "-company-admin", company.getId());
        Employee global = employee(prefix + "-platform-admin", company.getId());
        scopes.saveAll(List.of(new AdministratorScope(localAdmin.getId(), "ORGANIZATION", department.getId()),
                new AdministratorScope(companyAdmin.getId(), "ORGANIZATION", company.getId()),
                new AdministratorScope(global.getId(), "GLOBAL", null)));
        return new DirectoryFixture(prefix, company, department, peer, foreign, worker, localAdmin,
                browser(login(worker.getUsername(), PASSWORD, token(null))),
                browser(login(localAdmin.getUsername(), PASSWORD, token(null))),
                browser(login(companyAdmin.getUsername(), PASSWORD, token(null))),
                browser(login(global.getUsername(), PASSWORD, token(null))));
    }

    private record DirectoryFixture(String prefix, Organization company, Organization department, Organization peer,
                                    Organization foreignCompany, Employee worker, Employee departmentAdmin,
                                    Browser workerBrowser, Browser departmentBrowser, Browser companyBrowser,
                                    Browser globalBrowser) {
    }

    private JsonNode directoryPage(String path, Browser browserContext) {
        ResponseEntity<JsonNode> response = request(HttpMethod.GET, path, browserContext, null, null);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        return response.getBody();
    }

    @Test
    @DisplayName("API预查询：未知系统与未登记操作分别返回404")
    void apiUnknownTargets() {
        String prefix = newPrefix();
        Organization company = organization(prefix, "COMPANY", null);
        BusinessSystem app = systems.save(system(prefix + "-app", company));
        Employee actor = employee(prefix + "-user", company.getId());
        Browser browserContext = browser(login(actor.getUsername(), PASSWORD, token(null)));
        assertError(request(HttpMethod.GET, ME + "/api-check?systemCode=missing-" + prefix
                + "&operationId=approveRequest", browserContext, null, null), 404, "SYSTEM_NOT_FOUND");
        assertError(request(HttpMethod.GET, ME + "/api-check?systemCode=" + app.getCode()
                + "&operationId=approveRequest", browserContext, null, null), 404, "API_NOT_FOUND");
    }

    @Test
    @DisplayName("API预查询：匿名401、缺参数或超长参数400")
    void apiInputBoundary() {
        String prefix = newPrefix();
        Organization company = organization(prefix, "COMPANY", null);
        Employee actor = employee(prefix + "-user", company.getId());
        Browser browserContext = browser(login(actor.getUsername(), PASSWORD, token(null)));
        String path = ME + "/api-check";
        assertError(request(HttpMethod.GET, path, null, null, null), 401, "AUTHENTICATION_REQUIRED");
        for (String query : List.of("", "?systemCode=x", "?systemCode=&operationId=x",
                "?systemCode=x&operationId=", "?systemCode=x&operationId=" + "x".repeat(129),
                "?systemCode=" + "x".repeat(65) + "&operationId=x")) {
            assertError(request(HttpMethod.GET, path + query, browserContext, null, null), 400, "VALIDATION_FAILED");
        }
    }

    @Test
    @DisplayName("API预查询：先验证系统可见范围，不泄露未知API")
    void apiSystemIsolation() {
        String prefix = newPrefix();
        Organization own = organization(prefix, "COMPANY", null);
        Organization foreign = organization(prefix + "-foreign", "COMPANY", null);
        BusinessSystem app = systems.save(system(prefix + "-app", foreign));
        Employee actor = employee(prefix + "-user", own.getId());
        Browser browserContext = browser(login(actor.getUsername(), PASSWORD, token(null)));
        assertError(request(HttpMethod.GET, ME + "/api-check?systemCode=" + app.getCode()
                + "&operationId=unknown", browserContext, null, null), 403, "SYSTEM_ACCESS_DENIED");
    }

    @Test
    @DisplayName("API预查询：ALL组合、create无需read、撤权后实际调用拒绝")
    void apiAllAndLiveRevocation() {
        ApiFixture f = apiFixture();
        apiGrant(f, "request:create");
        assertApi(f, "createRequest", true);
        assertApi(f, "listSystemRequests", false);
        apiGrant(f, "request:approve");
        assertApi(f, "approveRequest", false);
        apiGrant(f, "request:read");
        assertApi(f, "listSystemRequests", true);
        assertApi(f, "approveRequest", true);
        SimulatedRequest row = simulatedRequests.save(new SimulatedRequest(f.app().getId(), "撤权前申请"));
        rolePermissions.deleteById(new RolePermission.Key(f.role().getId(), f.permissions().get("request:approve")));
        assertApi(f, "approveRequest", false);
        Token actor = token(f.browserContext());
        assertError(requestAction("/api/requests/" + row.getId() + "/approve", actor), 403, "PERMISSION_DENIED");
        employeeRoles.deleteById(new EmployeeRole.Key(f.employee().getId(), f.role().getId()));
        assertApi(f, "createRequest", false);
    }

    @Test
    @DisplayName("API预查询：八个操作的要求与实际入口一致")
    void apiEightOperationsAndSystemSeparation() {
        ApiFixture f = apiFixture();
        for (String code : f.permissions().keySet()) {
            apiGrant(f, code);
        }
        for (String operation : apiDefinitions().keySet()) {
            assertApi(f, operation, true);
        }
        // 同一公司内另一个系统存在同码权限，也不能借用原系统授权。
        BusinessSystem other = systems.save(new BusinessSystem(newPrefix(), "同组织另一系统", f.employee().getOrganizationId(), false));
        Permission otherRead = permissions.save(new Permission("request:read", "另一系统查看", other.getId(), null));
        apiRegister(other.getId(), "listSystemRequests", List.of(otherRead.getId()));
        JsonNode result = directoryPage(ME + "/api-check?systemCode=" + other.getCode()
                + "&operationId=listSystemRequests", f.browserContext());
        assertThat(result.path("allowed").asBoolean()).isFalse();
        assertError(request(HttpMethod.GET, apiPath(f, "ApproveRequest"), f.browserContext(), null, null), 404, "API_NOT_FOUND");
        assertError(request(HttpMethod.GET, apiPath(f, "login"), f.browserContext(), null, null), 404, "API_NOT_FOUND");
    }

    @Test
    @DisplayName("API预查询：继承边撤销即时生效")
    void apiInheritedAuthorization() {
        ApiFixture f = apiFixture();
        apiGrant(f, "request:read");
        RbacRole junior = roles.save(new RbacRole(newPrefix(), f.employee().getOrganizationId()));
        rolePermissions.save(new RolePermission(junior.getId(), f.permissions().get("request:approve")));
        jdbc.update("INSERT INTO role_inheritance (senior_role_id, junior_role_id) VALUES (?, ?)",
                f.role().getId(), junior.getId());
        assertApi(f, "approveRequest", true);
        jdbc.update("DELETE FROM role_inheritance WHERE senior_role_id = ? AND junior_role_id = ?",
                f.role().getId(), junior.getId());
        assertApi(f, "approveRequest", false);
    }

    @Test
    @DisplayName("API预查询：组织停用、重开和重授沿用实时授权")
    void apiOrganizationPolicies() {
        ApiFixture f = apiFixture();
        apiGrant(f, "request:read");
        apiGrant(f, "request:approve");
        Long organizationId = f.employee().getOrganizationId();
        Long permissionId = f.permissions().get("request:approve");
        organizationPermissions.save(new OrganizationPermission(organizationId, permissionId, "DISABLED"));
        assertApi(f, "approveRequest", false);
        organizationPermissions.save(new OrganizationPermission(organizationId, permissionId, "REOPENED"));
        assertApi(f, "approveRequest", false);
        regrants.save(new PermissionRegrant(organizationId, f.role().getId(), permissionId));
        assertApi(f, "approveRequest", true);
        regrants.deleteById(new PermissionRegrant.Key(organizationId, f.role().getId(), permissionId));
        assertApi(f, "approveRequest", false);
    }

    @Test
    @DisplayName("API预查询：空映射、跨系统引用和定义漂移失败关闭")
    void apiCorruptMappingsFailClosed() {
        ApiFixture f = apiFixture();
        for (String code : f.permissions().keySet()) {
            apiGrant(f, code);
        }
        jdbc.update("DELETE FROM api_operation_permission WHERE system_id = ? AND operation_id = ?",
                f.app().getId(), "approveRequest");
        assertError(request(HttpMethod.GET, apiPath(f, "approveRequest"), f.browserContext(), null, null), 503, "AUTHORIZATION_UNAVAILABLE");
        // 缺少read的映射不能把ALL降为单权限。
        jdbc.update("INSERT INTO api_operation_permission VALUES (?, ?, ?)", f.app().getId(), "approveRequest",
                f.permissions().get("request:approve"));
        assertError(request(HttpMethod.GET, apiPath(f, "approveRequest"), f.browserContext(), null, null), 503, "AUTHORIZATION_UNAVAILABLE");
        BusinessSystem other = systems.save(new BusinessSystem(newPrefix(), "同组织另一系统", f.employee().getOrganizationId(), false));
        Permission foreign = permissions.save(new Permission("request:read", "跨系统引用", other.getId(), null));
        jdbc.update("INSERT INTO api_operation_permission VALUES (?, ?, ?)", f.app().getId(), "approveRequest", foreign.getId());
        assertError(request(HttpMethod.GET, apiPath(f, "approveRequest"), f.browserContext(), null, null), 503, "AUTHORIZATION_UNAVAILABLE");
        apiRegister(f.app().getId(), "unrecognizedOperation", List.of(f.permissions().get("request:read")));
        assertError(request(HttpMethod.GET, apiPath(f, "unrecognizedOperation"), f.browserContext(), null, null), 503, "AUTHORIZATION_UNAVAILABLE");
    }

    @Test
    @DisplayName("API预查询：不替代对象、状态检查，也不构成实际接口准入凭证")
    void apiActualObjectAndStateAuthorization() {
        ApiFixture f = apiFixture();
        for (String code : f.permissions().keySet()) {
            apiGrant(f, code);
        }
        Token actor = token(f.browserContext());
        Employee other = employee(newPrefix(), f.employee().getOrganizationId());
        SimulatedRequest row = new SimulatedRequest(f.app().getId(), other.getId(), "别人的申请");
        row = simulatedRequests.save(row);
        assertApi(f, "updateRequest", true);
        String detail = "/api/requests/" + row.getId();
        assertError(request(HttpMethod.PATCH, detail, actor.browser(), actor.value(), Map.of("title", "禁止修改")),
                403, "PERMISSION_DENIED");
        assertThat(requestAction(detail + "/approve", actor).getStatusCode().value()).isEqualTo(200);
        assertApi(f, "approveRequest", true);
        assertError(requestAction(detail + "/approve", actor), 409, "REQUEST_ALREADY_APPROVED");
        // 映射表只服务预查询；实际入口仍按规范权限授权，不因映射缺失而绕过或改写规则。
        jdbc.update("DELETE FROM api_operation_permission WHERE system_id = ? AND operation_id = ?", f.app().getId(), "createRequest");
        jdbc.update("DELETE FROM api_operation WHERE system_id = ? AND operation_id = ?", f.app().getId(), "createRequest");
        assertError(request(HttpMethod.GET, apiPath(f, "createRequest"), f.browserContext(), null, null), 404, "API_NOT_FOUND");
        assertThat(request(HttpMethod.POST, SYSTEMS + "/" + f.app().getCode() + "/requests", actor.browser(), actor.value(),
                Map.of("title", "仍执行真实授权")).getStatusCode().value()).isEqualTo(201);
    }

    @Test
    @DisplayName("API预查询：客户端不能指定他人身份，员工停用后拒绝会话")
    void apiTrustedActorOnly() {
        ApiFixture f = apiFixture();
        JsonNode result = directoryPage(apiPath(f, "approveRequest") + "&employeeId=1&username=global-admin", f.browserContext());
        assertThat(result.path("allowed").asBoolean()).isFalse();
        f.employee().disable();
        employees.save(f.employee());
        assertError(request(HttpMethod.GET, apiPath(f, "approveRequest"), f.browserContext(), null, null), 401, "AUTHENTICATION_REQUIRED");
    }

    @Test
    @DisplayName("API映射引用的权限不能被管理接口直接删除")
    void apiReferencedPermissionCannotBeDeleted() {
        ApiFixture f = apiFixture();
        scopes.save(new AdministratorScope(f.employee().getId(), "GLOBAL", null));
        Token actor = token(f.browserContext());
        assertError(request(HttpMethod.DELETE, "/api/permissions/" + f.permissions().get("request:read"),
                actor.browser(), actor.value(), null), 409, "RESOURCE_CONFLICT");
        assertApi(f, "listSystemRequests", true);
        organizationPermissions.save(new OrganizationPermission(f.employee().getOrganizationId(),
                f.permissions().get("request:read"), "DISABLED"));
        assertApi(f, "listSystemRequests", false); // 全局管理员也不能绕过组织停用。
    }

    /** 独立列出契约要求，避免测试从生产定义反推预期。 */
    private Map<String, List<String>> apiDefinitions() {
        return Map.of("listSystemRequests", List.of("request:read"), "getRequest", List.of("request:read"),
                "createRequest", List.of("request:create"), "updateRequest", List.of("request:read", "request:update"),
                "withdrawRequest", List.of("request:read", "request:withdraw"), "approveRequest", List.of("request:read", "request:approve"),
                "rejectRequest", List.of("request:read", "request:reject"), "deleteRejectedRequest", List.of("request:read", "request:delete"));
    }

    /** 只写入本测试新建系统的数据，不改四个演示系统及其现场授权。 */
    private ApiFixture apiFixture() {
        String prefix = newPrefix();
        Organization company = organization(prefix, "COMPANY", null);
        BusinessSystem app = systems.save(system(prefix + "-app", company));
        Employee actor = employee(prefix + "-actor", company.getId());
        RbacRole role = roles.save(new RbacRole(prefix, company.getId()));
        employeeRoles.save(new EmployeeRole(actor.getId(), role.getId()));
        Map<String, Long> permissionIds = new java.util.HashMap<>();
        apiDefinitions().values().stream().flatMap(List::stream).distinct().forEach(code ->
                permissionIds.put(code, permissions.save(new Permission(code, code, app.getId(), null)).getId()));
        apiDefinitions().forEach((operation, codes) -> apiRegister(app.getId(), operation,
                codes.stream().map(permissionIds::get).toList()));
        return new ApiFixture(app, actor, role, permissionIds, browser(login(actor.getUsername(), PASSWORD, token(null))));
    }

    private void apiRegister(Long systemId, String operationId, List<Long> permissionIds) {
        jdbc.update("INSERT INTO api_operation VALUES (?, ?)", systemId, operationId);
        permissionIds.forEach(permissionId -> jdbc.update("INSERT INTO api_operation_permission VALUES (?, ?, ?)",
                systemId, operationId, permissionId));
    }

    private void apiGrant(ApiFixture f, String code) {
        rolePermissions.save(new RolePermission(f.role().getId(), f.permissions().get(code)));
    }

    private String apiPath(ApiFixture f, String operationId) {
        return ME + "/api-check?systemCode=" + f.app().getCode() + "&operationId=" + operationId;
    }

    private void assertApi(ApiFixture f, String operationId, boolean allowed) {
        JsonNode result = directoryPage(apiPath(f, operationId), f.browserContext());
        assertThat(result.properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrder("systemCode", "operationId", "allowed");
        assertThat(result.path("systemCode").asText()).isEqualTo(f.app().getCode());
        assertThat(result.path("operationId").asText()).isEqualTo(operationId);
        assertThat(result.path("allowed").asBoolean()).isEqualTo(allowed);
    }

    private record ApiFixture(BusinessSystem app, Employee employee, RbacRole role,
                              Map<String, Long> permissions, Browser browserContext) {
    }

    /** 建立独立夹具键，允许同一测试库重复验证。 */
    private String newPrefix() {
        return "auth-" + UUID.randomUUID();
    }

    /** 创建组织及与测试场景对应的归属。 */
    private Organization organization(String code, String type, Long parent) {
        return organizations.save(new Organization(code, code, type, parent, null, null));
    }

    /** 创建带组织默认项的系统。 */
    private BusinessSystem system(String code, Organization owner) {
        return new BusinessSystem(code, code, owner.getId(), true);
    }

    /** 创建合成员工及独立密码哈希。 */
    private Employee employee(String username, Long organizationId) {
        Employee employee = employees.save(new Employee(username, username, organizationId, null, null, null, null));
        accounts.save(new LoginAccount(employee.getId(), passwords.encode(PASSWORD)));
        return employee;
    }

    /** 同一浏览器保存Cookie；业务身份另由访问JWT显式提供。 */
    private Token token(Browser existingBrowser) {
        Browser browser = existingBrowser == null ? new Browser() : existingBrowser;
        ResponseEntity<JsonNode> response = request(HttpMethod.GET, CSRF, browser, null, null);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return new Token(browser, response.getBody().path("token").asText());
    }

    private ResponseEntity<JsonNode> login(String username, String password, Token token) {
        return request(HttpMethod.POST, LOGIN, token.browser(), token.value(),
                Map.of("username", username, "password", password));
    }

    /** 登录响应明确提取Bearer，Cookie只用于后续认证入口。 */
    private Browser browser(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Browser browser = new Browser();
        browser.accessToken = response.getBody().path("accessToken").asText();
        assertThat(browser.accessToken).isNotBlank();
        browser.receive(response);
        return browser;
    }

    private ResponseEntity<JsonNode> request(HttpMethod method, String path, Browser browser, String token,
                                             Map<String, String> body) {
        HttpHeaders headers = new HttpHeaders();
        boolean cookieEndpoint = Set.of(CSRF, LOGIN, LOGOUT, "/api/auth/refresh").contains(path.split("\\?", 2)[0]);
        if (browser != null) {
            if (cookieEndpoint && !browser.cookies.isEmpty()) {
                headers.add(HttpHeaders.COOKIE, browser.cookies.entrySet().stream()
                        .map(entry -> entry.getKey() + "=" + entry.getValue()).collect(java.util.stream.Collectors.joining("; ")));
            } else if (browser.accessToken != null) {
                headers.setBearerAuth(browser.accessToken);
            }
        }
        if (token != null) headers.add("X-CSRF-TOKEN", token);
        if (body != null) headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<JsonNode> response = http.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
        if (browser != null) browser.receive(response);
        return response;
    }

    private void assertError(ResponseEntity<JsonNode> response, int status, String code) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody().path("code").asText()).isEqualTo(code);
    }

    /** 仅为真实HTTP夹具维护浏览器状态；不在服务端模拟认证或自动刷新。 */
    private static final class Browser {
        private String accessToken;
        private final Map<String,String> cookies = new java.util.concurrent.ConcurrentHashMap<>();
        private void receive(ResponseEntity<?> response) {
            for (String header : response.getHeaders().getOrDefault(HttpHeaders.SET_COOKIE, List.of())) {
                String[] pair = header.split(";", 2)[0].split("=", 2);
                if (header.contains("Max-Age=0") || pair.length < 2 || pair[1].isEmpty()) cookies.remove(pair[0]);
                else cookies.put(pair[0], pair[1]);
            }
        }
    }

    private record Token(Browser browser, String value) { }
}
