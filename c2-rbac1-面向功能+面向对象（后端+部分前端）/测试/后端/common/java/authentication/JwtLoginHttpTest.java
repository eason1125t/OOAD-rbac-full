// 文件职责：通过真实HTTP/MySQL/Redis验证JWT登录、严格刷新、退出和安全链错误边界。
// 协作关系：两版共同执行；依赖故障用明确Spy注入，不停止共享数据库或Redis服务。
package authentication;

import app.RbacApplication;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import organization.Employee;
import organization.EmployeeRepository;
import organization.Organization;
import organization.OrganizationRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

@SpringBootTest(classes = RbacApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "RBAC_DB_URL", matches = ".*[/]ooad_rbac1_(?:func|oo)_test(?:[?].*)?")
@EnabledIfEnvironmentVariable(named = "RBAC_AUTH_NAMESPACE", matches = "rbac1:(func|oo):jwt-test:.*")
class JwtLoginHttpTest {
    private static final String CSRF = "/api/auth/csrf", LOGIN = "/api/auth/login", REFRESH = "/api/auth/refresh";
    private static final String LOGOUT = "/api/auth/logout", ME = "/api/auth/me";
    private static final String PASSWORD = "jwt-test-password";
    @Autowired private TestRestTemplate http;
    @Autowired private OrganizationRepository organizations;
    @Autowired private EmployeeRepository employees;
    @Autowired private LoginAccountRepository accounts;
    @Autowired private PasswordEncoder passwords;
    @Autowired private AuthenticationProperties settings;
    @Autowired private JwtAccessTokens tokens;
    @Autowired private StringRedisTemplate redis;
    @MockitoSpyBean private RedisLoginStore logins;
    @MockitoSpyBean private CurrentEmployeeService currentEmployees;

    @Test
    void loginIssuesBearerAndCookieCannotAuthenticateBusiness() {
        Employee employee = employee();
        Client client = signedIn(employee);
        assertThat(client.loginResponse.getBody().properties()).extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("employee", "workspaces", "defaultWorkspaceKey", "accessToken", "tokenType", "expiresIn");
        assertThat(client.loginResponse.getBody().path("expiresIn").asLong()).isEqualTo(900);
        assertThat(client.loginResponse.getHeaders().get(HttpHeaders.SET_COOKIE))
                .anyMatch(header -> header.startsWith(settings.refreshCookieName() + "=")
                        && header.contains("HttpOnly") && header.contains("SameSite=Strict"))
                .noneMatch(header -> header.contains("_SESSION="));
        assertThat(client.call(HttpMethod.GET, ME, null, null, true).getStatusCode().value()).isEqualTo(200);
        Client cookieOnly = client.copy();
        cookieOnly.access = null;
        assertError(cookieOnly.call(HttpMethod.GET, ME, null, null, true), 401, "AUTHENTICATION_REQUIRED");
        cookieOnly.cookies.put("RBAC1_" + System.getProperty("rbac.test.edition").toUpperCase() + "_SESSION", UUID.randomUUID().toString());
        assertError(cookieOnly.call(HttpMethod.GET, ME, null, null, true), 401, "AUTHENTICATION_REQUIRED");
    }

    @Test
    void refreshRotatesAndConfirmedReuseRevokesAllAccessForThatLogin() {
        Client client = signedIn(employee());
        String csrf = client.csrf();
        Client old = client.copy();
        String previousRefresh = client.cookies.get(settings.refreshCookieName());
        String previousAccess = client.access;
        ResponseEntity<JsonNode> refreshed = client.call(HttpMethod.POST, REFRESH, null, csrf, false);
        assertThat(refreshed.getStatusCode().value()).isEqualTo(200);
        assertThat(refreshed.getBody().properties()).extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("accessToken", "tokenType", "expiresIn");
        client.access = refreshed.getBody().path("accessToken").asText();
        assertThat(client.access).isNotEqualTo(previousAccess);
        assertThat(client.cookies.get(settings.refreshCookieName())).isNotEqualTo(previousRefresh);
        assertThat(client.call(HttpMethod.GET, ME, null, null, true).getStatusCode().value()).isEqualTo(200);
        assertError(old.call(HttpMethod.POST, REFRESH, null, csrf, false), 401, "REFRESH_TOKEN_REUSED");
        assertError(client.call(HttpMethod.GET, ME, null, null, true), 401, "AUTHENTICATION_REQUIRED");
    }

    @Test
    void unknownSecretForKnownSidDoesNotRevokeLegitimateLogin() {
        Client client = signedIn(employee());
        String csrf = client.csrf();
        Client forged = client.copy();
        String sid = tokens.decode(client.access).getClaimAsString("sid");
        forged.cookies.put(settings.refreshCookieName(), RefreshCredential.create(sid).value());
        assertError(forged.call(HttpMethod.POST, REFRESH, null, csrf, false), 401, "REFRESH_TOKEN_INVALID");
        assertThat(client.call(HttpMethod.GET, ME, null, null, true).getStatusCode().value()).isEqualTo(200);
        assertThat(client.call(HttpMethod.POST, REFRESH, null, csrf, false).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void logoutIsImmediateAndIndependentOfOtherBrowsers() {
        Employee employee = employee();
        Client first = signedIn(employee);
        Client second = signedIn(employee);
        assertThat(first.call(HttpMethod.POST, LOGOUT, null, first.csrf(), false).getStatusCode().value()).isEqualTo(204);
        assertError(first.call(HttpMethod.GET, ME, null, null, true), 401, "AUTHENTICATION_REQUIRED");
        assertThat(second.call(HttpMethod.GET, ME, null, null, true).getStatusCode().value()).isEqualTo(200);
        assertThat(first.call(HttpMethod.POST, LOGOUT, null, first.csrf(), false).getStatusCode().value()).isEqualTo(204);
    }

    @Test
    void replacingBrowserLoginRevokesOldSidButFailedLoginPreservesCurrent() {
        Employee employee = employee();
        Client client = signedIn(employee);
        Client otherBrowser = signedIn(employee);
        Client old = client.copy();
        ResponseEntity<JsonNode> replacement = client.login(employee.getUsername(), PASSWORD);
        assertThat(replacement.getStatusCode().value()).isEqualTo(200);
        assertError(old.call(HttpMethod.GET, ME, null, null, true), 401, "AUTHENTICATION_REQUIRED");
        assertThat(otherBrowser.call(HttpMethod.GET, ME, null, null, true).getStatusCode().value()).isEqualTo(200);
        String refresh = client.cookies.get(settings.refreshCookieName());
        assertError(client.login(employee.getUsername(), "wrong"), 401, "INVALID_CREDENTIALS");
        assertThat(client.cookies.get(settings.refreshCookieName())).isEqualTo(refresh);
        assertThat(client.call(HttpMethod.GET, ME, null, null, true).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void expiredAccessCanRefreshAndLogoutButCannotAccessBusiness() {
        Employee employee = employee();
        Client client = signedIn(employee);
        String sid = tokens.decode(client.access).getClaimAsString("sid");
        JwtAccessTokens historical = JwtAccessTokens.fromPem(settings.issuer(), settings.audience(),
                settings.privateKeyLocation(), settings.publicKeyLocation(), Clock.offset(Clock.systemUTC(), Duration.ofMinutes(-16)),
                Duration.ofMinutes(15));
        client.access = historical.issue(employee.getId(), sid, java.time.Instant.now().plusSeconds(3600)).accessToken();
        ResponseEntity<JsonNode> expired = client.call(HttpMethod.GET, ME, null, null, true);
        assertError(expired, 401, "ACCESS_TOKEN_EXPIRED");
        assertThat(expired.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
        assertThat(client.call(HttpMethod.POST, REFRESH, null, client.csrf(), true).getStatusCode().value()).isEqualTo(200);
        assertThat(client.call(HttpMethod.POST, LOGOUT, null, client.csrf(), true).getStatusCode().value()).isEqualTo(204);
    }

    @Test
    void cookieEntrancesRequireCsrfAndDoNotAcceptBodyRefreshAlias() {
        Client client = signedIn(employee());
        String csrf = client.csrf();
        assertError(client.call(HttpMethod.POST, REFRESH, null, null, false), 403, "CSRF_INVALID");
        assertError(client.call(HttpMethod.POST, REFRESH, null, null, true), 403, "CSRF_INVALID");
        assertError(client.call(HttpMethod.POST, LOGOUT, null, null, true), 403, "CSRF_INVALID");
        assertError(client.call(HttpMethod.POST, LOGOUT, null, "wrong", false), 403, "CSRF_INVALID");
        Client bodyOnly = client.copy();
        bodyOnly.cookies.remove(settings.refreshCookieName());
        assertError(bodyOnly.call(HttpMethod.POST, REFRESH, Map.of("refreshToken", client.cookies.get(settings.refreshCookieName())),
                csrf, false), 401, "REFRESH_TOKEN_INVALID");
        assertThat(client.call(HttpMethod.GET, ME, null, null, true).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void concurrentRefreshHasOneSuccessAndOneReuseRejection() throws Exception {
        Client client = signedIn(employee());
        String csrf = client.csrf();
        Client a = client.copy(), b = client.copy();
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> { barrier.await(); return a.call(HttpMethod.POST, REFRESH, null, csrf, false); });
            var second = workers.submit(() -> { barrier.await(); return b.call(HttpMethod.POST, REFRESH, null, csrf, false); });
            var results = List.of(first.get(10, java.util.concurrent.TimeUnit.SECONDS), second.get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertThat(results).extracting(result -> result.getStatusCode().value()).containsExactlyInAnyOrder(200, 401);
            assertThat(results.stream().filter(result -> result.getStatusCode().value() == 401).findFirst().orElseThrow()
                    .getBody().path("code").asText()).isEqualTo("REFRESH_TOKEN_REUSED");
        }
        assertError(client.call(HttpMethod.GET, ME, null, null, true), 401, "AUTHENTICATION_REQUIRED");
    }

    @Test
    void disabledEmployeeCannotRefreshAndRecordIsRevoked() {
        Employee employee = employee();
        Client client = signedIn(employee);
        String sid = tokens.decode(client.access).getClaimAsString("sid");
        employee.disable();
        employees.save(employee);
        assertError(client.call(HttpMethod.POST, REFRESH, null, client.csrf(), false), 401, "REFRESH_TOKEN_INVALID");
        assertThat(redis.hasKey(logins.key(sid))).isFalse();
        assertError(client.call(HttpMethod.GET, ME, null, null, true), 401, "AUTHENTICATION_REQUIRED");
    }

    @Test
    void observedIneligibleEmployeeCannotReviveLoginByReEnabling() {
        Employee employee = employee();
        Client client = signedIn(employee);
        String sid = tokens.decode(client.access).getClaimAsString("sid");
        employee.disable();
        employees.save(employee);
        assertError(client.call(HttpMethod.GET, ME, null, null, true), 401, "AUTHENTICATION_REQUIRED");
        assertThat(redis.hasKey(logins.key(sid))).isFalse();
        org.springframework.test.util.ReflectionTestUtils.setField(employee, "enabled", true);
        employees.save(employee);
        assertError(client.call(HttpMethod.GET, ME, null, null, true), 401, "AUTHENTICATION_REQUIRED");
    }

    @Test
    void redisFailureIs503AndInvalidJwtIsRejectedBeforeRedis() {
        Client client = signedIn(employee());
        doThrow(new RedisConnectionFailureException("injected test outage")).when(logins).requireActive(anyString(), anyLong());
        assertError(client.call(HttpMethod.GET, ME, null, null, true), 503, "AUTHENTICATION_UNAVAILABLE");
        client.access = "not-a-jwt";
        assertError(client.call(HttpMethod.GET, ME, null, null, true), 401, "AUTHENTICATION_REQUIRED");
    }

    @Test
    void redisFailureDuringRefreshDoesNotClearCookieOrPretendLogoutSucceeded() {
        Client client = signedIn(employee());
        String raw = client.cookies.get(settings.refreshCookieName());
        doThrow(new RedisConnectionFailureException("injected test outage")).when(logins).inspect(any(RefreshCredential.class));
        assertError(client.call(HttpMethod.POST, REFRESH, null, client.csrf(), false), 503, "AUTHENTICATION_UNAVAILABLE");
        assertThat(client.cookies.get(settings.refreshCookieName())).isEqualTo(raw);
        doThrow(new RedisConnectionFailureException("injected test outage")).when(logins).revoke(any(RefreshCredential.class));
        assertError(client.call(HttpMethod.POST, LOGOUT, null, client.csrf(), false), 503, "AUTHENTICATION_UNAVAILABLE");
        assertThat(client.cookies.get(settings.refreshCookieName())).isEqualTo(raw);
    }

    @Test
    void employeeDatabaseFailureDuringRefreshDoesNotConsumeCredential() {
        Employee employee = employee();
        Client client = signedIn(employee);
        String raw = client.cookies.get(settings.refreshCookieName());
        doThrow(new DataAccessResourceFailureException("injected database outage")).when(currentEmployees).findActive(employee.getId());
        assertError(client.call(HttpMethod.POST, REFRESH, null, client.csrf(), false), 503, "AUTHORIZATION_UNAVAILABLE");
        assertThat(client.cookies.get(settings.refreshCookieName())).isEqualTo(raw);
        assertThat(logins.inspect(RefreshCredential.parse(raw).orElseThrow()).employeeId()).isEqualTo(employee.getId());
    }

    @Test
    void otherEditionIssuerAndAudienceAreRejectedEvenWithSameTestKey() {
        Employee employee = employee();
        Client client = signedIn(employee);
        String other = System.getProperty("rbac.test.edition").equals("func") ? "oo" : "func";
        JwtAccessTokens foreign = JwtAccessTokens.fromPem("urn:ooad-rbac:c2:" + other, "rbac1-" + other,
                settings.privateKeyLocation(), settings.publicKeyLocation(), Clock.systemUTC(), Duration.ofMinutes(15));
        client.access = foreign.issue(employee.getId(), tokens.decode(client.access).getClaimAsString("sid"),
                java.time.Instant.now().plusSeconds(3600)).accessToken();
        assertError(client.call(HttpMethod.GET, ME, null, null, true), 401, "AUTHENTICATION_REQUIRED");
    }

    private Employee employee() {
        String prefix = "jwt-it-" + UUID.randomUUID();
        Organization company = organizations.save(new Organization(prefix, prefix, "COMPANY", null, null, null));
        Employee employee = employees.save(new Employee(prefix, prefix, company.getId(), null, null, null, null));
        accounts.save(new LoginAccount(employee.getId(), passwords.encode(PASSWORD)));
        return employee;
    }

    private Client signedIn(Employee employee) {
        Client client = new Client();
        assertThat(client.login(employee.getUsername(), PASSWORD).getStatusCode().value()).isEqualTo(200);
        return client;
    }

    private void assertError(ResponseEntity<JsonNode> response, int status, String code) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody().path("code").asText()).isEqualTo(code);
    }

    /** 小型显式Cookie客户端：不自动刷新，不掩盖并发重放和错误响应。 */
    private final class Client {
        private final Map<String,String> cookies = new HashMap<>();
        private String access;
        private ResponseEntity<JsonNode> loginResponse;

        private Client copy() { Client copy = new Client(); copy.cookies.putAll(cookies); copy.access = access; return copy; }
        private String csrf() { return call(HttpMethod.GET, CSRF, null, null, false).getBody().path("token").asText(); }

        private ResponseEntity<JsonNode> login(String username, String password) {
            loginResponse = call(HttpMethod.POST, LOGIN, Map.of("username", username, "password", password), csrf(), false);
            if (loginResponse.getStatusCode().is2xxSuccessful()) access = loginResponse.getBody().path("accessToken").asText();
            return loginResponse;
        }

        private ResponseEntity<JsonNode> call(HttpMethod method, String path, Map<String,String> body, String csrf, boolean bearer) {
            HttpHeaders headers = new HttpHeaders();
            if (!cookies.isEmpty()) headers.set(HttpHeaders.COOKIE, cookies.entrySet().stream()
                    .map(entry -> entry.getKey() + "=" + entry.getValue()).collect(java.util.stream.Collectors.joining("; ")));
            if (bearer && access != null) headers.setBearerAuth(access);
            if (csrf != null) headers.set("X-CSRF-TOKEN", csrf);
            if (body != null) headers.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<JsonNode> response = http.exchange(path, method, new HttpEntity<>(body,headers), JsonNode.class);
            for (String value : response.getHeaders().getOrDefault(HttpHeaders.SET_COOKIE,List.of())) {
                String[] pair = value.split(";",2)[0].split("=",2);
                if (pair.length<2 || pair[1].isEmpty() || value.contains("Max-Age=0")) cookies.remove(pair[0]);
                else cookies.put(pair[0],pair[1]);
            }
            return response;
        }
    }
}
