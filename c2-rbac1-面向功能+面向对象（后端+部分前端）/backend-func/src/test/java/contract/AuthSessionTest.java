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

/** 通过 HTTP 验证 C1 登录契约及真实 Redis 会话。 */
@SpringBootTest(classes = RbacApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.session.redis.namespace=rbac1:func:auth-it", "spring.session.timeout=8s"})
@EnabledIfEnvironmentVariable(named = "RBAC_DB_URL", matches = ".*[/]ooad_rbac1_func_test(?:[?].*)?")
@EnabledIfEnvironmentVariable(named = "RBAC_REDIS_PORT", matches = "[0-9]+")
class AuthSessionTest {
    private static final String CSRF = "/api/auth/csrf";
    private static final String LOGIN = "/api/auth/login";
    private static final String ME = "/api/auth/me";
    private static final String LOGOUT = "/api/auth/logout";
    private static final String SESSION_LOGGER = "rbac0.infrastructure.authentication.SessionAuthentication";
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

    /** 连通 HTTP、匿名会话及 Redis 会话存储。 */
    @Test
    @DisplayName("C1-M2-02 领取匿名会话和 CSRF 令牌")
    void issuesCsrf() {
        ResponseEntity<JsonNode> response = http.getForEntity(CSRF, JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().path("token").asText()).isNotBlank();
        assertThat(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE))
                .contains("RBAC1_FUNC_SESSION=", "Path=/func/", "HttpOnly", "SameSite=Lax");
        assertThat(redis.keys("rbac1:func:auth-it:sessions:*")).isNotEmpty();

        ResponseEntity<JsonNode> missingToken = http.postForEntity(LOGIN,
                Map.of("username", "unused", "password", PASSWORD), JsonNode.class);
        assertThat(missingToken.getStatusCode().value()).isEqualTo(403);
        assertThat(missingToken.getBody().path("code").asText()).isEqualTo("CSRF_INVALID");
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
        String cookie = cookie(loggedIn);
        assertThat(cookie).isNotEqualTo(anonymous.cookie());
        assertThat(request(HttpMethod.GET, ME, anonymous.cookie(), null, null).getStatusCode().value()).isEqualTo(401);
        assertThat(request(HttpMethod.GET, ME, cookie, null, null).getBody().path("employee").path("id").asLong())
                .isEqualTo(employee.getId());
        assertThat(request(HttpMethod.GET, SYSTEMS, cookie, null, null).getBody()).hasSize(2);

        assertError(request(HttpMethod.POST, LOGOUT, cookie, anonymous.value(), null), 403, "CSRF_INVALID");
        Token refreshed = token(cookie);
        assertThat(request(HttpMethod.POST, LOGOUT, cookie, refreshed.value(), null).getStatusCode().value())
                .isEqualTo(204);
        assertError(request(HttpMethod.GET, ME, cookie, null, null), 401, "AUTHENTICATION_REQUIRED");
    }

    /** 验证会话适配器的关键操作可追踪，且日志不记录请求值及失败堆栈。 */
    @Test
    @DisplayName("C1-M2-01 登录与退出只记录会话操作和异常类型")
    void logsSessionOperationsWithoutCredentials() {
        String prefix = newPrefix();
        Organization company = organization(prefix + "-co", "COMPANY", null);
        Employee employee = employee(prefix, company.getId());
        Token anonymous = token(null);
        String rejectedPassword = prefix + "-invalid";
        Logger logger = (Logger) LoggerFactory.getLogger(SESSION_LOGGER);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.setLevel(Level.DEBUG);
        logger.addAppender(events);
        try {
            assertError(login(employee.getUsername(), rejectedPassword, anonymous), 401, "INVALID_CREDENTIALS");
            ResponseEntity<JsonNode> loggedIn = login(employee.getUsername(), PASSWORD, anonymous);
            assertThat(loggedIn.getStatusCode().value()).isEqualTo(200);
            String sessionCookie = cookie(loggedIn);
            Token refreshed = token(sessionCookie);
            assertThat(request(HttpMethod.POST, LOGOUT, sessionCookie, refreshed.value(), null)
                    .getStatusCode().value()).isEqualTo(204);

            List<String> messages = events.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertThat(messages).anyMatch(message -> message.startsWith("verify 失败") && message.endsWith("BadCredentialsException"));
            for (String operation : List.of("verify", "start", "end")) {
                assertThat(messages).contains(operation + " 开始")
                        .anyMatch(message -> message.startsWith(operation + " 完成，耗时 "));
            }
            assertThat(String.join("\n", messages)).doesNotContain(employee.getUsername(), PASSWORD,
                    rejectedPassword, anonymous.value(), refreshed.value(), sessionCookie.substring("RBAC1_FUNC_SESSION=".length()));
            assertThat(events.list).allMatch(event -> event.getThrowableProxy() == null);
        } finally {
            logger.detachAppender(events);
            logger.setLevel(previousLevel);
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
        ResponseEntity<JsonNode> session = login(employee.getUsername(), PASSWORD, token(null));
        assertThat(session.getStatusCode().value()).isEqualTo(200);
        String activeCookie = cookie(session);

        ReflectionTestUtils.setField(employee, "enabled", false);
        employees.save(employee);

        assertError(request(HttpMethod.GET, ME, activeCookie, null, null), 401, "AUTHENTICATION_REQUIRED");
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
        String cookie = cookie(login(employee.getUsername(), PASSWORD, token(null)));
        Token csrf = token(cookie);

        assertError(request(HttpMethod.GET, REQUESTS.formatted(prefix + "-app"), cookie, null, null),
                403, "SYSTEM_ACCESS_DENIED");
        assertError(request(HttpMethod.GET, REQUESTS.formatted(prefix + "-missing"), cookie, null, null),
                404, "SYSTEM_NOT_FOUND");
        assertError(request(HttpMethod.GET, REQUESTS.formatted(prefix + "-app") + "?pageSize=101",
                cookie, null, null), 400, "VALIDATION_FAILED");
        assertError(request(HttpMethod.POST, APPROVE.formatted(0L), cookie, csrf.value(), null),
                400, "VALIDATION_FAILED");
        assertError(request(HttpMethod.POST, APPROVE.formatted(Long.MAX_VALUE), cookie, csrf.value(), null),
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
        String cookie = cookie(login(employee.getUsername(), PASSWORD, token(null)));
        Token csrf = token(cookie);

        ResponseEntity<JsonNode> page = request(HttpMethod.GET,
                REQUESTS.formatted(departmentApp.getCode()) + "?page=2&pageSize=1", cookie, null, null);
        assertThat(page.getStatusCode().value()).isEqualTo(200);
        assertThat(page.getBody().path("total").asLong()).isEqualTo(2);
        assertThat(page.getBody().path("items").get(0).path("id").asLong()).isEqualTo(second.getId());

        assertError(approve(companyRequest, csrf), 403, "PERMISSION_DENIED");
        assertThat(simulatedRequests.findById(companyRequest.getId()).orElseThrow().getStatus()).isEqualTo("PENDING");
        assertError(request(HttpMethod.POST, APPROVE.formatted(first.getId()), cookie, null, null),
                403, "CSRF_INVALID");
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
        Token csrf = token(cookie(login(admin.getUsername(), PASSWORD, token(null))));

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
        Token firstSession = token(cookie(login(admin.getUsername(), PASSWORD, token(null))));
        Token secondSession = token(cookie(login(admin.getUsername(), PASSWORD, token(null))));
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
        return request(HttpMethod.POST, APPROVE.formatted(simulatedRequest.getId()), csrf.cookie(), csrf.value(), null);
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
        Token worker = token(cookie(login(employee.getUsername(), PASSWORD, token(null))));
        String collection = "/api/business-systems/" + app.getCode() + "/requests";

        ResponseEntity<JsonNode> created = request(HttpMethod.POST, collection, worker.cookie(), worker.value(),
                Map.of("title", "  设备维护  "));
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(created.getBody().path("status").asText()).isEqualTo("PENDING");
        assertThat(created.getBody().path("applicantId").asLong()).isEqualTo(employee.getId());
        long requestId = created.getBody().path("id").asLong();
        ResponseEntity<JsonNode> detail = request(HttpMethod.GET, "/api/requests/" + requestId,
                worker.cookie(), null, null);
        assertThat(detail.getStatusCode().value()).isEqualTo(200);
        assertThat(detail.getBody().path("title").asText()).isEqualTo("设备维护");
        JsonNode listing = request(HttpMethod.GET, collection, worker.cookie(), null, null).getBody();
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
        Token actor = token(cookie(login(outsider.getUsername(), PASSWORD, token(null))));
        assertError(request(HttpMethod.GET, fixture.collection(), actor.cookie(), null, null), 403, "PERMISSION_DENIED");
        assertError(request(HttpMethod.GET, detail, actor.cookie(), null, null), 403, "PERMISSION_DENIED");
        assertError(requestAction(detail + "/approve", actor), 403, "PERMISSION_DENIED");
        assertThat(request(HttpMethod.POST, fixture.collection(), actor.cookie(), actor.value(),
                Map.of("title", "只发起不读取")).getStatusCode().value()).isEqualTo(201);

        Permission read = permissions.findBySystemIdAndCode(fixture.system().getId(), "request:read").orElseThrow();
        RbacRole source = roles.save(new RbacRole(newPrefix() + "-source", organizationId));
        rolePermissions.save(new RolePermission(source.getId(), read.getId()));
        Employee admin = employee(newPrefix() + "-admin", organizationId);
        scopes.save(new AdministratorScope(admin.getId(), "GLOBAL", null));
        Token manager = token(cookie(login(admin.getUsername(), PASSWORD, token(null))));
        String edge = "/api/roles/%d/parents/%d".formatted(reader.getId(), source.getId());
        assertNoContent(HttpMethod.PUT, edge, manager);
        assertThat(request(HttpMethod.GET, fixture.collection(), actor.cookie(), null, null).getStatusCode().value())
                .isEqualTo(200);
        assertThat(request(HttpMethod.GET, detail, actor.cookie(), null, null).getStatusCode().value()).isEqualTo(200);
        assertThat(requestAction(detail + "/approve", actor).getStatusCode().value()).isEqualTo(200);
        assertNoContent(HttpMethod.DELETE, edge, manager);
        assertError(request(HttpMethod.GET, fixture.collection(), actor.cookie(), null, null), 403, "PERMISSION_DENIED");
        assertError(request(HttpMethod.GET, detail, actor.cookie(), null, null), 403, "PERMISSION_DENIED");
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
        Token session = token(cookie(login(viewer.getUsername(), PASSWORD, token(null))));
        for (String state : List.of("PENDING", "APPROVED", "WITHDRAWN", "REJECTED")) {
            SimulatedRequest old = new SimulatedRequest(fixture.system().getId(), state);
            ReflectionTestUtils.setField(old, "status", state);
            old = simulatedRequests.save(old);
            String path = "/api/requests/" + old.getId();
            JsonNode detail = request(HttpMethod.GET, path, session.cookie(), null, null).getBody();
            assertThat(detail.path("status").asText()).isEqualTo(state);
            assertThat(detail.path("applicantId").isNull()).isTrue();
            assertError(requestAction(path + "/approve", session), 403, "PERMISSION_DENIED");
        }
        JsonNode page = request(HttpMethod.GET, fixture.collection(), session.cookie(), null, null).getBody();
        assertThat(page.path("total").asInt()).isEqualTo(4);
        assertThat(page.path("allowedActions").size()).isZero();
        BusinessSystem other = systems.save(new BusinessSystem(newPrefix(), "同公司其他系统", owner));
        permissions.save(new Permission("request:read", "其他查看", other.getId(), null));
        assertError(request(HttpMethod.GET, "/api/business-systems/" + other.getCode() + "/requests",
                session.cookie(), null, null), 403, "PERMISSION_DENIED");
        organizationPermissions.save(new OrganizationPermission(owner, read.getId(), "DISABLED"));
        assertError(request(HttpMethod.GET, fixture.collection(), session.cookie(), null, null), 403, "PERMISSION_DENIED");
    }

    /** 本人可修改与撤回待审批单，撤回后原单保持终态。 */
    @Test
    @DisplayName("C2-12 本人修改、撤回和状态冲突")
    void updatesAndWithdrawsPendingRequest() {
        RequestFixture fixture = lifecycleFixture();
        String path = createLifecycleRequest(fixture);
        ResponseEntity<JsonNode> changed = request(HttpMethod.PATCH, path, fixture.owner().cookie(),
                fixture.owner().value(), Map.of("title", "新标题"));
        assertThat(changed.getStatusCode().value()).isEqualTo(200);
        assertThat(changed.getBody().path("title").asText()).isEqualTo("新标题");
        assertThat(changed.getBody().path("status").asText()).isEqualTo("PENDING");
        assertError(request(HttpMethod.PATCH, path, fixture.reviewer().cookie(), fixture.reviewer().value(),
                Map.of("title", "他人标题")), 403, "PERMISSION_DENIED");
        ResponseEntity<JsonNode> withdrawn = requestAction(path + "/withdraw", fixture.owner());
        assertThat(withdrawn.getStatusCode().value()).isEqualTo(200);
        assertThat(withdrawn.getBody().path("status").asText()).isEqualTo("WITHDRAWN");
        assertError(request(HttpMethod.PATCH, path, fixture.owner().cookie(), fixture.owner().value(),
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
        assertError(request(HttpMethod.GET, path, fixture.owner().cookie(), null, null), 404, "REQUEST_NOT_FOUND");
        assertAssignmentError(HttpMethod.DELETE, path, fixture.owner(), 404, "REQUEST_NOT_FOUND");
        assertThat(request(HttpMethod.GET, fixture.collection(), fixture.owner().cookie(), null, null)
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
        assertThat(request(HttpMethod.GET, first, fixture.owner().cookie(), null, null).getBody().path("status").asText())
                .isEqualTo(firstRace.get(0) == 200 ? "APPROVED" : "WITHDRAWN");

        String second = createLifecycleRequest(fixture);
        List<Integer> secondRace = race(() -> assignmentStatus(HttpMethod.POST, second + "/reject", fixture.reviewer()),
                () -> assignmentStatus(HttpMethod.POST, second + "/approve", fixture.owner()));
        assertThat(secondRace).containsExactlyInAnyOrder(200, 409);
        assertThat(request(HttpMethod.GET, second, fixture.owner().cookie(), null, null).getBody().path("status").asText())
                .isEqualTo(secondRace.get(0) == 200 ? "REJECTED" : "APPROVED");
    }

    /** 请求体验证与角色权限均由接口拒绝，不能挟带服务端管理字段。 */
    @Test
    @DisplayName("C2-11 / C2-12 访问、请求体与 C1 遗留申请兼容")
    void validatesLifecycleInputsAndLegacyOwnership() {
        RequestFixture fixture = lifecycleFixture();
        Token owner = fixture.owner();
        assertError(request(HttpMethod.POST, fixture.collection(), owner.cookie(), null, Map.of("title", "待拒绝")),
                403, "CSRF_INVALID");
        assertError(request(HttpMethod.POST, fixture.collection(), owner.cookie(), owner.value(),
                Map.of("title", "  ")), 400, "VALIDATION_FAILED");
        assertError(request(HttpMethod.POST, fixture.collection(), owner.cookie(), owner.value(),
                Map.of("title", "甲".repeat(129))), 400, "VALIDATION_FAILED");
        assertError(request(HttpMethod.POST, fixture.collection(), owner.cookie(), owner.value(),
                Map.of("title", "伪造", "applicantId", "1", "status", "APPROVED")), 400, "VALIDATION_FAILED");
        assertError(request(HttpMethod.POST, "/api/business-systems/absent/requests", owner.cookie(), owner.value(),
                Map.of("title", "待拒绝")), 404, "SYSTEM_NOT_FOUND");
        assertError(request(HttpMethod.GET, "/api/requests/" + Long.MAX_VALUE, owner.cookie(), null, null),
                404, "REQUEST_NOT_FOUND");
        assertError(request(HttpMethod.GET, fixture.collection(), null, null, null), 401, "AUTHENTICATION_REQUIRED");
        Employee noGrant = employee(newPrefix() + "-no-grant", fixture.system().getOwnerOrganizationId());
        Token noGrantToken = token(cookie(login(noGrant.getUsername(), PASSWORD, token(null))));
        assertError(request(HttpMethod.POST, fixture.collection(), noGrantToken.cookie(), noGrantToken.value(),
                Map.of("title", "无权限")), 403, "PERMISSION_DENIED");

        SimulatedRequest legacy = simulatedRequests.save(new SimulatedRequest(fixture.system().getId(), "遗留申请"));
        String legacyPath = "/api/requests/" + legacy.getId();
        JsonNode body = request(HttpMethod.GET, legacyPath, owner.cookie(), null, null).getBody();
        assertThat(body.path("applicantId").isNull()).isTrue();
        assertError(requestAction(legacyPath + "/withdraw", owner), 403, "PERMISSION_DENIED");
        assertThat(requestAction(legacyPath + "/approve", fixture.reviewer()).getStatusCode().value()).isEqualTo(200);
    }

    /** 框架日志之外保留可按动作和申请编号核对的提交结果。 */
    @Test
    @DisplayName("C2-14 Logback 记录发起、修改、读取和失败，不输出标题")
    void recordsRequestActionOutcomes() {
        RequestFixture fixture = lifecycleFixture();
        Logger logger = (Logger) LoggerFactory.getLogger("rbac1.func.request");
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        try {
            String path = createLifecycleRequest(fixture);
            assertThat(request(HttpMethod.GET, path, fixture.owner().cookie(), null, null).getStatusCode().value())
                    .isEqualTo(200);
            assertThat(request(HttpMethod.PATCH, path, fixture.owner().cookie(), fixture.owner().value(),
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
        Token ownerToken = token(cookie(login(owner.getUsername(), PASSWORD, token(null))));
        Token reviewerToken = token(cookie(login(reviewer.getUsername(), PASSWORD, token(null))));
        return new RequestFixture(app, "/api/business-systems/" + app.getCode() + "/requests", ownerToken, reviewerToken);
    }

    private String createLifecycleRequest(RequestFixture fixture) {
        Token actor = fixture.owner();
        ResponseEntity<JsonNode> response = request(HttpMethod.POST, fixture.collection(), actor.cookie(), actor.value(),
                Map.of("title", "演示申请"));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return "/api/requests/" + response.getBody().path("id").asLong();
    }

    private ResponseEntity<JsonNode> requestAction(String endpoint, Token actor) {
        return request(HttpMethod.POST, endpoint, actor.cookie(), actor.value(), null);
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
        Token manager = token(cookie(login(admin.getUsername(), PASSWORD, token(null))));
        Token worker = token(cookie(login(employee.getUsername(), PASSWORD, token(null))));
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
        ResponseEntity<JsonNode> inheritedPage = request(HttpMethod.GET, inherited, manager.cookie(), null, null);
        assertThat(inheritedPage.getStatusCode().value()).isEqualTo(200);
        assertThat(inheritedPage.getBody().path("total").asInt()).isEqualTo(1);
        assertThat(inheritedPage.getBody().path("items").get(0).path("id").asLong()).isEqualTo(approval.getId());
        assertError(request(HttpMethod.GET, inherited, worker.cookie(), null, null), 403, "PERMISSION_DENIED");
        assertThat(request(HttpMethod.GET, parents, manager.cookie(), null, null).getBody().path("total").asInt())
                .isEqualTo(1);
        String effective = "/api/employees/%d/effective-permissions?systemCode=%s"
                .formatted(employee.getId(), app.getCode());
        assertThat(request(HttpMethod.GET, effective, manager.cookie(), null, null).getBody()
                .path("items").get(0).path("id").asLong()).isEqualTo(approval.getId());
        assertError(request(HttpMethod.PUT, parents + "/" + supervisor.getId(), manager.cookie(), manager.value(), null),
                409, "SELF_ROLE_INHERITANCE");
        assertError(request(HttpMethod.PUT, "/api/roles/%d/parents/%d".formatted(approver.getId(), supervisor.getId()),
                manager.cookie(), manager.value(), null), 409, "ROLE_INHERITANCE_CYCLE");
        assertThat(approve(first, worker).getStatusCode().value()).isEqualTo(200);
        assertNoContent(HttpMethod.DELETE, edge, manager);
        assertNoContent(HttpMethod.DELETE, edge, manager);
        assertThat(request(HttpMethod.GET, inherited, manager.cookie(), null, null)
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
        Token manager = token(cookie(login(admin.getUsername(), PASSWORD, token(null))));
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
        assertError(request(HttpMethod.DELETE, "/api/roles/" + a.getId(), manager.cookie(), manager.value(), null),
                409, "RESOURCE_CONFLICT");
        Organization other = organization(prefix + "-other", "COMPANY", null);
        RbacRole outside = roles.save(new RbacRole(prefix + "-outside", other.getId()));
        assertError(request(HttpMethod.PUT, edge.formatted(a.getId(), outside.getId()),
                manager.cookie(), manager.value(), null), 409, "ROLE_INHERITANCE_SCOPE_MISMATCH");
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
        Token manager = token(cookie(login(admin.getUsername(), PASSWORD, token(null))));
        Token worker = token(cookie(login(employee.getUsername(), PASSWORD, token(null))));
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
        assertThat(request(HttpMethod.GET, effective, manager.cookie(), null, null).getBody().path("total").asInt())
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
        Token manager = token(cookie(login(admin.getUsername(), PASSWORD, token(null))));
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
        Token manager = token(cookie(login(admin.getUsername(), PASSWORD, token(null))));
        Token worker = token(cookie(login(employee.getUsername(), PASSWORD, token(null))));
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
        Token manager = token(cookie(login(departmentAdmin.getUsername(), PASSWORD, token(null))));
        Token companyManager = token(cookie(login(companyAdmin.getUsername(), PASSWORD, token(null))));
        Token worker = token(cookie(login(member.getUsername(), PASSWORD, token(null))));
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
        Token manager = token(cookie(login(admin.getUsername(), PASSWORD, token(null))));
        String employeeRole = EMPLOYEE_ROLE.formatted(pending.getId(), role.getId());
        String rolePermission = ROLE_PERMISSION.formatted(role.getId(), approval.getId());

        assertAssignmentError(HttpMethod.PUT, EMPLOYEE_ROLE.formatted(0L, role.getId()), manager,
                400, "VALIDATION_FAILED");
        assertAssignmentError(HttpMethod.DELETE, EMPLOYEE_ROLE.formatted(Long.MAX_VALUE, role.getId()), manager,
                404, "RESOURCE_NOT_FOUND");
        assertAssignmentError(HttpMethod.PUT, ROLE_PERMISSION.formatted(role.getId(), Long.MAX_VALUE), manager,
                404, "RESOURCE_NOT_FOUND");
        assertError(request(HttpMethod.PUT, rolePermission, manager.cookie(), null, null), 403, "CSRF_INVALID");
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
        Token firstAdmin = token(cookie(login(admin.getUsername(), PASSWORD, token(null))));
        Token secondAdmin = token(cookie(login(admin.getUsername(), PASSWORD, token(null))));
        String path = EMPLOYEE_ROLE.formatted(member.getId(), role.getId());
        CyclicBarrier start = new CyclicBarrier(2);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = executor.submit(() -> {
                start.await(3, TimeUnit.SECONDS);
                return request(HttpMethod.PUT, path, firstAdmin.cookie(), firstAdmin.value(), null).getStatusCode().value();
            });
            Future<Integer> second = executor.submit(() -> {
                start.await(3, TimeUnit.SECONDS);
                return request(HttpMethod.PUT, path, secondAdmin.cookie(), secondAdmin.value(), null).getStatusCode().value();
            });
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactly(204, 204);
        }
        assertThat(employeeRoles.existsById(new EmployeeRole.Key(member.getId(), role.getId()))).isTrue();
    }

    /** 管理请求拒绝时同时核对状态码和错误体。 */
    private void assertAssignmentError(HttpMethod method, String path, Token csrf, int status, String code) {
        assertError(request(method, path, csrf.cookie(), csrf.value(), null), status, code);
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
        Token manager = token(cookie(login(admin.getUsername(), PASSWORD, token(null))));
        Token employee = token(cookie(login(worker.getUsername(), PASSWORD, token(null))));
        String companyPolicy = POLICY.formatted(company.getId(), app.getCode());
        String departmentPolicy = POLICY.formatted(department.getId(), app.getCode());
        SimulatedRequest adminRequest = simulatedRequests.save(new SimulatedRequest(app.getId(), "管理员审批"));
        SimulatedRequest workerRequest = simulatedRequests.save(new SimulatedRequest(app.getId(), "员工审批"));

        assertAssignmentError(HttpMethod.PUT, companyPolicy + "/disabled", employee, 403, "PERMISSION_DENIED");
        assertAssignmentError(HttpMethod.GET, companyPolicy, employee, 403, "PERMISSION_DENIED");
        assertThat(request(HttpMethod.GET, departmentPolicy, manager.cookie(), null, null)
                .getBody().path("status").asText()).isEqualTo("OPEN");
        assertNoContent(HttpMethod.PUT, departmentPolicy + "/disabled", manager);
        assertThat(request(HttpMethod.GET, departmentPolicy, manager.cookie(), null, null)
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
        Token manager = token(cookie(login(admin.getUsername(), PASSWORD, token(null))));
        Token grantingAdmin = token(cookie(login(operator.getUsername(), PASSWORD, token(null))));
        Token engineerSession = token(cookie(login(engineer.getUsername(), PASSWORD, token(null))));
        Token financeSession = token(cookie(login(accountant.getUsername(), PASSWORD, token(null))));
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
        Token manager = token(cookie(login(admin.getUsername(), PASSWORD, token(null))));
        Token secondManager = token(cookie(login(admin.getUsername(), PASSWORD, token(null))));
        Token workerSession = token(cookie(login(worker.getUsername(), PASSWORD, token(null))));
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
        assertThat(approve(pending, workerSession).getStatusCode().value()).isEqualTo(reopened.get(0) == 204 ? 200 : 403);
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
        return request(method, path, token.cookie(), token.value(), null).getStatusCode().value();
    }

    /** 管理关系接口的成功响应无正文。 */
    private void assertNoContent(HttpMethod method, String path, Token csrf) {
        ResponseEntity<JsonNode> response = request(method, path, csrf.cookie(), csrf.value(), null);
        assertThat(response.getStatusCode().value()).as("%s %s: %s", method, path, response.getBody())
                .isEqualTo(204);
        assertThat(response.getBody()).isNull();
    }

    /** 验证 Redis 在空闲后删除会话，旧 Cookie 无法恢复身份。 */
    @Test
    @DisplayName("C1-M2-01 Redis 会话空闲过期")
    void expiresAnIdleSession() {
        String prefix = newPrefix();
        Organization company = organization(prefix, "COMPANY", null);
        Employee employee = employee(prefix, company.getId());
        Token anonymous = token(null);
        Set<String> beforeLogin = redis.keys("rbac1:func:auth-it:sessions:*");
        String cookie = cookie(login(employee.getUsername(), PASSWORD, anonymous));
        Set<String> afterLogin = redis.keys("rbac1:func:auth-it:sessions:*");
        List<String> newKeys = afterLogin.stream().filter(key -> !beforeLogin.contains(key)).toList();
        assertThat(newKeys).hasSize(1);
        await().atMost(Duration.ofSeconds(15))
                .until(() -> Boolean.FALSE.equals(redis.hasKey(newKeys.getFirst())));
        assertError(request(HttpMethod.GET, ME, cookie, null, null), 401, "AUTHENTICATION_REQUIRED");
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
        String adminCookie = cookie(login(admin.getUsername(), PASSWORD, token(null)));
        String workerCookie = cookie(login(outsider.getUsername(), PASSWORD, token(null)));

        ResponseEntity<JsonNode> result = request(HttpMethod.GET, ADMIN_EMPLOYEES + "?page=2&pageSize=1",
                adminCookie, null, null);
        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getBody().path("total").asLong()).isEqualTo(2);
        assertThat(result.getBody().path("items").get(0).path("phone").asText()).isEqualTo("138****8000");
        JsonNode directory = request(HttpMethod.GET, ADMIN_DEPARTMENTS, adminCookie, null, null).getBody();
        assertThat(directory.path("total").asLong()).isEqualTo(1);
        assertThat(directory.path("items").get(0).path("sourceParentName").asText()).isEqualTo("原表上级");
        assertError(request(HttpMethod.GET, ADMIN_EMPLOYEES, workerCookie, null, null), 403, "PERMISSION_DENIED");
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
        String adminCookie = cookie(login(admin.getUsername(), PASSWORD, token(null)));
        String globalCookie = cookie(login(global.getUsername(), PASSWORD, token(null)));
        String rolePage = "/api/roles/" + localRole.getId() + "/permissions";
        String employeePage = ADMIN_EMPLOYEES + "/" + member.getId() + "/roles";

        JsonNode assigned = request(HttpMethod.GET, rolePage, adminCookie, null, null).getBody();
        assertThat(assigned.path("total").asLong()).isEqualTo(1);
        assertThat(assigned.path("items").get(0).path("id").asLong()).isEqualTo(companyApproval.getId());
        assertThat(request(HttpMethod.GET, rolePage, globalCookie, null, null).getBody().path("total").asLong())
                .isEqualTo(2);
        assertThat(request(HttpMethod.GET, employeePage, adminCookie, null, null).getBody().path("total").asLong())
                .isEqualTo(1);
        assertError(request(HttpMethod.GET, ADMIN_EMPLOYEES + "/" + outsider.getId() + "/roles",
                adminCookie, null, null), 403, "PERMISSION_DENIED");
        assertError(request(HttpMethod.GET, "/api/roles/" + peerRole.getId() + "/permissions", adminCookie, null, null),
                403, "PERMISSION_DENIED");
        assertError(request(HttpMethod.GET, "/api/roles/" + Long.MAX_VALUE + "/permissions", adminCookie, null, null),
                404, "RESOURCE_NOT_FOUND");
        assertError(request(HttpMethod.GET, ADMIN_EMPLOYEES + "?pageSize=101", adminCookie, null, null),
                400, "VALIDATION_FAILED");
        employeeRoles.deleteById(new EmployeeRole.Key(member.getId(), localRole.getId()));
        assertThat(request(HttpMethod.GET, employeePage, adminCookie, null, null).getBody().path("total").asLong())
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
        String adminCookie = cookie(login(admin.getUsername(), PASSWORD, token(null)));
        assertThat(request(HttpMethod.GET, "/api/companies", adminCookie, null, null).getBody().path("total").asLong())
                .isEqualTo(1);
        JsonNode visibleRoles = request(HttpMethod.GET, "/api/roles?pageSize=1", adminCookie, null, null).getBody();
        long lastRolePage = visibleRoles.path("total").asLong();
        assertThat(request(HttpMethod.GET, "/api/roles?pageSize=1&page=" + lastRolePage, adminCookie, null, null)
                .getBody().path("items").get(0).path("id").asLong()).isEqualTo(localRole.getId());
        assertThat(request(HttpMethod.GET, "/api/permissions", adminCookie, null, null).getBody()
                .path("items").get(0).path("id").asLong()).isEqualTo(permission.getId());
        int page = Math.toIntExact(catalogEntries.count());
        JsonNode entry = request(HttpMethod.GET, "/api/catalog-entries?pageSize=1&page=" + page,
                adminCookie, null, null).getBody().path("items").get(0);
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
        Token manager = token(cookie(login(admin.getUsername(), PASSWORD, token(null))));
        Token workerSession = token(cookie(login(worker.getUsername(), PASSWORD, token(null))));
        String target = ADMIN_EMPLOYEES + "/" + worker.getId();
        assertThat(request(HttpMethod.POST, ADMIN_EMPLOYEES, manager.cookie(), manager.value(),
                Map.of("username", prefix + "-new", "name", "新员工", "organizationId", local.getId().toString()))
                .getStatusCode().value()).isEqualTo(201);
        assertThat(request(HttpMethod.PATCH, target, manager.cookie(), manager.value(), Map.of("name", "新姓名"))
                .getBody().path("name").asText()).isEqualTo("新姓名");
        RbacRole role = roles.save(new RbacRole(prefix, local.getId()));
        employeeRoles.save(new EmployeeRole(worker.getId(), role.getId()));
        assertError(request(HttpMethod.PATCH, target, manager.cookie(), manager.value(),
                Map.of("organizationId", peer.getId().toString())), 409, "ASSIGNMENT_CONFLICT");
        assertNoContent(HttpMethod.DELETE, EMPLOYEE_ROLE.formatted(worker.getId(), role.getId()), manager);
        assertThat(request(HttpMethod.PATCH, target, manager.cookie(), manager.value(),
                Map.of("organizationId", peer.getId().toString())).getBody().path("organizationId").asLong())
                .isEqualTo(peer.getId());
        RbacRole peerRole = roles.save(new RbacRole(prefix, peer.getId()));
        assertNoContent(HttpMethod.PUT, EMPLOYEE_ROLE.formatted(worker.getId(), peerRole.getId()), manager);
        assertNoContent(HttpMethod.DELETE, target, manager);
        assertError(request(HttpMethod.GET, ME, workerSession.cookie(), null, null), 401, "AUTHENTICATION_REQUIRED");
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
        Token manager = token(cookie(login(admin.getUsername(), PASSWORD, token(null))));
        Token unrelated = token(cookie(login(outsider.getUsername(), PASSWORD, token(null))));
        Map<String, String> roleBody = Map.of("name", prefix, "ownerOrganizationId", local.getId().toString());
        Map<String, String> permissionBody = Map.of("code", "request:approve", "name", "审批",
                "systemId", app.getId().toString(), "ownerOrganizationId", local.getId().toString());
        ResponseEntity<JsonNode> createdRole = request(HttpMethod.POST, "/api/roles", manager.cookie(),
                manager.value(), roleBody);
        ResponseEntity<JsonNode> createdPermission = request(HttpMethod.POST, "/api/permissions", manager.cookie(),
                manager.value(), permissionBody);
        assertThat(createdRole.getStatusCode().value()).isEqualTo(201);
        assertThat(createdPermission.getStatusCode().value()).isEqualTo(201);
        Long roleId = createdRole.getBody().path("id").asLong();
        Long permissionId = createdPermission.getBody().path("id").asLong();
        String role = "/api/roles/" + roleId;
        String permission = "/api/permissions/" + permissionId;
        String assignment = ROLE_PERMISSION.formatted(roleId, permissionId);
        assertError(request(HttpMethod.POST, "/api/roles", manager.cookie(), manager.value(), roleBody),
                409, "RESOURCE_CONFLICT");
        assertError(request(HttpMethod.POST, "/api/permissions", manager.cookie(), manager.value(), permissionBody),
                409, "RESOURCE_CONFLICT");
        assertError(request(HttpMethod.PATCH, role, unrelated.cookie(), unrelated.value(), Map.of("name", "越权")),
                403, "PERMISSION_DENIED");
        assertAssignmentError(HttpMethod.DELETE, permission, unrelated, 403, "PERMISSION_DENIED");
        assertThat(request(HttpMethod.PATCH, permission, manager.cookie(), manager.value(), Map.of("name", "审批操作"))
                .getBody().path("code").asText()).isEqualTo("request:approve");
        assertThat(request(HttpMethod.GET, permission, manager.cookie(), null, null).getBody().path("name").asText())
                .isEqualTo("审批操作");
        assertNoContent(HttpMethod.PUT, assignment, manager);
        assertAssignmentError(HttpMethod.DELETE, role, manager, 409, "RESOURCE_CONFLICT");
        assertAssignmentError(HttpMethod.DELETE, permission, manager, 409, "RESOURCE_CONFLICT");
        assertNoContent(HttpMethod.DELETE, assignment, manager);
        assertNoContent(HttpMethod.DELETE, role, manager);
        assertNoContent(HttpMethod.DELETE, permission, manager);
        assertError(request(HttpMethod.GET, role, manager.cookie(), null, null), 404, "RESOURCE_NOT_FOUND");
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

    /** 领取当前会话的令牌，保留服务端发出的 Cookie。 */
    private Token token(String existingCookie) {
        ResponseEntity<JsonNode> response = request(HttpMethod.GET, CSRF, existingCookie, null, null);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return new Token(existingCookie == null ? cookie(response) : existingCookie,
                response.getBody().path("token").asText());
    }

    /** 使用同一个匿名会话提交凭据与令牌。 */
    private ResponseEntity<JsonNode> login(String username, String password, Token token) {
        return request(HttpMethod.POST, LOGIN, token.cookie(), token.value(),
                Map.of("username", username, "password", password));
    }

    /** 提取 Set-Cookie 中供后续请求使用的键值。 */
    private String cookie(ResponseEntity<JsonNode> response) {
        assertThat(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).startsWith("RBAC1_FUNC_SESSION=");
        return response.getHeaders().getFirst(HttpHeaders.SET_COOKIE).split(";", 2)[0];
    }

    /** 发送显式传入 Cookie 的请求，避免混用两个浏览器会话。 */
    private ResponseEntity<JsonNode> request(HttpMethod method, String path, String cookie, String token,
                                             Map<String, String> body) {
        HttpHeaders headers = new HttpHeaders();
        if (cookie != null) {
            headers.add(HttpHeaders.COOKIE, cookie);
        }
        if (token != null) {
            headers.add("X-CSRF-TOKEN", token);
        }
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return http.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    /** 检查 HTTP 与契约错误码一致。 */
    private void assertError(ResponseEntity<JsonNode> response, int status, String code) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody().path("code").asText()).isEqualTo(code);
    }

    /** 请求使用的会话 Cookie 与 CSRF 令牌。 */
    private record Token(String cookie, String value) {
    }
}
